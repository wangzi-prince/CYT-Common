"""Implemented native schema extensions and bounded collection contracts."""
import copy

def extend_contract(ops, cases, base):
    source = {op['key']: op for op in base['operations']}
    extensions = []
    def s(description='', maximum=200): return dict(type='string', minLength=1, maxLength=maximum, description=description)
    def obj(properties=None, required=(), **extra): return dict(type='object', properties=properties or {}, required=list(required), additionalProperties=False, **extra)
    def arr(item, maximum=100): return dict(type='array', items=item, minItems=1, maxItems=maximum)
    def num(minimum=1, maximum=10000): return dict(type='integer', minimum=minimum, maximum=maximum)
    account = dict(type='string', default='', description='上游账号；留空使用当前调用方的默认账号')
    request = {'request_id':s('稳定业务编号，重试保持相同',128)}
    confirm = {'confirm':dict(type='boolean', default=False, description='确认已审核预检结果及业务影响')}
    version = {'plan_version':s('预检返回的版本；执行时校验上游关联数据未变',64)}
    def defaults(value, schema):
        if isinstance(value, dict):
            for key, prop in schema.get('properties',{}).items():
                if key not in value and 'default' in prop:value[key]=copy.deepcopy(prop['default'])
                if key in value:defaults(value[key],prop)
        if isinstance(value,list):
            for item in value:defaults(item,schema.get('items',{}))
    def add(key,title,description,properties,required,example,write=False,sensitive=False,category='业务流程',schema=None):
        schema=schema or obj(properties,required,examples=[example])
        tags=['write' if write else 'read','business']
        if write:tags.append('idempotency_required')
        ops.append(dict(key=key,platform=key.split('.')[0],summary=title,description=description,quota='60/min',sensitive=sensitive,idempotent=write,timeout_s=90,retry_max=1,since='2026-11',deprecated=False,tags=tags,category=category,sort_order=-60,path='/integrations/'+key.replace('.','/').replace('_','-'),schema=schema))
        normalized=copy.deepcopy(example);defaults(normalized,schema)
        cases.append(dict(operation=key,params=example,normalized=normalized))
    def extension(key,properties,definitions=None):
        row=dict(key=key,properties=properties)
        if definitions:row['definitions']=definitions
        extensions.append(row)
    for key in ['docusign.envelopes.list','docusign.templates.list']:
        extension(key,{'start_position':num(0,10000000)})
    role=copy.deepcopy(source['docusign.envelopes.create']['schema']['$defs']['TemplateRole'])
    role['properties']['tabs']=dict(type='object',additionalProperties=True,description='模板预填字段；先通过 templates.get 核对角色和标签')
    extension('docusign.envelopes.create',{}, {'TemplateRole':role})
    work_fields={key:s(maximum=2000) for key in ['amountCurrency','globalPurchaseTime','logisticsProvider','platformCode','preArrivalTime','reasonUuid','remark','siteCode','trackingNo','returnWidDesc','logisticsTypeCode','logisticsProviderDesc','globalTrackNo','waybillNo']}
    work_fields.update({key:num(0,9223372036854775807) for key in ['createManualWork','cutOff','globalOrderNo','hasPrediction','logisticsType','salesType','source','storeId','sid','type','wid','returnWid','deliveryTime','logisticsTypeId','platform','wpType']})
    work_fields.update(workOrderSupplement=dict(type='object',additionalProperties=True),tagList=dict(type='array',items=s()))
    extension('lingxing.workorder.add',work_fields)
    add('docusign.templates.get','读取签署模板、角色和字段','合并模板与收件人字段定义，为业务签署预检提供角色和字段说明。',{'template_id':s()},['template_id'],{'template_id':'demo-template'},category='签署模板')
    # Source-driven collectors share the same signed cursor implementation.
    for key,src,size,cursor in [('docusign.envelopes.collect','docusign.envelopes.list','page_size','start_position'),('docusign.templates.collect','docusign.templates.list','page_size','start_position'),('quickcep.messages.collect','quickcep.messages.list','page_size','page'),('lingxing.orders.collect','lingxing.orders.list','length','offset'),('lingxing.product.collect','lingxing.product.list','length','offset')]:
        props=copy.deepcopy(source[src]['schema']['properties']);props.pop(size,None);props.pop(cursor,None)
        props.update(page_size=dict(type='integer',default=50,minimum=1,maximum=1000),max_items=dict(type='integer',default=200,minimum=1,maximum=1000),max_pages=dict(type='integer',default=10,minimum=1,maximum=100),resume_cursor=s(maximum=16000))
        example={'session_id':'demo-session'} if key.startswith('quickcep') else {'start_date':'2026-11-01','end_date':'2026-11-06'} if key=='lingxing.orders.collect' else {}
        schema=obj(props,source[src]['schema'].get('required',[]),examples=[example])
        add(key,'自动分页汇总：'+source[src]['summary'],'有界自动分页，统一 items/page/errors 与签名续传；保持原筛选，续传时每页大小固定。',{},[],schema['examples'][0],category='业务聚合',schema=schema)
    return extensions
