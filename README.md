# NAT穿透软件

这是一个基于Java 21实现的NAT穿透软件，包含服务端和客户端两个组件。通过建立隧道，可以实现内网服务的远程访问。项目使用虚拟线程技术，提供更好的并发性能。

## 功能特性

- **服务端**: 监听客户端连接，处理外部请求并转发给客户端
- **客户端**: 连接服务端，建立隧道，转发本地服务数据
- **自动重连**: 客户端支持自动重连机制
- **心跳检测**: 保持连接活跃状态
- **多客户端支持**: 服务端支持多个客户端同时连接
- **日志记录**: 完整的日志记录和调试信息
- **虚拟线程**: 使用Java 21虚拟线程技术，提供更好的并发性能

## 项目结构

```
my_nat_penetration/
├── src/main/java/com/natpenetration/
│   ├── common/           # 公共组件
│   │   ├── Config.java   # 配置类
│   │   └── Message.java  # 消息结构
│   ├── server/           # 服务端组件
│   │   ├── NatServer.java      # 服务端主类
│   │   ├── ClientHandler.java  # 客户端处理器
│   │   └── RequestHandler.java # 请求处理器
│   └── client/           # 客户端组件
│       ├── NatClient.java           # 客户端主类
│       ├── TunnelHandler.java       # 隧道处理器
│       └── LocalServiceHandler.java # 本地服务处理器
├── src/main/resources/
│   └── logback.xml       # 日志配置
├── pom.xml               # Maven配置
└── README.md             # 项目说明
```

## 系统要求

- Java 21 或更高版本
- Maven 3.6 或更高版本

## 编译和运行

### 1. 编译项目

```bash
mvn clean compile
```

### 2. 打包项目

```bash
mvn clean package
```

### 3. 运行服务端

```bash
# 使用Maven运行
mvn exec:java -Dexec.mainClass="com.natpenetration.server.NatServer"

# 或者使用打包后的jar文件
java -jar target/nat-penetration-1.0.0.jar
```

### 4. 运行客户端

```bash
# 使用Maven运行
mvn exec:java -Dexec.mainClass="com.natpenetration.client.NatClient" -Dexec.args="client1 localhost 8080"

# 或者使用打包后的jar文件
java -cp target/nat-penetration-1.0.0.jar com.natpenetration.client.NatClient client1 localhost 8080
```

## 配置说明

### 服务端配置 (Config.java)

- `SERVER_PORT`: 服务端监听客户端连接的端口 (默认: 8888)
- `TUNNEL_PORT`: 服务端监听外部请求的端口 (默认: 9999)
- `MAX_CLIENTS`: 最大客户端连接数 (默认: 100)
- `HEARTBEAT_INTERVAL`: 心跳间隔 (默认: 30秒)
- `CONNECTION_TIMEOUT`: 连接超时时间 (默认: 60秒)

### 客户端配置 (Config.java)

- `SERVER_HOST`: 服务端地址 (默认: localhost)
- `CLIENT_RECONNECT_DELAY`: 重连延迟 (默认: 5秒)
- `CLIENT_MAX_RECONNECT_ATTEMPTS`: 最大重连次数 (默认: 10)

## 使用示例

### 1. 启动服务端

```bash
# 在服务器上运行
java -jar nat-penetration-1.0.0.jar
```

服务端将监听两个端口：
- 8888: 客户端连接端口
- 9999: 外部请求端口

### 2. 启动客户端

```bash
# 在内网机器上运行，假设本地有一个Web服务运行在8080端口
java -cp nat-penetration-1.0.0.jar com.natpenetration.client.NatClient client1 localhost 8080
```

### 3. 访问内网服务

现在可以通过服务器的9999端口访问内网的8080端口服务：

```bash
# 访问内网Web服务
curl http://服务器IP:9999/
```

## 工作原理

1. **客户端连接**: 客户端连接到服务端的8888端口，建立隧道
2. **外部请求**: 外部请求发送到服务端的9999端口
3. **数据转发**: 服务端将请求数据通过隧道转发给客户端
4. **本地处理**: 客户端将数据转发给本地服务(如Web服务器)
5. **响应返回**: 本地服务的响应通过隧道返回给服务端，再返回给外部请求者

## 注意事项

1. **防火墙配置**: 确保服务端的8888和9999端口对外开放
2. **网络环境**: 客户端需要能够访问服务端的8888端口
3. **本地服务**: 确保客户端能够访问指定的本地服务端口
4. **安全考虑**: 生产环境中建议添加认证和加密机制

## 故障排除

### 常见问题

1. **连接失败**: 检查服务端地址和端口是否正确
2. **本地服务无法访问**: 确认本地服务正在运行且端口正确
3. **数据转发失败**: 检查网络连接和防火墙设置

### 日志查看

日志文件保存在 `logs/nat-penetration.log`，可以通过查看日志来诊断问题。

## 虚拟线程特性

本项目使用Java 21的虚拟线程技术，相比传统线程具有以下优势：

- **更高的并发性能**: 可以创建数百万个虚拟线程而不会耗尽系统资源
- **更低的资源消耗**: 虚拟线程的内存占用远低于传统线程
- **更好的响应性**: 虚拟线程在等待I/O时会自动让出CPU资源
- **简化的编程模型**: 使用方式与传统线程相同，但性能更好

### 虚拟线程使用示例

```java
// 使用虚拟线程执行器
try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
    executor.submit(() -> {
        // 执行任务
        logger.info("虚拟线程执行任务");
    });
}
```

## 扩展功能

- 添加用户认证机制
- 支持SSL/TLS加密
- 实现负载均衡
- 添加Web管理界面
- 支持UDP协议 