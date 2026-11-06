import {
  Connection,
  Grid,
  Setting,
  Tools,
  User,
  Document,
  DataAnalysis,
  List,
  Key,
  Refresh,
  Tickets,
  House,
} from '@element-plus/icons-vue';

export const navigation = [
  {
    title: '工作空间',
    items: [
      {
        path: '/workspace',
        label: '工作台',
        icon: House,
        description: '从接入服务、开放能力或业务调用开始',
        keywords: 'home start 开始 入门 工作台',
      },
      {
        path: '/platforms',
        label: '服务接入',
        icon: Connection,
        description: '我们调用第三方：连接平台、凭据与接口',
        keywords: 'platform upstream 第三方 领星 企微 服务 平台 接入',
      },
      {
        path: '/exposed',
        label: '对外能力',
        icon: Grid,
        description: '自有系统开放业务接口，供其他系统调用',
        keywords: 'capability publish api 发布 对外 封装 能力',
      },
      {
        path: '/systems',
        label: '调用方',
        icon: User,
        description: '在一个系统内管理密钥、授权与接入文档',
        keywords: 'consumer system app key 调用方 密钥 权限 授权',
      },
      {
        path: '/docs',
        label: '接入文档',
        icon: Document,
        description: '快速接入、接口参考与可交付的文档和 SDK',
        keywords: 'docs documentation sdk guide quickstart 文档 指南 示例 开发者 快速接入',
      },
      {
        path: '/monitor',
        label: '运行中心',
        icon: DataAnalysis,
        description: '观察调用、定位失败并追踪配置变更',
        keywords: 'monitor overview logs 监控 概览 调用 记录 审计',
      },
    ],
  },
  {
    title: '管理',
    items: [
      {
        path: '/settings',
        label: '设置',
        icon: Setting,
        description: '管理环境、上游凭据与回调',
        keywords: 'settings credential env webhook 环境 凭据 回调 配置',
      },
    ],
  },
];

export const destinations = [
  ...navigation.flatMap((group) => group.items.map((item) => ({ ...item, group: group.title }))),
  {
    path: '/registry?platform=wecom&domain=approval',
    label: '企业微信 · 审批能力',
    icon: Tickets,
    description: '在企微服务内管理审批模板、申请和查询',
    keywords: 'wecom approval 企微 审批 模板',
    group: '服务接入',
  },
  {
    path: '/modules?tab=modules&action=create',
    label: '接入新的 HTTP 服务',
    icon: Connection,
    description: '配置第三方平台连接',
    keywords: 'module http 新增 新建 服务',
    group: '服务接入',
  },
  {
    path: '/modules?tab=apis&action=create',
    label: '登记已有 HTTP 接口',
    icon: Tools,
    description: '设置服务接口的路径、参数和返回值',
    keywords: 'rest endpoint 新增 接口',
    group: '服务接入',
  },
  {
    path: '/capabilities',
    label: '封装说明',
    icon: Document,
    description: '核对能力实现、推荐用法与业务边界',
    keywords: 'review 封装 深度 说明',
    group: '对外能力',
  },
  {
    path: '/settings?tab=credentials',
    label: '环境与凭据',
    icon: Key,
    description: '管理上游服务凭据',
    keywords: 'secret credential 上游 凭据 密钥',
    group: '设置',
  },
  {
    path: '/settings?tab=callbacks',
    label: '事件回调',
    icon: Refresh,
    description: '配置结果事件分发',
    keywords: 'webhook callback 回调 事件',
    group: '设置',
  },
  {
    path: '/monitor?tab=calls',
    label: '调用记录',
    icon: List,
    description: '按接口与系统查找失败调用',
    keywords: 'calls trace log 日志 错误',
    group: '运行中心',
  },
];

export function navigationPath(path: string, system?: unknown, direction?: unknown) {
  if (['/registry', '/modules', '/playground'].includes(path) && direction === 'internal')
    return '/exposed';
  if (path === '/playground' && system) return '/systems';
  if (path.startsWith('/systems') || path === '/grants') return '/systems';
  if (['/registry', '/modules', '/playground'].includes(path)) return '/platforms';
  if (path === '/capabilities') return '/exposed';
  if (['/overview', '/calls', '/audit'].includes(path)) return '/monitor';
  if (['/credentials', '/callbacks'].includes(path)) return '/settings';
  return path;
}
