package com.natpenetration.server;

import com.natpenetration.common.Config;
import com.natpenetration.common.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
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
    private final int tunnelOuterPort;

    private ClientSession clientSession;
    private final ConcurrentHashMap<String, TunnelSession> tunnels;
    private volatile boolean clientConnected = false;

    private Selector selector;
    private ServerSocketChannel serverChannel;
    private ServerSocketChannel tunnelOuterChannel;
    private ScheduledExecutorService scheduler;
    private volatile boolean running = false;

    public NatServer() {
        this(Config.SERVER_PORT, Config.TUNNEL_OUTER_DATA_PORT);
    }

    public NatServer(int serverPort, int tunnelOuterPort) {
        this.serverPort = serverPort;
        this.tunnelOuterPort = tunnelOuterPort;
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

            // 启动隧道连接监听
            startTunnelListener();

            // 启动心跳调度器
            scheduler = Executors.newScheduledThreadPool(1);
            scheduler.scheduleAtFixedRate(this::sendHeartbeat, 0, Config.HEARTBEAT_INTERVAL, TimeUnit.MILLISECONDS);

            running = true;
            logger.info("NAT穿透服务端启动成功");
            logger.info("客户端连接端口: {}", serverPort);

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
        tunnelOuterChannel = ServerSocketChannel.open();
        tunnelOuterChannel.configureBlocking(false);
        tunnelOuterChannel.socket().bind(new InetSocketAddress(tunnelOuterPort));
        tunnelOuterChannel.register(selector, SelectionKey.OP_ACCEPT);
        logger.info("开始监听隧道连接，端口: {}", tunnelOuterPort);
    }

    /**
     * 启动客户端数据通道监听
     */
    public ServerSocketChannel startClientDataListener(Object att) throws IOException {
        ServerSocketChannel serverSocketChannel = ServerSocketChannel.open();
        serverSocketChannel.configureBlocking(false);
        serverSocketChannel.socket().bind(new InetSocketAddress(0));
        serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT, att);
        logger.info("开始监听客户端数据通道连接，端口: {}", ((InetSocketAddress) serverSocketChannel.getLocalAddress()).getPort());
        return serverSocketChannel;
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

    record SelectionKeyForTunnelSession(TunnelSession tunnelSession, int customOrClient) {}

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
            this.clientSession = clientSession;
            this.clientConnected = true;

            clientChannel.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE, clientSession);
            logger.info("新的客户端连接: {} -> {}", clientChannel.getRemoteAddress(), clientId);

        } else if (serverSocketChannel == tunnelOuterChannel) {
            // 隧道连接
            String tunnelId = "tunnel_" + System.currentTimeMillis();

            TunnelSession tunnelSession = new TunnelSession(tunnelId, clientChannel, this);
            tunnels.put(tunnelId, tunnelSession);

            clientChannel.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE, new SelectionKeyForTunnelSession(tunnelSession, 1));
            logger.info("新的隧道连接: {} -> {}", clientChannel.getRemoteAddress(), tunnelId);
        } else if (key.attachment() instanceof TunnelSession tunnelSession) {
            tunnelSession.setChannelForClient(clientChannel);
            clientChannel.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE, new SelectionKeyForTunnelSession(tunnelSession, 2));
            logger.info("新的客户端数据连接: {} -> {}", clientChannel.getRemoteAddress(), serverSocketChannel.getLocalAddress());
        }
    }

    /**
     * 处理读事件
     */
    private void handleRead(SelectionKey key) throws IOException {
        Object attachment = key.attachment();

        if (attachment instanceof ClientSession session) {
            session.handleRead();
        } else if (attachment instanceof SelectionKeyForTunnelSession tunnelSessionRecord) {
            if (tunnelSessionRecord.customOrClient == 1) {
                tunnelSessionRecord.tunnelSession.handleCustomerRead();
            } else if (tunnelSessionRecord.customOrClient == 2) {
                tunnelSessionRecord.tunnelSession.handleClientRead();
            }
        }
    }

    /**
     * 处理写事件
     */
    private void handleWrite(SelectionKey key) throws IOException {
        Object attachment = key.attachment();

        if (attachment instanceof ClientSession clientSession) {
            clientSession.handleWrite();
        } else if (attachment instanceof SelectionKeyForTunnelSession tunnelSessionRecord) {
            if (tunnelSessionRecord.customOrClient == 1) {
                tunnelSessionRecord.tunnelSession.handleCustomerWrite();
            } else if (tunnelSessionRecord.customOrClient == 2) {
                tunnelSessionRecord.tunnelSession.handleClientWrite();
            }
        }
    }

    public void sendClientMessage(Message msg) throws IOException {
        if (clientSession == null) {
            logger.warn("尝试发送消息但客户端会话未连接: {}", msg.getType());
            return;
        }
        clientSession.sendMessage(msg.toByteBuffer());
    }

    /**
     * 发送心跳
     */
    private void sendHeartbeat() {
        if (clientSession == null || !clientConnected) {
            logger.debug("跳过心跳发送，客户端会话未连接");
            return;
        }
        try {
            Message heartbeat = new Message(Message.Type.HEARTBEAT, clientSession.getClientId(), null, null);
            ByteBuffer buffer = heartbeat.toByteBuffer();
            clientSession.sendMessage(buffer);
        } catch (IOException e) {
            logger.error("发送心跳失败: {}", clientSession.getClientId(), e);
        }
    }

    /**
     * 处理客户端断开连接
     */
    public void onClientDisconnected() {
        clientConnected = false;
        clientSession = null;
        logger.info("客户端已断开连接，服务端继续运行等待新的客户端连接");
        
        // 清理所有隧道连接
        tunnels.values().forEach(TunnelSession::close);
        tunnels.clear();
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
        clientSession.close();

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
            if (tunnelOuterChannel != null) {
                tunnelOuterChannel.close();
            }
        } catch (IOException e) {
            logger.error("关闭服务端资源时发生错误", e);
        }

        logger.info("服务端已停止");
    }

    /**
     * 获取选择器
     */
    public Selector getSelector() {
        return selector;
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