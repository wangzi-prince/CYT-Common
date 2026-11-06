<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import {
  ArrowDown,
  ArrowRight,
  Box,
  Connection,
  CopyDocument,
  Document,
  Plus,
  Refresh,
  Search,
} from '@element-plus/icons-vue';
import { api } from '~/api';
import { useRequest } from '~/composables/useRequest';
import { buildCatalog, type CatalogOperation } from '~/utils/authoring';
import { operationDirection } from '~/utils/integration-direction';
import { filterOperationModule } from '~/utils/operation-modules';
import type { PlatformModule } from '~/api/types';
import PageHeader from '~/components/PageHeader.vue';
import PageState from '~/components/PageState.vue';
import OperationModuleBrowser from '~/components/OperationModuleBrowser.vue';
import SchemaFields from '~/components/SchemaFields.vue';
import ResponseFields from '~/components/ResponseFields.vue';

const router = useRouter(),
  route = useRoute();
const { data, loading, error, load } = useRequest(async () => {
  const [registry, custom, modules] = await Promise.all([
    api.registry(),
    api.customApis(),
    api.modules(),
  ]);
  return {
    services: modules.items.filter((m) => m.service_kind === 'internal'),
    operations: buildCatalog(registry.operations, custom.items, modules.items).filter(
      (o) => operationDirection(o, modules.items) === 'internal',
    ),
  };
});
const tab = ref(route.query.view === 'providers' ? 'providers' : 'interfaces');
const query = ref(''),
  provider = ref(String(route.query.provider || '')),
  category = ref(''),
  status = ref(''),
  page = ref(1);
const selected = ref<CatalogOperation>(),
  detailTab = ref('params');
const chooseProvider = ref(false),
  newProvider = ref('');
const services = computed(() => data.value?.services || []);
const operations = computed(() => data.value?.operations || []);
const scoped = computed(() =>
  operations.value.filter((o) => !provider.value || o.moduleKey === provider.value),
);
const matching = computed(() =>
  scoped.value.filter(
    (o) =>
      (!status.value || (status.value === 'enabled' ? o.available : !o.available)) &&
      `${o.key} ${o.summary} ${o.description} ${o.category}`
        .toLowerCase()
        .includes(query.value.trim().toLowerCase()),
  ),
);
const filtered = computed(() => filterOperationModule(matching.value, category.value));
const rows = computed(() => filtered.value.slice((page.value - 1) * 15, page.value * 15));
const filteredServices = computed(() =>
  services.value.filter(
    (s) =>
      (!status.value || (status.value === 'enabled' ? Boolean(s.enabled) : !s.enabled)) &&
      `${s.name} ${s.key} ${s.description}`
        .toLowerCase()
        .includes(query.value.trim().toLowerCase()),
  ),
);
const serviceRows = computed(() =>
  filteredServices.value.slice((page.value - 1) * 15, page.value * 15),
);
const hasFilters = computed(() =>
  Boolean(query.value || provider.value || category.value || status.value),
);
const providerName = (op: CatalogOperation) =>
  services.value.find((s) => s.key === op.moduleKey)?.name || op.platform;
const serviceCount = (key: string) => operations.value.filter((o) => o.moduleKey === key).length;
watch([query, provider, category, status], () => (page.value = 1));
watch([filtered, filteredServices], () => {
  const count = tab.value === 'interfaces' ? filtered.value.length : filteredServices.value.length;
  page.value = Math.min(page.value, Math.max(1, Math.ceil(count / 15)));
});
watch(provider, () => (category.value = ''));
watch(
  tab,
  () => {
    if (route.path !== '/exposed') return;
    resetFilters();
    const view = tab.value === 'providers' ? 'providers' : undefined;
    if (route.query.view !== view) void router.replace({ path: '/exposed', query: { view } });
  },
  { flush: 'sync' },
);
watch(
  () => route.query.view,
  (view) => {
    if (route.path === '/exposed') tab.value = view === 'providers' ? 'providers' : 'interfaces';
  },
);
watch(
  () => route.query.operation,
  (key) => {
    if (route.path === '/exposed') selected.value = operations.value.find((op) => op.key === key);
  },
);
onMounted(async () => {
  await load();
  const op = operations.value.find((o) => o.key === route.query.operation);
  if (op) open(op);
});
function resetFilters() {
  query.value = '';
  provider.value = '';
  category.value = '';
  status.value = '';
  page.value = 1;
}
function showProviderInterfaces(key: string) {
  tab.value = 'interfaces';
  resetFilters();
  provider.value = key;
}
function open(op: CatalogOperation) {
  selected.value = op;
  detailTab.value = 'params';
}
function createProvider() {
  void router.push({
    path: '/modules',
    query: { tab: 'modules', action: 'create', kind: 'internal' },
  });
}
function createInterface(key?: string) {
  if (!services.value.length) {
    createProvider();
    return;
  }
  const target = key || (services.value.length === 1 ? services.value[0]!.key : '');
  if (!target) {
    newProvider.value = provider.value;
    chooseProvider.value = true;
    return;
  }
  chooseProvider.value = false;
  void router.push({
    path: '/modules',
    query: { tab: 'apis', action: 'create', platform: target, kind: 'internal' },
  });
}
function editProvider(service: PlatformModule) {
  void router.push({
    path: '/modules',
    query: { tab: 'modules', edit: service.id, kind: 'internal' },
  });
}
function editInterface(op: CatalogOperation) {
  void router.push({
    path: '/modules',
    query: { tab: 'apis', edit: op.configId, kind: 'internal' },
  });
}
function docs(op?: CatalogOperation) {
  void router.push({
    path: '/docs',
    query: { direction: 'internal', ...(op ? { operation: op.key } : {}) },
  });
}
function debug(op: CatalogOperation) {
  void router.push({ path: '/playground', query: { operation: op.key, direction: 'internal' } });
}
function grant(op: CatalogOperation) {
  void router.push({
    path: '/grants',
    query: { operation: op.key, platform: op.platform, direction: 'internal' },
  });
}
async function copyPath() {
  try {
    await navigator.clipboard.writeText(selected.value?.path || '');
    ElMessage.success('接口路径已复制');
  } catch {
    ElMessage.info('请选择接口路径手动复制');
  }
}
</script>

<template>
  <div class="exposed-page">
    <PageHeader title="对外能力" description="管理业务接口、调用授权与接入文档">
      <el-button :icon="Document" @click="docs()">接入文档</el-button>
      <el-dropdown
        trigger="click"
        @command="
          (command: string) => (command === 'provider' ? createProvider() : createInterface())
        "
      >
        <el-button type="primary" :icon="Plus"
          >新增<el-icon class="add-arrow"><ArrowDown /></el-icon
        ></el-button>
        <template #dropdown
          ><el-dropdown-menu>
            <el-dropdown-item command="provider" :icon="Connection">登记提供方</el-dropdown-item>
            <el-dropdown-item command="interface" :icon="Box" :disabled="!services.length"
              >新增对外接口</el-dropdown-item
            >
          </el-dropdown-menu></template
        >
      </el-dropdown>
    </PageHeader>
    <PageState :loading="loading" :error="error" @retry="load">
      <section class="panel exposed-catalog">
        <div class="catalog-heading">
          <el-tabs v-model="tab" class="catalog-tabs" aria-label="对外能力视图">
            <el-tab-pane name="interfaces"
              ><template #label
                >接口能力<span class="tab-count">{{ operations.length }}</span></template
              ></el-tab-pane
            >
            <el-tab-pane name="providers"
              ><template #label
                >提供方管理<span class="tab-count">{{ services.length }}</span></template
              ></el-tab-pane
            >
          </el-tabs>
          <el-tooltip content="刷新列表"
            ><el-button :icon="Refresh" text circle aria-label="刷新列表" @click="load"
          /></el-tooltip>
        </div>
        <div
          v-if="tab === 'interfaces' ? operations.length : services.length"
          class="exposed-filters"
        >
          <el-input
            v-model="query"
            :prefix-icon="Search"
            clearable
            :placeholder="
              tab === 'interfaces' ? '搜索接口名称、标识或用途' : '搜索提供方名称、标识'
            "
            aria-label="搜索对外能力"
          />
          <el-select
            v-if="tab === 'interfaces'"
            v-model="provider"
            filterable
            clearable
            placeholder="全部提供方"
            aria-label="筛选提供方"
            ><el-option
              v-for="service in services"
              :key="service.key"
              :value="service.key"
              :label="service.name"
          /></el-select>
          <el-select v-model="status" clearable placeholder="全部状态" aria-label="筛选状态"
            ><el-option value="enabled" label="已启用" /><el-option value="disabled" label="未启用"
          /></el-select>
          <el-button :disabled="!hasFilters" text @click="resetFilters">重置</el-button>
        </div>
        <div v-if="tab === 'interfaces' && !operations.length" class="catalog-empty">
          <div class="empty-symbol">
            <el-icon><Box /></el-icon>
          </div>
          <h2>暂无对外接口</h2>
          <p>
            {{
              services.length
                ? '添加业务接口后，即可调试、授权并交付接入文档。'
                : '先登记提供方系统，再添加需要开放的业务接口。'
            }}
          </p>
          <el-button
            type="primary"
            :icon="Plus"
            @click="services.length ? createInterface() : createProvider()"
            >{{ services.length ? '新增对外接口' : '登记提供方' }}</el-button
          >
          <el-button v-if="services.length" text @click="tab = 'providers'"
            >管理提供方<el-icon><ArrowRight /></el-icon
          ></el-button>
        </div>
        <OperationModuleBrowser
          v-else-if="tab === 'interfaces'"
          v-model="category"
          :operations="scoped"
          :matching="matching"
        >
          <div v-if="!filtered.length" class="filter-empty">
            <el-icon><Search /></el-icon>
            <h3>没有匹配的接口</h3>
            <p>试试其他关键词，或调整筛选条件</p>
            <el-button @click="resetFilters">清除筛选</el-button>
          </div>
          <el-table v-else :data="rows" row-key="key" class="exposed-table">
            <el-table-column label="接口能力" min-width="220"
              ><template #default="{ row }"
                ><el-button class="interface-name" link @click="open(row)">{{
                  row.summary
                }}</el-button>
                <p class="subtext mono">{{ row.key }}</p></template
              ></el-table-column
            >
            <el-table-column label="提供方" min-width="110"
              ><template #default="{ row }"
                >{{ providerName(row) }}
                <p class="subtext">{{ row.category || '未分类' }}</p></template
              ></el-table-column
            >
            <el-table-column label="状态" width="100"
              ><template #default="{ row }"
                ><span class="state-label" :class="{ enabled: row.available }"
                  ><i />{{ row.available ? '已启用' : '未启用' }}</span
                ></template
              ></el-table-column
            >
            <el-table-column label="访问控制" min-width="125"
              ><template #default="{ row }"
                ><el-tag :type="row.sensitive ? 'warning' : 'info'" size="small" effect="light">{{
                  row.sensitive ? '敏感 · 精确授权' : '授权调用'
                }}</el-tag></template
              ></el-table-column
            >
            <el-table-column label="操作" width="175" fixed="right"
              ><template #default="{ row }"
                ><div class="row-actions">
                  <el-button link type="primary" @click="open(row)">详情</el-button
                  ><el-button link type="primary" :disabled="!row.available" @click="debug(row)"
                    >调试</el-button
                  >
                  <el-dropdown
                    trigger="click"
                    @command="
                      (command: string) =>
                        command === 'docs'
                          ? docs(row)
                          : command === 'grant'
                            ? grant(row)
                            : editInterface(row)
                    "
                    ><el-button link type="primary" :aria-label="`${row.summary}的更多操作`"
                      >更多<el-icon><ArrowDown /></el-icon
                    ></el-button>
                    <template #dropdown
                      ><el-dropdown-menu
                        ><el-dropdown-item command="docs">接入文档</el-dropdown-item
                        ><el-dropdown-item command="grant" :disabled="!row.available"
                          >授权调用方</el-dropdown-item
                        ><el-dropdown-item v-if="row.configId" command="edit" divided
                          >编辑接口</el-dropdown-item
                        ></el-dropdown-menu
                      ></template
                    >
                  </el-dropdown>
                </div></template
              ></el-table-column
            >
          </el-table>
          <div v-if="filtered.length" class="catalog-footer">
            <span>共 {{ filtered.length }} 个接口</span
            ><el-pagination
              v-if="filtered.length > 15"
              v-model:current-page="page"
              :page-size="15"
              :total="filtered.length"
              layout="prev,pager,next"
            />
          </div>
        </OperationModuleBrowser>
        <template v-else>
          <div v-if="!services.length" class="catalog-empty">
            <div class="empty-symbol">
              <el-icon><Connection /></el-icon>
            </div>
            <h2>暂无提供方</h2>
            <p>登记系统地址和鉴权方式，让多个接口共享连接配置。</p>
            <el-button type="primary" :icon="Plus" @click="createProvider">登记提供方</el-button>
          </div>
          <div v-else-if="!filteredServices.length" class="filter-empty">
            <el-icon><Search /></el-icon>
            <h3>没有匹配的提供方</h3>
            <p>试试其他关键词，或调整筛选条件</p>
            <el-button @click="resetFilters">清除筛选</el-button>
          </div>
          <el-table v-else :data="serviceRows" row-key="key" class="exposed-table">
            <el-table-column label="提供方系统" min-width="220"
              ><template #default="{ row }"
                ><div class="provider-cell">
                  <span class="provider-symbol"
                    ><el-icon><Connection /></el-icon
                  ></span>
                  <div>
                    <strong>{{ row.name }}</strong>
                    <p class="subtext mono">{{ row.key }}</p>
                  </div>
                </div></template
              ></el-table-column
            >
            <el-table-column label="服务地址" min-width="240" show-overflow-tooltip
              ><template #default="{ row }"
                ><span class="mono service-address">{{ row.base_url }}</span></template
              ></el-table-column
            >
            <el-table-column label="接口数" width="100"
              ><template #default="{ row }"
                ><el-button
                  link
                  type="primary"
                  :disabled="!serviceCount(row.key)"
                  @click="showProviderInterfaces(row.key)"
                  >{{ serviceCount(row.key) }}</el-button
                ></template
              ></el-table-column
            >
            <el-table-column label="状态" width="115"
              ><template #default="{ row }"
                ><span class="state-label" :class="{ enabled: row.enabled }"
                  ><i />{{ row.enabled ? '已启用' : '未启用' }}</span
                ></template
              ></el-table-column
            >
            <el-table-column label="操作" width="190" fixed="right"
              ><template #default="{ row }"
                ><div class="row-actions">
                  <el-button link type="primary" @click="editProvider(row)">连接配置</el-button
                  ><el-button link type="primary" @click="createInterface(row.key)"
                    >新增接口</el-button
                  >
                </div></template
              ></el-table-column
            >
          </el-table>
          <div v-if="filteredServices.length" class="catalog-footer">
            <span>共 {{ filteredServices.length }} 个提供方</span
            ><el-pagination
              v-if="filteredServices.length > 15"
              v-model:current-page="page"
              :page-size="15"
              :total="filteredServices.length"
              layout="prev,pager,next"
            />
          </div>
        </template>
      </section>
      <div class="exposed-shortcuts">
        <button type="button" @click="createProvider">
          <el-icon><Connection /></el-icon>
          <div><strong>登记提供方</strong><span>配置系统连接与鉴权</span></div>
          <el-icon><ArrowRight /></el-icon>
        </button>
        <button type="button" @click="router.push('/systems')">
          <el-icon><Box /></el-icon>
          <div><strong>调用方管理</strong><span>管理密钥与接口授权</span></div>
          <el-icon><ArrowRight /></el-icon>
        </button>
        <button type="button" @click="docs()">
          <el-icon><Document /></el-icon>
          <div><strong>接入文档</strong><span>查看参数与调用方式</span></div>
          <el-icon><ArrowRight /></el-icon>
        </button>
      </div>
    </PageState>
    <el-dialog
      v-model="chooseProvider"
      title="新增对外接口"
      width="min(460px,94vw)"
      destroy-on-close
    >
      <el-form label-position="top"
        ><el-form-item label="提供方系统"
          ><el-select
            v-model="newProvider"
            filterable
            placeholder="选择接口所属的提供方"
            style="width: 100%"
            ><el-option
              v-for="service in services"
              :key="service.key"
              :label="`${service.name}${service.enabled ? '' : '（未启用）'}`"
              :value="service.key" /></el-select></el-form-item
      ></el-form>
      <el-button link type="primary" :icon="Plus" @click="createProvider">登记新的提供方</el-button>
      <template #footer
        ><el-button @click="chooseProvider = false">取消</el-button
        ><el-button type="primary" :disabled="!newProvider" @click="createInterface(newProvider)"
          >下一步</el-button
        ></template
      >
    </el-dialog>
    <el-drawer
      :model-value="!!selected"
      title="接口详情"
      size="min(960px,96vw)"
      destroy-on-close
      @close="selected = undefined"
    >
      <template v-if="selected">
        <div class="capability-heading">
          <div class="detail-kicker">
            {{ providerName(selected) }}<span> / </span>{{ selected.category || '未分类' }}
          </div>
          <h2>{{ selected.summary }}</h2>
          <p v-if="selected.description">{{ selected.description }}</p>
          <div class="detail-tags">
            <el-tag :type="selected.available ? 'success' : 'info'" size="small" effect="light">{{
              selected.status
            }}</el-tag
            ><el-tag v-if="selected.sensitive" type="warning" size="small" effect="light"
              >敏感接口 · 精确授权</el-tag
            ><span>限流 {{ selected.quota }}</span
            ><span>超时 {{ selected.timeout_s }} 秒</span>
          </div>
        </div>
        <div class="endpoint-block">
          <span class="http-method">POST</span><code>{{ selected.path }}</code
          ><el-tooltip content="复制接口路径"
            ><el-button
              :icon="CopyDocument"
              text
              circle
              aria-label="复制接口路径"
              @click="copyPath"
          /></el-tooltip>
        </div>
        <div class="auth-note">
          <span>调用鉴权</span><code>Authorization: Bearer &lt;API Key&gt;</code>
        </div>
        <el-tabs v-model="detailTab" class="detail-tabs"
          ><el-tab-pane label="请求参数" name="params"
            ><SchemaFields :schema="selected.schema" /></el-tab-pane
          ><el-tab-pane label="响应字段" name="response"
            ><ResponseFields :operation="selected" /></el-tab-pane
        ></el-tabs>
      </template>
      <template #footer
        ><div v-if="selected" class="detail-footer">
          <el-button v-if="selected.configId" @click="editInterface(selected)">编辑接口</el-button>
          <div>
            <el-button @click="docs(selected)">接入文档</el-button
            ><el-button :disabled="!selected.available" @click="grant(selected)"
              >授权调用方</el-button
            ><el-button type="primary" :disabled="!selected.available" @click="debug(selected)"
              >调试接口</el-button
            >
          </div>
        </div></template
      >
    </el-drawer>
  </div>
</template>

<style scoped>
.add-arrow {
  margin-left: 10px;
}
.catalog-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 0 22px;
  border-bottom: 1px solid var(--app-divider);
}
.catalog-tabs {
  min-width: 0;
}
.catalog-tabs :deep(.el-tabs__header) {
  margin: 0;
}
.catalog-tabs :deep(.el-tabs__nav-wrap::after) {
  display: none;
}
.catalog-tabs :deep(.el-tabs__item) {
  height: 58px;
  font-size: 14px;
  gap: 8px;
}
.catalog-tabs :deep(.el-tabs__content) {
  display: none;
}
.tab-count {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 22px;
  height: 20px;
  padding: 0 6px;
  border-radius: 5px;
  background: var(--app-surface-soft);
  color: var(--app-text-muted);
  font-size: 11px;
  font-weight: 500;
}
:deep(.is-active) .tab-count {
  background: var(--app-primary-soft);
  color: var(--app-primary);
}
.exposed-filters {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  padding: 20px 22px;
}
.exposed-filters > .el-input {
  flex: 1;
  min-width: 220px;
}
.exposed-filters > .el-select {
  width: 175px;
}
.catalog-empty {
  min-height: 352px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 42px 24px;
  text-align: center;
}
.empty-symbol {
  display: grid;
  place-items: center;
  width: 66px;
  height: 66px;
  background: var(--app-primary-soft);
  color: var(--app-primary);
  border: 1px solid #dbeafe;
  border-radius: 20px;
  margin-bottom: 22px;
  font-size: 29px;
}
.catalog-empty h2 {
  font-size: 18px;
  font-weight: 600;
}
.catalog-empty p,
.filter-empty p {
  margin: 10px 0 24px;
  color: var(--app-text-muted);
  font-size: 13px;
  line-height: 1.8;
  max-width: 360px;
}
.catalog-empty > .el-button + .el-button {
  margin: 8px 0 0;
}
.filter-empty {
  min-height: 260px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 32px 20px;
}
.filter-empty > .el-icon {
  font-size: 26px;
  color: #94a3b8;
  margin-bottom: 16px;
}
.filter-empty h3 {
  font-size: 15px;
}
.interface-name.el-button {
  color: var(--app-text);
  font-weight: 600;
  max-width: 100%;
  height: auto;
  text-align: left;
  line-height: 1.6;
}
.interface-name :deep(span) {
  white-space: normal;
}
.interface-name:hover {
  color: var(--app-primary);
}
.exposed-table .subtext {
  margin-top: 5px;
  font-size: 11px;
}
.state-label {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  font-size: 12px;
  color: var(--app-text-muted);
  white-space: nowrap;
}
.state-label i {
  width: 6px;
  height: 6px;
  background: #94a3b8;
  border-radius: 50%;
}
.state-label.enabled {
  color: #168563;
}
.state-label.enabled i {
  background: #168563;
}
.row-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}
.row-actions .el-button {
  margin: 0;
  font-size: 12px;
}
.row-actions .el-icon {
  margin-left: 4px;
}
.provider-cell {
  display: flex;
  align-items: center;
  gap: 13px;
}
.provider-cell strong {
  font-weight: 600;
  color: var(--app-text);
}
.provider-symbol {
  display: grid;
  place-items: center;
  width: 36px;
  height: 36px;
  border: 1px solid var(--app-border);
  background: var(--app-surface-soft);
  border-radius: 9px;
  color: #64748b;
  font-size: 18px;
  flex-shrink: 0;
}
.service-address {
  font-size: 12px;
}
.catalog-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 16px 22px;
  border-top: 1px solid var(--app-divider);
  font-size: 12px;
  color: var(--app-text-muted);
}
.exposed-shortcuts {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
}
.exposed-shortcuts button {
  display: flex;
  align-items: center;
  text-align: left;
  gap: 14px;
  padding: 20px;
  background: var(--app-surface);
  border: 1px solid var(--app-border);
  border-radius: 10px;
  color: var(--app-text-secondary);
  transition:
    border-color 0.15s,
    box-shadow 0.15s;
}
.exposed-shortcuts button:hover {
  border-color: #a9c4fa;
  box-shadow: 0 3px 12px #0f172a06;
}
.exposed-shortcuts button > .el-icon:first-child {
  color: var(--app-primary);
  font-size: 21px;
}
.exposed-shortcuts button > .el-icon:last-child {
  margin-left: auto;
  color: #94a3b8;
}
.exposed-shortcuts strong {
  display: block;
  font-size: 13px;
  font-weight: 600;
  color: var(--app-text);
  margin-bottom: 5px;
}
.exposed-shortcuts span {
  font-size: 12px;
  color: var(--app-text-muted);
}
.detail-kicker {
  font-size: 12px;
  color: var(--app-text-muted);
  margin-bottom: 12px;
}
.detail-kicker span {
  color: #cbd5e1;
  margin: 0 6px;
}
.capability-heading h2 {
  font-size: 21px;
  font-weight: 650;
  line-height: 1.5;
  color: var(--app-text);
}
.capability-heading > p {
  font-size: 13px;
  line-height: 1.9;
  margin-top: 10px;
  color: var(--app-text-secondary);
}
.detail-tags {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px 16px;
  margin: 18px 0 24px;
  font-size: 12px;
  color: var(--app-text-muted);
}
.endpoint-block {
  display: flex;
  align-items: center;
  gap: 14px;
  background: #f8fafc;
  border: 1px solid var(--app-border);
  padding: 12px 16px;
  border-radius: 9px;
}
.http-method {
  font-size: 11px;
  font-weight: 700;
  color: #168563;
  background: #e4f4ec;
  padding: 5px 8px;
  border-radius: 5px;
}
.endpoint-block code {
  overflow-wrap: anywhere;
  font-size: 12px;
  line-height: 1.7;
  color: var(--app-text);
}
.endpoint-block .el-button {
  margin-left: auto;
  flex-shrink: 0;
}
.auth-note {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 10px;
  margin: 12px 0 24px;
  font-size: 12px;
  color: var(--app-text-muted);
}
.auth-note code {
  color: var(--app-text-secondary);
  font-size: 11px;
}
.detail-tabs :deep(.el-tabs__header) {
  margin-bottom: 20px;
}
.detail-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.detail-footer > div {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-left: auto;
}
.detail-footer .el-button + .el-button {
  margin-left: 0;
}
@media (max-width: 1100px) {
  .exposed-shortcuts {
    gap: 12px;
  }
  .exposed-shortcuts button {
    padding: 16px;
    gap: 10px;
  }
}
@media (max-width: 700px) {
  .catalog-heading {
    padding: 0 16px;
  }
  .catalog-tabs :deep(.el-tabs__item) {
    font-size: 13px;
  }
  .exposed-filters {
    padding: 16px;
    gap: 10px;
  }
  .exposed-filters > .el-select {
    flex: 1;
    min-width: 120px;
  }
  .exposed-filters > .el-input {
    flex-basis: 100%;
  }
  .exposed-shortcuts {
    grid-template-columns: 1fr;
    gap: 10px;
  }
  .exposed-shortcuts button {
    padding: 16px;
  }
  .exposed-shortcuts strong {
    margin-bottom: 3px;
  }
  .catalog-empty {
    min-height: 330px;
  }
  .detail-footer {
    flex-wrap: wrap;
  }
  .detail-footer > div {
    margin-left: 0;
  }
  .capability-heading h2 {
    font-size: 18px;
  }
  .endpoint-block {
    gap: 8px;
    padding: 10px;
  }
}
</style>
