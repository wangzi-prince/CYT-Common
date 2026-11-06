"""Build the independently maintained business contract and synthetic parameter fixtures."""
from pathlib import Path
import json,copy
ROOT=Path(__file__).resolve().parents[1]
ops=[];cases=[]
def string(title='',maximum=160,minimum=1,**extra):return dict(type='string',minLength=minimum,maxLength=maximum,description=title,**extra)
def obj(**extra):return dict(type='object',additionalProperties=True,**extra)
def integer(default,minimum=1,maximum=100):return dict(type='integer',default=default,minimum=minimum,maximum=maximum)
def array(items=None,minimum=0,maximum=100):return dict(type='array',items=items or {},minItems=minimum,maxItems=maximum)
def boolean(default):return dict(type='boolean',default=default)
T={'template':string('业务模板别名或企微模板 ID')}
REQ={'request_id':string('稳定业务请求编号；重试时保持相同，不能使用新的随机数',128)}
FLOW=array(dict(type='object',additionalProperties=False,properties={'type':dict(type='string',enum=['approve','notify','handle'],default='approve'),'mode':dict(type='string',enum=['all','any','sequential'],default='all'),'users':array(string('成员 userid',64),1)},required=['users']),0,20)
FIELDS={'fields':obj(description='字段对象，支持业务别名、唯一标题或控件 ID；按模板类型自动转换'),'apply_data':obj(description='高级模式：官方 contents，不可与 fields 同时传入'),'applicant':string('申请人 userid',64),'department_id':dict(type='integer',minimum=1),'flow':FLOW,'summary':array(string('摘要行，最多 20 个字符',20),0,3),'template_version':string('预检返回的模板版本；提交时检测模板变更',64)}
def add(resource,action,title,description,properties=None,required=None,example=None,write=False,sensitive=False,idempotency=False,deprecated=False,source=''):
 key=f'wecom.{resource}.{action}';properties=properties or {};example=example or {}
 schema=dict(type='object',additionalProperties=False,properties=properties,required=required or [],examples=[example])
 tags=['write' if write else 'read','business','approval']
 if idempotency:tags.append('idempotency_required')
 op=dict(key=key,platform='wecom',summary=title,description=description,quota='120/min',sensitive=sensitive,idempotent=write,timeout_s=90 if action=='collect' else 60,retry_max=1 if write else 2,since='2026-11',deprecated=deprecated,tags=tags,category='通用审批' if resource=='approvals' else {'approval_templates':'审批模板','approval_files':'审批附件','approval_events':'审批事件','vacation':'假期管理'}[resource],sort_order=-90+len(ops),path='/integrations/'+key.replace('.','/').replace('_','-'),schema=schema,official_docs=source)
 ops.append(op)
 def defaults(value,s):
  if isinstance(value,dict):
   for k,p in s.get('properties',{}).items():
    if k not in value and 'default' in p:value[k]=copy.deepcopy(p['default'])
    if k in value:defaults(value[k],p)
  if isinstance(value,list):
   for v in value:defaults(v,s.get('items',{}))
 normalized=copy.deepcopy(example);defaults(normalized,schema);cases.append(dict(operation=key,params=example,normalized=normalized))
base='https://developer.work.weixin.qq.com/document/path/'
add('approval_templates','list','列出已注册的业务审批模板','仅列出当前环境、调用系统和模拟/真实模式下的模板别名。企微没有列出企业全部模板的服务端接口。',example={})
add('approvals','track','按业务请求号追踪审批结果','用提交时的 request_id 找到本系统审批单，再读取最新状态、当前审批人、步骤与业务字段；隔离环境、模拟模式和上游应用。',REQ,['request_id'],{'request_id':'expense-20261105-001'},source=base+'91983')
add('approval_templates','get','获取可直接填写的审批表单','自动整理字段、必填项、可选项、明细和示例。保留官方结构，支持全部官方控件的高级值模式。',T,['template'],{'template':'demo-expense'},source=base+'91982')
add('approval_templates','bind','绑定业务模板别名与字段别名','别名按调用系统隔离；绑定前核对真实模板和控件 ID，不会修改上游模板。',{'template_id':string(),'alias':string(maximum=64),'field_aliases':obj(),'description':string(maximum=255,minimum=0)},['template_id','alias'],{'template_id':'demo-expense','alias':'expense','field_aliases':{'reason':'Text-01','amount':'Money-02'}},write=True)
add('approval_templates','unbind','移除本系统的模板别名','只移除本地别名，不删除企微模板。',{'alias':string(maximum=64)},['alias'],{'alias':'expense'},write=True)
create={'name':string('模板名称',40),'fields':array(obj(description='key、label、control、required；选择控件可传 options，Table 可传 children'),1),'template_content':obj(description='高级模式：官方模板内容，不能与 fields 同时传入'),'alias':string(maximum=64),'description':string(maximum=255,minimum=0),**REQ}
fields=[{'key':'reason','label':'事由','control':'Text','required':True},{'key':'amount','label':'金额','control':'Money','required':True}]
add('approval_templates','create','按业务字段创建审批模板','提供字段名与控件类型即可创建。新模板的审批规则需要在企微管理后台确认；支持直接传官方 template_content。',create,['name'],{'name':'费用申请（测试）','fields':fields,'request_id':'template-create-example'},write=True,sensitive=True,idempotency=True,source=base+'97437')
add('approval_templates','update','更新审批模板内容','保留企微既有流程和规则，只允许更新应用有权限的模板。提供完整字段定义或官方 template_content。',{**T,**create},['template','name'],{'template':'demo-expense','name':'费用申请（测试）','fields':fields,'request_id':'template-update-example'},write=True,sensitive=True,idempotency=True,source=base+'97438')
add('approval_templates','clone','复制模板为新的业务模板','读取原模板控件再创建新模板；不会复制原审批流程或管理员规则。',{**T,'name':string(maximum=40),'alias':string(maximum=64),'description':string(maximum=255,minimum=0),**REQ},['template','name'],{'template':'demo-expense','name':'新的费用申请（测试）','request_id':'template-clone-example'},write=True,sensitive=True,idempotency=True)
example={'template':'demo-expense','applicant':'fixture-user','fields':{'事由':'购买办公用品','金额':'99.50','费用类型':'办公'}}
add('approvals','prepare','预检审批申请并生成官方请求','按模板校验全部字段，自动转换控件、选项与金额，生成摘要。只读取模板，不会提单。',{**T,**FIELDS},['template','applicant'],example,source=base+'91853')
add('approvals','submit','用业务字段一键提交审批','自动读取最新模板、转换字段、校验流程和生成摘要。默认采用模板审批流程。request_id 防重复提交；超时结果不确定时不自动重发。',{**T,**FIELDS,**REQ},['template','applicant','request_id'],{**example,'request_id':'expense-order-20261105-001'},write=True,sensitive=True,idempotency=True,source=base+'91853')
add('approvals','get','获取整理后的审批详情','统一状态、申请人、字段、附件、明细和流程；保留 official，未知状态保持 unknown。',{'approval_no':string(maximum=64)},['approval_no'],{'approval_no':'MOCK-1'},source=base+'91983')
add('approvals','get_many','批量获取审批详情（支持部分失败）','去重后逐个读取详情，每个失败单独返回；不会因一个无效单号丢失其他结果。',{'approval_nos':array(string(maximum=64),1,100)},['approval_nos'],{'approval_nos':['MOCK-1']},source=base+'91983')
query={'start_at':dict(description='ISO 日期时间、北京时间或 Unix 秒/毫秒；省略为最近 7 天'),'end_at':dict(description='结束时间；总范围最多 366 天'),'template':string(),'applicant':string(maximum=64),'status':dict(description='pending/approved/rejected/cancelled/approval_revoked/deleted/paid，或官方数字状态'),'department_ids':array(dict(type='integer',minimum=1)),'record_type':dict(type='integer',minimum=1,maximum=9),'cursor':string(maximum=16000,minimum=0),'page_size':integer(20,1,100),'include_details':boolean(True)}
add('approvals','list','按业务条件查询审批（自动补齐详情）','支持易读状态、日期和模板别名。自动拆分 31 天窗口；只按游标判断结束，空页也能继续。游标绑定调用系统和筛选条件。',query,example={'include_details':False},source=base+'91816')
add('approvals','collect','有界汇总审批（自动分页与跨月查询）','自动翻页和拆分时间窗口，按单号去重。达到 max_items/max_pages 时返回 partial 与续传游标，避免无限拉取或静默截断。',{**query,'max_items':integer(100,1,1000),'max_pages':integer(10,1,100)},example={'include_details':False,'max_items':100},source=base+'91816')
add('approvals','engine_get','查询自建应用 JS-SDK 审批引擎状态','用于通过企微客户端发起的引擎审批，单号使用开发者 third_no。',{'third_no':string(maximum=64)},['third_no'],{'third_no':'fixture-third-001'},source=base+'90269')
add('approvals','engine_launch','生成客户端审批引擎调用参数','生成 thirdPartyOpenPage 参数；必须在企微客户端完成 JS-SDK 初始化后调用，不会在服务端直接提单。',{'template_id':string(),'third_no':string(maximum=64),'mode':dict(type='string',enum=['create','view'],default='create'),'fields':obj()},['template_id','third_no'],{'template_id':'fixture-engine-template','third_no':'fixture-third-001','fields':{'事由':'示例'}},source=base+'90269')
add('approvals','legacy_get','读取旧版审批数据（兼容）','官方旧接口不再维护，新集成请使用 approvals.list/collect。',{'start_at':{},'end_at':{},'next_spnum':dict(type='integer',default=0,minimum=0)},['start_at','end_at'],{'start_at':'2026-11-01','end_at':'2026-11-05'},deprecated=True,source=base+'91530')
add('approval_files','upload','上传可用于审批的临时附件','返回可直接填入 File 字段的 file_id。单文件最多 10 MB，单个审批单最多 6 个附件。',{'filename':string(maximum=128),'content_base64':string(maximum=14000000)},['filename','content_base64'],{'filename':'fixture.txt','content_base64':'Zml4dHVyZQ=='},write=True)
add('approval_files','download','读取审批附件内容','使用 file_id 获取附件，返回 Base64 内容与 MIME 类型，不接受任意远程 URL。',{'file_id':string(maximum=512)},['file_id'],{'file_id':'mock-file-fixture'})
add('approval_events','list','增量读取审批事件','按 after_id 增量读取当前调用系统的验签事件，使用 event_id 去重。与配置的签名 Webhook 转发可同时使用。',{'after_id':integer(0,0,9007199254740991),'limit':integer(50,1,200)},example={})
add('approval_events','retry','重试本系统失败的审批事件转发','仅重试 failed/poll_only 事件，不更改审批业务状态。',{'event_id':string(maximum=64)},['event_id'],{'event_id':'0'*64},write=True)
add('vacation','config','获取企业假期管理配置','查询假期类型、计量粒度与余额规则，供通用请假表单使用。',example={},source=base+'93375')
add('vacation','get','获取成员假期余额','返回统一的秒数值和原始明细，按官方天/小时粒度转换，避免假定一天等于八小时。',{'userid':string(maximum=64)},['userid'],{'userid':'fixture-user'},source=base+'93376')
add('vacation','set','用天或小时设置假期余额','显式选择单位并校验 0.1 单位的精度；需要 confirm=true 和稳定 request_id。',{'userid':string(maximum=64),'vacation_id':dict(type='integer',minimum=1),'remaining':dict(type='number',minimum=0,maximum=24000),'unit':dict(type='string',enum=['days','hours']),'remarks':string(maximum=200,minimum=0),'confirm':boolean(False),**REQ},['userid','vacation_id','remaining','unit'],{'userid':'fixture-user','vacation_id':1,'remaining':8,'unit':'hours','confirm':True,'request_id':'vacation-set-fixture'},write=True,sensitive=True,idempotency=True,source=base+'93377')
creds=[]
for name,label,hint in [('APPROVAL_SECRET','审批应用 Secret','优先用于审批；未设置时使用当前调用系统的企微自建应用凭据，需要审批数据权限'),('CALLBACK_TOKEN','企微回调 Token','与企微后台 API 接收配置一致，用于 SHA-1 验签'),('CALLBACK_AES_KEY','企微回调 EncodingAESKey','与企微后台 API 接收配置一致，固定 43 位')]:
 creds.append(dict(name='WEWORK_'+name,label=label,required=False,secret=True,hint=hint,per_system=True,scoped='WEWORK_{SYSTEM}_'+name))
# Curated P1 collectors preserve native filters while standardizing pagination and response shape.
base_contract=json.loads((ROOT/'src/main/resources/contract.json').read_text(encoding='utf8'))
workflow_specs=[
 ('gmail.messages.search','gmail.messages.list','max_results','page_token'),
 ('gdrive.files.search','gdrive.files.list','page_size','page_token'),
 ('gcs.objects.collect','gcs.objects.list','page_size','page_token'),
 ('nas.objects.collect','nas.objects.list','max_keys','continuation_token'),
 ('shopify.orders.collect','shopify.orders.list','limit','after'),
 ('shopify.products.collect','shopify.products.list','limit','after'),
 ('mabang.orders.collect','mabang.orders.list','maxRows','cursor'),
 *[(key.replace('.list','.collect'),key,'limit','cursor') for key in ['judgeme.reviews.list','trustpilot.reviews.list','stripe.disputes.list','paypal.disputes.list','rd.products.list']],
]
for key,source_key,size_key,cursor_key in workflow_specs:
 source=next(o for o in base_contract['operations'] if o['key']==source_key)
 properties=copy.deepcopy(source['schema']['properties']);properties.pop(size_key,None);properties.pop(cursor_key,None)
 properties.update(page_size=integer(50,1,1000),max_items=integer(100,1,1000),max_pages=integer(10,1,100),resume_cursor=string('回传 page.next_cursor；绑定调用系统和筛选条件',16000,0))
 if key=='gmail.messages.search':properties.update(sender=string('发件人',200),subject=string('主题关键词',200),since={},before={},unread=boolean(False),has_attachment=boolean(False),include_details=boolean(True),include_body=boolean(False))
 if key=='gdrive.files.search':properties.update(name_contains=string('文件名包含',200),parent_id=string('所在文件夹 ID'),mime_type=string('文件 MIME 类型'),modified_since={})
 schema=dict(type='object',additionalProperties=False,properties=properties,required=source['schema'].get('required',[]),examples=[{}])
 if '$defs' in source['schema']:schema['$defs']=copy.deepcopy(source['schema']['$defs'])
 summary={'gmail.messages.search':'按业务条件搜索邮件并整理内容','gdrive.files.search':'按文件名、目录和类型搜索文件'}.get(key,'自动分页汇总：'+source['summary'])
 op=dict(key=key,platform=source['platform'],summary=summary,description='自动分页、统一 items/page/errors；支持 max_items、max_pages 与签名续传游标。仅扫描根记录，嵌套资源遵循原接口的范围。读取能力独立授权，保留原接口兼容。',quota=source.get('quota','120/min'),sensitive=False,idempotent=False,timeout_s=90,retry_max=1,since='2026-11',deprecated=False,tags=['read','business','collection'],category='业务聚合',sort_order=-50,path='/integrations/'+key.replace('.','/').replace('_','-'),schema=schema,source_operation=source_key)
 ops.append(op)
 normalized={k:copy.deepcopy(v['default']) for k,v in properties.items() if 'default' in v}
 cases.append(dict(operation=key,params={},normalized=normalized))

from business_domains import extend_contract
extensions=extend_contract(ops,cases,base_contract)
resource=ROOT/'src/main/resources/business-contract.json';resource.write_text(json.dumps(dict(operations=ops,native_extensions=extensions,credentials=[dict(platform='wecom',vars=creds)]),ensure_ascii=False,indent=2)+'\n',encoding='utf8')
(ROOT/'src/test/resources/business-cases.json').write_text(json.dumps(cases,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
print('Business operations:',len(ops))
