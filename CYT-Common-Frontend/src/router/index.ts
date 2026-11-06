import { createRouter, createWebHashHistory } from 'vue-router';
import { TOKEN_KEY } from '~/http';
import { trackNavigation } from './navigation-feedback';
const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/login', component: () => import('~/pages/Login.vue'), meta: { title: '登录' } },
    {
      path: '/',
      component: () => import('~/layouts/AdminLayout.vue'),
      redirect: '/workspace',
      children: [
        {
          path: 'workspace',
          component: () => import('~/pages/Workspace.vue'),
          meta: { title: '工作台', group: '工作空间' },
        },
        {
          path: 'monitor',
          component: () => import('~/pages/Monitor.vue'),
          meta: { title: '运行中心', group: '工作空间' },
        },
        {
          path: 'settings',
          component: () => import('~/pages/Settings.vue'),
          meta: { title: '设置', group: '管理' },
        },
        {
          path: 'systems/:system',
          component: () => import('~/pages/ConsumerWorkspace.vue'),
          meta: { title: '调用方接入', group: '调用方' },
        },
        {
          path: 'exposed',
          component: () => import('~/pages/ExposedCapabilities.vue'),
          meta: { title: '对外能力', group: '工作空间' },
        },
        {
          path: 'approvals',
          redirect: { path: '/registry', query: { platform: 'wecom', domain: 'approval' } },
        },
        {
          path: 'capabilities',
          component: () => import('~/pages/Capabilities.vue'),
          meta: { title: '能力指南', group: '业务调用' },
        },
        {
          path: 'overview',
          component: () => import('~/pages/Overview.vue'),
          meta: { title: '调用总览', group: '运行监控' },
        },
        {
          path: 'calls',
          component: () => import('~/pages/Calls.vue'),
          meta: { title: '调用记录', group: '运行监控' },
        },
        {
          path: 'platforms',
          component: () => import('~/pages/Platforms.vue'),
          meta: { title: '服务接入', group: '工作空间' },
        },
        {
          path: 'registry',
          component: () => import('~/pages/Registry.vue'),
          meta: { title: '服务接口', group: '服务接入' },
        },
        {
          path: 'systems',
          component: () => import('~/pages/Systems.vue'),
          meta: { title: '调用方', group: '工作空间' },
        },
        {
          path: 'grants',
          redirect: (to) =>
            to.query.system
              ? {
                  path: `/systems/${encodeURIComponent(String(to.query.system))}`,
                  query: { ...to.query, tab: 'access' },
                }
              : { path: '/systems', query: { ...to.query, intent: 'grant' } },
        },
        {
          path: 'credentials',
          component: () => import('~/pages/Credentials.vue'),
          meta: { title: '环境与凭据', group: '环境与集成' },
        },
        {
          path: 'callbacks',
          component: () => import('~/pages/Callbacks.vue'),
          meta: { title: '回调配置', group: '环境与集成' },
        },
        {
          path: 'audit',
          component: () => import('~/pages/Audit.vue'),
          meta: { title: '审计日志', group: '运行监控' },
        },
        {
          path: 'modules',
          component: () => import('~/pages/ApiModules.vue'),
          meta: { title: '服务与接口配置', group: '服务接入' },
        },
        {
          path: 'playground',
          component: () => import('~/pages/Playground.vue'),
          meta: { title: '接口调试', group: '服务接入' },
        },
        {
          path: 'docs',
          component: () => import('~/pages/Docs.vue'),
          meta: { title: '接入文档', group: '工作空间' },
        },
      ],
    },
    { path: '/:pathMatch(.*)*', redirect: '/workspace' },
  ],
});
router.beforeEach((to) => {
  if (to.path !== '/login' && !localStorage.getItem(TOKEN_KEY))
    return { path: '/login', query: { redirect: to.fullPath } };
});
export const navigationFeedback = trackNavigation(router);
router.afterEach((to, _from, failure) => {
  if (!failure) document.title = `${String(to.meta.title || '管理平台')} · CYT Common`;
});
export default router;
