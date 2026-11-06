<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import {
  Refresh,
  Search,
  Plus,
  Star,
  StarFilled,
  Grid,
  List,
  MoreFilled,
  QuestionFilled,
  DocumentCopy,
} from '@element-plus/icons-vue';
import { ElMessage, type InputInstance } from 'element-plus';
import { api } from '~/api';
import { useRequest } from '~/composables/useRequest';
import {
  buildPlatformDirectory,
  needsAttention,
  platformPage,
  selectPlatforms,
  type DirectoryPlatform,
} from '~/utils/platform-directory';
import PageHeader from '~/components/PageHeader.vue';
import PageState from '~/components/PageState.vue';
const route = useRoute();
const router = useRouter();
const favoriteStorage = 'cyt_common_favorite_platforms';
const viewStorage = 'cyt_common_platform_view';
function stored(key: string): unknown {
  try {
    return JSON.parse(localStorage.getItem(key) || 'null');
  } catch {
    return null;
  }
}
function persist(key: string, value: unknown) {
  try {
    localStorage.setItem(key, JSON.stringify(value));
  } catch {
    /* Browsing remains available when storage is unavailable. */
  }
}
const savedFavorites = stored(favoriteStorage);
const favorites = ref(
  new Set<string>(
    Array.isArray(savedFavorites)
      ? savedFavorites.filter((key): key is string => typeof key === 'string')
      : [],
  ),
);
function option(value: unknown, choices: string[], fallback: string) {
  const text = String(value || '');
  return choices.includes(text) ? text : fallback;
}
function number(value: unknown, fallback: number) {
  const parsed = Number(value);
  return Number.isInteger(parsed) && parsed > 0 ? parsed : fallback;
}
const q = ref(String(route.query.q || ''));
const source = ref(option(route.query.source, ['all', 'builtin', 'custom'], 'all'));
const kind = ref('third_party');
const state = ref(
  option(route.query.state, ['all', 'configured', 'pending', 'mock', 'disabled'], 'all'),
);
const scope = ref(option(route.query.scope, ['all', 'favorites', 'attention'], 'all'));
const sort = ref(option(route.query.sort, ['recommended', 'name', 'operations'], 'recommended'));
const view = ref(option(route.query.view || stored(viewStorage), ['list', 'cards'], 'list'));
const phoneViewport = window.matchMedia('(max-width: 600px)');
const phone = ref(phoneViewport.matches);
const effectiveView = computed(() => (phone.value ? 'cards' : view.value));
const page = ref(number(route.query.page, 1));
const jumpPage = ref(String(page.value));
watch(page, (value) => {
  jumpPage.value = String(value);
});
const sizes = computed(() => (effectiveView.value === 'cards' ? [12, 24, 48] : [20, 50, 100]));
const pageSize = ref(
  sizes.value.includes(number(route.query.size, sizes.value[0]!))
    ? number(route.query.size, sizes.value[0]!)
    : sizes.value[0]!,
);
const selectedKey = ref('');
const searchInput = ref<InputInstance>();
const viewport = window.matchMedia('(max-width: 1100px)');
const compact = ref(viewport.matches);
const { data, loading, error, load } = useRequest(async () => {
  const [platforms, modules] = await Promise.all([api.platforms(), api.modules()]);
  return { platforms: buildPlatformDirectory(platforms.platforms, modules.items) };
});
const platforms = computed(() =>
  (data.value?.platforms || []).filter((p) => p.kind === 'third_party'),
);
const counts = computed(() => ({
  all: platforms.value.length,
  favorites: platforms.value.filter((p) => favorites.value.has(p.platform)).length,
  attention: platforms.value.filter(needsAttention).length,
}));
const filtered = computed(() =>
  selectPlatforms(
    platforms.value,
    {
      q: q.value,
      source: source.value,
      kind: kind.value,
      state: state.value,
      scope: scope.value,
      sort: sort.value,
    },
    favorites.value,
  ),
);
const current = computed(() => platformPage(filtered.value, page.value, pageSize.value));
const selected = computed(() => platforms.value.find((p) => p.platform === selectedKey.value));
const hasFilters = computed(
  () => !!q.value || source.value !== 'all' || state.value !== 'all' || scope.value !== 'all',
);
let readingRoute = false;
watch([q, source, kind, state, scope, sort, pageSize], () => {
  if (!readingRoute) page.value = 1;
});
watch(view, (value) => persist(viewStorage, value));
watch(effectiveView, () => {
  if (!readingRoute) {
    page.value = 1;
    pageSize.value = sizes.value[0]!;
  }
});
watch(
  [q, source, kind, state, scope, sort, view, page, pageSize],
  () => {
    if (readingRoute || route.path !== '/platforms') return;
    void router.replace({
      query: {
        q: q.value || undefined,
        source: source.value === 'all' ? undefined : source.value,
        kind: undefined,
        state: state.value === 'all' ? undefined : state.value,
        scope: scope.value === 'all' ? undefined : scope.value,
        sort: sort.value === 'recommended' ? undefined : sort.value,
        view: view.value === 'list' ? undefined : view.value,
        page: page.value === 1 ? undefined : page.value,
        size: pageSize.value === sizes.value[0] ? undefined : pageSize.value,
      },
    });
  },
  { flush: 'post' },
);
watch(
  () => route.query,
  async (query) => {
    readingRoute = true;
    q.value = String(query.q || '');
    source.value = option(query.source, ['all', 'builtin', 'custom'], 'all');
    kind.value = 'third_party';
    state.value = option(query.state, ['all', 'configured', 'pending', 'mock', 'disabled'], 'all');
    scope.value = option(query.scope, ['all', 'favorites', 'attention'], 'all');
    sort.value = option(query.sort, ['recommended', 'name', 'operations'], 'recommended');
    view.value = option(query.view || stored(viewStorage), ['list', 'cards'], 'list');
    page.value = number(query.page, 1);
    pageSize.value = sizes.value.includes(number(query.size, sizes.value[0]!))
      ? number(query.size, sizes.value[0]!)
      : sizes.value[0]!;
    await nextTick();
    readingRoute = false;
  },
);
watch([() => filtered.value.length, loading], () => {
  if (!loading.value && data.value) page.value = current.value.page;
});
function resetFilters() {
  q.value = '';
  source.value = 'all';
  kind.value = 'third_party';
  state.value = 'all';
  scope.value = 'all';
}
function jumpToPage() {
  const target = Number(jumpPage.value);
  if (!Number.isInteger(target) || target < 1) {
    ElMessage.info('请输入大于 0 的整数页码');
    return;
  }
  page.value = Math.min(target, Math.max(1, Math.ceil(filtered.value.length / pageSize.value)));
  jumpPage.value = String(page.value);
}
function toggleFavorite(platform: DirectoryPlatform) {
  const next = new Set(favorites.value);
  if (next.has(platform.platform)) next.delete(platform.platform);
  else next.add(platform.platform);
  favorites.value = next;
  persist(favoriteStorage, [...next]);
}
function favoriteLabel(platform: DirectoryPlatform) {
  return (favorites.value.has(platform.platform) ? '取消常用 ' : '设为常用 ') + platform.platform;
}
function statusLabel(platform: DirectoryPlatform) {
  if (platform.state === 'configured') return platform.module ? '连接已配置' : '凭据已配齐';
  return (
    ({ pending: '待配置凭据', mock: '模拟模式', disabled: '已停用' } as Record<string, string>)[
      platform.state
    ] || ''
  );
}
function statusType(platform: DirectoryPlatform): 'success' | 'warning' | 'info' {
  return platform.state === 'configured'
    ? 'success'
    : platform.state === 'pending' || platform.state === 'mock'
      ? 'warning'
      : 'info';
}
function connection(platform: DirectoryPlatform) {
  if (!platform.module) return '内置适配器';
  try {
    return new URL(platform.module.base_url).host;
  } catch {
    return platform.module.base_url;
  }
}
function openOperations(platform: DirectoryPlatform) {
  void router.push({ path: '/registry', query: { platform: platform.platform } });
}
function addOperation(platform: DirectoryPlatform) {
  void router.push({
    path: '/modules',
    query: { tab: 'apis', action: 'create', platform: platform.platform },
  });
}
function configure(platform: DirectoryPlatform) {
  void router.push(
    platform.module
      ? { path: '/modules', query: { tab: 'modules', edit: platform.module.id } }
      : { path: '/settings', query: { tab: 'credentials', platform: platform.platform } },
  );
}
function createPlatform() {
  void router.push({
    path: '/modules',
    query: {
      tab: 'modules',
      action: 'create',
      kind: kind.value === 'internal' ? 'internal' : 'third_party',
    },
  });
}
async function copyAddress(address: string) {
  try {
    await navigator.clipboard.writeText(address);
    ElMessage.success('连接地址已复制');
  } catch {
    ElMessage.info('请在详情中选择地址复制');
  }
}
function shortcut(event: KeyboardEvent) {
  if (event.isComposing) return;
  const target = event.target as HTMLElement | null;
  const typing = !!target?.closest('input, textarea, [contenteditable="true"]');
  const modalOpen = [...document.querySelectorAll('.el-overlay')].some(
    (el) => el.getClientRects().length > 0,
  );
  if (
    !event.defaultPrevented &&
    !typing &&
    event.key === '/' &&
    !event.ctrlKey &&
    !event.metaKey &&
    !event.altKey &&
    !modalOpen
  ) {
    event.preventDefault();
    searchInput.value?.focus();
  }
}
function resize(event: MediaQueryListEvent) {
  compact.value = event.matches;
}
function resizePhone(event: MediaQueryListEvent) {
  phone.value = event.matches;
}
onMounted(() => {
  if (route.query.kind === 'internal') {
    void router.replace('/exposed');
    return;
  }
  void load();
  viewport.addEventListener('change', resize);
  phoneViewport.addEventListener('change', resizePhone);
  window.addEventListener('keydown', shortcut);
});
onUnmounted(() => {
  viewport.removeEventListener('change', resize);
  phoneViewport.removeEventListener('change', resizePhone);
  window.removeEventListener('keydown', shortcut);
});
</script>
<template>
  <div class="platform-directory">
    <PageHeader
      title="服务接入"
      description="我们调用第三方：管理领星、企微等平台的连接、凭据与接口。自有系统提供给其他系统的接口请进入「对外能力」。"
    >
      <el-popover placement="bottom-end" :width="360" trigger="click">
        <template #reference
          ><el-button text :icon="QuestionFilled" aria-label="了解服务与凭据"
            >接入说明</el-button
          ></template
        >
        <div class="directory-help">
          <strong>上游服务凭据</strong>
          <p>供网关访问第三方服务，在服务连接或环境与凭据中配置。</p>
          <strong>调用方密钥与授权</strong>
          <p>供业务系统访问本网关，在调用方系统与操作授权中管理。</p>
          <el-button link type="primary" @click="router.push('/systems')">管理调用系统</el-button>
        </div>
      </el-popover>
      <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      <el-button type="primary" :icon="Plus" @click="createPlatform">接入新服务</el-button>
    </PageHeader>
    <section class="panel directory-panel">
      <div class="directory-collections" aria-label="服务集合">
        <button :class="{ active: scope === 'all' }" @click="scope = 'all'">
          全部服务 <span>{{ counts.all }}</span>
        </button>
        <button :class="{ active: scope === 'favorites' }" @click="scope = 'favorites'">
          <el-icon><Star /></el-icon>常用服务 <span>{{ counts.favorites }}</span>
        </button>
        <button :class="{ active: scope === 'attention' }" @click="scope = 'attention'">
          需要处理 <span :class="{ attention: counts.attention > 0 }">{{ counts.attention }}</span>
        </button>
        <div v-if="!phone" class="directory-view-switch" role="group" aria-label="显示方式">
          <el-button
            :class="{ selected: view === 'list' }"
            :icon="List"
            :aria-pressed="view === 'list'"
            aria-label="列表视图"
            @click="view = 'list'"
          />
          <el-button
            :class="{ selected: view === 'cards' }"
            :icon="Grid"
            :aria-pressed="view === 'cards'"
            aria-label="卡片视图"
            @click="view = 'cards'"
          />
        </div>
      </div>
      <div class="directory-toolbar">
        <div class="directory-search">
          <el-input
            ref="searchInput"
            v-model="q"
            :prefix-icon="Search"
            clearable
            placeholder="搜索名称、标识、域名或用途"
            aria-label="搜索服务"
            ><template #suffix><kbd>/</kbd></template></el-input
          >
          <p>支持空格分隔多个关键词，准确匹配优先。</p>
        </div>
        <div class="directory-filter-set">
          <el-select v-if="source !== 'all'" v-model="source" aria-label="连接方式"
            ><el-option value="all" label="全部来源" /><el-option
              value="builtin"
              label="内置适配器" /><el-option value="custom" label="自定义 HTTP"
          /></el-select>
          <el-select v-model="state" aria-label="服务状态"
            ><el-option value="all" label="全部状态" /><el-option
              value="pending"
              label="待配置凭据" /><el-option
              value="configured"
              label="连接 / 凭据已配" /><el-option value="mock" label="模拟模式" /><el-option
              value="disabled"
              label="已停用"
          /></el-select>
          <el-select v-model="sort" aria-label="服务排序"
            ><el-option value="recommended" label="常用优先" /><el-option
              value="name"
              label="名称排序" /><el-option value="operations" label="接口数量排序"
          /></el-select>
        </div>
      </div>
      <div v-if="hasFilters" class="directory-filter-summary">
        <span>匹配 {{ filtered.length }} 个服务</span
        ><el-button link type="primary" @click="resetFilters">清除筛选</el-button>
      </div>
      <PageState :loading="loading" :error="error" @retry="load">
        <el-empty
          v-if="!filtered.length"
          :description="
            scope === 'favorites' && !q && source === 'all' && state === 'all'
              ? '点击服务旁的星标，即可在这里快速访问常用服务'
              : scope === 'attention' && !q && source === 'all' && state === 'all'
                ? '没有待配置或已停用的服务'
                : '没有匹配的服务，试试其他关键词或清除筛选'
          "
          :image-size="100"
        >
          <el-button v-if="hasFilters" @click="resetFilters">查看全部服务</el-button
          ><el-button v-if="!platforms.length" type="primary" @click="createPlatform"
            >接入新服务</el-button
          >
        </el-empty>
        <el-table
          v-else-if="effectiveView === 'list'"
          :data="current.items"
          row-key="platform"
          :max-height="
            compact ? 'max(220px, calc(100vh - 475px))' : 'max(240px, calc(100vh - 420px))'
          "
          class="directory-table"
          @row-click="(p: DirectoryPlatform) => (selectedKey = p.platform)"
        >
          <el-table-column width="48" class-name="directory-favorite-column"
            ><template #default="{ row }"
              ><el-button
                text
                :icon="favorites.has(row.platform) ? StarFilled : Star"
                :class="{ 'is-favorite': favorites.has(row.platform) }"
                :aria-label="favoriteLabel(row)"
                :title="favoriteLabel(row)"
                :aria-pressed="favorites.has(row.platform)"
                @click.stop="toggleFavorite(row)" /></template
          ></el-table-column>
          <el-table-column label="服务 / 连接" min-width="260"
            ><template #default="{ row }"
              ><div class="directory-identity">
                <span class="directory-monogram">{{
                  row.display_name.slice(0, 1).toUpperCase()
                }}</span>
                <div>
                  <strong>{{ row.display_name }}</strong>
                  <p class="directory-key">
                    <span class="mono">{{ row.platform }}</span
                    ><template v-if="compact"
                      ><span>·</span
                      ><span class="directory-inline-count"
                        >{{ row.operation_count }} 个接口</span
                      ></template
                    ><template v-else-if="row.module"
                      ><span>·</span><span>{{ connection(row) }}</span></template
                    >
                  </p>
                </div>
              </div></template
            ></el-table-column
          >
          <el-table-column label="连接状态" width="140"
            ><template #default="{ row }"
              ><div class="directory-status">
                <el-tag :type="statusType(row)" effect="plain">{{ statusLabel(row) }}</el-tag>
                <div class="directory-status-note">
                  {{ row.source === 'custom' ? '自定义 HTTP' : '内置适配器' }}
                </div>
              </div></template
            ></el-table-column
          >
          <el-table-column v-if="!compact" label="接口数" width="95" align="right"
            ><template #default="{ row }"
              ><button class="directory-operation-count" @click.stop="openOperations(row)">
                {{ row.operation_count }}
              </button></template
            ></el-table-column
          >
          <el-table-column label="操作" width="175" fixed="right"
            ><template #default="{ row }"
              ><div class="directory-row-actions">
                <el-button link type="primary" @click.stop="openOperations(row)">查看接口</el-button
                ><el-button link @click.stop="configure(row)">{{
                  row.module ? '配置连接' : '凭据'
                }}</el-button
                ><el-dropdown
                  trigger="click"
                  @command="
                    (command: string) =>
                      command === 'add' ? addOperation(row) : (selectedKey = row.platform)
                  "
                  ><el-button
                    text
                    :icon="MoreFilled"
                    :aria-label="'更多操作 ' + row.platform"
                    @click.stop
                  /><template #dropdown
                    ><el-dropdown-menu
                      ><el-dropdown-item command="detail">查看服务详情</el-dropdown-item
                      ><el-dropdown-item command="add">新增接口</el-dropdown-item></el-dropdown-menu
                    ></template
                  ></el-dropdown
                >
              </div></template
            ></el-table-column
          >
        </el-table>
        <div v-else class="directory-cards">
          <article v-for="p in current.items" :key="p.platform" class="directory-card">
            <header>
              <span class="directory-monogram">{{ p.display_name.slice(0, 1).toUpperCase() }}</span
              ><el-tag :type="statusType(p)" effect="plain">{{ statusLabel(p) }}</el-tag
              ><el-button
                text
                :icon="favorites.has(p.platform) ? StarFilled : Star"
                :class="{ 'is-favorite': favorites.has(p.platform) }"
                :aria-label="favoriteLabel(p)"
                :aria-pressed="favorites.has(p.platform)"
                @click="toggleFavorite(p)"
              />
            </header>
            <button class="directory-card-title" @click="selectedKey = p.platform">
              {{ p.display_name }}
            </button>
            <p class="directory-key mono">{{ p.platform }}</p>
            <p class="directory-card-note">{{ p.note }}</p>
            <div class="directory-card-meta">
              <span>{{ p.operation_count }} 个{{ p.module ? '接口配置' : '已注册接口' }}</span
              ><span>{{ p.source === 'custom' ? '自定义 HTTP' : '内置适配器' }}</span>
            </div>
            <footer>
              <el-button link type="primary" @click="openOperations(p)">查看接口</el-button
              ><el-button link @click="addOperation(p)">新增接口</el-button
              ><el-button link @click="configure(p)">{{
                p.module ? '配置连接' : '配置凭据'
              }}</el-button>
            </footer>
          </article>
        </div>
        <div v-if="filtered.length" class="directory-pagination">
          <span
            >显示 {{ current.from }}–{{ current.to }}，共 <b>{{ filtered.length }}</b> 个服务</span
          >
          <div class="directory-page-controls">
            <el-pagination
              v-model:current-page="page"
              v-model:page-size="pageSize"
              :page-sizes="sizes"
              :pager-count="5"
              :total="filtered.length"
              layout="sizes, prev, pager, next"
            />
            <div class="directory-jump">
              <span>前往</span
              ><el-input
                v-model="jumpPage"
                type="number"
                :min="1"
                :max="Math.max(1, Math.ceil(filtered.length / pageSize))"
                aria-label="跳转页码"
                @keydown.enter.prevent="jumpToPage"
              /><el-button size="small" @click="jumpToPage">跳转</el-button>
            </div>
          </div>
        </div>
      </PageState>
    </section>
    <el-drawer
      :model-value="!!selected"
      title="服务详情"
      size="min(620px, 96vw)"
      @close="selectedKey = ''"
    >
      <template v-if="selected"
        ><div class="directory-detail-heading">
          <span class="directory-monogram">{{
            selected.display_name.slice(0, 1).toUpperCase()
          }}</span>
          <div>
            <h2>{{ selected.display_name }}</h2>
            <p class="mono muted">{{ selected.platform }}</p>
          </div>
          <el-button
            text
            :icon="favorites.has(selected.platform) ? StarFilled : Star"
            :class="{ 'is-favorite': favorites.has(selected.platform) }"
            :aria-label="favoriteLabel(selected)"
            @click="toggleFavorite(selected)"
          />
        </div>
        <div class="inline-actions">
          <el-tag :type="statusType(selected)" effect="plain">{{ statusLabel(selected) }}</el-tag
          ><el-tag type="info" effect="plain">{{
            selected.source === 'custom' ? '自定义 HTTP' : '内置适配器'
          }}</el-tag>
        </div>
        <p class="form-hint" style="margin-top: 20px">{{ selected.note }}</p>
        <el-descriptions :column="1" border
          ><el-descriptions-item label="接口数量"
            >{{ selected.operation_count }} 个{{
              selected.module ? '接口配置（含未启用）' : '已注册接口'
            }}</el-descriptions-item
          ><el-descriptions-item label="连接地址"
            ><template v-if="selected.module"
              ><span class="mono">{{ selected.module.base_url }}</span
              ><el-button
                link
                :icon="DocumentCopy"
                aria-label="复制连接地址"
                @click="copyAddress(selected.module.base_url)" /></template
            ><span v-else>由内置适配器维护</span></el-descriptions-item
          ><el-descriptions-item v-if="selected.module" label="鉴权方式">{{
            selected.module.auth_type
          }}</el-descriptions-item
          ><el-descriptions-item label="服务配额">{{
            selected.platform_quota || '按接口规则执行'
          }}</el-descriptions-item></el-descriptions
        >
        <el-alert
          v-if="selected.module"
          type="info"
          :closable="false"
          title="连接配置已保存，不代表鉴权已验证"
          description="请通过具体接口的调试功能确认实际连通情况。"
          style="margin-top: 20px"
        />
        <div class="drawer-next-actions">
          <el-button type="primary" @click="openOperations(selected)">查看接口</el-button
          ><el-button @click="addOperation(selected)">新增接口</el-button
          ><el-button @click="configure(selected)">{{
            selected.module ? '编辑服务连接' : '配置上游凭据'
          }}</el-button>
        </div>
      </template>
    </el-drawer>
  </div>
</template>
<style scoped>
.directory-panel {
  overflow: hidden;
}
.directory-collections {
  display: flex;
  align-items: center;
  gap: 24px;
  min-height: 62px;
  padding: 0 22px;
  border-bottom: 1px solid var(--app-divider);
}
.directory-collections > button {
  display: flex;
  align-items: center;
  gap: 7px;
  align-self: stretch;
  border: 0;
  border-bottom: 2px solid transparent;
  background: transparent;
  padding: 0 1px;
  color: var(--app-text-secondary);
  font-size: 13px;
  white-space: nowrap;
}
.directory-collections > button.active {
  color: var(--el-color-primary);
  border-bottom-color: var(--el-color-primary);
  font-weight: 600;
}
.directory-collections > button > span {
  border-radius: 5px;
  background: var(--app-surface-soft);
  padding: 2px 6px;
  font-size: 12px;
  font-weight: 500;
  color: var(--app-text-muted);
}
.directory-collections > button.active > span {
  background: var(--app-primary-soft);
  color: var(--el-color-primary);
}
.directory-collections > button > span.attention {
  color: #a37526;
}
.directory-view-switch {
  display: flex;
  margin-left: auto;
  border: 1px solid var(--app-border);
  border-radius: 7px;
  padding: 3px;
}
.directory-view-switch .el-button {
  border: 0;
  width: 30px;
  height: 27px;
  margin: 0;
  color: var(--app-text-muted);
  background: transparent;
  border-radius: 4px;
}
.directory-view-switch .el-button.selected {
  color: var(--el-color-primary);
  background: var(--app-primary-soft);
}
.directory-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  gap: 16px;
  padding: 20px 22px 16px;
}
.directory-search {
  flex: 1;
  min-width: 260px;
}
.directory-search p {
  font-size: 11px;
  color: var(--app-text-muted);
  margin: 8px 0 0;
}
.directory-search kbd {
  font-family: inherit;
  border: 1px solid var(--app-border);
  border-radius: 4px;
  padding: 0 5px;
  line-height: 19px;
  color: var(--app-text-muted);
  background: var(--app-surface-soft);
}
.directory-filter-set {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}
.directory-filter-set .el-select {
  width: 135px;
}
.directory-filter-summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 22px 12px;
  font-size: 12px;
  color: var(--app-text-muted);
}
.directory-table {
  cursor: pointer;
}
.directory-table :deep(.el-table__cell) {
  height: max(var(--app-row-height), 58px);
}
.directory-table :deep(th.el-table__cell) {
  height: 42px;
}
.directory-table :deep(.directory-favorite-column .cell) {
  padding: 0 0 0 12px;
}
.directory-table :deep(.directory-favorite-column .el-button) {
  padding: 7px;
  color: #bdc3cf;
}
.directory-identity {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}
.directory-identity > div {
  min-width: 0;
  display: grid;
  grid-template-rows: 22px 18px;
  gap: 2px;
}
.directory-identity strong {
  display: block;
  color: var(--app-text);
  font-size: 13px;
  font-weight: 550;
  line-height: 22px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.directory-monogram {
  width: 32px;
  height: 32px;
  flex: 0 0 32px;
  display: grid;
  place-items: center;
  color: var(--el-color-primary);
  background: var(--app-primary-soft);
  border: 1px solid #dbeafe;
  border-radius: 9px;
  font-size: 13px;
  font-weight: 600;
}
.directory-key {
  display: flex;
  align-items: center;
  gap: 7px;
  margin: 0;
  line-height: 18px;
  color: var(--app-text-muted);
  font-size: 11px;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.directory-key > span {
  overflow: hidden;
  text-overflow: ellipsis;
}
.directory-inline-count {
  flex-shrink: 0;
}
.directory-card > .directory-key {
  margin-top: 5px;
}
.directory-compact-count,
.directory-status-note {
  color: var(--app-text-muted);
  font-size: 11px;
  margin-top: 5px;
}
.directory-status {
  display: grid;
  grid-template-rows: 22px 18px;
  gap: 2px;
  align-content: center;
}
.directory-status :deep(.el-tag) {
  align-self: center;
  justify-self: start;
  height: 22px;
  line-height: 20px;
  margin: 0;
}
.directory-status .directory-status-note {
  margin: 0;
  line-height: 18px;
}
.directory-compact-count {
  line-height: 18px;
  margin: 0;
}
.directory-operation-count {
  border: 0;
  background: transparent;
  padding: 5px;
  color: var(--el-color-primary);
  font-weight: 550;
  font-variant-numeric: tabular-nums;
}
.directory-row-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  white-space: nowrap;
}
.directory-row-actions .el-button {
  margin: 0;
  font-size: 12px;
}
.directory-row-actions .el-dropdown .el-button {
  padding: 3px;
  color: var(--app-text-muted);
}
.is-favorite {
  color: #b58b39 !important;
}
.directory-pagination {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 15px;
  padding: 17px 22px;
  border-top: 1px solid var(--app-divider);
  color: var(--app-text-muted);
  font-size: 12px;
}
.directory-pagination b {
  color: #5f687e;
  font-weight: 500;
}
.directory-page-controls {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
}
.directory-jump {
  display: flex;
  align-items: center;
  gap: 7px;
}
.directory-jump .el-input {
  width: 55px;
}
.directory-jump .el-button {
  margin-left: 0;
}
.directory-cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 16px;
  padding: 0 20px 20px;
}
.directory-card {
  border: 1px solid var(--app-border);
  border-radius: 10px;
  padding: 17px;
  min-width: 0;
}
.directory-card > header {
  display: flex;
  align-items: center;
  gap: 9px;
}
.directory-card > header > .el-tag {
  margin-left: auto;
}
.directory-card > header > .el-button {
  padding: 3px;
  margin: 0;
  color: #b7bfcd;
}
.directory-card-title {
  display: block;
  max-width: 100%;
  text-align: left;
  padding: 0;
  margin-top: 17px;
  border: 0;
  background: transparent;
  color: var(--app-text);
  font-weight: 550;
  font-size: 14px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.directory-card-note {
  margin: 12px 0 16px;
  color: var(--app-text-muted);
  font-size: 12px;
  line-height: 1.7;
  height: 38px;
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  overflow: hidden;
}
.directory-card-meta {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  color: var(--app-text-muted);
  font-size: 11px;
  margin-bottom: 15px;
}
.directory-card footer {
  display: flex;
  justify-content: space-between;
  border-top: 1px solid var(--app-divider);
  padding-top: 13px;
}
.directory-card footer .el-button {
  font-size: 12px;
  margin: 0;
}
.directory-help {
  font-size: 12px;
  line-height: 1.8;
  color: var(--app-text-secondary);
}
.directory-help strong {
  color: var(--app-text-secondary);
  font-weight: 600;
}
.directory-help p {
  margin: 4px 0 15px;
}
.directory-detail-heading {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 22px;
}
.directory-detail-heading h2 {
  font-size: 18px;
  font-weight: 600;
  margin-bottom: 7px;
}
.directory-detail-heading > .el-button {
  margin-left: auto;
}
@media (max-width: 1100px) {
  .directory-search {
    flex-basis: 100%;
  }
  .directory-filter-set {
    width: 100%;
  }
  .directory-filter-set .el-select {
    flex: 1;
  }
}
@media (max-width: 600px) {
  .directory-collections {
    gap: 14px;
    padding: 0 14px;
  }
  .directory-collections > button {
    font-size: 12px;
    gap: 5px;
  }
  .directory-toolbar {
    padding: 16px 14px;
  }
  .directory-filter-set {
    gap: 7px;
  }
  .directory-filter-set .el-select {
    min-width: 95px;
  }
  .directory-pagination {
    padding: 15px 14px;
    flex-direction: column;
    align-items: flex-start;
    overflow: auto;
  }
  .directory-pagination :deep(.el-pagination__sizes) {
    margin-right: 7px;
  }
  .directory-cards {
    padding: 0 14px 14px;
  }
  .directory-view-switch .el-button {
    width: 25px;
  }
}
</style>
