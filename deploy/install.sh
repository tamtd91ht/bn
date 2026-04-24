#!/usr/bin/env bash
# Cài bot lên VPS Linux (Ubuntu/Debian).
# Chạy 1 lần lúc deploy đầu, subsequent deploy chỉ cần copy jar + restart service.
set -euo pipefail

BOT_HOME=/opt/bot
BOT_USER=bot

echo "[1/6] Tạo user '$BOT_USER' nếu chưa có..."
if ! id "$BOT_USER" &>/dev/null; then
    sudo useradd --system --home "$BOT_HOME" --shell /usr/sbin/nologin "$BOT_USER"
fi

echo "[2/6] Tạo thư mục $BOT_HOME..."
sudo mkdir -p "$BOT_HOME" "$BOT_HOME/data" "$BOT_HOME/logs"
sudo chown -R "$BOT_USER:$BOT_USER" "$BOT_HOME"

echo "[3/6] Copy jar + config..."
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
sudo install -o "$BOT_USER" -g "$BOT_USER" -m 644 "$SCRIPT_DIR/../target/bot.jar" "$BOT_HOME/bot.jar"
sudo install -o "$BOT_USER" -g "$BOT_USER" -m 644 "$SCRIPT_DIR/../application.yml" "$BOT_HOME/application.yml"

if [[ ! -f "$BOT_HOME/.env" ]]; then
    echo "[4/6] .env chưa có - copy template..."
    sudo install -o "$BOT_USER" -g "$BOT_USER" -m 600 "$SCRIPT_DIR/../.env.example" "$BOT_HOME/.env"
    echo "  => CHỈNH /opt/bot/.env để điền BINANCE_API_KEY/SECRET trước khi start service"
else
    echo "[4/6] .env đã tồn tại - giữ nguyên"
fi

echo "[5/6] Install systemd unit..."
sudo install -m 644 "$SCRIPT_DIR/bot.service" /etc/systemd/system/bot.service
sudo systemctl daemon-reload

echo "[6/6] Bật NTP sync (để tránh clock skew với Binance)..."
sudo timedatectl set-ntp true || true

echo ""
echo "=== Cài đặt xong ==="
echo "Lệnh thường dùng:"
echo "  sudo systemctl enable --now bot    # bật và chạy service"
echo "  sudo systemctl status bot          # xem trạng thái"
echo "  sudo journalctl -u bot -f          # tail log realtime"
echo "  sudo systemctl restart bot         # restart sau khi update jar"
