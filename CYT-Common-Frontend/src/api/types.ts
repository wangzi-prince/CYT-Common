export interface Schema {
  type?: string | string[];
  title?: string;
  description?: string;
  properties?: Record<string, Schema>;
  required?: string[];
  default?: unknown;
  enum?: unknown[];
  items?: Schema;
  $ref?: string;
  $defs?: Record<string, Schema>;
  anyOf?: Schema[];
  examples?: unknown[];
}
export interface CapabilityReview {
  generated_at: string;
  scope: string;
  references: { title: string; url: string }[];
  items: CapabilityItem[];
  depth_summary: {
    total: number;
    all_deep: boolean;
    counts: Record<CapabilityDepth, number>;
    conclusion: string;
    basis: string;
    scope: string;
    levels: { key: CapabilityDepth; label: string; description: string }[];
    findings: { priority: string; title: string; detail: string; operations: string[] }[];
    auxiliary_interfaces: { path: string; depth: CapabilityDepth; reason: string }[];
  };
}
export type CapabilityDepth = 'deep' | 'enhanced' | 'basic' | 'supporting' | 'test_only';
export interface CapabilityItem {
  key: string;
  platform: string;
  summary: string;
  assessment: 'recommended' | 'ready' | 'enhance' | 'low_level' | 'test_only';
  current: string;
  proposal: string;
  caveat: string;
  recommended_operations: string[];
  source: string;
  depth: CapabilityDepth;
  depth_reason: string;
  remaining_gap: string;
  evidence: { path: string; line: number; anchor: string }[];
}
export interface Operation {
  key: string;
  platform: string;
  path: string;
  summary: string;
  description: string;
  quota: string;
  sensitive: boolean;
  idempotent: boolean;
  timeout_s: number;
  tags: string[];
  category: string;
  sort_order: number;
  schema: Schema;
  integration_kind?: 'third_party' | 'internal';
  response_schema?: Schema;
  deprecated?: boolean;
}
export interface PlatformModule extends Record<string, unknown> {
  service_kind?: 'third_party' | 'internal';
  id: number;
  key: string;
  name: string;
  description: string;
  base_url: string;
  auth_type: string;
  enabled: boolean;
  operation_count: number;
}
export interface CustomApi extends Record<string, unknown> {
  response_schema_json?: string | null;
  id: number;
  key: string;
  name: string;
  description: string;
  module_key: string;
  base_url: string;
  method: string;
  path: string;
  params_schema_json: string;
  category: string;
  sort_order: number;
  enabled: boolean;
  sensitive: boolean;
  timeout_s: number;
}
export interface Platform {
  platform: string;
  display_name: string;
  mock: boolean;
  configured: boolean;
  platform_quota: string | null;
  note: string;
  operation_count: number;
  sensitive_count: number;
}
export interface SystemItem {
  key: string;
  display_name: string;
  description: string;
  enabled: boolean;
  grant_count: number;
  apikey_count: number;
  created_at: string;
}
export interface CallLog {
  id: number;
  trace_id: string;
  system: string;
  operation: string;
  platform: string;
  status: string;
  http_status: number;
  latency_ms: number;
  mock: boolean;
  error_message: string | null;
  request_summary: string;
  response_summary: string;
  called_at: string;
}
export interface Page<T> {
  items: T[];
  total: number;
  page: number;
  page_size: number;
}
export interface Overview {
  overview: {
    total_calls: number;
    error_count: number;
    success_rate: number;
    p95_latency_ms: number;
    cost_usd: number;
  };
  series: { ts: string; calls: number; errors: number }[];
  by_platform: {
    platform: string;
    calls: number;
    errors: number;
    success_rate: number;
    avg_latency_ms: number;
  }[];
  recent: CallLog[];
}
export interface GrantDecision {
  operation: string;
  allowed: boolean;
  reason?: string;
  quota?: string;
  rules?: { id: number; operation_pattern: string; effect: 'allow' | 'deny' }[];
}
export interface GrantBatchRequest {
  system: string;
  operations: string[];
  effect: 'allow' | 'deny';
}
export interface GrantBatchPreview {
  system: string;
  effect: 'allow' | 'deny';
  revision: string;
  changed: number;
  blocked_count: number;
  noop_count: number;
  sensitive_count: number;
  items: {
    operation: string;
    summary: string;
    sensitive: boolean;
    before: boolean;
    after: boolean;
    noop: boolean;
    blocked: boolean;
    reason: string;
    quota: string | null;
    expires_at: string | null;
    blocking_rules: { id: number; operation_pattern: string; effect: 'allow' | 'deny' }[];
  }[];
}
export interface Grant {
  id: number;
  system: string;
  operation_pattern: string;
  effect: 'allow' | 'deny';
  quota: string | null;
  note: string;
  expires_at: string | null;
}
export interface Environment {
  id: number;
  key: string;
  label: string;
  description: string;
  is_production: boolean;
  enabled: boolean;
  secret_count: number;
}
export interface CredentialState {
  name: string;
  set: boolean;
  source: string;
  preview?: string;
  system?: string;
  is_shared?: boolean;
}
export interface CredentialVariable extends CredentialState {
  label: string;
  required: boolean;
  secret: boolean;
  hint?: string;
  account?: string;
  per_system?: boolean;
  scoped?: CredentialState[];
}
export interface CredentialPlatform {
  platform: string;
  label: string;
  note?: string;
  configured: boolean;
  vars: CredentialVariable[];
}
export interface Audit {
  id: number;
  actor: string;
  action: string;
  target: string;
  detail: string;
  created_at: string;
}
