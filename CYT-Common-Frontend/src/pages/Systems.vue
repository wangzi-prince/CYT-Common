<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Plus, Refresh, Search, Right } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { api } from '~/api';
import type { SystemItem } from '~/api/types';
import { useRequest, useAction, confirm } from '~/composables/useRequest';
import PageHeader from '~/components/PageHeader.vue';
import PageState from '~/components/PageState.vue';
const route = useRoute(),
  router = useRouter();
const { data, loading, error, load } = useRequest(api.systems);
const { busy, run } = useAction();
const q = ref('');
const dialog = ref(route.query.action === 'create');
const form = reactive({ key: '', display_name: '', description: '' });
const rows = computed(() => {
  const words = q.value.trim().toLowerCase().split(/\s+/).filter(Boolean);
  return (data.value?.systems || []).filter((s) =>
    words.every((w) => `${s.key} ${s.display_name} ${s.description}`.toLowerCase().includes(w)),
  );
});
onMounted(load);
function open(row: SystemItem) {
  void router.push({
    path: `/systems/${encodeURIComponent(row.key)}`,
    query: {
      ...(route.query.operation ? { operation: route.query.operation } : {}),
      ...(route.query.platform ? { platform: route.query.platform } : {}),
      ...(route.query.intent === 'grant' ? { tab: 'access' } : {}),
    },
  });
}
async function create() {
  const key = form.key;
  const result = await run(() => api.createSystem(form));
  if (!result) return;
  dialog.value = false;
  ElMessage.success('调用方已创建，接下来准备密钥和授权');
  void router.push({ path: `/systems/${encodeURIComponent(key)}`, query: { tab: 'keys' } });
}
async function toggle(row: SystemItem) {
  if (
    !(await confirm(
      `确认${row.enabled ? '停用' : '启用'}「${row.display_name}」？${row.enabled ? '停用后该系统所有密钥均无法调用网关。' : ''}`,
    ))
  )
    return;
  await run(async () => {
    await api.toggleSystem(row.key);
    await load();
  });
}
</script>
<template>
  <div>
    <PageHeader
      title="调用方"
      description="一个调用方对应一个业务系统。进入后，在同一处管理它的密钥、能力授权和调用文档。"
      ><el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button
      ><el-button type="primary" :icon="Plus" @click="dialog = true"
        >新增调用方</el-button
      ></PageHeader
    ><el-alert
      v-if="route.query.intent === 'grant'"
      title="先选择要获得授权的业务系统"
      :description="
        route.query.operation
          ? `已定位能力：${route.query.operation}。选择系统后会直接进入对应授权。`
          : '选择一个调用方后，可以批量分配能力、设置配额和有效期。'
      "
      type="info"
      :closable="false"
      style="margin-bottom: 20px"
    />
    <section class="panel">
      <div class="panel-toolbar">
        <el-input
          v-model="q"
          :prefix-icon="Search"
          placeholder="搜索业务系统名称、标识或用途"
          clearable
          aria-label="搜索调用方"
          style="max-width: 440px"
        /><span class="subtext">{{ rows.length }} 个调用方</span>
      </div>
      <PageState :loading="loading" :error="error" @retry="load"
        ><el-table :data="rows" empty-text="还没有调用方，新增后即可准备密钥、授权与专属文档"
          ><el-table-column label="业务系统" min-width="230"
            ><template #default="{ row }"
              ><el-button link type="primary" @click="open(row)"
                ><strong>{{ row.display_name }}</strong></el-button
              >
              <div class="subtext mono">{{ row.key }}</div></template
            ></el-table-column
          ><el-table-column prop="description" label="用途" min-width="210" /><el-table-column
            label="接入进度"
            min-width="200"
            ><template #default="{ row }"
              ><div class="caller-progress">
                <span :class="{ ready: row.apikey_count }">{{
                  row.apikey_count ? `${row.apikey_count} 把密钥` : '待生成密钥'
                }}</span
                ><span :class="{ ready: row.grant_count }">{{
                  row.grant_count ? '已配置授权' : '待分配能力'
                }}</span>
              </div></template
            ></el-table-column
          ><el-table-column label="状态" width="100"
            ><template #default="{ row }"
              ><el-tag :type="row.enabled ? 'success' : 'info'" effect="plain">{{
                row.enabled ? '已启用' : '已停用'
              }}</el-tag></template
            ></el-table-column
          ><el-table-column label="操作" width="180" fixed="right"
            ><template #default="{ row }"
              ><el-button link type="primary" @click="open(row)"
                >配置接入<el-icon><Right /></el-icon></el-button
              ><el-button
                link
                :type="row.enabled ? 'danger' : 'primary'"
                :disabled="busy"
                @click="toggle(row)"
                >{{ row.enabled ? '停用' : '启用' }}</el-button
              ></template
            ></el-table-column
          ></el-table
        ></PageState
      >
    </section>
    <el-dialog
      v-model="dialog"
      title="新增调用方"
      width="min(500px,94vw)"
      :close-on-click-modal="false"
      ><el-form label-position="top"
        ><el-form-item label="系统名称" required
          ><el-input
            v-model="form.display_name"
            placeholder="例如：售后工作台"
            maxlength="64" /></el-form-item
        ><el-form-item label="系统标识" required
          ><el-input
            v-model="form.key"
            placeholder="例如 aftersales，使用小写字母、数字和下划线"
            maxlength="32" /></el-form-item
        ><el-form-item label="用途说明"
          ><el-input
            v-model="form.description"
            placeholder="这个系统需要使用哪些业务能力"
            type="textarea"
            maxlength="255" /></el-form-item></el-form
      ><template #footer
        ><el-button @click="dialog = false">取消</el-button
        ><el-button
          type="primary"
          :disabled="!form.key || !form.display_name"
          :loading="busy"
          @click="create"
          >创建并准备密钥</el-button
        ></template
      ></el-dialog
    >
  </div>
</template>
<style scoped>
.caller-progress {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.caller-progress span {
  padding: 4px 7px;
  border-radius: 5px;
  background: var(--el-fill-color-light);
}
.caller-progress .ready {
  color: var(--el-color-success);
  background: var(--el-color-success-light-9);
}
</style>
