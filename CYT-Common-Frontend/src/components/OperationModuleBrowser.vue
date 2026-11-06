<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { Search, Grid } from '@element-plus/icons-vue';
import { operationModules, type ModuleOperation } from '~/utils/operation-modules';

const props = defineProps<{
  modelValue: string;
  operations: ModuleOperation[];
  matching: ModuleOperation[];
}>();
const emit = defineEmits<{ 'update:modelValue': [value: string] }>();
const search = ref('');
watch(
  () => props.operations,
  () => {
    search.value = '';
  },
);
const modules = computed(() => operationModules(props.operations, props.matching));
const visibleModules = computed(() =>
  modules.value.filter((module) =>
    module.name.toLowerCase().includes(search.value.trim().toLowerCase()),
  ),
);
const count = computed(() =>
  props.modelValue
    ? modules.value.find((module) => module.name === props.modelValue)?.count || 0
    : props.matching.length,
);
</script>

<template>
  <div class="module-browser">
    <aside class="module-sidebar" aria-label="业务模块">
      <div class="module-sidebar-heading">
        <h2>
          <el-icon><Grid /></el-icon>业务模块
        </h2>
        <span>{{ modules.length }}</span>
      </div>
      <p>根据现有接口自动归类</p>
      <el-input
        v-if="modules.length > 7"
        v-model="search"
        :prefix-icon="Search"
        clearable
        size="small"
        placeholder="查找模块"
        aria-label="查找业务模块"
        class="module-search"
      />
      <nav class="module-list" aria-label="业务模块筛选">
        <button
          type="button"
          :class="{ 'is-active': !modelValue }"
          :aria-pressed="!modelValue"
          @click="emit('update:modelValue', '')"
        >
          <span>全部模块</span><b>{{ matching.length }}</b>
        </button>
        <button
          v-for="module in visibleModules"
          :key="module.name"
          type="button"
          :class="{ 'is-active': modelValue === module.name, 'is-empty': !module.count }"
          :aria-pressed="modelValue === module.name"
          :title="`${module.name}：${module.count} 项匹配，共 ${module.total} 个接口`"
          @click="emit('update:modelValue', module.name)"
        >
          <span>{{ module.name }}</span
          ><b>{{ module.count }}</b>
        </button>
        <span v-if="search && !visibleModules.length" class="module-no-match">没有匹配的模块</span>
      </nav>
      <el-select
        :model-value="modelValue"
        class="module-mobile-select"
        filterable
        aria-label="筛选业务模块"
        @update:model-value="emit('update:modelValue', $event)"
      >
        <el-option value="" :label="`全部模块 · ${matching.length}`" />
        <el-option
          v-for="module in modules"
          :key="module.name"
          :value="module.name"
          :label="`${module.name} · ${module.count}`"
        />
      </el-select>
    </aside>
    <div class="module-content">
      <div class="module-result-heading">
        <div>
          <h2>{{ modelValue || '全部模块' }}</h2>
          <span>{{ count }} 个匹配接口</span>
        </div>
        <el-button v-if="modelValue" link type="primary" @click="emit('update:modelValue', '')"
          >查看全部模块</el-button
        >
      </div>
      <slot />
    </div>
  </div>
</template>

<style scoped>
.module-browser {
  display: grid;
  grid-template-columns: 186px minmax(0, 1fr);
  border-top: 1px solid var(--app-border);
}
.module-sidebar {
  padding: 18px 12px;
  background: var(--el-fill-color-lighter);
  border-right: 1px solid var(--app-border);
  min-width: 0;
}
.module-sidebar-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 8px;
  gap: 8px;
}
.module-sidebar-heading h2 {
  display: flex;
  align-items: center;
  gap: 7px;
  margin: 0;
  font-size: 13px;
}
.module-sidebar-heading > span {
  color: var(--app-text-secondary);
  font-size: 12px;
}
.module-sidebar > p {
  margin: 9px 8px 16px;
  font-size: 11px;
  color: var(--app-text-secondary);
  line-height: 1.6;
}
.module-search {
  margin-bottom: 10px;
}
.module-list {
  display: flex;
  flex-direction: column;
  gap: 5px;
  max-height: 600px;
  overflow-y: auto;
}
.module-list button {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  width: 100%;
  padding: 10px;
  background: transparent;
  border: 1px solid transparent;
  border-radius: 7px;
  color: var(--app-text-secondary);
  font: inherit;
  font-size: 12px;
  text-align: left;
  cursor: pointer;
}
.module-list button > span {
  overflow-wrap: anywhere;
  line-height: 1.5;
}
.module-list button b {
  min-width: 23px;
  flex-shrink: 0;
  border-radius: 5px;
  padding: 1px 5px;
  color: var(--app-text-secondary);
  background: var(--el-fill-color);
  font-size: 11px;
  font-weight: 500;
  text-align: center;
}
.module-list button:hover {
  background: var(--el-fill-color);
}
.module-list button.is-active {
  background: var(--el-color-primary-light-9);
  border-color: var(--el-color-primary-light-7);
  color: var(--el-color-primary);
  font-weight: 600;
}
.module-list button.is-active b {
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-8);
}
.module-list button:focus-visible {
  outline: 2px solid var(--el-color-primary);
  outline-offset: -2px;
}
.module-list button.is-empty:not(.is-active) {
  opacity: 0.6;
}
.module-no-match {
  padding: 12px 8px;
  color: var(--app-text-secondary);
  font-size: 12px;
}
.module-content {
  min-width: 0;
}
.module-result-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 16px 18px;
}
.module-result-heading > div {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 10px;
  min-width: 0;
}
.module-result-heading h2 {
  margin: 0;
  font-size: 14px;
  line-height: 1.5;
  overflow-wrap: anywhere;
}
.module-result-heading span {
  font-size: 12px;
  color: var(--app-text-secondary);
}
.module-result-heading .el-button {
  font-size: 12px;
  flex-shrink: 0;
}
.module-mobile-select {
  display: none;
}
@media (max-width: 900px) {
  .module-browser {
    grid-template-columns: minmax(0, 1fr);
  }
  .module-sidebar {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 14px 16px;
    border-right: 0;
    border-bottom: 1px solid var(--app-border);
  }
  .module-sidebar-heading {
    padding: 0;
    flex-shrink: 0;
  }
  .module-sidebar-heading > span,
  .module-sidebar > p,
  .module-search,
  .module-list {
    display: none;
  }
  .module-mobile-select {
    display: block;
    flex: 1;
    min-width: 0;
  }
}
</style>
