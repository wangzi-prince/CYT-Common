import test from 'node:test';
import assert from 'node:assert/strict';
import { gatewayAddress, callExample } from '../src/utils/documentation.ts';

const operation = {
  key: 'wecom.approvals.submit',
  path: '/integrations/wecom/approvals/submit',
  tags: ['idempotency_required'],
};
test('gateway examples reject credentials and ambiguous addresses while preserving reverse-proxy prefixes', () => {
  assert.equal(
    gatewayAddress('https://gateway.example.com/api/'),
    'https://gateway.example.com/api',
  );
  for (const address of [
    'javascript:alert(1)',
    'https://user:secret@gateway.example.com',
    'https://gateway.example.com?key=secret',
    'https://gateway.example.com/#/docs',
  ])
    assert.equal(gatewayAddress(address), '');
});
test('write examples use the same business request ID in body and header without mutating the source example', () => {
  const params = { request_id: '', fields: { reason: "John's request", enabled: false } };
  const sample = callExample(operation, params, 'http', 'https://gateway.example.com');
  assert.match(sample, /Idempotency-Key: <稳定业务请求号>/);
  assert.match(sample, /"request_id": "<稳定业务请求号>"/);
  assert.ok(sample.includes("John'\\''s request"));
  assert.equal(params.request_id, '');
});
test('SDK examples retain valid JSON decoding and only add idempotency arguments for required operations', () => {
  const params = { active: true, extra: null, request_id: 'order-001' };
  assert.match(callExample(operation, params, 'python', 'http://localhost:8189'), /json.loads\(/);
  assert.match(
    callExample(operation, params, 'typescript', 'https://gateway.example.com'),
    /params, "order-001"/,
  );
  assert.doesNotMatch(
    callExample({ ...operation, tags: ['read'] }, {}, 'http', 'https://gateway.example.com'),
    /Idempotency-Key/,
  );
  assert.equal(callExample(operation, params, 'http', 'invalid-address'), '');
});
