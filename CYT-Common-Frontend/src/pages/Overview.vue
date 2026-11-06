<script setup lang="ts">
import { computed, defineAsyncComponent, onMounted, ref, watch } from 'vue';
import { Refresh, Right, Connection, CircleCheck, Timer, Warning } from '@element-plus/icons-vue';
import { useRouter } from 'vue-router';
import { api } from '~/api';
import { useRequest, dateTime } from '~/composables/useRequest';
import PageHeader from '~/components/PageHeader.vue';
import PageState from '~/components/PageState.vue';
const TrendChart = defineAsyncComponent(() => import('~/components/TrendChart.vue'));
const router = useRouter();
const hours = ref(24);
const { data, loading, error, load } = useRequest(() => api.overview(hours.value));
const metrics = computed(() => {
  const o = data.value?.overview;
  return [
    {
      label: '全部调用',
      value: o?.total_calls.toLocaleString() || '0',
      unit: '次',
      icon: Connection,
      note: '所选时间范围内的请求总数',
    },
    {
      label: '调用成功率',
      value: ((o?.success_rate || 0) * 100).toFixed(1),
      unit: '%',
      icon: CircleCheck,
      note: '成功请求占全部调用的比例',
    },
    {
      label: 'P95 响应时间',
      value: String(o?.p95_latency_ms || 0),
      unit: 'ms',
      icon: Timer,
      note: '95% 的请求响应时间低于此值',
    },
    {
      label: '失败调用',
      value: String(o?.error_count || 0),
      unit: '次',
      icon: Warning,
      note: '包含权限拒绝、限流与上游异常',
    },
  ];
});
onMounted(load);
watch(hours, load);
</script>
<template>
  <div>
    <PageHeader title="调用总览" description="掌握外部 API 的运行状况，让每一次调用清晰可见。"
      ><el-select v-model="hours" style="width: 130px"
        ><el-option :value="24" label="最近 24 小时" /><el-option
          :value="168"
          label="最近 7 天" /><el-option :value="720" label="最近 30 天" /></el-select
      ><el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button></PageHeader
    ><PageState :loading="loading" :error="error" @retry="load"
      ><div class="metric-grid">
        <article
          v-for="(metric, index) in metrics"
          :key="metric.label"
          class="metric"
          :class="`metric-${index}`"
        >
          <header>
            <span>{{ metric.label }}</span
            ><el-icon><component :is="metric.icon" /></el-icon>
          </header>
          <p>
            {{ metric.value }}<small>{{ metric.unit }}</small>
          </p>
          <footer>{{ metric.note }}</footer>
        </article>
      </div>
      <div class="overview-grid">
        <section class="panel">
          <header class="panel-head">
            <div>
              <h2>调用趋势</h2>
              <p>所选范围内的调用分布 · 时间以 UTC 计</p>
            </div>
            <el-tag type="info" effect="plain">{{ hours <= 48 ? '按小时' : '按天' }}</el-tag>
          </header>
          <div style="padding: 10px 12px"><TrendChart :series="data?.series || []" /></div>
        </section>
        <section class="panel">
          <header class="panel-head">
            <div>
              <h2>平台调用分布</h2>
              <p>按调用量排序</p>
            </div>
          </header>
          <div class="distribution">
            <el-empty
              v-if="!data?.by_platform.length"
              description="暂无平台调用"
              :image-size="75"
            />
            <div
              v-for="p in data?.by_platform.slice(0, 6)"
              :key="p.platform"
              class="distribution-row"
            >
              <header>
                <span><i />{{ p.platform }}</span
                ><strong>{{ p.calls.toLocaleString() }}<small>次</small></strong>
              </header>
              <el-progress
                :percentage="Math.round((p.calls / (data?.overview.total_calls || 1)) * 100)"
                :show-text="false"
                :stroke-width="5"
                color="#6394ef"
              />
            </div>
          </div>
        </section>
      </div>
      <section class="panel">
        <header class="panel-head">
          <div>
            <h2>最近调用</h2>
            <p>查看每次调用的结果与响应耗时</p>
          </div>
          <el-button link type="primary" :icon="Right" @click="router.push('/calls')"
            >全部记录</el-button
          >
        </header>
        <el-table :data="data?.recent || []"
          ><el-table-column label="调用操作" min-width="230"
            ><template #default="{ row }"
              ><span class="mono">{{ row.operation }}</span></template
            ></el-table-column
          ><el-table-column prop="system" label="调用系统" width="120" /><el-table-column
            label="调用结果"
            width="125"
            ><template #default="{ row }"
              ><span
                ><i class="status-dot" :class="{ error: row.status !== 'ok' }" />{{
                  row.status === 'ok' ? '成功' : '失败'
                }}</span
              ><el-tag v-if="row.mock" type="info" size="small" style="margin-left: 8px"
                >模拟</el-tag
              ></template
            ></el-table-column
          ><el-table-column label="响应耗时" width="110"
            ><template #default="{ row }"
              >{{ row.latency_ms }} <span class="muted">ms</span></template
            ></el-table-column
          ><el-table-column label="调用时间" width="180"
            ><template #default="{ row }">{{ dateTime(row.called_at) }}</template></el-table-column
          ></el-table
        >
      </section></PageState
    >
  </div>
</template>
