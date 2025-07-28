package com.natpenetration.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.natpenetration.common.Config;
import com.natpenetration.common.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 隧道处理器
 * 负责处理客户端和服务端之间的通信，以及本地服务的转发
 */
public class TunnelHandler {
    
    private static final Logger logger = LoggerFactory.getLogger(TunnelHandler.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    private final Socket serverSocket;
    private final NatClient client;
    private final BufferedReader reader;
    private final PrintWriter writer;
    private final ExecutorService executorService;
    private final AtomicBoolean running;
    private final LocalServiceHandler localServiceHandler;
    
    public TunnelHandler(Socket serverSocket, NatClient client) throws IOException {
        this.serverSocket = serverSocket;
        this.client = client;
        this.reader = new BufferedReader(new InputStreamReader(serverSocket.getInputStream()));
        this.writer = new PrintWriter(serverSocket.getOutputStream(), true);
        // 使用虚拟线程执行器
        this.executorService = Executors.newVirtualThreadPerTaskExecutor();
        this.running = new AtomicBoolean(false);
        this.localServiceHandler = new LocalServiceHandler(client);
    }
    
    /**
     * 启动隧道处理
     */
    public void start() {
        if (running.compareAndSet(false, true)) {
            logger.info("启动隧道处理器");
            
            // 发送连接请求
            sendConnectRequest();
            
            // 启动心跳线程
            startHeartbeatThread();
            
            // 启动消息处理线程
            startMessageHandler();
            
            // 启动本地服务处理器
            localServiceHandler.start();
            
        } else {
            logger.warn("隧道处理器已经在运行中");
        }
    }
    
    /**
     * 发送连接请求
     */
    private void sendConnectRequest() {
        Message connectMessage = new Message(Message.Type.CONNECT, client.getClientId(), null);
        sendMessage(connectMessage);
        logger.info("发送连接请求: {}", client.getClientId());
    }
    
    /**
     * 启动心跳线程
     */
    private void startHeartbeatThread() {
        executorService.submit(() -> {
            while (running.get()) {
                try {
                    Thread.sleep(Config.HEARTBEAT_INTERVAL);
                    
                    if (running.get()) {
                        Message heartbeat = new Message(Message.Type.HEARTBEAT, client.getClientId(), "PING".getBytes());
                        sendMessage(heartbeat);
                        
                        if (Config.DEBUG_MODE) {
                            logger.debug("发送心跳包");
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    logger.error("发送心跳包失败", e);
                    break;
                }
            }
        });
    }
    
    /**
     * 启动消息处理线程
     */
    private void startMessageHandler() {
        executorService.submit(() -> {
            try {
                String line;
                while (running.get() && (line = reader.readLine()) != null) {
                    try {
                        Message message = objectMapper.readValue(line, Message.class);
                        handleMessage(message);
                    } catch (Exception e) {
                        logger.error("解析服务端消息失败: {}", line, e);
                    }
                }
            } catch (IOException e) {
                if (running.get()) {
                    logger.error("读取服务端消息时发生错误", e);
                }
            } finally {
                stop();
            }
        });
    }
    
    /**
     * 处理服务端消息
     */
    private void handleMessage(Message message) {
        switch (message.getType()) {
            case CONNECT:
                handleConnectResponse(message);
                break;
            case DATA:
                handleDataMessage(message);
                break;
            case HEARTBEAT:
                handleHeartbeatResponse(message);
                break;
            case DISCONNECT:
                handleDisconnectMessage(message);
                break;
            default:
                logger.warn("未知的消息类型: {}", message.getType());
        }
    }
    
    /**
     * 处理连接响应
     */
    private void handleConnectResponse(Message message) {
        logger.info("收到连接响应: {}", new String(message.getData()));
    }
    
    /**
     * 处理数据消息
     */
    private void handleDataMessage(Message message) {
        if (message.getData() != null && message.getData().length > 0) {
            String dataStr = new String(message.getData());
            
            // 检查是否是隧道数据（包含请求ID）
            if (dataStr.contains(":")) {
                String[] parts = dataStr.split(":", 2);
                if (parts.length == 2) {
                    String requestId = parts[0];
                    byte[] actualData = parts[1].getBytes();
                    
                    // 处理隧道数据
                    handleTunnelData(requestId, actualData);
                }
            } else {
                // 普通数据转发
                localServiceHandler.forwardToLocalService(message.getData());
                
                if (Config.DEBUG_MODE) {
                    logger.debug("转发数据到本地服务，数据长度: {}", message.getData().length);
                }
            }
        }
    }
    
    /**
     * 处理隧道数据
     */
    private void handleTunnelData(String requestId, byte[] data) {
        // 转发数据到本地服务
        localServiceHandler.forwardToLocalService(data);
        
        if (Config.DEBUG_MODE) {
            logger.debug("处理隧道数据，请求ID: {}, 数据长度: {}", requestId, data.length);
        }
    }
    
    /**
     * 处理心跳响应
     */
    private void handleHeartbeatResponse(Message message) {
        if (Config.DEBUG_MODE) {
            logger.debug("收到心跳响应");
        }
    }
    
    /**
     * 处理断开连接消息
     */
    private void handleDisconnectMessage(Message message) {
        logger.info("收到断开连接消息");
        stop();
    }
    
    /**
     * 发送消息给服务端
     */
    public void sendMessage(Message message) {
        if (!running.get()) {
            return;
        }
        
        try {
            String jsonMessage = objectMapper.writeValueAsString(message);
            writer.println(jsonMessage);
            
            if (Config.DEBUG_MODE) {
                logger.debug("发送消息给服务端: {}", message.getType());
            }
        } catch (Exception e) {
            logger.error("发送消息给服务端失败", e);
            stop();
        }
    }
    
    /**
     * 发送数据给服务端
     */
    public void sendData(byte[] data) {
        Message message = new Message(Message.Type.DATA, client.getClientId(), data);
        sendMessage(message);
    }
    
    /**
     * 停止隧道处理
     */
    public void stop() {
        if (running.compareAndSet(true, false)) {
            logger.info("正在停止隧道处理器...");
            
            // 停止本地服务处理器
            localServiceHandler.stop();
            
            // 发送断开连接消息
            try {
                Message disconnectMessage = new Message(Message.Type.DISCONNECT, client.getClientId(), null);
                sendMessage(disconnectMessage);
            } catch (Exception e) {
                logger.error("发送断开连接消息失败", e);
            }
            
            // 关闭服务端套接字
            try {
                if (serverSocket != null && !serverSocket.isClosed()) {
                    serverSocket.close();
                }
            } catch (IOException e) {
                logger.error("关闭服务端套接字时发生错误", e);
            }
            
            // 关闭线程池
            executorService.shutdown();
            
            logger.info("隧道处理器已停止");
        }
    }
    
    /**
     * 等待完成
     */
    public void waitForCompletion() {
        try {
            while (running.get()) {
                Thread.sleep(1000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * 检查是否正在运行
     */
    public boolean isRunning() {
        return running.get();
    }
}