<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref, watch } from 'vue';
import { init, use, type EChartsType } from 'echarts/core';
import { LineChart } from 'echarts/charts';
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
use([LineChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer]);
const props = defineProps<{ series: { ts: string; calls: number; errors: number }[] }>();
const host = ref<HTMLDivElement>();
let chart: EChartsType | undefined;
let observer: ResizeObserver | undefined;
function render() {
  chart?.setOption({
    animation: false,
    color: ['#3b82f6', '#e47171'],
    tooltip: {
      trigger: 'axis',
      borderColor: '#e2e8f0',
      textStyle: { color: '#334155', fontSize: 12 },
    },
    legend: {
      right: 15,
      top: 5,
      itemWidth: 14,
      itemHeight: 4,
      textStyle: { color: '#64748b', fontSize: 11 },
    },
    grid: { left: 40, right: 22, top: 50, bottom: 33 },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: props.series.map((s) => s.ts.slice(-5)),
      axisTick: { show: false },
      axisLine: { lineStyle: { color: '#eaeef5' } },
      axisLabel: { color: '#64748b', fontSize: 11 },
    },
    yAxis: {
      type: 'value',
      minInterval: 1,
      name: '调用次数',
      nameTextStyle: { color: '#64748b', fontSize: 11 },
      axisLabel: { color: '#64748b', fontSize: 11 },
      splitLine: { lineStyle: { color: '#f0f2f7', type: 'dashed' } },
    },
    series: [
      {
        name: '全部调用',
        type: 'line',
        smooth: 0.2,
        showSymbol: false,
        lineStyle: { width: 2 },
        areaStyle: { opacity: 0.06 },
        data: props.series.map((s) => s.calls),
      },
      {
        name: '失败调用',
        type: 'line',
        smooth: 0.2,
        showSymbol: false,
        lineStyle: { width: 1.5 },
        data: props.series.map((s) => s.errors),
      },
    ],
  });
}
onMounted(() => {
  if (!host.value) return;
  chart = init(host.value);
  observer = new ResizeObserver(() => chart?.resize());
  observer.observe(host.value);
  render();
});
watch(() => props.series, render);
onBeforeUnmount(() => {
  observer?.disconnect();
  chart?.dispose();
});
</script>
<template>
  <div
    ref="host"
    style="height: 285px; width: 100%"
    role="img"
    aria-label="调用次数与失败次数随时间变化趋势"
  />
</template>
