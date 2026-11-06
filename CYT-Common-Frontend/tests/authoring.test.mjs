import test from 'node:test';
import assert from 'node:assert/strict';
import {
  buildPlatformDirectory,
  selectPlatforms,
  platformPage,
} from '../src/utils/platform-directory.ts';
import {
  readParameters,
  writeParameters,
  splitEndpoint,
  templateParameters,
  buildCatalog,
  gatewayPath,
} from '../src/utils/authoring.ts';

test('editing parameter definitions preserves false, zero and existing metadata', () => {
  const original = [
    { name: 'enabled', type: 'bool', default: false, required: false },
    { name: 'page', type: 'int', default: 0, description: '页码', enum: [0, 1] },
  ];
  const result = JSON.parse(writeParameters(readParameters(JSON.stringify(original))));
  assert.equal(result[0].default, false);
  assert.equal(result[0].type, 'boolean');
  assert.equal(result[1].default, 0);
  assert.equal(result[1].type, 'integer');
  assert.deepEqual(result[1].enum, [0, 1]);
});

test('invalid edits are rejected before saving instead of silently changing the payload', () => {
  const rows = readParameters('[{"name":"page","type":"integer","default":1}]');
  assert.throws(() => writeParameters([...rows, ...rows]), /重复/);
  assert.throws(() => writeParameters([{ ...rows[0], defaultText: 'false' }]), /类型不一致/);
  assert.throws(() => readParameters('{}'), /数组/);
  assert.throws(() => writeParameters([{ ...rows[0], name: 'bad-name' }]), /参数名/);
});

test('a pasted endpoint preserves its path template and rejects embedded credentials or query values', () => {
  assert.deepEqual(splitEndpoint('https://api.example.com/v1/items/{{params.id}}'), {
    base: 'https://api.example.com',
    path: '/v1/items/{{params.id}}',
  });
  assert.deepEqual(splitEndpoint('https://api.example.com'), {
    base: 'https://api.example.com',
    path: '/',
  });
  for (const value of [
    'https://user:password@api.example.com/items',
    'https://api.example.com/items?token=value',
    'http://api.example.com/items',
    'https://api.example.com/items#fragment',
  ])
    assert.throws(() => splitEndpoint(value));
});

test('parameter inference includes path, JSON body and header references without duplicates', () => {
  assert.deepEqual(
    templateParameters(
      '/items/{{params.id}}',
      '{"owner":"{{ params.owner }}","item":"{{params.id}}"}',
      '{"X-Page":"{{params.page}}"}',
    ),
    ['id', 'owner', 'page'],
  );
});

test('the catalog retains inactive definitions and honors the platform state over stale runtime metadata', () => {
  const builtin = {
    key: 'native.items.list',
    platform: 'native',
    summary: '内置接口',
    category: '查询',
    sort_order: 0,
    schema: {},
  };
  const row = {
    id: 2,
    key: 'demo.items.get',
    name: '读取项目',
    module_key: 'demo',
    method: 'GET',
    path: '/items/{{params.id}}',
    params_schema_json: '[{"name":"id","type":"string","required":true}]',
    enabled: true,
    sensitive: false,
    timeout_s: 30,
  };
  const input = structuredClone(row);
  const result = buildCatalog(
    [builtin, { ...builtin, key: row.key, platform: 'demo' }],
    [row],
    [{ key: 'demo', enabled: false }],
  );
  assert.equal(result.find((o) => o.key === builtin.key).source, 'builtin');
  const custom = result.find((o) => o.key === row.key);
  assert.equal(custom.available, false);
  assert.equal(custom.status, '平台已停用');
  assert.equal(custom.configId, 2);
  assert.deepEqual(row, input);
  const inactive = buildCatalog(
    [builtin],
    [{ ...row, enabled: false }],
    [{ key: 'demo', enabled: true }],
  );
  assert.equal(inactive.find((o) => o.key === row.key).status, '未启用');
  assert.equal(inactive.find((o) => o.key === row.key).schema.required[0], 'id');
  assert.equal(gatewayPath('demo.items.get_detail'), '/integrations/demo/items/get-detail');
});

const thousandPlatforms = buildPlatformDirectory(
  [],
  Array.from({ length: 1000 }, (_, index) => {
    const id = index + 1,
      key = 'vendor_' + String(id).padStart(4, '0');
    return {
      id,
      key,
      name: '业务平台 ' + id,
      description: '订单与客户数据',
      base_url: 'https://api.' + key + '.example.com',
      auth_type: 'bearer',
      enabled: id % 10 !== 0,
      operation_count: id % 37,
    };
  }),
);
const directoryFilters = { q: '', source: 'all', state: 'all', scope: 'all', sort: 'name' };

test('one thousand platforms have stable ordering and bounded pages without lost or duplicate records', () => {
  const sorted = selectPlatforms(thousandPlatforms, directoryFilters, new Set());
  const keys = [];
  for (let page = 1; page <= 50; page++) {
    const result = platformPage(sorted, page, 20);
    assert.equal(result.items.length, 20);
    assert.equal(result.from, (page - 1) * 20 + 1);
    keys.push(...result.items.map((p) => p.platform));
  }
  assert.equal(new Set(keys).size, 1000);
  assert.equal(keys[0], 'vendor_0001');
  assert.equal(keys[999], 'vendor_1000');
});

test('search finds a platform across name, key and domain and applies all keywords', () => {
  const result = selectPlatforms(
    thousandPlatforms,
    { ...directoryFilters, q: '业务 api.vendor_0973.example.com' },
    new Set(),
  );
  assert.deepEqual(
    result.map((p) => p.platform),
    ['vendor_0973'],
  );
  assert.equal(
    selectPlatforms(thousandPlatforms, { ...directoryFilters, q: 'vendor_0999' }, new Set())[0]
      .platform,
    'vendor_0999',
  );
  assert.equal(
    selectPlatforms(
      thousandPlatforms,
      { ...directoryFilters, q: '不存在 api.vendor_0973.example.com' },
      new Set(),
    ).length,
    0,
  );
});

test('favorites and actionable states narrow a thousand platforms without changing source records', () => {
  const favorites = new Set(['vendor_0002', 'vendor_0990']);
  assert.equal(
    selectPlatforms(thousandPlatforms, { ...directoryFilters, sort: 'recommended' }, favorites)[0]
      .platform,
    'vendor_0002',
  );
  assert.equal(
    selectPlatforms(thousandPlatforms, { ...directoryFilters, scope: 'favorites' }, favorites)
      .length,
    2,
  );
  assert.deepEqual(
    selectPlatforms(
      thousandPlatforms,
      { ...directoryFilters, scope: 'favorites', state: 'disabled' },
      favorites,
    ).map((p) => p.platform),
    ['vendor_0990'],
  );
  assert.equal(
    selectPlatforms(thousandPlatforms, { ...directoryFilters, scope: 'attention' }, favorites)
      .length,
    100,
  );
  assert.equal(thousandPlatforms[0].platform, 'vendor_0001');
});

test('searching from the final page returns the first matching page and clamps untrusted page sizes', () => {
  const result = selectPlatforms(
    thousandPlatforms,
    { ...directoryFilters, q: 'vendor_0999' },
    new Set(),
  );
  assert.equal(platformPage(result, 50, 20).page, 1);
  assert.equal(platformPage(result, 50, 20).items[0].platform, 'vendor_0999');
  assert.equal(platformPage(thousandPlatforms, 1, 10000).items.length, 100);
  assert.equal(platformPage([], 50, 20).from, 0);
});


test('service directory separates own systems from third-party connectors', () => {
  const result = buildPlatformDirectory([], [
    { key: 'orders', name: '订单系统', service_kind: 'internal', enabled: true, operation_count: 2 },
    { key: 'partner', name: '外部服务', service_kind: 'third_party', enabled: true, operation_count: 1 },
  ]);
  const chosen = selectPlatforms(result, { q: '', source: 'all', state: 'all', scope: 'all', sort: 'name', kind: 'internal' }, new Set());
  assert.deepEqual(chosen.map((p) => p.platform), ['orders']);
  assert.equal(chosen[0].operation_count, 2);
});
