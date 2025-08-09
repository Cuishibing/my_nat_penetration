#!/bin/bash

# NAT穿透Docker停止脚本

# 显示使用说明
show_usage() {
    echo "用法: $0 [server|client|all]"
    echo ""
    echo "命令:"
    echo "  server  - 仅停止服务端"
    echo "  client  - 仅停止客户端"
    echo "  all     - 停止所有服务（默认）"
    echo ""
    echo "示例:"
    echo "  $0 server  # 停止服务端"
    echo "  $0 client  # 停止客户端"
    echo "  $0 all     # 停止所有服务"
}

# 检查参数
if [ "$1" = "-h" ] || [ "$1" = "--help" ]; then
    show_usage
    exit 0
fi

# 解析命令
COMMAND=${1:-"all"}

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

# 根据命令停止相应服务
case "$COMMAND" in
    "server")
        info "停止服务端..."
        docker-compose stop nat-server
        docker-compose rm -f nat-server
        ;;
    "client")
        info "停止客户端..."
        docker-compose stop nat-client
        docker-compose rm -f nat-client
        ;;
    "all"|*)
        info "停止所有服务..."
        docker-compose down
        ;;
esac

info "停止完成！" 