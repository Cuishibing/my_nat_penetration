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


## 使用示例

### 1. 启动服务端

```bash
# 在服务器上运行
mvn exec:java -Dexec.mainClass="com.natpenetration.server.NatServer"
```

服务端将监听两个端口：
- 8080: 客户端连接端口
- 8081: 外部请求端口
- 随机端口：用于隧道建立

### 2. 启动客户端

```bash
# 在内网机器上运行，假设本地有一个Web服务运行在9000端口
java -cp nat-penetration-1.0.0.jar com.natpenetration.client.NatClient <服务端地址> 8080 9000
```

### 3. 访问内网服务

现在可以通过服务器的8081端口访问内网的端口服务：

```bash
# 访问内网Web服务
curl http://服务器IP:8081/
```

### 日志查看

日志文件保存在 `logs/nat-penetration.log`，可以通过查看日志来诊断问题。
