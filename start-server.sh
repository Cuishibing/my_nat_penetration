#!/bin/bash

# NAT穿透服务端启动脚本

echo "启动NAT穿透服务端..."

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

# 启动服务端
echo "启动服务端..."
mvn exec:java -Dexec.mainClass="com.natpenetration.server.NatServer" 