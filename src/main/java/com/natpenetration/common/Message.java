package com.natpenetration.common;

import java.nio.ByteBuffer;

/**
 * NAT穿透消息协议
 */
public class Message {
    
    public enum Type {
        REGISTER(1),           // 客户端注册
        HEARTBEAT(2),          // 心跳
        TUNNEL_REQUEST(3),     // 隧道请求
        TUNNEL_RESPONSE(4),    // 隧道响应
        DATA(5),               // 数据传输
        ERROR(6);              // 错误
        
        private final int value;
        
        Type(int value) {
            this.value = value;
        }
        
        public int getValue() {
            return value;
        }
        
        public static Type fromValue(int value) {
            for (Type type : values()) {
                if (type.value == value) {
                    return type;
                }
            }
            throw new IllegalArgumentException("Unknown message type: " + value);
        }
    }
    
    private Type type;
    private String clientId;
    private String tunnelId;
    private byte[] data;
    
    public Message() {}
    
    public Message(Type type, String clientId, String tunnelId, byte[] data) {
        this.type = type;
        this.clientId = clientId;
        this.tunnelId = tunnelId;
        this.data = data;
    }
    
    /**
     * 将消息序列化为ByteBuffer
     */
    public ByteBuffer toByteBuffer() {
        int dataLength = data != null ? data.length : 0;
        int clientIdLength = clientId != null ? clientId.getBytes().length : 0;
        int tunnelIdLength = tunnelId != null ? tunnelId.getBytes().length : 0;
        
        // 消息格式: [类型(4字节)][客户端ID长度(4字节)][隧道ID长度(4字节)][数据长度(4字节)][客户端ID][隧道ID][数据]
        int totalLength = 16 + clientIdLength + tunnelIdLength + dataLength;
        ByteBuffer buffer = ByteBuffer.allocate(totalLength);
        
        buffer.putInt(type.getValue());
        buffer.putInt(clientIdLength);
        buffer.putInt(tunnelIdLength);
        buffer.putInt(dataLength);
        
        if (clientId != null) {
            buffer.put(clientId.getBytes());
        }
        if (tunnelId != null) {
            buffer.put(tunnelId.getBytes());
        }
        if (data != null) {
            buffer.put(data);
        }
        
        buffer.flip();
        return buffer;
    }
    
    /**
     * 从ByteBuffer反序列化消息
     */
    public static Message fromByteBuffer(ByteBuffer buffer) {
        Message message = new Message();
        
        message.type = Type.fromValue(buffer.getInt());
        int clientIdLength = buffer.getInt();
        int tunnelIdLength = buffer.getInt();
        int dataLength = buffer.getInt();
        
        if (clientIdLength > 0) {
            byte[] clientIdBytes = new byte[clientIdLength];
            buffer.get(clientIdBytes);
            message.clientId = new String(clientIdBytes);
        }
        
        if (tunnelIdLength > 0) {
            byte[] tunnelIdBytes = new byte[tunnelIdLength];
            buffer.get(tunnelIdBytes);
            message.tunnelId = new String(tunnelIdBytes);
        }
        
        if (dataLength > 0) {
            message.data = new byte[dataLength];
            buffer.get(message.data);
        }
        
        return message;
    }
    
    // Getters and Setters
    public Type getType() {
        return type;
    }
    
    public void setType(Type type) {
        this.type = type;
    }
    
    public String getClientId() {
        return clientId;
    }
    
    public void setClientId(String clientId) {
        this.clientId = clientId;
    }
    
    public String getTunnelId() {
        return tunnelId;
    }
    
    public void setTunnelId(String tunnelId) {
        this.tunnelId = tunnelId;
    }
    
    public byte[] getData() {
        return data;
    }
    
    public void setData(byte[] data) {
        this.data = data;
    }
    
    @Override
    public String toString() {
        return "Message{" +
                "type=" + type +
                ", clientId='" + clientId + '\'' +
                ", tunnelId='" + tunnelId + '\'' +
                ", dataLength=" + (data != null ? data.length : 0) +
                '}';
    }
} 