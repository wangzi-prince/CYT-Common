<script setup lang="ts">
defineProps<{ loading: boolean; error: boolean; empty?: boolean }>();
defineEmits<{ retry: [] }>();
</script>
<template>
  <div v-if="error" class="state-block">
    <el-result icon="warning" title="暂时无法加载" sub-title="请检查服务连接，然后重新尝试。"
      ><template #extra
        ><el-button @click="$emit('retry')">重新加载</el-button></template
      ></el-result
    >
  </div>
  <div v-else-if="loading" class="state-block"><el-skeleton :rows="6" animated /></div>
  <el-empty v-else-if="empty" description="暂无记录" /><slot v-else />
</template>
