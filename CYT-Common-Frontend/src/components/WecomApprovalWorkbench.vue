<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { Plus, Refresh, Right, DocumentChecked } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { api } from '~/api';
import type { SystemItem } from '~/api/types';
import { useAction, confirm } from '~/composables/useRequest';
import ApprovalFields, { type ApprovalField } from '~/components/ApprovalFields.vue';
import JsonViewer from '~/components/JsonViewer.vue';

interface FormDefinition {
  name: string;
  template_id: string;
  version: string;
  fields: ApprovalField[];
}
interface ApprovalRow {
  approval_no: string;
  template_id?: string;
  template_name?: string;
  status?: string;
  status_label?: string;
  applicant?: unknown;
  created_at?: unknown;
}
interface Envelope<T> {
  ok: boolean;
  data: T;
  meta?: { mock?: boolean };
}
const router = useRouter();
const { busy, run } = useAction();
const systems = ref<SystemItem[]>([]);
const system = ref('');
const allowed = ref<Set<string>>();
let permissionGeneration = 0;
function can(operation: string) {
  return allowed.value?.has(`wecom.${operation}`) === true;
}
const mock = ref<boolean>();
const environment = ref('');
const tab = ref('apply');
const template = ref('');
const registered = ref<{ alias: string; template_id: string; description: string }[]>([]);
const form = ref<FormDefinition>();
const applicant = ref('');
const values = ref<Record<string, unknown>>({});
const invalidJson = ref(false);
const requestId = ref('');
const preflight = ref<{
  valid: boolean;
  template_version?: string;
  errors?: unknown[];
  payload?: unknown;
}>();
const submitted = ref<unknown>();
const queried = ref<ApprovalRow[]>([]);
const queryNumber = ref('');
const queryIdentifier = ref('approval_no');
const queryOperation = computed(() =>
  queryNumber.value.trim()
    ? queryIdentifier.value === 'request_id'
      ? 'approvals.track'
      : 'approvals.get'
    : 'approvals.collect',
);
const queryStatus = ref('');
const queryRange = ref<string[]>([]);
const queryPage = ref<Record<string, unknown>>();
const queryErrors = ref<unknown[]>([]);
const detail = ref<unknown>();
const events = ref<Record<string, unknown>[]>([]);
const afterId = ref(0);
const eventNext = ref(0);
const callbackPath = computed(
  () => `/integrations/wecom/webhooks/${system.value || '{system}'}/events`,
);
const loadedTemplate = ref('');
const formGeneration = ref(0);
const draft = computed(() => ({
  template: template.value.trim(),
  applicant: applicant.value.trim(),
  fields: values.value,
}));
const requestExample = computed(() => ({
  ...draft.value,
  template_version: preflight.value?.template_version,
  request_id: requestId.value || '<稳定业务编号>',
}));
const preparedDraft = ref('');
const canSubmit = computed(
  () =>
    preflight.value?.valid &&
    can('approvals.submit') &&
    preparedDraft.value === JSON.stringify(draft.value) &&
    requestId.value.trim() &&
    !invalidJson.value,
);
watch(
  draft,
  () => {
    preflight.value = undefined;
    submitted.value = undefined;
  },
  { deep: true },
);
watch(system, () => {
  form.value = undefined;
  loadedTemplate.value = '';
  registered.value = [];
  preflight.value = undefined;
  queried.value = [];
  events.value = [];
  afterId.value = 0;
  submitted.value = undefined;
  values.value = {};
  requestId.value = '';
  queryPage.value = undefined;
  queryErrors.value = [];
  eventNext.value = 0;
  allowed.value = undefined;
  const generation = ++permissionGeneration;
  if (system.value)
    void api
      .effective(system.value)
      .then((result) => {
        if (generation === permissionGeneration)
          allowed.value = new Set(
            result.operations.filter((item) => item.allowed).map((item) => item.operation),
          );
      })
      .catch(() => {
        if (generation === permissionGeneration) allowed.value = new Set();
      });
});
watch(template, () => {
  if (template.value.trim() !== loadedTemplate.value) form.value = undefined;
});

async function call<T>(operation: string, params: Record<string, unknown> = {}) {
  const response = (await api.simulate({
    system: system.value,
    operation: `wecom.${operation}`,
    params,
  })) as unknown as Envelope<T>;
  if (response.meta?.mock !== undefined) mock.value = response.meta.mock;
  return response.data;
}
async function refreshTemplates() {
  await run(async () => {
    const result = await call<{ items: typeof registered.value }>('approval_templates.list');
    registered.value = result.items;
  });
}
async function loadForm() {
  await run(async () => {
    const result = await call<FormDefinition>('approval_templates.get', {
      template: template.value.trim(),
    });
    form.value = result;
    formGeneration.value++;
    loadedTemplate.value = template.value.trim();
    values.value = {};
    invalidJson.value = false;
    preflight.value = undefined;
    submitted.value = undefined;
  });
}
async function prepare() {
  if (invalidJson.value) {
    ElMessage.error('请先修正字段中的 JSON 格式');
    return;
  }
  await run(async () => {
    const fingerprint = JSON.stringify(draft.value);
    const caller = system.value;
    const result = await call<typeof preflight.value>('approvals.prepare', JSON.parse(fingerprint));
    if (fingerprint !== JSON.stringify(draft.value) || caller !== system.value) {
      ElMessage.info('申请内容已变化，请重新预检');
      return;
    }
    preflight.value = result;
    preparedDraft.value = fingerprint;
    if (preflight.value?.valid) ElMessage.success('预检通过，可以提交');
  });
}
async function submit() {
  if (!canSubmit.value) return;
  if (
    !(await confirm(
      `${mock.value ? '将生成模拟审批单' : '将向企微提交真实审批申请'}。申请人：${applicant.value}；业务编号：${requestId.value}。确认提交？`,
      '提交审批',
    ))
  )
    return;
  await run(async () => {
    submitted.value = await call('approvals.submit', requestExample.value);
    ElMessage.success(mock.value ? '模拟审批已生成' : '审批已提交');
  });
}
async function upload(key: string, file: File) {
  if (!can('approval_files.upload')) {
    ElMessage.warning('当前调用系统未开通审批附件上传权限，请先配置授权');
    return;
  }
  if (!file.size || file.size > 10 * 1024 * 1024) {
    ElMessage.error('请选择非空且不超过 10MB 的文件');
    return;
  }
  await run(async () => {
    const encoded = await new Promise<string>((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => resolve(String(reader.result).split(',')[1] || '');
      reader.onerror = reject;
      reader.readAsDataURL(file);
    });
    const result = await call<{ file_id: string }>('approval_files.upload', {
      filename: file.name,
      content_base64: encoded,
    });
    values.value = {
      ...values.value,
      [key]: [...((values.value[key] as string[]) || []), result.file_id],
    };
    ElMessage.success('附件已上传并填入表单');
  });
}
async function query() {
  await run(async () => {
    if (queryNumber.value.trim()) {
      const result = await call<ApprovalRow>(
        queryIdentifier.value === 'request_id' ? 'approvals.track' : 'approvals.get',
        {
          [queryIdentifier.value]: queryNumber.value.trim(),
        },
      );
      queried.value = [result];
      queryPage.value = undefined;
      queryErrors.value = [];
    } else {
      const result = await call<{
        items: ApprovalRow[];
        page: Record<string, unknown>;
        errors?: unknown[];
      }>('approvals.collect', {
        ...(queryStatus.value ? { status: queryStatus.value } : {}),
        ...(queryRange.value?.length === 2
          ? { start_at: queryRange.value[0], end_at: queryRange.value[1] }
          : {}),
        include_details: true,
        max_items: 50,
        max_pages: 5,
      });
      queried.value = result.items;
      queryPage.value = result.page;
      queryErrors.value = result.errors || [];
    }
  });
}
async function pollEvents(advance = false) {
  await run(async () => {
    const result = await call<{ items: Record<string, unknown>[]; next_after_id: number }>(
      'approval_events.list',
      { after_id: advance ? eventNext.value : afterId.value, limit: 50 },
    );
    if (advance) afterId.value = eventNext.value;
    events.value = result.items;
    eventNext.value = Number(result.next_after_id);
  });
}
function advanced(operation: string) {
  void router.push({ path: '/playground', query: { operation: `wecom.${operation}` } });
}
function grants(operation?: unknown) {
  void router.push({
    path: '/grants',
    query: {
      system: system.value,
      platform: 'wecom',
      ...(typeof operation === 'string' ? { operation: `wecom.${operation}` } : {}),
    },
  });
}

const bindingDialog = ref(false);
const alias = ref('');
const bindingDescription = ref('');
const aliasDraft = ref<Record<string, string>>({});
function openBinding() {
  alias.value = '';
  bindingDescription.value = '';
  aliasDraft.value = Object.fromEntries(
    (form.value?.fields || [])
      .filter((f) => !f.read_only)
      .map((f) => [f.id, f.key === f.id ? '' : f.key]),
  );
  bindingDialog.value = true;
}
async function bind() {
  const entries = Object.entries(aliasDraft.value).filter(([, name]) => name.trim());
  if (new Set(entries.map(([, name]) => name)).size !== entries.length) {
    ElMessage.error('业务字段名不能重复');
    return;
  }
  await run(async () => {
    await call('approval_templates.bind', {
      template_id: form.value?.template_id,
      alias: alias.value.trim(),
      description: bindingDescription.value,
      field_aliases: Object.fromEntries(entries.map(([id, name]) => [name.trim(), id])),
    });
    bindingDialog.value = false;
    template.value = alias.value.trim();
    registered.value = (
      await call<{ items: typeof registered.value }>('approval_templates.list')
    ).items;
    ElMessage.success('已绑定，请重新加载表单使用业务字段名');
  });
}
onMounted(async () => {
  await run(async () => {
    const [s, meta] = await Promise.all([api.systems(), api.meta()]);
    systems.value = s.systems.filter((item) => item.enabled);
    system.value = systems.value[0]?.key || '';
    mock.value = meta.platforms.find((p) => p.platform === 'wecom')?.mock;
    environment.value = meta.env;
    if (mock.value) template.value = 'demo-expense';
  });
});
</script>
<template>
  <div class="approval-workbench">
    <div class="approval-tools">
      <p class="form-hint">
        按模板填写业务字段，直接调试企微审批接口；授权、凭据和调用记录沿用统一网关。
      </p>
      <el-button
        @click="router.push({ path: '/docs', query: { operation: 'wecom.approvals.submit' } })"
        >调用文档</el-button
      >
      <el-dropdown @command="advanced"
        ><el-button type="primary" :icon="Plus">更多审批能力</el-button
        ><template #dropdown
          ><el-dropdown-menu>
            <el-dropdown-item command="approval_templates.create">创建新模板</el-dropdown-item>
            <el-dropdown-item command="approval_templates.update">更新模板内容</el-dropdown-item>
            <el-dropdown-item command="approval_templates.clone">复制现有模板</el-dropdown-item>
            <el-dropdown-item command="vacation.config" divided
              >假期类型与余额管理</el-dropdown-item
            >
            <el-dropdown-item command="approvals.engine_launch">自建应用审批引擎</el-dropdown-item>
            <el-dropdown-item command="approvals.get_many">批量查询审批详情</el-dropdown-item>
          </el-dropdown-menu></template
        ></el-dropdown
      >
    </div>
    <section class="panel">
      <div class="panel-body approval-context">
        <div>
          <span class="approval-context-label">调用方系统</span
          ><el-select v-model="system" :disabled="busy" filterable placeholder="选择业务系统"
            ><el-option
              v-for="item in systems"
              :key="item.key"
              :value="item.key"
              :label="item.display_name"
          /></el-select>
        </div>
        <div class="approval-runtime">
          <el-tag :type="mock ? 'warning' : 'success'" effect="plain">{{
            mock === undefined ? '读取模式中' : mock ? '模拟模式' : '真实模式'
          }}</el-tag
          ><span class="subtext">{{ environment }} · 权限与凭据按调用方生效</span>
        </div>
        <el-button link type="primary" @click="grants"
          >配置授权<el-icon><Right /></el-icon
        ></el-button>
      </div>
    </section>
    <el-alert
      v-if="tab === 'apply' && allowed && !can('approval_templates.get')"
      title="当前系统还未开通审批模板读取权限"
      description="先在操作授权中开启模板读取、预检和所需查询能力；提交审批与修改模板需要额外的精确授权。"
      type="info"
      :closable="false"
      style="margin-bottom: 16px"
      ><template #default
        ><span>先配置所需审批权限。</span
        ><el-button link type="primary" @click="grants('approval_templates.get')"
          >前往授权</el-button
        ></template
      ></el-alert
    >
    <el-tabs v-model="tab" class="approval-tabs">
      <el-tab-pane label="发起审批" name="apply">
        <div class="approval-layout">
          <div>
            <section class="panel">
              <header class="panel-head">
                <h2><span class="approval-step">1</span>选择审批模板</h2>
                <el-button
                  link
                  :icon="Refresh"
                  :disabled="!system || busy || !can('approval_templates.list')"
                  @click="refreshTemplates"
                  >已绑定模板</el-button
                >
              </header>
              <div class="panel-body">
                <el-select
                  v-model="template"
                  :disabled="busy"
                  filterable
                  allow-create
                  default-first-option
                  clearable
                  placeholder="输入企微模板 ID 或业务别名"
                  style="width: 100%"
                  ><el-option
                    v-for="item in registered"
                    :key="item.alias"
                    :value="item.alias"
                    :label="`${item.alias} · ${item.description || item.template_id}`" /><el-option
                    v-if="mock"
                    value="demo-expense"
                    label="demo-expense · 内置费用申请示例"
                /></el-select>
                <div class="approval-inline-actions">
                  <el-button
                    type="primary"
                    :loading="busy"
                    :disabled="!system || !template.trim() || !can('approval_templates.get')"
                    @click="loadForm"
                    >加载表单</el-button
                  ><el-button
                    :disabled="!form || busy || !can('approval_templates.bind')"
                    @click="openBinding"
                    >绑定业务别名</el-button
                  >
                </div>
                <p class="form-hint">
                  从企微管理后台获取模板 ID，加载后即可填写。已绑定模板仅属于当前调用系统。
                </p>
              </div>
            </section>
            <section class="panel">
              <header class="panel-head">
                <h2><span class="approval-step">2</span>{{ form?.name || '填写审批内容' }}</h2>
                <el-tag v-if="form" effect="plain"
                  >{{ form.fields.filter((f) => !f.read_only).length }} 个字段</el-tag
                >
              </header>
              <div class="panel-body">
                <el-empty
                  v-if="!form"
                  description="先加载模板，自动生成可填写的表单"
                  :image-size="90"
                />
                <template v-else
                  ><el-form label-position="top"
                    ><el-form-item label="申请人 userid" required
                      ><el-input
                        v-model="applicant"
                        placeholder="企微成员账号，如 zhangsan" /></el-form-item
                  ></el-form>
                  <ApprovalFields
                    :key="`${system}:${loadedTemplate}:${formGeneration}`"
                    :disabled="busy"
                    v-model="values"
                    :fields="form.fields"
                    @invalid="invalidJson = $event"
                    @upload="upload"
                  />
                  <el-form label-position="top"
                    ><el-form-item label="业务请求编号" required
                      ><el-input
                        v-model="requestId"
                        maxlength="128"
                        placeholder="如 purchase-20261105-001；重试保持相同编号" /></el-form-item
                  ></el-form>
                  <p class="form-hint">
                    默认使用模板配置的审批人和流程。自定义流程、部门和摘要可在接口调试中设置。
                  </p>
                </template>
              </div>
            </section>
          </div>
          <aside>
            <section class="panel approval-submit-panel">
              <header class="panel-head">
                <h2><span class="approval-step">3</span>预检与提交</h2>
              </header>
              <div class="panel-body">
                <p class="approval-explanation">
                  提交前读取最新模板，校验字段、选项和必填项。预检不会生成审批单。
                </p>
                <el-button
                  :icon="DocumentChecked"
                  :loading="busy"
                  :disabled="!form || !applicant.trim() || invalidJson || !can('approvals.prepare')"
                  @click="prepare"
                  >预检申请</el-button
                >
                <el-alert
                  v-if="preflight"
                  :title="preflight.valid ? '预检通过' : '请修正以下问题'"
                  :type="preflight.valid ? 'success' : 'error'"
                  :closable="false"
                  style="margin-top: 16px"
                />
                <ul v-if="preflight && !preflight.valid" class="approval-errors">
                  <li
                    v-for="(issue, i) in preflight.errors as { field?: string; message: string }[]"
                    :key="i"
                  >
                    {{ issue.field ? `${issue.field}：` : '' }}{{ issue.message }}
                  </li>
                </ul>
                <el-button
                  type="primary"
                  :loading="busy"
                  :disabled="!canSubmit"
                  class="approval-submit-button"
                  @click="submit"
                  >{{
                    submitted ? '重试相同业务请求' : mock ? '提交模拟申请' : '提交审批申请'
                  }}</el-button
                >
                <p class="form-hint">
                  需要 wecom.approvals.submit 精确授权。结果不确定时，保留业务编号核对原单。
                </p>
                <el-button
                  v-if="allowed && !can('approvals.submit')"
                  link
                  type="primary"
                  @click="grants('approvals.submit')"
                  >为当前系统开通提交权限</el-button
                >
                <JsonViewer v-if="submitted" :value="submitted" />
              </div>
            </section>
            <section class="panel">
              <header class="panel-head"><h2>业务系统调用</h2></header>
              <div class="panel-body">
                <p class="form-hint">
                  POST /integrations/wecom/approvals/submit<br />Authorization: Bearer &lt;系统 API
                  Key&gt;
                </p>
                <JsonViewer :value="requestExample" />
                <p class="form-hint">金额保持字符串；不需要向调用方提供企微 Secret。</p>
              </div>
            </section>
          </aside>
        </div>
      </el-tab-pane>
      <el-tab-pane label="审批查询" name="query">
        <section class="panel">
          <div class="panel-body">
            <el-form label-position="top"
              ><div class="approval-query-filters">
                <el-form-item label="精确定位"
                  ><el-input v-model="queryNumber" clearable placeholder="留空按时间与状态查询"
                    ><template #prepend
                      ><el-select
                        v-model="queryIdentifier"
                        style="width: 125px"
                        aria-label="查询编号类型"
                        ><el-option label="审批单号" value="approval_no" /><el-option
                          label="业务请求号"
                          value="request_id" /></el-select></template></el-input
                ></el-form-item>
                <el-form-item label="提交时间"
                  ><el-date-picker
                    v-model="queryRange"
                    type="datetimerange"
                    value-format="YYYY-MM-DD HH:mm:ss"
                    start-placeholder="开始（北京时间）"
                    end-placeholder="结束"
                    :disabled="!!queryNumber"
                /></el-form-item>
                <el-form-item label="状态"
                  ><el-select
                    v-model="queryStatus"
                    clearable
                    placeholder="全部状态"
                    :disabled="!!queryNumber"
                    ><el-option
                      v-for="item in [
                        { value: 'pending', label: '审批中' },
                        { value: 'approved', label: '已通过' },
                        { value: 'rejected', label: '已驳回' },
                        { value: 'cancelled', label: '已撤销' },
                      ]"
                      :key="item.value"
                      :value="item.value"
                      :label="item.label" /></el-select
                ></el-form-item>
              </div>
              <el-button
                type="primary"
                :disabled="!system || !can(queryOperation)"
                :loading="busy"
                @click="query"
                >查询审批</el-button
              ><el-button
                v-if="allowed && !can(queryOperation)"
                link
                type="primary"
                @click="grants(queryOperation)"
                >定位并开通此查询权限</el-button
              ></el-form
            >
            <p class="form-hint">
              默认最近 7 天，页面最多汇总 50 条；更多数据可用 collect
              接口的游标续传，跨月范围自动拆分。
            </p>
          </div>
          <el-table :data="queried" empty-text="暂无查询结果"
            ><el-table-column prop="approval_no" label="审批单号" min-width="180" /><el-table-column
              prop="name"
              label="模板"
              min-width="150"
            /><el-table-column prop="status_label" label="状态" width="110" /><el-table-column
              label="操作"
              width="100"
              ><template #default="{ row }"
                ><el-button link type="primary" @click="detail = row">查看详情</el-button></template
              ></el-table-column
            ></el-table
          >
          <div v-if="queryPage" class="panel-body">
            <el-alert
              v-if="queryErrors.length"
              :title="`${queryErrors.length} 条审批详情未能读取，已保留失败单号`"
              type="warning"
              :closable="false"
            />
            <JsonViewer v-if="queryErrors.length" :value="queryErrors" />
            <el-alert
              v-if="queryPage.has_more"
              title="还有数据未展示，可前往接口调试使用续传游标"
              type="warning"
              :closable="false"
            /><el-collapse
              ><el-collapse-item title="分页与完整性信息"
                ><JsonViewer :value="queryPage" /></el-collapse-item
            ></el-collapse>
          </div>
        </section>
      </el-tab-pane>
      <el-tab-pane label="结果事件" name="events">
        <section class="panel">
          <header class="panel-head">
            <div>
              <h2>已验签的审批事件</h2>
              <p>Webhook 与主动拉取可以同时使用；业务系统按 event_id 去重。</p>
            </div>
            <el-button @click="router.push('/callbacks')">配置结果推送</el-button>
          </header>
          <div class="panel-body approval-event-toolbar">
            <span>从事件 ID 之后读取</span
            ><el-input-number v-model="afterId" :min="0" :precision="0" /><el-button
              type="primary"
              :loading="busy"
              :disabled="!system || !can('approval_events.list')"
              @click="pollEvents()"
              >拉取事件</el-button
            ><el-button :disabled="!eventNext || busy" @click="pollEvents(true)">下一批</el-button>
          </div>
          <el-table :data="events" empty-text="尚无审批回调事件"
            ><el-table-column prop="id" label="ID" width="90" /><el-table-column
              prop="approval_no"
              label="审批单号"
              min-width="180"
            /><el-table-column prop="delivery_state" label="推送状态" width="140" /><el-table-column
              prop="attempts"
              label="尝试次数"
              width="100"
            /><el-table-column label="事件" width="100"
              ><template #default="{ row }"
                ><el-button link type="primary" @click="detail = row">查看</el-button></template
              ></el-table-column
            ></el-table
          >
          <div class="panel-body">
            <p class="form-hint">在企微后台配置接收地址：</p>
            <code class="approval-callback-path">{{ callbackPath }}</code>
            <p class="form-hint">
              同时设置回调
              Token、EncodingAESKey。事件加密入库后即应答，推送失败自动重试；没有推送地址时可直接拉取。共享应用中，网关提交的审批按原调用系统分发。
            </p>
          </div>
        </section>
      </el-tab-pane>
    </el-tabs>
    <el-collapse class="panel approval-help"
      ><el-collapse-item title="接入准备与能力边界" name="setup"
        ><div class="approval-help-links">
          <el-button link type="primary" @click="router.push('/credentials')"
            >配置企微凭据</el-button
          ><el-button link type="primary" @click="router.push('/grants')">配置操作授权</el-button
          ><el-button link type="primary" @click="advanced('vacation.get')">查询假期余额</el-button
          ><el-button link type="primary" @click="advanced('approval_events.retry')"
            >重试失败事件</el-button
          >
        </div>
        <p>
          真实调用需要企业自建应用拥有相应审批数据和模板权限。模板的审批流程及规则请在企微后台配置。列表展示已注册的业务模板；企微没有列出企业全部模板的服务端接口。
        </p>
        <p>
          同意、驳回、撤销由企微客户端和对应流程处理。自建应用审批引擎需在客户端调用
          JS-SDK，生成参数不会直接提单。更多复杂控件支持业务 JSON 或高级官方值。
        </p></el-collapse-item
      ></el-collapse
    >
    <el-dialog v-model="bindingDialog" title="绑定业务模板与字段名" width="min(620px, 92vw)"
      ><el-form label-position="top"
        ><el-form-item label="业务模板别名" required
          ><el-input
            v-model="alias"
            placeholder="如 purchase、expense"
            maxlength="64" /></el-form-item
        ><el-form-item label="用途说明"
          ><el-input v-model="bindingDescription" maxlength="255" /></el-form-item
        ><el-form-item
          v-for="field in form?.fields.filter((f) => !f.read_only)"
          :key="field.id"
          :label="`${field.label} · ${field.id}`"
          ><el-input
            v-model="aliasDraft[field.id]"
            placeholder="可选，如 reason、amount（字母开头）"
            maxlength="64" /></el-form-item
      ></el-form>
      <p class="form-hint">
        只设置当前调用系统的别名，不修改企微模板。字段名需唯一，支持字母、数字和下划线。
      </p>
      <template #footer
        ><el-button @click="bindingDialog = false">取消</el-button
        ><el-button type="primary" :disabled="!alias.trim()" :loading="busy" @click="bind"
          >保存绑定</el-button
        ></template
      ></el-dialog
    >
    <el-drawer
      :model-value="!!detail"
      title="审批详情"
      size="min(720px, 94vw)"
      @close="detail = undefined"
      ><JsonViewer :value="detail"
    /></el-drawer>
  </div>
</template>
<style scoped>
.approval-tools {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 18px;
}
.approval-tools > p {
  flex: 1;
  min-width: 220px;
}
.approval-context {
  display: flex;
  align-items: center;
  gap: 24px;
  flex-wrap: wrap;
}
.approval-context > div:first-child {
  display: flex;
  align-items: center;
  gap: 12px;
}
.approval-context :deep(.el-select) {
  width: 240px;
}
.approval-context-label {
  font-weight: 600;
  white-space: nowrap;
}
.approval-runtime {
  display: flex;
  align-items: center;
  gap: 10px;
  flex: 1;
  flex-wrap: wrap;
}
.approval-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.45fr) minmax(300px, 1fr);
  gap: 20px;
  align-items: start;
}
.approval-layout > div,
.approval-layout > aside {
  min-width: 0;
}
.approval-step {
  display: inline-grid;
  place-items: center;
  width: 25px;
  height: 25px;
  margin-right: 9px;
  border-radius: 50%;
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  font-size: 13px;
}
.approval-inline-actions {
  display: flex;
  gap: 8px;
  margin-top: 16px;
  flex-wrap: wrap;
}
.approval-inline-actions .el-button + .el-button {
  margin-left: 0;
}
.approval-submit-button {
  margin-top: 20px;
  width: 100%;
}
.approval-explanation {
  margin-top: 0;
  line-height: 1.7;
  color: var(--app-text-secondary);
}
.approval-errors {
  padding-left: 20px;
  color: var(--el-color-danger);
  font-size: 13px;
  line-height: 1.8;
}
.approval-query-filters {
  display: grid;
  grid-template-columns: 1fr 1.8fr 0.8fr;
  gap: 16px;
}
.approval-query-filters :deep(.el-date-editor) {
  width: 100%;
}
.approval-event-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.approval-callback-path {
  display: block;
  overflow-wrap: anywhere;
  padding: 12px;
  background: var(--el-fill-color-light);
  border-radius: 6px;
  font-size: 12px;
}
.approval-help {
  padding: 0 20px;
}
.approval-help p {
  color: var(--app-text-secondary);
  line-height: 1.8;
  margin: 12px 0;
}
.approval-help-links {
  display: flex;
  gap: 18px;
  flex-wrap: wrap;
}
.approval-help-links .el-button {
  margin-left: 0;
}
@media (max-width: 1100px) {
  .approval-layout {
    grid-template-columns: 1fr;
  }
  .approval-query-filters {
    grid-template-columns: 1fr;
    gap: 0;
  }
}
@media (max-width: 600px) {
  .approval-context {
    gap: 14px;
  }
  .approval-context > div:first-child {
    width: 100%;
  }
  .approval-context :deep(.el-select) {
    flex: 1;
    width: 0;
  }
  .approval-runtime {
    flex-basis: 100%;
  }
}
</style>
