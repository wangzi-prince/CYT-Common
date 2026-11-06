<script setup lang="ts">
import { computed } from 'vue';
import type { Operation } from '~/api/types';
import { responseEnvelope } from '~/utils/integration-direction';
import SchemaFields from './SchemaFields.vue';
import JsonViewer from './JsonViewer.vue';
const props = defineProps<{ operation?: Operation }>();
const schema = computed(() => responseEnvelope(props.operation));
</script>
<template>
  <section>
    <p class="form-hint">
      以下路径对应完整 HTTP 响应。SDK 成功时直接返回 data；配置型 HTTP 接口的业务内容位于
      data.data。入参“必填”不等于响应字段总会返回，上游权限及实际业务状态仍可能影响字段。
    </p>
    <el-alert
      v-if="
        !operation?.response_schema?.properties ||
        (!operation.response_schema.properties.data?.properties &&
          operation.response_schema.properties.status_code)
      "
      :title="
        operation?.response_schema?.properties?.data?.description ||
        operation?.response_schema?.description ||
        '此接口尚未登记业务响应字段契约，当前只说明网关协议。'
      "
      type="info"
      :closable="false"
    /><SchemaFields :schema="schema" />
    <h3 class="docs-heading">失败响应字段</h3>
    <p class="form-hint">
      ok=false；error.type 为错误分类，error.code 为平台/网关错误码，error.message
      为可读说明，error.trace_id 用于追踪，error.retryable 表示是否建议重试，error.detail
      为补充信息。字段按实际错误返回。
    </p>
    <el-collapse
      ><el-collapse-item title="完整响应 Schema"><JsonViewer :value="schema" /></el-collapse-item
    ></el-collapse>
  </section>
</template>
