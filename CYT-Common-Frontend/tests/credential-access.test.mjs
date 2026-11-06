import test from 'node:test';
import assert from 'node:assert/strict';
import { useCredentialAccess } from '../src/composables/useCredentialAccess.ts';
function deferred() {
  let resolve;
  const promise = new Promise((yes) => {
    resolve = yes;
  });
  return { promise, resolve };
}

test('view reads one selected credential and closing clears the plaintext', async () => {
  const calls = [];
  const access = useCredentialAccess(
    async (...args) => {
      calls.push(args);
      return { value: 'fixture-value\nsecond line', source: 'db' };
    },
    async () => {},
  );
  await access.view('staging', 'CUSTOM_TOKEN');
  assert.deepEqual(calls, [['staging', 'CUSTOM_TOKEN', 'view']]);
  assert.equal(access.value.value, 'fixture-value\nsecond line');
  access.reset();
  assert.equal(access.value.value, '');
  assert.equal(access.target.value, undefined);
});
test('copy fetches the actual credential without exposing it in the viewer', async () => {
  const calls = [],
    clipboard = [];
  const access = useCredentialAccess(
    async (...args) => {
      calls.push(args);
      return { value: 'fixture-actual-secret', source: 'env' };
    },
    async (value) => clipboard.push(value),
  );
  assert.equal(await access.copy('local', 'CUSTOM_TOKEN'), 'copied');
  assert.deepEqual(calls, [['local', 'CUSTOM_TOKEN', 'copy']]);
  assert.deepEqual(clipboard, ['fixture-actual-secret']);
  assert.equal(access.value.value, '');
  assert.equal(access.target.value, undefined);
});
test('switching environment or closing cancels pending reveals and clipboard writes', async () => {
  const viewing = deferred(),
    copying = deferred(),
    clipboard = [];
  const access = useCredentialAccess(
    async (_env, _name, action) => (action === 'view' ? viewing.promise : copying.promise),
    async (value) => clipboard.push(value),
  );
  const read = access.view('old-env', 'CUSTOM_TOKEN'),
    copy = access.copy('old-env', 'CUSTOM_TOKEN');
  access.reset();
  viewing.resolve({ value: 'fixture-old-secret', source: 'db' });
  copying.resolve({ value: 'fixture-old-secret', source: 'db' });
  await read;
  assert.equal(await copy, 'cancelled');
  assert.equal(access.value.value, '');
  assert.deepEqual(clipboard, []);
});
test('a slow older reveal cannot replace the newly selected credential', async () => {
  const old = deferred();
  const access = useCredentialAccess(
    async (_env, name) => (name === 'OLD' ? old.promise : { value: 'fixture-new', source: 'env' }),
    async () => {},
  );
  const read = access.view('local', 'OLD');
  await access.view('local', 'NEW');
  old.resolve({ value: 'fixture-old', source: 'db' });
  await read;
  assert.equal(access.target.value.name, 'NEW');
  assert.equal(access.value.value, 'fixture-new');
});
test('clipboard and read failures report failure without a false copied result', async () => {
  const denied = useCredentialAccess(
    async () => {
      throw new Error('denied');
    },
    async () => {},
  );
  assert.equal(await denied.copy('local', 'TOKEN'), 'failed');
  const blocked = useCredentialAccess(
    async () => ({ value: 'fixture-value', source: 'db' }),
    async () => {
      throw new Error('clipboard blocked');
    },
  );
  assert.equal(await blocked.copy('local', 'TOKEN'), 'clipboardUnavailable');
  assert.equal(blocked.copying.value, '');
});
