<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { api } from '~/api';
import { useRequest, useAction, confirm, dateTime } from '~/composables/useRequest';
import PageState from './PageState.vue';
const props = defineProps<{ system: string; enabled: boolean }>();
const emit = defineEmits<{ changed: [] }>();
const { data, loading, error, load } = useRequest(() => api.keys(props.system));
const { busy, run } = useAction();
const name = ref('');
const created = ref('');
onMounted(load);
async function create() {
  const result = await run(() => api.createKey(props.system, name.value));
  if (!result) return;
  created.value = result.key;
  name.value = '';
  await load();
  emit('changed');
}
async function revoke(id: number) {
  if (!(await confirm('吊销后，该密钥将无法调用网关。确认吊销？'))) return;
  await run(async () => {
    await api.revokeKey(id);
    await load();
    emit('changed');
  });
}
async function copy() {
  try {
    await navigator.clipboard.writeText(created.value);
    ElMessage.success('已复制调用密钥');
  } catch {
    ElMessage.info('请手动复制密钥并妥善保存');
  }
}
</script>
<template>
  <section class="panel">
    <div class="panel-body">
      <h2>调用密钥</h2>
      <p class="form-hint">供这个业务系统访问网关。上游企微、Google 等平台的凭据统一由网关管理。</p>
      <el-alert
        v-if="created"
        title="这把密钥的明文只在创建后展示，请立即保存"
        type="warning"
        :closable="false"
        ><p class="mono key-value">{{ created }}</p>
        <el-button @click="copy">复制密钥</el-button></el-alert
      >
      <div class="inline-actions key-create">
        <el-input
          v-model="name"
          placeholder="用途，例如生产服务或测试环境"
          maxlength="64"
        /><el-button type="primary" :loading="busy" :disabled="!enabled" @click="create"
          >生成独立密钥</el-button
        >
      </div>
      <PageState :loading="loading" :error="error" @retry="load"
        ><el-table :data="data?.items || []" empty-text="尚未生成密钥，生成后交由该系统后端保存"
          ><el-table-column prop="name" label="用途" min-width="150" /><el-table-column
            label="密钥前缀"
            min-width="150"
            ><template #default="{ row }"
              ><code>cyt_{{ row.prefix }}_…</code></template
            ></el-table-column
          ><el-table-column label="最近使用" min-width="150"
            ><template #default="{ row }">{{
              dateTime(row.last_used_at)
            }}</template></el-table-column
          ><el-table-column label="状态 / 操作" width="120"
            ><template #default="{ row }"
              ><el-tag v-if="row.revoked" type="info" effect="plain">已吊销</el-tag
              ><el-button v-else link type="danger" :disabled="busy" @click="revoke(row.id)"
                >吊销</el-button
              ></template
            ></el-table-column
          ></el-table
        ></PageState
      >
    </div>
  </section>
</template>
<style scoped>
.key-value {
  word-break: break-all;
}
.key-create {
  margin: 20px 0;
}
.key-create .el-input {
  max-width: 400px;
}
h2 {
  font-size: 17px;
}
</style>
