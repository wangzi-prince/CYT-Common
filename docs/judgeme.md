# Judge.me 公开回复

Java 网关与 Python 网关使用同一契约：`judgeme.reviews.reply`，
路径 `POST /integrations/judgeme/reviews/reply`。

```json
{
  "account": "fixture",
  "review_id": 111,
  "body": "Thank you for your feedback. Please contact our support team.",
  "send_reply_email": false,
  "confirm": true
}
```

按账号配置 `JUDGEME_<ACCOUNT>_SHOP_DOMAIN` 和 `JUDGEME_<ACCOUNT>_API_TOKEN`，
使用 Private API Token。指定账号不会回退到共享凭据。`review_id` 必须是
Judge.me 的评论 ID，不能使用商品 ID 或中台主键；正文必须非空且最多 10000 字。

操作为敏感写入，调用方须有 `judgeme.reviews.reply` 精确授权。已有环境在
授权管理中添加，seed 声明只用于初始化，不会自动重建已撤销的授权。
`confirm=true` 才发布，通知邮件默认关闭，可显式设为 true。

使用稳定的 `Idempotency-Key`；超时或结果不确定时不得自动重发。
适配器调用官方 `POST https://api.judge.me/api/v1/replies`，成功后返回
`data.ok=true` 及本次评论 ID、账号、正文和通知选项。模拟模式明确返回
`data.ok=false`，不会声称真实发布。

中台保留起草、审批和执行流程。发送成功后自动写入“已回复”、正文、回复人
和确认成功时间；模拟结果、失败或超时保持待回复，执行记录进入待核对状态。
Judge.me 评论列表不返回回复内容，后台手动回复仍需人工登记。

官方文档：https://judge.me/api/docs（Replies / Create）。未对真实评论发帖。
