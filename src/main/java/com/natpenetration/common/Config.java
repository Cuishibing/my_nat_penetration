package com.natpenetration.common;

/**
 * NAT穿透配置
 */
public class Config {
    // 服务端配置
    public static final int SERVER_PORT = 8080;           // 服务端监听端口

    // 对外隧道配置
    public static final int TUNNEL_OUTER_DATA_PORT = 8081;
    
    // 客户端配置
    public static final int LOCAL_SERVICE_PORT = 8082;     // 本地服务端口
    
    // 缓冲区配置
    public static final int BUFFER_SIZE = 8192;           // 缓冲区大小，8KB
    
    // 连接配置
    public static final int CONNECTION_TIMEOUT = 30000;   // 连接超时时间(毫秒)
    public static final int HEARTBEAT_INTERVAL = 30000;   // 心跳间隔(毫秒)
    
    // 重连配置
    public static final int MAX_RECONNECT_ATTEMPTS = 10;  // 最大重连次数
    public static final long RECONNECT_DELAY_MS = 5000;   // 重连延迟(毫秒)
    public static final int CONNECTION_CHECK_INTERVAL = 10000; // 连接检查间隔(毫秒)
    
    // 服务器地址
    public static final String SERVER_HOST = "localhost"; // 服务器地址
} 