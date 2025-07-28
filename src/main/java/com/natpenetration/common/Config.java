package com.natpenetration.common;

/**
 * 配置类
 */
public class Config {
    
    // 服务端配置
    public static final int SERVER_PORT = 8888;           // 服务端监听端口
    public static final int TUNNEL_PORT = 9999;           // 隧道端口
    public static final int MAX_CLIENTS = 100;            // 最大客户端连接数
    public static final int HEARTBEAT_INTERVAL = 30000;   // 心跳间隔(毫秒)
    public static final int CONNECTION_TIMEOUT = 60000;   // 连接超时时间(毫秒)
    
    // 客户端配置
    public static final String SERVER_HOST = "localhost"; // 服务端地址
    public static final int CLIENT_RECONNECT_DELAY = 5000; // 重连延迟(毫秒)
    public static final int CLIENT_MAX_RECONNECT_ATTEMPTS = 10; // 最大重连次数
    
    // 缓冲区配置
    public static final int BUFFER_SIZE = 8192;           // 缓冲区大小
    public static final int MAX_MESSAGE_SIZE = 1024 * 1024; // 最大消息大小(1MB)
    
    // 日志配置
    public static final boolean DEBUG_MODE = true;        // 调试模式
} 