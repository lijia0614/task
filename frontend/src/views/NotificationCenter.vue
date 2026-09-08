<template>
  <div class="section">
    <div class="page-head">
      <h2>消息中心</h2>
      <el-button type="primary" plain :icon="Check" :disabled="unread === 0 || markingAll"
                 :loading="markingAll" @click="markAll">全部已读</el-button>
    </div>

    <div v-if="error" class="section center-box">
      <el-result icon="error" title="加载失败" :sub-title="error">
        <template #extra>
          <el-button type="primary" :icon="Refresh" @click="load">重试</el-button>
        </template>
      </el-result>
    </div>
    <div v-else-if="loading" class="section center-box">
      <el-skeleton :rows="5" animated />
    </div>
    <div v-else-if="!records.length" class="section center-box">
      <el-empty description="暂无通知" :image-size="80" />
    </div>
    <div v-else class="notif-list">
      <article v-for="n in records" :key="n.id" class="notif-row" :class="{ 'notif-unread': !n.read }"
               :data-notification-id="n.id" role="link" tabindex="0"
               @click="openNotification(n)" @keydown.enter="openNotification(n)">
        <span v-if="!n.read" class="notif-dot" aria-hidden="true"></span>
        <div class="notif-main">
          <div class="notif-head">
            <strong>{{ n.title }}</strong>
            <span class="notif-time">{{ formatTime(n.createdAt) }}</span>
          </div>
          <p class="notif-content">{{ n.content }}</p>
        </div>
        <el-icon class="notif-arrow"><ArrowRight /></el-icon>
      </article>
      <el-pagination v-if="total > pageSize" class="notif-pagination" layout="prev, pager, next, total"
                     v-model:current-page="page" :page-size="pageSize" :total="total"
                     @current-change="load" />
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowRight, Check, Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { listNotifications, markAllRead, markRead, unreadCount } from '../api/notification'

const router = useRouter()
const records = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = 10
const loading = ref(false)
const error = ref('')
const unread = ref(0)
const markingAll = ref(false)

/** 请求序号：只有最新一次请求的响应才能更新页面状态 */
let requestSeq = 0

const formatTime = (t) => (t ? String(t).replace('T', ' ').slice(0, 16) : '—')

const fetchUnread = async () => {
  try {
    unread.value = (await unreadCount()).count
  } catch (e) {
    // 角标失败静默，不打扰主流程
  }
}

const load = async () => {
  const seq = ++requestSeq
  loading.value = true
  error.value = ''
  try {
    const data = await listNotifications({ page: page.value, size: pageSize })
    if (seq !== requestSeq) return // 过期响应丢弃
    records.value = Array.isArray(data.records) ? data.records : []
    total.value = data.total
  } catch (e) {
    if (seq !== requestSeq) return
    error.value = e.message || '网络错误'
    records.value = []
    total.value = 0
  } finally {
    if (seq === requestSeq) loading.value = false
  }
}

const openNotification = async (n) => {
  if (!n.read) {
    try {
      await markRead(n.id)
      n.read = true
      unread.value = Math.max(0, unread.value - 1)
    } catch (e) {
      ElMessage.error(e.message || '操作失败')
      return
    }
  }
  router.push(n.taskId ? `/tasks/${n.taskId}` : '/reports/pending')
}

const markAll = async () => {
  if (markingAll.value || unread.value === 0) return
  markingAll.value = true
  try {
    await markAllRead()
    records.value.forEach((n) => { n.read = true })
    unread.value = 0
    ElMessage.success('已全部标记为已读')
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  } finally {
    markingAll.value = false
  }
}

onMounted(() => {
  fetchUnread()
  load()
})
</script>

<style scoped>
.notif-list { display: flex; flex-direction: column; gap: 8px; }
.notif-row {
  position: relative;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 14px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-bg);
  cursor: pointer;
}
.notif-row:hover { border-color: var(--color-primary); }
.notif-unread { background: var(--color-primary-soft, #eef4ff); }
.notif-dot {
  width: 8px; height: 8px; border-radius: 50%;
  background: var(--color-danger); flex-shrink: 0;
}
.notif-main { flex: 1; min-width: 0; }
.notif-head { display: flex; justify-content: space-between; gap: 8px; align-items: baseline; }
.notif-time { color: var(--color-text-muted); font-size: 12px; flex-shrink: 0; }
.notif-content { margin: 4px 0 0; color: var(--color-text-secondary); font-size: 13px; }
.notif-arrow { color: var(--color-text-muted); }
.notif-pagination { display: flex; justify-content: center; margin-top: 12px; }
</style>
