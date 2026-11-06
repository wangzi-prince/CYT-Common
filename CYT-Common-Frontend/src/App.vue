<script setup lang="ts">
import zhCn from 'element-plus/es/locale/lang/zh-cn';
import { useUiPreferences } from '~/composables/useUiPreferences';
const { density } = useUiPreferences();
</script>
<template>
  <el-config-provider :locale="zhCn" :size="density === 'compact' ? 'small' : 'default'"
    ><RouterView
  /></el-config-provider>
</template>
