package com.natpenetration.client;

import com.natpenetration.common.Config;
import com.natpenetration.common.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.channels.SelectionKey;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 本地会话
 * 处理本地连接和数据传输
 */
public class LocalSession {
    
    private static final Logger logger = LoggerFactory.getLogger(LocalSession.class);
    
    private final String tunnelId;
    private final SocketChannel channel;
    private final NatClient client;
    private final ConcurrentLinkedQueue<ByteBuffer> writeQueue;
    
    private ByteBuffer readBuffer;
    private volatile boolean connected = true;
    
    public LocalSession(String tunnelId, SocketChannel channel, NatClient client) {
        this.tunnelId = tunnelId;
        this.channel = channel;
        this.client = client;
        this.writeQueue = new ConcurrentLinkedQueue<>();
        this.readBuffer = ByteBuffer.allocate(Config.BUFFER_SIZE);
    }
    
    /**
     * 处理读事件
     */
    public void handleRead() {
        if (!connected) {
            return;
        }
        
        try {
            readBuffer.clear();
            int bytesRead = channel.read(readBuffer);
            
            if (bytesRead == -1) {
                // 连接已关闭
                close();
                return;
            }
            
            if (bytesRead > 0) {
                readBuffer.flip();
                // 转发数据到服务器
                forwardToServer();
            }
            
        } catch (IOException e) {
            logger.error("读取本地连接数据失败: {}", tunnelId, e);
            close();
        }
    }
    
    /**
     * 处理写事件
     */
    public void handleWrite() {
        if (!connected) {
            return;
        }
        
        try {
            // 处理写队列中的数据
            while (!writeQueue.isEmpty()) {
                ByteBuffer buffer = writeQueue.peek();
                
                int bytesWritten = channel.write(buffer);
                if (bytesWritten == 0) {
                    // 缓冲区已满，等待下次写事件
                    break;
                }
                
                if (!buffer.hasRemaining()) {
                    writeQueue.poll();
                }
            }
            
            // 如果没有更多数据要写，取消写事件监听
            if (writeQueue.isEmpty()) {
                channel.keyFor(client.getSelector()).interestOps(SelectionKey.OP_READ);
            }
            
        } catch (IOException e) {
            logger.error("写入本地连接数据失败: {}", tunnelId, e);
            close();
        }
    }
    
    /**
     * 转发数据到服务器
     */
    private void forwardToServer() {
        byte[] data = new byte[readBuffer.remaining()];
        readBuffer.get(data);

        // 创建数据消息并发送到服务器，包含tunnelId
        Message message = new Message(Message.Type.DATA, client.getClientId(), tunnelId, data);
        client.getServerSession().forwardToServer(message);

    }
    
    /**
     * 转发数据到本地连接
     */
    public void forwardToLocal(byte[] data) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(data);
            writeQueue.offer(buffer);
            
            // 注册写事件监听
            channel.keyFor(client.getSelector()).interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
            
        } catch (Exception e) {
            logger.error("转发数据到本地连接失败: {}", tunnelId, e);
            close();
        }
    }
    
    /**
     * 关闭连接
     */
    public void close() {
        if (!connected) {
            return;
        }
        
        connected = false;
        try {
            logger.info("关闭本地连接: {} -> tunnelId: {}", channel.getRemoteAddress(), tunnelId);
        } catch (IOException e) {
            logger.info("关闭本地连接: 未知地址 -> tunnelId: {}", tunnelId);
        }
        
        try {
            if (channel != null) {
                channel.close();
            }
        } catch (IOException e) {
            logger.error("关闭本地连接失败: {}", tunnelId, e);
        }
        
        // 从隧道映射中移除
        client.removeLocalSession(tunnelId);
    }
    
    // Getters
    public String getTunnelId() {
        return tunnelId;
    }
    
    public SocketChannel getChannel() {
        return channel;
    }
    
    public boolean isConnected() {
        return connected;
    }
} 