<script setup lang="ts">
import { useRoute } from 'vue-router';
import { SwitchButton } from '@element-plus/icons-vue';
import { navigation, navigationPath } from './navigation';
import { useSessionStore } from '~/stores/session';
defineProps<{ collapsed?: boolean }>();
defineEmits<{ navigate: []; logout: [] }>();
const route = useRoute();
const session = useSessionStore();
</script>
<template>
  <RouterLink
    to="/workspace"
    class="brand"
    aria-label="CYT Common 工作台"
    @click="$emit('navigate')"
  >
    <span class="brand-symbol">C<span>·</span></span>
    <div v-if="!collapsed"><strong>CYT Common</strong><span>统一 API 管理平台</span></div>
  </RouterLink>
  <div v-if="!collapsed" class="workspace-label">
    <span class="workspace-dot" />畅韵科技<span class="workspace-kind">工作空间</span>
  </div>
  <nav class="sidebar-navigation" aria-label="主导航">
    <el-menu
      :default-active="
        navigationPath(route.path, route.query.system, route.query.direction || route.query.kind)
      "
      :collapse="collapsed"
      :collapse-transition="false"
      router
      @select="$emit('navigate')"
    >
      <el-menu-item-group v-for="group in navigation" :key="group.title" :title="group.title">
        <el-menu-item
          v-for="item in group.items"
          :key="item.path"
          :index="item.path"
          :aria-label="item.label"
        >
          <el-icon><component :is="item.icon" /></el-icon>
          <template #title
            ><span>{{ item.label }}</span></template
          >
        </el-menu-item>
      </el-menu-item-group>
    </el-menu>
  </nav>
  <div class="sidebar-account">
    <el-dropdown v-if="collapsed" trigger="click" @command="$emit('logout')">
      <button type="button" class="avatar" aria-label="账户菜单" aria-haspopup="menu">
        {{ (session.actor || 'A').slice(0, 1).toUpperCase() }}
      </button>
      <template #dropdown
        ><el-dropdown-menu
          ><el-dropdown-item disabled>{{ session.actor || '管理员' }}</el-dropdown-item
          ><el-dropdown-item divided command="logout">退出登录</el-dropdown-item></el-dropdown-menu
        ></template
      >
    </el-dropdown>
    <template v-else>
      <div class="avatar">{{ (session.actor || 'A').slice(0, 1).toUpperCase() }}</div>
    </template>
    <div v-if="!collapsed" class="account-detail">
      <strong>{{ session.actor || '管理员' }}</strong
      ><span>管理员</span>
    </div>
    <button v-if="!collapsed" type="button" class="sidebar-logout" @click="$emit('logout')">
      <el-icon><SwitchButton /></el-icon><span>退出登录</span>
    </button>
  </div>
</template>
