import test from 'node:test';
import assert from 'node:assert/strict';
import { filterGrantOperations, updateGrantSelection } from '../src/utils/grants.ts';
const operations = [
  { key: 'wecom.approvals.track', platform: 'wecom', summary: '按业务请求号追踪审批', description: '', tags: ['read'], sensitive: false },
  { key: 'wecom.approvals.submit', platform: 'wecom', summary: '提交审批', description: '', tags: ['write'], sensitive: true },
  { key: 'gmail.profile.get', platform: 'gmail', summary: '邮箱身份', description: '', tags: ['read'], sensitive: false },
];
const options = { query: '企业微信 审批', platform: '', state: 'all', kind: 'read' };
test('authorization search combines Chinese platform names, business keywords and effective state', () => {
  assert.deepEqual(filterGrantOperations(operations, options, new Set(), { wecom: '企业微信' }).map(o => o.key), ['wecom.approvals.track']);
  assert.equal(filterGrantOperations(operations, { ...options, state: 'allowed' }, new Set(), { wecom: '企业微信' }).length, 0);
  assert.equal(filterGrantOperations(operations, { ...options, query: '', kind: 'sensitive', platform: 'wecom' }, new Set()).length, 1);
});
test('cross-page selection survives filtering and removing a page preserves other selections', () => {
  const initial = new Set(['first-page']);
  const both = updateGrantSelection(initial, ['second-page'], true);
  assert.deepEqual([...both], ['first-page', 'second-page']);
  assert.deepEqual([...initial], ['first-page']);
  assert.deepEqual([...updateGrantSelection(both, ['first-page'], false)], ['second-page']);
});
