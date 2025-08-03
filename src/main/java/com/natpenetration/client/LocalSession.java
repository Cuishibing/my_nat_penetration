package com.natpenetration.client;

import com.natpenetration.common.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 本地会话
 * 处理本地连接和数据传输
 */
public class LocalSession {

    private static final Logger logger = LoggerFactory.getLogger(LocalSession.class);

    private final String tunnelId;
    private final SocketChannel localDataChannel;
    private final SocketChannel remoteDataChannel;
    private final NatClient client;
    private final ConcurrentLinkedQueue<ByteBuffer> writeLocalQueue;
    private final ConcurrentLinkedQueue<ByteBuffer> writeRemoteQueue;

    private volatile boolean connected = true;

    public LocalSession(String tunnelId, SocketChannel localDataChannel, SocketChannel remoteDataChannel, NatClient client) {
        this.tunnelId = tunnelId;
        this.localDataChannel = localDataChannel;
        this.remoteDataChannel = remoteDataChannel;
        this.client = client;
        this.writeLocalQueue = new ConcurrentLinkedQueue<>();
        this.writeRemoteQueue = new ConcurrentLinkedQueue<>();
    }

    /**
     * 处理读事件
     */
    public void handleLocalRead() {
        if (!connected) {
            return;
        }
        while (true) {
            try {
                ByteBuffer buffer = ByteBuffer.allocate(Config.BUFFER_SIZE);
                int bytesRead = localDataChannel.read(buffer);

                if (bytesRead == -1) {
                    // 连接已关闭
                    close();
                    return;
                }

                if (bytesRead == 0) {
                    return;
                }

                if (bytesRead > 0) {
                    buffer.flip();
                    // 转发数据到服务器
                    writeRemoteQueue.offer(buffer);
                }

            } catch (IOException e) {
                logger.error("读取本地连接数据失败: {}", tunnelId, e);
                close();
            }
        }
    }

    /**
     * 处理写事件
     */
    public void handleLocalWrite() {
        if (!connected) {
            return;
        }

        try {
            // 处理写队列中的数据
            while (!writeLocalQueue.isEmpty()) {
                ByteBuffer buffer = writeLocalQueue.peek();

                int bytesWritten = localDataChannel.write(buffer);
                if (bytesWritten == 0) {
                    // 缓冲区已满，等待下次写事件
                    break;
                }

                if (!buffer.hasRemaining()) {
                    writeLocalQueue.poll();
                }
            }
        } catch (IOException e) {
            logger.error("写入本地连接数据失败: {}", tunnelId, e);
            close();
        }
    }

    /**
     * 处理读事件
     */
    public void handleRemoteRead() {
        if (!connected) {
            return;
        }

        while (true) {
            try {
                ByteBuffer buffer = ByteBuffer.allocate(Config.BUFFER_SIZE);
                int bytesRead = localDataChannel.read(buffer);

                if (bytesRead == -1) {
                    // 连接已关闭
                    close();
                    return;
                }

                if (bytesRead == 0) {
                    return;
                }

                if (bytesRead > 0) {
                    buffer.flip();
                    // 转发数据到服务器
                    writeLocalQueue.offer(buffer);
                }

            } catch (IOException e) {
                logger.error("读取本地连接数据失败: {}", tunnelId, e);
                close();
            }
        }
    }

    /**
     * 处理写事件
     */
    public void handleRemoteWrite() {
        if (!connected) {
            return;
        }

        try {
            // 处理写队列中的数据
            while (!writeRemoteQueue.isEmpty()) {
                ByteBuffer buffer = writeRemoteQueue.peek();

                int bytesWritten = localDataChannel.write(buffer);
                if (bytesWritten == 0) {
                    // 缓冲区已满，等待下次写事件
                    break;
                }

                if (!buffer.hasRemaining()) {
                    writeRemoteQueue.poll();
                }
            }
        } catch (IOException e) {
            logger.error("写入本地连接数据失败: {}", tunnelId, e);
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
            logger.info("关闭本地连接: {} -> tunnelId: {}", localDataChannel.getRemoteAddress(), tunnelId);
        } catch (IOException e) {
            logger.info("关闭本地连接: 未知地址 -> tunnelId: {}", tunnelId);
        }

        try {
            if (localDataChannel != null) {
                localDataChannel.close();
            }
            if (remoteDataChannel != null) {
                remoteDataChannel.close();
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

    public boolean isConnected() {
        return connected;
    }
} 