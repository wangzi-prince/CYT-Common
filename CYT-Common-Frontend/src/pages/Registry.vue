<script setup lang="ts">
import { computed, defineAsyncComponent, onMounted, onUnmounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Search, Refresh, Plus, DocumentCopy } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { api } from '~/api';
import { useRequest } from '~/composables/useRequest';
import { buildCatalog, type CatalogOperation } from '~/utils/authoring';
import { operationExample } from '~/utils/schema';
import { filterOperationModule, operationModule } from '~/utils/operation-modules';
import { operationDirection } from '~/utils/integration-direction';
import OperationModuleBrowser from '~/components/OperationModuleBrowser.vue';
import PageHeader from '~/components/PageHeader.vue';
import PageState from '~/components/PageState.vue';
import SchemaFields from '~/components/SchemaFields.vue';
import ResponseFields from '~/components/ResponseFields.vue';
import JsonViewer from '~/components/JsonViewer.vue';
const WecomApprovalWorkbench = defineAsyncComponent(
  () => import('~/components/WecomApprovalWorkbench.vue'),
);
const route = useRoute();
const router = useRouter();
const q = ref(String(route.query.q || ''));
const platform = ref(String(route.query.platform || ''));
const category = ref(String(route.query.category || ''));
const source = ref(String(route.query.source || 'all'));
const state = ref(String(route.query.state || 'all'));
const domain = ref(String(route.query.domain || 'all'));
const approvalDialog = ref(false);
const selectedKey = ref(String(route.query.operation || ''));
const page = ref(1);
const pageSize = ref(15);
const compactViewport = window.matchMedia('(max-width: 1320px)');
const compact = ref(compactViewport.matches);
function resizeCatalog(event: MediaQueryListEvent) {
  compact.value = event.matches;
}
const { data, loading, error, load } = useRequest(async () => {
  const [registry, custom, modules, platforms] = await Promise.all([
    api.registry(),
    api.customApis(),
    api.modules(),
    api.platforms(),
  ]);
  return {
    operations: buildCatalog(registry.operations, custom.items, modules.items),
    modules: modules.items,
    platforms: platforms.platforms,
  };
});
const operations = computed(() =>
  (data.value?.operations || []).filter(
    (o) =>
      operationDirection(o, data.value?.modules) ===
      (internalContext.value ? 'internal' : 'third_party'),
  ),
);
const internalContext = computed(
  () =>
    route.query.direction === 'internal' ||
    data.value?.modules.some((m) => m.key === platform.value && m.service_kind === 'internal'),
);
const platformNames = computed(
  () =>
    new Map([
      ...(data.value?.platforms || []).map((p) => [p.platform, p.display_name] as const),
      ...(data.value?.modules || []).map((m) => [m.key, m.name || m.key] as const),
    ]),
);
const platforms = computed(() => [...new Set(operations.value.map((o) => o.platform))]);
const platformOperations = computed(() =>
  operations.value.filter((o) => !platform.value || o.platform === platform.value),
);
const customCount = computed(
  () => platformOperations.value.filter((o) => o.source === 'custom').length,
);
const approvalCount = computed(
  () => platformOperations.value.filter((o) => o.tags?.includes('approval')).length,
);
const moduleOperations = computed(() =>
  platformOperations.value.filter(
    (o) =>
      platform.value !== 'wecom' ||
      domain.value === 'all' ||
      (domain.value === 'approval' ? o.tags?.includes('approval') : !o.tags?.includes('approval')),
  ),
);
const matching = computed(() =>
  moduleOperations.value.filter(
    (o) =>
      (source.value === 'all' || o.source === source.value) &&
      (state.value === 'all' || o.available === (state.value === 'available')) &&
      `${o.key} ${o.summary} ${o.description} ${o.category} ${platformNames.value.get(o.platform) || ''}`
        .toLowerCase()
        .includes(q.value.trim().toLowerCase()),
  ),
);
const filtered = computed(() => filterOperationModule(matching.value, category.value));
const visible = computed(() =>
  filtered.value.slice((page.value - 1) * pageSize.value, page.value * pageSize.value),
);
watch(
  () => filtered.value.length,
  (total) => {
    page.value = Math.min(page.value, Math.max(1, Math.ceil(total / pageSize.value)));
  },
);
const selected = computed(() => operations.value.find((o) => o.key === selectedKey.value));
const hasFilters = computed(
  () =>
    !!(
      q.value ||
      platform.value ||
      category.value ||
      source.value !== 'all' ||
      state.value !== 'all' ||
      domain.value !== 'all'
    ),
);
watch([q, platform, category, source, state, domain, pageSize], () => {
  if (route.path !== '/registry') return;
  page.value = 1;
  void router.replace({
    query: {
      ...route.query,
      q: q.value || undefined,
      platform: platform.value || undefined,
      category: category.value || undefined,
      source: source.value === 'all' ? undefined : source.value,
      state: state.value === 'all' ? undefined : state.value,
      domain: platform.value === 'wecom' && domain.value !== 'all' ? domain.value : undefined,
    },
  });
});
watch(
  () => route.query,
  (query) => {
    if (route.path !== '/registry') return;
    q.value = String(query.q || '');
    platform.value = String(query.platform || '');
    category.value = String(query.category || '');
    source.value = String(query.source || 'all');
    state.value = String(query.state || 'all');
    domain.value = String(query.domain || 'all');
    selectedKey.value = String(query.operation || '');
  },
);
watch([moduleOperations, loading], () => {
  if (route.path !== '/registry') return;
  if (
    !loading.value &&
    data.value &&
    category.value &&
    !moduleOperations.value.some((o) => operationModule(o) === category.value)
  )
    category.value = '';
});
onMounted(() => {
  void load();
  compactViewport.addEventListener('change', resizeCatalog);
});
onUnmounted(() => compactViewport.removeEventListener('change', resizeCatalog));
function resetFilters() {
  q.value = '';
  platform.value = '';
  category.value = '';
  source.value = 'all';
  state.value = 'all';
  domain.value = 'all';
}
function create(tab: 'apis' | 'modules') {
  void router.push({
    path: '/modules',
    query: {
      tab,
      action: 'create',
      platform: tab === 'apis' ? platform.value || undefined : undefined,
      category: tab === 'apis' ? category.value || undefined : undefined,
      kind: internalContext.value ? 'internal' : undefined,
    },
  });
}
function detail(operation: CatalogOperation) {
  selectedKey.value = operation.key;
}
function closeDetail() {
  selectedKey.value = '';
  if (route.query.operation)
    void router.replace({ query: { ...route.query, operation: undefined } });
}
function edit(operation: CatalogOperation) {
  void router.push({ path: '/modules', query: { tab: 'apis', edit: operation.configId } });
}
async function copyPath(path: string) {
  try {
    await navigator.clipboard.writeText(path);
    ElMessage.success('网关调用路径已复制');
  } catch {
    ElMessage.info('浏览器未允许复制，请从详情中选择路径复制');
  }
}
</script>
<template>
  <div>
    <PageHeader
      :title="platform ? `${platformNames.get(platform) || platform} · 服务接口` : '服务接口目录'"
      :description="
        platform === 'wecom'
          ? '通讯录、消息、日程与审批统一接入；业务系统通过同一网关调用。'
          : '按平台发现接口，从这里新增、查看参数、调试，再授权给调用系统。'
      "
    >
      <el-button @click="router.push(internalContext ? '/exposed' : '/platforms')">{{
        internalContext ? '返回对外能力' : '返回服务接入'
      }}</el-button>
      <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      <el-button
        @click="router.push({ path: '/capabilities', query: { platform: platform || undefined } })"
        >封装说明</el-button
      >
      <el-button
        @click="
          router.push({
            path: '/docs',
            query: {
              platform: platform || undefined,
              category: category || undefined,
              direction: internalContext ? 'internal' : 'third_party',
            },
          })
        "
        >{{ internalContext ? '自有能力调用文档' : '第三方接入文档' }}</el-button
      >
      <el-button type="primary" :icon="Plus" @click="create('apis')">新增接口</el-button>
    </PageHeader>
    <div class="workflow-strip">
      <span><b>1</b> 已选择服务</span><i>→</i><span><b>2</b> 配置接口与参数</span><i>→</i
      ><span><b>3</b> 验证能力</span><i>→</i><span><b>4</b> 分配给调用方</span>
      <el-button link type="primary" @click="router.push('/systems')">配置调用方接入</el-button>
    </div>
    <section v-if="platform === 'wecom'" class="panel wecom-capabilities">
      <div class="panel-body">
        <div class="wecom-capability-heading">
          <div>
            <h2>企微审批 · 业务封装</h2>
            <p class="form-hint">
              调用方只传模板、申请人与业务字段；网关自动解析控件、校验、生成摘要并提单。支持模板管理、审批查询、结果事件与假期能力。
            </p>
          </div>
          <el-button type="primary" @click="approvalDialog = true">按模板调试</el-button>
        </div>
        <el-radio-group v-model="domain" aria-label="企业微信能力范围"
          ><el-radio-button value="all">全部接口 {{ platformOperations.length }}</el-radio-button
          ><el-radio-button value="approval">审批业务接口 {{ approvalCount }}</el-radio-button
          ><el-radio-button value="core"
            >其他企微能力 {{ platformOperations.length - approvalCount }}</el-radio-button
          ></el-radio-group
        >
      </div>
    </section>
    <section class="panel">
      <div v-if="platform !== 'wecom' || customCount > 0" class="panel-toolbar catalog-source-tabs">
        <el-radio-group v-model="source" aria-label="接口来源">
          <el-radio-button value="all">全部 {{ platformOperations.length }}</el-radio-button>
          <el-radio-button value="builtin"
            >内置适配器 {{ platformOperations.length - customCount }}</el-radio-button
          >
          <el-radio-button value="custom">自定义 HTTP {{ customCount }}</el-radio-button>
        </el-radio-group>
        <span class="muted">内置接口由适配器维护；自定义接口可直接配置和编辑。</span>
      </div>
      <div class="panel-toolbar catalog-filters">
        <el-input
          v-model="q"
          :prefix-icon="Search"
          clearable
          placeholder="搜索名称、标识或用途"
          aria-label="搜索接口"
        />
        <el-select
          v-model="platform"
          clearable
          filterable
          placeholder="全部平台"
          aria-label="筛选平台"
          ><el-option v-for="p in platforms" :key="p" :label="platformNames.get(p) || p" :value="p"
        /></el-select>
        <el-select v-model="state" aria-label="筛选启用状态"
          ><el-option value="all" label="全部状态" /><el-option
            value="available"
            label="已注册 / 已启用" /><el-option value="inactive" label="未启用 / 平台停用"
        /></el-select>
        <el-button v-if="hasFilters" link @click="resetFilters">清除筛选</el-button>
      </div>
      <PageState :loading="loading" :error="error" @retry="load">
        <OperationModuleBrowser
          v-model="category"
          :operations="moduleOperations"
          :matching="matching"
        >
          <el-empty
            v-if="!filtered.length"
            :description="
              hasFilters ? '没有匹配的接口，试试清除筛选或新增接口' : '还没有接口，从接入平台开始'
            "
          >
            <el-button v-if="hasFilters" @click="resetFilters">清除筛选</el-button
            ><el-button type="primary" @click="create('apis')">新增接口</el-button>
          </el-empty>
          <el-table v-else :data="visible" row-key="key" class="catalog-table" @row-click="detail">
            <el-table-column label="接口" min-width="270"
              ><template #default="{ row }"
                ><strong>{{ row.summary || row.key }}</strong>
                <div class="subtext mono">{{ row.key }}</div>
                <div class="catalog-inline-meta">
                  <el-tag size="small" type="info" effect="plain">{{
                    row.source === 'custom' ? '自定义 HTTP' : '内置适配器'
                  }}</el-tag
                  ><el-tag
                    v-if="row.tags?.includes('business')"
                    size="small"
                    type="primary"
                    effect="plain"
                    >业务封装</el-tag
                  ><span>{{ row.status }}</span
                  ><span>{{ row.quota }}</span
                  ><span v-if="row.sensitive" class="sensitive-label">敏感操作</span>
                </div></template
              ></el-table-column
            >
            <el-table-column label="平台 / 模块" min-width="145"
              ><template #default="{ row }"
                >{{ platformNames.get(row.platform) || row.platform }}
                <div class="subtext">{{ operationModule(row) }}</div></template
              ></el-table-column
            >
            <el-table-column
              v-if="!compact"
              label="来源 / 状态"
              width="145"
              class-name="catalog-source-column"
              ><template #default="{ row }"
                ><el-tag
                  size="small"
                  :type="
                    row.source === 'custom' || row.tags?.includes('business') ? 'primary' : 'info'
                  "
                  effect="plain"
                  >{{
                    row.source === 'custom'
                      ? '自定义 HTTP'
                      : row.tags?.includes('business')
                        ? '业务封装'
                        : '内置适配器'
                  }}</el-tag
                >
                <div class="subtext">{{ row.status }}</div></template
              ></el-table-column
            >
            <el-table-column
              v-if="!compact"
              label="调用策略"
              width="145"
              class-name="catalog-policy-column"
              ><template #default="{ row }"
                ><span :class="{ 'sensitive-label': row.sensitive }">{{
                  row.sensitive ? '敏感 · 精确授权' : '标准操作'
                }}</span>
                <div class="subtext">{{ row.quota }}</div></template
              ></el-table-column
            >
            <el-table-column label="操作" width="150" fixed="right"
              ><template #default="{ row }"
                ><el-button link type="primary" @click.stop="detail(row)">详情</el-button
                ><el-button
                  v-if="row.available"
                  link
                  type="primary"
                  @click.stop="router.push({ path: '/playground', query: { operation: row.key } })"
                  >调试</el-button
                ><el-button
                  v-if="row.source === 'custom'"
                  link
                  type="primary"
                  @click.stop="edit(row)"
                  >编辑</el-button
                ></template
              ></el-table-column
            >
          </el-table>
          <div class="catalog-pagination">
            <span class="muted">共 {{ filtered.length }} 个接口</span
            ><el-pagination
              v-model:current-page="page"
              v-model:page-size="pageSize"
              :page-sizes="[15, 30, 60]"
              :total="filtered.length"
              layout="sizes, prev, pager, next"
            />
          </div>
        </OperationModuleBrowser>
      </PageState>
    </section>
    <el-drawer
      :model-value="!!selected"
      title="接口详情"
      size="min(760px, 96vw)"
      @close="closeDetail"
    >
      <template v-if="selected">
        <div class="detail-heading">
          <h2>{{ selected.summary || selected.key }}</h2>
          <p class="mono muted">{{ selected.key }}</p>
          <div class="inline-actions">
            <el-tag effect="plain">{{
              selected.source === 'custom' ? '自定义 HTTP' : '内置适配器'
            }}</el-tag
            ><el-tag :type="selected.available ? 'success' : 'info'" effect="plain">{{
              selected.status
            }}</el-tag
            ><el-tag v-if="selected.sensitive" type="warning" effect="plain"
              >敏感操作需精确授权</el-tag
            >
          </div>
        </div>
        <p class="form-hint">{{ selected.description || '暂无补充说明。' }}</p>
        <el-alert
          v-if="selected.key === 'wecom.approvals.submit'"
          title="一次业务调用即可提交"
          description="传 template、applicant、fields 和稳定 request_id 即可。提交接口内部自动读取模板、转换字段并校验；prepare 可用于提前展示全部字段问题。"
          type="info"
          :closable="false"
        />
        <el-descriptions :column="1" border>
          <el-descriptions-item label="网关调用"
            ><span class="mono">POST {{ selected.path }}</span
            ><el-button
              link
              :icon="DocumentCopy"
              aria-label="复制调用路径"
              @click="copyPath(selected.path)"
          /></el-descriptions-item>
          <el-descriptions-item v-if="selected.source === 'custom'" label="上游请求"
            ><span class="mono"
              >{{ selected.upstreamMethod }} {{ selected.upstreamPath }}</span
            ></el-descriptions-item
          >
          <el-descriptions-item label="配额 / 超时"
            >{{ selected.quota }} / {{ selected.timeout_s }} 秒</el-descriptions-item
          >
          <el-descriptions-item label="幂等支持">{{
            selected.idempotent ? '支持 Idempotency-Key' : '无幂等保证，请谨慎重复调用写接口'
          }}</el-descriptions-item>
        </el-descriptions>
        <h3 class="docs-heading">请求参数</h3>
        <SchemaFields :schema="selected.schema" />
        <h3 class="docs-heading">响应字段含义</h3>
        <ResponseFields :operation="selected" />
        <h3 class="docs-heading">参数示例</h3>
        <JsonViewer :value="operationExample(selected)" />
        <el-collapse
          ><el-collapse-item title="完整参数 Schema" name="schema"
            ><JsonViewer :value="selected.schema" /></el-collapse-item
        ></el-collapse>
        <el-alert
          v-if="!selected.available"
          type="info"
          :closable="false"
          title="配置尚未生效"
          description="接口或所属平台未启用。先编辑配置，再进行调试和授权。"
        />
        <div class="drawer-next-actions">
          <el-button
            v-if="['wecom.approvals.prepare', 'wecom.approvals.submit'].includes(selected.key)"
            type="primary"
            @click="
              closeDetail();
              approvalDialog = true;
            "
            >按模板填写调试</el-button
          >
          <el-button v-if="selected.source === 'custom'" @click="edit(selected)"
            >编辑配置</el-button
          >
          <el-button
            :disabled="!selected.available"
            @click="router.push({ path: '/grants', query: { operation: selected.key } })"
            >管理授权</el-button
          >
          <el-button
            :disabled="!selected.available"
            @click="router.push({ path: '/docs', query: { operation: selected.key } })"
            >查看调用文档</el-button
          >
          <el-button
            type="primary"
            :disabled="!selected.available"
            @click="router.push({ path: '/playground', query: { operation: selected.key } })"
            >调试接口</el-button
          >
        </div>
      </template>
    </el-drawer>
    <el-dialog
      v-model="approvalDialog"
      title="企业微信 · 审批接口调试"
      width="min(1180px, 96vw)"
      top="4vh"
      destroy-on-close
      ><div style="max-height: 78vh; overflow-y: auto; padding-right: 8px">
        <WecomApprovalWorkbench v-if="approvalDialog" /></div
    ></el-dialog>
  </div>
</template>
<style scoped>
.wecom-capability-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 18px;
}
.wecom-capability-heading h2 {
  font-size: 15px;
  margin: 0 0 8px;
}
.wecom-capability-heading .form-hint {
  max-width: 800px;
  line-height: 1.8;
}
@media (max-width: 650px) {
  .wecom-capability-heading {
    align-items: flex-start;
    flex-direction: column;
    gap: 12px;
  }
  .wecom-capabilities :deep(.el-radio-group) {
    display: flex;
    gap: 8px;
  }
  .wecom-capabilities :deep(.el-radio-button__inner) {
    border-radius: 6px !important;
    border: 1px solid var(--app-border);
    box-shadow: none;
    font-size: 12px;
  }
}
</style>
