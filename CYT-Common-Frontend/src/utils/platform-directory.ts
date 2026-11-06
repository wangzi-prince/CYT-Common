import type { Platform, PlatformModule } from '../api/types';

export type PlatformState = 'configured' | 'pending' | 'mock' | 'disabled';
export interface DirectoryPlatform extends Platform {
  kind: 'third_party' | 'internal';
  source: 'builtin' | 'custom';
  state: PlatformState;
  module?: PlatformModule;
}
export interface DirectoryFilters {
  kind?: string;
  q: string;
  source: string;
  state: string;
  scope: string;
  sort: string;
}
const names = new Intl.Collator('zh-CN', { numeric: true, sensitivity: 'base' });

export function buildPlatformDirectory(
  platforms: Platform[],
  modules: PlatformModule[],
): DirectoryPlatform[] {
  const connections = new Set(modules.map((m) => m.key));
  const builtin: DirectoryPlatform[] = platforms
    .filter((p) => !connections.has(p.platform))
    .map((p) => ({
      ...p,
      kind: 'third_party',
      source: 'builtin',
      state: p.mock ? 'mock' : p.configured ? 'configured' : 'pending',
    }));
  const custom: DirectoryPlatform[] = modules.map((m) => ({
    kind: m.service_kind === 'internal' ? 'internal' : 'third_party',
    platform: m.key,
    display_name: m.name || m.key,
    mock: false,
    configured: Boolean(m.enabled),
    platform_quota: null,
    operation_count: Number(m.operation_count || 0),
    sensitive_count: 0,
    note:
      m.description ||
      (m.enabled
        ? '连接配置已保存，真实鉴权请通过接口调试验证。'
        : '平台已停用，所属接口当前均不可调用。'),
    module: m,
    source: 'custom',
    state: m.enabled ? 'configured' : 'disabled',
  }));
  return [...builtin, ...custom];
}

export function needsAttention(platform: DirectoryPlatform) {
  return platform.state === 'pending' || platform.state === 'disabled';
}

export function selectPlatforms(
  platforms: DirectoryPlatform[],
  filters: DirectoryFilters,
  favorites: ReadonlySet<string>,
): DirectoryPlatform[] {
  const query = filters.q.trim().toLocaleLowerCase();
  const tokens = query.split(/\s+/).filter(Boolean);
  const score = (p: DirectoryPlatform) => {
    if (!query) return 0;
    const key = p.platform.toLocaleLowerCase(),
      name = p.display_name.toLocaleLowerCase();
    return key === query || name === query
      ? 3
      : key.startsWith(query) || name.startsWith(query)
        ? 2
        : 1;
  };
  return platforms
    .filter((p) => {
      if (filters.kind && filters.kind !== 'all' && p.kind !== filters.kind) return false;
      if (filters.source !== 'all' && p.source !== filters.source) return false;
      if (filters.state !== 'all' && p.state !== filters.state) return false;
      if (filters.scope === 'favorites' && !favorites.has(p.platform)) return false;
      if (filters.scope === 'attention' && !needsAttention(p)) return false;
      const searchable = [
        p.platform,
        p.display_name,
        p.module?.base_url || '',
        p.module?.description || '',
        p.note,
      ]
        .join(' ')
        .toLocaleLowerCase();
      return tokens.every((token) => searchable.includes(token));
    })
    .sort((a, b) => {
      const relevance = score(b) - score(a);
      if (relevance) return relevance;
      if (filters.sort === 'recommended') {
        const priority = Number(favorites.has(b.platform)) - Number(favorites.has(a.platform));
        if (priority) return priority;
      }
      if (filters.sort === 'operations') {
        const count = b.operation_count - a.operation_count;
        if (count) return count;
      }
      return names.compare(a.display_name, b.display_name) || names.compare(a.platform, b.platform);
    });
}

export function platformPage<T>(items: T[], requestedPage: number, requestedSize: number) {
  const pageSize = Number.isFinite(requestedSize)
    ? Math.min(100, Math.max(1, Math.trunc(requestedSize)))
    : 20;
  const pageCount = Math.max(1, Math.ceil(items.length / pageSize));
  const page = Number.isFinite(requestedPage)
    ? Math.min(pageCount, Math.max(1, Math.trunc(requestedPage)))
    : 1;
  const start = (page - 1) * pageSize;
  return {
    items: items.slice(start, start + pageSize),
    page,
    pageSize,
    total: items.length,
    from: items.length ? start + 1 : 0,
    to: Math.min(start + pageSize, items.length),
  };
}
