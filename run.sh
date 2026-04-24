#!/usr/bin/env bash
# Chạy bot local (Linux/Mac/Git Bash) với .env ở cùng thư mục.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

if [[ ! -f .env ]]; then
    echo "ERROR: không tìm thấy .env"
    echo "Copy .env.example -> .env rồi điền API key."
    exit 1
fi
if [[ ! -f target/bot.jar ]]; then
    echo "ERROR: target/bot.jar chưa tồn tại."
    echo "Chạy: mvn package -DskipTests"
    exit 1
fi

# Load .env (bỏ qua dòng comment + dòng rỗng)
set -a
# shellcheck disable=SC2046
source <(grep -v '^\s*#' .env | grep -v '^\s*$')
set +a

COMMAND="${1:-run}"
shift || true
exec java -jar target/bot.jar "$COMMAND" "$@"
