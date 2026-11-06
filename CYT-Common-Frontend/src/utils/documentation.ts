import type { Operation } from '../api/types';

export type ExampleLanguage = 'http' | 'python' | 'typescript';

export function gatewayAddress(value: string) {
  try {
    const url = new URL(value.trim());
    if (
      !['http:', 'https:'].includes(url.protocol) ||
      url.username ||
      url.password ||
      url.search ||
      url.hash
    )
      return '';
    return url.toString().replace(/\/+$/, '');
  } catch {
    return '';
  }
}

const shellQuote = (value: string) => "'" + value.replaceAll("'", "'\\''") + "'";

export function callExample(
  operation: Operation,
  parameters: unknown,
  language: ExampleLanguage,
  address: string,
) {
  const base = gatewayAddress(address);
  if (!base) return '';
  const required = operation.tags?.includes('idempotency_required');
  const params = structuredClone(parameters);
  const record =
    params && typeof params === 'object' && !Array.isArray(params)
      ? (params as Record<string, unknown>)
      : undefined;
  if (required && record && 'request_id' in record && !record.request_id)
    record.request_id = '<稳定业务请求号>';
  const requestId = String(record?.request_id || '<稳定业务请求号>');
  const body = JSON.stringify(params, null, 2);
  if (language === 'python')
    return `import json\nimport os\nfrom cyt_gateway import Gateway\n\ngateway = Gateway(${JSON.stringify(base)}, os.environ['CYT_API_KEY'])\nparams = json.loads(${JSON.stringify(JSON.stringify(params))})\nresult = gateway.call(${JSON.stringify(operation.key)}, params${required ? `, request_id=${JSON.stringify(requestId)}` : ''})\nprint(result)  # SDK 返回 data；HTTP 响应另有 ok / meta`;
  if (language === 'typescript')
    return `import { Gateway } from './sdk/typescript/index';\n\nconst gateway = new Gateway(${JSON.stringify(base)}, process.env.CYT_API_KEY!);\nconst params = ${body};\nconst result = await gateway.call(${JSON.stringify(operation.key)}, params${required ? `, ${JSON.stringify(requestId)}` : ''});\nconsole.log(result); // SDK 返回 data；HTTP 响应另有 ok / meta`;
  return `curl -X POST ${shellQuote(base + operation.path)} \\\n  -H "Authorization: Bearer \${CYT_API_KEY}" \\\n  -H 'Content-Type: application/json'${required ? ` \\\n  -H ${shellQuote('Idempotency-Key: ' + requestId)}` : ''} \\\n  -d ${shellQuote(body)}`;
}
