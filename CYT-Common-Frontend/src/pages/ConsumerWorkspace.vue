<script setup lang="ts">
import { computed, defineAsyncComponent, onMounted, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Back, Right, Key, Lock, Document } from '@element-plus/icons-vue';
import { api } from '~/api';
import { useRequest } from '~/composables/useRequest';
import PageHeader from '~/components/PageHeader.vue';
import PageState from '~/components/PageState.vue';
import ConsumerKeys from '~/components/ConsumerKeys.vue';
const route = useRoute(),
  router = useRouter();
const system = computed(() => String(route.params.system || ''));
const tab = computed({
  get: () =>
    ['keys', 'access', 'docs'].includes(String(route.query.tab))
      ? String(route.query.tab)
      : 'overview',
  set: (value) => {
    void router.replace({ query: { ...route.query, tab: value } });
  },
});
const { data, loading, error, load } = useRequest(async () => {
  const [systems, access, keys] = await Promise.all([
    api.systems(),
    api.effective(system.value),
    api.keys(system.value),
  ]);
  return {
    caller: systems.systems.find((s) => s.key === system.value),
    allowed: access.operations.filter((o) => o.allowed).length,
    keys: keys.items.filter((k) => !k.revoked).length,
  };
});
const Grants = defineAsyncComponent(() => import('./Grants.vue'));
const Docs = defineAsyncComponent(() => import('./Docs.vue'));
onMounted(load);
watch(system, load);
</script>
<template>
  <div>
    <PageHeader
      :title="data?.caller?.display_name || '调用方接入'"
      description="在这里为一个业务系统准备密钥、分配能力并交付专属调用文档。"
      ><el-button :icon="Back" @click="router.push('/systems')">返回调用方</el-button
      ><el-button @click="router.push({ path: '/monitor', query: { tab: 'calls', system } })"
        >查看调用记录</el-button
      ><el-button type="primary" @click="router.push({ path: '/docs', query: { system } })"
        >交付接入文档</el-button
      ></PageHeader
    ><PageState :loading="loading" :error="error" @retry="load"
      ><template v-if="data?.caller"
        ><div class="consumer-summary">
          <code>{{ system }}</code
          ><el-tag :type="data.caller.enabled ? 'success' : 'info'" effect="plain">{{
            data.caller.enabled ? '已启用' : '已停用'
          }}</el-tag
          ><span>{{ data.keys }} 把有效密钥</span><span>{{ data.allowed }} 项已授权能力</span>
        </div>
        <el-alert
          v-if="!data.caller.enabled"
          title="调用方已停用，其所有密钥均无法调用网关"
          type="warning"
          :closable="false" /><el-tabs v-model="tab" class="consumer-tabs"
          ><el-tab-pane label="接入概览" name="overview"
            ><div class="consumer-steps">
              <button @click="tab = 'keys'">
                <el-icon><Key /></el-icon>
                <h2>1 准备调用密钥</h2>
                <p>
                  {{
                    data.keys
                      ? `已有 ${data.keys} 把有效密钥，可按环境管理和轮换。`
                      : '生成该系统独立的 API Key，由它的后端保存。'
                  }}
                </p>
                <span
                  >{{ data.keys ? '管理密钥' : '生成密钥' }} <el-icon><Right /></el-icon
                ></span></button
              ><button @click="tab = 'access'">
                <el-icon><Lock /></el-icon>
                <h2>2 分配业务能力</h2>
                <p>
                  {{
                    data.allowed
                      ? `已获授权 ${data.allowed} 项接口，可批量调整范围和期限。`
                      : '搜索业务能力并批量授权，敏感操作统一确认。'
                  }}
                </p>
                <span
                  >配置能力授权 <el-icon><Right /></el-icon
                ></span></button
              ><button @click="tab = 'docs'">
                <el-icon><Document /></el-icon>
                <h2>3 交付接入文档</h2>
                <p>查看快速接入与接口示例，导出可离线阅读的指南、专属 OpenAPI 和 SDK。</p>
                <span
                  >查看调用文档 <el-icon><Right /></el-icon
                ></span>
              </button>
            </div>
            <section class="panel">
              <div class="panel-body">
                <h2 class="consumer-title">调用约定</h2>
                <p class="form-hint">
                  通过 Authorization: Bearer &lt;本系统 API Key&gt; 调用已授权的 /integrations/…
                  接口。写操作使用稳定业务编号，遇到超时先核对原结果。
                </p>
                <el-button @click="router.push({ path: '/exposed', query: { system } })"
                  >浏览可接入的业务能力</el-button
                >
              </div>
            </section></el-tab-pane
          ><el-tab-pane label="调用密钥" name="keys"
            ><ConsumerKeys
              :key="system"
              :system="system"
              :enabled="data.caller.enabled"
              @changed="load" /></el-tab-pane
          ><el-tab-pane label="能力授权" name="access"
            ><Grants
              v-if="tab === 'access'"
              :key="system"
              :fixed-system="system"
              embedded
              @updated="load" /></el-tab-pane
          ><el-tab-pane label="调用文档" name="docs"
            ><Docs
              v-if="tab === 'docs'"
              :key="system"
              :fixed-system="system"
              embedded /></el-tab-pane></el-tabs></template
      ><el-empty v-else description="该调用方不存在或已被移除"
        ><el-button @click="router.push('/systems')">选择调用方</el-button></el-empty
      ></PageState
    >
  </div>
</template>
<style scoped>
.consumer-summary {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 14px;
  margin-bottom: 20px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.consumer-tabs {
  margin-top: 14px;
}
.consumer-steps {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
  margin-bottom: 20px;
}
.consumer-steps button {
  text-align: left;
  border: 1px solid var(--el-border-color-lighter);
  background: white;
  padding: 22px;
  border-radius: 12px;
  cursor: pointer;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  color: var(--el-text-color-primary);
}
.consumer-steps button > .el-icon {
  font-size: 22px;
  color: var(--el-color-primary);
}
.consumer-steps h2 {
  font-size: 16px;
  margin: 18px 0 10px;
}
.consumer-steps p {
  font-size: 13px;
  line-height: 1.8;
  color: var(--el-text-color-secondary);
  flex: 1;
  margin: 0 0 20px;
}
.consumer-steps span {
  color: var(--el-color-primary);
  font-size: 13px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.consumer-title {
  font-size: 16px;
  margin-top: 0;
}
@media (max-width: 800px) {
  .consumer-steps {
    grid-template-columns: 1fr;
  }
}
</style>
