<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Document, Menu, Fold, Expand, Operation, ArrowRight } from '@element-plus/icons-vue';
import { useSessionStore } from '~/stores/session';
import { useUiPreferences, type Density } from '~/composables/useUiPreferences';
import { api } from '~/api';
import WorkspaceNavigation from './WorkspaceNavigation.vue';
import QuickNavigation from '~/components/QuickNavigation.vue';
import { navigation, navigationPath } from './navigation';
const route = useRoute();
const router = useRouter();
const session = useSessionStore();
const { collapsed, density } = useUiPreferences();
const mobile = ref(false);
const env = ref('');
const mockCount = ref(0);
const breadcrumbGroup = computed(() => {
  const parent = navigationPath(
    route.path,
    route.query.system,
    route.query.direction || route.query.kind,
  );
  if (parent !== route.path) {
    return navigation.flatMap((group) => group.items).find((item) => item.path === parent)?.label;
  }
  return route.meta.group || '工作台';
});
onMounted(async () => {
  try {
    await session.restore();
    const meta = await api.meta();
    env.value = meta.env;
    mockCount.value = meta.platforms.filter((p) => p.mock).length;
  } catch {
    /* HTTP interceptor handles session expiry */
  }
});
function logout() {
  mobile.value = false;
  session.logout();
  void router.replace('/login');
}
function setDensity(value: Density) {
  density.value = value;
}
function focusContent() {
  document.getElementById('main-content')?.focus();
}
</script>
<template>
  <div class="admin-shell" :class="{ 'sidebar-collapsed': collapsed }">
    <a href="#main-content" class="skip-link" @click.prevent="focusContent">跳到页面内容</a>
    <aside class="sidebar" :class="{ 'is-collapsed': collapsed }">
      <WorkspaceNavigation :collapsed="collapsed" @logout="logout" />
    </aside>
    <el-drawer
      v-model="mobile"
      direction="ltr"
      size="260px"
      :with-header="false"
      class="navigation-drawer"
      aria-label="工作台导航"
    >
      <WorkspaceNavigation @navigate="mobile = false" @logout="logout" />
    </el-drawer>
    <div class="main-shell">
      <header class="topbar">
        <div class="topbar-location">
          <el-tooltip :content="collapsed ? '展开导航' : '收起导航'" placement="bottom">
            <el-button
              class="desktop-menu topbar-icon"
              text
              :icon="collapsed ? Expand : Fold"
              :aria-label="collapsed ? '展开导航' : '收起导航'"
              :aria-expanded="!collapsed"
              @click="collapsed = !collapsed"
            />
          </el-tooltip>
          <el-button
            class="mobile-menu topbar-icon"
            text
            :icon="Menu"
            aria-label="打开导航"
            @click="mobile = true"
          />
          <el-breadcrumb :separator-icon="ArrowRight">
            <el-breadcrumb-item
              class="breadcrumb-group"
              :to="{
                path: navigationPath(
                  route.path,
                  route.query.system,
                  route.query.direction || route.query.kind,
                ),
              }"
              >{{ breadcrumbGroup }}</el-breadcrumb-item
            >
            <el-breadcrumb-item>{{ route.meta.title }}</el-breadcrumb-item>
          </el-breadcrumb>
        </div>
        <QuickNavigation />
        <div class="topbar-right">
          <el-tooltip
            :content="mockCount ? `${mockCount} 个平台在模拟模式下运行` : '查看平台连接状态'"
            placement="bottom"
          >
            <button
              class="runtime-indicator"
              :class="{ 'is-mock': mockCount }"
              @click="
                router.push({ path: '/platforms', query: mockCount ? { state: 'mock' } : {} })
              "
            >
              <i />{{ mockCount ? '模拟模式' : '平台状态' }}
            </button>
          </el-tooltip>
          <span class="environment"><i />{{ env || '连接中' }}</span>
          <span class="topbar-separator" />
          <el-dropdown trigger="click" @command="setDensity">
            <el-button
              text
              class="topbar-icon"
              :icon="Operation"
              aria-label="调整显示密度"
              title="显示密度"
            />
            <template #dropdown
              ><el-dropdown-menu>
                <el-dropdown-item
                  command="comfortable"
                  :class="{ 'is-current': density === 'comfortable' }"
                  >舒适间距 {{ density === 'comfortable' ? '✓' : '' }}</el-dropdown-item
                >
                <el-dropdown-item command="compact" :class="{ 'is-current': density === 'compact' }"
                  >紧凑间距 {{ density === 'compact' ? '✓' : '' }}</el-dropdown-item
                >
              </el-dropdown-menu></template
            >
          </el-dropdown>
          <el-tooltip content="使用指引" placement="bottom"
            ><el-button
              text
              class="topbar-icon help-button"
              :icon="Document"
              aria-label="使用指引"
              @click="router.push('/workspace')"
          /></el-tooltip>
        </div>
      </header>
      <main id="main-content" class="page-content" tabindex="-1">
        <RouterView v-slot="{ Component }"
          ><Transition name="page" mode="out-in"><component :is="Component" /></Transition
        ></RouterView>
      </main>
      <footer class="app-footer">
        <span>CYT Common <span class="footer-dot">·</span> 统一 API 管理平台</span
        ><span>畅韵科技</span>
      </footer>
    </div>
  </div>
</template>
