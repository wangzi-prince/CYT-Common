# 业务调用 SDK

无供应商 Secret，业务系统只持有网关 API Key。Python 版本仅使用标准库（3.10+）；TypeScript 使用标准 fetch，可直接复制或纳入项目编译。

Python：将 `sdk/python` 加到模块路径，或复制 `cyt_gateway.py`。

```python
import os
from cyt_gateway import Gateway

gateway = Gateway("http://127.0.0.1:8189", os.environ["CYT_API_KEY"])
form = gateway.approvals.form("expense")
fields = {"reason": "购买办公用品", "amount": "99.50"}
prepared = gateway.approvals.prepare("expense", "zhangsan", fields)
if not prepared["valid"]:
    raise ValueError(prepared["errors"])
result = gateway.approvals.submit(
    "expense", "zhangsan", fields,
    request_id="expense-order-20261105-001",
    template_version=prepared["template_version"],
)
print(result["approval_no"])
```

TypeScript：

```ts
import { Gateway } from './cyt-gateway'; // 指向复制的 sdk/typescript/index.ts

const gateway = new Gateway('http://127.0.0.1:8189', process.env.CYT_API_KEY!);
const fields = { reason: '购买办公用品', amount: '99.50' };
const prepared = await gateway.approvals.prepare('expense', 'zhangsan', fields);
if (!prepared.valid) throw new Error(JSON.stringify(prepared.errors));
const result = await gateway.approvals.submit(
  'expense', 'zhangsan', fields, 'expense-order-20261105-001',
  { template_version: prepared.template_version },
);
```

模板别名和字段名需要先注册，示例中的 userid 需要替换。金额推荐字符串，避免浮点精度变化。新业务编号由业务系统生成并持久保存；重复请求保持原编号。客户端不自动重试写操作。

其他操作通过通用 `call('wecom.vacation.get', {'userid': 'zhangsan'})` 或 `call('wecom.approval_templates.clone', params, request_id=...)` 调用，完整参数见网关 OpenAPI 和接口调试示例。

有界分页按批次返回，而不是自动加载无限数据：

```python
from cyt_gateway import CollectionLimitError

try:
    for batch in gateway.collect(
        "gmail.messages.search", sender="supplier@example.com",
        since="2026-11-01", include_body=True, max_items=100,
        max_pages=5, max_requests=10,
    ):
        consume(batch["items"], batch.get("errors", []))
except CollectionLimitError as error:
    save_resume_cursor(error.next_cursor)  # 之后显式续传，不能当作已读完
```

审批汇总使用 `gateway.collect('wecom.approvals.collect', ...)`，SDK 自动选择 cursor；其他业务聚合使用 resume_cursor。即使空页也按 has_more 与游标继续；达到 max_requests 时抛出携带续传游标的 CollectionLimitError。业务需处理 partial/errors，并按来源 ID 去重。

`GatewayError` 保留 HTTP 状态、错误码、trace_id 和 retryable。上游或网络异常发生在提交之后时，可能已产生业务结果，应先核对原编号。不要因客户端超时换编号重试。

验证：`cd sdk/python && python -m unittest -v`。TypeScript 模块可由项目 TypeScript 编译器检查，不需要独立安装依赖。

审批提交后可调用 Python `gateway.approvals.track(request_id)` 或 TypeScript `gateway.approvals.track(requestId)`，按稳定业务请求号取得最新状态、待处理审批人和已绑定的业务字段。该操作需独立授权 `wecom.approvals.track`；未记录或凭据变更不应自动重新提交。
