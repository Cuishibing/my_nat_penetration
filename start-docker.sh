#!/bin/bash

# NAT穿透Docker启动脚本

# 显示使用说明
show_usage() {
    echo "用法: $0 [server|client|both] [服务端IP] [服务端端口] [本地端口]"
    echo ""
    echo "命令:"
    echo "  server  - 仅启动服务端"
    echo "  client  - 仅启动客户端"
    echo "  both    - 启动服务端和客户端（默认）"
    echo ""
    echo "示例:"
    echo "  $0 server                    # 启动服务端"
    echo "  $0 client 192.168.1.100     # 启动客户端连接到指定服务器"
    echo "  $0 both 192.168.1.100 8080 8082  # 启动完整服务"
    echo "  $0 client localhost 8080 8082  # 使用host模式访问localhost"
    echo ""
    echo "默认值: localhost:8080 -> localhost:8082"
    echo "注意: 客户端使用host网络模式，可直接访问localhost"
}

# 检查参数
if [ "$1" = "-h" ] || [ "$1" = "--help" ]; then
    show_usage
    exit 0
fi

# 解析命令和参数
COMMAND=${1:-"both"}
SERVER_HOST=${2:-"localhost"}
SERVER_PORT=${3:-"8080"}
LOCAL_PORT=${4:-"8082"}

# 颜色输出
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

info() {
    echo -e "${GREEN}[INFO] $1${NC}"
}

warn() {
    echo -e "${YELLOW}[WARN] $1${NC}"
}

info "启动NAT穿透服务..."
info "命令: $COMMAND"
info "服务端地址: $SERVER_HOST:$SERVER_PORT"
info "本地端口: $LOCAL_PORT"

# 设置环境变量
export SERVER_HOST=$SERVER_HOST
export SERVER_PORT=$SERVER_PORT
export LOCAL_PORT=$LOCAL_PORT

# 动态修改docker-compose.yml中的配置
info "配置服务参数..."
if [ "$SERVER_PORT" != "8080" ]; then
    sed -i.bak "s/- \"8080:8080\"/- \"$SERVER_PORT:8080\"/" docker-compose.yml
fi

# 修改服务启动参数
if [ "$COMMAND" = "server" ] || [ "$COMMAND" = "both" ]; then
    # 服务端使用默认配置，无需修改
    info "服务端使用默认配置"
fi

if [ "$COMMAND" = "client" ] || [ "$COMMAND" = "both" ]; then
    # 为客户端修改command参数，使用类名进行替换
    info "修改客户端参数: $SERVER_HOST:$SERVER_PORT -> $LOCAL_PORT"
    sed -i.bak "s/com.natpenetration.client.NatClient\", \"localhost\", \"8080\", \"8082\"/com.natpenetration.client.NatClient\", \"$SERVER_HOST\", \"$SERVER_PORT\", \"$LOCAL_PORT\"/" docker-compose.yml
fi

# 构建镜像
info "构建Docker镜像..."
docker-compose build

# 根据命令启动相应服务
case "$COMMAND" in
    "server")
        info "启动服务端..."
        docker-compose up -d nat-server
        ;;
    "client")
        info "启动客户端..."
        docker-compose up -d nat-client
        ;;
    "both"|*)
        info "启动完整服务..."
        docker-compose up -d
        ;;
esac

# 等待服务启动
info "等待服务启动..."
sleep 5

# 显示服务状态
info "服务状态："
docker-compose ps

# 显示日志
info "服务日志："
docker-compose logs --tail=10

# 显示部署信息
case "$COMMAND" in
    "server")
        info "服务端部署完成！"
        info "服务端: http://$SERVER_HOST:$SERVER_PORT"
        ;;
    "client")
        info "客户端部署完成！"
        info "连接到: $SERVER_HOST:$SERVER_PORT"
        info "本地端口: $LOCAL_PORT"
        ;;
    "both"|*)
        info "完整服务部署完成！"
        info "服务端: http://$SERVER_HOST:$SERVER_PORT"
        info "客户端端口: $LOCAL_PORT"
        ;;
esac

# 恢复原始配置
if [ -f "docker-compose.yml.bak" ]; then
    mv docker-compose.yml.bak docker-compose.yml
fi 