import test from 'node:test';
import assert from 'node:assert/strict';
import { operationModules, filterOperationModule } from '../src/utils/operation-modules.ts';
import { filterGrantOperations, updateGrantSelection } from '../src/utils/grants.ts';

const operations = [
  {
    key: 'wecom.templates.get',
    platform: 'wecom',
    category: '审批模板',
    summary: '获取模板',
    tags: ['read'],
  },
  {
    key: 'wecom.templates.create',
    platform: 'wecom',
    category: '审批模板 ',
    summary: '创建模板',
    sensitive: true,
  },
  {
    key: 'wecom.approvals.get',
    platform: 'wecom',
    category: '通用审批',
    summary: '查询申请',
    tags: ['read'],
  },
  {
    key: 'gmail.messages.get',
    platform: 'gmail',
    category: '邮件',
    summary: '读取邮件',
    tags: ['read'],
  },
  { key: 'orders.actions.get', platform: 'orders', category: '', summary: '查询订单' },
];

test('modules follow the current catalog, normalize missing categories and include newly registered groups', () => {
  const current = [...operations, { key: 'wecom.calendar.get', category: '日程' }];
  assert.deepEqual(operationModules(current), [
    { name: '审批模板', total: 2, count: 2 },
    { name: '通用审批', total: 1, count: 1 },
    { name: '邮件', total: 1, count: 1 },
    { name: '未分类', total: 1, count: 1 },
    { name: '日程', total: 1, count: 1 },
  ]);
  assert.equal(filterOperationModule(current, '未分类')[0].key, 'orders.actions.get');
});

test('platform and keyword filters update module counts without hiding known empty modules', () => {
  const scope = operations.filter((operation) => operation.platform === 'wecom');
  const matching = filterGrantOperations(
    scope,
    {
      query: '查询',
      platform: 'wecom',
      state: 'all',
      kind: 'all',
    },
    new Set(),
  );
  assert.deepEqual(operationModules(scope, matching), [
    { name: '审批模板', total: 2, count: 0 },
    { name: '通用审批', total: 1, count: 1 },
  ]);
  assert.deepEqual(filterOperationModule(matching, '审批模板'), []);
  assert.equal(filterOperationModule(matching, '').length, 1);
});

test('selecting every result in a module respects permission filters and preserves selections in other modules', () => {
  const matching = filterGrantOperations(
    operations,
    {
      query: '',
      platform: 'wecom',
      state: 'denied',
      kind: 'read',
    },
    new Set(['wecom.approvals.get']),
  );
  const chosen = filterOperationModule(matching, '审批模板');
  const selection = updateGrantSelection(
    new Set(['gmail.messages.get']),
    chosen.map((operation) => operation.key),
    true,
  );
  assert.deepEqual([...selection], ['gmail.messages.get', 'wecom.templates.get']);
  assert.equal(operations[1].category, '审批模板 ');
});
