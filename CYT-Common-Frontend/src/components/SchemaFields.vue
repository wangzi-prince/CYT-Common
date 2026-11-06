<script setup lang="ts">
import { computed } from 'vue';
import type { Schema } from '~/api/types';
import { schemaRows } from '~/utils/integration-direction';
const props = defineProps<{ schema: Schema }>();
const rows = computed(() => schemaRows(props.schema));
</script>
<template>
  <el-table :data="rows" size="small"
    ><el-table-column label="参数" min-width="160"
      ><template #default="{ row }"
        ><span class="mono">{{ row.name }}</span
        ><el-tag
          v-if="row.required"
          size="small"
          type="warning"
          effect="plain"
          style="margin-left: 8px"
          >必填</el-tag
        ></template
      ></el-table-column
    ><el-table-column prop="type" label="类型" width="125" /><el-table-column
      prop="description"
      label="说明"
      min-width="260" /><el-table-column prop="default" label="默认值" width="120"
  /></el-table>
</template>
