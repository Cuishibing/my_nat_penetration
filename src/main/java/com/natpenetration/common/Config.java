package com.natpenetration.common;

/**
 * NAT穿透配置
 */
public class Config {
    // 服务端配置
    public static final int SERVER_PORT = 8080;           // 服务端监听端口
    public static final int TUNNEL_PORT = 8081;           // 隧道端口
    
    // 客户端配置
    public static final int LOCAL_SERVICE_PORT = 8082;     // 本地服务端口
    
    // 缓冲区配置
    public static final int BUFFER_SIZE = 8192;           // 缓冲区大小
    
    // 连接配置
    public static final int CONNECTION_TIMEOUT = 30000;   // 连接超时时间(毫秒)
    public static final int HEARTBEAT_INTERVAL = 30000;   // 心跳间隔(毫秒)
    
    // 服务器地址
    public static final String SERVER_HOST = "localhost"; // 服务器地址
} 