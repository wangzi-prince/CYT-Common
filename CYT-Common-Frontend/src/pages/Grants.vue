<script setup lang="ts">
import { computed, onMounted, ref, reactive, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Plus, Search, Refresh, Key } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { api } from '~/api';
import type { Grant, GrantBatchPreview, GrantBatchRequest } from '~/api/types';
import { useRequest, useAction, confirm, dateTime } from '~/composables/useRequest';
import { filterGrantOperations, updateGrantSelection } from '~/utils/grants';
import { filterOperationModule, operationModule } from '~/utils/operation-modules';
import OperationModuleBrowser from '~/components/OperationModuleBrowser.vue';
import { operationDirection } from '~/utils/integration-direction';
import PageHeader from '~/components/PageHeader.vue';
import PageState from '~/components/PageState.vue';
const props = defineProps<{ fixedSystem?: string; embedded?: boolean }>();
const emit = defineEmits<{ updated: [] }>();

const route = useRoute();
const router = useRouter();
const { data, loading, error, load } = useRequest(async () => {
  const [grants, platforms, catalog] = await Promise.all([
    api.grants(),
    api.platforms(),
    api.registry(),
  ]);
  return { ...grants, operations: catalog.operations, platforms: platforms.platforms };
});
const { busy, run } = useAction();
const system = ref('');
const q = ref(String(route.query.operation || ''));
const platform = ref(String(route.query.platform || ''));
const category = ref(String(route.query.category || ''));
const state = ref('all');
const kind = ref('all');
const direction = ref(String(route.query.direction || 'all'));
const mode = ref('matrix');
const page = ref(1);
const pageSize = ref(20);
const selected = ref(new Set<string>());
const decisionRequest = useRequest(() => api.effective(system.value));
const decisions = computed(
  () => new Map((decisionRequest.data.value?.operations || []).map((o) => [o.operation, o])),
);
const allowed = computed(
  () => new Set([...decisions.value.values()].filter((o) => o.allowed).map((o) => o.operation)),
);
const platformNames = computed(() =>
  Object.fromEntries((data.value?.platforms || []).map((p) => [p.platform, p.display_name])),
);
const moduleOperations = computed(() =>
  (data.value?.operations || []).filter(
    (op) =>
      (!platform.value || op.platform === platform.value) &&
      (direction.value === 'all' || operationDirection(op) === direction.value),
  ),
);
const matching = computed(() =>
  filterGrantOperations(
    moduleOperations.value,
    {
      query: q.value,
      platform: platform.value,
      state: state.value,
      kind: kind.value,
    },
    allowed.value,
    platformNames.value,
  ),
);
const operations = computed(() => filterOperationModule(matching.value, category.value));
const pageOperations = computed(() =>
  operations.value.slice((page.value - 1) * pageSize.value, page.value * pageSize.value),
);
const checkedOnPage = computed(
  () => pageOperations.value.filter((o) => selected.value.has(o.key)).length,
);
const outsideCount = computed(
  () => selected.value.size - operations.value.filter((o) => selected.value.has(o.key)).length,
);
const systemName = computed(
  () => data.value?.systems.find((s) => s.key === system.value)?.display_name || system.value,
);
const ready = computed(
  () =>
    !!system.value &&
    !loading.value &&
    !error.value &&
    !decisionRequest.loading.value &&
    !decisionRequest.error.value &&
    !busy.value,
);
const ruleQ = ref('');
const focusRule = ref<number>();
const rules = computed(() =>
  (data.value?.grants || []).filter(
    (g) =>
      g.system === system.value &&
      `${g.operation_pattern} ${g.note}`.toLowerCase().includes(ruleQ.value.toLowerCase()),
  ),
);
const dialog = ref(false);
const editingRule = ref(false);
const expiry = ref<Date>();
const form = reactive({
  system: '',
  operation_pattern: '',
  effect: 'allow',
  quota: '',
  note: '',
  confirm_sensitive: false,
});
const preview = ref<GrantBatchPreview>();
const batchRequest = ref<GrantBatchRequest>();
const batchDialog = ref(false);
const confirmSensitive = ref(false);
const skipBlocked = ref(false);

watch([q, platform, category, state, kind, direction, pageSize], () => {
  page.value = 1;
});
watch([moduleOperations, loading], () => {
  if (
    !loading.value &&
    data.value &&
    category.value &&
    !moduleOperations.value.some((op) => operationModule(op) === category.value)
  )
    category.value = '';
});
watch(system, async () => {
  selected.value = new Set();
  batchDialog.value = false;
  page.value = 1;
  focusRule.value = undefined;
  if (system.value) await decisionRequest.load();
});
watch(
  () => [route.query.system, route.query.operation, route.query.platform, props.fixedSystem],
  () => {
    if (props.fixedSystem) system.value = props.fixedSystem;
    else if (data.value?.systems.some((s) => s.key === route.query.system))
      system.value = String(route.query.system);
    q.value = String(route.query.operation || '');
    platform.value = String(route.query.platform || '');
    category.value = String(route.query.category || '');
    mode.value = 'matrix';
    state.value = 'all';
    kind.value = 'all';
    selected.value = new Set();
  },
);
onMounted(async () => {
  await load();
  system.value =
    data.value?.systems.find((s) => s.key === (props.fixedSystem || route.query.system))?.key ||
    data.value?.systems[0]?.key ||
    '';
});
function select(keys: string[], checked = true) {
  selected.value = updateGrantSelection(selected.value, keys, checked);
}
function selectReadOnly() {
  kind.value = 'read';
  selected.value = new Set(operations.value.map((o) => o.key));
}
async function refresh() {
  await Promise.all([load(), system.value ? decisionRequest.load() : Promise.resolve()]);
}
async function startBatch(effect: 'allow' | 'deny', keys = [...selected.value]) {
  if (!ready.value || !keys.length) return;
  const body = { system: system.value, operations: keys, effect };
  const result = await run(() => api.previewGrantBatch(body));
  if (!result || system.value !== body.system) return;
  batchRequest.value = body;
  preview.value = result;
  confirmSensitive.value = false;
  skipBlocked.value = false;
  batchDialog.value = true;
}
async function refreshPreview() {
  if (!batchRequest.value) return;
  const result = await run(() => api.previewGrantBatch(batchRequest.value!));
  if (result) {
    preview.value = result;
    confirmSensitive.value = false;
    skipBlocked.value = false;
  }
}
async function applyBatch() {
  if (!preview.value || !batchRequest.value) return;
  const result = await run(() =>
    api.applyGrantBatch({
      ...batchRequest.value!,
      revision: preview.value!.revision,
      confirm_sensitive: confirmSensitive.value,
      skip_blocked: skipBlocked.value,
    }),
  );
  if (!result) return;
  batchDialog.value = false;
  select(batchRequest.value.operations, false);
  ElMessage.success(
    `已更新 ${result.changed} 项授权${result.skipped ? `，跳过 ${result.skipped} 项冲突` : ''}`,
  );
  await refresh();
  emit('updated');
}
function locateRule(id: number) {
  batchDialog.value = false;
  mode.value = 'rules';
  const rule = data.value?.grants.find((g) => g.id === id);
  ruleQ.value = rule?.operation_pattern || '';
  focusRule.value = id;
}
function editRule(rule?: Grant) {
  editingRule.value = !!rule;
  Object.assign(form, {
    system: rule?.system || system.value,
    operation_pattern: rule?.operation_pattern || String(route.query.operation || ''),
    effect: rule?.effect || 'allow',
    quota: rule?.quota || '',
    note: rule?.note || '',
    confirm_sensitive: false,
  });
  expiry.value = rule?.expires_at ? new Date(rule.expires_at) : undefined;
  dialog.value = true;
}
async function save() {
  const result = await run(() =>
    api.upsertGrant({ ...form, expires_at: expiry.value?.toISOString() || null }),
  );
  if (!result) return;
  dialog.value = false;
  await refresh();
  emit('updated');
}
async function remove(id: number) {
  if (!(await confirm('确认删除该授权规则？删除后最终权限会重新按其他规则计算。'))) return;
  const result = await run(async () => {
    await api.deleteGrant(id);
    return true;
  });
  if (result) {
    await refresh();
    emit('updated');
  }
}
</script>

<template>
  <div class="grant-page">
    <PageHeader
      v-if="!embedded"
      title="操作授权"
      description="先找到接口，再批量授权。拒绝规则优先，敏感操作统一确认。"
    >
      <el-button :icon="Refresh" :disabled="busy" @click="refresh">刷新</el-button>
      <el-button :icon="Plus" @click="editRule()">新建规则</el-button>
    </PageHeader>
    <section class="panel">
      <div class="panel-toolbar grant-toolbar">
        <el-select
          v-if="!fixedSystem"
          v-model="system"
          filterable
          placeholder="搜索调用方名称或标识"
          :disabled="busy"
          class="system-select"
          aria-label="调用方系统"
        >
          <el-option
            v-for="s in data?.systems || []"
            :key="s.key"
            :value="s.key"
            :label="`${s.display_name} · ${s.key}`"
          >
            {{ s.display_name }}
            <span class="subtext">{{ s.key }}{{ s.enabled ? '' : ' · 已停用' }}</span>
          </el-option>
        </el-select>
        <el-radio-group v-model="mode"
          ><el-radio-button value="matrix">操作权限</el-radio-button
          ><el-radio-button value="rules">授权规则</el-radio-button></el-radio-group
        >
        <span class="subtext system-status"
          >{{ allowed.size }} / {{ data?.operations.length || 0 }} 项已授权</span
        >
        <el-button v-if="embedded" :icon="Refresh" :disabled="busy" @click="refresh"
          >刷新</el-button
        >
        <el-button v-if="embedded" :icon="Plus" @click="editRule()">新建规则</el-button>
      </div>
      <template v-if="mode === 'matrix'">
        <div class="grant-filters">
          <el-select v-model="direction" aria-label="授权调用方向"
            ><el-option value="all" label="全部调用方向" /><el-option
              value="third_party"
              label="第三方接入" /><el-option value="internal" label="自有对外能力"
          /></el-select>
          <el-input
            v-model="q"
            :prefix-icon="Search"
            placeholder="搜索名称、接口标识或业务词，支持多个关键词"
            clearable
            aria-label="搜索授权接口"
          />
          <el-select
            v-model="platform"
            clearable
            filterable
            placeholder="全部平台"
            aria-label="筛选平台"
            ><el-option
              v-for="p in data?.platforms"
              :key="p.platform"
              :label="p.display_name"
              :value="p.platform"
          /></el-select>
          <el-select v-model="state" aria-label="筛选授权状态"
            ><el-option label="全部授权状态" value="all" /><el-option
              label="尚未授权"
              value="denied" /><el-option label="已授权" value="allowed"
          /></el-select>
          <el-select v-model="kind" aria-label="筛选操作类型"
            ><el-option label="全部操作类型" value="all" /><el-option
              label="只读操作"
              value="read" /><el-option label="敏感操作" value="sensitive"
          /></el-select>
        </div>
        <div class="grant-selection">
          <span
            >找到 <strong>{{ operations.length }}</strong> 项</span
          >
          <el-button
            link
            type="primary"
            :disabled="!ready || !operations.length"
            @click="select(operations.map((o) => o.key))"
            >选中全部筛选结果</el-button
          >
          <el-button link :disabled="!ready" @click="selectReadOnly"
            >仅选当前范围只读接口</el-button
          >
        </div>
        <div v-if="selected.size" class="grant-batchbar">
          <div>
            <el-icon><Key /></el-icon> 已选 <strong>{{ selected.size }}</strong> 项
            <span v-if="outsideCount" class="subtext"
              >（{{ outsideCount }} 项位于当前筛选之外）</span
            >
            <span v-if="selected.size > 500" class="expired-rule">
              每批最多 500 项，请缩小选择范围。</span
            >
          </div>
          <div class="batch-actions">
            <el-button text :disabled="busy" @click="selected = new Set()">清空选择</el-button
            ><el-button :disabled="!ready || selected.size > 500" @click="startBatch('deny')"
              >批量停用</el-button
            ><el-button
              type="primary"
              :disabled="!ready || selected.size > 500"
              @click="startBatch('allow')"
              >批量授权</el-button
            >
          </div>
        </div>
        <el-alert
          v-if="decisionRequest.error.value"
          title="权限状态加载失败，请刷新后重试"
          type="error"
          :closable="false"
        />
      </template>
      <div v-else class="grant-selection">
        <el-input
          v-model="ruleQ"
          :prefix-icon="Search"
          placeholder="搜索匹配规则或备注"
          clearable
        /><span class="subtext">拒绝规则优先于允许规则；通配允许不包含敏感操作。</span>
      </div>
      <PageState :loading="loading" :error="error" @retry="load">
        <OperationModuleBrowser
          v-if="mode === 'matrix'"
          v-model="category"
          :operations="moduleOperations"
          :matching="matching"
        >
          <el-table
            :data="pageOperations"
            row-key="key"
            v-loading="decisionRequest.loading.value"
            empty-text="没有匹配的接口，请调整关键词或筛选条件"
          >
            <el-table-column width="48"
              ><template #header
                ><el-checkbox
                  :model-value="!!pageOperations.length && checkedOnPage === pageOperations.length"
                  :indeterminate="checkedOnPage > 0 && checkedOnPage < pageOperations.length"
                  :disabled="!ready || !pageOperations.length"
                  aria-label="选择本页接口"
                  @change="
                    select(
                      pageOperations.map((o) => o.key),
                      !!$event,
                    )
                  " /></template
              ><template #default="{ row }"
                ><el-checkbox
                  :model-value="selected.has(row.key)"
                  :disabled="!ready"
                  :aria-label="`选择 ${row.key}`"
                  @change="select([row.key], !!$event)" /></template
            ></el-table-column>
            <el-table-column label="接口 / 操作" min-width="300"
              ><template #default="{ row }"
                ><div class="grant-operation">
                  <strong>{{ row.summary }}</strong
                  ><el-tag v-if="row.sensitive" type="warning" size="small" effect="plain"
                    >敏感</el-tag
                  >
                </div>
                <el-button
                  link
                  class="mono operation-link"
                  @click="
                    router.push({
                      path: '/registry',
                      query: { operation: row.key, platform: row.platform },
                    })
                  "
                  >{{ row.key }}</el-button
                ></template
              ></el-table-column
            >
            <el-table-column label="平台" min-width="125"
              ><template #default="{ row }">{{
                platformNames[row.platform] || row.platform
              }}</template></el-table-column
            >
            <el-table-column label="生效权限" min-width="165"
              ><template #default="{ row }"
                ><el-popover placement="top" width="320" trigger="click"
                  ><template #reference
                    ><el-button link :type="allowed.has(row.key) ? 'success' : 'info'"
                      >{{ allowed.has(row.key) ? '已授权' : '未授权' }} · 查看原因</el-button
                    ></template
                  >
                  <p>{{ decisions.get(row.key)?.reason || '尚未授权' }}</p>
                  <div v-for="rule in decisions.get(row.key)?.rules" :key="rule.id">
                    <el-button link type="primary" @click="locateRule(rule.id)"
                      >{{ rule.effect === 'deny' ? '拒绝' : '允许' }} {{ rule.operation_pattern }} →
                      定位规则</el-button
                    >
                  </div>
                  <div class="subtext">
                    生效配额：{{ decisions.get(row.key)?.quota || row.quota }}
                  </div></el-popover
                ></template
              ></el-table-column
            >
            <el-table-column label="操作" width="85" fixed="right"
              ><template #default="{ row }"
                ><el-button
                  link
                  type="primary"
                  :disabled="!ready"
                  @click="startBatch(allowed.has(row.key) ? 'deny' : 'allow', [row.key])"
                  >{{ allowed.has(row.key) ? '停用' : '授权' }}</el-button
                ></template
              ></el-table-column
            >
          </el-table>
          <div class="grant-pagination">
            <el-pagination
              v-model:current-page="page"
              v-model:page-size="pageSize"
              :total="operations.length"
              :page-sizes="[20, 50, 100]"
              layout="total, sizes, prev, pager, next"
              small
            />
          </div>
        </OperationModuleBrowser>
        <el-table
          v-else
          :data="rules"
          :row-class-name="
            ({ row }: { row: Grant }) => (row.id === focusRule ? 'focused-grant' : '')
          "
          empty-text="没有匹配的授权规则"
        >
          <el-table-column
            prop="operation_pattern"
            label="匹配规则"
            min-width="250"
          /><el-table-column label="策略" width="85"
            ><template #default="{ row }"
              ><el-tag :type="row.effect === 'allow' ? 'success' : 'danger'" effect="plain">{{
                row.effect === 'allow' ? '允许' : '拒绝'
              }}</el-tag></template
            ></el-table-column
          >
          <el-table-column label="配额" width="110"
            ><template #default="{ row }">{{ row.quota || '接口默认' }}</template></el-table-column
          ><el-table-column label="到期时间" min-width="145"
            ><template #default="{ row }"
              ><span
                :class="{
                  'expired-rule': row.expires_at && new Date(row.expires_at) <= new Date(),
                }"
                >{{ row.expires_at ? dateTime(row.expires_at) : '长期有效' }}</span
              ></template
            ></el-table-column
          ><el-table-column prop="note" label="备注" min-width="140" /><el-table-column
            label="操作"
            width="120"
            ><template #default="{ row }"
              ><el-button link type="primary" :disabled="busy" @click="editRule(row)"
                >编辑</el-button
              ><el-button link type="danger" :disabled="busy" @click="remove(row.id)"
                >删除</el-button
              ></template
            ></el-table-column
          >
        </el-table>
      </PageState>
    </section>
    <el-dialog
      v-model="batchDialog"
      :title="preview?.effect === 'allow' ? '确认批量授权' : '确认批量停用'"
      width="min(900px, 94vw)"
      :close-on-click-modal="false"
    >
      <template v-if="preview"
        ><p class="batch-summary">
          调用方：<strong>{{ systemName }}</strong> · 将变更
          <strong>{{ preview.changed }}</strong> 项 · 无需修改 {{ preview.noop_count }} 项 · 冲突
          {{ preview.blocked_count }} 项
        </p>
        <el-alert
          title="只保存所选接口的精确规则，保留已有配额、有效期和备注。"
          type="info"
          :closable="false"
          show-icon
        />
        <el-table :data="preview.items" max-height="350" class="preview-table"
          ><el-table-column label="接口" min-width="230"
            ><template #default="{ row }"
              >{{ row.summary }}
              <el-tag v-if="row.sensitive" type="warning" size="small" effect="plain">敏感</el-tag>
              <div class="mono subtext">{{ row.operation }}</div></template
            ></el-table-column
          ><el-table-column label="变更结果" width="135"
            ><template #default="{ row }"
              ><el-tag
                :type="row.blocked ? 'danger' : row.noop ? 'info' : 'success'"
                effect="plain"
                >{{
                  row.blocked
                    ? '冲突'
                    : row.noop
                      ? '无需修改'
                      : row.after
                        ? '未授权 → 已授权'
                        : '已授权 → 停用'
                }}</el-tag
              ></template
            ></el-table-column
          ><el-table-column label="说明" min-width="230"
            ><template #default="{ row }"
              ><span>{{ row.reason }}</span>
              <div v-for="rule in row.blocking_rules" :key="rule.id">
                <el-button link type="primary" @click="locateRule(rule.id)"
                  >{{ rule.operation_pattern }} · 定位规则</el-button
                >
              </div>
              <div v-if="row.expires_at" class="subtext">
                有效期至 {{ dateTime(row.expires_at) }}
              </div></template
            ></el-table-column
          ></el-table
        >
        <el-checkbox v-if="preview.blocked_count" v-model="skipBlocked" class="batch-confirm"
          >跳过 {{ preview.blocked_count }} 项冲突，仅保存可生效的
          {{ preview.changed }} 项</el-checkbox
        ><el-checkbox
          v-if="preview.sensitive_count"
          v-model="confirmSensitive"
          class="batch-confirm"
          >确认向 {{ systemName }} 开放
          {{ preview.sensitive_count }} 项敏感操作，已知晓相关业务影响</el-checkbox
        >
      </template>
      <template #footer
        ><el-button :disabled="busy" @click="batchDialog = false">取消</el-button
        ><el-button :disabled="busy" @click="refreshPreview">重新预览</el-button
        ><el-button
          type="primary"
          :loading="busy"
          :disabled="
            !preview?.changed ||
            (!!preview.blocked_count && !skipBlocked) ||
            (!!preview.sensitive_count && !confirmSensitive)
          "
          @click="applyBatch"
          >保存 {{ preview?.changed || 0 }} 项变更</el-button
        ></template
      >
    </el-dialog>
    <el-dialog
      v-model="dialog"
      title="授权规则"
      width="min(520px, 94vw)"
      :close-on-click-modal="false"
      ><el-form label-position="top"
        ><el-form-item label="调用方系统" required
          ><el-select v-model="form.system" filterable :disabled="editingRule"
            ><el-option
              v-for="s in data?.systems"
              :key="s.key"
              :label="`${s.display_name} · ${s.key}`"
              :value="s.key" /></el-select></el-form-item
        ><el-form-item label="操作或通配规则" required
          ><el-input
            v-model="form.operation_pattern"
            :disabled="editingRule"
            placeholder="如 gmail.messages.get 或 gmail.*" /></el-form-item
        ><el-form-item label="授权策略"
          ><el-radio-group v-model="form.effect"
            ><el-radio value="allow">允许</el-radio
            ><el-radio value="deny">拒绝</el-radio></el-radio-group
          ></el-form-item
        ><el-form-item label="覆盖配额"
          ><el-input
            v-model="form.quota"
            placeholder="如 60/min，留空使用接口默认值" /></el-form-item
        ><el-form-item label="到期时间"
          ><el-date-picker
            v-model="expiry"
            type="datetime"
            placeholder="留空长期有效"
            clearable
            style="width: 100%" /></el-form-item
        ><el-form-item label="备注"><el-input v-model="form.note" maxlength="255" /></el-form-item
        ><el-checkbox v-model="form.confirm_sensitive"
          >授权敏感操作时，确认知晓其业务影响</el-checkbox
        ></el-form
      ><template #footer
        ><el-button @click="dialog = false">取消</el-button
        ><el-button
          type="primary"
          :disabled="!form.system || !form.operation_pattern"
          :loading="busy"
          @click="save"
          >保存规则</el-button
        ></template
      ></el-dialog
    >
  </div>
</template>

<style scoped>
.grant-toolbar {
  flex-wrap: wrap;
  gap: 12px;
}
.system-select {
  width: 330px;
  max-width: 100%;
}
.system-status {
  margin-left: auto;
}
.grant-filters {
  display: grid;
  grid-template-columns: minmax(250px, 1fr) 170px 150px 150px;
  gap: 10px;
  padding: 0 20px 12px;
}
.grant-selection {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 14px;
  padding: 6px 20px 14px;
  font-size: 13px;
}
.grant-selection .el-input {
  max-width: 360px;
}
.grant-batchbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 10px;
  margin: 0 20px 14px;
  padding: 10px 14px;
  background: var(--el-color-primary-light-9);
  border: 1px solid var(--el-color-primary-light-7);
  border-radius: 8px;
  font-size: 13px;
}
.grant-batchbar .el-icon {
  vertical-align: middle;
  margin-right: 4px;
}
.batch-actions {
  display: flex;
  gap: 8px;
}
.batch-actions .el-button + .el-button {
  margin-left: 0;
}
.grant-operation {
  display: flex;
  align-items: center;
  gap: 8px;
}
.operation-link {
  font-size: 12px;
  margin-top: 4px;
}
.grant-pagination {
  padding: 16px 20px;
  overflow-x: auto;
}
.batch-summary {
  margin-top: 0;
}
.preview-table {
  margin-top: 14px;
}
.batch-confirm {
  display: flex;
  height: auto;
  margin-top: 16px;
  white-space: normal;
}
.batch-confirm :deep(.el-checkbox__label) {
  white-space: normal;
  line-height: 1.6;
}
.expired-rule {
  color: var(--el-color-danger);
}
:deep(.focused-grant td) {
  background: var(--el-color-primary-light-9) !important;
}
@media (max-width: 1100px) {
  .grant-filters {
    grid-template-columns: 1fr 1fr;
  }
}
@media (max-width: 600px) {
  .system-select {
    width: 100%;
  }
  .system-status {
    margin-left: 0;
  }
  .grant-filters {
    padding: 0 12px 12px;
  }
  .grant-filters > :first-child {
    grid-column: 1 / -1;
  }
  .grant-selection {
    padding: 6px 12px 14px;
    gap: 10px;
  }
  .grant-batchbar {
    margin: 0 12px 12px;
  }
  .grant-pagination {
    padding: 14px 12px;
  }
}
</style>
