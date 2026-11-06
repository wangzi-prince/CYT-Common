<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue';
import { useRouter, useRoute } from 'vue-router';
import {
  Plus,
  Refresh,
  Delete,
  Search,
  InfoFilled,
  View,
  CopyDocument,
} from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { api } from '~/api';
import type { CredentialState } from '~/api/types';
import { useRequest, useAction, confirm, dateTime } from '~/composables/useRequest';
import { useCredentialAccess } from '~/composables/useCredentialAccess';
import PageHeader from '~/components/PageHeader.vue';
import PageState from '~/components/PageState.vue';
const router = useRouter();
const route = useRoute();
const env = ref('');
const environments = useRequest(api.environments);
const secrets = useRequest(() => api.secrets(env.value));
const history = useRequest(() =>
  api.audit({ actor: 'gcloud-import', q: env.value, page_size: 20 }),
);
const { busy, run } = useAction();
const q = ref('');
const platform = ref(String(route.query.platform || ''));
const state = ref('all');
const system = ref('');
const page = ref(1);
const size = ref(20);
const secretDialog = ref(false);
const envDialog = ref(false);
const cloudHelp = ref(false);
const editing = ref(false);
const multiline = ref(false);
const reveal = ref(false);
const form = reactive({ name: '', value: '' });
const access = useCredentialAccess(api.readSecret, (value) => navigator.clipboard.writeText(value));
const {
  target: viewing,
  value: viewedValue,
  source: viewedSource,
  loading: viewingLoading,
  copying,
} = access;
watch([env, system], access.reset);
onUnmounted(access.reset);
async function copyCredential(name: string) {
  const result = await access.copy(env.value, name);
  if (result === 'copied') ElMessage.success('凭据已复制');
  else if (result === 'clipboardUnavailable')
    ElMessage.warning('剪贴板不可用，请通过“查看”手动复制');
}
watch(
  () => form.name,
  (name) => {
    if (!editing.value) multiline.value = /(?:_JSON|PRIVATE_KEY|PUBLIC_KEY)$/.test(name);
  },
);
const envForm = reactive({ key: '', label: '', description: '', is_production: false });
const cloudCommands =
  'cd D:/code/CYT-Common/CYT-Common-API\npython tools/gcloud_credentials.py plan\npython tools/gcloud_credentials.py apply\n\n# 轮换：检查计划后，显式覆盖已有不同值\npython tools/gcloud_credentials.py apply --overwrite';
type Stored = NonNullable<typeof secrets.data.value>['db_secrets'][number];
interface CloudSource {
  secret?: string;
  version?: string;
  project?: string;
  importedAt: string;
}
interface CredentialRow {
  name: string;
  label: string;
  platformKeys: string[];
  platformLabels: string[];
  required: boolean;
  secret: boolean;
  set: boolean;
  source: string;
  preview: string;
  hint: string;
  stored?: Stored;
  cloud?: CloudSource;
}
const imported = computed(() => {
  const map = new Map<string, CloudSource>();
  for (const event of history.data.value?.items || []) {
    if (event.target !== env.value) continue;
    try {
      const detail = JSON.parse(event.detail);
      for (const entry of detail.entries || [])
        if (typeof entry.name === 'string' && !map.has(entry.name)) {
          map.set(entry.name, {
            secret: entry.secret,
            version: String(entry.version || ''),
            project: detail.project,
            importedAt: event.created_at,
          });
        }
    } catch {
      /* Unrelated or older audit entries do not contain source metadata. */
    }
  }
  return map;
});
const rows = computed(() => {
  const stored = new Map((secrets.data.value?.db_secrets || []).map((s) => [s.name, s]));
  const grouped = new Map<string, CredentialRow>();
  for (const p of secrets.data.value?.platforms || [])
    for (const variable of p.vars) {
      const selected: CredentialState =
        system.value && variable.per_system
          ? variable.scoped?.find((s) => s.system === system.value) || variable
          : variable;
      const old = grouped.get(selected.name);
      if (old) {
        if (!old.platformKeys.includes(p.platform)) {
          old.platformKeys.push(p.platform);
          old.platformLabels.push(p.label);
        }
        old.required ||= variable.required;
        continue;
      }
      const record = stored.get(selected.name);
      grouped.set(selected.name, {
        name: selected.name,
        label: variable.label || selected.name,
        platformKeys: [p.platform],
        platformLabels: [p.label + (variable.account ? ' · ' + variable.account : '')],
        required: variable.required,
        secret: variable.secret,
        set: selected.set,
        source: selected.source,
        preview: selected.preview || '',
        hint: variable.hint || '',
        stored: record,
        cloud:
          record?.updated_by === 'gcloud-import' ? imported.value.get(selected.name) : undefined,
      });
    }
  for (const record of stored.values())
    if (!grouped.has(record.name))
      grouped.set(record.name, {
        name: record.name,
        label: record.name.startsWith('CUSTOM_') ? '自定义 HTTP 凭据' : '专属或扩展变量',
        platformKeys: ['custom'],
        platformLabels: ['自定义 / 专属变量'],
        required: false,
        secret: true,
        set: Boolean(secrets.data.value?.writable),
        source: 'db',
        preview: '',
        hint: '',
        stored: record,
        cloud: record.updated_by === 'gcloud-import' ? imported.value.get(record.name) : undefined,
      });
  return [...grouped.values()].sort(
    (a, b) =>
      Number(a.set) - Number(b.set) ||
      Number(b.required) - Number(a.required) ||
      a.name.localeCompare(b.name),
  );
});
const filtered = computed(() =>
  rows.value.filter(
    (row) =>
      (!platform.value || row.platformKeys.includes(platform.value)) &&
      (state.value === 'all' ||
        (state.value === 'missing' && row.required && !row.set) ||
        (state.value === 'set' && row.set) ||
        (state.value === 'cloud' && !!row.cloud)) &&
      (row.name + ' ' + row.label + ' ' + row.platformLabels.join(' '))
        .toLowerCase()
        .includes(q.value.trim().toLowerCase()),
  ),
);
const visible = computed(() =>
  filtered.value.slice((page.value - 1) * size.value, page.value * size.value),
);
const configuredCount = computed(() => rows.value.filter((r) => r.set).length);
const missingCount = computed(() => rows.value.filter((r) => r.required && !r.set).length);
const cloudCount = computed(() => rows.value.filter((r) => r.cloud).length);
const scopeOptions = computed(() => secrets.data.value?.systems || []);
watch([q, platform, state, system, size], () => {
  page.value = 1;
});
watch(
  () => filtered.value.length,
  (count) => {
    page.value = Math.min(page.value, Math.max(1, Math.ceil(count / size.value)));
  },
);
async function loadDetails() {
  if (env.value) await Promise.all([secrets.load(), history.load()]);
}
async function reload() {
  await environments.load();
  if (!env.value || !environments.data.value?.items.some((e) => e.key === env.value))
    env.value = environments.data.value?.current || '';
  else await loadDetails();
}
onMounted(reload);
watch(env, () => {
  system.value = '';
  void loadDetails();
});
function openSecret(row?: CredentialRow) {
  editing.value = !!row;
  form.name = row?.name || '';
  form.value = '';
  reveal.value = false;
  multiline.value = /(?:_JSON|PRIVATE_KEY|PUBLIC_KEY)$/.test(form.name);
  secretDialog.value = true;
}
function sourceLabel(row: CredentialRow) {
  if (row.cloud) return 'GCP 导入';
  if (row.stored) return '加密数据库';
  return row.source === 'env' ? '部署配置' : '尚未配置';
}
async function saveSecret() {
  if (!/^[A-Z][A-Z0-9_]{0,63}$/.test(form.name.trim()) || !form.value.trim()) {
    ElMessage.error('请填写合法变量名和新凭据值');
    return;
  }
  const result = await run(() => api.saveSecret(env.value, { [form.name.trim()]: form.value }));
  if (!result) return;
  secretDialog.value = false;
  form.value = '';
  ElMessage.success('新凭据已加密保存，后续请求使用新值');
  await loadDetails();
  await environments.load();
}
async function deleteSecret(row: CredentialRow) {
  if (
    !(await confirm(
      '仅删除「' +
        row.name +
        '」在此环境的数据库值；同名部署配置仍可能生效。上游令牌的撤销需要在供应商侧处理。',
    ))
  )
    return;
  const result = await run(() => api.deleteSecret(env.value, row.name));
  if (result) {
    await loadDetails();
    await environments.load();
  }
}
async function createEnv() {
  const result = await run(() => api.createEnvironment(envForm));
  if (!result) return;
  envDialog.value = false;
  await environments.load();
  env.value = envForm.key;
}
async function deleteEnv() {
  const selected = environments.data.value?.items.find((e) => e.key === env.value);
  if (
    !selected ||
    !(await confirm(
      '确认删除环境「' +
        (selected.label || selected.key) +
        '」？当前运行环境或含有凭据的环境不能删除。',
    ))
  )
    return;
  const result = await run(() => api.deleteEnvironment(selected.id));
  if (result) {
    await environments.load();
    env.value = environments.data.value?.current || '';
  }
}
</script>
<template>
  <div>
    <PageHeader
      title="环境与凭据"
      description="按平台和调用系统管理上游凭据，查看来源、复制密钥与更新凭据。"
    >
      <el-button :icon="InfoFilled" @click="cloudHelp = true">GCP 同步说明</el-button>
      <el-button :icon="Refresh" :loading="secrets.loading.value" @click="reload"
        >刷新状态</el-button
      >
      <el-button
        type="primary"
        :icon="Plus"
        :disabled="!secrets.data.value?.writable"
        @click="openSecret()"
        >设置凭据</el-button
      >
    </PageHeader>
    <el-alert
      v-if="environments.data.value && !environments.data.value.writable"
      title="后端尚未加载凭据主密钥"
      description="云端同步工具会为新的本地加密库配置主密钥。首次配置 CREDENTIAL_MASTER_KEY 后，需重启 Java 服务；已有密文需要保留原主密钥。"
      type="warning"
      :closable="false"
      class="credential-notice"
    />
    <section class="panel">
      <div class="panel-toolbar credential-environment-bar">
        <el-select v-model="env" aria-label="凭据环境"
          ><el-option
            v-for="e in environments.data.value?.items"
            :key="e.key"
            :label="
              (e.label || e.key) + (e.key === environments.data.value?.current ? ' · 当前运行' : '')
            "
            :value="e.key"
        /></el-select>
        <el-tag
          :type="env === environments.data.value?.current ? 'success' : 'info'"
          effect="plain"
          >{{
            env === environments.data.value?.current ? '数据库更新后立即生效' : '为其他部署准备凭据'
          }}</el-tag
        >
        <div class="credential-environment-actions">
          <el-button link :icon="Plus" @click="envDialog = true">新增环境</el-button
          ><el-button
            v-if="env && env !== environments.data.value?.current"
            link
            type="danger"
            :icon="Delete"
            @click="deleteEnv"
            >删除环境</el-button
          >
        </div>
      </div>
      <div class="credential-summary">
        <span
          ><b>{{ configuredCount }}</b> 项已配置</span
        ><span
          ><b :class="{ missing: missingCount > 0 }">{{ missingCount }}</b> 项必填未配</span
        ><span
          ><b>{{ cloudCount }}</b> 项来自 GCP</span
        ><el-button
          link
          type="primary"
          @click="router.push({ path: '/audit', query: { q: 'secrets_' } })"
          >查看更新审计</el-button
        >
      </div>
      <div class="panel-toolbar credential-filters">
        <el-input
          v-model="q"
          :prefix-icon="Search"
          clearable
          placeholder="搜索平台、变量名或用途"
          aria-label="搜索凭据"
        />
        <el-select
          v-model="platform"
          clearable
          filterable
          placeholder="全部平台"
          aria-label="凭据平台"
          ><el-option
            v-for="p in secrets.data.value?.platforms"
            :key="p.platform"
            :label="p.label"
            :value="p.platform" /><el-option value="custom" label="自定义 / 专属变量"
        /></el-select>
        <el-select v-model="state" aria-label="凭据状态"
          ><el-option value="all" label="全部状态" /><el-option
            value="missing"
            label="必填未配置" /><el-option value="set" label="已配置" /><el-option
            value="cloud"
            label="GCP 导入"
        /></el-select>
        <el-select
          v-if="scopeOptions.length"
          v-model="system"
          placeholder="共享凭据"
          :empty-values="[null, undefined]"
          aria-label="凭据作用域"
          ><el-option value="" label="共享凭据" /><el-option
            v-for="s in scopeOptions"
            :key="s.key"
            :value="s.key"
            :label="s.display_name + ' · 专属配置'"
        /></el-select>
      </div>
      <PageState
        :loading="environments.loading.value || secrets.loading.value"
        :error="environments.error.value || secrets.error.value"
        @retry="reload"
      >
        <el-empty
          v-if="!filtered.length"
          description="没有匹配的凭据项，可选择其他平台或清除筛选"
        />
        <el-table v-else :data="visible" row-key="name" class="credential-table">
          <el-table-column label="凭据 / 用途" min-width="300"
            ><template #default="{ row }"
              ><div class="credential-stack">
                <strong class="mono">{{ row.name }}</strong
                ><span class="subtext">{{ row.label }}</span>
              </div></template
            ></el-table-column
          >
          <el-table-column label="平台与要求" min-width="160"
            ><template #default="{ row }"
              ><div class="credential-stack">
                <span>{{ row.platformLabels.join(' / ') }}</span
                ><span class="subtext">{{ row.required ? '必填' : '可选 / 扩展' }}</span>
              </div></template
            ></el-table-column
          >
          <el-table-column label="状态 / 来源" width="165"
            ><template #default="{ row }"
              ><div class="credential-stack">
                <div>
                  <el-tag
                    :type="
                      row.set ? 'success' : row.stored ? 'info' : row.required ? 'warning' : 'info'
                    "
                    effect="plain"
                    >{{
                      row.set
                        ? '已配置'
                        : row.stored
                          ? '已保存 · 待加载'
                          : row.required
                            ? '缺少凭据'
                            : '未设置'
                    }}</el-tag
                  >
                </div>
                <span class="subtext"
                  >{{ sourceLabel(row)
                  }}{{ row.cloud?.version ? ' · v' + row.cloud.version : '' }}</span
                >
              </div></template
            ></el-table-column
          >
          <el-table-column label="值 / 更新时间" min-width="240"
            ><template #default="{ row }"
              ><div class="credential-stack">
                <div class="credential-value">
                  <span :class="{ 'credential-masked': row.secret }">{{
                    row.set || row.stored
                      ? row.secret
                        ? '••••••••'
                        : row.preview || '已保存'
                      : '—'
                  }}</span>
                  <div v-if="row.set || row.stored" class="credential-value-actions">
                    <el-tooltip content="查看完整值"
                      ><el-button
                        :icon="View"
                        text
                        circle
                        size="small"
                        :aria-label="`查看 ${row.name}`"
                        @click="access.view(env, row.name)"
                    /></el-tooltip>
                    <el-tooltip content="复制凭据"
                      ><el-button
                        :icon="CopyDocument"
                        text
                        circle
                        size="small"
                        :loading="copying === row.name"
                        :disabled="!!copying && copying !== row.name"
                        :aria-label="`复制 ${row.name}`"
                        @click="copyCredential(row.name)"
                    /></el-tooltip>
                  </div>
                </div>
                <span class="subtext">{{
                  row.stored
                    ? dateTime(row.stored.updated_at)
                    : row.source === 'env'
                      ? '随部署加载'
                      : '尚未保存'
                }}</span>
              </div></template
            ></el-table-column
          >
          <el-table-column label="操作" width="140" fixed="right"
            ><template #default="{ row }"
              ><el-button
                link
                type="primary"
                :disabled="!secrets.data.value?.writable"
                @click="openSecret(row)"
                >{{ row.set || row.stored ? '更新 / 轮换' : '配置' }}</el-button
              ><el-button
                v-if="row.stored"
                link
                type="danger"
                :disabled="!secrets.data.value?.writable"
                @click="deleteSecret(row)"
                >删除</el-button
              ></template
            ></el-table-column
          >
        </el-table>
        <div class="catalog-pagination">
          <span>共 {{ filtered.length }} 项凭据</span
          ><el-pagination
            v-model:current-page="page"
            v-model:page-size="size"
            :page-sizes="[20, 50, 100]"
            :total="filtered.length"
            layout="sizes, prev, pager, next"
          />
        </div>
      </PageState>
    </section>
    <section class="panel">
      <div class="panel-body credential-guidance">
        <strong>日常更新与轮换</strong>
        <p>
          列表默认遮罩显示，点“查看”或“复制”读取完整值。平台密钥到期后，点“更新 / 轮换”保存新值。GCP
          导入项会标记云端版本，手动更新后改为数据库管理；重新同步不同值需要显式覆盖。
        </p>
        <strong>环境、作用域与主密钥</strong>
        <p>
          页面选择环境只用于管理，不切换服务的运行环境。调用方专属凭据优先，其次是环境数据库和部署变量，具体回退遵循平台策略。主密钥仅保存在部署配置中，轮换主密钥前必须重加密已有密文。
        </p>
        <p>这里管理的是上游凭据；业务系统访问网关使用的 API Key，请到“调用方系统”管理。</p>
      </div>
    </section>
    <el-dialog
      :model-value="!!viewing"
      title="查看凭据"
      width="min(680px,96vw)"
      destroy-on-close
      @close="access.reset"
    >
      <template v-if="viewing">
        <div class="credential-detail-heading">
          <strong class="mono">{{ viewing.name }}</strong
          ><span
            >{{ viewing.env }} ·
            {{
              viewedSource === 'db'
                ? '加密数据库'
                : viewedSource === 'env'
                  ? '部署配置'
                  : '正在读取'
            }}</span
          >
        </div>
        <el-skeleton v-if="viewingLoading" :rows="4" animated />
        <el-input
          v-else
          :model-value="viewedValue"
          type="textarea"
          :rows="8"
          readonly
          aria-label="完整凭据值"
          class="credential-detail-value"
          spellcheck="false"
        />
      </template>
      <template #footer
        ><el-button @click="access.reset">关闭</el-button
        ><el-button
          type="primary"
          :icon="CopyDocument"
          :disabled="!viewing || viewingLoading"
          :loading="!!copying"
          @click="viewing && copyCredential(viewing.name)"
          >复制凭据</el-button
        ></template
      >
    </el-dialog>
    <el-dialog
      v-model="secretDialog"
      :title="editing ? '更新 / 轮换凭据' : '设置平台凭据'"
      width="min(620px, 96vw)"
      :close-on-click-modal="false"
      @closed="
        form.value = '';
        reveal = false;
      "
    >
      <el-form label-position="top"
        ><el-form-item label="环境"><el-input :model-value="env" disabled /></el-form-item
        ><el-form-item label="变量名称" required
          ><el-select
            v-model="form.name"
            filterable
            allow-create
            default-first-option
            :disabled="editing"
            placeholder="选择已有变量，或输入 CUSTOM_ 开头的新变量"
            ><el-option
              v-for="row in rows"
              :key="row.name"
              :label="row.name + ' · ' + row.label"
              :value="row.name" /></el-select
        ></el-form-item>
        <div class="credential-value-options">
          <el-checkbox v-model="multiline">多行私钥 / JSON</el-checkbox
          ><el-checkbox v-if="multiline" v-model="reveal">显示本次输入</el-checkbox>
        </div>
        <el-form-item label="新凭据值" required
          ><el-input
            v-model="form.value"
            :type="multiline ? 'textarea' : 'password'"
            :show-password="!multiline"
            :rows="7"
            :class="{ 'credential-concealed': multiline && !reveal }"
            placeholder="输入新值；保留原值请取消"
            autocomplete="new-password"
            spellcheck="false"
        /></el-form-item>
        <p class="form-hint">
          保存到选定环境的加密数据库，不修改 GCP 云端版本。私钥与 JSON
          请使用多行模式，保留原始换行。
        </p></el-form
      >
      <template #footer
        ><el-button @click="secretDialog = false">取消</el-button
        ><el-button
          type="primary"
          :loading="busy"
          :disabled="!form.name || !form.value.trim()"
          @click="saveSecret"
          >加密保存新值</el-button
        ></template
      >
    </el-dialog>
    <el-dialog v-model="cloudHelp" title="GCP 凭据同步与后续轮换" width="min(700px, 96vw)"
      ><div class="credential-guidance">
        <p>
          使用本机已登录的 Google CLI 读取 Secret
          Manager，只导入已注册平台的匹配变量，云端版本固定为数字版本号。不会复制 CLI
          的个人登录令牌，也不会切换平台的模拟模式。
        </p>
        <pre class="credential-commands">{{ cloudCommands }}</pre>
        <p>
          plan 只读取名称与版本并生成清单；apply
          把所选值写入本地加密库，默认保留已有不同值。云端更新后重新生成计划，核对来源与版本后再覆盖同步。
        </p>
        <p>
          生产部署应使用服务运行身份访问 Secret Manager。Gmail / Drive 的 OAuth 授权、GCS
          的服务身份需要分别配置，CLI 登录本身不等于这些平台凭据。
        </p>
      </div></el-dialog
    >
    <el-dialog v-model="envDialog" title="新增环境" width="min(470px, 96vw)"
      ><el-form label-position="top"
        ><el-form-item label="环境标识" required
          ><el-input v-model="envForm.key" placeholder="如 staging" maxlength="32" /></el-form-item
        ><el-form-item label="显示名称"
          ><el-input
            v-model="envForm.label"
            placeholder="如 预发布环境"
            maxlength="64" /></el-form-item
        ><el-form-item label="用途说明"
          ><el-input v-model="envForm.description" maxlength="255" /></el-form-item
        ><el-checkbox v-model="envForm.is_production">这是生产环境</el-checkbox></el-form
      ><template #footer
        ><el-button @click="envDialog = false">取消</el-button
        ><el-button type="primary" :loading="busy" :disabled="!envForm.key" @click="createEnv"
          >创建环境</el-button
        ></template
      ></el-dialog
    >
  </div>
</template>
<style scoped>
.credential-notice {
  margin-bottom: 20px;
}
.credential-environment-bar > .el-select {
  width: 240px;
}
.credential-environment-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-left: auto;
}
.credential-summary {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 24px;
  padding: 17px 22px;
  border-bottom: 1px solid var(--app-divider);
  color: var(--app-text-muted);
  font-size: 12px;
}
.credential-summary b {
  font-size: 17px;
  color: var(--app-text-secondary);
  margin-right: 5px;
  font-weight: 550;
}
.credential-summary b.missing {
  color: #ad8030;
}
.credential-summary > .el-button {
  margin-left: auto;
}
.credential-filters > .el-input {
  flex: 1;
  min-width: 220px;
}
.credential-filters > .el-select {
  width: 165px;
}
.credential-stack {
  display: grid;
  grid-template-rows: 22px 18px;
  gap: 2px;
  align-content: center;
}
.credential-stack > :first-child {
  line-height: 22px;
  height: 22px;
  display: flex;
  align-items: center;
}
.credential-stack strong {
  font-size: 12px;
  font-weight: 500;
}
.credential-stack .subtext {
  margin: 0;
  line-height: 18px;
  font-size: 11px;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.credential-stack .el-tag {
  height: 22px;
  line-height: 20px;
}
.credential-masked {
  letter-spacing: 2px;
  color: var(--app-text-muted);
}
.credential-value {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}
.credential-value > span {
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.credential-value-actions {
  display: flex;
  align-items: center;
  flex-shrink: 0;
  gap: 2px;
}
.credential-value-actions .el-button {
  margin: 0;
  width: 24px;
  height: 24px;
}
.credential-detail-heading {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 18px;
  overflow-wrap: anywhere;
}
.credential-detail-heading strong {
  font-size: 13px;
  color: var(--app-text);
}
.credential-detail-heading span {
  font-size: 12px;
  color: var(--app-text-muted);
}
.credential-detail-value :deep(textarea) {
  font-family: ui-monospace, Consolas, monospace;
  font-size: 12px;
  line-height: 1.8;
  overflow-wrap: anywhere;
}
.credential-guidance {
  color: var(--app-text-muted);
  font-size: 12px;
  line-height: 1.9;
}
.credential-guidance strong {
  color: var(--app-text-secondary);
  font-weight: 600;
}
.credential-guidance p {
  margin: 5px 0 15px;
}
.credential-value-options {
  display: flex;
  flex-wrap: wrap;
  gap: 15px;
  margin-bottom: 8px;
}
.credential-concealed :deep(textarea) {
  -webkit-text-security: disc;
}
.credential-commands {
  background: var(--app-surface-soft);
  border: 1px solid var(--app-border);
  border-radius: 8px;
  padding: 16px;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  color: var(--app-text-secondary);
  font-size: 12px;
}
.el-dialog .el-select {
  width: 100%;
}
</style>
