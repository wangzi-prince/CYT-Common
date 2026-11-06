import test from 'node:test';
import assert from 'node:assert/strict';
import {
  createRouter,
  createMemoryHistory,
  isNavigationFailure,
  NavigationFailureType,
} from 'vue-router';
import { trackNavigation } from '../src/router/navigation-feedback.ts';

const page = { render: () => null };
function setup(extra) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: page, meta: { title: '工作台' } },
      { path: '/other', component: page, meta: { title: '其他页面' } },
      ...extra,
    ],
  });
  return { router, feedback: trackNavigation(router) };
}
function deferred() {
  let resolve, reject;
  const promise = new Promise((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return { promise, resolve, reject };
}

test('lazy page loading shows the destination while the current route remains unchanged', async () => {
  const gate = deferred(),
    started = deferred();
  const { router, feedback } = setup([
    {
      path: '/slow',
      meta: { title: '服务接入' },
      component: () => {
        started.resolve();
        return gate.promise;
      },
    },
  ]);
  await router.push('/');
  const navigation = feedback.navigate('/slow');
  await started.promise;
  assert.equal(router.currentRoute.value.path, '/');
  assert.deepEqual(feedback.pending.value, { path: '/slow', title: '服务接入' });
  gate.resolve(page);
  assert.equal(await navigation, true);
  assert.equal(router.currentRoute.value.path, '/slow');
  assert.equal(feedback.pending.value, undefined);
});

test('failed lazy loading preserves the current route and exposes the exact retry destination', async () => {
  let attempts = 0;
  const { router, feedback } = setup([
    {
      path: '/docs',
      meta: { title: '接入文档' },
      component: () =>
        ++attempts === 1
          ? Promise.reject(new Error('Failed to fetch dynamically imported module'))
          : Promise.resolve(page),
    },
  ]);
  await router.push('/');
  assert.equal(await feedback.navigate('/docs?direction=internal'), false);
  assert.equal(router.currentRoute.value.path, '/');
  assert.deepEqual(feedback.failed.value, { path: '/docs?direction=internal', title: '接入文档' });
  assert.equal(feedback.pending.value, undefined);
  assert.equal(await feedback.retry(), true);
  assert.equal(router.currentRoute.value.fullPath, '/docs?direction=internal');
  assert.equal(feedback.failed.value, undefined);
});

test('an earlier failed load cannot overwrite a newer successful navigation', async () => {
  const gate = deferred(),
    started = deferred();
  const { router, feedback } = setup([
    {
      path: '/slow',
      component: () => {
        started.resolve();
        return gate.promise;
      },
    },
  ]);
  await router.push('/');
  const oldNavigation = feedback.navigate('/slow');
  await started.promise;
  assert.equal(await feedback.navigate('/other'), true);
  gate.reject(new Error('late failure'));
  assert.equal(await oldNavigation, false);
  assert.equal(router.currentRoute.value.path, '/other');
  assert.equal(feedback.pending.value, undefined);
  assert.equal(feedback.failed.value, undefined);
});

test('cancelled form departure leaves the current page selected and does not report a loading error', async () => {
  const { router, feedback } = setup([]);
  router.beforeEach((to) => (to.path === '/other' ? false : undefined));
  await router.push('/');
  assert.equal(await feedback.navigate('/other'), false);
  assert.equal(router.currentRoute.value.path, '/');
  assert.equal(feedback.pending.value, undefined);
  assert.equal(feedback.failed.value, undefined);
  assert.equal(await feedback.navigate('/'), true);
});

test('a superseded slow navigation never leaves a loading indicator behind', async () => {
  const gate = deferred(),
    started = deferred();
  const { router, feedback } = setup([
    {
      path: '/slow',
      component: () => {
        started.resolve();
        return gate.promise;
      },
    },
  ]);
  await router.push('/');
  const oldNavigation = router.push('/slow');
  await started.promise;
  await feedback.navigate('/other');
  gate.resolve(page);
  assert.ok(isNavigationFailure(await oldNavigation, NavigationFailureType.cancelled));
  assert.equal(router.currentRoute.value.path, '/other');
  assert.equal(feedback.pending.value, undefined);
});
