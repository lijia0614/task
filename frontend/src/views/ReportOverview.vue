<template>
  <div class="section">
    <div class="page-head">
      <h2>数据报表</h2>
      <el-radio-group v-model="range" class="range-switch" @change="load">
        <el-radio-button value="all">全部</el-radio-button>
        <el-radio-button value="7d">近 7 天</el-radio-button>
        <el-radio-button value="30d">近 30 天</el-radio-button>
      </el-radio-group>
    </div>

    <div v-if="error" class="section center-box">
      <el-result icon="error" title="加载失败" :sub-title="error">
        <template #extra>
          <el-button type="primary" :icon="Refresh" @click="load">重试</el-button>
        </template>
      </el-result>
    </div>
    <div v-else-if="loading" class="section center-box">
      <el-skeleton :rows="3" animated />
    </div>
    <div v-else class="stat-grid">
      <div v-for="c in cards" :key="c.label" class="stat-card">
        <div class="stat-label">{{ c.label }}</div>
        <div class="stat-value">{{ c.value }}</div>
        <div v-if="c.rate !== undefined" class="rate-bar">
          <div class="rate-fill" :style="{ width: c.rate + '%' }"></div>
        </div>
        <div v-if="c.rate !== undefined" class="stat-rate">{{ c.rate }}%</div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { reportSummary } from '../api/report'

const range = ref('all')
const data = ref(null)
const error = ref('')
const loading = ref(false)

/** 请求序号：只有最新一次请求的响应才能更新页面状态 */
let requestSeq = 0

const load = async () => {
  const seq = ++requestSeq
  loading.value = true
  error.value = ''
  try {
    const res = await reportSummary(range.value)
    if (seq !== requestSeq) return // 过期响应丢弃
    data.value = res
  } catch (e) {
    if (seq !== requestSeq) return
    error.value = e.message || '网络错误'
    data.value = null
  } finally {
    if (seq === requestSeq) loading.value = false
  }
}

const cards = computed(() => {
  const d = data.value
  if (!d) return []
  return [
    { label: '任务总数', value: d.total },
    { label: '进行中', value: d.doing },
    { label: '已完成', value: d.done },
    { label: '已过期', value: d.overdue },
    { label: '完成率', value: d.done + '/' + d.total, rate: d.completionRate },
    { label: '逾期率', value: d.overdue + '/' + d.total, rate: d.overdueRate }
  ]
})

onMounted(load)
</script>

<style scoped>
.stat-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(170px, 1fr));
  gap: 12px;
}
.stat-card {
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-bg);
  padding: 16px;
}
.stat-label { color: var(--color-text-muted); font-size: 13px; }
.stat-value { font-size: 28px; font-weight: 600; margin: 6px 0; }
.stat-rate { margin-top: 6px; font-size: 13px; color: var(--color-text-secondary); }
.rate-bar {
  height: 8px;
  background: var(--color-border);
  border-radius: 4px;
  overflow: hidden;
}
.rate-fill {
  height: 100%;
  background: var(--color-primary);
  border-radius: 4px;
}
</style>
