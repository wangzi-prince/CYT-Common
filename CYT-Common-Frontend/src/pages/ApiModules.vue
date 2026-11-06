<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router';
import { Plus, Search, Refresh, Delete } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { api } from '~/api';
import type { CustomApi, PlatformModule } from '~/api/types';
import { operationModules } from '~/utils/operation-modules';
import { useRequest, useAction, confirm } from '~/composables/useRequest';
import {
  gatewayPath,
  readParameters,
  writeParameters,
  templateParameters,
  splitEndpoint,
  type ParameterDraft,
} from '~/utils/authoring';
import PageHeader from '~/components/PageHeader.vue';
import PageState from '~/components/PageState.vue';
import SchemaFields from '~/components/SchemaFields.vue';
const route = useRoute();
const router = useRouter();
const { data, loading, error, load } = useRequest(async () => {
  const [modules, apis, platforms, registry] = await Promise.all([
    api.modules(),
    api.customApis(),
    api.platforms(),
    api.registry(),
  ]);
  return {
    modules: modules.items,
    apis: apis.items,
    platforms: platforms.platforms,
    operations: registry.operations,
  };
});
const { busy, run } = useAction();
const tab = ref<'modules' | 'apis'>(route.query.tab === 'modules' ? 'modules' : 'apis');
const q = ref('');
const moduleDialog = ref(false);
const apiDialog = ref(false);
const connectionMode = ref('http');
const step = ref(0);
const formError = ref('');
const scope = ref('standalone');
const fullEndpoint = ref('');
const parameterPlaceholder = '{{params.参数名}}';
const rawMode = ref(false);
const rawParameters = ref('[]');
const parameters = ref<ParameterDraft[]>([]);
const advanced = ref<string[]>([]);
const initial = ref('');
const lastIntent = ref('');
const saved = ref<{
  kind: 'modules' | 'custom-apis';
  id: number;
  key: string;
  enabled: boolean;
  moduleKey: string;
}>();
const emptyModule = () => ({
  id: undefined as number | undefined,
  key: '',
  name: '',
  description: '',
  service_kind: route.query.kind === 'internal' ? 'internal' : 'third_party',
  base_url: '',
  auth_type: 'none',
  auth_name: '',
  secret_ref: '',
  token_url: '',
  client_id_ref: '',
  client_secret_ref: '',
  refresh_token_ref: '',
  scopes: '',
  headers_json: '{}',
  timeout_s: 30,
  enabled: true,
});
const emptyApi = () => ({
  id: undefined as number | undefined,
  key: '',
  name: '',
  description: '',
  module_key: '',
  base_url: '',
  method: 'GET',
  path: '/',
  auth_type: 'none',
  auth_name: '',
  secret_ref: '',
  headers_json: '{}',
  body_template: '',
  params_schema_json: '[]',
  response_path: '',
  response_schema_json: '{}',
  pagination_json: '{}',
  retry_max: 1,
  timeout_s: 30,
  sensitive: false,
  enabled: false,
  category: '',
  sort_order: 0,
});
const moduleForm = reactive(emptyModule());
const moduleTitle = computed(() =>
  moduleForm.service_kind === 'internal'
    ? moduleForm.id
      ? '编辑提供方连接'
      : '登记提供方'
    : moduleForm.id
      ? '编辑第三方服务连接'
      : '接入第三方服务',
);
const moduleNamePlaceholder = computed(() =>
  moduleForm.service_kind === 'internal' ? '例如：订单系统' : '例如：合作伙伴服务',
);
const moduleKeyPlaceholder = computed(() =>
  moduleForm.service_kind === 'internal' ? '例如：order_service' : '例如：partner_service',
);
const moduleBasePlaceholder = computed(() =>
  moduleForm.service_kind === 'internal'
    ? 'https://orders.internal.example'
    : 'https://www.googleapis.com/youtube/v3',
);
const apiForm = reactive(emptyApi());
const apiCategories = computed(() => {
  const prefix = scope.value.split(':')[1] || apiForm.key.split('.')[0];
  return operationModules(
    [...(data.value?.operations || []), ...(data.value?.apis || [])].filter(
      (operation) =>
        operation.category?.trim() && (!prefix || operation.key.startsWith(prefix + '.')),
    ),
  ).map((module) => module.name);
});
const selectedModule = computed(() =>
  data.value?.modules.find((m) => m.key === apiForm.module_key),
);
const internalContext = computed(
  () =>
    route.query.kind === 'internal' ||
    route.query.direction === 'internal' ||
    selectedModule.value?.service_kind === 'internal',
);
const responsePreview = computed(() => {
  try {
    return JSON.parse(apiForm.response_schema_json || '{}');
  } catch {
    return {};
  }
});
const builtinPlatforms = computed(() =>
  (data.value?.platforms || []).filter(
    (p) => !data.value?.modules.some((m) => m.key === p.platform),
  ),
);
const contextModules = computed(() =>
  (data.value?.modules || []).filter(
    (m) => (m.service_kind === 'internal') === internalContext.value,
  ),
);
const contextApis = computed(() =>
  (data.value?.apis || []).filter(
    (a) =>
      (data.value?.modules.some((m) => m.key === a.module_key && m.service_kind === 'internal') ||
        false) === internalContext.value,
  ),
);
const items = computed(
  () =>
    (tab.value === 'modules' ? contextModules.value : contextApis.value).filter((r) =>
      (r.key + ' ' + r.name + ' ' + r.description)
        .toLowerCase()
        .includes(q.value.trim().toLowerCase()),
    ) || [],
);
const endpointPreview = computed(
  () =>
    (apiForm.base_url || selectedModule.value?.base_url || '').replace(/\/$/, '') + apiForm.path,
);
const snapshot = () =>
  JSON.stringify(
    moduleDialog.value
      ? moduleForm
      : {
          form: apiForm,
          parameters: parameters.value,
          rawMode: rawMode.value,
          raw: rawParameters.value,
        },
  );
const dirty = computed(
  () => (moduleDialog.value || apiDialog.value) && !!initial.value && snapshot() !== initial.value,
);
function recordInitial() {
  initial.value = snapshot();
}
async function mayDiscard() {
  return !dirty.value || (await confirm('未保存的修改将丢失，确认离开？', '放弃修改'));
}
async function closeModule(done?: () => void) {
  if (!busy.value && (await mayDiscard())) {
    moduleDialog.value = false;
    initial.value = '';
    done?.();
  }
}
async function closeApi(done?: () => void) {
  if (!busy.value && (await mayDiscard())) {
    apiDialog.value = false;
    initial.value = '';
    done?.();
  }
}
onBeforeRouteLeave(async () => {
  if (busy.value) {
    ElMessage.info('正在保存配置，请稍候再切换页面');
    return false;
  }
  return mayDiscard();
});
function resetEditor() {
  formError.value = '';
  advanced.value = [];
  step.value = 0;
  rawMode.value = false;
  fullEndpoint.value = '';
}
function suggestKey() {
  if (apiForm.id) return;
  const prefix = scope.value.split(':')[1] || apiForm.key.split('.')[0] || 'custom';
  const resource =
    apiForm.path.split('/').filter((p) => /^[a-z][a-z0-9_]*$/.test(p) && !/^v\d+$/.test(p))[0] ||
    'resources';
  const action =
    (
      { GET: 'list', POST: 'create', PUT: 'update', PATCH: 'update', DELETE: 'delete' } as Record<
        string,
        string
      >
    )[apiForm.method] || 'call';
  apiForm.key = prefix + '.' + resource + '.' + action;
}
function setScope(value: string, suggest = true) {
  scope.value = value;
  apiForm.module_key = value.startsWith('module:') ? value.slice(7) : '';
  apiForm.auth_type = apiForm.module_key ? 'inherit' : 'none';
  if (suggest && !apiForm.id) suggestKey();
}
function editModule(row?: PlatformModule) {
  resetEditor();
  connectionMode.value = 'http';
  Object.assign(moduleForm, emptyModule(), row || {});
  moduleForm.enabled = row ? Boolean(row.enabled) : true;
  moduleDialog.value = true;
  apiDialog.value = false;
  recordInitial();
}
function editApi(row?: CustomApi, preferredPlatform = String(route.query.platform || '')) {
  resetEditor();
  Object.assign(apiForm, emptyApi(), row || {});
  apiForm.response_schema_json = String(row?.response_schema_json || '{}');
  apiForm.enabled = row ? Boolean(row.enabled) : false;
  apiForm.sensitive = row ? Boolean(row.sensitive) : false;
  rawParameters.value = String(apiForm.params_schema_json || '[]');
  try {
    parameters.value = readParameters(rawParameters.value);
  } catch {
    parameters.value = [];
    rawMode.value = true;
  }
  const moduleKey =
    row?.module_key ||
    contextModules.value.find((m) => m.key === preferredPlatform)?.key ||
    (!row && internalContext.value ? contextModules.value[0]?.key : '');
  scope.value = moduleKey
    ? 'module:' + moduleKey
    : (
          row
            ? builtinPlatforms.value.some((p) => p.platform === row.key.split('.')[0])
            : !!preferredPlatform
        )
      ? 'builtin:' + (row?.key.split('.')[0] || preferredPlatform)
      : 'standalone';
  if (!row) {
    apiForm.category = String(route.query.category || '');
    setScope(scope.value, false);
    if (scope.value !== 'standalone') suggestKey();
  }
  apiDialog.value = true;
  moduleDialog.value = false;
  recordInitial();
}
function create() {
  if (tab.value === 'modules') editModule();
  else editApi();
}
function handleIntent() {
  if (route.path !== '/modules') return;
  if (!data.value || lastIntent.value === route.fullPath) return;
  lastIntent.value = route.fullPath;
  tab.value = route.query.tab === 'modules' ? 'modules' : 'apis';
  if (route.query.edit) {
    const id = Number(route.query.edit);
    const row =
      tab.value === 'modules'
        ? data.value.modules.find((m) => m.id === id)
        : data.value.apis.find((a) => a.id === id);
    if (!row) {
      ElMessage.warning('该配置已不存在，请刷新列表');
      return;
    }
    if (tab.value === 'modules') editModule(row as PlatformModule);
    else editApi(row as CustomApi);
  } else if (route.query.action === 'create') create();
}
onMounted(async () => {
  await load();
  handleIntent();
});
watch(() => route.fullPath, handleIntent);
watch(tab, () => {
  q.value = '';
});
watch(
  () => apiForm.method,
  (method) => {
    if (apiDialog.value && !apiForm.id && method !== 'GET') apiForm.sensitive = true;
  },
);
function fail(message: string) {
  formError.value = message;
  return false;
}
function jsonObject(value: string, label: string) {
  let parsed: unknown;
  try {
    parsed = JSON.parse(value);
  } catch {
    throw new Error(label + '不是合法 JSON');
  }
  if (!parsed || Array.isArray(parsed) || typeof parsed !== 'object')
    throw new Error(label + '必须是 JSON 对象');
}
function https(value: string, label: string) {
  let url: URL;
  try {
    url = new URL(value);
  } catch {
    throw new Error(label + '必须是完整 HTTPS 地址');
  }
  if (
    (url.protocol !== 'https:' &&
      !(
        url.protocol === 'http:' &&
        (moduleDialog.value
          ? moduleForm.service_kind === 'internal'
          : selectedModule.value?.service_kind === 'internal')
      )) ||
    !url.hostname ||
    url.username ||
    url.password ||
    url.hash ||
    url.search
  )
    throw new Error(
      label + '需使用 HTTPS（自有系统可使用已获准的内网 HTTP），且不能包含账号密码、查询参数或片段',
    );
}
function credentials(form: typeof moduleForm | typeof apiForm) {
  for (const name of ['secret_ref', 'client_id_ref', 'client_secret_ref', 'refresh_token_ref']) {
    const value = String((form as Record<string, unknown>)[name] || '');
    if (value && !/^CUSTOM_[A-Z0-9_]{1,57}$/.test(value))
      throw new Error('凭据引用需以 CUSTOM_ 开头，后面使用大写字母、数字和下划线');
  }
  if (['bearer', 'header', 'query', 'basic'].includes(form.auth_type) && !form.secret_ref)
    throw new Error('请选择鉴权凭据引用');
  if (['header', 'query'].includes(form.auth_type) && !form.auth_name.trim())
    throw new Error('请填写 Header 或 Query 参数名称');
  if (form.auth_type === 'oauth2_refresh' && 'token_url' in form) {
    https(form.token_url, 'Token 地址');
    if (!form.client_id_ref || !form.client_secret_ref || !form.refresh_token_ref)
      throw new Error('请填写完整的 OAuth2 凭据引用');
  }
}
function syncParameters() {
  if (rawMode.value) parameters.value = readParameters(rawParameters.value);
  apiForm.params_schema_json = writeParameters(parameters.value);
}
function validateStep(index: number) {
  formError.value = '';
  try {
    if (index === 0) {
      apiForm.key = apiForm.key.trim();
      apiForm.name = apiForm.name.trim();
      apiForm.base_url = apiForm.base_url.trim();
      apiForm.path = apiForm.path.trim();
      apiForm.category = apiForm.category.trim();
      if (apiForm.category.length > 64) throw new Error('业务模块名称不能超过 64 个字符');
      if (
        !/^[a-z][a-z0-9_]*\.[a-z][a-z0-9_]*\.[a-z][a-z0-9_]*$/.test(apiForm.key) ||
        apiForm.key.length > 128
      )
        throw new Error(
          '操作标识使用「平台.资源.动作」，如 youtube.videos.list；每段以小写字母开头',
        );
      if (!apiForm.name) throw new Error('请填写接口名称，让目录中的用途容易辨认');
      if (
        data.value?.operations.some((o) => o.key === apiForm.key) &&
        !data.value?.apis.some((a) => a.id === apiForm.id && a.key === apiForm.key)
      )
        throw new Error('操作标识与已注册接口重复');
      if (data.value?.apis.some((a) => a.key === apiForm.key && a.id !== apiForm.id))
        throw new Error('该操作标识已被使用，包括未启用的接口');
      if (apiForm.module_key && !apiForm.key.startsWith(apiForm.module_key + '.'))
        throw new Error('操作标识必须以所选平台「' + apiForm.module_key + '.」开头');
      https(apiForm.base_url || selectedModule.value?.base_url || '', '基础地址');
      if (
        !apiForm.path.startsWith('/') ||
        apiForm.path.startsWith('//') ||
        /[\\?#]/.test(apiForm.path)
      )
        throw new Error('接口路径需以单个 / 开头；查询参数请在参数表中定义');
    } else if (index === 1) {
      jsonObject(apiForm.response_schema_json, '业务响应契约');
      if (
        apiForm.response_schema_json.trim() !== '{}' &&
        data.value?.operations.some((o) => !o.integration_kind)
      )
        throw new Error('当前运行后端尚未加载响应契约，请更新并重启服务后保存，避免丢失响应字段。');
      syncParameters();
      if (apiForm.body_template.trim()) {
        try {
          JSON.parse(apiForm.body_template);
        } catch {
          throw new Error('请求体模板不是合法 JSON，参数占位符应放在双引号内');
        }
      }
      const names = new Set(parameters.value.map((p) => p.name.trim()));
      const missing = templateParameters(
        apiForm.path,
        apiForm.body_template,
        apiForm.headers_json,
      ).filter((p) => !names.has(p));
      if (missing.length)
        throw new Error(
          '模板引用了未定义的参数：' + missing.join('、') + '。请点击「从模板补全参数」',
        );
    } else {
      credentials(apiForm);
      jsonObject(apiForm.headers_json, '固定 Header');
      jsonObject(apiForm.pagination_json, '分页配置');
      if (apiForm.enabled && selectedModule.value && !selectedModule.value.enabled)
        throw new Error('所属平台已停用，请先启用服务连接，或暂存为未启用接口');
    }
    return true;
  } catch (error) {
    return fail(error instanceof Error ? error.message : '请检查配置');
  }
}
function next() {
  if (validateStep(step.value)) step.value++;
}
function toggleRaw() {
  try {
    if (rawMode.value) {
      parameters.value = readParameters(rawParameters.value);
      writeParameters(parameters.value);
    } else
      rawParameters.value = JSON.stringify(JSON.parse(writeParameters(parameters.value)), null, 2);
    rawMode.value = !rawMode.value;
    formError.value = '';
  } catch (error) {
    fail((error as Error).message);
  }
}
function addParameter() {
  parameters.value.push({
    name: '',
    type: 'string',
    required: false,
    description: '',
    defaultText: '',
  });
}
function inferParameters() {
  try {
    if (rawMode.value) {
      parameters.value = readParameters(rawParameters.value);
      rawMode.value = false;
    }
    const names = templateParameters(apiForm.path, apiForm.body_template, apiForm.headers_json);
    const missing = names.filter((name) => !parameters.value.some((p) => p.name === name));
    parameters.value.push(
      ...missing.map((name) => ({
        name,
        type: 'string',
        required: true,
        description: '',
        defaultText: '',
      })),
    );
    ElMessage.success(
      missing.length
        ? '已补全 ' + missing.length + ' 个参数，请确认类型和必填项'
        : '模板中的参数均已定义',
    );
  } catch (error) {
    fail((error as Error).message);
  }
}
function fillEndpoint() {
  try {
    const endpoint = splitEndpoint(fullEndpoint.value.trim());
    apiForm.base_url = endpoint.base;
    apiForm.path = endpoint.path;
    if (!apiForm.key) suggestKey();
    formError.value = '';
  } catch (error) {
    fail((error as Error).message);
  }
}
async function saveModule() {
  formError.value = '';
  try {
    moduleForm.key = moduleForm.key.trim();
    moduleForm.name = moduleForm.name.trim();
    moduleForm.base_url = moduleForm.base_url.trim();
    if (!/^[a-z][a-z0-9_]{1,31}$/.test(moduleForm.key))
      throw new Error('平台标识使用 2–32 个小写字母、数字或下划线，以字母开头');
    if (!moduleForm.name) throw new Error('请填写平台名称');
    if (
      builtinPlatforms.value.some((p) => p.platform === moduleForm.key) ||
      data.value?.modules.some((m) => m.key === moduleForm.key && m.id !== moduleForm.id)
    )
      throw new Error('平台标识已被使用，请使用一个新的标识');
    https(moduleForm.base_url, '基础地址');
    credentials(moduleForm);
    jsonObject(moduleForm.headers_json, '固定 Header');
  } catch (error) {
    fail((error as Error).message);
    return;
  }
  const result = await run(() => api.saveResource('modules', { ...moduleForm }));
  if (!result) return;
  moduleDialog.value = false;
  initial.value = '';
  saved.value = {
    kind: 'modules',
    id: result.id,
    key: moduleForm.key,
    enabled: moduleForm.enabled,
    moduleKey: moduleForm.key,
  };
  await load();
}
async function saveApi() {
  for (let index = 0; index < 3; index++)
    if (!validateStep(index)) {
      step.value = index;
      return;
    }
  const result = await run(() => api.saveResource('custom-apis', { ...apiForm }));
  if (!result) return;
  apiDialog.value = false;
  initial.value = '';
  saved.value = {
    kind: 'custom-apis',
    id: result.id,
    key: apiForm.key,
    enabled: apiForm.enabled,
    moduleKey: apiForm.module_key,
  };
  await load();
}
async function enableAndDebug() {
  if (!saved.value || saved.value.kind !== 'custom-apis') return;
  const row = data.value?.apis.find((a) => a.id === saved.value?.id);
  if (!row) return;
  if (row.module_key && !data.value?.modules.find((m) => m.key === row.module_key)?.enabled) {
    ElMessage.warning('请先在平台连接中启用所属平台');
    return;
  }
  const result =
    row.enabled || (await run(() => api.saveResource('custom-apis', { ...row, enabled: true })));
  if (result)
    void router.push({
      path: '/playground',
      query: { operation: row.key, direction: internalContext.value ? 'internal' : 'third_party' },
    });
}
function addForSavedPlatform() {
  const key = saved.value?.key || '';
  saved.value = undefined;
  tab.value = 'apis';
  editApi(undefined, key);
}
async function remove(row: PlatformModule | CustomApi) {
  if (
    !(await confirm(
      '确认删除「' +
        (row.name || row.key) +
        '」？建议优先编辑并停用，保留历史配置。平台下存在接口时需要先移除接口。',
    ))
  )
    return;
  const result = await run(() =>
    api.deleteResource(tab.value === 'modules' ? 'modules' : 'custom-apis', row.id),
  );
  if (result) await load();
}
</script>
<template>
  <div>
    <PageHeader
      :title="internalContext ? '对外接口配置' : '第三方服务与接口配置'"
      :description="
        internalContext ? '配置提供方连接、请求参数与响应字段' : '管理第三方服务连接与接口配置'
      "
    >
      <el-button @click="router.push(internalContext ? '/exposed' : '/platforms')">{{
        internalContext ? '返回对外能力' : '返回服务接入'
      }}</el-button>
      <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      <el-button type="primary" :icon="Plus" @click="create">{{
        tab === 'modules' ? (internalContext ? '登记提供方' : '接入新平台') : '新增接口'
      }}</el-button>
    </PageHeader>
    <div v-if="!internalContext" class="workflow-strip">
      <span><b>平台连接</b> 访问哪里、怎样鉴权</span><i>→</i
      ><span><b>接口定义</b> 做什么、传哪些参数</span><i>→</i
      ><span><b>调用授权</b> 哪个业务系统可以使用</span>
    </div>
    <section class="panel">
      <div class="panel-toolbar">
        <el-radio-group v-model="tab" aria-label="配置类型"
          ><el-radio-button value="modules"
            >{{ internalContext ? '提供方连接' : '平台连接' }}
            {{ contextModules.length }}</el-radio-button
          ><el-radio-button value="apis"
            >{{ internalContext ? '对外接口' : 'HTTP 接口' }}
            {{ contextApis.length }}</el-radio-button
          ></el-radio-group
        >
        <el-input
          v-model="q"
          :prefix-icon="Search"
          placeholder="搜索名称、标识或说明"
          clearable
          style="margin-left: auto"
        />
      </div>
      <PageState :loading="loading" :error="error" @retry="load">
        <el-empty
          v-if="!items.length"
          :description="
            q
              ? '没有匹配的配置'
              : tab === 'modules'
                ? internalContext
                  ? '登记提供方，配置系统地址与鉴权方式'
                  : '先添加一个平台连接，多个接口即可共享地址和鉴权'
                : internalContext
                  ? '添加需要开放的业务接口'
                  : '添加一个 HTTP 接口，保存后会出现在操作目录中'
          "
          ><el-button type="primary" @click="create">{{
            tab === 'modules' ? (internalContext ? '登记提供方' : '接入新平台') : '新增接口'
          }}</el-button></el-empty
        >
        <el-table v-else :data="items" row-key="id">
          <el-table-column label="名称 / 标识" min-width="230"
            ><template #default="{ row }"
              ><strong>{{ row.name || row.key }}</strong>
              <p class="subtext mono">{{ row.key }}</p></template
            ></el-table-column
          >
          <el-table-column v-if="tab === 'modules'" label="基础地址" min-width="230"
            ><template #default="{ row }"
              ><span class="mono">{{ row.base_url }}</span></template
            ></el-table-column
          >
          <el-table-column v-else label="上游请求" min-width="230"
            ><template #default="{ row }"
              ><el-tag size="small" effect="plain">{{ row.method }}</el-tag
              ><span class="mono" style="margin-left: 10px">{{ row.path }}</span></template
            ></el-table-column
          >
          <el-table-column
            v-if="tab === 'modules'"
            prop="operation_count"
            label="接口数"
            width="80"
          />
          <el-table-column v-else :label="internalContext ? '提供方' : '平台连接'" width="130"
            ><template #default="{ row }">{{
              data?.modules.find((m) => m.key === row.module_key)?.name ||
              row.module_key ||
              '独立配置'
            }}</template></el-table-column
          >
          <el-table-column label="状态" width="110"
            ><template #default="{ row }"
              ><el-tag :type="row.enabled ? 'success' : 'info'" effect="plain">{{
                row.enabled ? '已启用' : '未启用'
              }}</el-tag></template
            ></el-table-column
          >
          <el-table-column label="操作" width="190"
            ><template #default="{ row }"
              ><el-button
                link
                type="primary"
                @click="tab === 'modules' ? editModule(row) : editApi(row)"
                >编辑</el-button
              ><el-button
                v-if="tab === 'modules'"
                link
                type="primary"
                @click="
                  tab = 'apis';
                  editApi(undefined, row.key);
                "
                >新增接口</el-button
              ><el-button
                v-else
                link
                type="primary"
                @click="
                  router.push({
                    path: internalContext ? '/exposed' : '/registry',
                    query: {
                      operation: row.key,
                      ...(internalContext ? {} : { platform: row.key.split('.')[0] }),
                    },
                  })
                "
                >查看详情</el-button
              ><el-button link type="danger" @click="remove(row)">删除</el-button></template
            ></el-table-column
          >
        </el-table>
      </PageState>
    </section>
    <el-alert
      v-if="!internalContext"
      type="info"
      :closable="false"
      title="什么时候用 HTTP 配置，什么时候需要适配器？"
      description="固定 REST 地址与 Bearer、API Key、Basic、OAuth2 刷新令牌可直接配置；复杂签名、SDK、文件协议或多接口编排需要开发平台适配器，完成后统一展示在操作目录。"
    />
    <el-dialog
      v-model="moduleDialog"
      :title="moduleTitle"
      width="min(720px, 96vw)"
      :before-close="closeModule"
      :close-on-click-modal="false"
    >
      <el-radio-group
        v-if="!moduleForm.id && moduleForm.service_kind !== 'internal'"
        v-model="connectionMode"
        class="authoring-mode"
        ><el-radio-button value="http">通过 HTTP 配置接入</el-radio-button
        ><el-radio-button value="adapter">需要开发平台适配器</el-radio-button></el-radio-group
      >
      <div v-if="connectionMode === 'adapter'" class="adapter-guide">
        <h3>适合复杂平台协议与业务编排</h3>
        <p>例如每次请求签名、平台 SDK、特殊文件上传、多个接口串联，单靠 HTTP 表单不能完整实现。</p>
        <ol>
          <li>准备平台文档、鉴权方式和要开放的操作。</li>
          <li>开发适配器，声明参数、敏感级别、配额和模拟行为。</li>
          <li>完成测试并发布服务，操作自动进入目录，再为调用系统授权。</li>
        </ol>
        <el-alert
          type="info"
          title="这里不会创建一个无法执行的空平台"
          description="如果只是普通 REST 请求，请切回 HTTP 配置；已有内置平台可以直接在目录中发现接口。"
          :closable="false"
        />
        <el-button
          @click="
            moduleDialog = false;
            router.push({ path: '/registry', query: { source: 'builtin' } });
          "
          >查看内置适配器</el-button
        >
      </div>
      <el-form v-else label-position="top" class="authoring-form">
        <div class="form-grid">
          <el-form-item v-if="!internalContext" label="服务来源"
            ><el-radio-group v-model="moduleForm.service_kind"
              ><el-radio-button value="third_party">第三方服务</el-radio-button
              ><el-radio-button value="internal">自有系统</el-radio-button></el-radio-group
            >
          </el-form-item>
          <el-form-item label="服务名称" required
            ><el-input
              v-model="moduleForm.name"
              maxlength="64"
              :placeholder="moduleNamePlaceholder" /></el-form-item
          ><el-form-item label="服务标识" required
            ><el-input
              v-model="moduleForm.key"
              :disabled="!!moduleForm.id"
              maxlength="32"
              :placeholder="moduleKeyPlaceholder"
            />
            <p class="subtext">接口标识将以此开头，创建后不可修改。</p></el-form-item
          >
        </div>
        <el-form-item label="基础地址" required
          ><el-input v-model="moduleForm.base_url" :placeholder="moduleBasePlaceholder" />
          <p class="subtext">
            同一平台的接口共享此地址；保存会校验域名，不会测试真实鉴权。
            <span v-if="moduleForm.service_kind === 'internal'"
              >内部网络地址需由部署管理员加入可信服务来源。</span
            >
          </p></el-form-item
        >
        <el-form-item label="服务用途"
          ><el-input
            v-model="moduleForm.description"
            maxlength="255"
            placeholder="说明提供哪些业务能力"
        /></el-form-item>
        <div class="form-grid">
          <el-form-item label="连接鉴权"
            ><el-select v-model="moduleForm.auth_type"
              ><el-option label="无需鉴权" value="none" /><el-option
                label="Bearer Token"
                value="bearer" /><el-option
                label="自定义 Header / API Key"
                value="header" /><el-option label="Basic（用户名:密码）" value="basic" /><el-option
                label="Query 参数"
                value="query" /><el-option
                label="OAuth2 刷新令牌"
                value="oauth2_refresh" /></el-select></el-form-item
          ><el-form-item label="超时（秒）"
            ><el-input-number v-model="moduleForm.timeout_s" :min="1" :max="120"
          /></el-form-item>
        </div>
        <template v-if="moduleForm.auth_type === 'oauth2_refresh'"
          ><el-form-item label="Token 地址" required
            ><el-input
              v-model="moduleForm.token_url"
              placeholder="https://oauth2.googleapis.com/token" /></el-form-item
          ><el-form-item label="Client ID 凭据引用" required
            ><el-input
              v-model="moduleForm.client_id_ref"
              placeholder="CUSTOM_YOUTUBE_CLIENT_ID" /></el-form-item
          ><el-form-item label="Client Secret 凭据引用" required
            ><el-input
              v-model="moduleForm.client_secret_ref"
              placeholder="CUSTOM_YOUTUBE_CLIENT_SECRET" /></el-form-item
          ><el-form-item label="Refresh Token 凭据引用" required
            ><el-input
              v-model="moduleForm.refresh_token_ref"
              placeholder="CUSTOM_YOUTUBE_REFRESH_TOKEN" /></el-form-item
        ></template>
        <template v-else-if="moduleForm.auth_type !== 'none'"
          ><el-form-item label="凭据引用名称" required
            ><el-input v-model="moduleForm.secret_ref" placeholder="CUSTOM_YOUTUBE_API_TOKEN" />
            <p class="subtext">
              只填写名称；实际值到“环境与凭据”中设置，或由部署环境注入。
            </p></el-form-item
          ><el-form-item
            v-if="['header', 'query'].includes(moduleForm.auth_type)"
            label="Header / Query 名称"
            required
            ><el-input
              v-model="moduleForm.auth_name"
              placeholder="如 X-API-Key 或 key" /></el-form-item
        ></template>
        <el-collapse v-model="advanced"
          ><el-collapse-item title="高级：固定 Header 与 OAuth2 Scopes" name="module"
            ><el-form-item label="固定 Header（JSON 对象）"
              ><el-input
                v-model="moduleForm.headers_json"
                type="textarea"
                :rows="3" /></el-form-item
            ><el-form-item v-if="moduleForm.auth_type === 'oauth2_refresh'" label="OAuth2 Scopes"
              ><el-input v-model="moduleForm.scopes" /></el-form-item></el-collapse-item
        ></el-collapse>
        <el-form-item label="启用服务连接" style="margin-top: 16px"
          ><el-switch v-model="moduleForm.enabled" /><span class="subtext" style="margin-left: 12px"
            >停用后，所属接口也不能被调用。</span
          ></el-form-item
        >
      </el-form>
      <el-alert v-if="formError" type="error" :closable="false" :title="formError" show-icon />
      <template #footer
        ><el-button @click="closeModule()">取消</el-button
        ><el-button
          v-if="connectionMode === 'http'"
          type="primary"
          :loading="busy"
          @click="saveModule"
          >保存服务连接</el-button
        ></template
      >
    </el-dialog>
    <el-drawer
      v-model="apiDialog"
      :title="
        internalContext
          ? apiForm.id
            ? '编辑对外接口'
            : '新增对外接口'
          : apiForm.id
            ? '编辑 HTTP 接口'
            : '新增 HTTP 接口'
      "
      size="min(860px, 98vw)"
      :before-close="closeApi"
      :close-on-click-modal="false"
    >
      <el-steps :active="step" simple class="authoring-steps"
        ><el-step title="接口信息" /><el-step title="请求参数" /><el-step title="确认配置"
      /></el-steps>
      <el-form label-position="top" class="authoring-form">
        <template v-if="step === 0">
          <el-form-item :label="internalContext ? '提供方系统' : '接入到哪个平台？'"
            ><el-select
              :model-value="scope"
              filterable
              :disabled="!!apiForm.id"
              @update:model-value="setScope"
              ><el-option
                v-if="!internalContext"
                label="独立 HTTP 接口（单独配置地址与鉴权）"
                value="standalone" /><el-option-group
                :label="internalContext ? '提供方连接' : '自定义平台连接'"
                ><el-option
                  v-for="m in contextModules"
                  :key="m.key"
                  :label="(m.name || m.key) + (m.enabled ? '' : ' · 未启用')"
                  :value="'module:' + m.key" /></el-option-group
              ><el-option-group v-if="!internalContext" label="内置平台的 HTTP 扩展"
                ><el-option
                  v-for="p in builtinPlatforms"
                  :key="p.platform"
                  :label="p.display_name"
                  :value="'builtin:' + p.platform" /></el-option-group></el-select
          ></el-form-item>
          <p v-if="scope.startsWith('builtin:')" class="form-hint">
            这是额外的 HTTP 接口，需单独配置地址和 CUSTOM_
            凭据；不会自动继承内置适配器的签名或平台令牌逻辑。
          </p>
          <div class="form-grid">
            <el-form-item label="接口名称" required
              ><el-input
                v-model="apiForm.name"
                maxlength="64"
                :placeholder="
                  internalContext ? '填写业务接口名称' : '如 查询视频列表'
                " /></el-form-item
            ><el-form-item label="操作标识" required
              ><el-input
                v-model="apiForm.key"
                :disabled="!!apiForm.id"
                maxlength="128"
                :placeholder="internalContext ? 'service.resource.list' : 'youtube.videos.list'"
              /><el-button v-if="!apiForm.id" link type="primary" @click="suggestKey"
                >按平台、路径和方法生成建议</el-button
              ></el-form-item
            >
          </div>
          <el-form-item v-if="!apiForm.module_key" label="从完整 URL 快速填写（可选）"
            ><div class="url-import">
              <el-input
                v-model="fullEndpoint"
                placeholder="https://api.example.com/v1/videos"
              /><el-button @click="fillEndpoint">拆分地址</el-button>
            </div></el-form-item
          >
          <el-form-item v-if="!apiForm.module_key || apiForm.base_url" label="基础地址" required
            ><el-input v-model="apiForm.base_url" placeholder="https://api.example.com"
          /></el-form-item>
          <p v-else class="form-hint">
            继承平台地址：<span class="mono">{{ selectedModule?.base_url }}</span>
          </p>
          <div class="form-grid endpoint-grid">
            <el-form-item label="上游方法"
              ><el-select v-model="apiForm.method"
                ><el-option
                  v-for="method in ['GET', 'POST', 'PUT', 'PATCH', 'DELETE']"
                  :key="method"
                  :value="method" /></el-select></el-form-item
            ><el-form-item label="上游接口路径" required
              ><el-input v-model="apiForm.path" placeholder="/videos 或 /videos/{{params.id}}"
            /></el-form-item>
          </div>
          <div class="endpoint">
            <el-tag size="small">{{ apiForm.method }}</el-tag
            ><span class="mono">{{ endpointPreview || '填写地址和路径后生成预览' }}</span>
          </div>
          <div class="form-grid">
            <el-form-item label="业务模块"
              ><el-select
                v-model="apiForm.category"
                filterable
                allow-create
                default-first-option
                clearable
                placeholder="选择已有模块，也可输入新模块"
                aria-label="接口业务模块"
                ><el-option
                  v-for="category in apiCategories"
                  :key="category"
                  :label="category"
                  :value="category" /></el-select></el-form-item
            ><el-form-item label="用途说明"
              ><el-input
                v-model="apiForm.description"
                maxlength="255"
                placeholder="说明接口适用的业务场景"
            /></el-form-item>
          </div>
        </template>
        <template v-else-if="step === 1">
          <el-form-item label="业务响应契约（JSON Schema）"
            ><el-input
              v-model="apiForm.response_schema_json"
              type="textarea"
              :rows="7"
              aria-label="业务响应契约"
              :placeholder="
                JSON.stringify(
                  {
                    type: 'object',
                    properties: {
                      items: {
                        type: 'array',
                        description: '业务记录列表',
                        items: {
                          type: 'object',
                          properties: { id: { type: 'string', description: '业务记录唯一标识' } },
                        },
                      },
                    },
                  },
                  null,
                  2,
                )
              "
            />
            <p class="form-hint">
              这里描述提供方在“响应取值路径”处理后的业务内容及字段含义，不包含网关 ok / meta 和 HTTP
              适配器包装。文档和调试台会同步展示；尚未登记的字段不会自动推测。
            </p></el-form-item
          ><SchemaFields v-if="responsePreview.properties" :schema="responsePreview" />
          <div class="parameter-toolbar">
            <div>
              <h3>业务调用参数</h3>
              <p class="subtext">
                {{
                  ['GET', 'DELETE'].includes(apiForm.method)
                    ? '路径参数替换后，其余参数自动作为 Query 参数发送。'
                    : '默认将全部参数作为 JSON 请求体，也可在下方定义请求体模板。'
                }}
              </p>
            </div>
            <el-button @click="inferParameters">从模板补全参数</el-button
            ><el-button link @click="toggleRaw">{{ rawMode ? '切回表格' : '高级 JSON' }}</el-button>
          </div>
          <el-input
            v-if="rawMode"
            v-model="rawParameters"
            type="textarea"
            :rows="12"
            spellcheck="false"
          />
          <div v-else class="parameter-editor">
            <el-empty
              v-if="!parameters.length"
              description="此接口暂无参数；需要时可添加，或从路径与模板自动补全"
              :image-size="70"
            />
            <div v-for="(parameter, index) in parameters" :key="index" class="parameter-row">
              <div class="parameter-row-main">
                <el-input
                  v-model="parameter.name"
                  placeholder="参数名，如 id"
                  :aria-label="'第 ' + (index + 1) + ' 行参数名'"
                /><el-select
                  v-model="parameter.type"
                  :aria-label="'第 ' + (index + 1) + ' 行参数类型'"
                  ><el-option
                    v-for="type in ['string', 'integer', 'number', 'boolean', 'object', 'array']"
                    :key="type"
                    :value="type" /></el-select
                ><el-checkbox v-model="parameter.required">必填</el-checkbox
                ><el-button
                  text
                  type="danger"
                  :icon="Delete"
                  :aria-label="'删除第 ' + (index + 1) + ' 行参数'"
                  @click="parameters.splice(index, 1)"
                />
              </div>
              <div class="parameter-row-extra">
                <el-input v-model="parameter.description" placeholder="参数说明" /><el-input
                  v-model="parameter.defaultText"
                  placeholder='默认值（可选），如 1、true、"文本"'
                />
              </div>
            </div>
            <el-button :icon="Plus" @click="addParameter">添加参数</el-button>
          </div>
          <el-form-item label="请求体模板（可选，合法 JSON）" style="margin-top: 24px"
            ><el-input
              v-model="apiForm.body_template"
              type="textarea"
              :rows="5"
              :placeholder="JSON.stringify({ query: '{{params.query}}' }, null, 2)"
            />
            <p class="subtext">
              留空使用默认参数映射；模板中可使用 {{ parameterPlaceholder }} 引用已定义参数。
            </p></el-form-item
          >
        </template>
        <template v-else>
          <h3 class="docs-heading">调用路径预览</h3>
          <div class="endpoint">
            <el-tag type="success">POST</el-tag
            ><span class="mono">{{ gatewayPath(apiForm.key) }}</span>
          </div>
          <p class="form-hint">
            业务系统始终调用网关 POST 地址，上游实际使用
            {{ apiForm.method }}。保存不会新增授权规则，现有通配授权仍会匹配新接口。
          </p>
          <el-form-item label="上游鉴权"
            ><el-select v-model="apiForm.auth_type"
              ><el-option
                v-if="apiForm.module_key"
                value="inherit"
                label="继承平台连接的鉴权" /><el-option value="none" label="无需鉴权" /><el-option
                value="bearer"
                label="Bearer Token" /><el-option
                value="header"
                label="自定义 Header / API Key" /><el-option
                value="basic"
                label="Basic（用户名:密码）" /><el-option
                value="query"
                label="Query 参数" /></el-select
          ></el-form-item>
          <p v-if="apiForm.auth_type === 'inherit'" class="form-hint">
            使用 {{ selectedModule?.name || apiForm.module_key }} 的
            {{ selectedModule?.auth_type }} 配置。保存不代表令牌已验证，请通过调试确认。
          </p>
          <el-form-item
            v-if="!['inherit', 'none'].includes(apiForm.auth_type)"
            label="凭据引用名称"
            required
            ><el-input v-model="apiForm.secret_ref" placeholder="CUSTOM_API_TOKEN" />
            <p class="subtext">
              实际值通过“环境与凭据”或部署变量提供，请勿把密钥写入地址和固定 Header。
            </p></el-form-item
          >
          <el-form-item
            v-if="['header', 'query'].includes(apiForm.auth_type)"
            label="Header / Query 名称"
            required
            ><el-input v-model="apiForm.auth_name" placeholder="如 X-API-Key"
          /></el-form-item>
          <el-collapse v-model="advanced"
            ><el-collapse-item title="响应处理、分页与请求策略" name="api"
              ><el-form-item label="响应取值路径"
                ><el-input
                  v-model="apiForm.response_path"
                  placeholder="如 data.items，留空返回完整响应" /></el-form-item
              ><el-form-item label="固定 Header（JSON 对象）"
                ><el-input v-model="apiForm.headers_json" type="textarea" :rows="3" /></el-form-item
              ><el-form-item label="自动分页（JSON 对象）"
                ><el-input v-model="apiForm.pagination_json" type="textarea" :rows="4" />
                <p class="subtext">
                  例如
                  {"mode":"page","items_path":"data.items","page_param":"page","size_param":"page_size","max_pages":5}。
                </p></el-form-item
              >
              <div class="form-grid">
                <el-form-item label="超时（秒）"
                  ><el-input-number v-model="apiForm.timeout_s" :min="1" :max="120" /></el-form-item
                ><el-form-item label="最多尝试次数"
                  ><el-input-number v-model="apiForm.retry_max" :min="1" :max="5" />
                  <p class="subtext">写接口建议保持 1，避免重复产生业务结果。</p></el-form-item
                >
              </div></el-collapse-item
            ></el-collapse
          >
          <div class="authoring-review">
            <el-checkbox v-model="apiForm.sensitive">敏感操作，必须精确授权</el-checkbox
            ><el-checkbox v-model="apiForm.enabled">保存后启用接口</el-checkbox>
            <p class="subtext">
              默认先保存为未启用，之后可选择“启用并调试”；启用会立即注册调用路径。
            </p>
          </div>
        </template>
      </el-form>
      <el-alert
        v-if="formError"
        type="error"
        :closable="false"
        :title="formError"
        show-icon
        class="authoring-error"
      />
      <template #footer
        ><el-button @click="closeApi()">取消</el-button
        ><el-button
          v-if="step > 0"
          :disabled="busy"
          @click="
            step--;
            formError = '';
          "
          >上一步</el-button
        ><el-button v-if="step < 2" type="primary" @click="next">下一步</el-button
        ><el-button v-else type="primary" :loading="busy" @click="saveApi">{{
          apiForm.enabled ? '保存并启用' : '保存配置'
        }}</el-button></template
      >
    </el-drawer>
    <el-dialog
      :model-value="!!saved"
      title="配置已保存"
      width="min(620px, 96vw)"
      @close="saved = undefined"
    >
      <template v-if="saved"
        ><el-alert
          type="success"
          :closable="false"
          :title="
            saved.kind === 'modules'
              ? internalContext
                ? '提供方连接已保存'
                : '平台连接已保存，尚未验证真实鉴权'
              : saved.enabled
                ? '接口已启用，可继续调试和授权'
                : '接口已保存为未启用，可在目录中查看和继续编辑'
          "
        />
        <p class="mono form-hint">{{ saved.key }}</p>
        <p v-if="!internalContext" class="form-hint">
          凭据属于上游平台；API Key 和操作授权属于调用方业务系统，这是两套不同的身份。
        </p>
        <div class="saved-next-grid">
          <el-button
            v-if="!internalContext"
            @click="
              saved = undefined;
              router.push('/credentials');
            "
            >配置上游凭据</el-button
          ><el-button v-if="saved.kind === 'modules'" type="primary" @click="addForSavedPlatform">{{
            internalContext ? '新增对外接口' : '为此平台新增接口'
          }}</el-button
          ><el-button v-else type="primary" :loading="busy" @click="enableAndDebug">{{
            saved.enabled ? '前往调试' : '启用并前往调试'
          }}</el-button
          ><el-button
            @click="
              router.push({
                path: internalContext ? '/exposed' : '/registry',
                query: internalContext
                  ? {
                      ...(saved.kind === 'modules'
                        ? { view: 'providers' }
                        : { operation: saved.key }),
                    }
                  : { platform: saved.key.split('.')[0] },
              })
            "
            >{{ internalContext ? '返回对外能力' : '在操作目录查看' }}</el-button
          ><el-button
            v-if="saved.kind === 'custom-apis'"
            :disabled="!saved.enabled"
            @click="
              router.push({
                path: '/grants',
                query: {
                  operation: saved.key,
                  direction: internalContext ? 'internal' : 'third_party',
                },
              })
            "
            >为调用系统授权</el-button
          >
        </div>
      </template>
    </el-dialog>
  </div>
</template>
