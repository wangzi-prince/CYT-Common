#!/usr/bin/env bash
set -Eeuo pipefail

# Jenkins「cyt-common-deploy」构建步骤的总入口。工作区 = 已 checkout 的临时源码目录。
#   SKIP_DEPLOY=1  只构建并推送镜像，不重建容器

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

echo "==> 工作区: $ROOT"
echo "==> 分支: $(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo '(非 git)')  提交: $(git rev-parse --short HEAD 2>/dev/null || echo '-')"

echo "==> 1/2 构建镜像并推送"
bash "$ROOT/deploy/test-server/build-on-server.sh" "$ROOT"

if [[ "${SKIP_DEPLOY:-0}" == "1" ]]; then
  echo "==> SKIP_DEPLOY=1，跳过重启，容器仍在跑旧镜像"
  exit 0
fi

echo "==> 2/2 重建容器 + 健康检查"
bash "$ROOT/deploy/test-server/redeploy.sh" "$ROOT"

echo "✅ 部署完成：http://192.168.1.182:8088/"
