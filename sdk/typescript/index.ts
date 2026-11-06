export interface PageResult<T = Record<string, unknown>> {
  items: T[];
  errors?: Record<string, unknown>[];
  partial?: boolean;
  page: { has_more: boolean; next_cursor: string | null; count: number; pages?: number };
}
export interface ApprovalResult {
  approval_no: string;
  template_id: string;
  request_id: string;
  status: string;
  submitted: boolean;
}
export class GatewayError extends Error {
  constructor(public status: number, public info: { message?: string; code?: string; type?: string; trace_id?: string; retryable?: boolean; detail?: unknown }) {
    super(info.message || 'Gateway request failed');
    this.name = 'GatewayError';
  }
}
export class CollectionLimitError extends Error {
  constructor(public nextCursor: string) { super('Reached maxRequests; resume explicitly using nextCursor'); }
}
export class Gateway {
  constructor(private baseUrl: string, private apiKey: string, private timeoutMs = 100_000) {}

  async call<T = Record<string, unknown>>(operation: string, params: Record<string, unknown> = {}, requestId?: string): Promise<T> {
    if (!/^[a-z][a-z0-9_]*\.[a-z][a-z0-9_]*\.[a-z][a-z0-9_]*$/.test(operation)) throw new Error('Invalid operation name');
    const response = await fetch(`${this.baseUrl.replace(/\/$/, '')}/integrations/${operation.replaceAll('.', '/').replaceAll('_', '-')}`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${this.apiKey}`, 'Content-Type': 'application/json', ...(requestId ? { 'Idempotency-Key': requestId } : {}) },
      body: JSON.stringify(params),
      signal: AbortSignal.timeout(this.timeoutMs),
    });
    const result = await response.json().catch(() => ({ error: { message: 'Gateway returned a non-JSON response' } }));
    if (!response.ok || !result.ok) throw new GatewayError(response.status, result.error || {});
    return result.data as T;
  }

  readonly approvals = {
    templates: () => this.call('wecom.approval_templates.list'),
    form: (template: string) => this.call('wecom.approval_templates.get', { template }),
    bind: (alias: string, templateId: string, fieldAliases: Record<string, string> = {}) => this.call('wecom.approval_templates.bind', { alias, template_id: templateId, field_aliases: fieldAliases }),
    prepare: (template: string, applicant: string, fields: Record<string, unknown>, options: Record<string, unknown> = {}) => this.call('wecom.approvals.prepare', { ...options, template, applicant, fields }),
    submit: (template: string, applicant: string, fields: Record<string, unknown>, requestId: string, options: Record<string, unknown> = {}) => {
      if (!requestId.trim()) throw new Error('requestId must be a stable business identifier');
      return this.call<ApprovalResult>('wecom.approvals.submit', { ...options, template, applicant, fields, request_id: requestId }, requestId);
    },
    get: (approvalNo: string) => this.call('wecom.approvals.get', { approval_no: approvalNo }),
    track: (requestId: string) => this.call('wecom.approvals.track', { request_id: requestId }),
    getMany: (approvalNos: string[]) => this.call('wecom.approvals.get_many', { approval_nos: approvalNos }),
    query: (filters: Record<string, unknown> = {}) => this.call<PageResult>('wecom.approvals.collect', filters),
    events: (afterId = 0, limit = 50) => this.call('wecom.approval_events.list', { after_id: afterId, limit }),
  };

  async *collect<T = Record<string, unknown>>(operation: string, params: Record<string, unknown> = {}, maxRequests = 10): AsyncGenerator<PageResult<T>> {
    if (!Number.isInteger(maxRequests) || maxRequests < 1 || maxRequests > 100) throw new Error('maxRequests must be between 1 and 100');
    const cursorKey = operation === 'wecom.approvals.collect' ? 'cursor' : 'resume_cursor';
    let cursor = typeof params[cursorKey] === 'string' ? params[cursorKey] as string : undefined;
    const seen = new Set<string>();
    for (let i = 0; i < maxRequests; i++) {
      const result = await this.call<PageResult<T>>(operation, { ...params, ...(cursor ? { [cursorKey]: cursor } : {}) });
      yield result;
      if (!result.page.has_more) return;
      cursor = result.page.next_cursor || undefined;
      if (!cursor || seen.has(cursor)) throw new Error('Gateway returned a missing or repeated cursor');
      seen.add(cursor);
    }
    throw new CollectionLimitError(cursor!);
  }
}
