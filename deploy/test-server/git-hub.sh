#!/usr/bin/env bash
set -Eeuo pipefail

# 测试服访问 GitHub：直连优先，失败自动走 mihomo 代理（1panel-network 内 172.18.0.6:7890）。
# Jenkins 的 shell 是宿主机进程，不在 1panel-network 里，所以 git 必须显式指代理。
#
# 用法：
#   bash git-hub.sh branches                 列出远端分支
#   bash git-hub.sh clone <branch> <dest>    浅克隆指定分支
#
# 环境变量：
#   GITHUB_TOKEN_FILE  默认 /opt/jenkins/github_token_common（本项目专用，别动 CS 的那个）
#   GH_USER            默认 wangzi-prince
#   GIT_REPO           默认 https://github.com/wangzi-prince/CYT-Common.git
#   GIT_PROXY          默认 http://172.18.0.6:7890

GIT_PROXY="${GIT_PROXY:-http://172.18.0.6:7890}"
GITHUB_TOKEN_FILE="${GITHUB_TOKEN_FILE:-/opt/jenkins/github_token_common}"
GH_USER="${GH_USER:-wangzi-prince}"
GIT_REPO="${GIT_REPO:-https://github.com/wangzi-prince/CYT-Common.git}"

command -v git >/dev/null 2>&1 || { echo "ERROR: 没有 git" >&2; exit 2; }
[[ -r "$GITHUB_TOKEN_FILE" ]] || { echo "ERROR: 读不到 $GITHUB_TOKEN_FILE" >&2; exit 2; }
TOKEN="$(cat "$GITHUB_TOKEN_FILE")"
AUTH_URL="https://${GH_USER}:${TOKEN}@${GIT_REPO#https://}"

attempt() {
  local mode="$1"; shift
  # GIT_TERMINAL_PROMPT=0：凭据被拒时立刻失败，别在 Jenkins 无终端环境里等人输用户名
  # 低速超时压到 20s，才能走完「直连 → 代理」两轮而不是卡住整个构建
  local envs=(GIT_TERMINAL_PROMPT=0 GIT_HTTP_LOW_SPEED_LIMIT=1000 GIT_HTTP_LOW_SPEED_TIME=20)
  if [[ "$mode" == proxy ]]; then
    env "${envs[@]}" HTTP_PROXY="$GIT_PROXY" HTTPS_PROXY="$GIT_PROXY" git "$@"
  else
    env "${envs[@]}" git "$@"
  fi
}

run_git() {
  if attempt direct "$@" 2>/dev/null; then return 0; fi
  echo "==> 直连 GitHub 失败，改走代理 $GIT_PROXY 重试" >&2
  attempt proxy "$@"
}

case "${1:-}" in
  branches)
    run_git ls-remote --heads "$AUTH_URL" | awk '{print $2}' | sed 's#refs/heads/##' | sort -u
    ;;
  clone)
    BRANCH="${2:?用法: git-hub.sh clone <branch> <dest>}"
    DEST="${3:?用法: git-hub.sh clone <branch> <dest>}"
    run_git clone --depth 1 --branch "$BRANCH" "$AUTH_URL" "$DEST"
    ;;
  *)
    echo "用法: git-hub.sh branches | clone <branch> <dest>" >&2
    exit 2
    ;;
esac
