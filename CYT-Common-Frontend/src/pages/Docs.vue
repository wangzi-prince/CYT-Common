<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Download, Search, Right, Document, Key, Lock } from '@element-plus/icons-vue';
import { api } from '~/api';
import { useRequest, useAction } from '~/composables/useRequest';
import { operationExample } from '~/utils/schema';
import { callExample, gatewayAddress, type ExampleLanguage } from '~/utils/documentation';
import {
  operationModule,
  operationModules,
  filterOperationModule,
} from '~/utils/operation-modules';
import PageHeader from '~/components/PageHeader.vue';
import PageState from '~/components/PageState.vue';
import SchemaFields from '~/components/SchemaFields.vue';
import JsonViewer from '~/components/JsonViewer.vue';
import ResponseFields from '~/components/ResponseFields.vue';
import { operationDirection, type Direction } from '~/utils/integration-direction';

const props = defineProps<{ fixedSystem?: string; embedded?: boolean }>();
const route = useRoute(),
  router = useRouter();
const callerKey = ref(String(route.query.system || ''));
const direction = ref<Direction>(route.query.direction === 'internal' ? 'internal' : 'third_party');
const system = computed(() => props.fixedSystem || callerKey.value);
const { data, loading, error, load } = useRequest(async () => {
  const [docs, callers, platforms, modules] = await Promise.all([
    api.docs(),
    api.systems(),
    api.platforms(),
    api.modules(),
  ]);
  return {
    ...docs,
    systems: callers.systems,
    platforms: platforms.platforms,
    modules: modules.items,
  };
});
const access = useRequest(async () => {
  const key = system.value;
  return { ...(await api.effective(key)), system: key };
});
const caller = computed(() => data.value?.systems.find((s) => s.key === system.value));
const allowed = computed(
  () =>
    new Map(
      (access.data.value?.system === system.value ? access.data.value.operations : [])
        .filter((o) => o.allowed)
        .map((o) => [o.operation, o]),
    ),
);
const operations = computed(() =>
  (data.value?.operations || [])
    .filter((o) => operationDirection(o, data.value?.modules) === direction.value)
    .filter((o) => !system.value || allowed.value.has(o.key))
    .map((o) => ({
      ...o,
      quota: system.value ? allowed.value.get(o.key)?.quota || o.quota : o.quota,
    })),
);
const docsReady = computed(
  () =>
    !loading.value &&
    !error.value &&
    !!data.value &&
    (!system.value ||
      (!access.loading.value && !access.error.value && access.data.value?.system === system.value)),
);
const supportsContracts = computed(
  () =>
    !!data.value?.operations.length &&
    data.value.operations.every((o) => o.integration_kind && o.response_schema),
);
const exportReady = computed(
  () => docsReady.value && supportsContracts.value && (!system.value || caller.value?.enabled),
);
const platformNames = computed(() =>
  Object.fromEntries((data.value?.platforms || []).map((p) => [p.platform, p.display_name])),
);
const { busy, run } = useAction();
const view = ref(route.query.operation ? 'reference' : 'quickstart');
const language = ref<ExampleLanguage>('http');
const address = ref(window.location.origin);
const normalizedAddress = computed(() => gatewayAddress(address.value));
const q = ref('');
const platform = ref(String(route.query.platform || ''));
const category = ref(String(route.query.category || ''));
const platformOperations = computed(() =>
  operations.value.filter((o) => !platform.value || o.platform === platform.value),
);
const modules = computed(() => operationModules(platformOperations.value));
const filtered = computed(() =>
  filterOperationModule(platformOperations.value, category.value).filter((o) =>
    `${o.key} ${o.summary} ${o.category}`.toLowerCase().includes(q.value.trim().toLowerCase()),
  ),
);
const selectedKey = ref(String(route.query.operation || ''));
const selected = computed(() => operations.value.find((o) => o.key === selectedKey.value));
const sample = computed(() =>
  selected.value
    ? callExample(selected.value, operationExample(selected.value), language.value, address.value)
    : '',
);
const responseExample = {
  ok: true,
  data: { 业务数据: '以接口实际返回为准' },
  meta: { trace_id: '请求追踪标识', mock: false },
};
const problems = [
  ['401', '检查是否使用本系统 API Key，以及密钥是否已吊销。'],
  ['403', '检查调用方是否启用、接口是否获授权，以及是否存在拒绝规则。'],
  ['422', '按参数说明检查必填字段、数据类型和业务编号。'],
  ['409', '核对原业务请求的结果及幂等键，勿更换编号盲目重提。'],
  ['429', '达到调用配额，按返回提示延后重试。'],
  ['502 / 503', '将 error.trace_id 交给管理员，核对上游连接、凭据和调用日志。'],
];
watch([filtered, docsReady], () => {
  if (docsReady.value && !filtered.value.some((o) => o.key === selectedKey.value))
    selectedKey.value =
      filtered.value.find((o) => o.tags?.includes('read'))?.key || filtered.value[0]?.key || '';
});
watch([operations, docsReady], () => {
  if (!docsReady.value) return;
  if (platform.value && !operations.value.some((o) => o.platform === platform.value))
    platform.value = '';
  if (
    category.value &&
    !platformOperations.value.some((o) => operationModule(o) === category.value)
  )
    category.value = '';
});
watch(platform, () => {
  if (category.value && !modules.value.some((m) => m.name === category.value)) category.value = '';
});
watch(system, () => {
  q.value = '';
  platform.value = '';
  category.value = '';
  if (data.value && system.value) void access.load();
});
watch(callerKey, () => {
  if (!props.embedded && route.path === '/docs')
    void router.replace({ query: { ...route.query, system: callerKey.value || undefined } });
});
watch(direction, () => {
  q.value = '';
  platform.value = '';
  category.value = '';
  if (route.path === '/docs')
    void router.replace({ query: { ...route.query, direction: direction.value } });
});
watch(
  () => route.query.direction,
  (value) => {
    if (route.path === '/docs') direction.value = value === 'internal' ? 'internal' : 'third_party';
  },
);
watch(
  () => route.query.system,
  (key) => {
    if (!props.fixedSystem && route.path === '/docs') callerKey.value = String(key || '');
  },
);
watch(
  () => route.query.operation,
  (key) => {
    if (key) {
      selectedKey.value = String(key);
      view.value = 'reference';
    }
  },
);
async function reload() {
  await load();
  if (!route.query.direction && route.query.operation)
    direction.value =
      data.value?.operations.find((o) => o.key === route.query.operation)?.integration_kind ||
      'third_party';
  if (system.value) await access.load();
}
onMounted(reload);
function manage(tab: string) {
  void router.push(
    system.value
      ? {
          path: `/systems/${encodeURIComponent(system.value)}`,
          query: { tab, direction: direction.value },
        }
      : '/systems',
  );
}
function exportDocs() {
  if (exportReady.value)
    return run(() =>
      system.value
        ? api.exportConsumerOpenApi(system.value, direction.value)
        : api.exportOpenApi(direction.value),
    );
}
function exportKit() {
  if (exportReady.value && system.value)
    return run(() => api.exportConsumerPackage(system.value, direction.value));
}
function debug() {
  if (selected.value)
    void router.push({
      path: '/playground',
      query: {
        operation: selected.value.key,
        direction: direction.value,
        ...(system.value ? { system: system.value } : {}),
      },
    });
}
</script>

<template>
  <div class="documentation-page">
    <PageHeader
      v-if="!embedded"
      title="接入文档"
      description="查询接口参数、复制调用示例、下载接入资料"
    >
      <el-button
        :icon="Document"
        @click="router.push(direction === 'internal' ? '/exposed' : '/platforms')"
        >{{ direction === 'internal' ? '对外能力' : '服务接入' }}</el-button
      >
      <el-button :icon="Download" :disabled="!exportReady" :loading="busy" @click="exportDocs">{{
        system ? '导出专属 OpenAPI' : '导出 OpenAPI'
      }}</el-button>
      <el-button
        type="primary"
        :icon="Download"
        :disabled="!system || !exportReady"
        :loading="busy"
        @click="exportKit"
        >下载接入包</el-button
      >
    </PageHeader>
    <el-alert
      v-if="data && !supportsContracts"
      title="当前运行的后端尚未加载方向与响应契约，请更新并重启服务后再按方向导出接入资料。"
      type="info"
      :closable="false"
    />
    <section class="panel docs-direction">
      <el-radio-group v-model="direction" aria-label="文档调用方向"
        ><el-radio-button value="third_party">第三方接入文档</el-radio-button
        ><el-radio-button value="internal">对外能力调用文档</el-radio-button></el-radio-group
      >
      <el-button
        link
        type="primary"
        @click="router.push(direction === 'internal' ? '/exposed' : '/platforms')"
        >{{ direction === 'internal' ? '管理接口' : '管理服务' }}</el-button
      >
    </section>
    <section class="panel docs-context">
      <div class="docs-context-main">
        <div>
          <strong>{{ embedded ? '当前调用方的接入资料' : '调用方接入资料' }}</strong>
          <p>选择调用方，查看已授权接口并下载专属接入包。</p>
        </div>
        <el-select
          v-if="!fixedSystem"
          v-model="callerKey"
          filterable
          clearable
          placeholder="全部接口 · 选择调用方"
          aria-label="文档调用方"
        >
          <el-option
            v-for="s in data?.systems || []"
            :key="s.key"
            :value="s.key"
            :label="`${s.display_name} · ${s.key}${s.enabled ? '' : ' · 已停用'}`"
          />
        </el-select>
        <el-tag v-else effect="plain">{{ caller?.display_name || system }}</el-tag>
        <span class="docs-scope">{{
          system ? `${operations.length} 项已授权接口` : `${operations.length} 项接口参考`
        }}</span>
      </div>
      <div class="docs-context-actions">
        <span>{{
          system
            ? '解压接入包后打开 START_HERE.html 查看指南；API Key 单独交付。'
            : '接入包包含离线指南、OpenAPI 和 Python / TypeScript SDK。'
        }}</span>
        <el-button
          v-if="embedded"
          type="primary"
          :icon="Download"
          :disabled="!exportReady"
          :loading="busy"
          @click="exportKit"
          >下载接入包</el-button
        >
        <el-button v-if="embedded" :disabled="!exportReady" :loading="busy" @click="exportDocs"
          >仅导出 OpenAPI</el-button
        >
        <el-button link type="primary" :icon="Key" @click="manage('keys')">{{
          system ? '管理调用密钥' : '注册调用方 / 领取密钥'
        }}</el-button>
        <el-button v-if="system" link type="primary" :icon="Lock" @click="manage('access')"
          >配置能力授权</el-button
        >
      </div>
      <el-alert
        v-if="caller && !caller.enabled"
        title="此调用方已停用，启用后才能调用接口或导出接入包。"
        type="warning"
        :closable="false"
      />
    </section>
    <el-tabs v-model="view" class="documentation-tabs">
      <el-tab-pane label="快速接入" name="quickstart">
        <section class="panel quickstart-intro">
          <span class="docs-eyebrow">QUICK START</span>
          <h2>让第一个请求跑通</h2>
          <p>领取调用方身份和授权，配置网关地址，再复制下面的示例到业务后端执行。</p>
          <div class="docs-step-grid">
            <article>
              <b>1</b>
              <div>
                <h3>拿到身份与权限</h3>
                <p>
                  {{
                    caller
                      ? `${caller.display_name}：${caller.apikey_count} 把有效密钥，${operations.length} 项授权接口。`
                      : '请管理员注册调用方、生成独立 API Key，并授权所需接口。'
                  }}
                </p>
              </div>
            </article>
            <article>
              <b>2</b>
              <div>
                <h3>准备网关地址和 SDK</h3>
                <p>
                  使用接入方可访问的网关地址；从接入包取出 SDK，密钥保存在后端环境变量 CYT_API_KEY。
                </p>
              </div>
            </article>
            <article>
              <b>3</b>
              <div>
                <h3>验证一个真实请求</h3>
                <p>
                  先选只读接口，填写实际业务参数。检查 ok、meta.mock 和 data 中的 partial / errors。
                </p>
              </div>
            </article>
          </div>
        </section>
        <section class="panel quickstart-request">
          <div class="panel-head">
            <div>
              <h2>复制你的第一个调用示例</h2>
              <p>示例不会自动执行；请替换业务参数后在接入方后端运行。</p>
            </div>
            <el-button link type="primary" @click="view = 'reference'"
              >查看完整参数 <el-icon><Right /></el-icon
            ></el-button>
          </div>
          <div class="panel-body">
            <div class="docs-request-options">
              <label
                ><span>网关基础地址</span
                ><el-input
                  v-model="address"
                  placeholder="https://gateway.example.com"
                  aria-label="网关基础地址"
              /></label>
              <label
                ><span>选择接口</span
                ><el-select
                  v-model="selectedKey"
                  filterable
                  placeholder="搜索接口名称或标识"
                  aria-label="首个调用接口"
                  ><el-option
                    v-for="o in filtered"
                    :key="o.key"
                    :value="o.key"
                    :label="`${o.summary} · ${o.key}`" /></el-select
              ></label>
            </div>
            <p class="form-hint">
              当前地址来自本次访问；交付给其他系统时，填写其实际可达的网关地址。HTTP 示例适用于
              Bash；Python SDK 可直接放入项目，TypeScript SDK 从接入包导入。
            </p>
            <el-alert
              v-if="!normalizedAddress"
              title="填写完整 HTTP / HTTPS 地址，不要在地址中包含密钥、账号密码、查询参数或页面锚点。"
              type="error"
              :closable="false"
            />
            <el-alert
              v-if="selected?.sensitive || selected?.tags.includes('write')"
              title="当前选择的是写操作，请先核对业务参数；需要幂等键的操作必须使用稳定业务请求号。"
              type="warning"
              :closable="false"
            />
            <PageState
              :loading="loading || access.loading.value"
              :error="error || (!!system && access.error.value)"
              @retry="reload"
            >
              <template v-if="selected"
                ><el-tabs v-model="language"
                  ><el-tab-pane label="HTTP / cURL" name="http" /><el-tab-pane
                    label="Python"
                    name="python" /><el-tab-pane label="TypeScript" name="typescript" /></el-tabs
                ><JsonViewer v-if="sample" :value="sample" />
                <div class="docs-example-actions">
                  <el-button @click="view = 'reference'">查看参数与接口说明</el-button
                  ><el-button @click="debug">在后台调试此接口</el-button>
                </div></template
              >
              <el-empty
                v-else
                :description="
                  direction === 'internal'
                    ? '当前没有获授权的自有接口，请先登记对外能力并分配给调用方。'
                    : '当前没有可展示的第三方接口，请先为调用方配置授权。'
                "
                ><el-button type="primary" @click="manage('access')"
                  >选择调用方并配置授权</el-button
                ></el-empty
              >
            </PageState>
          </div>
        </section>
        <div class="docs-result-grid">
          <section class="panel">
            <div class="panel-head"><h2>如何确认调用成功</h2></div>
            <div class="panel-body">
              <p class="form-hint">
                HTTP 响应结构示意，业务数据以各接口实际返回为准。SDK 成功时直接返回
                data；异常包含状态、trace_id 和 retryable。
              </p>
              <JsonViewer :value="responseExample" />
              <p class="form-hint">
                meta.mock=true 表示模拟结果；data.partial=true 或 errors
                非空时，继续核对部分失败。分页接口按 page.has_more / next_cursor 判断是否完成。
              </p>
            </div>
          </section>
          <section class="panel">
            <div class="panel-head"><h2>遇到错误先检查这里</h2></div>
            <div class="panel-body docs-problems">
              <div v-for="[code, description] in problems" :key="code">
                <code>{{ code }}</code
                ><span>{{ description }}</span>
              </div>
              <p class="form-hint">写请求超时先查询原结果，重试沿用原幂等键，避免重复提交。</p>
            </div>
          </section>
        </div>
      </el-tab-pane>
      <el-tab-pane label="接口参考" name="reference">
        <PageState
          :loading="loading || access.loading.value"
          :error="error || (!!system && access.error.value)"
          @retry="reload"
        >
          <div class="docs-grid">
            <aside class="panel docs-sidebar">
              <div class="docs-sidebar-filters">
                <el-input
                  v-model="q"
                  :prefix-icon="Search"
                  placeholder="搜索名称、标识或模块"
                  aria-label="搜索文档接口"
                  clearable
                /><el-select
                  v-model="platform"
                  filterable
                  clearable
                  placeholder="全部平台"
                  aria-label="文档平台"
                  ><el-option
                    v-for="p in [...new Set(operations.map((o) => o.platform))]"
                    :key="p"
                    :value="p"
                    :label="platformNames[p] || p" /></el-select
                ><el-select
                  v-model="category"
                  filterable
                  clearable
                  placeholder="全部业务模块"
                  aria-label="文档业务模块"
                  ><el-option
                    v-for="m in modules"
                    :key="m.name"
                    :value="m.name"
                    :label="`${m.name} · ${m.total}`" /></el-select
                ><span class="subtext">{{ filtered.length }} 项接口</span>
              </div>
              <button
                v-for="o in filtered"
                :key="o.key"
                type="button"
                :class="{ selected: o.key === selectedKey }"
                :aria-pressed="o.key === selectedKey"
                @click="selectedKey = o.key"
              >
                <strong>{{ o.summary }}</strong
                ><span class="mono">{{ o.key }}</span>
              </button>
              <p v-if="!filtered.length" class="docs-no-results">
                没有匹配的接口，请调整筛选或配置授权。
              </p>
            </aside>
            <section v-if="selected" class="panel">
              <header class="panel-head">
                <div>
                  <h2>{{ selected.summary }}</h2>
                  <p class="mono">POST {{ selected.path }}</p>
                </div>
                <el-button link type="primary" :icon="Right" @click="debug">前往调试</el-button>
              </header>
              <div class="panel-body">
                <p class="form-hint">{{ selected.description }}</p>
                <div class="inline-actions">
                  <el-tag effect="plain">{{ operationModule(selected) }}</el-tag
                  ><el-tag effect="plain">配额 {{ selected.quota }}</el-tag
                  ><el-tag v-if="selected.sensitive" type="warning" effect="plain"
                    >敏感操作 · 精确授权</el-tag
                  ><el-tag
                    v-if="selected.tags.includes('idempotency_required')"
                    type="warning"
                    effect="plain"
                    >必填稳定幂等键</el-tag
                  ><el-tag v-else-if="selected.idempotent" type="info" effect="plain"
                    >支持幂等键</el-tag
                  >
                </div>
                <h3 class="docs-heading">请求参数</h3>
                <SchemaFields :schema="selected.schema" />
                <h3 class="docs-heading">响应字段含义</h3>
                <ResponseFields :operation="selected" />
                <h3 class="docs-heading">调用示例</h3>
                <p class="form-hint">示例使用“快速接入”页签中的网关基础地址。</p>
                <el-radio-group v-model="language"
                  ><el-radio-button value="http">HTTP</el-radio-button
                  ><el-radio-button value="python">Python</el-radio-button
                  ><el-radio-button value="typescript">TypeScript</el-radio-button></el-radio-group
                ><JsonViewer v-if="sample" :value="sample" /><el-alert
                  v-else
                  title="请在快速接入中填写有效的网关基础地址"
                  type="info"
                  :closable="false"
                /><el-collapse
                  ><el-collapse-item title="完整参数 Schema" name="schema"
                    ><JsonViewer :value="selected.schema" /></el-collapse-item
                ></el-collapse>
              </div>
            </section>
            <el-empty v-else description="当前范围暂无接口" />
          </div>
        </PageState>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.docs-context {
  padding: 20px;
}
.docs-direction {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
  padding: 16px 20px;
}
.docs-context-main {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
}
.docs-context-main > div:first-child {
  flex: 1;
  min-width: 240px;
}
.docs-context-main strong {
  font-size: 14px;
}
.docs-context-main p {
  margin: 7px 0 0;
  color: var(--app-text-secondary);
  font-size: 12px;
  line-height: 1.7;
}
.docs-context-main > .el-select {
  width: 300px;
  max-width: 100%;
}
.docs-scope {
  font-size: 12px;
  color: var(--app-text-secondary);
}
.docs-context-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px 14px;
  align-items: center;
  margin-top: 18px;
  padding-top: 15px;
  border-top: 1px solid var(--app-border);
}
.docs-context-actions > span {
  width: 100%;
  font-size: 12px;
  color: var(--app-text-secondary);
  line-height: 1.8;
}
.docs-context-actions > .el-button {
  margin: 0;
}
.quickstart-intro {
  padding: 24px;
}
.docs-eyebrow {
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.12em;
  color: var(--el-color-primary);
}
.quickstart-intro h2 {
  font-size: 24px;
  margin: 12px 0;
}
.quickstart-intro > p {
  color: var(--app-text-secondary);
  font-size: 13px;
  line-height: 1.8;
}
.docs-step-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 20px;
  margin-top: 24px;
}
.docs-step-grid article {
  display: flex;
  align-items: flex-start;
  gap: 12px;
}
.docs-step-grid b {
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  flex-shrink: 0;
  border-radius: 8px;
  color: var(--el-color-primary);
  background: var(--app-primary-soft);
}
.docs-step-grid h3 {
  font-size: 14px;
  margin: 3px 0 10px;
}
.docs-step-grid p {
  margin: 0;
  font-size: 12px;
  color: var(--app-text-secondary);
  line-height: 1.9;
}
.docs-request-options {
  display: grid;
  grid-template-columns: 1fr 1.4fr;
  gap: 16px;
}
.docs-request-options label {
  display: grid;
  gap: 9px;
  font-size: 12px;
  min-width: 0;
}
.docs-example-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 16px;
}
.docs-example-actions .el-button {
  margin: 0;
}
.docs-result-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 20px;
}
.docs-problems > div {
  display: flex;
  align-items: baseline;
  gap: 16px;
  margin-bottom: 16px;
  font-size: 12px;
  line-height: 1.8;
}
.docs-problems code {
  color: var(--el-color-primary);
  min-width: 62px;
  flex-shrink: 0;
}
.docs-problems span {
  color: var(--app-text-secondary);
}
.docs-sidebar-filters {
  display: grid;
  gap: 10px;
  padding: 16px;
}
.docs-no-results {
  padding: 0 16px 16px;
  font-size: 12px;
  color: var(--app-text-secondary);
  line-height: 1.8;
}
.documentation-tabs {
  margin-top: 20px;
}
@media (max-width: 1000px) {
  .docs-step-grid,
  .docs-result-grid {
    grid-template-columns: 1fr;
  }
}
@media (max-width: 600px) {
  .docs-context,
  .quickstart-intro {
    padding: 16px;
  }
  .docs-request-options {
    grid-template-columns: 1fr;
  }
  .docs-context-main > div:first-child {
    min-width: 0;
    flex-basis: 100%;
  }
  .docs-context-main > .el-select {
    width: 100%;
  }
}
</style>
