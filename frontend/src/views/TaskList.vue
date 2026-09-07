<template>
  <div>
    <!-- 筛选栏：无框、紧凑 -->
    <div class="filter-bar">
      <el-tabs v-model="activeTab" @tab-change="resetPageAndLoad">
        <el-tab-pane label="全部" name="all" />
        <el-tab-pane v-if="auth.canCreateTask" label="我创建的" name="mine_created" />
        <el-tab-pane label="分配给我的" name="assigned" />
      </el-tabs>
      <div class="filter-right">
        <el-input v-model="keyword" class="kw-input" placeholder="搜索任务名称" clearable
                  :prefix-icon="Search" @keyup.enter="resetPageAndLoad" @clear="resetPageAndLoad" />
        <el-select v-model="status" class="status-select" placeholder="全部状态" clearable @change="resetPageAndLoad">
          <el-option label="进行中" value="DOING" />
          <el-option label="已完成" value="DONE" />
        </el-select>
        <el-button v-if="auth.canCreateTask" type="primary" :icon="Plus" @click="$router.push('/tasks/create')">
          创建任务
        </el-button>
      </div>
    </div>

    <!-- 列表主体：骨架/空/错误与结果共用高度，切换不跳动 -->
    <div class="list-body">
      <!-- 请求失败：可恢复 -->
      <div v-if="error" class="section center-box">
        <el-result icon="error" title="加载失败" :sub-title="error">
          <template #extra>
            <el-button type="primary" :icon="Refresh" @click="load">重试</el-button>
          </template>
        </el-result>
      </div>

      <!-- 加载骨架（与卡片同尺寸） -->
      <div v-else-if="loading" class="card-grid">
        <div v-for="i in 6" :key="i" class="task-card skeleton-card">
          <el-skeleton animated>
            <template #template>
              <el-skeleton-item variant="h3" style="width: 45%" />
              <el-skeleton-item variant="text" style="width: 92%; margin-top: 12px" />
              <el-skeleton-item variant="text" style="width: 65%; margin-top: 6px" />
              <el-skeleton-item variant="text" style="width: 100%; margin-top: 18px" />
            </template>
          </el-skeleton>
        </div>
      </div>

      <!-- 空状态 -->
      <div v-else-if="!records.length" class="section center-box">
        <el-empty description="暂无任务" :image-size="80" />
      </div>

      <!-- 任务卡片 -->
      <div v-else class="card-grid">
        <div v-for="t in records" :key="t.id" class="task-card" tabindex="0" role="link"
             :aria-label="`查看任务：${t.name}`" @click="open(t)"
             @keydown.enter="open(t)" @keydown.space.prevent="open(t)">
          <div class="card-head">
            <span class="task-name" :title="t.name">{{ t.name }}</span>
            <el-tag :type="t.status === 'DONE' ? 'success' : 'primary'" size="small" effect="plain">
              {{ t.status === 'DONE' ? '已完成' : '进行中' }}
            </el-tag>
          </div>
          <p class="task-desc" :title="t.description || ''">{{ t.description || '暂无简介' }}</p>
          <div class="task-meta">
            <span class="meta-item" :title="t.assignType === 'GROUP' ? '小组任务' : '个人任务'">
              <el-icon><component :is="t.assignType === 'GROUP' ? UserFilled : User" /></el-icon>
              <span class="meta-text">{{ t.assignType === 'GROUP' ? '小组' : '个人' }} · {{ t.assigneeName || '—' }}</span>
            </span>
            <span class="meta-item" :title="`负责人：${t.creatorName || ''}`">
              <el-icon><Avatar /></el-icon>
              <span class="meta-text">{{ t.creatorName || '—' }}</span>
            </span>
            <span class="meta-item" :class="{ 'deadline-overdue': isOverdue(t) }" :title="deadlineText(t)">
              <el-icon><Clock /></el-icon>
              <span class="meta-text">{{ deadlineText(t) }}</span>
            </span>
          </div>
          <div class="progress-row">
            <el-progress class="progress-bar" :percentage="safeProgress(t.progress)" :stroke-width="6"
                         :color="t.status === 'DONE' ? 'var(--color-success)' : 'var(--color-primary)'" />
            <span class="progress-text">{{ safeProgress(t.progress) }}%</span>
          </div>
        </div>
      </div>
    </div>

    <!-- 分页：总数超过一页时显示（与用户管理页同款交互） -->
    <el-pagination v-if="total > pageSize" class="task-pagination" layout="prev, pager, next, total"
                   v-model:current-page="page" :page-size="pageSize" :total="total"
                   @current-change="load" />
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Search, Refresh, User, UserFilled, Avatar, Clock, Plus } from '@element-plus/icons-vue'
import { listTasks } from '../api/task'
import { useAuthStore } from '../store/auth'

const auth = useAuthStore()
const router = useRouter()
const activeTab = ref('all')
const keyword = ref('')
const status = ref('')
const records = ref([])   // 当前页记录（分页响应 records）
const total = ref(0)      // 总数（分页响应 total）
const page = ref(1)
const pageSize = 12
const loading = ref(false)
const error = ref('')

/** 请求序号：只有最新一次筛选请求的响应才能更新页面状态，防止旧响应覆盖 */
let requestSeq = 0

/** 防御：null/超范围进度钳制到 0-100 */
const safeProgress = (p) => Math.max(0, Math.min(100, p ?? 0))

const deadlineText = (t) => (t.deadline ? String(t.deadline).slice(0, 10) : '未设置截止')

const isOverdue = (t) => t.status !== 'DONE' && !!t.deadline && new Date(t.deadline).getTime() < Date.now()

const load = async () => {
  const seq = ++requestSeq
  loading.value = true
  error.value = ''
  try {
    const data = await listTasks({
      type: activeTab.value,
      status: status.value || undefined,
      keyword: keyword.value || undefined,
      page: page.value,
      size: pageSize
    })
    if (seq !== requestSeq) return // 过期响应丢弃
    // 防御：后端异常时 records 可能非数组（如 null），兜底为空数组避免模板 !records.length 抛错
    records.value = Array.isArray(data.records) ? data.records : []
    total.value = data.total
  } catch (e) {
    if (seq !== requestSeq) return // 过期失败也丢弃
    error.value = e.message || '网络错误'
    records.value = []
    total.value = 0
  } finally {
    if (seq === requestSeq) loading.value = false
  }
}

/** 筛选条件变化：回到第 1 页再加载 */
const resetPageAndLoad = () => {
  page.value = 1
  load()
}

const open = (t) => router.push(`/tasks/${t.id}`)

onMounted(load)
</script>

<style scoped>
/* 筛选栏：无框紧凑 */
.filter-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.filter-bar :deep(.el-tabs__header) {
  margin: 0;
}

.filter-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.kw-input {
  width: 220px;
}

.status-select {
  width: 130px;
}

/* 列表主体：统一最小高度避免切换跳动 */
.list-body {
  min-height: 400px;
}

.center-box {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 400px;
}

/* 卡片网格：桌面 3 列 / 平板 2 列 / 手机 1 列 */
.card-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
}

@media (max-width: 1199px) {
  .card-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (max-width: 767px) {
  .card-grid {
    grid-template-columns: 1fr;
  }

  .filter-bar {
    flex-direction: column;
    align-items: stretch;
  }

  .filter-right {
    width: 100%;
  }

  .kw-input {
    flex: 1;
    width: auto;
  }
}

/* 任务卡片：稳定高度、可键盘操作、明确焦点态 */
.task-card {
  height: 172px;
  display: flex;
  flex-direction: column;
  padding: 14px;
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: border-color 0.15s ease;
}

.task-card:hover {
  border-color: var(--color-border-strong);
}

.task-card:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 2px;
}

.skeleton-card {
  cursor: default;
}

.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.task-name {
  font-size: 15px;
  font-weight: 600;
  color: var(--color-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.task-desc {
  margin: 8px 0;
  font-size: 13px;
  line-height: 1.5;
  color: var(--color-text-secondary);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.task-meta {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 12px;
  color: var(--color-text-muted);
  margin-bottom: auto;
  min-width: 0;
}

.meta-item {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  min-width: 0;
}

.meta-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.deadline-overdue {
  color: var(--color-danger);
}

.progress-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
}

.progress-bar {
  flex: 1;
}

.progress-text {
  font-size: 12px;
  color: var(--color-text-secondary);
  min-width: 34px;
  text-align: right;
}

.task-pagination { display: flex; justify-content: center; margin-top: 16px; }

/* 窄屏分页：与用户管理页同款——隐藏总数、防溢出 */
@media (max-width: 640px) {
  .task-pagination { overflow: hidden; }
  .task-pagination :deep(.el-pagination__total) { display: none; }
}
</style>
