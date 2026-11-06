#!/usr/bin/env bash
set -Eeuo pipefail

# 把仓库里最新的编排同步到 1Panel 目录，重建容器，并等 /health 通过。
# 首次运行会自动创建编排目录和随机 .env；之后【绝不覆盖 .env】，避免重置数据库密码和密钥。
#
# 用法： sudo bash redeploy.sh /path/to/repo
#   COMPOSE_DIR   默认 /opt/1panel/docker/compose/common-java
#   HEALTH_URL    默认 http://127.0.0.1:8088/health
#   WAIT_SECONDS  默认 300（首次 Flyway 建表 + seed 需要 1-2 分钟）
#   INITIAL_ADMIN_PASSWORD  首次生成的后台密码；不指定则随机生成（只写进 .env，不打印到构建日志）

SRC_DIR="${1:-$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)}"
COMPOSE_DIR="${COMPOSE_DIR:-/opt/1panel/docker/compose/common-java}"
COMPOSE_FILE="$COMPOSE_DIR/docker-compose.yml"
ENV_FILE="$COMPOSE_DIR/.env"
HEALTH_URL="${HEALTH_URL:-http://127.0.0.1:8088/health}"
WAIT_SECONDS="${WAIT_SECONDS:-300}"

SUDO=""
[[ "$(id -u)" == "0" ]] || SUDO="sudo"

command -v docker >/dev/null 2>&1 || { echo "ERROR: 没有 docker" >&2; exit 2; }
[[ -f "$SRC_DIR/deploy/test-server/docker-compose.yml" ]] || {
  echo "ERROR: 找不到 $SRC_DIR/deploy/test-server/docker-compose.yml" >&2; exit 2; }

echo "==> 同步编排文件 → $COMPOSE_FILE"
$SUDO mkdir -p "$COMPOSE_DIR"
$SUDO cp "$SRC_DIR/deploy/test-server/docker-compose.yml" "$COMPOSE_FILE"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "==> 首次部署：生成随机密钥到 $ENV_FILE（权限 600）"
  command -v openssl >/dev/null 2>&1 || { echo "ERROR: 需要 openssl 生成密钥" >&2; exit 2; }
  DB_PW="$(openssl rand -hex 16)"
  ROOT_PW="$(openssl rand -hex 16)"
  JWT="$(openssl rand -hex 32)"
  MASTER="$(openssl rand -hex 32)"
  ADMIN_PW="${INITIAL_ADMIN_PASSWORD:-$(openssl rand -hex 12)}"
  TMP="$(mktemp)"
  cat > "$TMP" <<EOF
COMMON_JAVA_IMAGE=192.168.1.182:5000/cyt-common-java:latest
APP_PORT=8088
APP_ENV=test
MYSQL_IMAGE=mysql:8.0
MYSQL_DATABASE=cyt_common
MYSQL_USER=cyt_gateway
MYSQL_PASSWORD=$DB_PW
MYSQL_ROOT_PASSWORD=$ROOT_PW
ADMIN_USERNAME=admin
ADMIN_PASSWORD=$ADMIN_PW
ADMIN_JWT_SECRET=$JWT
CREDENTIAL_MASTER_KEY=$MASTER
MOCK_ALL=true
JAVA_SEED_ENABLED=true
GATEWAY_DEV_MODE=false
EOF
  $SUDO install -m 600 "$TMP" "$ENV_FILE"
  rm -f "$TMP"
  echo "    后台登录: admin / 密码见 $ENV_FILE 的 ADMIN_PASSWORD（不打印到日志）"
  echo "    ENV=test 时登录页也可用「开发身份」免密进入"
else
  echo "==> 沿用已有 .env（不覆盖密钥、不重置数据库密码）"
fi

echo "==> 重建容器"
$SUDO docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" up -d 2>&1 | tail -20

echo "==> 等健康检查（最多 ${WAIT_SECONDS}s）：$HEALTH_URL"
deadline=$(( $(date +%s) + WAIT_SECONDS ))
body=""
while :; do
  body="$(curl -s -m 6 "$HEALTH_URL" || true)"
  if [[ "$body" =~ \"ok\"[[:space:]]*:[[:space:]]*true ]]; then
    echo "✅ 健康：${body:0:300}"
    $SUDO docker ps --format '{{.Names}}\t{{.Status}}\t{{.Ports}}' | grep cyt-common-java || true
    exit 0
  fi
  if (( $(date +%s) > deadline )); then
    echo "❌ 超时，最后一次响应：${body:0:300}" >&2
    echo "--- app 最近日志 ---" >&2
    $SUDO docker logs --tail 60 cyt-common-java >&2 || true
    echo "--- db 最近日志 ---" >&2
    $SUDO docker logs --tail 20 cyt-common-java-db >&2 || true
    exit 1
  fi
  sleep 5
done
