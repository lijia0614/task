<template>
  <div>
    <!-- 筛选栏：无框、紧凑 -->
    <div class="filter-bar">
      <el-tabs v-model="activeTab" @tab-change="load">
        <el-tab-pane label="全部" name="all" />
        <el-tab-pane v-if="auth.canCreateTask" label="我创建的" name="mine_created" />
        <el-tab-pane label="分配给我的" name="assigned" />
      </el-tabs>
      <div class="filter-right">
        <el-input v-model="keyword" class="kw-input" placeholder="搜索任务名称" clearable
                  :prefix-icon="Search" @keyup.enter="load" @clear="load" />
        <el-select v-model="status" class="status-select" placeholder="全部状态" clearable @change="load">
          <el-option label="进行中" value="DOING" />
          <el-option label="已完成" value="DONE" />
        </el-select>
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
      <div v-else-if="!tasks.length" class="section center-box">
        <el-empty description="暂无任务" :image-size="80" />
      </div>

      <!-- 任务卡片 -->
      <div v-else class="card-grid">
        <div v-for="t in tasks" :key="t.id" class="task-card" tabindex="0" role="link"
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
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Search, Refresh, User, UserFilled, Avatar, Clock } from '@element-plus/icons-vue'
import { listTasks } from '../api/task'
import { useAuthStore } from '../store/auth'

const auth = useAuthStore()
const router = useRouter()
const activeTab = ref('all')
const keyword = ref('')
const status = ref('')
const tasks = ref([])
const loading = ref(false)
const error = ref('')

/** 防御：null/超范围进度钳制到 0-100 */
const safeProgress = (p) => Math.max(0, Math.min(100, p ?? 0))

const deadlineText = (t) => (t.deadline ? String(t.deadline).slice(0, 10) : '未设置截止')

const isOverdue = (t) => t.status !== 'DONE' && !!t.deadline && new Date(t.deadline).getTime() < Date.now()

const load = async () => {
  loading.value = true
  error.value = ''
  try {
    tasks.value = await listTasks({
      type: activeTab.value,
      status: status.value || undefined,
      keyword: keyword.value || undefined
    })
  } catch (e) {
    error.value = e.message || '网络错误'
    tasks.value = []
  } finally {
    loading.value = false
  }
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
</style>
