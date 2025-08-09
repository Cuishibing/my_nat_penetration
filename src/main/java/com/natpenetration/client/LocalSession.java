package com.natpenetration.client;

import com.natpenetration.common.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.nio.channels.SelectionKey;

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

    // 修复：重用ByteBuffer
    private final ByteBuffer localReadBuffer;
    private final ByteBuffer remoteReadBuffer;

    private volatile boolean connected = true;

    public LocalSession(String tunnelId, SocketChannel localDataChannel, SocketChannel remoteDataChannel, NatClient client) {
        this.tunnelId = tunnelId;
        this.localDataChannel = localDataChannel;
        this.remoteDataChannel = remoteDataChannel;
        this.client = client;
        this.writeLocalQueue = new ConcurrentLinkedQueue<>();
        this.writeRemoteQueue = new ConcurrentLinkedQueue<>();
        // 修复：初始化可重用的ByteBuffer
        this.localReadBuffer = ByteBuffer.allocate(Config.BUFFER_SIZE);
        this.remoteReadBuffer = ByteBuffer.allocate(Config.BUFFER_SIZE);
    }

    /**
     * 处理本地读事件
     */
    public void handleLocalRead() {
        if (!connected) {
            return;
        }
        logger.info("local trigger read");
        try {
            // 修复：重用ByteBuffer
            localReadBuffer.clear();
            int bytesRead = localDataChannel.read(localReadBuffer);

            if (bytesRead == -1) {
                // 连接已关闭
                close();
                return;
            }

            if (bytesRead > 0) {
                localReadBuffer.flip();
                // 转发数据到远程服务器
                boolean wasEmpty = writeRemoteQueue.isEmpty();
                writeRemoteQueue.offer(localReadBuffer.duplicate());
                
                // 修复：如果写队列之前为空，现在有数据了，需要重新注册写事件
                if (wasEmpty) {
                    SelectionKey key = remoteDataChannel.keyFor(client.getSelector());
                    if (key != null && key.isValid()) {
                        key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
                    }
                }
            }

        } catch (IOException e) {
            logger.error("读取本地连接数据失败: {}", tunnelId, e);
            close();
        }
    }

    /**
     * 处理本地写事件
     */
    public void handleLocalWrite() {
        if (!connected) {
            return;
        }
        logger.info("local trigger write");
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
            
            // 修复：当写队列为空时，取消写事件监听
            if (writeLocalQueue.isEmpty()) {
                SelectionKey key = localDataChannel.keyFor(client.getSelector());
                if (key != null && key.isValid()) {
                    key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
                }
            }
        } catch (IOException e) {
            logger.error("写入本地连接数据失败: {}", tunnelId, e);
            close();
        }
    }

    /**
     * 处理远程读事件
     */
    public void handleRemoteRead() {
        if (!connected) {
            return;
        }
        logger.info("remote trigger read");
        try {
            // 修复：重用ByteBuffer并读取正确的channel
            remoteReadBuffer.clear();
            int bytesRead = remoteDataChannel.read(remoteReadBuffer);

            if (bytesRead == -1) {
                // 连接已关闭
                close();
                return;
            }

            if (bytesRead > 0) {
                remoteReadBuffer.flip();
                // 转发数据到本地
                boolean wasEmpty = writeLocalQueue.isEmpty();
                writeLocalQueue.offer(remoteReadBuffer.duplicate());
                
                // 修复：如果写队列之前为空，现在有数据了，需要重新注册写事件
                if (wasEmpty) {
                    SelectionKey key = localDataChannel.keyFor(client.getSelector());
                    if (key != null && key.isValid()) {
                        key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
                    }
                }
            }

        } catch (IOException e) {
            logger.error("读取远程连接数据失败: {}", tunnelId, e);
            close();
        }
    }

    /**
     * 处理远程写事件
     */
    public void handleRemoteWrite() {
        if (!connected) {
            return;
        }
        logger.info("remote trigger write");
        try {
            // 处理写队列中的数据
            while (!writeRemoteQueue.isEmpty()) {
                ByteBuffer buffer = writeRemoteQueue.peek();

                int bytesWritten = remoteDataChannel.write(buffer);
                if (bytesWritten == 0) {
                    // 缓冲区已满，等待下次写事件
                    break;
                }

                if (!buffer.hasRemaining()) {
                    writeRemoteQueue.poll();
                }
            }
            
            // 修复：当写队列为空时，取消写事件监听
            if (writeRemoteQueue.isEmpty()) {
                SelectionKey key = remoteDataChannel.keyFor(client.getSelector());
                if (key != null && key.isValid()) {
                    key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
                }
            }
        } catch (IOException e) {
            logger.error("写入远程连接数据失败: {}", tunnelId, e);
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
            // 修复：清理SelectionKey
            if (localDataChannel != null && localDataChannel.isRegistered()) {
                localDataChannel.keyFor(client.getSelector()).cancel();
            }
            if (remoteDataChannel != null && remoteDataChannel.isRegistered()) {
                remoteDataChannel.keyFor(client.getSelector()).cancel();
            }
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