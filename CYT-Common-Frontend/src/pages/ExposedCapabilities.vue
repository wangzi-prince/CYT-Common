<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { api } from '~/api';
import { useRequest } from '~/composables/useRequest';
import { buildCatalog } from '~/utils/authoring';
import { operationDirection } from '~/utils/integration-direction';
import { filterOperationModule } from '~/utils/operation-modules';
import { oaRosterExample } from '~/data/oa-roster-example';
import type { Operation } from '~/api/types';
import PageHeader from '~/components/PageHeader.vue';
import PageState from '~/components/PageState.vue';
import OperationModuleBrowser from '~/components/OperationModuleBrowser.vue';
import SchemaFields from '~/components/SchemaFields.vue';
import ResponseFields from '~/components/ResponseFields.vue';
const router = useRouter();
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
const query = ref(''),
  provider = ref(''),
  category = ref(''),
  page = ref(1);
const selected = ref<Operation>(),
  example = ref(false),
  view = ref('params');
const scoped = computed(
  () =>
    data.value?.operations.filter((o) => !provider.value || o.moduleKey === provider.value) || [],
);
const matching = computed(() =>
  scoped.value.filter((o) =>
    `${o.key} ${o.summary} ${o.description} ${o.category}`
      .toLowerCase()
      .includes(query.value.trim().toLowerCase()),
  ),
);
const filtered = computed(() => filterOperationModule(matching.value, category.value));
const rows = computed(() => filtered.value.slice((page.value - 1) * 15, page.value * 15));
const providerName = (key: string) => data.value?.services.find((s) => s.key === key)?.name || key;
watch([query, provider, category], () => (page.value = 1));
watch(provider, () => (category.value = ''));
onMounted(load);
function open(op: Operation, isExample = false) {
  selected.value = op;
  example.value = isExample;
  view.value = 'params';
}
function create(service?: string) {
  void router.push({
    path: '/modules',
    query: service
      ? { tab: 'apis', action: 'create', platform: service, kind: 'internal' }
      : { tab: 'modules', action: 'create', kind: 'internal' },
  });
}
function docs() {
  void router.push({
    path: '/docs',
    query: {
      direction: 'internal',
      ...(selected.value && !example.value ? { operation: selected.value.key } : {}),
    },
  });
}
</script>
<template>
  <div>
    <PageHeader
      title="对外能力"
      description="由我们自己的系统提供接口，授权给其他系统调用。第三方平台的接口在「服务接入」中管理。"
    >
      <el-button @click="docs">自有能力调用文档</el-button
      ><el-button type="primary" @click="create()">登记能力提供方</el-button>
    </PageHeader>
    <section class="panel ownership-intro">
      <div>
        <span class="eyebrow">我们的系统 → 其他系统</span>
        <h2>统一开放自有系统的业务能力</h2>
        <p>
          例如 OA
          对通讯录和领导关系进行整理，提供“含直属领导的组织花名册”，再授权给另一个业务系统。此页只显示明确登记为自有系统的提供方与接口。
        </p>
      </div>
      <div class="ownership-stats">
        <strong>{{ data?.services.length || 0 }}</strong
        ><span>能力提供方</span><strong>{{ data?.operations.length || 0 }}</strong
        ><span>已登记接口（含未启用）</span>
      </div>
    </section>
    <div class="ownership-flow">
      <span>1 登记提供方系统</span><span>→</span><span>2 定义入参与响应契约</span><span>→</span
      ><span>3 调试验证</span><span>→</span><span>4 授权与交付文档</span>
    </div>
    <PageState :loading="loading" :error="error" @retry="load">
      <section v-if="data?.services.length" class="panel">
        <div class="panel-head"><h2>能力提供方</h2></div>
        <div class="provider-list">
          <article v-for="service in data.services" :key="service.key">
            <div>
              <strong>{{ service.name }}</strong>
              <p class="mono subtext">
                {{ service.key }} · {{ service.enabled ? '已启用' : '未启用' }}
              </p>
            </div>
            <el-button
              @click="
                router.push({
                  path: '/modules',
                  query: { tab: 'modules', edit: service.id, kind: 'internal' },
                })
              "
              >提供方配置</el-button
            ><el-button @click="create(service.key)">新增对外接口</el-button>
          </article>
        </div>
      </section>
      <section class="panel">
        <div class="panel-toolbar exposed-filters">
          <el-input
            v-model="query"
            clearable
            placeholder="搜索业务能力、接口标识或用途"
            aria-label="搜索自有能力"
          /><el-select
            v-model="provider"
            clearable
            placeholder="全部提供方系统"
            aria-label="能力提供方"
            ><el-option
              v-for="service in data?.services || []"
              :key="service.key"
              :value="service.key"
              :label="service.name"
          /></el-select>
        </div>
        <OperationModuleBrowser v-model="category" :operations="scoped" :matching="matching">
          <el-empty
            v-if="!filtered.length"
            :description="
              data?.operations.length
                ? '当前范围没有接口，试试其他筛选条件'
                : '还没有登记自有系统的对外接口；第三方接口不会混入此列表。'
            "
            ><el-button type="primary" @click="create()">登记能力提供方</el-button
            ><el-button @click="open(oaRosterExample, true)"
              >查看 OA 花名册设计示例</el-button
            ></el-empty
          >
          <el-table v-else :data="rows" row-key="key"
            ><el-table-column label="对外业务能力" min-width="260"
              ><template #default="{ row }"
                ><strong>{{ row.summary }}</strong>
                <p class="mono subtext">{{ row.key }}</p></template
              ></el-table-column
            ><el-table-column label="提供方 / 状态" min-width="150"
              ><template #default="{ row }"
                >{{ providerName(row.moduleKey || row.platform) }}
                <p class="subtext">{{ row.status }}</p></template
              ></el-table-column
            ><el-table-column label="操作" width="225"
              ><template #default="{ row }"
                ><el-button link type="primary" @click="open(row)">契约</el-button
                ><el-button
                  link
                  type="primary"
                  :disabled="!row.available"
                  @click="
                    router.push({
                      path: '/playground',
                      query: { operation: row.key, direction: 'internal' },
                    })
                  "
                  >调试</el-button
                ><el-button
                  link
                  type="primary"
                  :disabled="!row.available"
                  @click="
                    router.push({
                      path: '/grants',
                      query: { operation: row.key, platform: row.platform, direction: 'internal' },
                    })
                  "
                  >授权</el-button
                ><el-button
                  link
                  type="primary"
                  @click="
                    router.push({
                      path: '/modules',
                      query: { tab: 'apis', edit: row.configId, kind: 'internal' },
                    })
                  "
                  >编辑</el-button
                ></template
              ></el-table-column
            ></el-table
          >
          <div class="exposed-pagination">
            <el-pagination
              v-model:current-page="page"
              :page-size="15"
              :total="filtered.length"
              layout="total,prev,pager,next"
            />
          </div>
        </OperationModuleBrowser>
      </section>
      <section class="panel example-card">
        <div>
          <h2>OA 花名册：从第三方数据到自有业务能力</h2>
          <p>企微通讯录是数据来源；OA 负责组织和领导关系规则；其他系统只调用 OA 的统一业务契约。</p>
          <span class="subtext">仅为设计示例，未连接、未注册，不计入接口数量。</span>
        </div>
        <el-button @click="open(oaRosterExample, true)">查看示例契约</el-button>
      </section>
    </PageState>
    <el-drawer
      :model-value="!!selected"
      :title="selected?.summary"
      size="min(960px,96vw)"
      @close="selected = undefined"
      ><template v-if="selected"
        ><el-alert
          v-if="example"
          title="设计示例 · 未接入、未注册，不可执行"
          type="info"
          :closable="false"
        />
        <p>{{ selected.description }}</p>
        <code>POST {{ selected.path }}</code
        ><el-alert
          v-if="example"
          title="领导关系规则"
          description="优先使用明确指定的直属领导；只有业务规则确认允许且候选唯一时才采用部门负责人。本人是部门负责人、多个负责人或没有负责人时不能直接推断，返回 unresolved 和 warnings。"
          type="info"
          :closable="false"
        /><el-tabs v-model="view"
          ><el-tab-pane label="请求参数含义" name="params"
            ><SchemaFields :schema="selected.schema" /></el-tab-pane
          ><el-tab-pane label="响应字段含义" name="response"
            ><ResponseFields :operation="selected" /></el-tab-pane
        ></el-tabs>
        <div v-if="!example" class="inline-actions">
          <el-button type="primary" @click="docs">打开调用文档</el-button>
        </div></template
      ></el-drawer
    >
  </div>
</template>
<style scoped>
.ownership-intro {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 28px;
  padding: 24px;
}
.ownership-intro > div:first-child {
  max-width: 760px;
}
.eyebrow {
  font-size: 12px;
  color: var(--el-color-primary);
}
.ownership-intro h2 {
  font-size: 23px;
  margin: 12px 0;
}
.ownership-intro p,
.example-card p {
  font-size: 13px;
  line-height: 1.9;
  color: var(--app-text-secondary);
}
.ownership-stats {
  display: grid;
  gap: 4px;
  flex-shrink: 0;
}
.ownership-stats strong {
  font-size: 26px;
  color: var(--el-color-primary);
}
.ownership-stats span {
  font-size: 12px;
  color: var(--app-text-secondary);
}
.ownership-flow {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  font-size: 12px;
  margin-bottom: 20px;
  color: var(--app-text-secondary);
}
.provider-list {
  padding: 0 20px;
}
.provider-list article {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  padding: 16px 0;
  border-bottom: 1px solid var(--app-border);
}
.provider-list article > div {
  flex: 1;
  min-width: 180px;
}
.exposed-filters {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}
.exposed-filters .el-input {
  flex: 1;
  min-width: 220px;
}
.exposed-filters .el-select {
  width: 220px;
}
.exposed-pagination {
  padding: 16px;
  overflow: auto;
}
.example-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  padding: 24px;
}
.example-card h2 {
  font-size: 16px;
}
.example-card .el-button {
  flex-shrink: 0;
}
@media (max-width: 700px) {
  .ownership-intro,
  .example-card {
    flex-direction: column;
    align-items: flex-start;
  }
  .ownership-stats {
    grid-template-columns: auto auto;
    align-items: baseline;
    gap: 8px 18px;
  }
}
</style>
