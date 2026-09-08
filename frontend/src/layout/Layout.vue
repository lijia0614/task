<template>
  <div class="layout">
    <!-- 桌面端：固定侧边栏 -->
    <aside v-if="isDesktop" class="sidebar">
      <div class="sidebar-brand">任务分配系统</div>
      <el-menu class="sidebar-menu" :default-active="$route.path" router
               background-color="#1c2536" text-color="rgba(255,255,255,0.72)"
               active-text-color="#ffffff">
        <el-menu-item index="/tasks"><el-icon><Files /></el-icon><span>任务列表</span></el-menu-item>
        <el-menu-item v-if="auth.canCreateTask" index="/tasks/create"><el-icon><DocumentAdd /></el-icon><span>创建任务</span></el-menu-item>
        <el-menu-item v-if="auth.canCreateTask" index="/reports/pending"><el-icon><ChatDotRound /></el-icon><span>待我审核</span></el-menu-item>
        <el-menu-item index="/groups"><el-icon><UserFilled /></el-icon><span>小组管理</span></el-menu-item>
        <el-menu-item v-if="auth.isAdmin" index="/users"><el-icon><Setting /></el-icon><span>用户管理</span></el-menu-item>
        <el-menu-item v-if="auth.isAdmin" index="/reports"><el-icon><DataAnalysis /></el-icon><span>数据报表</span></el-menu-item>
      </el-menu>
    </aside>

    <!-- 主区域 -->
    <div class="main">
      <header class="topbar">
        <!-- 手机端：菜单按钮 -->
        <el-button v-if="!isDesktop" class="menu-btn" text :icon="Menu"
                   aria-label="打开导航菜单" title="打开导航菜单"
                   @click="drawerOpen = true" />
        <div class="topbar-title">{{ $route.meta.title || pageTitle }}</div>
        <div class="topbar-right">
          <el-popover ref="popRef" placement="bottom-end" :width="320" trigger="click" @show="loadRecent">
            <template #reference>
              <el-badge :value="unread" :hidden="unread === 0" class="bell-badge">
                <el-button text :icon="Bell" class="bell-btn" aria-label="通知" title="通知" />
              </el-badge>
            </template>
            <div class="bell-panel">
              <div class="bell-head">
                <strong>通知</strong>
                <el-button link type="primary" size="small" :disabled="unread === 0" @click="markAll">全部已读</el-button>
              </div>
              <div v-if="!recent.length" class="bell-empty">暂无通知</div>
              <div v-for="n in recent" :key="n.id" class="bell-item" :class="{ 'bell-unread': !n.read }"
                   @click="openNotification(n)">
                <div class="bell-item-head">
                  <strong>{{ n.title }}</strong>
                  <span class="bell-time">{{ formatTime(n.createdAt) }}</span>
                </div>
                <div class="bell-content">{{ n.content }}</div>
              </div>
              <div class="bell-foot">
                <el-button link type="primary" @click="goCenter">查看全部</el-button>
              </div>
            </div>
          </el-popover>
          <el-dropdown @command="handleCommand">
            <span class="user-chip">
              <el-icon class="user-icon"><User /></el-icon>
              <span class="user-name">{{ auth.user?.realName }}</span>
              <el-tag size="small" type="info" effect="plain">{{ auth.roleText }}</el-tag>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="logout" :icon="SwitchButton">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>

      <main class="content">
        <router-view />
      </main>
    </div>

    <!-- 手机端：抽屉菜单 -->
    <el-drawer v-model="drawerOpen" direction="ltr" size="240px" :with-header="false" class="drawer-nav">
      <div class="sidebar-brand">任务分配系统</div>
      <el-menu class="sidebar-menu" :default-active="$route.path" router
               @select="drawerOpen = false">
        <el-menu-item index="/tasks"><el-icon><Files /></el-icon><span>任务列表</span></el-menu-item>
        <el-menu-item v-if="auth.canCreateTask" index="/tasks/create"><el-icon><DocumentAdd /></el-icon><span>创建任务</span></el-menu-item>
        <el-menu-item v-if="auth.canCreateTask" index="/reports/pending"><el-icon><ChatDotRound /></el-icon><span>待我审核</span></el-menu-item>
        <el-menu-item index="/groups"><el-icon><UserFilled /></el-icon><span>小组管理</span></el-menu-item>
        <el-menu-item v-if="auth.isAdmin" index="/users"><el-icon><Setting /></el-icon><span>用户管理</span></el-menu-item>
        <el-menu-item v-if="auth.isAdmin" index="/reports"><el-icon><DataAnalysis /></el-icon><span>数据报表</span></el-menu-item>
      </el-menu>
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Bell, DataAnalysis, Menu, SwitchButton, User } from '@element-plus/icons-vue'
import { useAuthStore } from '../store/auth'
import { listNotifications, markAllRead, markRead, unreadCount } from '../api/notification'

const auth = useAuthStore()
const router = useRouter()
const drawerOpen = ref(false)
const viewportWidth = ref(window.innerWidth)

const isDesktop = computed(() => viewportWidth.value >= 768)
const pageTitle = computed(() => ({
  '/tasks': '任务列表',
  '/tasks/create': '创建任务',
  '/reports/pending': '待我审核',
  '/groups': '小组管理',
  '/users': '用户管理',
  '/reports': '数据报表',
  '/notifications': '消息中心'
}[router.currentRoute.value.path] || '任务分配系统'))

const onResize = () => { viewportWidth.value = window.innerWidth }
onMounted(() => window.addEventListener('resize', onResize))
onBeforeUnmount(() => window.removeEventListener('resize', onResize))

const popRef = ref(null)
const unread = ref(0)
const recent = ref([])
let pollTimer = null

const formatTime = (t) => (t ? String(t).replace('T', ' ').slice(0, 16) : '—')

/** 拉未读数（角标）；失败静默，不打扰主流程 */
const fetchUnread = async () => {
  try {
    unread.value = (await unreadCount()).count
  } catch (e) { /* 静默 */ }
}

const loadRecent = async () => {
  try {
    recent.value = (await listNotifications({ page: 1, size: 5 })).records || []
    await fetchUnread()
  } catch (e) { /* 静默 */ }
}

const markAll = async () => {
  try {
    await markAllRead()
    unread.value = 0
    recent.value.forEach((n) => { n.read = true })
    ElMessage.success('已全部标记为已读')
  } catch (e) {
    // 错误提示已由请求拦截器统一弹出
  }
}

const openNotification = async (n) => {
  popRef.value?.hide()
  if (!n.read) {
    try {
      await markRead(n.id)
      n.read = true
      unread.value = Math.max(0, unread.value - 1)
    } catch (e) {
      return // 错误提示已由请求拦截器统一弹出；失败则不跳转
    }
  }
  router.push(n.taskId ? `/tasks/${n.taskId}` : '/reports/pending')
}

const goCenter = () => {
  popRef.value?.hide()
  router.push('/notifications')
}

onMounted(() => {
  fetchUnread()
  pollTimer = setInterval(fetchUnread, 30000)
})
onBeforeUnmount(() => { if (pollTimer) clearInterval(pollTimer) })

const handleCommand = (cmd) => {
  if (cmd === 'logout') {
    auth.logout()
    router.push('/login')
  }
}
</script>

<style scoped>
.layout {
  display: flex;
  height: 100vh;
  height: 100dvh;
  overflow: hidden;
}

.sidebar {
  width: 220px;
  flex-shrink: 0;
  background: var(--color-sidebar);
  display: flex;
  flex-direction: column;
}

.sidebar-brand {
  padding: 18px 16px;
  color: #ffffff;
  font-size: 16px;
  font-weight: 600;
  letter-spacing: 0;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}

.sidebar-menu {
  flex: 1;
  border-right: none;
  padding-top: 4px;
}

.sidebar-menu :deep(.el-menu-item) {
  border-radius: var(--radius-sm);
  margin: 2px 8px;
}

.main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.topbar {
  height: 52px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 16px;
  background: var(--color-surface);
  border-bottom: 1px solid var(--color-border);
}

.topbar-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--color-text);
}

.topbar-right {
  margin-left: auto;
}

.user-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  color: var(--color-text-secondary);
}

.user-icon {
  font-size: 16px;
}

.user-name {
  font-weight: 500;
  color: var(--color-text);
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.content {
  flex: 1;
  overflow: auto;
  padding: 16px;
}

/* 手机端抽屉内的菜单配色与桌面一致 */
.drawer-nav :deep(.sidebar-brand) {
  color: var(--color-text);
  border-bottom: 1px solid var(--color-border);
}

@media (max-width: 767px) {
  .content {
    padding: 12px;
  }

  .user-name {
    max-width: 72px;
  }
}

.bell-badge { margin-right: 4px; }
.bell-btn { font-size: 18px; }
.bell-panel { display: flex; flex-direction: column; }
.bell-head { display: flex; justify-content: space-between; align-items: center; padding-bottom: 8px; border-bottom: 1px solid var(--color-border); }
.bell-empty { padding: 24px 0; text-align: center; color: var(--color-text-muted); font-size: 13px; }
.bell-item { padding: 8px 4px; border-bottom: 1px solid var(--color-border); cursor: pointer; }
.bell-item:last-of-type { border-bottom: none; }
.bell-item:hover .bell-item-head strong { color: var(--color-primary); }
.bell-unread .bell-item-head strong::before { content: ''; display: inline-block; width: 6px; height: 6px; border-radius: 50%; background: var(--color-danger); margin-right: 6px; vertical-align: middle; }
.bell-item-head { display: flex; justify-content: space-between; gap: 8px; align-items: baseline; }
.bell-time { color: var(--color-text-muted); font-size: 12px; flex-shrink: 0; }
.bell-content { margin-top: 2px; color: var(--color-text-secondary); font-size: 12px; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.bell-foot { padding-top: 8px; text-align: center; }
</style>
