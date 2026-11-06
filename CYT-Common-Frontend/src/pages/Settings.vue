<script setup lang="ts">
import { computed, defineAsyncComponent } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import PageHeader from '~/components/PageHeader.vue';
const route = useRoute();
const router = useRouter();
const tab = computed({
  get: () => (route.query.tab === 'callbacks' ? 'callbacks' : 'credentials'),
  set: (value) => {
    void router.replace({ query: { ...route.query, tab: value } });
  },
});
const Credentials = defineAsyncComponent(() => import('./Credentials.vue'));
const Callbacks = defineAsyncComponent(() => import('./Callbacks.vue'));
</script>
<template>
  <div>
    <PageHeader
      title="设置"
      description="这里管理网关连接上游所需的环境、凭据和回调；业务系统的调用密钥在调用方内管理。"
    /><el-tabs v-model="tab" class="context-tabs"
      ><el-tab-pane label="环境与上游凭据" name="credentials"
        ><Credentials v-if="tab === 'credentials'" /></el-tab-pane
      ><el-tab-pane label="事件回调" name="callbacks"
        ><Callbacks v-if="tab === 'callbacks'" /></el-tab-pane
    ></el-tabs>
  </div>
</template>
<style scoped>
.context-tabs :deep(.page-header) {
  margin: 0 0 14px;
  justify-content: flex-end;
}
.context-tabs :deep(.page-header > div:first-child) {
  display: none;
}
</style>
