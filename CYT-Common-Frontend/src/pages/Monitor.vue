<script setup lang="ts">
import { computed, defineAsyncComponent } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import PageHeader from '~/components/PageHeader.vue';
const route = useRoute();
const router = useRouter();
const tab = computed({
  get: () =>
    ['calls', 'audit'].includes(String(route.query.tab)) ? String(route.query.tab) : 'overview',
  set: (value) => {
    void router.replace({ query: { ...route.query, tab: value } });
  },
});
const Overview = defineAsyncComponent(() => import('./Overview.vue'));
const Calls = defineAsyncComponent(() => import('./Calls.vue'));
const Audit = defineAsyncComponent(() => import('./Audit.vue'));
</script>
<template>
  <div>
    <PageHeader
      title="运行中心"
      description="先看运行情况，再定位具体调用；配置变更可在审计记录中追踪。"
    /><el-tabs v-model="tab" class="context-tabs"
      ><el-tab-pane label="运行概览" name="overview"
        ><Overview v-if="tab === 'overview'" /></el-tab-pane
      ><el-tab-pane label="调用记录" name="calls"><Calls v-if="tab === 'calls'" /></el-tab-pane
      ><el-tab-pane label="配置审计" name="audit"><Audit v-if="tab === 'audit'" /></el-tab-pane
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
