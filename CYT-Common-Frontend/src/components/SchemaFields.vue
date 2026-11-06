<script setup lang="ts">
import { computed } from 'vue';
import type { Schema } from '~/api/types';
import { schemaRows } from '~/utils/integration-direction';
const props = withDefaults(
  defineProps<{
    schema: Schema;
    fieldLabel?: string;
    showRequired?: boolean;
    emptyText?: string;
  }>(),
  {
    fieldLabel: '参数',
    showRequired: true,
    emptyText: '此接口无需请求参数',
  },
);
const rows = computed(() => schemaRows(props.schema));
</script>
<template>
  <el-table :data="rows" :empty-text="emptyText" size="small" class="schema-fields"
    ><el-table-column :label="fieldLabel" min-width="190"
      ><template #default="{ row }"
        ><span class="mono schema-field-name">{{ row.name }}</span
        ><el-tag
          v-if="showRequired && row.required"
          size="small"
          type="warning"
          effect="plain"
          style="margin-left: 8px"
          >必填</el-tag
        ></template
      ></el-table-column
    ><el-table-column prop="type" label="类型" width="125"
      ><template #default="{ row }"
        ><span class="schema-type">{{ row.type }}</span></template
      ></el-table-column
    ><el-table-column prop="description" label="说明" min-width="230" /><el-table-column
      prop="default"
      label="默认值 / 取值"
      min-width="125"
  /></el-table>
</template>
<style scoped>
.schema-fields {
  border: 1px solid var(--app-border);
  border-radius: 8px;
}
.schema-fields :deep(td.el-table__cell) {
  height: auto;
  padding: 14px 0;
}
.schema-fields :deep(.cell) {
  line-height: 1.8;
}
.schema-field-name {
  font-size: 12px;
  color: var(--app-text);
  overflow-wrap: anywhere;
}
.schema-type {
  color: #64748b;
  font-family: ui-monospace, Consolas, monospace;
  font-size: 11px;
  overflow-wrap: anywhere;
}
</style>
