package com.natpenetration.server;

import com.natpenetration.common.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 隧道会话
 * 处理隧道连接和数据转发
 */
public class TunnelSession {
    
    private static final Logger logger = LoggerFactory.getLogger(TunnelSession.class);
    
    private final String tunnelId;
    private final SocketChannel channel;
    private final NatServer server;
    private final ConcurrentLinkedQueue<ByteBuffer> writeQueue;
    
    private ByteBuffer readBuffer;
    private ByteBuffer writeBuffer;
    private volatile boolean connected = true;
    
    public TunnelSession(String tunnelId, SocketChannel channel, NatServer server) {
        this.tunnelId = tunnelId;
        this.channel = channel;
        this.server = server;
        this.writeQueue = new ConcurrentLinkedQueue<>();
        this.readBuffer = ByteBuffer.allocate(Config.BUFFER_SIZE);
        this.writeBuffer = ByteBuffer.allocate(Config.BUFFER_SIZE);
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
                // 直接转发数据到所有客户端
                forwardToAllClients();
            }
            
        } catch (IOException e) {
            logger.error("读取隧道数据失败: {}", tunnelId, e);
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
                channel.keyFor(server.getSelector()).interestOps(java.nio.channels.SelectionKey.OP_READ);
            }
            
        } catch (IOException e) {
            logger.error("写入隧道数据失败: {}", tunnelId, e);
            close();
        }
    }
    
    /**
     * 转发数据到所有客户端
     */
    private void forwardToAllClients() {
        byte[] data = new byte[readBuffer.remaining()];
        readBuffer.get(data);
        
        server.getClients().values().forEach(client -> {
            try {
                client.forwardToClient(data);
            } catch (Exception e) {
                logger.error("转发数据到客户端失败: {}", client.getClientId(), e);
            }
        });
    }
    
    /**
     * 转发数据到隧道
     */
    public void forwardToTunnel(byte[] data) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(data);
            writeQueue.offer(buffer);
            
            // 注册写事件监听
            channel.keyFor(server.getSelector()).interestOps(java.nio.channels.SelectionKey.OP_READ | java.nio.channels.SelectionKey.OP_WRITE);
            
        } catch (Exception e) {
            logger.error("转发数据到隧道失败: {}", tunnelId, e);
            close();
        }
    }
    
    /**
     * 转发数据到客户端（从客户端到隧道）
     */
    public void forwardToClient(byte[] data) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(data);
            writeQueue.offer(buffer);
            
            // 注册写事件监听
            channel.keyFor(server.getSelector()).interestOps(java.nio.channels.SelectionKey.OP_READ | java.nio.channels.SelectionKey.OP_WRITE);
            
        } catch (Exception e) {
            logger.error("转发数据到客户端失败: {}", tunnelId, e);
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
        logger.info("关闭隧道连接: {}", tunnelId);
        
        try {
            if (channel != null) {
                channel.close();
            }
        } catch (IOException e) {
            logger.error("关闭隧道连接失败: {}", tunnelId, e);
        }
        
        server.removeTunnel(tunnelId);
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