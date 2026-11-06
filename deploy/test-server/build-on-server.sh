#!/usr/bin/env bash
set -Eeuo pipefail

# 在【测试服本机】构建 CYT-Common 镜像并推到局域网仓库。
# 用法： bash build-on-server.sh /path/to/repo
# 可选环境变量：
#   REGISTRY=192.168.1.182:5000
#   BASE_REGISTRY=docker.m.daocloud.io/   Docker Hub 拉不动时的加速前缀
#   NPM_REGISTRY=https://registry.npmmirror.com
#   NO_PUSH=1                             只构建不推送（调试用）

SRC_DIR="${1:-$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)}"
REGISTRY="${REGISTRY:-192.168.1.182:5000}"
IMAGE="$REGISTRY/cyt-common-java:latest"
BASE_REGISTRY="${BASE_REGISTRY:-}"
NPM_REGISTRY="${NPM_REGISTRY:-https://registry.npmmirror.com}"
NO_PUSH="${NO_PUSH:-0}"

command -v docker >/dev/null 2>&1 || { echo "ERROR: 服务器上没有 docker" >&2; exit 2; }
docker info >/dev/null 2>&1 || { echo "ERROR: docker 守护进程不可用" >&2; exit 2; }
[[ -f "$SRC_DIR/deploy/test-server/Dockerfile" ]] || {
  echo "ERROR: $SRC_DIR/deploy/test-server/Dockerfile 不存在（源码没到位？）" >&2; exit 2; }

echo "==> 源码目录: $SRC_DIR"
echo "==> 目标镜像: $IMAGE"
echo "==> 基础镜像前缀: ${BASE_REGISTRY:-<官方源>}"

docker build \
  --build-arg "BASE_REGISTRY=$BASE_REGISTRY" \
  --build-arg "NPM_REGISTRY=$NPM_REGISTRY" \
  -f "$SRC_DIR/deploy/test-server/Dockerfile" \
  -t "$IMAGE" \
  "$SRC_DIR"

if [[ "$NO_PUSH" == "1" ]]; then
  echo "NO_PUSH=1，跳过推送。"
  exit 0
fi

echo "==> 推送 $IMAGE"
docker push "$IMAGE"
echo "✅ 镜像已推送"
