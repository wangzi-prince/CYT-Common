<script setup lang="ts">
import { computed, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import {
  Connection,
  OfficeBuilding,
  User,
  Right,
  CircleCheck,
  ArrowRight,
} from '@element-plus/icons-vue';
import { api } from '~/api';
import { useRequest } from '~/composables/useRequest';
import PageState from '~/components/PageState.vue';
const router = useRouter();
const { data, loading, error, load } = useRequest(async () => {
  const [meta, systems, modules] = await Promise.all([api.meta(), api.systems(), api.modules()]);
  return { meta, systems: systems.systems, modules: modules.items };
});
const pending = computed(() =>
  (data.value?.systems || []).filter((s) => s.enabled && (!s.apikey_count || !s.grant_count)),
);
const internal = computed(() =>
  (data.value?.modules || []).filter((m) => m.service_kind === 'internal'),
);
const recipes = [
  {
    title: '接入第三方服务',
    description: '使用企业微信、Google、ERP 等服务，把官方接口整理为业务能力。',
    icon: Connection,
    tag: '我需要调用外部服务',
    steps: ['选择内置适配器或新增连接', '配置上游凭据并验证接口', '为业务系统开放所需能力'],
    action: '选择第三方服务',
    path: '/platforms?kind=third_party',
    tone: 'blue',
  },
  {
    title: '开放自有系统接口',
    description: '把已有的订单、库存、客户等 HTTP 接口纳入网关，供其他系统统一调用。',
    icon: OfficeBuilding,
    tag: '我有接口要开放',
    steps: ['登记内部系统的服务地址', '配置接口、参数和返回值', '验证后分配给调用方'],
    action: '登记自有系统',
    path: '/modules?tab=modules&action=create&kind=internal',
    tone: 'teal',
  },
  {
    title: '让业务系统开始调用',
    description: '已有能力接入新系统，为它准备独立密钥、操作权限和专属文档。',
    icon: User,
    tag: '我要给一个系统接入',
    steps: ['新建或选择调用方系统', '生成密钥并批量分配能力', '交付调用示例与专属 OpenAPI'],
    action: '管理调用方接入',
    path: '/systems',
    tone: 'violet',
  },
];
onMounted(load);
</script>
<template>
  <div class="workspace-home">
    <section class="workspace-welcome">
      <div>
        <span class="workspace-kicker">CYT COMMON · 集成工作台</span>
        <h1>从你要完成的事情开始</h1>
        <p>按调用方向开始：接入第三方，或开放自有系统能力。</p>
      </div>
      <div class="workspace-welcome-actions">
        <el-button type="primary" @click="router.push('/docs')">我是接入方，查看文档</el-button
        ><el-button @click="router.push('/monitor')"
          >查看运行情况<el-icon><Right /></el-icon
        ></el-button>
      </div>
    </section>
    <div class="workspace-chain" aria-label="接入到调用的流程">
      <span><b>服务接入</b><small>我们调用第三方</small></span
      ><span>或</span><span><b>对外能力</b><small>自有系统开放给其他系统</small></span
      ><el-icon><ArrowRight /></el-icon><span><b>调用方</b><small>身份 / 密钥 / 授权</small></span
      ><el-icon><ArrowRight /></el-icon
      ><span><b>接入文档</b><small>快速接入 / 示例 / SDK</small></span>
    </div>
    <div class="workspace-recipes">
      <article v-for="item in recipes" :key="item.title" class="panel recipe" :class="item.tone">
        <div class="recipe-top">
          <el-icon><component :is="item.icon" /></el-icon><span>{{ item.tag }}</span>
        </div>
        <h2>{{ item.title }}</h2>
        <p>{{ item.description }}</p>
        <ol>
          <li v-for="step in item.steps" :key="step">{{ step }}</li>
        </ol>
        <el-button type="primary" @click="router.push(item.path)"
          >{{ item.action }}<el-icon><Right /></el-icon
        ></el-button>
      </article>
    </div>
    <PageState :loading="loading" :error="error" @retry="load"
      ><section class="panel workspace-status">
        <div>
          <h2>当前工作空间</h2>
          <p class="subtext">配置数量帮助你找到下一步；真实调用结果在运行中心查看。</p>
        </div>
        <div class="workspace-metrics">
          <button @click="router.push('/platforms')">
            <strong>{{ data?.meta.platforms.length || 0 }}</strong
            ><span>已接入服务</span></button
          ><button @click="router.push('/platforms?kind=internal')">
            <strong>{{ internal.length }}</strong
            ><span>已登记自有系统</span></button
          ><button @click="router.push('/exposed')">
            <strong>{{ data?.meta.operation_count || 0 }}</strong
            ><span>已注册接口</span></button
          ><button @click="router.push('/systems')">
            <strong>{{ data?.systems.length || 0 }}</strong
            ><span>调用方系统</span>
          </button>
        </div>
      </section>
      <section class="panel workspace-next">
        <div>
          <h2>继续接入</h2>
          <p class="subtext">从尚未完成接入的调用方继续。</p>
        </div>
        <div v-if="pending.length" class="next-callers">
          <button
            v-for="caller in pending.slice(0, 4)"
            :key="caller.key"
            @click="router.push(`/systems/${encodeURIComponent(caller.key)}`)"
          >
            <span
              ><strong>{{ caller.display_name }}</strong
              ><small>{{
                !caller.apikey_count ? '尚未生成调用密钥' : '尚未配置接口授权'
              }}</small></span
            ><el-icon><Right /></el-icon>
          </button>
        </div>
        <div v-else class="workspace-done">
          <el-icon><CircleCheck /></el-icon>
          当前调用方均已配置密钥和授权，可进入对外能力查看接入范围。
        </div>
      </section></PageState
    >
  </div>
</template>
<style scoped>
.workspace-welcome {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 20px;
  margin: 5px 0 24px;
}
.workspace-welcome-actions {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}
.workspace-welcome-actions .el-button {
  margin: 0;
}
.workspace-kicker {
  font-size: 11px;
  letter-spacing: 0.12em;
  color: var(--el-color-primary);
  font-weight: 700;
}
.workspace-welcome h1 {
  margin: 12px 0 10px;
  font-size: 30px;
  letter-spacing: -1px;
}
.workspace-welcome p {
  margin: 0;
  color: var(--el-text-color-secondary);
  line-height: 1.7;
}
.workspace-chain {
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 40px;
  margin-bottom: 24px;
  padding: 18px;
  border: 1px solid var(--el-border-color-lighter);
  background: white;
  border-radius: 12px;
}
.workspace-chain span {
  display: grid;
  gap: 6px;
}
.workspace-chain b {
  font-size: 14px;
}
.workspace-chain small {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.workspace-chain > .el-icon {
  color: var(--el-color-primary-light-5);
}
.workspace-recipes {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 18px;
}
.recipe {
  padding: 24px;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
}
.recipe-top {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.recipe-top .el-icon {
  font-size: 24px;
  padding: 12px;
  border-radius: 12px;
  color: #4767df;
  background: #eef2ff;
  box-sizing: content-box;
}
.teal .recipe-top .el-icon {
  color: #167d84;
  background: #e9f7f5;
}
.violet .recipe-top .el-icon {
  color: #8662c9;
  background: #f4effb;
}
.recipe h2 {
  font-size: 19px;
  margin: 22px 0 10px;
}
.recipe p {
  font-size: 13px;
  line-height: 1.9;
  color: var(--el-text-color-secondary);
  margin: 0;
}
.recipe ol {
  font-size: 12px;
  line-height: 2.4;
  color: var(--el-text-color-regular);
  padding-left: 18px;
  margin: 20px 0 24px;
  flex: 1;
}
.recipe .el-button {
  width: 100%;
}
.workspace-status,
.workspace-next {
  padding: 22px 24px;
  display: grid;
  gap: 16px;
}
.workspace-home h2 {
  margin-top: 0;
}
.workspace-status h2,
.workspace-next h2 {
  font-size: 16px;
  margin-bottom: 6px;
}
.workspace-status p,
.workspace-next p {
  margin: 0;
}
.workspace-metrics {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
}
.workspace-metrics button {
  display: grid;
  gap: 8px;
  border: 0;
  border-right: 1px solid var(--el-border-color-lighter);
  background: transparent;
  text-align: left;
  cursor: pointer;
  padding: 4px 20px;
}
.workspace-metrics button:first-child {
  padding-left: 0;
}
.workspace-metrics button:last-child {
  border-right: 0;
}
.workspace-metrics strong {
  font-size: 26px;
  color: var(--el-text-color-primary);
}
.workspace-metrics span {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.next-callers {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}
.next-callers button {
  display: flex;
  justify-content: space-between;
  align-items: center;
  border: 1px solid var(--el-border-color-lighter);
  padding: 14px 16px;
  background: var(--el-fill-color-light);
  border-radius: 8px;
  cursor: pointer;
  color: var(--el-text-color-regular);
}
.next-callers span {
  display: grid;
  text-align: left;
  gap: 6px;
}
.next-callers small {
  color: var(--el-text-color-secondary);
}
.workspace-done {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
@media (max-width: 1100px) {
  .workspace-recipes {
    grid-template-columns: 1fr;
  }
  .recipe {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 0 24px;
  }
  .recipe-top,
  .recipe h2,
  .recipe p {
    grid-column: 1;
  }
  .recipe ol {
    grid-column: 2;
    grid-row: 1 / 4;
    margin: 0;
    align-self: center;
  }
  .recipe .el-button {
    grid-column: 1 / -1;
    margin-top: 20px;
  }
  .workspace-chain {
    gap: 24px;
  }
}
@media (max-width: 600px) {
  .workspace-welcome {
    align-items: flex-start;
    flex-direction: column;
  }
  .workspace-welcome h1 {
    font-size: 24px;
  }
  .workspace-chain {
    gap: 12px;
    padding: 14px 12px;
  }
  .workspace-chain small {
    display: none;
  }
  .workspace-chain b {
    font-size: 12px;
  }
  .recipe {
    display: flex;
    padding: 20px;
  }
  .recipe ol {
    margin: 12px 0 8px;
  }
  .workspace-metrics {
    grid-template-columns: 1fr 1fr;
    gap: 18px;
  }
  .workspace-metrics button {
    border: 0;
    padding: 0;
  }
  .workspace-status,
  .workspace-next {
    padding: 20px;
  }
  .next-callers {
    grid-template-columns: 1fr;
  }
}
</style>
