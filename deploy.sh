#!/bin/bash

# NAT穿透项目部署脚本

# 配置信息
REMOTE_HOST="192.168.0.190"
REMOTE_USER="cui"
REMOTE_DIR="/home/cui/Workspace/MyNat"
SSH_KEY="$HOME/.ssh/id_rsa"  # SSH密钥路径 - 使用绝对路径
USE_PASSWORD=false  # 默认使用SSH密钥认证

# 颜色输出
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

# 打印带颜色的信息
info() {
    echo -e "${GREEN}[INFO] $1${NC}"
}

error() {
    echo -e "${RED}[ERROR] $1${NC}"
    exit 1
}

warn() {
    echo -e "${YELLOW}[WARN] $1${NC}"
}

# 检查SSH连接
check_ssh_connection() {
    info "检查SSH连接..."

    if [ ! -f "$SSH_KEY" ]; then
        warn "SSH密钥文件不存在: $SSH_KEY，将使用密码认证"
        USE_PASSWORD=true
        return
    fi

    if [ ! -r "$SSH_KEY" ]; then
        warn "SSH密钥文件不可读: $SSH_KEY，将使用密码认证"
        USE_PASSWORD=true
        return
    fi

    info "SSH密钥文件权限: $(ls -la $SSH_KEY)"

    # 测试SSH连接
    info "测试SSH连接到 $REMOTE_USER@$REMOTE_HOST..."
    if ssh -i "$SSH_KEY" -o ConnectTimeout=10 -o BatchMode=yes "$REMOTE_USER@$REMOTE_HOST" "echo 'SSH连接成功'" 2>/dev/null; then
        info "SSH连接测试成功！"
        USE_PASSWORD=false
    else
        warn "SSH密钥认证失败，将使用密码认证"
        USE_PASSWORD=true
    fi
}

# 检查必要命令
check_commands() {
    info "检查必要命令..."
    for cmd in ssh scp rsync docker docker-compose; do
        if ! command -v $cmd &> /dev/null; then
            error "$cmd 未安装"
        fi
    done
}

# 传输文件到远程服务器
transfer_files() {
    info "传输文件到远程服务器..."

    # 创建临时目录用于传输
    TEMP_DIR=$(mktemp -d)

    # 复制项目文件到临时目录
    rsync -av \
        --include 'src/' \
        --include 'src/**' \
        --include 'pom.xml' \
        --include 'Dockerfile' \
        --include 'Dockerfile.client' \
        --include 'docker-compose.yml' \
        --include 'start-server.sh' \
        --include 'start-client.sh' \
        --include 'README.md' \
        --include 'start-docker.sh' \
        --include 'stop-docker.sh' \
        --exclude '*' \
        ./ $TEMP_DIR/my_nat_penetration/ || error "复制项目文件失败"

    # 传输文件到远程服务器
    if [ "$USE_PASSWORD" = true ]; then
        info "使用密码认证传输文件..."
        scp -r $TEMP_DIR/* $REMOTE_USER@$REMOTE_HOST:$REMOTE_DIR/ || error "文件传输失败"
    else
        info "使用SSH密钥认证传输文件..."
        scp -i "$SSH_KEY" -r $TEMP_DIR/* $REMOTE_USER@$REMOTE_HOST:$REMOTE_DIR/ || error "文件传输失败"
    fi

    # 清理临时目录
    rm -rf $TEMP_DIR
}

# 在远程服务器上执行部署命令
deploy_on_remote() {
    info "在远程服务器上执行部署命令..."

    # 根据认证方式选择SSH命令
    if [ "$USE_PASSWORD" = true ]; then
        info "使用密码认证执行远程命令..."
        SSH_BASE="ssh $REMOTE_USER@$REMOTE_HOST"
    else
        info "使用SSH密钥认证执行远程命令..."
        SSH_BASE="ssh -i \"$SSH_KEY\" $REMOTE_USER@$REMOTE_HOST"
    fi

    # 执行部署命令
    info "停止现有容器..."
    $SSH_BASE "cd $REMOTE_DIR && docker-compose down" || warn "停止容器时出现警告"

    info "构建Docker镜像..."
    $SSH_BASE "cd $REMOTE_DIR && docker-compose build" || error "构建Docker镜像失败"

    info "启动服务..."
    $SSH_BASE "cd $REMOTE_DIR && docker-compose up -d" || error "启动服务失败"

    info "检查服务状态..."
    $SSH_BASE "cd $REMOTE_DIR && docker-compose ps"

    info "查看服务日志..."
    $SSH_BASE "cd $REMOTE_DIR && docker-compose logs --tail=20"
}

# 主函数
main() {
    info "开始NAT穿透项目部署流程..."

    check_commands
    check_ssh_connection
    transfer_files


    # 询问用户是否要自动部署
    echo -e "${YELLOW}是否要自动在远程服务器上执行部署命令？(y/n)${NC}"
    read -r response

    if [[ "$response" =~ ^[Yy]$ ]]; then
        deploy_on_remote
        info "部署完成！"
        info "服务端地址: http://$REMOTE_HOST:8080"
        info "客户端端口: 8082"
    else
        info "请在远程服务器上手动执行以下命令进行部署："
        info "cd $REMOTE_DIR"
        info "docker-compose build"
        info "docker-compose up -d"
    fi
}

# 执行主函数
main "$@"