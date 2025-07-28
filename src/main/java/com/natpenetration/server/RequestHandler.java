package com.natpenetration.server;

import com.natpenetration.common.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.Socket;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 请求处理器
 * 负责处理外部请求并转发给对应的客户端
 */
public class RequestHandler {
    
    private static final Logger logger = LoggerFactory.getLogger(RequestHandler.class);
    
    private final NatServer server;
    private final ConcurrentHashMap<String, Socket> localServices;
    private final ConcurrentHashMap<String, Socket> requestConnections;
    private final ExecutorService executorService;
    
    public RequestHandler(NatServer server) {
        this.server = server;
        this.localServices = new ConcurrentHashMap<>();
        this.requestConnections = new ConcurrentHashMap<>();
        this.executorService = Executors.newVirtualThreadPerTaskExecutor();
    }
    
    /**
     * 处理外部请求
     */
    public void handleRequest(Socket requestSocket) {
        try {
            logger.info("处理外部请求: {}", requestSocket.getInetAddress());
            
            // 获取可用的客户端
            ClientHandler client = getAvailableClient();
            if (client == null) {
                logger.warn("没有可用的客户端连接");
                sendErrorResponse(requestSocket, "No available client");
                return;
            }
            
            // 建立双向数据流
            establishBidirectionalTunnel(requestSocket, client);
            
        } catch (IOException e) {
            logger.error("处理外部请求时发生错误", e);
            try {
                sendErrorResponse(requestSocket, "Internal server error");
            } catch (IOException ex) {
                logger.error("发送错误响应失败", ex);
            }
        } finally {
            try {
                requestSocket.close();
            } catch (IOException e) {
                logger.error("关闭请求套接字失败", e);
            }
        }
    }
    
    /**
     * 建立双向隧道
     */
    private void establishBidirectionalTunnel(Socket requestSocket, ClientHandler client) {
        // 创建请求ID用于标识这个连接
        String requestId = "req_" + System.currentTimeMillis() + "_" + Thread.currentThread().getId();
        
        // 注册这个请求连接
        registerRequestConnection(requestId, requestSocket);
        
        // 通知客户端建立到本地服务的连接
        client.establishTunnel(requestId);
        
        // 启动双向数据转发
        startBidirectionalForwarding(requestId, requestSocket, client);
    }
    
    /**
     * 启动双向数据转发
     */
    private void startBidirectionalForwarding(String requestId, Socket requestSocket, ClientHandler client) {
        // 从外部请求读取数据并转发给客户端
        executorService.submit(() -> {
            try {
                InputStream in = requestSocket.getInputStream();
                byte[] buffer = new byte[Config.BUFFER_SIZE];
                int bytesRead;
                
                while ((bytesRead = in.read(buffer)) != -1) {
                    byte[] data = new byte[bytesRead];
                    System.arraycopy(buffer, 0, data, 0, bytesRead);
                    
                    // 发送数据给客户端
                    client.sendTunnelData(requestId, data);
                    
                    if (Config.DEBUG_MODE) {
                        logger.debug("转发外部请求数据到客户端，长度: {}", bytesRead);
                    }
                }
            } catch (IOException e) {
                logger.error("读取外部请求数据失败", e);
            } finally {
                // 清理连接
                unregisterRequestConnection(requestId);
            }
        });
    }
    
    /**
     * 转发数据到本地服务
     */
    public void forwardToLocalService(String clientId, byte[] data) {
        Socket localService = localServices.get(clientId);
        if (localService != null && !localService.isClosed()) {
            try {
                OutputStream out = localService.getOutputStream();
                out.write(data);
                out.flush();
                
                if (Config.DEBUG_MODE) {
                    logger.debug("数据已转发到本地服务 {}，数据长度: {}", clientId, data.length);
                }
            } catch (IOException e) {
                logger.error("转发数据到本地服务失败", e);
                localServices.remove(clientId);
            }
        } else {
            logger.warn("本地服务 {} 不可用", clientId);
        }
    }
    
    /**
     * 读取请求数据
     */
    private byte[] readRequestData(Socket socket) throws IOException {
        InputStream in = socket.getInputStream();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        
        byte[] data = new byte[Config.BUFFER_SIZE];
        int bytesRead;
        
        while ((bytesRead = in.read(data)) != -1) {
            buffer.write(data, 0, bytesRead);
            
            // 检查是否超过最大消息大小
            if (buffer.size() > Config.MAX_MESSAGE_SIZE) {
                logger.warn("请求数据超过最大大小限制");
                break;
            }
        }
        
        return buffer.toByteArray();
    }
    
    /**
     * 获取可用的客户端
     */
    private ClientHandler getAvailableClient() {
        return server.getClients().values().stream()
                .filter(ClientHandler::isAlive)
                .findFirst()
                .orElse(null);
    }
    
    /**
     * 发送成功响应
     */
    private void sendSuccessResponse(Socket socket, String message) throws IOException {
        String response = "HTTP/1.1 200 OK\r\n" +
                         "Content-Type: text/plain\r\n" +
                         "Content-Length: " + message.length() + "\r\n" +
                         "\r\n" +
                         message;
        
        OutputStream out = socket.getOutputStream();
        out.write(response.getBytes());
        out.flush();
    }
    
    /**
     * 发送错误响应
     */
    private void sendErrorResponse(Socket socket, String message) throws IOException {
        String response = "HTTP/1.1 500 Internal Server Error\r\n" +
                         "Content-Type: text/plain\r\n" +
                         "Content-Length: " + message.length() + "\r\n" +
                         "\r\n" +
                         message;
        
        OutputStream out = socket.getOutputStream();
        out.write(response.getBytes());
        out.flush();
    }
    
    /**
     * 注册请求连接
     */
    public void registerRequestConnection(String requestId, Socket requestSocket) {
        requestConnections.put(requestId, requestSocket);
        logger.info("注册请求连接: {}", requestId);
    }
    
    /**
     * 注销请求连接
     */
    public void unregisterRequestConnection(String requestId) {
        requestConnections.remove(requestId);
        logger.info("注销请求连接: {}", requestId);
    }
    
    /**
     * 获取请求连接
     */
    public Socket getRequestConnection(String requestId) {
        return requestConnections.get(requestId);
    }
    
    /**
     * 注册本地服务
     */
    public void registerLocalService(String clientId, Socket localService) {
        localServices.put(clientId, localService);
        logger.info("注册本地服务: {}", clientId);
    }
    
    /**
     * 注销本地服务
     */
    public void unregisterLocalService(String clientId) {
        localServices.remove(clientId);
        logger.info("注销本地服务: {}", clientId);
    }
} 