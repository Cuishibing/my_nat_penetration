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
import java.util.concurrent.ConcurrentHashMap;
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
    private ServerSocketChannel localChannel;
    private ScheduledExecutorService scheduler;
    private volatile boolean running = false;
    
    // 服务器会话
    private ServerSession serverSession;
    
    // 本地会话映射：tunnelId -> LocalSession
    private final ConcurrentHashMap<String, LocalSession> localSessionMapping;
    
    public NatClient() {
        this(Config.SERVER_HOST, Config.SERVER_PORT, Config.LOCAL_SERVICE_PORT);
    }
    
    public NatClient(String serverHost, int serverPort, int localPort) {
        this.serverHost = serverHost;
        this.serverPort = serverPort;
        this.localPort = localPort;
        this.clientId = "client_" + System.currentTimeMillis();
        this.localSessionMapping = new ConcurrentHashMap<>();
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
        SocketChannel serverChannel = SocketChannel.open();
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
        
        // 创建服务器会话
        serverSession = new ServerSession(clientId, serverChannel, this);
        serverChannel.register(selector, SelectionKey.OP_READ, serverSession);
        logger.info("已连接到服务器: {}:{}", serverHost, serverPort);
        
        // 发送注册消息
        serverSession.sendRegister();
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
        
        // 创建本地会话
        LocalSession localSession = new LocalSession(tunnelId, clientChannel, this);
        clientChannel.register(selector, SelectionKey.OP_READ, localSession);
        
        // 添加到本地会话映射
        localSessionMapping.put(tunnelId, localSession);
        
        logger.info("新的本地连接: {} -> tunnelId: {}", clientChannel.getRemoteAddress(), tunnelId);
    }
    
    /**
     * 处理读事件
     */
    private void handleRead(SelectionKey key) throws IOException {
        Object attachment = key.attachment();
        
        if (attachment instanceof LocalSession) {
            ((LocalSession) attachment).handleRead();
        } else if (attachment instanceof ServerSession) {
            ((ServerSession) attachment).handleRead();
        }
    }
    
    /**
     * 处理写事件
     */
    private void handleWrite(SelectionKey key) throws IOException {
        Object attachment = key.attachment();
        
        if (attachment instanceof LocalSession) {
            ((LocalSession) attachment).handleWrite();
        } else if (attachment instanceof ServerSession) {
            ((ServerSession) attachment).handleWrite();
        }
    }
    

    
    /**
     * 发送心跳
     */
    private void sendHeartbeat() {
        if (serverSession != null && serverSession.isConnected()) {
            serverSession.sendHeartbeat();
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
        
        // 关闭服务器会话
        if (serverSession != null) {
            serverSession.close();
        }
        
        // 关闭选择器
        try {
            if (selector != null) {
                selector.close();
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
    
    public ServerSession getServerSession() {
        return serverSession;
    }
    
    public Selector getSelector() {
        return selector;
    }
    
    /**
     * 获取本地会话
     */
    public LocalSession getLocalSession(String tunnelId) {
        return localSessionMapping.get(tunnelId);
    }
    
    /**
     * 移除本地会话映射
     */
    public void removeLocalSession(String tunnelId) {
        localSessionMapping.remove(tunnelId);
        logger.debug("已移除本地会话映射: {}", tunnelId);
    }
    
    /**
     * 主方法
     */
    public static void main(String[] args) {
        String serverHost = Config.SERVER_HOST;
        int serverPort = Config.SERVER_PORT;
        int localPort = Config.LOCAL_SERVICE_PORT;
        
        // 解析命令行参数
        if (args.length >= 1) {
            serverHost = args[0];
        }
        if (args.length >= 2) {
            try {
                serverPort = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                System.err.println("错误: 服务器端口必须是数字");
                System.exit(1);
            }
        }
        if (args.length >= 3) {
            try {
                localPort = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                System.err.println("错误: 本地端口必须是数字");
                System.exit(1);
            }
        }
        
        // 显示使用说明
        if (args.length == 0) {
            System.out.println("用法: java NatClient [服务器地址] [服务器端口] [本地端口]");
            System.out.println("示例: java NatClient 192.168.1.100 8080 8082");
            System.out.println("默认值: " + Config.SERVER_HOST + ":" + Config.SERVER_PORT + " -> localhost:" + Config.LOCAL_SERVICE_PORT);
        }
        
        System.out.println("启动NAT穿透客户端...");
        System.out.println("服务器地址: " + serverHost + ":" + serverPort);
        System.out.println("本地端口: " + localPort);
        
        NatClient client = new NatClient(serverHost, serverPort, localPort);
        
        // 添加关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(client::stop));
        
        // 启动客户端
        client.start();
    }
}