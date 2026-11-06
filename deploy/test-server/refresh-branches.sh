#!/usr/bin/env bash
set -Eeuo pipefail

# 刷新 Jenkins「cyt-common-deploy」的 BRANCH 下拉列表 = GitHub 上当前的分支列表。
#
# 为什么要这个：Jenkins 的选项参数写死在任务配置里，新建分支不会自己冒出来。
# 这台 Jenkins 是裸 war、没装 git-parameter 插件（装插件要重启，会打断别的部署），
# 所以用「点一下这个任务」的土办法：改任务 config.xml → POST 回去，单任务热更新、零插件、零重启。
#
# 用法： bash refresh-branches.sh [默认排第一的分支，默认 main]
# 依赖： /opt/jenkins/github_token_common（本项目 PAT）
#        /opt/cyt-common/deploy/jenkins.env（JENKINS_USER / JENKINS_PASSWORD）

JOB="${JOB:-cyt-common-deploy}"
DEFAULT_BRANCH="${1:-${DEFAULT_BRANCH:-main}}"
JENKINS_URL="${JENKINS_URL:-http://127.0.0.1:8090}"
JENKINS_ENV_FILE="${JENKINS_ENV_FILE:-/opt/cyt-common/deploy/jenkins.env}"
SELF_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
SUDO=""
[[ "$(id -u)" == "0" ]] || SUDO="sudo"

set -a; . "$JENKINS_ENV_FILE"; set +a
[[ -n "${JENKINS_USER:-}" && -n "${JENKINS_PASSWORD:-}" ]] || {
  echo "ERROR: $JENKINS_ENV_FILE 里要有 JENKINS_USER 与 JENKINS_PASSWORD" >&2; exit 2; }

echo "==> 拉取远端分支列表"
mapfile -t BRANCHES < <(bash "$SELF_DIR/git-hub.sh branches")
(( ${#BRANCHES[@]} > 0 )) || { echo "ERROR: 没拿到分支（GitHub 连不上或 token 过期）" >&2; exit 1; }

# 选项参数的第一项就是构建时的默认值
ORDERED=("$DEFAULT_BRANCH")
for b in "${BRANCHES[@]}"; do [[ "$b" == "$DEFAULT_BRANCH" ]] || ORDERED+=("$b"); done
echo "==> 分支（默认 ${ORDERED[0]}）: ${ORDERED[*]}"

JOBS_DIR="${JOBS_DIR:-/var/lib/jenkins/jobs}"
JOB_CONFIG="$JOBS_DIR/$JOB/config.xml"
[[ -f "$JOB_CONFIG" ]] || { echo "ERROR: $JOB_CONFIG 不存在" >&2; exit 2; }

TMP="$(mktemp)"
cp "$JOB_CONFIG" "$TMP"
python3 - "$TMP" "$JOB" "${ORDERED[@]}" <<'PY'
import sys
import xml.etree.ElementTree as ET

path, job_name, *branches = sys.argv[1:]
tree = ET.parse(path)
param = next(
    (p for p in tree.iter()
     if p.tag.endswith("ChoiceParameterDefinition") and (p.findtext("name") or "").strip() == "BRANCH"),
    None,
)
if param is None:
    sys.exit(f"ERROR: 任务 {job_name} 的配置里找不到 BRANCH 选项参数")
choices = param.find("choices")
if choices is None:
    choices = ET.SubElement(param, "choices")
for child in list(choices):
    choices.remove(child)
for b in branches:
    ET.SubElement(choices, "string").text = b
# Jenkins 用 Arrays$ArrayList 反序列化 choices，这个 class 属性丢了任务会读不出来
choices.set("class", "java.util.Arrays$ArrayList")
tree.write(path, encoding="UTF-8", xml_declaration=True)
print(f"已重写 {len(branches)} 个分支选项")
PY
$SUDO install -m 644 "$TMP" "$JOB_CONFIG"
rm -f "$TMP"

echo "==> 热更新 Jenkins 任务 $JOB（不重启 Jenkins）"
# crumb 绑定会话：取 crumb 和 POST 必须共用同一个 cookie jar，否则 403 No valid crumb
JAR="$(mktemp -t common-jenkins-cookies-XXXXXX)"
trap 'rm -f "$JAR"' EXIT
CRUMB_JSON="$(curl -s -c "$JAR" -b "$JAR" -u "$JENKINS_USER:$JENKINS_PASSWORD" "$JENKINS_URL/crumbIssuer/api/json")"
CRUMB_FIELD="$(printf '%s' "$CRUMB_JSON" | python3 -c 'import json,sys;print(json.load(sys.stdin).get("crumbRequestField",""))' 2>/dev/null || true)"
CRUMB="$(printf '%s' "$CRUMB_JSON" | python3 -c 'import json,sys;print(json.load(sys.stdin).get("crumb",""))' 2>/dev/null || true)"
CRUMB_ARGS=()
[[ -n "$CRUMB" && -n "$CRUMB_FIELD" ]] && CRUMB_ARGS=(-H "$CRUMB_FIELD: $CRUMB")

RESP="$(mktemp -t common-jenkins-resp-XXXXXX)"
# charset 必须写进 Content-Type：config.xml 里有中文，只写 application/xml 会按 ISO-8859-1 解导致 500
http_code="$(curl -s -o "$RESP" -w '%{http_code}' \
  -c "$JAR" -b "$JAR" \
  -X POST -u "$JENKINS_USER:$JENKINS_PASSWORD" \
  "${CRUMB_ARGS[@]}" \
  -H 'Content-Type: application/xml; charset=utf-8' \
  --data-binary "@$JOB_CONFIG" \
  "$JENKINS_URL/job/$JOB/config.xml")"

if [[ "$http_code" == "200" ]]; then
  echo "✅ 分支列表已更新（下次 Build with Parameters 就是新列表）"
  rm -f "$RESP"
else
  echo "❌ 更新失败 HTTP $http_code：$(head -c 400 "$RESP")" >&2
  rm -f "$RESP"
  exit 1
fi
