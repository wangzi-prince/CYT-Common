import type { CustomApi, Operation, PlatformModule, Schema } from '../api/types';

export interface ParameterDraft {
  name: string;
  type: string;
  required: boolean;
  description: string;
  defaultText: string;
  extra?: Record<string, unknown>;
}

export function gatewayPath(key: string) {
  return `/integrations/${key.replaceAll('.', '/').replaceAll('_', '-')}`;
}

export function readParameters(json: string): ParameterDraft[] {
  const items: unknown = JSON.parse(json || '[]');
  if (!Array.isArray(items)) throw new Error('参数定义必须是数组');
  return items.map((item) => {
    if (!item || typeof item !== 'object' || Array.isArray(item))
      throw new Error('每个参数必须是对象');
    const p = item as Record<string, unknown>;
    return {
      name: String(p.name || ''),
      type:
        ({ int: 'integer', float: 'number', bool: 'boolean' } as Record<string, string>)[
          String(p.type)
        ] || String(p.type || 'string'),
      required: Boolean(p.required),
      description: String(p.description || ''),
      defaultText: p.default === undefined || p.default === null ? '' : JSON.stringify(p.default),
      extra: p,
    };
  });
}

export function writeParameters(rows: ParameterDraft[]): string {
  const names = new Set<string>();
  const result = rows.map((row, index) => {
    const name = row.name.trim();
    if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(name))
      throw new Error(`第 ${index + 1} 行参数名只能包含字母、数字和下划线，且不能以数字开头`);
    if (names.has(name)) throw new Error(`参数名「${name}」重复`);
    names.add(name);
    if (!['string', 'integer', 'number', 'boolean', 'object', 'array'].includes(row.type))
      throw new Error(`参数「${name}」的类型不支持`);
    const value: Record<string, unknown> = {
      ...row.extra,
      name,
      type: row.type,
      required: row.required,
      description: row.description,
    };
    delete value.default;
    if (row.defaultText.trim()) {
      let parsed: unknown;
      try {
        parsed = JSON.parse(row.defaultText);
      } catch {
        throw new Error(`参数「${name}」的默认值必须是 JSON 值，例如 "文本"、1、true`);
      }
      const matches =
        row.type === 'array'
          ? Array.isArray(parsed)
          : row.type === 'object'
            ? parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed)
            : row.type === 'integer'
              ? typeof parsed === 'number' && Number.isInteger(parsed)
              : row.type === 'number'
                ? typeof parsed === 'number'
                : typeof parsed === row.type;
      if (!matches) throw new Error(`参数「${name}」的默认值与 ${row.type} 类型不一致`);
      value.default = parsed;
    }
    return value;
  });
  return JSON.stringify(result);
}

export function templateParameters(...templates: string[]) {
  return [
    ...new Set(
      templates.flatMap((text) =>
        [...text.matchAll(/\{\{\s*params\.([A-Za-z_][A-Za-z0-9_]*)\s*}}/g)].map((m) => m[1]!),
      ),
    ),
  ];
}

export function splitEndpoint(value: string): { base: string; path: string } {
  const url = new URL(value);
  if (
    url.protocol !== 'https:' ||
    !url.hostname ||
    url.username ||
    url.password ||
    url.search ||
    url.hash
  )
    throw new Error('请输入 HTTPS 地址；查询参数在参数表中定义，凭据在鉴权设置中引用');
  const path = value.slice(value.indexOf('://') + 3).replace(/^[^/]+/, '') || '/';
  return { base: url.origin, path };
}

export interface CatalogOperation extends Operation {
  source: 'builtin' | 'custom';
  available: boolean;
  status: string;
  configId?: number;
  moduleKey?: string;
  upstreamMethod?: string;
  upstreamPath?: string;
}

export function buildCatalog(
  operations: Operation[],
  custom: CustomApi[],
  modules: PlatformModule[],
): CatalogOperation[] {
  const configs = new Map(custom.map((a) => [a.key, a]));
  const connections = new Map(modules.map((m) => [m.key, m]));
  const active = new Map(operations.map((o) => [o.key, o]));
  const builtins: CatalogOperation[] = operations
    .filter((o) => !configs.has(o.key))
    .map((o) => ({
      ...o,
      source: 'builtin',
      available: true,
      status: o.deprecated ? '已弃用' : '已注册',
    }));
  return [
    ...builtins,
    ...custom.map((a): CatalogOperation => {
      const moduleAvailable = !a.module_key || Boolean(connections.get(a.module_key)?.enabled);
      const available = Boolean(a.enabled) && moduleAvailable;
      const properties: Record<string, Schema> = {};
      const required: string[] = [];
      for (const p of readParameters(a.params_schema_json)) {
        properties[p.name] = {
          type: p.type,
          description: p.description,
          ...(p.extra?.default == null ? {} : { default: p.extra.default }),
        };
        if (p.required) required.push(p.name);
      }
      return {
        key: a.key,
        platform: a.key.split('.')[0]!,
        summary: a.name || a.key,
        description: a.description || '',
        quota: '60/min',
        sensitive: Boolean(a.sensitive),
        idempotent: false,
        timeout_s: a.timeout_s,
        tags: a.method === 'GET' ? ['read'] : [],
        category: a.category || '',
        sort_order: a.sort_order || 0,
        path: gatewayPath(a.key),
        integration_kind:
          connections.get(a.module_key)?.service_kind === 'internal' ? 'internal' : 'third_party',
        response_schema: {
          type: 'object',
          description: '配置型 HTTP 接口数据包装',
          properties: {
            status_code: { type: 'integer', description: '提供方 HTTP 状态码' },
            data: a.response_schema_json
              ? JSON.parse(a.response_schema_json)
              : { description: '业务响应契约尚未登记' },
            mock: { type: 'boolean', description: '是否为模拟提供方结果' },
          },
        },
        schema: { type: 'object', properties, required },
        ...active.get(a.key),
        source: 'custom',
        available,
        status: !moduleAvailable ? '平台已停用' : available ? '已启用' : '未启用',
        configId: a.id,
        moduleKey: a.module_key,
        upstreamMethod: a.method,
        upstreamPath: a.path,
      };
    }),
  ].sort(
    (a, b) =>
      a.platform.localeCompare(b.platform) ||
      a.sort_order - b.sort_order ||
      a.key.localeCompare(b.key),
  );
}
