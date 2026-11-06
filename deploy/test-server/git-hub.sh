#!/usr/bin/env bash
set -Eeuo pipefail

# 从 GitHub 拿「分支列表」和「指定分支的源码」，不用 git clone。
#
# 为什么不用 git clone：这台测试服到 github.com:443 不通（实测 TCP 10s 超时；
# 走 mihomo 代理也在 TLS 握手断开），但 api.github.com（列分支）和
# codeload.github.com（下源码包）都通。所以走 REST + tarball：
#   查分支 SHA → 下载该 SHA 的 tar.gz → 解到临时工作区（写 .deploy-sha 记录版本）。
# 另一个好处：服务器不需要 .git 目录，构建上下文更干净。
#
# 用法：
#   bash git-hub.sh branches                 列出远端分支名
#   bash git-hub.sh fetch <branch> <dest>    把该分支源码解到 dest
#
# 环境变量：
#   GITHUB_TOKEN_FILE  默认 /opt/jenkins/github_token_common（本项目专用，别动 CS 的那个）
#   GH_OWNER / GH_REPO 默认 wangzi-prince / CYT-Common
#   GH_PROXY           默认 http://172.18.0.6:7890，直连失败时兜底

GITHUB_TOKEN_FILE="${GITHUB_TOKEN_FILE:-/opt/jenkins/github_token_common}"
GH_OWNER="${GH_OWNER:-wangzi-prince}"
GH_REPO="${GH_REPO:-CYT-Common}"
GH_PROXY="${GH_PROXY:-http://172.18.0.6:7890}"
API="https://api.github.com/repos/$GH_OWNER/$GH_REPO"

command -v curl >/dev/null 2>&1 || { echo "ERROR: 没有 curl" >&2; exit 2; }
command -v python3 >/dev/null 2>&1 || { echo "ERROR: 没有 python3" >&2; exit 2; }
[[ -r "$GITHUB_TOKEN_FILE" ]] || { echo "ERROR: 读不到 $GITHUB_TOKEN_FILE" >&2; exit 2; }
TOKEN="$(cat "$GITHUB_TOKEN_FILE")"
AUTH=(-H "Authorization: Bearer $TOKEN" -H 'Accept: application/vnd.github+json' -H 'X-GitHub-Api-Version: 2022-11-28')

# 这台机器到 GitHub 是「时通时不通」：每个请求先直连、再走代理，最多两轮。
# 短连接超时 + 整体超时，避免 Jenkins 在无终端环境里挂死。
# 用法：http_get <url> [curl 额外参数，例如 -o 文件]
http_get() {
  local url="$1"; shift
  local round mode proxy_args
  for round in 1 2; do
    for mode in direct proxy; do
      proxy_args=()
      [[ "$mode" == proxy ]] && proxy_args=(-x "$GH_PROXY")
      if curl -fsSL --connect-timeout 8 -m 60 --retry 1 \
          "${proxy_args[@]}" "${AUTH[@]}" "$@" "$url"; then
        return 0
      fi
      echo "==> $mode 访问失败，换路重试：$url" >&2
    done
  done
  echo "ERROR: 直连与代理都拿不到 $url（检查 token / 网络）" >&2
  return 1
}

# 输出「分支名 SHA」两列，自动翻页（每页 100）
branch_pairs() {
  local page=1 chunk count
  while (( page <= 10 )); do
    chunk="$(http_get "$API/branches?per_page=100&page=$page" \
      | python3 -c 'import json,sys
for b in json.load(sys.stdin): print(b["name"], b["commit"]["sha"])')"
    [[ -n "$chunk" ]] && printf '%s\n' "$chunk"
    count="$(printf '%s' "$chunk" | grep -c . || true)"
    if (( count < 100 )); then break; fi
    page=$((page + 1))
  done
}

case "${1:-}" in
  branches)
    branch_pairs | awk '{print $1}' | sort -u
    ;;
  fetch)
    BRANCH="${2:?用法: git-hub.sh fetch <branch> <dest>}"
    DEST="${3:?用法: git-hub.sh fetch <branch> <dest>}"
    ALL="$(branch_pairs)"
    SHA="$(printf '%s\n' "$ALL" | awk -v b="$BRANCH" '$1==b {print $2; exit}')"
    [[ -n "$SHA" ]] || {
      echo "ERROR: 分支 '$BRANCH' 不存在。当前分支：" >&2
      printf '%s\n' "$ALL" | awk '{print "  - "$1}' >&2
      exit 3
    }

    TMP="$(mktemp -t cyt-common-src-XXXXXX.tar.gz)"
    trap 'rm -f "$TMP"' EXIT
    echo "==> 下载 $BRANCH @ ${SHA:0:7} 的源码包" >&2
    http_get "https://codeload.github.com/$GH_OWNER/$GH_REPO/tar.gz/$SHA" -o "$TMP"

    mkdir -p "$DEST"
    # GitHub 的包顶层是 <repo>-<sha>/ 一层目录，剥掉它
    tar xzf "$TMP" --strip-components=1 -C "$DEST"
    printf '%s\n' "$SHA" > "$DEST/.deploy-sha"
    echo "==> 源码已就绪：$DEST（$BRANCH @ ${SHA:0:7}）" >&2
    ;;
  *)
    echo "用法: git-hub.sh branches | fetch <branch> <dest>" >&2
    exit 2
    ;;
esac
