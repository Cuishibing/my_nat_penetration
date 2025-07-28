package com.natpenetration.common;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 客户端和服务端之间传输的消息结构
 */
public class Message {
    
    public enum Type {
        CONNECT,        // 连接请求
        DATA,           // 数据传输
        DISCONNECT,     // 断开连接
        HEARTBEAT       // 心跳包
    }
    
    @JsonProperty("type")
    private Type type;
    
    @JsonProperty("clientId")
    private String clientId;
    
    @JsonProperty("data")
    private byte[] data;
    
    @JsonProperty("timestamp")
    private long timestamp;
    
    public Message() {
        this.timestamp = System.currentTimeMillis();
    }
    
    public Message(Type type, String clientId, byte[] data) {
        this.type = type;
        this.clientId = clientId;
        this.data = data;
        this.timestamp = System.currentTimeMillis();
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
    
    public byte[] getData() {
        return data;
    }
    
    public void setData(byte[] data) {
        this.data = data;
    }
    
    public long getTimestamp() {
        return timestamp;
    }
    
    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
    
    @Override
    public String toString() {
        return "Message{" +
                "type=" + type +
                ", clientId='" + clientId + '\'' +
                ", dataLength=" + (data != null ? data.length : 0) +
                ", timestamp=" + timestamp +
                '}';
    }
} 