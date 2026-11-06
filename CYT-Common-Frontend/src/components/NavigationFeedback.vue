<script setup lang="ts">
import { useRouter } from 'vue-router';
import { Loading } from '@element-plus/icons-vue';
import { navigationFeedback } from '~/router';
const router = useRouter();
const { pending, failed, retry, dismiss } = navigationFeedback;
function reloadTarget() {
  if (!failed.value) return;
  window.location.hash = router.resolve(failed.value.path).href.split('#').slice(1).join('#');
  window.location.reload();
}
</script>
<template>
  <div v-if="pending" class="navigation-pending" role="status" aria-live="polite">
    <el-icon class="is-loading"><Loading /></el-icon>正在打开{{ pending.title }}…
  </div>
  <el-alert
    v-if="failed"
    class="navigation-error"
    type="error"
    :title="`未能打开${failed.title}`"
    show-icon
    :closable="false"
  >
    <p>页面加载失败，请重试；也可以刷新后打开目标页面。</p>
    <div class="navigation-error-actions">
      <el-button size="small" type="primary" @click="retry">重试</el-button
      ><el-button size="small" @click="reloadTarget">刷新打开</el-button
      ><el-button size="small" text @click="dismiss">关闭</el-button>
    </div>
  </el-alert>
</template>
<style scoped>
.navigation-pending {
  position: fixed;
  top: 73px;
  right: 24px;
  z-index: 2000;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 16px;
  border: 1px solid var(--app-border);
  border-radius: 8px;
  background: white;
  box-shadow: var(--el-box-shadow-light);
  font-size: 12px;
  color: var(--app-text-secondary);
}
.navigation-error {
  margin-bottom: 20px;
}
.navigation-error p {
  margin: 6px 0 12px;
  line-height: 1.8;
}
.navigation-error-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.navigation-error-actions .el-button {
  margin: 0;
}
@media (max-width: 760px) {
  .navigation-pending {
    top: 66px;
    right: 16px;
  }
}
</style>
