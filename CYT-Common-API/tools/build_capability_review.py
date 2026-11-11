"""A code-backed review of every built-in operation; fail if the contract drifts."""
import json
from pathlib import Path
from capability_depth import attach_depth
ROOT = Path(__file__).resolve().parents[1]
base = json.loads((ROOT / 'src/main/resources/contract.json').read_text(encoding='utf8'))
business = json.loads((ROOT / 'src/main/resources/business-contract.json').read_text(encoding='utf8'))
reviews = {}
def review(key, current, proposal, caveat='', assessment='enhance', recommended=()):
    assert key not in reviews, key
    reviews[key] = dict(current=current, proposal=proposal, caveat=caveat, assessment=assessment, recommended_operations=list(recommended))

# Each statement describes the current Java adapter, not an assumed vendor feature.
review('docusign.envelopes.create', '已将模板角色、文件、签署顺序转换为信封请求。', '补充 prepare：核对模板角色、必填签署人和文件，再生成签署业务请求。', '发送与作废是有业务影响的写操作；超时后应核对原单。')
review('docusign.envelopes.get', '已合并信封与签署人两次查询，返回签署进度。', '增加 terminal、下一签署人和逾期提示，保留原始状态。', assessment='ready')
review('docusign.envelopes.list', '按日期/状态读取单页，返回下一页位置和可见总量。', '使用新增有界 collect 汇总，保留原子单页接口。', '上游分页不是一致性快照，业务按 ID 去重。', 'recommended', ['docusign.envelopes.collect'])
review('docusign.envelopes.void', '读取当前状态，重复作废返回已作废，阻止已完成、拒绝或删除的信封再作废。', '继续保持明确原因与稳定业务重试策略。', '状态读取和更新之间仍有竞争，供应商做最终校验。', 'ready')
review('docusign.templates.list', '规范模板目录并返回真实下一页位置。', '用 templates.collect 汇总、templates.get 读取模板角色和字段。', '不自动指定签署人和签署策略。', 'recommended', ['docusign.templates.collect', 'docusign.templates.get'])
review('docusign.testing.advance', '模拟器推进信封状态，便于回调演练。', '仅作为演练工具展示，真实签署由签署人完成。', '真实模式不可代替签署人完成签字。', 'test_only')

for key in ['gcs.objects.list', 'gdrive.files.list', 'gmail.messages.list']:
    target = {'gcs.objects.list':'gcs.objects.collect','gdrive.files.list':'gdrive.files.search','gmail.messages.list':'gmail.messages.search'}[key]
    review(key, '保留供应商筛选和分页参数，适合作为单页原子接口。', '优先使用新增业务接口：有界自动分页、统一结果与签名续传游标。', '原接口继续兼容；items 只代表当前页。', 'recommended', [target])
review('gcs.objects.delete', '对象路径转换为 Cloud Storage 删除请求。', '提供可选代际 generation 条件，删除前校验版本，避免覆盖并发更新。', '删除不可恢复；业务侧需确定保留策略。')
review('gcs.objects.get_metadata', '可读取对象的原始元信息。', '统一 size、content_type、etag、generation、updated_at 字段。', assessment='ready')
review('gcs.objects.upload', '处理 Base64 内容、对象路径和 MIME 上传。', '增加防覆盖条件和上传结果 checksum 校验；大文件改用签名直传。', 'Base64 更适合小文件；不应默认覆盖同名对象。')
review('gdrive.files.get', '支持按文件 ID 与 fields 读取元信息。', '提供规范化路径、权限摘要和 shortcuts 目标信息。', '元信息查询不等于下载文件内容。', 'ready')
review('gdrive.files.upload', '已处理文件内容、名称、MIME 与父目录。', '增加按业务目录定位、重复名称策略及上传后校验。', 'Drive 名称不唯一，不能把名称当文件 ID。')
review('gdrive.folders.create', '把文件夹名称和父 ID 转成 Drive folder 创建。', '增加 ensure_folder：按父目录和名称查重，发生重名时明确返回候选。', '不能随意选择同名目录；现接口保持显式创建。')
review('gmail.labels.list', '读取当前授权邮箱的标签。', '可为业务查询建立标签名到 ID 的唯一映射，并展示系统标签。', assessment='ready')
review('gmail.messages.get', '可选 metadata/full/raw 格式，读取单封邮件。', '新增 search 已自动读取详情并整理主题、发件人、MIME 正文和附件元信息。', '附件内容仍需独立下载能力；HTML 正文需要下游安全渲染。', 'recommended', ['gmail.messages.search'])
review('gmail.messages.restore_from_spam', '核实测试邮箱身份，且邮件必须在 SPAM、不在 TRASH。', '保留严格目标校验，可增加操作前后标签差异。', '仅测试白名单邮箱可写。', 'ready')
review('gmail.messages.send', '已组装 UTF-8 MIME，验证邮箱及全部收件人白名单。', '补草稿预检、模板变量检查和稳定业务发送编号。', '目前仅测试白名单可写；发送超时不代表未发送。')
review('gmail.profile.get', '返回实际 OAuth 授权邮箱，并验证 account 匹配。', '适合作为接入后的身份核对步骤。', 'account 参数不能切换到未授权邮箱。', 'ready')

review('judgeme.reviews.list', '将评价归一为客服数据结构，保留来源与游标。', '使用 collect 自动分页并显示部分失败；跨来源分析仍需统一评分尺度。', assessment='recommended', recommended=['judgeme.reviews.collect'])
review('judgeme.reviews.reply', '按账号、Judge.me 评论 ID 和确认参数公开回复，默认不发通知邮件。', '调用方审批通过后执行，明确成功再保存已回复；超时后人工核对。', '公开回复可用 judgeme.replies.list 回读；不提供平台回复编辑和删除写操作。', assessment='ready')
review('judgeme.replies.list', '读取官方公开组件的回复正文，区分明确空值和缺字段，分页覆盖商品与店铺评价。', '调用方按账号和评价原文唯一关联私有 ID，保存检查点并处理平台缓存延迟。', '只覆盖公开评价的公开回复；UUID 不等于私有评价 ID，平台不返回回复时间。', assessment='ready')
review('trustpilot.reviews.list', '将评价与业务来源关联，维护分页游标。', '使用 collect 自动分页；后续可添加回复状态与低分原因分类。', assessment='recommended', recommended=['trustpilot.reviews.collect'])
review('stripe.disputes.list', '已整理争议状态、证据和来源单号。', '使用 collect 汇总；截止日期和可提交动作应以供应商状态为准。', '不自动提交证据或接受争议。', 'recommended', ['stripe.disputes.collect'])
review('paypal.disputes.list', '读取争议并补齐取证详情，归一客服字段。', '使用 collect 汇总；进一步可生成取证缺项清单。', '详情补齐存在额外调用成本；当前来源详情失败会使该页报错，后续可深化为逐条失败保留。', 'recommended', ['paypal.disputes.collect'])
review('rd.products.list', '目录适配器将来源产品规范为统一列表。', '使用 collect 保持统一读取协议；后续增加 SKU 冲突提示。', '来源若不提供分页，collect 的完整性仍取决于来源快照。', 'recommended', ['rd.products.collect'])

review('lingxing.aftersales.reasons', '整理原因 ID、原因文字和创建信息；无翻译和启用项过滤。', '按原因用途建立业务字典，保留来源 reason_id，避免按译名提交。', assessment='ready')
review('lingxing.inventory.fba', '转换店铺/SKU 条件，返回 FBA 库存。', '统一可售、在途、预留单位与更新时间，禁止跨店铺简单相加。')
review('lingxing.inventory.list', '按仓库和 SKU 读取本地库存明细。', '增加 availability 查询，将仓库、可售和预留合成业务视图。', '本地仓与 FBA 含义不同，不能混为同一库存。')
review('lingxing.listing.list', '读取亚马逊 Listing 及库存字段。', '增加按站点/SKU 的业务查询，显式显示分页与缺失字段。', 'Listing 库存是渠道维度，不等于仓库可用量。')
review('lingxing.messages.detail', '已整理邮件主题、正文、收发地址与附件。', '与列表组合成有界邮件查询，并逐封返回补齐错误。', assessment='ready')
review('lingxing.messages.list', '规范邮件条目与 offset 游标，日期范围随游标保持稳定。', '本次修复用户指定短日期被扩到一年的问题；后续增加列表与正文的有界聚合。', '已校验正向日期和最多 365 天；仍是单页查询。')
review('lingxing.mporders.list', '按多平台条件读取订单，主体返回 raw/total/request_id。', '增加跨平台订单检索与平台单号/SKU 映射，显式标识部分行项目。')
review('lingxing.orders.detail', '读取亚马逊订单详情，主体保留供应商结构。', '增加与库存、物流、售后工单的关联查询。', '个人信息权限仍以授权账号为准。', 'ready')
review('lingxing.orders.list', '支持日期筛选，规范订单与分页字段。', '增加有界 collect 与增量同步水位；稳定排序下按订单 ID 去重。')
review('lingxing.product.list', '将本地 SKU、PID、品牌和状态整理成业务目录。', '增加唯一 SKU 解析，明确重复与无匹配错误，供工单自动补全。', assessment='ready')
review('lingxing.refund.create', '把订单与明细转换为退款请求。', '先读订单与已退款量，生成退款预检和可退金额说明。', '不可替调用方决定退款金额；需要真实订单验收与稳定幂等编号。')
review('lingxing.return.qty', '联合返回退货数量和本地产品 ID。', '供退货预检校验剩余数量，明确累计值和本次值。', assessment='ready')
review('lingxing.seller.list', '已规范店铺 ID、名称、国家等字段。', '作为其他业务接口的店铺选择字典，处理同名店铺。', assessment='ready')
review('lingxing.warehouse.list', '读取仓库目录。', '统一仓库编码、类型、地区和启用状态，供库存业务筛选。')
review('lingxing.workorder.add', '限定退货类型并转换工单字段。', '增加 prepare：通过订单、SKU 和退货数量补齐 PID、原因和仓库。', '业务原因和退货数量需调用方确认，不能自动猜测。')
review('lingxing.workorder.audit', '把已有工单提交到审批流程。', '核对工单状态及缺项；返回可继续操作或需人工处理的状态。', '提交审核与审批通过不同。')
review('lingxing.workorder.list', '读取售后工单并规范分页。', '增加按订单的售后进度视图，连接审核、退货与退款状态。')

review('mabang.orders.list', '已封装马帮签名、筛选和 cursor 翻页。', '优先使用新增有界自动分页接口，减少调用方的游标处理。', assessment='recommended', recommended=['mabang.orders.collect'])
review('mabang.orders.track', '支持最多 100 个订单查询，并整理物流状态。', '可补批量拆组和每单错误，不因一个失败丢失其他物流。', '物流事件不等于订单结算状态。', 'ready')

review('nas.buckets.create', '处理 S3 桶创建和区域配置。', '保持管理员原子操作；业务系统通常只需被分配的桶或目录。', assessment='low_level')
review('nas.buckets.delete', '删除指定桶，供应商要求为空。', '增加桶非空预检，展示对象量后再决定删除。', '不应隐式清空桶。', 'low_level')
review('nas.buckets.list', '将 S3 桶目录整理为名称与创建时间。', '保留运维入口；可按业务授权限制可见桶。', assessment='low_level')
review('nas.objects.copy', '服务端复制单对象，返回结果元信息。', '增加覆盖策略和源版本条件，明确 copy 与 move 的差异。', '并发写入期间复制不是快照事务。')
review('nas.objects.copy_prefix', '已有整目录复制/移动、最多 50000 对象、逐项失败；全部复制成功才删除源。', '大目录改为后台作业，支持进度、取消和按对象重试。', '跨对象不具原子性，可能部分成功；移动后应检查 failed。', 'ready')
review('nas.objects.delete', '删除单对象。', '增加保留策略检查或软删除业务接口。', '当前永久删除不可恢复。', 'low_level')
review('nas.objects.delete_batch', '已有批次拆分及逐项 deleted/errors 返回。', '增加 dry_run 与业务范围检查，保留每个对象失败原因。', '必须处理部分失败。', 'ready')
review('nas.objects.list', '支持目录分隔、递归和 S3 续传标记。', '优先用新增 collect，保留 folders 与统一分页信息。', '目录是对象前缀，不是真实文件系统目录。', 'recommended', ['nas.objects.collect'])
review('nas.objects.presign_get', '已批量生成带期限的临时读链接。', '对外作为文档下载能力，明确到期时间与逐项结果。', '链接属于临时访问凭证，勿写入公开日志。', 'ready')
review('nas.objects.presign_put', '自动生成键名、规范扩展名，签发直传链接。', '增加上传完成回执与 head 校验，确认大小和真实类型。', '签发成功不等于文件已上传。', 'ready')
review('nas.objects.read_head', '读取有限字节并返回 Base64 与上游 Content-Type；未实现自动类型识别或图片尺寸解析。', '补 magic bytes 和图片尺寸解析，再与上传回执结合形成通用文件验收。', '当前文件头的业务解析仍由调用方完成。', 'enhance')
review('nas.objects.stat', '整理对象大小、MIME、etag 和更新时间。', '作为上传后验证与下载前检查的基础。', assessment='ready')

review('quickcep.analytics.get', '按时间和渠道读取统计数据。', '统一指标口径、时区与零分母处理，返回数据覆盖范围。', '首响、流失等指标的口径需要保留来源解释。')
review('quickcep.messages.list', '已转换会话 ID、页码，返回 total/total_page。', '增加 transcript：自动翻页、按时间排序、发件角色规范化。')
review('quickcep.messages.send', '已区分坐席回复/访客模拟，检查坐席 owner_id。', '增加 reply：从会话读取渠道和坐席，预检可回复状态与稳定 mid。', '真实发送有业务影响；访客模拟须明确。')
review('quickcep.sessions.by_operator', '将坐席、会话类型和排序转为供应商参数。', '增加坐席名/ID 解析与有界列表，明确未接收筛选。')
review('quickcep.sessions.close', '按会话 ID 关闭，保留幂等语义。', '增加当前状态和最终关闭结果，让重复关闭可直接理解。', assessment='ready')
review('quickcep.sessions.create', '查询或创建会话，支持第三方会话号和测试模式。', '按业务会话号提供 ensure_session，并返回 created 与 resolved。', '测试 mode=2 与正式会话需要明确区分。')
review('quickcep.sessions.get', '返回会话详情，但主体仍是供应商字段。', '整理渠道、参与人、坐席、未读、可回复状态与下一动作。')
review('quickcep.sessions.list', '按创建/关闭日期读取会话 ID，分离有效 ID。', '增加日期拆分、分页与详情聚合，返回每项错误。', '当前返回的是 ID 列表，不是完整会话。')
review('quickcep.staff.list', '读取供应商坐席目录。', '归一 staffId/userId、显示名、在线和接待状态，提供唯一解析。')

review('shopify.inventory.set', '将库存覆盖转换为 GraphQL mutation。', '增加库存预检、可选 compareQuantity 并发校验，显示库位与变动量。', '覆盖值与增量不同，不能自动猜测库位。')
review('shopify.orders.create', '已有业务 reference 查重、SKU 唯一匹配、地址校验和零金额补发，并限制测试店铺。', '补独立的补发预检计划、原订单关联和并发唯一性。', '测试店铺限制保留，不能宣称已开放正式订单写入。')
review('shopify.orders.get', '合并 GraphQL 订单及明细查询。', '增加业务字段视图和嵌套连接完整性信息。', '嵌套 lineItems/transactions 的 first 上限不代表全量。', 'ready')
review('shopify.orders.list', '已整理订单结果和 GraphQL page_info。', '优先用 collect 自动翻根订单页；后续单独补齐大订单嵌套连接。', '本次 collect 的 coverage=root_records，不承诺嵌套明细全量。', 'recommended', ['shopify.orders.collect'])
review('shopify.orders.update', '转换备注、标签与收货地址更新。', '增加字段级变更预览；标签区分追加、删除和全量替换。', '覆盖标签前需读取最新状态。')
review('shopify.products.get', '读取商品、变体和库存关联信息。', '提供按 SKU 的唯一变体解析，展示重复 SKU 与子连接截断。', assessment='ready')
review('shopify.products.list', '返回商品页和 GraphQL page_info。', '优先用 collect 自动翻商品根页，再按需要补齐变体。', '不能把根分页完成等同于全部嵌套变体已读取。', 'recommended', ['shopify.products.collect'])
review('shopify.refunds.create', '已读取订单余额及成功 SALE 流水，校验可退金额并构造退款/退库请求。', '补独立的退款计划、强制稳定业务编号、多支付分摊和逐项退库。', '退款不可逆；当前 restock=true 使用当前订单行数量，需明确业务范围。')
review('shopify.refunds.list', '把按订单查询的退款展平成业务记录并附带订单。', '设计双层游标：根订单页与页内退款偏移，再提供 collect。', '当前游标以订单为单位；退款数量可能大于 limit，不能套用通用收集器。')
review('shopify.returns.list', '读取退货单及退货原因、状态与明细。', '独立处理退货根连接及明细连接，提供 coverage 和续传。', '嵌套连接仍有上限，本次未套通用 collect。')

review('wecom.attendees.add', '按日程 ID 转换参与人列表。', '增加参与人差异预检，避免重复通知和无效成员。')
review('wecom.attendees.remove', '按日程 ID 转换移除成员。', '显示当前参与人及移除后结果，保护组织者等受限角色。')
review('wecom.auth.access_token', '缓存企微应用 token，按凭据选择应用。', '业务方优先调用领域接口；token 仅保留必要的可信兼容场景。', 'access_token 属于上游凭据，不能用作普通页面数据。', 'low_level')
review('wecom.calendars.create', '已将所有者、权限和日历属性转换为官方请求。', '增加业务日历别名与 prepare，检查所有者及共享范围。')
review('wecom.calendars.delete', '按 cal_id 删除日历。', '增加关联日程预检，显式展示影响范围。', '不能默认联动删除业务记录。')
review('wecom.calendars.get_many', '支持批量读取日历详情。', '归一 owner、共享范围及逐个失败信息。', assessment='ready')
review('wecom.calendars.update', '把日历属性映射为更新请求。', '增加差异预览，区分不修改与清空字段。')
review('wecom.directory.departments', '已有按 ID/名称/完整路径查询与去重，支持单个/多个。', '将未找到与歧义路径的原因分开，供表单部门选择使用。', '当前二者都记录为 missing_paths；同名部门宜通过路径或 ID 区分。', 'ready')
review('wecom.directory.list', '合并部门树和成员，规范用户显示字段。', '大企业增加有界输出、按部门选择与可见范围说明。', '结果受通讯录权限限制，不代表企业全部成员。', 'ready')
review('wecom.directory.users', '支持成员账号或部门列表，补齐部门名和路径。', '作为审批申请人与流程节点的选择能力，保留唯一性检查。', '调用方仍需单独拥有通讯录权限。', 'ready')
review('wecom.messages.send_markdown', '按应用、接收者构建 markdown 消息。', '增加模板变量预检、分批收件人与 invaliduser 等部分失败归一。', '不能因网关 200 就认为所有收件人均成功。')
review('wecom.messages.send_text', '按应用、接收者构建文本消息。', '与 markdown 统一为通知业务层，增加模板和每类无效接收人结果。', '发送超时需核对，不自动更换业务编号重发。')
review('wecom.schedules.batch_create', '已有批量创建、逐项结果，部分成功不回滚。', '增加每项稳定外部编号和预检，支持只重试失败项。', '批次不是事务，调用方必须处理部分成功。', 'ready')
review('wecom.schedules.copy', '读取原日程，可替换日历和时间后创建。', '增加时间偏移表达和复制内容预览，明确通知参与人。', assessment='ready')
review('wecom.schedules.create', '已转换日程时间、提醒与参与者。', '增加自然的 ISO 时间/时区表达与 prepare，不推断业务时区。')
review('wecom.schedules.delete', '按 schedule_id 删除。', '增加取消原因与影响预检，返回最终状态。')
review('wecom.schedules.get_many', '批量获取并规范状态与参与者响应。', '增加每项错误与未找到单号，避免整批丢失。', assessment='ready')
review('wecom.schedules.list', '已返回整理日程与 page.next_cursor。', '用于单页交互，批量读取可用 list_all。', assessment='ready', recommended=['wecom.schedules.list_all'])
review('wecom.schedules.list_all', '已有自动翻页及 max_items 限制。', '改为达到上限时返回 partial 与续传游标，避免全部结果因超限报错丢失。', '当前超出 max_items 会报错；分页读取不是一致性快照。')
review('wecom.schedules.update', '转换日程属性更新。', '增加差异预检、版本冲突提示与通知范围。')
review('wecom.users.get', '按 userid 查询成员。', '业务侧可优先使用目录 users 的批量和部门路径封装。', assessment='ready', recommended=['wecom.directory.users'])
review('wecom.users.resolve_code', '网页授权 code 换成员身份。', '作为登录流程原子能力，绑定回调 state/nonce 并由业务认证层验证。', '一次性 code 不宜重放或用于任意人员查询。', 'low_level')

review('winit.inbound.list', '已整理入库结果、到仓状态和分页。', '按客户退货单提供到仓进度，关联物流和验收结果。')
review('winit.inbound.tracking', '已提取入库轨迹并处理供应商特殊响应形态。', '统一轨迹时间、事件码与异常提示，保留原始事件。', assessment='ready')
review('winit.inventory.list', '规范 SKU/仓库库存页。', '增加可用量业务查询，明确预留、在途及库存计量单位。')
review('winit.outbound.list', '转换出库订单条件并整理分页。', '增加按平台单号的关联解析与出库进度。')
review('winit.outbound.tracking', '正确拼接 trackingnos 并整理批量轨迹。', '增加逐单错误与异常事件归一。', assessment='ready')
review('winit.returns.create', '已有退货字段校验与地址/箱尺/商品转换，返回退货单号、RMA 和地址。', '联查渠道、商品档案与仓库形成退货计划，再自动衔接面单查询。', '当前创建结果不含面单，仍需调用 returns.label；业务地址和箱尺需确认。')
review('winit.returns.items', '读取退货商品档案和箱尺来源。', '增加 SKU 唯一解析，为创建退货预检补齐资料。', assessment='ready')
review('winit.returns.label', '将 ReturnLabel 面单链接和跟踪号规范为业务结果。', '显示链接到期时间与面单准备状态，必要时支持轮询。', '暂无链接不一定代表创建失败。', 'ready')
review('winit.returns.list', '规范退货订单列表与分页。', '增加按业务退货单的状态视图和有界 collect。')
review('winit.returns.psc', '已整理支持 ReturnLabel 的退货渠道。', '结合国家、邮编、箱尺过滤可用渠道并解释不适用原因。', assessment='ready')
review('winit.returns.void', '按退货单号取消退货。', '增加当前状态预检，明确已经使用的面单能否取消。', '取消是否成功以供应商最终状态为准。')
review('winit.warehouses.list', '已整理验货仓/目的仓目录。', '加入国家、邮编、用途和启用状态供退货预检选择。', assessment='ready')

expected = {o['key'] for o in base['operations']}
assert expected == set(reviews), f'missing={expected-set(reviews)} extra={set(reviews)-expected}'
adapters = {p: p for p in ['wecom','nas','shopify','lingxing','mabang','winit','quickcep','docusign']}
adapters.update({p:'google' for p in ['gmail','gdrive','gcs']})
adapters.update({p:'source' for p in ['judgeme','trustpilot','stripe','paypal','rd']})
names = dict(wecom='Wecom',nas='Nas',shopify='Shopify',lingxing='Lingxing',mabang='Mabang',winit='Winit',quickcep='Quickcep',docusign='Docusign',google='Google',source='CustomerSources')
items=[]
for op in base['operations']:
    adapter=adapters[op['platform']]
    items.append(dict(key=op['key'],platform=op['platform'],summary=op['summary'],source=f'src/main/java/group/cyt/common/module/adapter/{adapter}/{names[adapter]}Adapter.java',**reviews[op['key']]))
for op in business['operations']:
    approval=op['platform']=='wecom'
    caveat='真实环境需要对应应用权限；本次通过模拟上游、加密协议向量和隔离 MySQL 验证，未向供应商提交真实业务。' if approval else '自动翻页覆盖根记录；嵌套连接、供应商可见范围和来源快照仍有各自限制。'
    items.append(dict(key=op['key'],platform=op['platform'],summary=op['summary'],assessment='recommended',current=op['description'],proposal='使用接入文档和 Python/TypeScript SDK；保留稳定业务编号、续传游标与逐项错误。',caveat=caveat,recommended_operations=[],source='src/main/java/group/cyt/common/module/adapter/'+('wecom/approval/WecomApprovalService.java' if approval else 'docusign/DocusignAdapter.java' if op['key']=='docusign.templates.get' else 'support/BusinessWorkflows.java')))
references=[
 dict(title='企微审批：模板详情',url='https://developer.work.weixin.qq.com/document/path/91982'),
 dict(title='企微审批：提交申请',url='https://developer.work.weixin.qq.com/document/path/91853'),
 dict(title='企微审批：批量查询',url='https://developer.work.weixin.qq.com/document/path/91816'),
 dict(title='WxJava：按领域组织服务和 DTO',url='https://github.com/binarywang/WxJava'),
 dict(title='Stripe Java：自动分页迭代器',url='https://github.com/stripe/stripe-java/blob/master/src/main/java/com/stripe/model/StripeCollection.java'),
 dict(title='Drive：查询条件与转义',url='https://developers.google.com/workspace/drive/api/guides/search-files'),
 dict(title='Gmail：筛选与时间语义',url='https://developers.google.com/workspace/gmail/api/guides/filtering'),
]
depth_summary=attach_depth(items)
out=dict(generated_at='2026-11-05',scope=f'逐项核对全部 {len(items)} 个内置操作的实际代码；新增业务接口和推荐标签均不自动视为深度封装。',references=references,depth_summary=depth_summary,items=items)
(ROOT/'src/main/resources/capability-review.json').write_text(json.dumps(out,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
print(f'Reviewed {len(expected)} original + {len(business["operations"])} business operations')
