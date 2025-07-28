package com.natpenetration.client;

import com.natpenetration.common.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * NAT穿透客户端
 * 负责：
 * 1. 连接服务端
 * 2. 建立隧道
 * 3. 转发本地服务请求
 */
public class NatClient {
    
    private static final Logger logger = LoggerFactory.getLogger(NatClient.class);
    
    private final String serverHost;
    private final int serverPort;
    private final String clientId;
    private final String localHost;
    private final int localPort;
    
    private Socket serverSocket;
    private TunnelHandler tunnelHandler;
    private final AtomicBoolean running;
    
    public NatClient(String clientId, String localHost, int localPort) {
        this(Config.SERVER_HOST, Config.SERVER_PORT, clientId, localHost, localPort);
    }
    
    public NatClient(String serverHost, int serverPort, String clientId, String localHost, int localPort) {
        this.serverHost = serverHost;
        this.serverPort = serverPort;
        this.clientId = clientId;
        this.localHost = localHost;
        this.localPort = localPort;
        this.running = new AtomicBoolean(false);
    }
    
    /**
     * 启动客户端
     */
    public void start() {
        if (running.compareAndSet(false, true)) {
            logger.info("启动NAT穿透客户端");
            logger.info("客户端ID: {}", clientId);
            logger.info("服务端地址: {}:{}", serverHost, serverPort);
            logger.info("本地服务地址: {}:{}", localHost, localPort);
            
            connectToServer();
        } else {
            logger.warn("客户端已经在运行中");
        }
    }
    
    /**
     * 连接服务端
     */
    private void connectToServer() {
        int reconnectAttempts = 0;
        
        while (running.get() && reconnectAttempts < Config.CLIENT_MAX_RECONNECT_ATTEMPTS) {
            try {
                logger.info("尝试连接服务端 {}:{}", serverHost, serverPort);
                
                serverSocket = new Socket(serverHost, serverPort);
                serverSocket.setSoTimeout(Config.CONNECTION_TIMEOUT);
                
                // 创建隧道处理器
                tunnelHandler = new TunnelHandler(serverSocket, this);
                
                // 启动隧道处理
                tunnelHandler.start();
                
                logger.info("成功连接到服务端");
                reconnectAttempts = 0; // 重置重连计数
                
                // 等待隧道处理完成
                tunnelHandler.waitForCompletion();
                
            } catch (IOException e) {
                logger.error("连接服务端失败", e);
                reconnectAttempts++;
                
                if (running.get() && reconnectAttempts < Config.CLIENT_MAX_RECONNECT_ATTEMPTS) {
                    logger.info("{} 秒后尝试重连... (第 {} 次)", 
                              Config.CLIENT_RECONNECT_DELAY / 1000, reconnectAttempts);
                    
                    try {
                        Thread.sleep(Config.CLIENT_RECONNECT_DELAY);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        
        if (reconnectAttempts >= Config.CLIENT_MAX_RECONNECT_ATTEMPTS) {
            logger.error("达到最大重连次数，停止重连");
        }
    }
    
    /**
     * 停止客户端
     */
    public void stop() {
        if (running.compareAndSet(true, false)) {
            logger.info("正在停止客户端...");
            
            // 停止隧道处理
            if (tunnelHandler != null) {
                tunnelHandler.stop();
            }
            
            // 关闭服务端套接字
            try {
                if (serverSocket != null && !serverSocket.isClosed()) {
                    serverSocket.close();
                }
            } catch (IOException e) {
                logger.error("关闭服务端套接字时发生错误", e);
            }
            
            logger.info("客户端已停止");
        }
    }
    
    /**
     * 获取客户端ID
     */
    public String getClientId() {
        return clientId;
    }
    
    /**
     * 获取本地主机地址
     */
    public String getLocalHost() {
        return localHost;
    }
    
    /**
     * 获取本地端口
     */
    public int getLocalPort() {
        return localPort;
    }
    
    /**
     * 检查客户端是否正在运行
     */
    public boolean isRunning() {
        return running.get();
    }
    
    /**
     * 获取隧道处理器
     */
    public TunnelHandler getTunnelHandler() {
        return tunnelHandler;
    }
    
    /**
     * 主方法
     */
    public static void main(String[] args) {
        if (args.length < 3) {
            System.out.println("用法: java NatClient <clientId> <localHost> <localPort>");
            System.out.println("示例: java NatClient client1 localhost 8080");
            System.exit(1);
        }
        
        String clientId = args[0];
        String localHost = args[1];
        int localPort = Integer.parseInt(args[2]);
        
        NatClient client = new NatClient(clientId, localHost, localPort);
        
        // 添加关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(client::stop));
        
        // 启动客户端
        client.start();
    }
} 