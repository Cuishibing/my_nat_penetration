package com.natpenetration.server;

import com.natpenetration.common.Config;
import com.natpenetration.common.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 隧道会话
 * 处理隧道连接和数据转发
 */
public class TunnelSession {
    
    private static final Logger logger = LoggerFactory.getLogger(TunnelSession.class);
    
    private final String tunnelId;
    private final SocketChannel channelForCustomer; // 用户输入的channel
    private SocketChannel channelForClient;

    private final ServerSocketChannel dataServerSocketChannel;

    private final NatServer server;
    private final ConcurrentLinkedQueue<ByteBuffer> writeToCustomerQueue;
    private final ConcurrentLinkedQueue<ByteBuffer> writeToClientQueue;
    
    private volatile boolean connected = true;
    
    public TunnelSession(String tunnelId, SocketChannel channelForCustomer, NatServer server) throws IOException {
        this.tunnelId = tunnelId;
        this.channelForCustomer = channelForCustomer;
        this.server = server;
        this.writeToCustomerQueue = new ConcurrentLinkedQueue<>();
        this.writeToClientQueue = new ConcurrentLinkedQueue<>();
        this.dataServerSocketChannel = server.startClientDataListener(this);

        this.server.sendClientMessage(new Message(Message.Type.TUNNEL_OPEN, null, null, String.valueOf(((InetSocketAddress) dataServerSocketChannel.getLocalAddress()).getPort()).getBytes()));
    }


    public void setChannelForClient(SocketChannel channelForClient) {
        this.channelForClient = channelForClient;
    }

    /**
     * 处理从用户侧的读事件
     */
    public void handleCustomerRead() {
        if (!connected) {
            return;
        }
        while(true) {
            try {
                ByteBuffer buffer = ByteBuffer.allocate(Config.BUFFER_SIZE);
                int bytesRead = channelForCustomer.read(buffer);

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
                    // 转发数据到客户端
                    writeToClientQueue.offer(buffer);
                }

            } catch (IOException e) {
                logger.error("读取隧道数据失败: {}", tunnelId, e);
                close();
            }
        }
    }
    
    /**
     * 处理写事件
     */
    public void handleCustomerWrite() {
        if (!connected) {
            return;
        }
        
        try {
            // 处理写队列中的数据
            while (!writeToCustomerQueue.isEmpty()) {
                ByteBuffer buffer = writeToCustomerQueue.peek();
                
                int bytesWritten = channelForCustomer.write(buffer);
                if (bytesWritten == 0) {
                    // 缓冲区已满，等待下次写事件
                    break;
                }
                
                if (!buffer.hasRemaining()) {
                    writeToCustomerQueue.poll();
                }
            }
        } catch (IOException e) {
            logger.error("写入隧道数据失败: {}", tunnelId, e);
            close();
        }
    }

    /**
     * 处理从用户侧的读事件
     */
    public void handleClientRead() {
        if (!connected) {
            return;
        }
        while (true) {
            try {
                ByteBuffer buffer = ByteBuffer.allocate(Config.BUFFER_SIZE);
                int bytesRead = channelForClient.read(buffer);

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
                    // 转发数据到用户端
                    writeToCustomerQueue.offer(buffer);
                }

            } catch (IOException e) {
                logger.error("读取隧道数据失败: {}", tunnelId, e);
                close();
            }
        }
    }

    /**
     * 处理写事件
     */
    public void handleClientWrite() {
        if (!connected) {
            return;
        }

        try {
            // 处理写队列中的数据
            while (!writeToClientQueue.isEmpty()) {
                ByteBuffer buffer = writeToClientQueue.peek();

                int bytesWritten = channelForClient.write(buffer);
                if (bytesWritten == 0) {
                    // 缓冲区已满，等待下次写事件
                    break;
                }

                if (!buffer.hasRemaining()) {
                    writeToClientQueue.poll();
                }
            }
        } catch (IOException e) {
            logger.error("写入隧道数据失败: {}", tunnelId, e);
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
            if (channelForCustomer != null) {
                channelForCustomer.close();
            }
        } catch (IOException e) {
            logger.error("关闭隧道连接失败: {}", tunnelId, e);
        }
        
        server.removeTunnel(tunnelId);
    }
    
    public String getTunnelId() {
        return tunnelId;
    }

    public boolean isConnected() {
        return connected;
    }
} 