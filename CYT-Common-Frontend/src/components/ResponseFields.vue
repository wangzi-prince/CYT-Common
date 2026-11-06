<script setup lang="ts">
import { computed } from 'vue';
import type { Operation, Schema } from '~/api/types';
import { responseEnvelope } from '~/utils/integration-direction';
import SchemaFields from './SchemaFields.vue';
import JsonViewer from './JsonViewer.vue';
const props = defineProps<{ operation?: Operation }>();
const schema = computed(() => responseEnvelope(props.operation));
const httpWrapper = computed(() =>
  Boolean(props.operation?.response_schema?.properties?.status_code),
);
const missingContract = computed(() => {
  const response = props.operation?.response_schema;
  return !response?.properties || (httpWrapper.value && !response.properties.data?.properties);
});
const failure: Schema = {
  type: 'object',
  properties: {
    ok: { type: 'boolean', description: 'false 表示请求失败' },
    error: {
      type: 'object',
      description: '错误信息',
      properties: {
        type: { type: 'string', description: '错误分类' },
        code: { type: 'string', description: '平台或网关返回的错误码' },
        message: { type: 'string', description: '可读的错误说明' },
        trace_id: { type: 'string', description: '请求追踪标识，用于定位调用日志' },
        retryable: { type: 'boolean', description: '是否建议重试本次请求' },
        retry_after: { type: 'integer', description: '建议等待的秒数；限流等场景按需返回' },
        detail: { description: '错误补充信息，按实际错误返回' },
      },
    },
  },
};
</script>
<template>
  <section class="response-fields">
    <div class="response-context">
      <span class="response-caption">完整 HTTP 响应</span><span>SDK 返回 <code>data</code></span
      ><span v-if="httpWrapper">业务字段位于 <code>data.data</code></span>
    </div>
    <el-alert
      v-if="missingContract"
      class="contract-notice"
      :title="
        operation?.response_schema?.properties?.data?.description ||
        operation?.response_schema?.description ||
        '尚未登记业务响应字段，请在接口配置中补充响应契约。'
      "
      type="info"
      :closable="false"
      show-icon
    />
    <SchemaFields
      :schema="schema"
      field-label="响应字段"
      :show-required="false"
      empty-text="暂无响应字段"
    />
    <el-collapse class="response-more">
      <el-collapse-item title="失败响应字段" name="errors"
        ><p class="response-note">请求失败时返回以下错误信息，字段以实际错误为准。</p>
        <SchemaFields :schema="failure" field-label="错误字段" :show-required="false"
      /></el-collapse-item>
      <el-collapse-item title="完整响应 Schema" name="schema"
        ><JsonViewer :value="schema"
      /></el-collapse-item>
    </el-collapse>
  </section>
</template>
<style scoped>
.response-context {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px 20px;
  margin-bottom: 16px;
  font-size: 12px;
  color: var(--app-text-muted);
  line-height: 1.8;
}
.response-caption {
  color: var(--app-text);
  font-weight: 600;
}
.response-context code {
  background: var(--app-surface-soft);
  color: var(--app-text-secondary);
  padding: 2px 6px;
  border: 1px solid var(--app-divider);
  border-radius: 4px;
}
.contract-notice {
  margin-bottom: 16px;
}
.response-more {
  margin-top: 24px;
  border-bottom: 0;
}
.response-more :deep(.el-collapse-item__header) {
  font-size: 13px;
  font-weight: 500;
}
.response-note {
  color: var(--app-text-muted);
  font-size: 12px;
  line-height: 1.8;
  margin-bottom: 12px;
}
</style>
