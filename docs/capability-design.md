# 业务能力封装与逐项审阅

## 2026-11-05 封装深度复核

逐项对照运行注册目录与 Java 实现后，结论是：**151 个内置操作没有全部完成深度二次封装**。当前运行目录没有配置型自定义 HTTP 接口。管理端 CRUD 不作为业务深封完成项；批量调用及企微/DocuSign 入站回调另外记录。

按本次代码审阅口径，151 个操作中：

- 28 项已有较深的业务封装，涉及语义转换、关联补齐或多步骤编排。
- 68 项是增强封装，主要提供结果整理、校验、批处理或分页。
- 49 项仍是基础封装，以官方参数映射、单项读写或原始结果为主。
- 5 项为令牌、授权换码和桶管理等基础管理能力，按原子职责保留。
- 1 项仅用于模拟演练。

“深度”描述已实现的抽象程度，不代表没有缺口或已经完成供应商账号验收。一个较深接口也可能只支持特定场景。`business` 标签、推荐调用、统一认证/签名/重试和测试通过，均不能单独证明业务深度。

审批领域现有 24 项操作。模板和主流程之外，本轮增加按业务请求号追踪审批，补齐审批步骤、待处理人及明细字段业务别名，并联查假期规则核对单位。17 项跨平台聚合接口仍按增强封装评价，自动分页不等于完整业务流程。

逐项结果现在包含 `depth`、`depth_reason`、`remaining_gap` 和源文件/行号 `evidence`。这些字段由 `tools/capability_depth.py` 显式维护，生成器检查 151 项无遗漏，代码锚点不存在时拒绝生成。使用建议与封装深度分别展示在现有“封装说明”中。

本次复核修正了先前说明中不准确的描述：

- `nas.objects.read_head` 现已补齐常见文件签名识别、图片尺寸和 MIME 不一致提示；检查范围仍仅限文件头，不验证完整文件或压缩包中的实际内容。
- `lingxing.aftersales.reasons` 没有翻译或启用项过滤；多平台订单与订单详情仍较多保留原始结构。
- `winit.returns.create` 返回退货单号、RMA 和退货地址，面单仍需另查 `returns.label`。
- Shopify 补发其实已有 reference 查重和 SKU 唯一解析，退款已有余额/交易预检；应在现有基础上补齐并发、稳定业务编号、多支付和逐项退库边界。

优先深化的领域是领星售后关联、万邑通退货计划与面单、QuickCEP 会话全文与智能回复、DocuSign 业务角色预检，以及 Shopify 嵌套资源完整性。当前源码缺口已逐项记录，审阅结论不会将待实现建议计作完成。

操作目录的“封装说明”（接入文档也有入口）包含全部 109 个原有操作的逐项审阅，以及新增 42 个业务能力。每项列出现有行为、下一步建议、推荐接口和边界。数据源为 `CYT-Common-API/src/main/resources/capability-review.json`；生成器 `tools/build_capability_review.py` 会检查原契约是否新增或遗漏操作。推荐目标也通过注册表测试校验。

## 本轮易用性改造

- 操作授权支持调用方名称/标识搜索，平台、多个关键词、权限状态及只读/敏感筛选，跨页勾选、选中全部筛选结果，统一预览并保存。授权原因可直接定位冲突规则。
- `POST /admin/api/grants/batch/preview` 接收 `system`、明确的 `operations` 列表及 `effect`。保存到 `/admin/api/grants/batch` 时回传 `revision`；包含敏感项须 `confirm_sensitive=true`，存在冲突时须先处理或明确 `skip_blocked=true`。每批最多 500 项，事务提交并逐项审计。
- 批量操作保留已有精确规则的配额、有效期和备注，不清除其他拒绝规则，也不延长过期授权。规则变化后旧预览返回 409，须重新预览。无需变更项不新增规则；一次授权不包含未来新增接口。
- `wecom.approvals.track` 按当前调用系统、环境、模拟/真实模式查找原提交的 `request_id`，再核对上游应用指纹并读取最新审批。未记录的请求返回 404，凭据变更返回 409；均不推断为提交失败，也不会补提单。
- 审批详情新增 `steps`、`pending_approvers`、`notes`、`business_forms`，原 `timeline/comments/official` 保留。节点“转审”与单据“撤销”使用不同状态映射。一个上游模板绑定多个业务别名时分别返回，未绑定字段以控件 ID 为键；明细逐行还原。
- 假期配置返回单位/日历/发放方式；余额联查规则，失败时保留原始秒数并返回 `partial/errors`；修改余额先核对企业假期 ID 和单位，不代算实际排班时长。
- 通用采集固定上游每页大小；达到 `max_items` 时在页内续传。续传需保持 `page_size` 和筛选条件，页内记录发生变化返回 `cursor_stale`，由调用方重新扫描并按 ID 去重。游标不包含原始记录内容。
- NAS 文件头识别依据 [PNG 规范](https://www.w3.org/TR/png/) 与 [WebP 容器规范](https://developers.google.com/speed/webp/docs/riff_container)。PNG/JPEG/GIF/BMP 只读头部尺寸；较长 JPEG 头可能需增加 `head_bytes`。不执行像素解码、完整性校验或恶意内容扫描。

## 分层原则

原子适配器负责认证、签名和官方协议；业务服务负责模板解析、预检、批量编排、分页、结果规范化与错误说明；网关统一执行调用方认证、授权、配额、幂等、超时和脱敏日志。保留旧接口兼容，新增业务操作独立授权。

业务层只能调用代码中固定的只读原子能力，不接受调用方任意指定内部操作。授予邮件 search 即允许其读取同一凭据范围内的邮件详情，授予审批 collect 即允许其补齐审批详情，授权时应按这项业务能力的完整范围理解。

领域组织参考 [WxJava](https://github.com/binarywang/WxJava)；分页迭代参考 [Stripe Java](https://github.com/stripe/stripe-java/blob/master/src/main/java/com/stripe/model/StripeCollection.java)。只借鉴职责划分和易用接口，实际报文字段以当前官方文档为准，例如企微提单使用 process.node_list。

## 已实现的 P1 业务接口

- `gmail.messages.search`：发件人、主题、日期、未读、附件筛选；自动分页，默认补齐邮件摘要，按需解析 MIME 正文和附件元信息。单封详情错误保留 ID 和错误。
- `gdrive.files.search`：文件名、父目录、MIME、修改时间；正确转义查询文本并保留高级 query。
- `gcs.objects.collect`、`nas.objects.collect`：有界分页读取，NAS 保留目录前缀。
- `shopify.orders.collect`、`shopify.products.collect`：自动翻根连接页。
- `mabang.orders.collect`：保留订单筛选，自动处理原生游标。
- `judgeme.reviews.collect`、`trustpilot.reviews.collect`、`stripe.disputes.collect`、`paypal.disputes.collect`、`rd.products.collect`：统一客服来源的批量读取与完整性协议。

共同参数为 `page_size`、`max_items`、`max_pages`、`resume_cursor`。返回 `items/folders/errors/page/partial/coverage`，`page.next_cursor` 可续传。游标签名并绑定当前环境、模式、系统、操作和筛选条件。原生大小按来源上限压缩；请求达到上限返回续传位置，供应商给出异常超量页或重复游标时显式报错，不丢行假装完成。空页存在游标时继续读取。

`coverage=root_records` 只表示根记录范围。Shopify 大订单明细、大商品变体和退货内连接仍受原查询的 first 限制。退款列表的游标以订单为单位，展平后退款数可能大于订单数，所以没有强行套用通用收集器。后续应设计根游标加页内偏移、子连接游标。

Gmail 的日期筛选转换为 Unix 秒，避免官方字符串日期按 PST 解释。Drive 查询对单引号和反斜杠转义。官方依据：[Gmail 搜索](https://developers.google.com/workspace/gmail/api/guides/filtering)、[Drive 搜索](https://developers.google.com/workspace/drive/api/guides/search-files)。

## 本次修复与仍需深化的领域

修复了领星邮件查询把近期 start_date 扩展到一年前的问题，保留用户指定范围，并对非法、反向和超过 365 天的日期显式报错。

逐项审阅后的后续重点已经显示在能力指南，尚未实现的建议不会标记为已完成：

- 退款、退货、作废、库存覆盖：先生成可审核的业务计划，读取可退金额/数量、状态和并发版本，再执行明确的动作。金额、地址和业务原因不自动猜测。
- 领星售后、Winit 退货：关联 SKU/PID、仓库、渠道、箱尺和已退量，返回缺项说明。
- QuickCEP：聚合会话与正文，统一角色、坐席和可回复状态。
- DocuSign：模板角色预检、目录与信封的真实续传；当前 list 的单页结果不能当全量。
- NAS 大目录搬运：现有部分成功语义保留，后续改为持久作业、进度和失败重试。
- 企微消息：归一 invaliduser 等部分失败；日程 list_all 达上限时应支持续传。

这些需求涉及业务策略或供应商边界，当前保留兼容原子能力与逐项改造建议，不自动代替业务决策。

## 维护与验证

新增业务契约独立放在 `business-contract.json`，避免从原 Python 重导 contract 时覆盖。运行：

```sh
cd CYT-Common-API
python tools/build_business_contract.py
python tools/build_capability_review.py
mvn test
```

真实 MySQL 集成测试需显式设置 loopback 的 `TEST_MYSQL_URL`、用户名和密码，测试创建独立随机命名库并在结束后清理。测试涉及权限、防重放、模板转换、日期分段、续传、部分失败和回调；不向供应商产生真实业务写入。


## 2026-11-06 接入到调用的使用流程

主菜单为工作台、服务接入、对外能力、调用方、接入文档、运行中心，环境和全局配置归入设置。服务接入管理上游连接；对外能力展示提供给业务系统的能力；接入文档提供独立的快速接入与接口参考入口，也可从调用方打开专属资料。旧链接继续转到对应上下文。企微审批仍位于企业微信服务内。

接口目录、对外能力和能力授权按现有接口的 `category` 自动生成业务模块，显示各模块当前匹配数量。模块随平台及能力范围更新，可与搜索、状态和权限筛选组合使用；未填写分类的接口归入“未分类”。新增接口可选择已有模块或输入新模块，从模块目录进入时默认带入当前模块。模块筛选只改变展示与选择范围，不修改接口路径和已有授权。

- 第三方服务：工作台选择“接入第三方服务”，找到适配器或新增 HTTP 服务，配置上游凭据，进入服务接口验证，再到调用方分配能力。
- 自有系统：工作台选择“开放自有系统接口”，登记来源为 internal 的服务，配置接口、参数和返回值，启用并调试；随后在对外能力中查看，分配给调用方。
- 业务系统调用：创建或选择调用方，在同一工作区管理密钥、批量授权及专属文档。导出的 OpenAPI 由服务端按当前有效授权筛选，默认不包含未精确授权的敏感能力。

来源字段 service_kind 只负责目录分类，不自动改变网络信任或权限。内网服务必须由部署管理员在 INTERNAL_API_ALLOWED_ORIGINS 中登记完整来源（协议、主机、端口），连接继续使用解析后固定的地址；回环、云元数据和其他保留地址保持受限。

本轮已实现 DocuSign 模板角色/字段读取、目录与信封的原生分页及有界汇总、作废前状态核对，QuickCEP 消息与领星订单/产品的有界汇总，并修复领星工单未传递已登记顶层字段的问题。业务角色选择、客服发送身份、售后策略及跨系统事务仍按逐项边界记录，不把菜单整理或基础分页计为全部深度封装完成。

信息组织参考 [Apache APISIX 的 Route / Upstream / Consumer 分离](https://github.com/apache/apisix/blob/master/docs/en/latest/admin-api.md) 与 [WSO2 的发布和消费流程](https://apim.docs.wso2.com/en/latest/api-developer-portal/publish-an-api-to-dev-portal/)，结合本项目已有适配器与操作授权实现，不复制其全部技术菜单。

接入文档和调用方内均提供“下载接入包”：包含按当前授权筛选的 START_HERE.html、openapi.json、Python / TypeScript SDK 和 README，不包含密钥。管理员导出后将压缩包和实际可访问的网关地址交给接入方，API Key 单独交付。接入方解压并打开 START_HERE.html 即可离线阅读、按模块检索和复制示例，无需后台账号；这不是公开的管理后台或订阅门户。OpenAPI 通过 x-cyt-operation 标明 SDK 操作名，通过 x-cyt-effective-quota 标明调用方实际配额，并标注模块、只读和必填幂等策略。

## 调用方向与响应契约

- 服务接入：我们调用领星、企微等第三方平台。即使做了深度业务封装，仍归第三方接入，不转入自有对外能力。
- 对外能力：明确登记为 internal 的自有系统向其他系统提供业务接口。OA 组织花名册只是设计示例，未注册、未执行。部门负责人不能无条件推断为直属领导，示例明确列出来源与未确定状态。
- 文档、调试、授权分别显示调用方向。direction=internal / third_party 用于导出对应接口；不传方向的既有导出仍兼容全部授权范围。方向过滤与调用方授权共同生效。
- 配置接口新增 response_schema_json 描述经响应取值路径处理后的业务数据，文档与调试展示完整 HTTP 字段路径。配置型 HTTP 的业务体仍位于 data.data，不改变既有运行结果包装。
- 已逐项描述的响应契约目前包括企微组织目录和通用分页汇总；其他内置操作若尚未登记业务响应字段，会明确显示待补齐，不根据模拟数据推测。SchemaFields 展开嵌套对象、数组、本地引用及空值类型。
