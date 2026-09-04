#!/usr/bin/env bash
# xstar-relay 一键部署脚本（Linux + systemd）
# 用法:  sudo bash install.sh [port]
#       默认端口 9301
set -euo pipefail

PORT="${1:-9301}"
BIN_DIR="/opt/xstar-relay"
UNIT="/etc/systemd/system/xstar-relay.service"

if ! command -v go >/dev/null 2>&1; then
  echo "错误：未找到 go，请先安装 Go >= 1.19（例如 apt install golang）"
  exit 1
fi
if [ "$(id -u)" -ne 0 ]; then
  echo "请使用 root 或 sudo 运行"
  exit 1
fi

echo "==> 编译 xstar-relay"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"
CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o xstar-relay .

echo "==> 安装到 $BIN_DIR"
mkdir -p "$BIN_DIR"
install -m 0755 xstar-relay "$BIN_DIR/xstar-relay"
rm -f xstar-relay

echo "==> 写入 systemd 服务 $UNIT"
cat > "$UNIT" <<EOF
[Unit]
Description=XSTAR Listen-Together relay
After=network.target

[Service]
Type=simple
ExecStart=$BIN_DIR/xstar-relay -listen=:$PORT
Restart=always
RestartSec=3
LimitNOFILE=65535

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable xstar-relay
systemctl restart xstar-relay
systemctl --no-pager status xstar-relay | head -n 12 || true

echo
echo "==> 已启动，监听端口 $PORT"
echo "    请确保防火墙放行 TCP $PORT："
if command -v ufw >/dev/null 2>&1; then
  echo "      ufw allow $PORT/tcp"
fi
if command -v firewalld >/dev/null 2>&1; then
  echo "      firewall-cmd --permanent --add-port=$PORT/tcp && firewall-cmd --reload"
fi
echo "    查看日志：journalctl -u xstar-relay -f"
