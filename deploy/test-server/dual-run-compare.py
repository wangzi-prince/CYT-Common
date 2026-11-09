#!/usr/bin/env python3
"""旧网关 / 新网关影子双跑对比（只读，不切流量、不改任何服务配置）。

用旧库 gateway_call_log 里近 N 天真实成功调用的 operation + request_summary，
对 8085（旧 Python 网关）和 8088（新 Java 网关）各发一遍同样的请求，
逐个比较状态码、响应字段与错误契约，重点盯 429 / 502 的 retryable、retry_after。

用法：
  python deploy/test-server/dual-run-compare.py
  python deploy/test-server/dual-run-compare.py --bodies real-calls.jsonl --repeat 2
  python deploy/test-server/dual-run-compare.py --strict        # 有阻断差异时退出码 1

说明：
  * 真实请求体默认现场从旧库导出，不落盘、不进 Git，避免把业务 ID 写进仓库。
    也可以用 --bodies 指定一个 JSONL（每行 {"operation": "...", "body": {...}}）。
  * 只做只读重放；写操作默认跳过（--include-writes 才打开，请谨慎）。
  * 内网地址不要用环境里的 HTTP_PROXY，脚本已显式禁用代理。
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
import urllib.error
import urllib.request
from collections import Counter

DEFAULT_HOST = "192.168.1.182"
OLD_PORT = 8085
NEW_PORT = 8088
PG_CONTAINER = "1Panel-postgresql-aRra"
PG_DB = "cyt_gateway_test"

# 这些会真的改上游数据，默认不重放
WRITE_OPS = {
    "nas.objects.presign_put",
    "docusign.envelopes.create",
    "docusign.envelopes.void",
    "gmail.messages.send",
    "gmail.messages.restore_from_spam",
    "gcs.objects.delete",
    "gcs.objects.upload",
    "gdrive.files.create",
    "gdrive.files.delete",
    "wecom.messages.send_text",
    "wecom.messages.send_markdown",
}

# 每次请求都会变、不参与「是否一致」判定的字段
VOLATILE = {"trace_id", "latency_ms", "request_id", "timestamp", "ts", "duration_ms"}

OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))
HOST = DEFAULT_HOST


def export_calls(ssh_host: str, days: int) -> list[dict]:
    # 取近 N 天出现过的全部 operation（含只失败过的），有成功记录就带真实参数，
    # 没有则用 {} —— 这样 21 个真实操作一个不漏，失败接口也能对比错误契约。
    sql = (
        "select row_to_json(t)::text from ("
        " select a.operation, coalesce(b.body, '{}'::json) as body"
        " from (select distinct operation from public.gateway_call_log"
        f" where called_at > now() - interval '{days} days') a"
        " left join lateral (select c.request_summary::json as body"
        " from public.gateway_call_log c where c.operation=a.operation and c.status='ok'"
        f" and c.called_at > now() - interval '{days} days'"
        " and c.request_summary like '{%' order by c.called_at desc limit 1) b on true"
        " order by a.operation) t"
    )
    remote = (
        f'sudo docker exec {PG_CONTAINER} psql -U postgres -d {PG_DB} '
        f'-Atc "{sql}" 2>/dev/null'
    )
    proc = subprocess.run(
        ["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=12", ssh_host, "bash -s"],
        input=remote,
        capture_output=True,
        text=True,
        timeout=120,
    )
    if proc.returncode != 0:
        raise RuntimeError(f"从旧库导出真实请求失败: {proc.stderr.strip()[:300]}")
    calls = []
    for line in proc.stdout.splitlines():
        line = line.strip()
        if line.startswith("{"):
            try:
                row = json.loads(line)
                if isinstance(row.get("body"), dict):
                    calls.append(row)
            except json.JSONDecodeError:
                continue
    return calls


def load_bodies(path: str) -> list[dict]:
    calls = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line.startswith("{"):
                row = json.loads(line)
                if isinstance(row.get("body"), dict):
                    calls.append(row)
    return calls


def sanitize_masked(calls: list[dict]) -> int:
    """旧库写日志时脱敏过请求体（如 account="l***@epomaker.com"、page_token="***"）。
    这些不是真实请求值：旧网关的 _verified_client 会拿非空 account 与授权邮箱比对，
    用脱敏账号重放必然 403，产生「旧 403 / 新 200」的假阳性。
    把含 *** 的 account 置空，其余脱敏值两端一致、不影响契约比较。
    """
    fixed = 0
    for c in calls:
        body = c.get("body") or {}
        for k, v in list(body.items()):
            if k == "account" and isinstance(v, str) and "***" in v:
                body[k] = ""
                fixed += 1
    return fixed


def request(port: int, path: str, body: dict, system: str, timeout: float):
    headers = {"Content-Type": "application/json", "X-System-Id": system}
    req = urllib.request.Request(
        f"http://{HOST}:{port}{path}",
        data=json.dumps(body).encode(),
        headers=headers,
        method="POST",
    )
    try:
        res = OPENER.open(req, timeout=timeout)
        return res.status, dict(res.headers), res.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read().decode("utf-8", "replace")
    except Exception as e:  # 超时 / 连接失败
        return "ERR", {}, type(e).__name__


def parse(raw: str):
    try:
        return json.loads(raw)
    except Exception:
        return None


def diff(a, b, path="", out=None):
    if out is None:
        out = []
    if len(out) > 12:
        return out
    if type(a) is not type(b):
        out.append((path, "type", type(a).__name__, type(b).__name__))
        return out
    if isinstance(a, dict):
        for k in sorted(set(a) | set(b)):
            if k in VOLATILE:
                continue
            if k not in a:
                out.append((path + "." + k, "only_new", None, str(b[k])[:60]))
            elif k not in b:
                out.append((path + "." + k, "only_old", str(a[k])[:60], None))
            else:
                diff(a[k], b[k], path + "." + k, out)
    elif isinstance(a, list):
        if len(a) != len(b):
            out.append((path + ".len", "len", len(a), len(b)))
        for i, (x, y) in enumerate(zip(a, b)):
            diff(x, y, f"{path}[{i}]", out)
            if len(out) > 12:
                break
    elif a != b:
        out.append((path, "value", str(a)[:60], str(b)[:60]))
    return out


def error_summary(doc):
    if not isinstance(doc, dict):
        return {}
    e = doc.get("error") or {}
    if not isinstance(e, dict):
        return {}
    return {
        "type": e.get("type"),
        "retryable": e.get("retryable"),
        "retry_after": e.get("retry_after"),
        "message": str(e.get("message", ""))[:70],
    }


def main() -> int:
    global HOST
    ap = argparse.ArgumentParser(description="旧/新网关影子双跑对比")
    ap.add_argument("--host", default=DEFAULT_HOST, help="测试服地址")
    ap.add_argument("--old-port", type=int, default=OLD_PORT)
    ap.add_argument("--new-port", type=int, default=NEW_PORT)
    ap.add_argument("--ssh-host", default=f"lrb@{DEFAULT_HOST}", help="导出真实请求用的 SSH")
    ap.add_argument("--days", type=int, default=30, help="取近 N 天真实调用")
    ap.add_argument("--bodies", help="已有真实请求 JSONL，不指定则现场从旧库导出")
    ap.add_argument("--system", default="cs", help="X-System-Id，默认真实主调用方 cs")
    ap.add_argument("--repeat", type=int, default=1, help="重复轮数，用于观察 429 触发时机")
    ap.add_argument("--timeout", type=float, default=10.0)
    ap.add_argument("--include-writes", action="store_true", help="连写操作一起重放（危险）")
    ap.add_argument("--strict", action="store_true", help="有阻断差异时返回退出码 1")
    args = ap.parse_args()
    HOST = args.host

    calls = load_bodies(args.bodies) if args.bodies else export_calls(args.ssh_host, args.days)
    if not args.include_writes:
        calls = [c for c in calls if c["operation"] not in WRITE_OPS]
    if not calls:
        print("没有可用请求体", file=sys.stderr)
        return 2

    masked = sanitize_masked(calls)
    print(f"影子双跑：旧 {args.old_port} / 新 {args.new_port}，{len(calls)} 个真实操作，"
          f"{args.repeat} 轮，身份 {args.system}")
    if masked:
        print(f"（已把 {masked} 个日志脱敏的 account 置空，避免旧网关跨账号校验造成假阳性）")
    print()
    verdicts = Counter()
    blocking = []
    rate_limited = []

    for rnd in range(1, args.repeat + 1):
        if args.repeat > 1:
            print(f"── 第 {rnd} 轮 ──")
        print(f"{'operation':<34} {'旧':<8} {'新':<8} {'判定':<14} 差异")
        print("-" * 92)
        for c in calls:
            key = c["operation"]
            path = "/integrations/" + key.replace(".", "/")
            so, ho, bo = request(args.old_port, path, c["body"], args.system, args.timeout)
            sn, hn, bn = request(args.new_port, path, c["body"], args.system, args.timeout)
            if so == "ERR" or sn == "ERR":
                # 旧网关真连上游偶发超时（实测 judgeme.reviews.list），重试一次再判定，
                # 避免把网络抖动当成替换差异；新服务自身 ERR 仍算阻断。
                if so == "ERR":
                    so, ho, bo = request(args.old_port, path, c["body"], args.system, args.timeout)
                if sn == "ERR":
                    sn, hn, bn = request(args.new_port, path, c["body"], args.system, args.timeout)
            do, dn = parse(bo), parse(bn)
            ds = diff(do, dn) if (do is not None and dn is not None) else []
            if so == "ERR":
                verdict = "旧端超时"
            elif so != sn:
                verdict = "状态不同 <<<"
            elif ds:
                verdict = f"{len(ds)} 处字段差异"
            else:
                verdict = "完全一致"
            verdicts[verdict.split(" <")[0]] += 1
            note = "; ".join(f"{d[0]}{d[1]}" for d in ds[:2])
            print(f"{key:<34} {str(so):<8} {str(sn):<8} {verdict:<14} {note}")
            if so != sn and so != "ERR":
                blocking.append((key, so, sn, (error_summary(do), error_summary(dn))))
            if so in (429, 502) or sn in (429, 502):
                rate_limited.append(
                    (key, so, sn, error_summary(do), error_summary(dn),
                     ho.get("Retry-After"), hn.get("Retry-After"))
                )
        print()

    print("=== 汇总 ===")
    for k, v in verdicts.items():
        print(f"  {k}: {v}")
    print("\n=== 429 / 502 与重试契约 ===")
    if not rate_limited:
        print("  本轮未触发限流或上游错误（可用 --repeat 3 加压观察）")
    for key, so, sn, eo, en, ro, rn in rate_limited:
        print(f"  {key}: 旧 {so} {eo} Retry-After={ro} | 新 {sn} {en} Retry-After={rn}")
    print("\n=== 阻断性差异（状态码不一致）===")
    if not blocking:
        print("  无")
    for key, so, sn, (eo, en) in blocking:
        print(f"  {key}: 旧 {so} {eo} | 新 {sn} {en}")
    print("\n两端健康：")
    for port in (args.old_port, args.new_port):
        try:
            req = urllib.request.Request(f"http://{HOST}:{port}/health")
            res = OPENER.open(req, timeout=6)
            print(f"  {port} /health -> HTTP {res.status}")
        except Exception as e:
            print(f"  {port} /health -> {type(e).__name__}")

    return 1 if (args.strict and blocking) else 0


if __name__ == "__main__":
    sys.exit(main())
