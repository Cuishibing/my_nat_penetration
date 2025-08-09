package com.natpenetration.client;

import com.natpenetration.common.Config;
import com.natpenetration.common.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.nio.channels.SelectionKey;

/**
 * 服务器会话
 * 处理与服务器的连接和消息传输
 */
public class ServerSession {

    private static final Logger logger = LoggerFactory.getLogger(ServerSession.class);

    private final String clientId;
    private final SocketChannel channel;
    private final NatClient client;
    private final ConcurrentLinkedQueue<ByteBuffer> writeQueue;

    private final ByteBuffer readBuffer;
    private volatile boolean connected = true;

    public ServerSession(String clientId, SocketChannel channel, NatClient client) {
        this.clientId = clientId;
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
        logger.info("client trigger read");
        try {
            int bytesRead = channel.read(readBuffer);

            if (bytesRead == -1) {
                // 连接已关闭
                close();
                return;
            }

            if (bytesRead > 0) {
                readBuffer.flip();
                processMessage();
            }

        } catch (IOException e) {
            logger.error("读取服务器数据失败: {}", clientId, e);
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
        logger.info("client trigger write");
        try {
            // 处理写队列中的数据
            while (!writeQueue.isEmpty()) {
                ByteBuffer buffer = writeQueue.peek();

                int bytesWritten = channel.write(buffer);

                logger.info("client send control msg success");

                if (bytesWritten == 0) {
                    // 缓冲区已满，等待下次写事件
                    break;
                }

                if (!buffer.hasRemaining()) {
                    writeQueue.poll();
                }
            }
            
            // 修复：当写队列为空时，取消写事件监听
            if (writeQueue.isEmpty()) {
                SelectionKey key = channel.keyFor(client.getSelector());
                if (key != null && key.isValid()) {
                    key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
                }
            }
        } catch (IOException e) {
            logger.error("写入服务器数据失败: {}", clientId, e);
            close();
        }
    }

    /**
     * 处理接收到的消息
     */
    private void processMessage() {
        try {
            // 确保有足够的数据读取消息头
            if (readBuffer.remaining() < 16) {
                return;
            }

            // 标记当前位置
            readBuffer.mark();

            // 读取消息头
            int type = readBuffer.getInt();
            int clientIdLength = readBuffer.getInt();
            int tunnelIdLength = readBuffer.getInt();
            int dataLength = readBuffer.getInt();

            // 检查是否有完整的消息
            int totalLength = 16 + clientIdLength + tunnelIdLength + dataLength;
            if (readBuffer.remaining() < totalLength - 16) {
                readBuffer.reset();
                readBuffer.position(readBuffer.limit());
                readBuffer.limit(readBuffer.capacity());
                return;
            }

            // 创建消息
            Message message = new Message();
            message.setType(Message.Type.fromValue(type));

            if (clientIdLength > 0) {
                byte[] clientIdBytes = new byte[clientIdLength];
                readBuffer.get(clientIdBytes);
                message.setClientId(new String(clientIdBytes));
            }

            if (tunnelIdLength > 0) {
                byte[] tunnelIdBytes = new byte[tunnelIdLength];
                readBuffer.get(tunnelIdBytes);
                message.setTunnelId(new String(tunnelIdBytes));
            }

            if (dataLength > 0) {
                byte[] data = new byte[dataLength];
                readBuffer.get(data);
                message.setData(data);
            }

            // 处理消息
            handleMessage(message);
            readBuffer.clear();
        } catch (Exception e) {
            logger.error("处理消息失败: {}", clientId, e);
        }
    }

    /**
     * 处理不同类型的消息
     */
    private void handleMessage(Message message) {
        switch (message.getType()) {
            case REGISTER:
                handleRegister(message);
                break;
            case HEARTBEAT:
                handleHeartbeat(message);
                break;
            case TUNNEL_OPEN: {
                int remoteDataPort = Integer.parseInt(new String(message.getData()));
                logger.info("收到 TUNNEL_OPEN 消息， 端口为:{}", remoteDataPort);
                try {
                    client.connectToLocalServer(remoteDataPort);
                } catch (IOException e) {
                    logger.error("连接到本地服务失败，端口: {}, 错误: {}", remoteDataPort, e.getMessage(),e);
                    // 不要抛出RuntimeException，而是记录错误并继续
                    // 这样可以避免客户端因为连接失败而完全停止
                }
            }
            break;
            default:
                logger.warn("未知消息类型: {}", message.getType());
        }
    }

    /**
     * 处理注册消息
     */
    private void handleRegister(Message message) {
        logger.info("注册确认: {}", new String(message.getData()));
    }

    /**
     * 处理心跳消息
     */
    private void handleHeartbeat(Message message) {
        logger.debug("收到心跳确认：msg {}", message);
    }

    /**
     * 发送消息
     */
    public void sendMessage(ByteBuffer buffer) throws IOException {
        if (!connected) {
            throw new IOException("连接已断开");
        }

        boolean wasEmpty = writeQueue.isEmpty();
        writeQueue.offer(buffer.duplicate());
        
        // 修复：如果写队列之前为空，现在有数据了，需要重新注册写事件
        if (wasEmpty) {
            SelectionKey key = channel.keyFor(client.getSelector());
            if (key != null && key.isValid()) {
                key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
            }
        }
    }

    /**
     * 发送心跳
     */
    public void sendHeartbeat() {
        try {
            Message heartbeat = new Message(Message.Type.HEARTBEAT, clientId, null, null);
            sendMessage(heartbeat.toByteBuffer());
        } catch (IOException e) {
            logger.error("发送心跳失败", e);
        }
    }

    /**
     * 发送注册消息
     */
    public void sendRegister() {
        try {
            Message registerMessage = new Message(Message.Type.REGISTER, clientId, null, null);
            sendMessage(registerMessage.toByteBuffer());
            logger.info("已发送注册消息");
        } catch (IOException e) {
            logger.error("发送注册消息失败", e);
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
        logger.info("关闭服务器连接: {}", clientId);

        try {
            // 修复：清理SelectionKey
            if (channel != null && channel.isRegistered()) {
                channel.keyFor(client.getSelector()).cancel();
            }
            if (channel != null) {
                channel.close();
            }
        } catch (IOException e) {
            logger.error("关闭服务器连接失败: {}", clientId, e);
        }

        // 不要直接停止客户端，而是通知连接断开
        client.onConnectionLost();
    }

    // Getters
    public String getClientId() {
        return clientId;
    }

    public boolean isConnected() {
        return connected;
    }
} 