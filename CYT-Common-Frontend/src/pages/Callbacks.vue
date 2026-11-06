<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { Plus } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { api } from '~/api';
import { useRequest, useAction, confirm } from '~/composables/useRequest';
import PageHeader from '~/components/PageHeader.vue';
import PageState from '~/components/PageState.vue';
import JsonViewer from '~/components/JsonViewer.vue';
const { data, loading, error, load } = useRequest(api.callbacks);
const { busy, run } = useAction();
const dialog = ref(false);
const testResult = ref<unknown>();
const form = reactive({
  id: undefined as number | undefined,
  system: '',
  platform: 'docusign',
  url: '',
  secret_ref: '',
  description: '',
  enabled: true,
});
const systems = ref<{ key: string; display_name: string }[]>([]);
onMounted(async () => {
  await load();
  const result = await run(api.systems);
  systems.value = result?.systems || [];
});
function edit(row?: Record<string, unknown>) {
  Object.assign(
    form,
    {
      id: undefined,
      system: '',
      platform: 'docusign',
      url: '',
      secret_ref: '',
      description: '',
      enabled: true,
    },
    row || {},
  );
  dialog.value = true;
}
async function save() {
  const result = await run(() => api.saveResource('callbacks', { ...form }));
  if (result) {
    dialog.value = false;
    ElMessage.success('回调配置已保存');
    await load();
  }
}
async function remove(id: number) {
  if (await confirm('确认删除此回调转发配置？'))
    await run(async () => {
      await api.deleteResource('callbacks', id);
      await load();
    });
}
async function test(id: number) {
  if (!(await confirm('将向该配置的目标地址发送一条连通性测试事件，确认发送？', '测试回调')))
    return;
  const result = await run(() => api.testCallback(id));
  if (result) testResult.value = result;
}
</script>
<template>
  <div>
    <PageHeader title="回调配置" description="将已验签的第三方事件，按调用系统转发至对应业务服务。"
      ><el-button type="primary" :icon="Plus" @click="edit()">新增回调</el-button></PageHeader
    >
    <section class="panel">
      <PageState :loading="loading" :error="error" @retry="load"
        ><el-table :data="data?.items || []"
          ><el-table-column prop="system" label="目标系统" width="120" /><el-table-column
            prop="platform"
            label="平台"
            width="120"
          /><el-table-column label="转发地址" min-width="300"
            ><template #default="{ row }"
              ><span class="mono">{{ row.url }}</span>
              <p class="subtext">{{ row.description }}</p></template
            ></el-table-column
          ><el-table-column label="状态" width="100"
            ><template #default="{ row }"
              ><el-tag :type="row.enabled ? 'success' : 'info'" effect="plain">{{
                row.enabled ? '已启用' : '已停用'
              }}</el-tag></template
            ></el-table-column
          ><el-table-column label="操作" width="185"
            ><template #default="{ row }"
              ><el-button link type="primary" @click="edit(row)">编辑</el-button
              ><el-button link :disabled="busy" @click="test(row.id)">测试</el-button
              ><el-button link type="danger" @click="remove(row.id)">删除</el-button></template
            ></el-table-column
          ></el-table
        ></PageState
      >
    </section>
    <el-alert
      title="支持企微审批与 DocuSign Connect 事件"
      description="企微接收路径 /integrations/wecom/webhooks/{system}/events：配置回调 Token 和 EncodingAESKey 后验签解密，加密入库并重试转发，也可用 approval_events.list 主动拉取。DocuSign 接收路径 /integrations/docusign/webhooks/{system}/events：配置 DOCUSIGN_HMAC_KEY 验证原始签名，失败由上游重投。"
      type="info"
      :closable="false"
    /><el-dialog v-model="dialog" :title="form.id ? '编辑回调' : '新增回调'" width="550px"
      ><el-form label-position="top"
        ><el-form-item label="业务系统" required
          ><el-select v-model="form.system"
            ><el-option
              v-for="s in systems"
              :key="s.key"
              :value="s.key"
              :label="s.display_name" /></el-select></el-form-item
        ><el-form-item label="平台"
          ><el-select v-model="form.platform"
            ><el-option value="wecom" label="企微审批" /><el-option
              value="docusign"
              label="DocuSign" /><el-option
              value="*"
              label="该系统所有平台的默认回调" /></el-select></el-form-item
        ><el-form-item label="转发地址" required
          ><el-input v-model="form.url" placeholder="https://业务系统/callback" /></el-form-item
        ><el-form-item label="HMAC 签名凭据引用"
          ><el-input
            v-model="form.secret_ref"
            placeholder="CUSTOM_CALLBACK_SECRET，可选" /></el-form-item
        ><el-form-item label="用途说明"
          ><el-input v-model="form.description" maxlength="255" /></el-form-item
        ><el-form-item label="启用配置"><el-switch v-model="form.enabled" /></el-form-item></el-form
      ><template #footer
        ><el-button @click="dialog = false">取消</el-button
        ><el-button
          type="primary"
          :disabled="!form.system || !form.url"
          :loading="busy"
          @click="save"
          >保存配置</el-button
        ></template
      ></el-dialog
    ><el-dialog
      :model-value="!!testResult"
      title="连通性测试结果"
      width="560px"
      @close="testResult = undefined"
      ><JsonViewer :value="testResult"
    /></el-dialog>
  </div>
</template>
