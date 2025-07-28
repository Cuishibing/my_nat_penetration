package com.natpenetration.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.natpenetration.common.Config;
import com.natpenetration.common.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 客户端处理器
 * 负责处理单个客户端的连接、消息传输和心跳检测
 */
public class ClientHandler implements Runnable {
    
    private static final Logger logger = LoggerFactory.getLogger(ClientHandler.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    private final Socket clientSocket;
    private final NatServer server;
    private final BufferedReader reader;
    private final PrintWriter writer;
    private final AtomicBoolean running;
    
    private String clientId;
    private long lastHeartbeat;
    
    public ClientHandler(Socket clientSocket, NatServer server) throws IOException {
        this.clientSocket = clientSocket;
        this.server = server;
        this.reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
        this.writer = new PrintWriter(clientSocket.getOutputStream(), true);
        this.running = new AtomicBoolean(true);
        this.lastHeartbeat = System.currentTimeMillis();
    }
    
    @Override
    public void run() {
        try {
            logger.info("开始处理客户端连接: {}", clientSocket.getInetAddress());
            
            // 设置连接超时
            clientSocket.setSoTimeout(Config.CONNECTION_TIMEOUT);
            
            // 处理客户端消息
            String line;
            while (running.get() && (line = reader.readLine()) != null) {
                try {
                    Message message = objectMapper.readValue(line, Message.class);
                    handleMessage(message);
                } catch (Exception e) {
                    logger.error("解析客户端消息失败: {}", line, e);
                }
            }
            
        } catch (IOException e) {
            if (running.get()) {
                logger.error("处理客户端连接时发生错误", e);
            }
        } finally {
            disconnect();
        }
    }
    
    /**
     * 处理客户端消息
     */
    private void handleMessage(Message message) {
        switch (message.getType()) {
            case CONNECT:
                handleConnect(message);
                break;
            case DATA:
                handleData(message);
                break;
            case DISCONNECT:
                handleDisconnect(message);
                break;
            case HEARTBEAT:
                handleHeartbeat(message);
                break;
            default:
                logger.warn("未知的消息类型: {}", message.getType());
        }
    }
    
    /**
     * 处理连接请求
     */
    private void handleConnect(Message message) {
        this.clientId = message.getClientId();
        server.registerClient(clientId, this);
        
        // 发送连接确认
        Message response = new Message(Message.Type.CONNECT, clientId, "CONNECTED".getBytes());
        sendMessage(response);
        
        logger.info("客户端 {} 连接成功", clientId);
    }
    
    /**
     * 处理数据传输
     */
    private void handleData(Message message) {
        if (clientId == null) {
            logger.warn("客户端未注册，忽略数据消息");
            return;
        }
        
        // 将数据转发给对应的本地服务
        server.getRequestHandler().forwardToLocalService(clientId, message.getData());
        
        if (Config.DEBUG_MODE) {
            logger.debug("转发数据到客户端 {}，数据长度: {}", clientId, message.getData().length);
        }
    }
    
    /**
     * 处理断开连接
     */
    private void handleDisconnect(Message message) {
        logger.info("客户端 {} 请求断开连接", clientId);
        disconnect();
    }
    
    /**
     * 处理心跳包
     */
    private void handleHeartbeat(Message message) {
        lastHeartbeat = System.currentTimeMillis();
        
        // 发送心跳响应
        Message response = new Message(Message.Type.HEARTBEAT, clientId, "PONG".getBytes());
        sendMessage(response);
        
        if (Config.DEBUG_MODE) {
            logger.debug("收到客户端 {} 的心跳包", clientId);
        }
    }
    
    /**
     * 发送消息给客户端
     */
    public void sendMessage(Message message) {
        if (!running.get()) {
            return;
        }
        
        try {
            String jsonMessage = objectMapper.writeValueAsString(message);
            writer.println(jsonMessage);
            
            if (Config.DEBUG_MODE) {
                logger.debug("发送消息给客户端 {}: {}", clientId, message.getType());
            }
        } catch (Exception e) {
            logger.error("发送消息给客户端失败", e);
            disconnect();
        }
    }
    
    /**
     * 发送数据给客户端
     */
    public void sendData(byte[] data) {
        Message message = new Message(Message.Type.DATA, clientId, data);
        sendMessage(message);
    }
    
    /**
     * 建立隧道
     */
    public void establishTunnel(String requestId) {
        Message message = new Message(Message.Type.CONNECT, clientId, requestId.getBytes());
        sendMessage(message);
        logger.info("通知客户端建立隧道: {}", requestId);
    }
    
    /**
     * 发送隧道数据
     */
    public void sendTunnelData(String requestId, byte[] data) {
        // 将请求ID和数据一起发送
        String tunnelData = requestId + ":" + new String(data);
        Message message = new Message(Message.Type.DATA, clientId, tunnelData.getBytes());
        sendMessage(message);
    }
    
    /**
     * 断开连接
     */
    public void disconnect() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        
        logger.info("断开客户端连接: {}", clientId);
        
        // 注销客户端
        if (clientId != null) {
            server.unregisterClient(clientId);
        }
        
        // 关闭套接字
        try {
            if (clientSocket != null && !clientSocket.isClosed()) {
                clientSocket.close();
            }
        } catch (IOException e) {
            logger.error("关闭客户端套接字时发生错误", e);
        }
    }
    
    /**
     * 检查客户端是否活跃
     */
    public boolean isAlive() {
        return running.get() && 
               (System.currentTimeMillis() - lastHeartbeat) < Config.HEARTBEAT_INTERVAL * 2;
    }
    
    /**
     * 获取客户端ID
     */
    public String getClientId() {
        return clientId;
    }
    
    /**
     * 获取最后心跳时间
     */
    public long getLastHeartbeat() {
        return lastHeartbeat;
    }
} 