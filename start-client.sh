#!/bin/bash

# NAT穿透客户端启动脚本

# 检查参数
if [ $# -lt 1 ]; then
    echo "用法: $0 <服务器地址> [服务器端口] [本地端口]"
    echo "示例: $0 192.168.1.100 8080 8082"
    echo "默认值: localhost:8080 -> localhost:8082"
    exit 1
fi

SERVER_HOST=$1
SERVER_PORT=${2:-8080}  # 默认服务器端口8080
LOCAL_PORT=${3:-8082}    # 默认本地端口8082

echo "启动NAT穿透客户端..."
echo "服务器地址: $SERVER_HOST:$SERVER_PORT"
echo "本地端口: $LOCAL_PORT"

# 检查Java是否安装
if ! command -v java &> /dev/null; then
    echo "错误: 未找到Java，请先安装Java 21或更高版本"
    exit 1
fi

# 检查Java版本
JAVA_VERSION=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2 | cut -d'.' -f1)
if [ "$JAVA_VERSION" -lt 21 ]; then
    echo "错误: Java版本过低，需要Java 21或更高版本，当前版本: $JAVA_VERSION"
    exit 1
fi

# 检查Maven是否安装
if ! command -v mvn &> /dev/null; then
    echo "错误: 未找到Maven，请先安装Maven"
    exit 1
fi

# 编译项目
echo "编译项目..."
mvn clean compile

if [ $? -ne 0 ]; then
    echo "编译失败，请检查错误信息"
    exit 1
fi

# 启动客户端
echo "启动客户端..."
mvn exec:java -Dexec.mainClass="com.natpenetration.client.NatClient" -Dexec.args="$SERVER_HOST $SERVER_PORT $LOCAL_PORT"