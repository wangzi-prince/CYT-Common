import { get, post, put, remove, download } from '~/http';
import type {
  Operation,
  Platform,
  SystemItem,
  CallLog,
  Page,
  Overview,
  Grant,
  Environment,
  Audit,
  PlatformModule,
  CustomApi,
  CredentialPlatform,
  CapabilityReview,
  GrantDecision,
  GrantBatchRequest,
  GrantBatchPreview,
} from './types';
const base = '/admin/api';
export const api = {
  authConfig: () => get<{ dev_login: boolean; env: string }>(`${base}/auth/config`),
  login: (username: string, password: string) =>
    post<{ token: string; actor: string; expires_at: number }>(`${base}/auth/login`, {
      username,
      password,
    }),
  devLogin: () =>
    post<{ token: string; actor: string; expires_at: number }>(`${base}/auth/dev-login`),
  session: () => get<{ actor: string }>(`${base}/auth/session`),
  meta: () =>
    get<{
      env: string;
      dev_mode: boolean;
      actor: string;
      operation_count: number;
      platforms: Platform[];
      systems: string[];
    }>(`${base}/meta`),
  overview: (hours: number) => get<Overview>(`${base}/overview`, { hours }),
  calls: (params: Record<string, unknown>) => get<Page<CallLog>>(`${base}/calls`, params),
  callDetail: (trace: string) =>
    get<{ items: CallLog[] }>(`${base}/calls/${encodeURIComponent(trace)}`),
  exportCalls: (params: Record<string, unknown>) =>
    download(`${base}/export/calls.csv`, 'cyt-common-calls.csv', params),
  registry: () => get<{ operations: Operation[] }>(`${base}/registry`),
  platforms: () => get<{ platforms: Platform[] }>(`${base}/platforms`),
  systems: () => get<{ systems: SystemItem[] }>(`${base}/systems`),
  createSystem: (body: unknown) => post(`${base}/systems`, body),
  toggleSystem: (key: string) => post(`${base}/systems/${encodeURIComponent(key)}/toggle`),
  keys: (key: string) =>
    get<{
      items: {
        id: number;
        name: string;
        prefix: string;
        revoked: boolean;
        last_used_at: string | null;
      }[];
    }>(`${base}/systems/${encodeURIComponent(key)}/apikeys`),
  createKey: (key: string, name: string) =>
    post<{ key: string }>(`${base}/systems/${encodeURIComponent(key)}/apikeys`, { name }),
  revokeKey: (id: number) => remove(`${base}/apikeys/${id}`),
  grants: () =>
    get<{ grants: Grant[]; systems: SystemItem[]; operations: Operation[] }>(`${base}/grants`),
  upsertGrant: (body: unknown) => post(`${base}/grants`, body),
  deleteGrant: (id: number) => remove(`${base}/grants/${id}`),
  effective: (system: string) =>
    get<{ operations: GrantDecision[] }>(`${base}/effective/${encodeURIComponent(system)}`),
  toggleGrant: (system: string, operation: string, enabled: boolean, confirm_sensitive = false) =>
    post(`${base}/grants/toggle`, { system, operation, enabled, confirm_sensitive }),
  previewGrantBatch: (body: GrantBatchRequest) =>
    post<GrantBatchPreview>(`${base}/grants/batch/preview`, body),
  applyGrantBatch: (
    body: GrantBatchRequest & {
      revision: string;
      confirm_sensitive: boolean;
      skip_blocked: boolean;
    },
  ) => post<{ ok: boolean; changed: number; skipped: number }>(`${base}/grants/batch`, body),
  audit: (params: Record<string, unknown>) => get<Page<Audit>>(`${base}/audit`, params),
  environments: () =>
    get<{ current: string; writable: boolean; items: Environment[] }>(`${base}/environments`),
  createEnvironment: (body: unknown) => post(`${base}/environments`, body),
  deleteEnvironment: (id: number) => remove(`${base}/environments/${id}`),
  secrets: (env: string) =>
    get<{
      db_secrets: { name: string; length: number; updated_at: string; updated_by: string }[];
      writable: boolean;
      platforms: CredentialPlatform[];
      systems?: { key: string; display_name: string }[];
    }>(`${base}/environments/${encodeURIComponent(env)}/secrets`),
  saveSecret: (env: string, values: Record<string, string>) =>
    put(`${base}/environments/${encodeURIComponent(env)}/secrets`, { values }),
  deleteSecret: (env: string, name: string) =>
    remove(`${base}/environments/${encodeURIComponent(env)}/secrets/${encodeURIComponent(name)}`),
  simulate: (body: unknown) => post<Record<string, unknown>>(`${base}/simulate`, body),
  modules: () => get<{ items: PlatformModule[] }>(`${base}/modules`),
  customApis: () => get<{ items: CustomApi[] }>(`${base}/custom-apis`),
  callbacks: () => get<{ items: Record<string, unknown>[] }>(`${base}/callbacks`),
  saveResource: (
    resource: 'modules' | 'custom-apis' | 'callbacks',
    body: Record<string, unknown>,
  ) =>
    body.id
      ? put<{ ok: boolean; id: number }>(`${base}/${resource}/${body.id}`, body)
      : post<{ ok: boolean; id: number }>(`${base}/${resource}`, body),
  deleteResource: (resource: string, id: number) => remove(`${base}/${resource}/${id}`),
  testCallback: (id: number) => post(`${base}/callbacks/${id}/test`),
  testModule: (id: number) => post(`${base}/modules/${id}/test-auth`),
  docs: () => get<{ operations: Operation[] }>(`${base}/docs`),
  capabilityReview: () => get<CapabilityReview>(`${base}/capability-review`),
  exportOpenApi: (direction = 'all') =>
    download(`${base}/openapi.json`, 'cyt-common-openapi.json', { direction }),
  exportConsumerPackage: (system: string, direction = 'all') =>
    download(
      `${base}/consumer-package/${encodeURIComponent(system)}`,
      `cyt-${system}-integration.zip`,
      { direction },
    ),
  exportConsumerOpenApi: (system: string, direction = 'all') =>
    download(
      `${base}/consumer-openapi/${encodeURIComponent(system)}`,
      `cyt-${system}-openapi.json`,
      { direction },
    ),
};
