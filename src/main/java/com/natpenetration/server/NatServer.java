package com.natpenetration.server;

import com.natpenetration.common.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * NAT穿透服务端
 * 负责：
 * 1. 监听客户端连接
 * 2. 管理客户端隧道
 * 3. 处理外部请求并转发给客户端
 */
public class NatServer {
    
    private static final Logger logger = LoggerFactory.getLogger(NatServer.class);
    
    private final int serverPort;
    private final int tunnelPort;
    private final ExecutorService executorService;
    private final ConcurrentHashMap<String, ClientHandler> clients;
    private final RequestHandler requestHandler;
    
    private ServerSocket serverSocket;
    private ServerSocket tunnelSocket;
    private volatile boolean running = false;
    
    public NatServer() {
        this(Config.SERVER_PORT, Config.TUNNEL_PORT);
    }
    
    public NatServer(int serverPort, int tunnelPort) {
        this.serverPort = serverPort;
        this.tunnelPort = tunnelPort;
        // 使用虚拟线程执行器
        this.executorService = Executors.newVirtualThreadPerTaskExecutor();
        this.clients = new ConcurrentHashMap<>();
        this.requestHandler = new RequestHandler(this);
    }
    
    /**
     * 启动服务端
     */
    public void start() {
        if (running) {
            logger.warn("服务端已经在运行中");
            return;
        }
        
        running = true;
        
        try {
            // 启动客户端连接监听
            startClientListener();
            
            // 启动外部请求监听
            startRequestListener();
            
            logger.info("NAT穿透服务端启动成功");
            logger.info("客户端连接端口: {}", serverPort);
            logger.info("外部请求端口: {}", tunnelPort);
            
        } catch (IOException e) {
            logger.error("启动服务端失败", e);
            stop();
        }
    }
    
    /**
     * 启动客户端连接监听
     */
    private void startClientListener() throws IOException {
        serverSocket = new ServerSocket(serverPort);
        
        executorService.submit(() -> {
            logger.info("开始监听客户端连接，端口: {}", serverPort);
            
            while (running) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    logger.info("新的客户端连接: {}", clientSocket.getInetAddress());
                    
                    ClientHandler clientHandler = new ClientHandler(clientSocket, this);
                    executorService.submit(clientHandler);
                    
                } catch (IOException e) {
                    if (running) {
                        logger.error("接受客户端连接时发生错误", e);
                    }
                }
            }
        });
    }
    
    /**
     * 启动外部请求监听
     */
    private void startRequestListener() throws IOException {
        tunnelSocket = new ServerSocket(tunnelPort);
        
        executorService.submit(() -> {
            logger.info("开始监听外部请求，端口: {}", tunnelPort);
            
            while (running) {
                try {
                    Socket requestSocket = tunnelSocket.accept();
                    logger.info("新的外部请求: {}", requestSocket.getInetAddress());
                    
                    executorService.submit(() -> requestHandler.handleRequest(requestSocket));
                    
                } catch (IOException e) {
                    if (running) {
                        logger.error("接受外部请求时发生错误", e);
                    }
                }
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
        clients.values().forEach(ClientHandler::disconnect);
        clients.clear();
        
        // 关闭服务器套接字
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
            if (tunnelSocket != null && !tunnelSocket.isClosed()) {
                tunnelSocket.close();
            }
        } catch (IOException e) {
            logger.error("关闭服务器套接字时发生错误", e);
        }
        
        // 关闭线程池
        executorService.shutdown();
        
        logger.info("服务端已停止");
    }
    
    /**
     * 注册客户端
     */
    public void registerClient(String clientId, ClientHandler clientHandler) {
        clients.put(clientId, clientHandler);
        logger.info("客户端 {} 已注册，当前连接数: {}", clientId, clients.size());
    }
    
    /**
     * 注销客户端
     */
    public void unregisterClient(String clientId) {
        clients.remove(clientId);
        logger.info("客户端 {} 已注销，当前连接数: {}", clientId, clients.size());
    }
    
    /**
     * 获取客户端处理器
     */
    public ClientHandler getClient(String clientId) {
        return clients.get(clientId);
    }
    
    /**
     * 获取所有客户端
     */
    public ConcurrentHashMap<String, ClientHandler> getClients() {
        return clients;
    }
    
    /**
     * 检查服务端是否正在运行
     */
    public boolean isRunning() {
        return running;
    }
    
    /**
     * 获取请求处理器
     */
    public RequestHandler getRequestHandler() {
        return requestHandler;
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