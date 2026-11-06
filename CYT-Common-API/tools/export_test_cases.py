"""Export validated example parameters from the reference, without invoking adapters."""
import json
import sys
from pathlib import Path

sys.path.insert(0, sys.argv[1])
from gateway.registry import all_operations

def sample(schema, root, name=''):
    if '$ref' in schema:
        return sample(root['$defs'][schema['$ref'].split('/')[-1]], root, name)
    if 'default' in schema:
        return schema['default']
    if 'enum' in schema:
        return schema['enum'][0]
    if 'anyOf' in schema:
        return sample(next(s for s in schema['anyOf'] if s.get('type') != 'null'), root, name)
    kind = schema.get('type')
    if kind == 'object':
        return {k: sample(v, root, k) for k, v in schema.get('properties', {}).items() if k in schema.get('required', [])}
    if kind == 'array':
        return [sample(schema['items'], root, name)] * max(1, schema.get('minItems', 1))
    if kind in ('integer', 'number'):
        return schema.get('minimum', 1)
    if kind == 'boolean': return False
    if 'email' in name: return 'fixture@example.invalid'
    if name == 'document_base64': return 'aGVsbG8='
    if name == 'file_extension': return 'txt'
    if name == 'bucket': return 'cyt-test-bucket'
    if name == 'filename': return 'fixture.txt'
    if name == 'reference': return 'RMA-fixture-01'
    if name in ('path', 'key', 'source_key', 'dest_key'): return 'fixture/file.txt'
    if name == 'currency': return 'USD'
    if name == 'color': return '#5856D6'
    if name == 'countryCode': return 'US'
    if name == 'order_id' and kind == 'string': return 'gid://shopify/Order/123'
    return 'fixture-' + name.replace('_', '-')

cases = []
for op in all_operations():
    schema = op.schema()
    p = sample(schema, schema)
    if op.key == 'docusign.envelopes.create':
        p.update(template_id='fixture-template', template_roles=[{'role_name':'signer','email':'fixture@example.invalid','name':'Fixture'}])
    if op.key == 'quickcep.sessions.list': p['create_date'] = '2026-10-01'
    if op.key == 'wecom.directory.users': p['userids'] = ['fixture-user']
    if op.key == 'wecom.schedules.create': p.update(start_at='2026-10-30T10:00:00+08:00',end_at='2026-10-30T11:00:00+08:00')
    if op.key == 'wecom.schedules.batch_create': p['items'] = [{'summary':'Fixture','start_at':'2026-10-30T10:00:00+08:00','end_at':'2026-10-30T11:00:00+08:00'}]
    if op.key == 'wecom.calendars.create': p['shares'] = [{'userid':'fixture-user'}]
    if op.key in ('gcs.objects.upload','gdrive.files.upload'): p['content_text'] = 'fixture content'
    if op.key == 'shopify.refunds.create': p.update(amount='1.00',confirm=True)
    if op.key == 'shopify.orders.create': p.update(confirm=True,shipping_address={'name':'Fixture User','address1':'Test Street','countryCode':'US'})
    if op.key == 'shopify.orders.update': p['note'] = 'fixture'
    if op.key == 'nas.objects.copy_prefix': p.update(source_prefix='fixture-source/',dest_prefix='fixture-target/')
    if op.key == 'nas.objects.presign_put': p.update(folder='fixture',filename='fixture.txt')
    # The synthetic sample cannot satisfy the vendor's own branch rules for a return order:
    # confirm is a deliberate human gesture, and the non-Winit-outbound branch demands a ReturnLabel
    # channel, a parcel box and a full return address. Same rules the Java gateway re-checks.
    if op.key == 'winit.returns.create':
        p.update(confirm=True, winitProductCode='OW01011004124',
                 packageLength=11.0, packageWidth=22.0, packageHeight=33.0, packageWeight=4.0,
                 name='Fixture User', country='US', city='Los Angeles', postcode='90001',
                 address1='1 Test Street')
    if op.key == 'winit.returns.void': p['confirm'] = True
    try:
        validated = op.params_model.model_validate(p).model_dump(mode='json')
    except Exception as error:
        raise RuntimeError(f'Invalid fixture: {op.key}: {error}') from error
    cases.append({'operation': op.key, 'params': p, 'normalized': validated})

destination = Path(__file__).resolve().parents[1] / 'src/test/resources/operation-cases.json'
destination.parent.mkdir(parents=True, exist_ok=True)
destination.write_text(json.dumps(cases, ensure_ascii=False, indent=2), encoding='utf-8')
print(f'Exported {len(cases)} parameter cases validated by the original Python models. No adapter executed.')
