<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { api } from '~/api';
import { useSessionStore } from '~/stores/session';
import { useAction } from '~/composables/useRequest';
import { Connection, Lock, DataAnalysis } from '@element-plus/icons-vue';
const route = useRoute();
const router = useRouter();
const session = useSessionStore();
const { busy, run } = useAction();
const form = reactive({ username: '', password: '' });
const devLogin = ref(false);
const environment = ref('');
onMounted(async () => {
  try {
    const config = await api.authConfig();
    devLogin.value = config.dev_login;
    environment.value = config.env;
  } catch {
    /* connection message is shown by HTTP layer */
  }
});
async function login(dev = false) {
  const result = await run(() => (dev ? api.devLogin() : api.login(form.username, form.password)));
  if (!result) return;
  session.accept(result);
  const redirect = String(route.query.redirect || '/workspace');
  await router.replace(
    redirect.startsWith('/') && !redirect.startsWith('//') ? redirect : '/workspace',
  );
}
</script>
<template>
  <div class="login-page">
    <div class="login-main">
      <section class="login-story" aria-label="平台简介">
        <div class="login-brand">
          <span class="brand-symbol">C<span>·</span></span
          ><strong>CYT Common</strong>
        </div>
        <div class="login-story-copy">
          <div class="eyebrow">API WORKSPACE</div>
          <h2>统一连接，<br />让业务有序流转。</h2>
          <p>从平台接入到调用授权，在一个工作台中管理 API 的连接、配置与运行状态。</p>
          <div class="login-features">
            <span
              ><el-icon><Connection /></el-icon>统一接入</span
            ><span
              ><el-icon><Lock /></el-icon>权限治理</span
            ><span
              ><el-icon><DataAnalysis /></el-icon>运行可观测</span
            >
          </div>
        </div>
        <small>畅韵科技 · 统一 API 管理平台</small>
      </section>
      <div class="login-form-side">
        <div class="login-panel">
          <div class="eyebrow">管理工作台</div>
          <h1>欢迎回来</h1>
          <p class="login-description">登录后继续管理你的 API 连接与业务集成。</p>
          <el-form :model="form" label-position="top" size="large" @submit.prevent="login()"
            ><el-form-item label="管理员账号"
              ><el-input
                v-model="form.username"
                placeholder="请输入账号"
                autocomplete="username" /></el-form-item
            ><el-form-item label="登录密码"
              ><el-input
                v-model="form.password"
                type="password"
                show-password
                placeholder="请输入密码"
                autocomplete="current-password" /></el-form-item
            ><el-button
              native-type="submit"
              type="primary"
              class="login-submit"
              :loading="busy"
              :disabled="!form.username || !form.password"
              >登录工作台</el-button
            ></el-form
          ><template v-if="devLogin"
            ><el-divider>本地开发</el-divider
            ><el-button class="login-submit" :loading="busy" @click="login(true)"
              >进入本地管理环境</el-button
            >
            <p class="login-note">
              当前环境：{{ environment }} · 免密入口仅用于非生产环境
            </p></template
          >
        </div>
      </div>
    </div>
    <footer>CYT Common · 畅韵科技</footer>
  </div>
</template>
