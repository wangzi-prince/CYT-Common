"""Explicit, code-backed depth decisions. Business tags and recommendations are not scores."""
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ADAPTER = 'src/main/java/group/cyt/common/module/adapter/'
FILES = {name: ADAPTER + folder + '/' + cls + '.java' for name, folder, cls in [
    ('doc','docusign','DocusignAdapter'),('google','google','GoogleAdapter'),
    ('nas','nas','NasAdapter'),('shop','shopify','ShopifyAdapter'),
    ('lx','lingxing','LingxingAdapter'),('mb','mabang','MabangAdapter'),
    ('win','winit','WinitAdapter'),('quick','quickcep','QuickcepAdapter'),
    ('sources','source','CustomerSourcesAdapter'),('wecom','wecom','WecomAdapter'),
    ('approval','wecom/approval','WecomApprovalService'),
    ('form','wecom/approval','ApprovalFormCodec'),
    ('events','wecom/approval','WecomApprovalEvents'),
    ('collect','support','BusinessWorkflows'),
]}
LEVELS = [
    dict(key='deep', label='深度业务封装', description='已实现业务语义转换或关联查询、预检、提交流程；仍需逐项阅读边界，不等于完整生产验收。'),
    dict(key='enhanced', label='增强封装', description='已有字段整理、校验、批处理或分页便利，但业务关联或流程仍需调用方完成。'),
    dict(key='basic', label='基础封装', description='主要是单项读写、官方参数映射、原始结果或兼容入口。'),
    dict(key='supporting', label='基础管理能力', description='凭据、登录换码、桶管理等原子能力，按职责保留，不强行为每项制造业务流程。'),
    dict(key='test_only', label='模拟演练', description='只用于模拟，不是已经完成的真实供应商业务能力。'),
]
DECISIONS = {}

def evidence(file, anchor):
    path = FILES.get(file, file)
    lines = (ROOT / path).read_text(encoding='utf8').splitlines()
    matches = [i + 1 for i, line in enumerate(lines) if anchor in line]
    assert matches, (path, anchor)
    return dict(path=path, line=matches[0], anchor=anchor)

def d(keys, level, reason, gap, *refs):
    for key in keys.split():
        assert key not in DECISIONS, key
        DECISIONS[key] = dict(depth=level, depth_reason=reason, remaining_gap=gap,
                              evidence=[evidence(*ref) for ref in refs])

d('docusign.envelopes.create','enhanced','支持模板角色或文件构建、签署顺序和文件编号生成。','未读取模板核对角色及签署必填项，业务模板名和字段还要由调用方解析。',('doc','case "docusign.envelopes.create"'))
d('docusign.envelopes.get','deep','合并信封与签署人，计算终态、下一批签署人和草稿/等待动作。','两次读取不是快照；不代替签署人操作，过期判断仍以供应商状态为准。',('doc','case "docusign.envelopes.get"'))
d('docusign.envelopes.list','enhanced','规范日期与状态，支持原生分页位置并返回可续传的下一位置。','单页原子接口；有界汇总使用 envelopes.collect，不保证上游快照。',('doc','case "docusign.envelopes.list"'),('doc','private static Map<String, Object> page('))
d('docusign.envelopes.void','deep','先读取状态，重复作废返回已作废；阻止已完成/拒绝/删除信封再作废。','状态检查和更新不是原子事务，供应商仍做最终校验；保留原接口兼容。',('doc','case "docusign.envelopes.void"'))
d('docusign.templates.list','enhanced','规范模板目录并暴露原生下一页位置，可组合有界采集。','模板角色和字段请通过 templates.get 读取。',('doc','case "docusign.templates.list"'))
d('docusign.testing.advance','test_only','模拟器推进信封状态。','真实适配器没有代替签署人推进状态的实现。',('doc','default -> throw GatewayException.config'))

d('gcs.objects.delete','basic','按对象路径执行单项删除并返回 deleted。','没有 generation 版本条件、业务保留策略或删除预检。',('google','if (op.key().equals("gcs.objects.delete"))'))
d('gcs.objects.get_metadata','basic','按路径直接返回 Storage 元信息。','元信息仍为供应商结构，没有业务文件实体或统一属性模型。',('google','private Object gcs('))
d('gcs.objects.list','basic','映射官方筛选字段及单页 token。','调用方仍管理原生分页；可选用 collect，但后者也不补齐业务关联。',('google','if (op.key().equals("gcs.objects.list"))'))
d('gcs.objects.upload','enhanced','支持文本/Base64、大小校验和 MIME 上传。','缺少防覆盖版本条件、校验和确认及大文件直传流程。',('google','if (op.key().equals("gcs.objects.upload"))'),('google','public static byte[] content('))
d('gdrive.files.get gdrive.files.list','basic','转换文件 ID、fields、q 或分页参数，结果接近官方结构。','仍需理解 Drive 字段、目录 ID 和查询语法；目录定位和业务文件解析未封装。',('google','private Object drive('))
d('gdrive.files.upload','enhanced','构建文件元信息与 multipart，校验内容和 MIME。','父目录解析、同名策略和完成后校验仍由业务方处理。',('google','ByteArrayOutputStream out = new ByteArrayOutputStream();'))
d('gdrive.folders.create','basic','将名称、父目录和 folder MIME 转成一次创建。','没有 ensure_folder 查重或同名歧义处理。',('google','if (op.key().equals("gdrive.folders.create"))'))
d('gmail.labels.list gmail.messages.get gmail.messages.list','basic','读取标签、单封原始邮件或 ID 列表，保留官方格式与 token。','业务方仍要解析标签/邮件结构或逐封补齐；search 提供了更高一层能力。',('google','private Object gmail('))
d('gmail.messages.restore_from_spam','deep','先验证邮箱和 SPAM/TRASH 状态，再移除垃圾标签并移回收件箱。','仅适用于明确配置的测试邮箱；不等于通用邮件标签工作流。',('google','case "gmail.messages.restore_from_spam"'))
d('gmail.messages.send','enhanced','从业务文本构建 UTF-8 MIME，核对邮箱和全部收件人。','没有消息模板预检、强制业务防重和发送结果追踪；当前受测试白名单约束。',('google','case "gmail.messages.send"'))
d('gmail.profile.get','enhanced','核对实际授权邮箱与 account 参数，返回授权身份。','这是身份检查，不能算完整邮件业务流程。',('google','var profile = http.object('))

d('judgeme.reviews.list','enhanced','评价归一为评分、内容、客户、SKU 和日期。','仍是单来源单页；无订单关联补齐、评价回复或问题归因。',('sources','private Map<String, Object> judgemeReviews('))
d('judgeme.reviews.reply','enhanced','按账号解析店铺凭据，确认后公开发布回复，规范成功结果并禁止自动重试。','列表接口不返回已有回复；历史人工回复识别、审批和不确定结果核对由调用方完成。',('sources','private Map<String, Object> judgemeReply('))
d('trustpilot.reviews.list','enhanced','区分公开/私有读取并整理评价、客户与订单字段。','无跨源订单关联和回复流程，字段可见性取决于凭据。',('sources','private Map<String, Object> trustpilotReviews('))
d('stripe.disputes.list','enhanced','统一争议金额单位、币种、客户、订单和截止日期。','订单号来自已有 metadata，没有自动关联交易/订单和生成取证缺项。',('sources','private Map<String, Object> stripeDisputes('))
d('paypal.disputes.list','deep','自动从列表逐项补齐争议详情，整理买家、订单、金额和响应期限。','任一详情失败仍会使当页失败；多笔争议交易只选首笔，不是完整取证工作流。',('sources','private Map<String, Object> paypalDisputes('))
d('rd.products.list','enhanced','适配来源目录并整理为统一产品列表。','尚无 SKU 冲突处理、产品关联和增量变更模型。',('sources','private Map<String, Object> rdProducts('))

d('lingxing.aftersales.reasons','enhanced','整理售后原因 ID、原因文字和创建信息。','代码没有翻译或启用项过滤，不能把先前说明当作已有功能。',('lx','private Object aftersalesReasons('))
d('lingxing.inventory.fba lingxing.inventory.list lingxing.listing.list lingxing.warehouse.list','basic','映射官方库存/Listing/仓库条件，主要通过 rawAndTotal 返回数据。','没有统一可用库存、跨仓关联、仓库解析或有界分页流程。',('lx','private static Map<String, Object> rawAndTotal('),('lx','public Object invoke('))
d('lingxing.messages.detail','enhanced','整理邮件正文、收发地址和附件字段。','需要调用方先取得 webmail_uuid，未与列表组合成一次业务检索。',('lx','private Object data('))
d('lingxing.messages.list','enhanced','整理邮件页，固定查询日期随游标续传。','不自动读取正文，未提供列表加详情的有界业务查询。',('lx','private Object messagesList('))
d('lingxing.mporders.list','basic','支持按多平台单号或时间查询，主体仍为 raw/total/request_id。','工单需要的 globalOrderNo/orderItemNo/PID 仍由调用方抽取并串联后续接口。',('lx','private Object mpordersList('))
d('lingxing.orders.detail','basic','将供应商订单详情包装为 items/total。','明细主体未归一，未联查物流、售后和库存。',('lx','private Object ordersDetail('))
d('lingxing.orders.list','enhanced','归一订单、金额、店铺和商品行。','仍需调用方管理 offset/length，未自动汇总或做增量水位同步。',('lx','private Object ordersList('),('lx','private static Map<String, Object> orderRow('))
d('lingxing.product.list','enhanced','整理本地 SKU、PID 等商品目录字段。','没有唯一 SKU 解析以及用于工单的自动补齐。',('lx','private Object productList('))
d('lingxing.refund.create','basic','校验测试账号后传递 sid、订单号、购买时间及官方 data。','未联查订单、成功交易、已退款量或生成退款预检计划。',('lx','private Object refundCreate('))
d('lingxing.return.qty','enhanced','验证订单/行标识并整理退货数量响应。','仍依赖官方订单行号，未与工单和本地商品目录编排。',('lx','private Object returnQty('))
d('lingxing.seller.list','enhanced','归一店铺标识、名称等目录字段。','没有店铺业务别名及同名候选解析。',('lx','private Object sellerList('))
d('lingxing.workorder.audit','basic','提交官方工单明细或审批流 ID，返回 raw 和 request_id。','审核流程仍需业务明确选择，未自动审批。',('lx','case "workorder.add"'),('lx','case "workorder.audit"'))
d('lingxing.workorder.list','basic','传递日期/偏移并返回工单 raw/total。','没有按业务订单整合审核、到仓、退款进度。',('lx','private Object workOrderList('))

d('mabang.orders.list','enhanced','统一订单页和 next_cursor/has_next，识别响应形态。','订单数据仍来自供应商结构，调用方原接口需自己翻页。',('mb','case "mabang.orders.list"'))
d('mabang.orders.track','enhanced','兼容空响应并归一批量物流状态和轨迹。','未拆分超过单批上限的订单，也未提供逐单错误与订单查询联动。',('mb','case "mabang.orders.track"'))

d('nas.buckets.create nas.buckets.delete nas.buckets.list','supporting','提供 S3 桶生命周期与目录管理。','按基础设施职责保留；业务应用宜使用分配后的文件能力。',('nas','case "buckets.list"'))
d('nas.objects.copy','basic','复制单个源键到目标键。','没有覆盖策略、源版本校验或完整迁移任务。',('nas','private Object copy('))
d('nas.objects.copy_prefix','deep','自动枚举目录、逐项复制，全部复制成功才允许删除源，并返回失败项。','跨对象非事务；大任务无持久进度和续跑，复制时并发写入不提供一致性快照。',('nas','private Object copyPrefix('))
d('nas.objects.delete','basic','按键执行删除。','没有业务保留策略或软删除。',('nas','case "objects.delete"'))
d('nas.objects.delete_batch','enhanced','拆分批量删除并保留每项成功/错误。','批次处理本身不是业务范围预检或可审核的删除计划。',('nas','private Object delete('))
d('nas.objects.list','enhanced','整理文件、目录前缀和续传标记。','仍是单页前缀检索，没有业务文件实体和目录定位。',('nas','private Object list('))
d('nas.objects.presign_get','enhanced','批量生成带期限的读取链接。','不补齐文件业务权限、有效性和下载完成结果。',('nas','case "objects.presign_get"'))
d('nas.objects.presign_put','enhanced','自动生成日期/UUID 键名和扩展名，提供直传信息。','签发链接不代表上传成功，尚无完成回执与真实类型校验。',('nas','case "objects.presign_put"'))
d('nas.objects.read_head','enhanced','读取有界文件头，按签名识别常见图片/PDF/ZIP、解析图片尺寸并提示 MIME 不一致。','仅检查文件头，不验证完整性、恶意内容或压缩包中的实际类型；较长 JPEG 头可能需要增加 head_bytes。',('nas','case "objects.read_head"'),('src/main/java/group/cyt/common/module/adapter/nas/FileHeaderInspector.java','public static Map<String, Object> inspect('))
d('nas.objects.stat','enhanced','把 HEAD 结果整理为大小、MIME、etag 和修改时间。','仍是元信息单项读取，不验证业务上传是否完成或内容是否合规。',('nas','case "objects.stat"'))

d('quickcep.analytics.get','basic','转换日期/渠道后返回供应商统计主体。','指标口径、时间范围完整性和业务解读未规范。',('quick','case "analytics.get"'))
d('quickcep.messages.list','enhanced','统一消息页码、总量和页数。','未自动汇总会话全文或归一参与者角色。',('quick','case "messages.list"'))
d('quickcep.messages.send','enhanced','区分坐席/访客，构建消息并检查 owner_id。','调用方仍要提供会话、渠道和坐席；未查询可回复状态和自动补齐。',('quick','case "messages.send"'))
d('quickcep.sessions.by_operator','enhanced','转换坐席筛选及分页信息。','没有坐席名称解析、自动翻页和会话详情补齐。',('quick','case "sessions.by_operator"'))
d('quickcep.sessions.close','basic','调用供应商关闭会话。','未读取原状态或归一重复关闭的最终状态。',('quick','case "sessions.close"'))
d('quickcep.sessions.create','enhanced','转换访客与第三方会话号，使用供应商 getOrCreate。','未形成网关自身的业务会话解析和可继续操作视图。',('quick','case "sessions.create"'))
d('quickcep.sessions.get quickcep.sessions.list quickcep.staff.list','basic','读取原会话主体、会话 ID 页或坐席主体。','会话与消息、坐席未关联，供应商角色和状态仍需业务侧理解。',('quick','public Object invoke('))

d('shopify.inventory.set','basic','把库存条目、库位和覆盖数量转为 mutation。','仍需官方 ID；没有 SKU/库位解析、差异预览和比较版本前置条件。',('shop','case "inventory.set"'))
d('shopify.orders.create','deep','按业务 reference 查重，逐 SKU 唯一解析变体，校验地址并创建零金额补发单。','限测试店铺；按标签查重不是并发唯一约束，尚无完整预检计划和原订单校验。',('shop','private Object create('))
d('shopify.orders.get shopify.products.get','basic','返回预定义 GraphQL 的订单或商品节点。','业务关联和嵌套连接完整性仍需调用方处理。',('shop','case "orders.get"'),('shop','case "products.get"'))
d('shopify.orders.list shopify.products.list','enhanced','将根连接转成 items 和 page_info。','仅根分页；订单行和变体等嵌套连接仍受查询 first 限制。',('shop','private Object list('))
d('shopify.orders.update','basic','映射备注、标签、地址等更新字段。','没有现状读取、标签增删语义和差异预览。',('shop','case "orders.update"'))
d('shopify.refunds.create','deep','读取订单余额和成功 SALE 流水，检查可退金额并构建退款/退库请求。','默认随机生成供应商幂等键；多支付分摊和逐项退库未封装，restock=true 使用当前订单行数量。',('shop','private Object refund('))
d('shopify.refunds.list shopify.returns.list','enhanced','整理退货/退款查询；退款展平时附带订单信息。','退款游标属于根订单且子记录有独立数量，未提供双层续传与所有内连接补齐。',('shop','private Object list('))

d('wecom.attendees.add wecom.attendees.remove','basic','按日程 ID 转交参与者增删。','未读取原参与者、计算差异或预检可操作角色。',('wecom','if (key.equals("attendees.add")'))
d('wecom.auth.access_token wecom.users.resolve_code','supporting','提供应用令牌或网页授权换码的基础兼容入口。','不作为业务深封完成项；业务侧优先使用领域接口和自己的登录流程。',('wecom','if (key.equals("auth.access_token"))'),('wecom','if (key.equals("users.resolve_code"))'))
d('wecom.calendars.create wecom.calendars.update','basic','清理日历参数并转换官方 calendar 请求。','无业务日历别名、所有者解析、共享权限预检和差异更新。',('wecom','if (key.startsWith("calendars."))'))
d('wecom.calendars.delete','basic','按 cal_id 删除。','没有关联日程影响预检。',('wecom','if (action.equals("delete"))'))
d('wecom.calendars.get_many','basic','批量读取并增加 id 别名，主体保留官方字段。','无逐个错误隔离和所有者/共享范围的业务解释。',('wecom','if (action.equals("get_many"))'))
d('wecom.directory.departments wecom.directory.list wecom.directory.users','deep','组合部门树、路径/名称解析、去重与成员补齐，并按凭据能力选择读取方式。','未找到和歧义路径都进 missing_paths；大企业边界和部分失败还可细化。',('wecom','private Object directory('),('wecom','private String departmentPath('))
d('wecom.messages.send_markdown wecom.messages.send_text','enhanced','构建应用消息并拼接接收成员/部门/标签。','返回仍有官方 invaliduser 等字段；未做业务模板、收件人解析和投递结果统一。',('wecom','if (key.startsWith("messages."))'))
d('wecom.schedules.batch_create','deep','逐项规范化和创建，按索引返回成功、失败及批次摘要。','部分成功不回滚，每项业务编号和只重试失败项的持久编排仍需补充。',('wecom','if (key.equals("schedules.batch_create"))'))
d('wecom.schedules.copy','deep','读取源日程，覆盖目标日历/时间并自动创建副本。','源返回字段复制到创建请求，仍宜采用可写字段白名单和复制预览。',('wecom','if (key.equals("schedules.copy"))'))
d('wecom.schedules.create','enhanced','时间规范化和基本日程结构清理。','还需显式给成员与日历 ID，未做参与人和业务日历解析。',('wecom','private Map<String, Object> scheduleCreate('),('src/main/java/group/cyt/common/module/registry/service/ParameterRules.java','key.equals("wecom.schedules.create")'))
d('wecom.schedules.delete wecom.schedules.update','basic','按官方 ID 删除或更新日程字段。','无状态预检、差异说明和版本冲突处理。',('wecom','if (key.equals("schedules.update"))'),('wecom','if (key.equals("schedules.delete"))'))
d('wecom.schedules.get_many','enhanced','规范日程状态、时间与参与者响应。','单项读取错误隔离和业务关联未完成。',('wecom','if (key.equals("schedules.get_many"))'),('wecom','private Map<String, Object> scheduleView('))
d('wecom.schedules.list wecom.schedules.list_all','enhanced','整理日程页，list_all 可自动翻页。','list_all 超过 max_items 抛错，没有保留已有结果并返回续传位置。',('wecom','private Object schedules('))
d('wecom.users.get','basic','按 userid 返回官方成员资料。','没有成员别名/部门路径解析；更高层能力在 directory.users。',('wecom','if (key.equals("users.get"))'))

d('winit.inbound.list winit.outbound.list winit.inventory.list winit.returns.list winit.returns.items winit.warehouses.list','basic','映射官方条件并包装 pageParams/list 或 warehouseList。','仍需要官方仓库、单号和日期字段，列表内容未形成统一业务实体或关联流程。',('win','private Map<String, Object> buildData('),('win','public Object invoke('))
d('winit.inbound.tracking','basic','按入库单返回 trackingList。','未将入库、物流异常和到仓状态合成业务进度。',('win','case "inbound.tracking" ->'))
d('winit.outbound.tracking','enhanced','统一轨迹字段并排序，兼容批量结果。','缺少逐单异常解释和出库订单关联。',('win','private static Map<String, Object> outboundTrackingResult('))
d('winit.returns.create','enhanced','验证退货字段并构造商品/包裹，返回退货单号、RMA 和退货地址。','仓库、SKU、渠道和箱尺仍需人工拼装，面单还需调用 returns.label；没有一键退货计划。',('win','private static Map<String, Object> returnCreateData('),('win','case "returns.create" -> {'))
d('winit.returns.label','enhanced','整理面单 Base64、跟踪号和 has_label。','无面单准备状态轮询或与创建流程自动衔接。',('win','private static Map<String, Object> labelResult('))
d('winit.returns.psc','enhanced','明确标记是否支持 ReturnLabel。','没有根据国家、邮编、箱尺自动选出候选渠道并解释约束。',('win','private static Map<String, Object> pscResult('))
d('winit.returns.void','enhanced','把取消结果归一为每项 ok/reason。','未读取订单状态或核对已使用面单。',('win','private static Map<String, Object> voidResult('))

d('wecom.approval_templates.list','basic','读取当前系统已绑定的模板目录。','这是本地目录查询，不是企业全部模板自动发现。',('approval','if (key.equals("wecom.approval_templates.list"))'))
d('wecom.approval_templates.get','deep','解析业务别名和真实模板，生成字段、必填项、选项、明细与业务示例。','少数高级控件仍需官方值结构；未完成真实企业所有控件验收。',('approval','key.equals("wecom.approval_templates.get")'),('form','public static Map<String, Object> describe('))
d('wecom.approval_templates.bind','enhanced','核对真实控件并持久化当前系统的模板/字段别名。','字段别名需要明确配置，不会猜测业务含义。',('approval','key.equals("wecom.approval_templates.bind")'),('approval','private void validateAliases('))
d('wecom.approval_templates.unbind','basic','移除当前系统的本地模板别名。','按原子配置职责保留，不修改上游模板。',('approval','if (key.equals("wecom.approval_templates.unbind"))'))
d('wecom.approval_templates.create wecom.approval_templates.update','deep','按业务字段构建控件、数字 ID 和别名；更新优先保留原控件 ID。','复杂 config 和审批规则仍需明确配置，未自动设计审批人或管理员规则。',('approval','private Object saveTemplate('),('approval','private List<Object> buildControls('))
d('wecom.approval_templates.clone','deep','读取模板并创建新模板，可复用业务字段别名。','只复制控件，不复制原审批流程及管理员规则。',('approval','if (operation.endsWith("clone"))'))
d('wecom.approvals.prepare wecom.approvals.submit','deep','自动读取模板、聚合校验、类型/选项/金额转换、摘要和流程生成；提交沿用持久防重。','高级控件需要显式官方值；当前未在真实企业完成全部权限/控件联调。',('approval','private Map<String, Object> prepare('),('form','public static Map<String, Object> encode('),('src/main/java/group/cyt/common/module/gateway/service/GatewayExecutor.java','op.tags().contains("idempotency_required")'))
d('wecom.approvals.get wecom.approvals.track','deep','统一单据/节点状态、待处理人、备注和明细业务别名；track 按本系统业务请求号关联审批并验证上游应用。','高级控件仍保留部分官方值；流程读取不是一致性快照；未记录的超时提单不能推断成未提交。',('approval','private Map<String, Object> detail('),('approval','if (key.equals("wecom.approvals.track"))'),('approval','private static Map<String, Object> businessValues('))
d('wecom.approvals.get_many','enhanced','自动去重单号、逐项读取并保留错误。','是批处理便利，尚无业务订单映射和流程级补齐。',('approval','private Map<String, Object> details('))
d('wecom.approvals.list wecom.approvals.collect','deep','业务条件/模板别名转换、跨 31 天窗口、有界自动分页和详情补齐。','不同批次需按单号去重，不提供一致性快照；失败详情需调用方处理。',('approval','private Object query('))
d('wecom.approvals.engine_get','basic','按 third_no 获取并转交审批引擎 data。','引擎详情及状态未归一成普通审批模型。',('approval','if (key.equals("wecom.approvals.engine_get"))'))
d('wecom.approvals.engine_launch','enhanced','从字段对象构建客户端审批引擎调用参数。','只生成 JS-SDK 参数，客户端初始化和实际操作仍需业务系统完成。',('approval','if (key.equals("wecom.approvals.engine_launch"))'))
d('wecom.approvals.legacy_get','basic','校验 30 天日期窗口后读取旧接口。','保留兼容，旧结果不做通用审批归一。',('approval','if (key.equals("wecom.approvals.legacy_get"))'))
d('wecom.approval_files.upload wecom.approval_files.download','enhanced','Base64/二进制转换、文件名与大小校验或 MIME 返回。','是附件读写便利，未形成业务文件管理和申请关联流程。',('approval','if (key.equals("wecom.approval_files.upload"))'),('approval','if (key.equals("wecom.approval_files.download"))'))
d('wecom.approval_events.list','deep','消费验签解密、按提交所有者分流、加密入库的持久事件，支持增量拉取和转发重试。','外部发起的未知审批回到接收系统；凭据轮换后的旧单路由需核对。',('approval','if (key.equals("wecom.approval_events.list"))'),('events','public void receive('),('events','public void deliverPending('))
d('wecom.approval_events.retry','enhanced','限定当前系统与失败状态，重新排队转发。','是事件恢复工具，不改变审批状态或自动修复业务失败。',('approval','if (key.equals("wecom.approval_events.retry"))'))
d('wecom.vacation.config','enhanced','将企业假期配置整理为单位、日历和发放规则，保留官方值。','未知规则保留 unknown；不替代实际排班、适用范围或可申请时长计算。',('approval','private static List<Map<String, Object>> vacationRules('))
d('wecom.vacation.get','deep','联查企业规则和成员余额，返回明确单位的 remaining 及原始秒数；规则查询失败保留余额和逐项错误。','不是排班引擎，不计算某次请假的实际可用工作时长。',('approval','private Object vacation('))
d('wecom.vacation.set','deep','先核对企业假期 ID 与单位，再转换秒数并校验精度、范围、确认与稳定防重编号。','余额为绝对设置值；不代算请假扣减或跨系统原子事务。',('approval','if (!JsonUtils.bool(p.get("confirm")))'))

d('gmail.messages.search','deep','组合业务筛选、自动分页、逐封详情补齐和 MIME 正文/附件元信息解析。','不自动下载附件或关联订单，单封错误仍须业务方处理。',('collect','public static String gmailQuery('),('collect','var enriched = new ArrayList<Object>();'),('collect','private static Map<String, Object> mail('))
d('gdrive.files.search','enhanced','自动转义文件名/目录/类型筛选并有界翻页。','目录仍需官方 ID；不解析业务路径、同名歧义或文件所属业务。',('collect','public static String driveQuery('),('collect','while (more && pages < maxPages'))
for key, gap in [
    ('gcs.objects.collect','没有业务文件关联或对象版本策略。'),
    ('nas.objects.collect','保留目录前缀，但不形成迁移/验收作业。'),
    ('shopify.orders.collect','只补根订单页，嵌套商品行和交易有 first 上限。'),
    ('shopify.products.collect','只补商品根页，大量变体需独立翻子连接。'),
    ('mabang.orders.collect','保留原生订单结构，未联查物流或售后。'),
    ('judgeme.reviews.collect','没有评价到订单/SKU 的进一步关联和回复流程。'),
    ('trustpilot.reviews.collect','没有跨来源评价语义和订单补齐。'),
    ('stripe.disputes.collect','未联查支付订单或生成取证计划。'),
    ('rd.products.collect','统一读取协议不等于 SKU 解析与目录治理。'),
]:
    d(key,'enhanced','在固定只读能力上增加上限、分页、去重、统一返回和签名续传。',gap,('collect','public Object invoke('),('collect','"root_records"'))
d('paypal.disputes.collect','deep','在逐笔详情补齐和争议业务字段整理之上增加有界分页。','继承来源单条失败导致当页失败的限制，也未实现取证准备或争议处置。',('collect','public Object invoke('),('sources','private Map<String, Object> paypalDisputes('))


d('docusign.templates.get','deep','联查模板定义与收件人字段，返回角色和 tabs。','复杂角色、条件字段和签署策略需明确配置，不推断签署人。',('doc','case "docusign.templates.get"'))
d('docusign.envelopes.collect docusign.templates.collect quickcep.messages.collect lingxing.orders.collect lingxing.product.collect','enhanced','固定原生页大小、有界自动汇总、页内续传，并绑定系统与筛选条件。','自动分页不是完整业务事务；根记录范围和上游一致性仍有边界。',('collect','public static final Map<String, Spec> SPECS'),('collect','case "total_offset"'))
d('lingxing.workorder.add','enhanced','补齐官方顶层工单字段的传递，避免遗漏系统单号、原因、退货仓等必需信息。','仍需显式提供订单行、PID、原因与退货数量；未自动编排售后审批或退款。',('lx','private static Map<String, Object> workOrderPayload('))

FINDINGS = [
    dict(priority='P1', title='统一网关与推荐标签不能证明深度封装', operations=[], detail='认证、签名、重试、字段改名、推荐调用和自动翻页不单独计为深度业务封装。'),
    dict(priority='P1', title='领星售后链路仍需业务方编排', operations=['lingxing.mporders.list','lingxing.workorder.add','lingxing.workorder.audit','lingxing.refund.create'], detail='订单行号、PID、售后原因、已退数量与审批流 ID 没有形成自动关联预检。'),
    dict(priority='P1', title='万邑通退货创建与取面单尚未串联', operations=['winit.returns.create','winit.returns.psc','winit.returns.items','winit.returns.label'], detail='创建接口返回单号/RMA/地址，不直接返回面单；缺少业务订单到退货计划的关联补齐。'),
    dict(priority='P1', title='自动翻根页不代表嵌套数据完整', operations=['shopify.orders.collect','shopify.products.collect','shopify.refunds.list','shopify.returns.list'], detail='嵌套行、变体和交易有独立查询上限，退款还需根页和页内偏移共同续传。'),
    dict(priority='P1边界', title='文件头识别不代表完整文件校验', operations=['nas.objects.read_head'], detail='已识别常见文件签名及图片尺寸，并提示声明 MIME 不一致；仍不执行完整解码、病毒扫描或压缩包内容识别。'),
    dict(priority='P1', title='QuickCEP 缺少一体化会话与回复能力', operations=['quickcep.sessions.get','quickcep.messages.list','quickcep.messages.send','quickcep.staff.list'], detail='会话详情、完整正文、坐席/渠道解析和可回复状态仍需分别处理。'),
    dict(priority='P1', title='DocuSign 已补目录分页，业务签署策略仍需显式确定', operations=['docusign.envelopes.create','docusign.envelopes.list','docusign.templates.list'], detail='已提供模板角色/字段读取、单页与汇总续传；从业务角色到签署策略仍需接入方配置。'),
    dict(priority='P0接入边界', title='审批主流程较深，单项工具与高级控件仍有边界', operations=['wecom.approvals.submit','wecom.approvals.get','wecom.approvals.engine_get','wecom.vacation.set'], detail='审批已补业务请求追踪、步骤/字段还原和假期规则预检；高级控件、引擎统一模型和真实账号验收仍有边界。'),
]

def attach_depth(items):
    expected = {item['key'] for item in items}
    assert expected == set(DECISIONS), f'missing={expected-set(DECISIONS)} extra={set(DECISIONS)-expected}'
    for item in items:
        item.update(DECISIONS[item['key']])
    counts = Counter(item['depth'] for item in items)
    auxiliary = [
        dict(path='POST /integrations/batch', depth='enhanced', reason='统一执行、逐项结果和 stop_on_error；调用依赖、业务回滚和补偿仍由业务层处理。', evidence=[evidence('src/main/java/group/cyt/common/module/gateway/service/GatewayExecutor.java','public Object batch(')]),
        dict(path='GET /integrations/wecom/webhooks/{system}/events', depth='enhanced', reason='验签、解密并验证企业接收者，处理接入 challenge，不代表审批业务已经发生。', evidence=[evidence('src/main/java/group/cyt/common/module/adapter/wecom/approval/WecomApprovalController.java','public String verify(')]),
        dict(path='POST /integrations/wecom/webhooks/{system}/events', depth='deep', reason='验签解密、归一状态、所有者路由、加密去重入库和持久转发重试。未知来源与凭据轮换有明确边界。', evidence=[evidence('events','public void receive('),evidence('events','public void deliverPending(')]),
        dict(path='POST /integrations/docusign/webhooks/{system}/events（兼容 /integrations/docusign/webhooks/events）', depth='enhanced', reason='原文验签、事件归一并转发；没有持久去重收件箱和重试作业，失败依赖上游重投。', evidence=[evidence('src/main/java/group/cyt/common/module/callback/controller/CallbackController.java','public Object webhook(')]),
    ]
    return dict(total=len(items), counts={x['key']: counts[x['key']] for x in LEVELS},
                all_deep=all(item['depth'] == 'deep' for item in items),
                conclusion='当前没有全部完成深度二次封装。推荐调用、business 标签和测试通过均不能替代逐项业务深度判断。',
                basis='静态代码逐项审阅；只评价实际已实现的语义转换、关联补齐与流程编排，不评价供应商真实账号验收。',
                scope=f'当前检查 {len(items)} 个注册内置业务操作；本地运行目录未检测到自定义 HTTP 定义。管理端 CRUD 不作为业务深封完成项。',
                levels=LEVELS, findings=FINDINGS, auxiliary_interfaces=auxiliary)
