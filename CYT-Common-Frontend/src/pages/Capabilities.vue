<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Search, Right } from '@element-plus/icons-vue';
import { api } from '~/api';
import type { CapabilityDepth, CapabilityItem } from '~/api/types';
import { useRequest } from '~/composables/useRequest';
import PageHeader from '~/components/PageHeader.vue';
import PageState from '~/components/PageState.vue';
const router = useRouter();
const route = useRoute();
const { data, loading, error, load } = useRequest(api.capabilityReview);
const query = ref('');
const platform = ref(String(route.query.platform || ''));
const assessment = ref('');
const depth = ref('');
const page = ref(1);
const selected = ref<CapabilityItem>();
const labels = {
  recommended: '建议优先使用',
  ready: '可复用现有接口',
  enhance: '有改进建议',
  low_level: '保留原子职责',
  test_only: '模拟演练',
};
const depthLabels: Record<CapabilityDepth, string> = {
  deep: '深度业务封装',
  enhanced: '增强封装',
  basic: '基础封装',
  supporting: '基础管理能力',
  test_only: '模拟演练',
};
const scopedItems = computed(() =>
  (data.value?.items || []).filter((item) => !platform.value || item.platform === platform.value),
);
const depthCounts = computed(() =>
  scopedItems.value.reduce(
    (counts, item) => {
      counts[item.depth] = (counts[item.depth] || 0) + 1;
      return counts;
    },
    {} as Record<CapabilityDepth, number>,
  ),
);
const platforms = computed(() =>
  [...new Set(data.value?.items.map((item) => item.platform) || [])].sort(),
);
const filtered = computed(() =>
  scopedItems.value.filter(
    (item) =>
      (!platform.value || item.platform === platform.value) &&
      (!assessment.value || item.assessment === assessment.value) &&
      (!depth.value || item.depth === depth.value) &&
      `${item.key} ${item.summary} ${item.depth_reason} ${item.remaining_gap} ${item.current} ${item.proposal}`
        .toLowerCase()
        .includes(query.value.trim().toLowerCase()),
  ),
);
const rows = computed(() => filtered.value.slice((page.value - 1) * 20, page.value * 20));
function search() {
  page.value = 1;
}
function selectDepth(key: CapabilityDepth) {
  depth.value = depth.value === key ? '' : key;
  search();
}
function docs(key: string) {
  void router.push({ path: '/docs', query: { operation: key } });
}
onMounted(load);
</script>
<template>
  <div>
    <PageHeader
      title="能力指南"
      description="逐项核对实际实现，区分封装深度、推荐用法和仍需补齐的业务流程。"
      ><el-button
        type="primary"
        @click="
          router.push({ path: '/registry', query: { platform: 'wecom', domain: 'approval' } })
        "
        >查看企微审批接口<el-icon><Right /></el-icon></el-button
    ></PageHeader>
    <PageState :loading="loading" :error="error" @retry="load">
      <section class="panel capability-intro">
        <div class="panel-body">
          <el-alert
            :title="data?.depth_summary?.conclusion || '正在读取逐项审阅结果'"
            type="info"
            :closable="false"
          />
          <p>{{ data?.depth_summary?.basis }}</p>
          <div class="capability-depth-counts">
            <button
              v-for="level in data?.depth_summary?.levels"
              :key="level.key"
              :class="{ selected: depth === level.key }"
              :aria-label="`筛选${level.label}`"
              @click="selectDepth(level.key)"
            >
              <strong>{{ depthCounts[level.key] || 0 }}</strong
              ><span>{{ level.label }}</span>
            </button>
          </div>
          <p class="subtext">
            统计范围：{{ platform || '全部平台' }} ·
            {{ scopedItems.length }} 项。点击深度分类可筛选；推荐调用与封装深度分别判断。
          </p>
          <p>{{ data?.scope }}</p>
          <span class="subtext"
            >审阅日期 {{ data?.generated_at }} ·
            深度结论来自静态代码审阅，测试通过不代表所有业务流程或供应商账号已验收。</span
          >
        </div>
      </section>
      <section v-if="data?.depth_summary?.findings.length" class="panel capability-findings">
        <el-collapse
          ><el-collapse-item title="优先补齐的业务缺口与判断标准" name="gaps"
            ><div
              v-for="finding in data.depth_summary.findings"
              :key="finding.title"
              class="capability-finding"
            >
              <strong>{{ finding.priority }} · {{ finding.title }}</strong>
              <p class="capability-copy">{{ finding.detail }}</p>
              <div class="capability-actions">
                <el-button
                  v-for="key in finding.operations"
                  :key="key"
                  link
                  type="primary"
                  @click="
                    query = key;
                    depth = '';
                    assessment = '';
                    platform = '';
                    search();
                  "
                  >{{ key }}</el-button
                >
              </div>
            </div></el-collapse-item
          ></el-collapse
        >
      </section>
      <section class="panel">
        <div class="panel-body capability-toolbar">
          <el-input
            v-model="query"
            :prefix-icon="Search"
            clearable
            placeholder="搜索业务、接口或改进建议"
            @input="search"
          />
          <el-select v-model="platform" clearable placeholder="全部平台" @change="search"
            ><el-option v-for="item in platforms" :key="item" :value="item" :label="item"
          /></el-select>
          <el-select v-model="depth" clearable placeholder="全部封装深度" @change="search"
            ><el-option v-for="(label, key) in depthLabels" :key="key" :value="key" :label="label"
          /></el-select>
          <el-select v-model="assessment" clearable placeholder="全部使用建议" @change="search"
            ><el-option v-for="(label, value) in labels" :key="value" :value="value" :label="label"
          /></el-select>
        </div>
        <el-table :data="rows" row-key="key" @row-click="selected = $event" class="capability-table"
          ><el-table-column label="对外能力" min-width="270"
            ><template #default="{ row }"
              ><strong>{{ row.summary }}</strong>
              <p class="subtext mono">{{ row.key }}</p></template
            ></el-table-column
          ><el-table-column label="封装深度" width="145"
            ><template #default="{ row }"
              ><el-tag
                :type="
                  row.depth === 'deep'
                    ? 'success'
                    : row.depth === 'enhanced'
                      ? 'primary'
                      : row.depth === 'basic'
                        ? 'warning'
                        : 'info'
                "
                effect="plain"
                >{{ depthLabels[row.depth as CapabilityDepth] || '未评定' }}</el-tag
              ></template
            ></el-table-column
          ><el-table-column label="实际实现与缺口" min-width="300"
            ><template #default="{ row }"
              ><span>{{ row.depth_reason }}</span>
              <p class="subtext">{{ row.remaining_gap }}</p></template
            ></el-table-column
          ><el-table-column label="操作" width="95"
            ><template #default="{ row }"
              ><el-button link type="primary" @click.stop="selected = row"
                >查看</el-button
              ></template
            ></el-table-column
          ></el-table
        >
        <div class="panel-body capability-pagination">
          <span class="subtext">共 {{ filtered.length }} 项</span
          ><el-pagination
            v-model:current-page="page"
            :total="filtered.length"
            :page-size="20"
            layout="prev, pager, next"
          />
        </div>
      </section>
      <section class="panel">
        <header class="panel-head">
          <h2>设计参考</h2>
          <span class="subtext">官方协议与开源 SDK 的领域组织、分页迭代思路</span>
        </header>
        <div class="panel-body capability-references">
          <a
            v-for="reference in data?.references"
            :key="reference.url"
            :href="reference.url"
            target="_blank"
            rel="noopener noreferrer"
            >{{ reference.title }} ↗</a
          >
        </div>
      </section>
      <section
        v-if="data?.depth_summary?.auxiliary_interfaces.length"
        class="panel capability-findings"
      >
        <el-collapse
          ><el-collapse-item title="另行核对：批量调用与入站回调" name="auxiliary"
            ><div
              v-for="item in data.depth_summary.auxiliary_interfaces"
              :key="item.path"
              class="capability-finding"
            >
              <el-tag size="small" effect="plain">{{ depthLabels[item.depth] }}</el-tag>
              <p class="mono capability-copy">{{ item.path }}</p>
              <p class="capability-copy">{{ item.reason }}</p>
            </div></el-collapse-item
          ></el-collapse
        >
      </section>
    </PageState>
    <el-drawer
      :model-value="!!selected"
      title="能力封装说明"
      size="min(640px, 94vw)"
      @close="selected = undefined"
      ><template v-if="selected"
        ><el-tag effect="plain">{{ depthLabels[selected.depth] || '未评定' }}</el-tag>
        <h2>{{ selected.summary }}</h2>
        <p class="mono subtext">{{ selected.key }}</p>
        <h3>现有能力</h3>
        <p class="capability-copy">{{ selected.depth_reason }}</p>
        <h3>仍需补齐 / 业务边界</h3>
        <p class="capability-copy">{{ selected.remaining_gap }}</p>
        <h3>建议用法与改进</h3>
        <el-tag type="info" effect="plain">{{ labels[selected.assessment] }}（使用建议）</el-tag>
        <p class="capability-copy">{{ selected.proposal }}</p>
        <el-alert v-if="selected.caveat" :title="selected.caveat" type="info" :closable="false" />
        <el-collapse class="capability-evidence"
          ><el-collapse-item title="代码依据（静态审阅）" name="evidence"
            ><div v-for="ref in selected.evidence" :key="`${ref.path}:${ref.line}`">
              <code>{{ ref.path }}:{{ ref.line }}</code>
              <p class="subtext mono">{{ ref.anchor }}</p>
            </div></el-collapse-item
          ></el-collapse
        >
        <div class="capability-actions">
          <el-button
            v-for="key in selected.recommended_operations"
            :key="key"
            type="primary"
            @click="docs(key)"
            >使用 {{ key }}</el-button
          ><el-button @click="docs(selected.key)">接口文档</el-button>
        </div></template
      ></el-drawer
    >
  </div>
</template>
<style scoped>
.capability-depth-counts {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 12px;
  margin: 18px 0;
}
.capability-depth-counts button {
  display: flex;
  flex-direction: column;
  gap: 8px;
  align-items: flex-start;
  background: var(--el-fill-color-light);
  border: 1px solid var(--app-border);
  border-radius: 8px;
  padding: 15px;
  color: var(--app-text-secondary);
}
.capability-depth-counts button.selected {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}
.capability-depth-counts strong {
  font-size: 26px;
  color: var(--app-text);
}
.capability-findings {
  padding: 0 20px;
}
.capability-finding {
  padding: 15px 0;
  border-top: 1px solid var(--app-border);
}
.capability-finding .capability-actions {
  margin-top: 8px;
  gap: 10px;
}
.capability-evidence {
  margin-top: 20px;
}
.capability-evidence code {
  display: block;
  overflow-wrap: anywhere;
}
.capability-evidence p {
  margin: 6px 0 14px;
}
.capability-intro p,
.capability-copy {
  line-height: 1.8;
  color: var(--app-text-secondary);
}
.capability-toolbar {
  display: grid;
  grid-template-columns: minmax(220px, 1fr) 150px 150px 150px;
  gap: 12px;
}
.capability-pagination {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
}
.capability-table :deep(.el-table__row) {
  cursor: pointer;
}
.capability-table .subtext {
  margin: 6px 0 0;
}
.capability-references,
.capability-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
}
.capability-actions {
  margin-top: 24px;
}
.capability-actions .el-button {
  margin: 0;
}
.capability-references a {
  font-size: 13px;
  color: var(--el-color-primary);
  text-decoration: none;
}
@media (max-width: 700px) {
  .capability-toolbar {
    grid-template-columns: 1fr;
  }
  .capability-depth-counts {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@media (min-width: 701px) and (max-width: 1100px) {
  .capability-toolbar {
    grid-template-columns: 1fr 1fr;
  }
}
</style>
