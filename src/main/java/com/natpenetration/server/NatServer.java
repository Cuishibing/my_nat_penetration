package com.natpenetration.server;

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
 * NAT穿透服务端
 * 基于NIO实现，使用零拷贝技术
 */
public class NatServer {
    
    private static final Logger logger = LoggerFactory.getLogger(NatServer.class);
    
    private final int serverPort;
    private final int tunnelPort;
    private final ConcurrentHashMap<String, ClientSession> clients;
    private final ConcurrentHashMap<String, TunnelSession> tunnels;
    
    private Selector selector;
    private ServerSocketChannel serverChannel;
    private ServerSocketChannel tunnelChannel;
    private ScheduledExecutorService scheduler;
    private volatile boolean running = false;
    
    public NatServer() {
        this(Config.SERVER_PORT, Config.TUNNEL_PORT);
    }
    
    public NatServer(int serverPort, int tunnelPort) {
        this.serverPort = serverPort;
        this.tunnelPort = tunnelPort;
        this.clients = new ConcurrentHashMap<>();
        this.tunnels = new ConcurrentHashMap<>();
    }
    
    /**
     * 启动服务端
     */
    public void start() {
        if (running) {
            logger.warn("服务端已经在运行中");
            return;
        }
        
        try {
            // 初始化选择器
            selector = Selector.open();
            
            // 启动客户端连接监听
            startClientListener();
            
            // 启动隧道监听
            startTunnelListener();
            
            // 启动心跳调度器
            scheduler = Executors.newScheduledThreadPool(1);
            scheduler.scheduleAtFixedRate(this::sendHeartbeat, 0, Config.HEARTBEAT_INTERVAL, TimeUnit.MILLISECONDS);
            
            running = true;
            logger.info("NAT穿透服务端启动成功");
            logger.info("客户端连接端口: {}", serverPort);
            logger.info("隧道端口: {}", tunnelPort);
            
            // 主事件循环
            eventLoop();
            
        } catch (IOException e) {
            logger.error("启动服务端失败", e);
            stop();
        }
    }
    
    /**
     * 启动客户端连接监听
     */
    private void startClientListener() throws IOException {
        serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(false);
        serverChannel.socket().bind(new InetSocketAddress(serverPort));
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);
        logger.info("开始监听客户端连接，端口: {}", serverPort);
    }
    
    /**
     * 启动隧道监听
     */
    private void startTunnelListener() throws IOException {
        tunnelChannel = ServerSocketChannel.open();
        tunnelChannel.configureBlocking(false);
        tunnelChannel.socket().bind(new InetSocketAddress(tunnelPort));
        tunnelChannel.register(selector, SelectionKey.OP_ACCEPT);
        logger.info("开始监听隧道连接，端口: {}", tunnelPort);
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
        
        if (serverSocketChannel == serverChannel) {
            // 客户端连接
            String clientId = "client_" + System.currentTimeMillis();
            ClientSession clientSession = new ClientSession(clientId, clientChannel, this);
            clients.put(clientId, clientSession);
            
            clientChannel.register(selector, SelectionKey.OP_READ, clientSession);
            logger.info("新的客户端连接: {} -> {}", clientChannel.getRemoteAddress(), clientId);
            
        } else if (serverSocketChannel == tunnelChannel) {
            // 隧道连接
            String tunnelId = "tunnel_" + System.currentTimeMillis();
            TunnelSession tunnelSession = new TunnelSession(tunnelId, clientChannel, this);
            tunnels.put(tunnelId, tunnelSession);
            
            clientChannel.register(selector, SelectionKey.OP_READ, tunnelSession);
            logger.info("新的隧道连接: {} -> {}", clientChannel.getRemoteAddress(), tunnelId);
        }
    }
    
    /**
     * 处理读事件
     */
    private void handleRead(SelectionKey key) throws IOException {
        Object attachment = key.attachment();
        
        if (attachment instanceof ClientSession) {
            ((ClientSession) attachment).handleRead();
        } else if (attachment instanceof TunnelSession) {
            ((TunnelSession) attachment).handleRead();
        }
    }
    
    /**
     * 处理写事件
     */
    private void handleWrite(SelectionKey key) throws IOException {
        Object attachment = key.attachment();
        
        if (attachment instanceof ClientSession) {
            ((ClientSession) attachment).handleWrite();
        } else if (attachment instanceof TunnelSession) {
            ((TunnelSession) attachment).handleWrite();
        }
    }
    
    /**
     * 发送心跳
     */
    private void sendHeartbeat() {
        Message heartbeat = new Message(Message.Type.HEARTBEAT, null, null, null);
        ByteBuffer buffer = heartbeat.toByteBuffer();
        
        clients.values().forEach(client -> {
            try {
                client.sendMessage(buffer.duplicate());
            } catch (IOException e) {
                logger.error("发送心跳失败: {}", client.getClientId(), e);
            }
        });
    }
    
    /**
     * 停止服务端
     */
    public void stop() {
        if (!running) {
            return;
        }
        
        running = false;
        logger.info("正在停止服务端...");
        
        // 关闭所有客户端连接
        clients.values().forEach(ClientSession::close);
        clients.clear();
        
        // 关闭所有隧道连接
        tunnels.values().forEach(TunnelSession::close);
        tunnels.clear();
        
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
            if (tunnelChannel != null) {
                tunnelChannel.close();
            }
        } catch (IOException e) {
            logger.error("关闭服务端资源时发生错误", e);
        }
        
        logger.info("服务端已停止");
    }
    
    /**
     * 获取客户端会话
     */
    public ClientSession getClient(String clientId) {
        return clients.get(clientId);
    }
    
    /**
     * 获取隧道会话
     */
    public TunnelSession getTunnel(String tunnelId) {
        return tunnels.get(tunnelId);
    }
    
    /**
     * 移除客户端会话
     */
    public void removeClient(String clientId) {
        clients.remove(clientId);
        logger.info("客户端 {} 已断开连接", clientId);
    }
    
    /**
     * 获取选择器
     */
    public Selector getSelector() {
        return selector;
    }
    
    /**
     * 获取所有客户端
     */
    public ConcurrentHashMap<String, ClientSession> getClients() {
        return clients;
    }
    
    /**
     * 移除隧道会话
     */
    public void removeTunnel(String tunnelId) {
        tunnels.remove(tunnelId);
        logger.info("隧道 {} 已断开连接", tunnelId);
    }
    
    /**
     * 主方法
     */
    public static void main(String[] args) {
        NatServer server = new NatServer();
        
        // 添加关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        
        // 启动服务端
        server.start();
    }
} 