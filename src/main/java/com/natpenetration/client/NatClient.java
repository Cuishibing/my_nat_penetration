 package com.natpenetration.client;

import com.natpenetration.common.Config;
import com.natpenetration.common.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.Iterator;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * NAT穿透客户端
 * 基于NIO实现，使用零拷贝技术
 */
public class NatClient {
    
    private static final Logger logger = LoggerFactory.getLogger(NatClient.class);
    
    private final String serverHost;
    private final int serverPort;
    private final int localPort;
    private final String clientId;
    
    private Selector selector;
    private SocketChannel serverChannel;
    private ServerSocketChannel localChannel;
    private ScheduledExecutorService scheduler;
    private volatile boolean running = false;
    
    // 隧道映射：tunnelId -> LocalConnectionHandler
    private final java.util.concurrent.ConcurrentHashMap<String, LocalConnectionHandler> tunnelMapping;
    
    public NatClient() {
        this(Config.SERVER_HOST, Config.SERVER_PORT, Config.LOCAL_SERVICE_PORT);
    }
    
    public NatClient(String serverHost, int serverPort, int localPort) {
        this.serverHost = serverHost;
        this.serverPort = serverPort;
        this.localPort = localPort;
        this.clientId = "client_" + System.currentTimeMillis();
        this.tunnelMapping = new java.util.concurrent.ConcurrentHashMap<>();
    }
    
    /**
     * 启动客户端
     */
    public void start() {
        if (running) {
            logger.warn("客户端已经在运行中");
            return;
        }
        
        try {
            // 初始化选择器
            selector = Selector.open();
            
            // 连接到服务器
            connectToServer();
            
            // 启动本地服务监听
            startLocalService();
            
            // 启动心跳调度器
            scheduler = Executors.newScheduledThreadPool(1);
            scheduler.scheduleAtFixedRate(this::sendHeartbeat, 0, Config.HEARTBEAT_INTERVAL, TimeUnit.MILLISECONDS);
            
            running = true;
            logger.info("NAT穿透客户端启动成功");
            logger.info("客户端ID: {}", clientId);
            logger.info("本地服务端口: {}", localPort);
            
            // 主事件循环
            eventLoop();
            
        } catch (IOException e) {
            logger.error("启动客户端失败", e);
            stop();
        }
    }
    
    /**
     * 连接到服务器
     */
    private void connectToServer() throws IOException {
        serverChannel = SocketChannel.open();
        serverChannel.configureBlocking(false);
        serverChannel.connect(new InetSocketAddress(serverHost, serverPort));
        
        // 等待连接完成
        while (!serverChannel.finishConnect()) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("连接被中断", e);
            }
        }
        
        serverChannel.register(selector, SelectionKey.OP_READ);
        logger.info("已连接到服务器: {}:{}", serverHost, serverPort);
        
        // 发送注册消息
        sendRegisterMessage();
    }
    
    /**
     * 启动本地服务监听
     */
    private void startLocalService() throws IOException {
        localChannel = ServerSocketChannel.open();
        localChannel.configureBlocking(false);
        localChannel.socket().bind(new InetSocketAddress(localPort));
        localChannel.register(selector, SelectionKey.OP_ACCEPT);
        logger.info("开始监听本地服务，端口: {}", localPort);
    }
    
    /**
     * 发送注册消息
     */
    private void sendRegisterMessage() {
        try {
            Message registerMessage = new Message(Message.Type.REGISTER, clientId, null, null);
            ByteBuffer buffer = registerMessage.toByteBuffer();
            serverChannel.write(buffer);
            logger.info("已发送注册消息");
        } catch (IOException e) {
            logger.error("发送注册消息失败", e);
        }
    }
    
    /**
     * 主事件循环
     */
    private void eventLoop() {
        while (running) {
            try {
                int readyChannels = selector.select(1000);
                if (readyChannels == 0) {
                    continue;
                }
                
                Iterator<SelectionKey> keyIterator = selector.selectedKeys().iterator();
                while (keyIterator.hasNext()) {
                    SelectionKey key = keyIterator.next();
                    keyIterator.remove();
                    
                    if (!key.isValid()) {
                        continue;
                    }
                    
                    if (key.isAcceptable()) {
                        handleAccept(key);
                    } else if (key.isReadable()) {
                        handleRead(key);
                    } else if (key.isWritable()) {
                        handleWrite(key);
                    }
                }
            } catch (IOException e) {
                if (running) {
                    logger.error("事件循环处理错误", e);
                }
            }
        }
    }
    
    /**
     * 处理连接接受事件
     */
    private void handleAccept(SelectionKey key) throws IOException {
        ServerSocketChannel serverSocketChannel = (ServerSocketChannel) key.channel();
        SocketChannel clientChannel = serverSocketChannel.accept();
        clientChannel.configureBlocking(false);
        
        // 为本地连接分配tunnelId
        String tunnelId = "tunnel_" + System.currentTimeMillis() + "_" + clientChannel.hashCode();
        
        // 创建本地连接处理器
        LocalConnectionHandler localHandler = new LocalConnectionHandler(clientChannel, this, tunnelId);
        clientChannel.register(selector, SelectionKey.OP_READ, localHandler);
        
        // 添加到隧道映射
        tunnelMapping.put(tunnelId, localHandler);
        
        logger.info("新的本地连接: {} -> tunnelId: {}", clientChannel.getRemoteAddress(), tunnelId);
    }
    
    /**
     * 处理读事件
     */
    private void handleRead(SelectionKey key) throws IOException {
        Object attachment = key.attachment();
        
        if (attachment instanceof LocalConnectionHandler) {
            ((LocalConnectionHandler) attachment).handleRead();
        } else if (key.channel() == serverChannel) {
            handleServerRead();
        }
    }
    
    /**
     * 处理写事件
     */
    private void handleWrite(SelectionKey key) throws IOException {
        Object attachment = key.attachment();
        
        if (attachment instanceof LocalConnectionHandler) {
            ((LocalConnectionHandler) attachment).handleWrite();
        }
    }
    
    /**
     * 处理服务器读事件
     */
    private void handleServerRead() throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(Config.BUFFER_SIZE);
        int bytesRead = serverChannel.read(buffer);
        
        if (bytesRead == -1) {
            // 连接已关闭
            logger.error("服务器连接已断开");
            stop();
            return;
        }
        
        if (bytesRead > 0) {
            buffer.flip();
            processServerMessage(buffer);
        }
    }
    
    /**
     * 处理服务器消息
     */
    private void processServerMessage(ByteBuffer buffer) {
        try {
            // 确保有足够的数据读取消息头
            if (buffer.remaining() < 16) {
                return;
            }
            
            // 标记当前位置
            buffer.mark();
            
            // 读取消息头
            int type = buffer.getInt();
            int clientIdLength = buffer.getInt();
            int tunnelIdLength = buffer.getInt();
            int dataLength = buffer.getInt();
            
            // 检查是否有完整的消息
            int totalLength = 16 + clientIdLength + tunnelIdLength + dataLength;
            if (buffer.remaining() < totalLength - 16) {
                buffer.reset();
                return;
            }
            
            // 创建消息
            Message message = new Message();
            message.setType(Message.Type.fromValue(type));
            
            if (clientIdLength > 0) {
                byte[] clientIdBytes = new byte[clientIdLength];
                buffer.get(clientIdBytes);
                message.setClientId(new String(clientIdBytes));
            }
            
            if (tunnelIdLength > 0) {
                byte[] tunnelIdBytes = new byte[tunnelIdLength];
                buffer.get(tunnelIdBytes);
                message.setTunnelId(new String(tunnelIdBytes));
            }
            
            if (dataLength > 0) {
                byte[] data = new byte[dataLength];
                buffer.get(data);
                message.setData(data);
            }
            
            // 处理消息
            handleServerMessage(message);
            
            // 处理剩余数据
            if (buffer.hasRemaining()) {
                processServerMessage(buffer);
            }
            
        } catch (Exception e) {
            logger.error("处理服务器消息失败", e);
        }
    }
    
    /**
     * 处理不同类型的服务器消息
     */
    private void handleServerMessage(Message message) {
        switch (message.getType()) {
            case REGISTER:
                logger.info("注册确认: {}", new String(message.getData()));
                break;
            case HEARTBEAT:
                logger.debug("收到心跳");
                break;
            case DATA:
                handleDataMessage(message);
                break;
            default:
                logger.warn("未知消息类型: {}", message.getType());
        }
    }
    
    /**
     * 处理数据消息
     */
    private void handleDataMessage(Message message) {
        // 转发数据到本地连接
        if (message.getData() != null) {
            String tunnelId = message.getTunnelId();
            if (tunnelId != null) {
                // 根据tunnelId找到对应的本地连接
                LocalConnectionHandler handler = tunnelMapping.get(tunnelId);
                if (handler != null && handler.isConnected()) {
                    handler.forwardToLocal(message.getData());
                    logger.debug("已转发数据到隧道 {}，数据长度: {}", tunnelId, message.getData().length);
                } else {
                    logger.warn("隧道 {} 不存在或已断开", tunnelId);
                }
            } else {
                logger.warn("收到数据消息但缺少tunnelId");
            }
        }
    }
    
    /**
     * 发送心跳
     */
    private void sendHeartbeat() {
        try {
            Message heartbeat = new Message(Message.Type.HEARTBEAT, clientId, null, null);
            ByteBuffer buffer = heartbeat.toByteBuffer();
            serverChannel.write(buffer);
        } catch (IOException e) {
            logger.error("发送心跳失败", e);
        }
    }
    
    /**
     * 停止客户端
     */
    public void stop() {
        if (!running) {
            return;
        }
        
        running = false;
        logger.info("正在停止客户端...");
        
        // 关闭调度器
        if (scheduler != null) {
            scheduler.shutdown();
        }
        
        // 关闭选择器
        try {
            if (selector != null) {
                selector.close();
            }
            if (serverChannel != null) {
                serverChannel.close();
            }
            if (localChannel != null) {
                localChannel.close();
            }
        } catch (IOException e) {
            logger.error("关闭客户端资源时发生错误", e);
        }
        
        logger.info("客户端已停止");
    }
    
    // Getters
    public String getClientId() {
        return clientId;
    }
    
    public SocketChannel getServerChannel() {
        return serverChannel;
    }
    
    public Selector getSelector() {
        return selector;
    }
    
    /**
     * 移除隧道映射
     */
    public void removeTunnelMapping(String tunnelId) {
        tunnelMapping.remove(tunnelId);
        logger.debug("已移除隧道映射: {}", tunnelId);
    }
    
    /**
     * 主方法
     */
    public static void main(String[] args) {
        NatClient client = new NatClient();
        
        // 添加关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(client::stop));
        
        // 启动客户端
        client.start();
    }
}