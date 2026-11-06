<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { Search, Right } from '@element-plus/icons-vue';
import type { InputInstance } from 'element-plus';
import { destinations } from '~/layouts/navigation';
const router = useRouter();
const open = ref(false);
const query = ref('');
const active = ref(0);
const input = ref<InputInstance>();
const results = computed(() => {
  const words = query.value.trim().toLowerCase().split(/\s+/).filter(Boolean);
  return destinations.filter((item) =>
    words.every((word) =>
      `${item.label} ${item.description} ${item.keywords} ${item.path}`
        .toLowerCase()
        .includes(word),
    ),
  );
});
watch(query, () => {
  active.value = 0;
});
function show() {
  query.value = '';
  active.value = 0;
  open.value = true;
}
function select(index: number) {
  const item = results.value[index];
  if (!item) return;
  open.value = false;
  void router.push(item.path);
}
async function move(direction: number) {
  if (!results.value.length) return;
  active.value = (active.value + direction + results.value.length) % results.value.length;
  await nextTick();
  document
    .getElementById(`quick-destination-${active.value}`)
    ?.scrollIntoView({ block: 'nearest' });
}
function handleKeydown(event: KeyboardEvent) {
  if (event.isComposing) return;
  if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
    event.preventDefault();
    void move(event.key === 'ArrowDown' ? 1 : -1);
  } else if (event.key === 'Enter') {
    event.preventDefault();
    select(active.value);
  }
}
function shortcut(event: KeyboardEvent) {
  if (
    event.isComposing ||
    event.altKey ||
    !(event.ctrlKey || event.metaKey) ||
    event.key.toLowerCase() !== 'k'
  )
    return;
  event.preventDefault();
  event.stopImmediatePropagation();
  if (open.value) open.value = false;
  else show();
}
onMounted(() => window.addEventListener('keydown', shortcut, true));
onUnmounted(() => window.removeEventListener('keydown', shortcut, true));
</script>
<template>
  <button
    class="quick-navigation-trigger"
    aria-label="快捷导航"
    aria-haspopup="dialog"
    @click="show"
  >
    <el-icon><Search /></el-icon><span>搜索功能、快速前往…</span><kbd>Ctrl K</kbd>
  </button>
  <el-dialog
    v-model="open"
    title="快捷导航"
    class="quick-navigation-dialog"
    width="580px"
    top="12vh"
    append-to-body
    @opened="input?.focus()"
  >
    <el-input
      ref="input"
      v-model="query"
      :prefix-icon="Search"
      size="large"
      clearable
      placeholder="搜索功能，如：凭据、调试、新增接口"
      aria-label="搜索功能"
      container-role="combobox"
      aria-autocomplete="list"
      :aria-expanded="open"
      aria-controls="quick-destinations"
      :aria-activedescendant="results.length ? `quick-destination-${active}` : undefined"
      @keydown="handleKeydown"
    />
    <p class="quick-navigation-caption">
      {{ query ? `找到 ${results.length} 个功能` : '快速前往' }}
    </p>
    <div
      id="quick-destinations"
      class="quick-navigation-results"
      role="listbox"
      aria-label="功能列表"
    >
      <button
        v-for="(item, index) in results"
        :id="`quick-destination-${index}`"
        :key="item.path"
        type="button"
        role="option"
        tabindex="-1"
        :aria-selected="active === index"
        :class="{ 'is-active': active === index }"
        @click="select(index)"
      >
        <span class="quick-navigation-icon"
          ><el-icon><component :is="item.icon" /></el-icon
        ></span>
        <span class="quick-navigation-copy"
          ><strong
            >{{ item.label }}<small>{{ item.group }}</small></strong
          ><span>{{ item.description }}</span></span
        >
        <el-icon><Right /></el-icon>
      </button>
      <el-empty
        v-if="!results.length"
        description="没有匹配的功能，试试其他关键词"
        :image-size="64"
      />
    </div>
    <template #footer
      ><div class="quick-navigation-footer">
        <span><kbd>↑</kbd><kbd>↓</kbd> 选择 <kbd>Enter</kbd> 前往</span
        ><span><kbd>Esc</kbd> 关闭</span>
      </div></template
    >
  </el-dialog>
</template>
