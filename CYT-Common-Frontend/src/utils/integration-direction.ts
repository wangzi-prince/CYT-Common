import type { Operation, PlatformModule, Schema } from '../api/types';
export type Direction = 'third_party' | 'internal';
export function operationDirection(
  op: Pick<Operation, 'platform' | 'integration_kind'> & { moduleKey?: string },
  modules: PlatformModule[] = [],
): Direction {
  if (op.integration_kind) return op.integration_kind;
  return op.moduleKey &&
    modules.some((m) => m.key === op.moduleKey && m.service_kind === 'internal')
    ? 'internal'
    : 'third_party';
}
export function schemaRows(
  schema: Schema,
  prefix = '',
  root = schema,
  depth = 0,
): { name: string; type: string; required: boolean; description: string; default: string }[] {
  if (depth > 8) return [];
  const resolve = (s: Schema): Schema =>
    s.$ref ? root.$defs?.[s.$ref.split('/').pop() || ''] || s : s;
  const current = resolve(schema);
  const branch = current.anyOf?.find((s) => s.type !== 'null');
  if (branch)
    return schemaRows(
      { ...resolve(branch), description: current.description || branch.description },
      prefix,
      root,
      depth + 1,
    );
  if (current.type === 'array')
    return schemaRows(current.items || {}, prefix + '[]', root, depth + 1);
  return Object.entries(current.properties || {}).flatMap(([name, input]) => {
    const field = resolve(input),
      path = prefix ? prefix + '.' + name : name;
    const types =
      field.type ||
      (field.$ref ? 'object' : field.anyOf?.map((s) => s.type || 'object').join(' | ')) ||
      (field.properties ? 'object' : '未指定');
    return [
      {
        name: path,
        type: Array.isArray(types) ? types.join(' | ') : types,
        required: !!current.required?.includes(name),
        description: input.description || field.description || '未登记字段说明',
        default: field.enum
          ? '可选值：' + JSON.stringify(field.enum)
          : field.default === undefined
            ? '—'
            : JSON.stringify(field.default),
      },
      ...schemaRows(field, path, root, depth + 1),
    ];
  });
}
export function responseEnvelope(operation?: Operation): Schema {
  const f = (type: string, description: string): Schema => ({ type, description });
  const data = operation?.response_schema || {
    description: '此接口尚未登记业务响应字段，请由提供方补齐契约。',
  };
  return {
    type: 'object',
    $defs: data.$defs,
    properties: {
      ok: f('boolean', '本次网关执行是否成功'),
      data: {
        ...data,
        description: data.description || '本操作的业务数据；SDK 成功时直接返回此部分',
      },
      meta: {
        type: 'object',
        description: '网关追踪与执行信息',
        properties: {
          trace_id: f('string', '请求追踪标识，可定位调用日志'),
          latency_ms: f('integer', '网关耗时，单位毫秒'),
          platform: f('string', '实际服务/提供方标识'),
          mock: f('boolean', 'true 为模拟结果，不代表真实业务已执行'),
          idempotent_replay: f('boolean', '是否复用已完成的幂等请求结果'),
        },
      },
    },
  };
}
