<script setup lang="ts">
import { computed } from 'vue';
import { ElMessage } from 'element-plus';
const props = defineProps<{ value: unknown }>();
const formatted = computed(() => {
  if (typeof props.value === 'string') {
    try {
      return JSON.stringify(JSON.parse(props.value), null, 2);
    } catch {
      return props.value;
    }
  }
  return JSON.stringify(props.value, null, 2);
});
async function copy() {
  try {
    await navigator.clipboard.writeText(formatted.value || '');
    ElMessage.success('已复制');
  } catch {
    ElMessage.warning('浏览器不支持剪贴板，请手动选择文本');
  }
}
</script>
<template>
  <div class="json-viewer">
    <el-button class="json-copy" size="small" @click="copy">复制</el-button>
    <pre>{{ formatted }}</pre>
  </div>
</template>
