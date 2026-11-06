# 企微通用审批接入

入口：管理后台 → **接入平台 → 企业微信 → 查看接口**，选择“审批业务接口”。审批与通讯录、消息、日程属于同一个企微平台，统一注册、授权、文档和调试。所有业务调用继续使用 `/integrations/wecom/...`。

`approvals.submit` 内部已经完成模板读取、字段转换、校验和摘要生成，业务方一次调用即可提单。独立 `prepare` 用于提前反馈字段问题。可在企微接口目录点击“按模板调试”，在同一目录的弹窗中加载表单；它只是一种调试辅助，业务系统直接调 API 即可。新模板、假期和审批引擎等操作也在同一企微接口目录。

本次新增 23 个审批领域操作。范围是企业自建应用可以使用的官方服务端审批 API，另提供 JS-SDK 审批引擎参数生成。不是代替员工审批的机器人。

## 最短接入流程

1. 注册调用方系统并生成独立 API Key。
2. 在“环境与凭据”中配置该系统的企微应用凭据；在企微后台授予审批数据和模板权限。
3. 从企微管理后台获取模板 ID，用 `wecom.approval_templates.get` 查看字段。
4. 可用 `wecom.approval_templates.bind` 注册业务模板别名及字段名，如 `expense`、`reason`、`amount`。绑定不会修改上游模板。
5. 授予模板读取、预检、提交和查询所需的操作权限。提交需要 **wecom.approvals.submit 精确授权**，通配授权不能替代敏感操作授权。
6. 预检通过后提交，并保存返回的 `approval_no`。配置结果回调或增量拉取事件。

真实模板 ID 由企微提供。没有服务端接口可以列出企业全部模板；`approval_templates.list` 只列出当前系统已绑定的模板。模拟模式可直接加载 `demo-expense`，其中的人员、附件和单据均为合成示例。

## 业务调用

所有请求使用 `Authorization: Bearer <系统 API Key>`，成功返回 `{ok, data, meta}`。路径中的下划线转换为连字符，例如 `wecom.approval_templates.get` 对应 `POST /integrations/wecom/approval-templates/get`。

绑定时将真实控件 ID 映射为稳定业务名：

```json
{
  "template_id": "<企微模板 ID>",
  "alias": "expense",
  "field_aliases": {"reason": "Text-01", "amount": "Money-02"}
}
```

控件 ID 必须以实际模板为准，不能照抄示例。也可直接用唯一中文标题填写；同名字段必须用别名或控件 ID 消除歧义。

`POST /integrations/wecom/approvals/prepare`：

```json
{
  "template": "expense",
  "applicant": "zhangsan",
  "fields": {"reason": "购买办公用品", "amount": "99.50"}
}
```

预检只读取模板，不会提单。返回 `valid`、全部字段错误、`template_version` 和生成的官方 `payload`。`valid=false` 是一次成功的预检响应，调用方必须检查它。

`POST /integrations/wecom/approvals/submit` 使用同样参数，另加：

```json
{
  "template": "expense",
  "applicant": "zhangsan",
  "fields": {"reason": "购买办公用品", "amount": "99.50"},
  "request_id": "expense-order-20261105-001",
  "template_version": "<预检返回的版本>"
}
```

`request_id` 使用稳定业务单号，重试必须相同。也发送 `Idempotency-Key` 时两者必须一致。同编号与同参数会重放原结果；不同参数返回 409。上游超时或结果不确定时，网关不自动重发写请求，原幂等记录保持待核对状态。先检查调用日志、回调和原申请，不能用新随机编号掩盖可能已成功的提交。模式/环境变化也不会重放另一个模式的成功结果。

默认使用模板的审批流程，自动从文本和金额等字段生成最多三行摘要。需要自定义流程时，显式提供：

```json
{
  "flow": [
    {"type": "approve", "mode": "all", "users": ["manager"]},
    {"type": "notify", "users": ["finance"]}
  ]
}
```

`type` 为 approve / notify / handle；`mode` 为 all / any / sequential。网关生成当前官方的 `process.node_list` 结构。申请人、审批人和业务金额由调用方明确指定。

## 字段转换

- Text / Textarea：字符串。单行文本拒绝换行。
- Money / Number：推荐十进制字符串，保留精度与零值。
- Date：ISO 日期、日期时间或 Unix 秒/毫秒。没有时区的日期时间按北京时间解释。
- Selector：选项 key 或唯一显示名；多选为数组。未知或歧义选项会报错。
- Contact：成员 userid / 部门 ID 数组，根据模板判断成员或部门。
- File：`file_id` 数组；先用 `approval_files.upload` 上传 Base64 内容。单文件不超过 10MB，每单附件合计最多 6 个，包括明细内附件。
- Table：每行一个字段对象，自动补齐明细里的空可选控件，并校验必填子字段。
- DateRange / Attendance：`start_at`、`end_at`、明确的 `duration_seconds`；不从经过的钟表时间猜工时。半天边界为北京时间 00:00 或 12:00。
- Vacation：上述时间字段再加 `leave_type`（模板中的假期类型名称或 ID），可指定 granularity。
- Location：显式提供经纬度、标题、地址和时间。
- RelatedApproval：审批单号数组。
- Formula / Tips 等自动展示或计算控件无需填写。

需要新的或特殊官方控件时，可传 `{"$value": {"<官方值字段>": "..."}}`，或使用 `apply_data.contents`。高级值必须遵循对应官方 API，`fields` 与 `apply_data` 不能同时传入。网关不会猜测文档未明确支持的控件格式；收款账户等详情会保留在官方结构中，真实可提交范围由企微接口决定。

## 模板管理

`approval_templates.create` 接受 `name` 和 `fields`，字段包括 `key`、`label`、`control`、`required`，选择控件可提供 `options`，明细提供 `children`。网关生成官方要求的“控件类型-数字”ID 并返回业务字段映射。需要特定配置时使用 `config` 或完整 `template_content`。

`update` 更新完整模板内容，优先按已绑定字段名或唯一标题保留原控件 ID；重命名时应显式提供原 `id`。改变控件类型需要新增字段。官方保留已配置的审批流程和规则，但业务方仍应在管理后台核对规则与新字段的关系。

`clone` 读取原模板后创建新模板，只复制控件，不复制审批流程和管理规则。新模板使用企微默认流程，应在正式提单前确认。`unbind` 仅删除本系统的本地别名。

创建、更新、复制模板都需要稳定 request_id（或 Idempotency-Key）和相应的精确敏感授权；不要把同一个编号用于不同操作。

## 查询、事件与假期

`approvals.get` 返回统一状态、申请人、字段、流程、评论及 `official`。未知状态保留为 unknown，不判断为终态。`get_many` 返回 `items/errors/summary`，单项失败不会丢失其他结果。

`list/collect` 默认近 7 天，可按模板别名、申请人、状态及部门筛选。总范围最多 366 天，自动拆成每段不超过 31 天。只根据官方游标判断结束，即使空页也继续。`collect` 按单号去重，达到 `max_items/max_pages` 时返回 `partial=true` 和 `page.next_cursor`。续传传入 `cursor`，保持原筛选条件；游标绑定环境、模式、系统和查询条件。跨段边界和不同请求批次仍可能重复，业务侧以 approval_no 去重。详情失败也会设置 partial，不能仅按 items 数量判断全量。分页不是一致性快照。

结果接收地址：`/integrations/wecom/webhooks/{system}/events`。在企微后台配置同一地址、Token 和 EncodingAESKey。GET 验证 URL，POST 接收事件；验签、AES 解密和企业接收者校验通过后，事件加密入库并应答。

应用共用时，由网关提交的审批按提交记录分发到原调用系统；未知来源审批和 JS-SDK 引擎事件归接收 URL 对应系统，不向所有系统广播。应用凭据轮换会改变内部应用指纹，轮换期间旧单需保留原回调路由或人工核对，避免把未映射事件当成其他系统数据。

业务系统可二选一或同时使用：

- 在“回调配置”添加平台 wecom 与推送地址。事件失败自动退避重试，最多 10 次；无地址时为 poll_only。配置转发 HMAC 凭据后，按原始请求体验证 `X-Cyt-Signature`。
- 调用 `approval_events.list`，保存 `next_after_id` 作为下一次 after_id。使用 event_id 去重，至少一次投递。`retry` 仅重试本系统 failed/poll_only 事件。模拟模式不读取或重投真实事件。

假期能力：`vacation.config/get/set`。读取返回秒数；修改显式选择 days/hours，校验 0.1 单位精度、confirm=true 和稳定业务编号。这里的 1 天按官方时间单位为 86400 秒，不自动视为八小时工作日。

`engine_launch` 只生成企微客户端 thirdPartyOpenPage 参数，返回 submitted=false；需在客户端完成 JS-SDK config/agentConfig。`engine_get` 按 third_no 查询引擎状态。`legacy_get` 保留旧审批接口兼容，单段最多 30 天，新项目优先使用 list/collect。

## 凭据与权限

优先解析调用系统专属 `WEWORK_{SYSTEM}_CORP_ID`、`WEWORK_{SYSTEM}_APPROVAL_SECRET`，可在凭据页面配置。未单独指定审批 Secret 时，按当前凭据策略使用该系统的企微应用 Secret；应用必须拥有相应审批权限，不能只凭通讯录或消息权限调用。

回调使用 `WEWORK_{SYSTEM}_CALLBACK_TOKEN`、`WEWORK_{SYSTEM}_CALLBACK_AES_KEY`（43 位）。共享变量为 `WEWORK_APPROVAL_SECRET`、`WEWORK_CALLBACK_TOKEN`、`WEWORK_CALLBACK_AES_KEY`，是否允许继承由凭据隔离策略决定。业务 API 不接受调用方随意传入 Secret。

模板别名与提交映射隔离环境、调用系统和 mock/live。审批数据读取范围受所选应用权限控制；需要更细的“仅本人单据”等业务规则时，应在调用方系统中实施，不应把共享应用的全部可见范围直接开放给普通终端用户。

本次验证采用模拟上游、官方加密示例、隔离 MySQL 和前端构建；真实应用权限、企业模板控件及回调公网地址仍需在专用测试企业联调。没有发送真实审批或修改真实假期余额。

官方依据：[模板详情](https://developer.work.weixin.qq.com/document/path/91982)、[提交申请](https://developer.work.weixin.qq.com/document/path/91853)、[查询审批](https://developer.work.weixin.qq.com/document/path/91816)、[创建模板](https://developer.work.weixin.qq.com/document/path/97437)、[更新模板](https://developer.work.weixin.qq.com/document/path/97438)、[审批回调](https://developer.work.weixin.qq.com/document/path/91815)。
