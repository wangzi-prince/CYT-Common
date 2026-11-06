<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { CaretRight, Refresh } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { api } from '~/api';
import type { Operation, SystemItem } from '~/api/types';
import { useRequest, useAction, confirm } from '~/composables/useRequest';
import { operationExample } from '~/utils/schema';
import PageHeader from '~/components/PageHeader.vue';
import PageState from '~/components/PageState.vue';
import SchemaFields from '~/components/SchemaFields.vue';
import JsonViewer from '~/components/JsonViewer.vue';
import ResponseFields from '~/components/ResponseFields.vue';
import { operationDirection, type Direction } from '~/utils/integration-direction';
const route = useRoute();
const router = useRouter();
const { data, loading, error, load } = useRequest(api.registry);
const { busy, run } = useAction();
const operation = ref(String(route.query.operation || ''));
const direction = ref<Direction>(route.query.direction === 'internal' ? 'internal' : 'third_party');
const operations = computed(() =>
  (data.value?.operations || []).filter((o) => operationDirection(o) === direction.value),
);
watch(direction, () => {
  if (!operations.value.some((o) => o.key === operation.value))
    operation.value = operations.value[0]?.key || '';
});
watch(
  () => route.query.operation,
  (key) => {
    if (key) operation.value = String(key);
  },
);
const system = ref('');
const systems = ref<SystemItem[]>([]);
const params = ref('{}');
const result = ref<unknown>();
const active = ref('parameters');
const idempotency = ref('');
const selected = computed(() => data.value?.operations.find((o) => o.key === operation.value));
function reset() {
  params.value = JSON.stringify(selected.value ? operationExample(selected.value) : {}, null, 2);
  result.value = undefined;
  idempotency.value = '';
}
watch(operation, reset);
onMounted(async () => {
  await load();
  if (operation.value && !route.query.direction)
    direction.value =
      data.value?.operations.find((o) => o.key === operation.value)?.integration_kind ||
      'third_party';
  const response = await run(api.systems);
  systems.value = response?.systems.filter((s) => s.enabled) || [];
  system.value =
    systems.value.find((s) => s.key === route.query.system)?.key || systems.value[0]?.key || '';
  if (!operation.value) operation.value = operations.value[0]?.key || '';
  else reset();
});
async function execute() {
  let body: unknown;
  try {
    body = JSON.parse(params.value);
    if (!body || Array.isArray(body) || typeof body !== 'object') throw new Error();
  } catch {
    ElMessage.error('参数必须是合法的 JSON 对象');
    return;
  }
  if (
    selected.value?.sensitive &&
    !(await confirm(
      `即将以 ${system.value} 身份执行敏感操作「${selected.value.summary}」。确认继续？`,
      '确认执行',
    ))
  )
    return;
  const response = await run(() =>
    api.simulate({
      system: system.value,
      operation: operation.value,
      params: body,
      idempotency_key: idempotency.value || undefined,
    }),
  );
  if (response) {
    result.value = response;
    active.value = 'result';
  }
}
</script>
<template>
  <div>
    <PageHeader
      title="接口调试"
      description="以指定业务系统身份执行完整网关流程，验证参数、权限和上游结果。"
    /><PageState :loading="loading" :error="error" @retry="load"
      ><section class="panel">
        <div class="panel-body">
          <el-radio-group v-model="direction" aria-label="调试调用方向"
            ><el-radio-button value="third_party">第三方服务调试</el-radio-button
            ><el-radio-button value="internal">自有能力调试</el-radio-button></el-radio-group
          >
          <p class="form-hint">
            {{
              direction === 'third_party'
                ? '验证我们调用第三方服务时的参数、授权与返回结果。'
                : '验证其他系统调用我们自己的业务接口时的入参和响应契约。'
            }}
          </p>
          <el-form label-position="top"
            ><div class="playground-selectors">
              <el-form-item label="选择操作" required
                ><el-select v-model="operation" filterable placeholder="搜索操作标识"
                  ><el-option
                    v-for="o in operations"
                    :key="o.key"
                    :value="o.key"
                    :label="`${o.key} · ${o.summary}`" /></el-select></el-form-item
              ><el-form-item label="调用方系统" required
                ><el-select v-model="system"
                  ><el-option
                    v-for="s in systems"
                    :key="s.key"
                    :value="s.key"
                    :label="s.display_name" /></el-select
              ></el-form-item>
            </div>
            <template v-if="selected"
              ><div class="endpoint">
                <el-tag type="success" effect="plain">POST</el-tag
                ><span class="mono">{{ selected.path }}</span
                ><el-tag v-if="selected.sensitive" type="warning" effect="plain">敏感操作</el-tag>
              </div>
              <p class="form-hint">{{ selected.description || selected.summary }}</p></template
            ><el-alert
              title="调试会执行实际网关流程"
              description="系统授权、配额、幂等和调用日志均生效；真实模式下可能产生业务影响。返回 meta.mock=true 时才是模拟调用。"
              type="info"
              :closable="false" /><el-tabs v-model="active" style="margin-top: 22px"
              ><el-tab-pane label="请求参数" name="parameters"
                ><el-input
                  v-model="params"
                  type="textarea"
                  :rows="16"
                  class="request-editor"
                  spellcheck="false" /><el-form-item
                  v-if="selected?.idempotent"
                  :label="
                    selected?.tags.includes('idempotency_required')
                      ? '幂等键（可用请求体 request_id）'
                      : '幂等键（可选）'
                  "
                  style="margin-top: 20px"
                  ><el-input
                    v-model="idempotency"
                    placeholder="同一业务请求使用相同的唯一键"
                    maxlength="128" /></el-form-item></el-tab-pane
              ><el-tab-pane label="参数说明" name="schema"
                ><SchemaFields v-if="selected" :schema="selected.schema" /></el-tab-pane
              ><el-tab-pane label="响应字段说明" name="response-schema"
                ><ResponseFields v-if="selected" :operation="selected" /><el-empty
                  v-else
                  description="当前方向尚无已启用接口" /></el-tab-pane
              ><el-tab-pane label="响应结果" name="result"
                ><JsonViewer v-if="result" :value="result" /><el-empty
                  v-else
                  description="执行请求后查看响应" /></el-tab-pane></el-tabs
          ></el-form>
        </div>
        <footer class="playground-footer">
          <el-button :icon="Refresh" @click="reset">重置参数</el-button
          ><el-button
            type="primary"
            :icon="CaretRight"
            :loading="busy"
            :disabled="!operation || !system"
            @click="execute"
            >执行请求</el-button
          >
        </footer>
      </section></PageState
    >
    <el-button
      @click="
        route.query.system
          ? router.push({
              path: `/systems/${encodeURIComponent(String(route.query.system))}`,
              query: { tab: 'docs', operation, direction },
            })
          : direction === 'internal'
            ? router.push('/exposed')
            : router.push({ path: '/registry', query: { platform: operation.split('.')[0] } })
      "
      >{{ route.query.system ? '返回调用方' : '返回服务接口' }}</el-button
    >
  </div>
</template>
