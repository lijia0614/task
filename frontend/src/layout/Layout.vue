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
      </el-menu>
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Menu, User, SwitchButton } from '@element-plus/icons-vue'
import { useAuthStore } from '../store/auth'

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
  '/users': '用户管理'
}[router.currentRoute.value.path] || '任务分配系统'))

const onResize = () => { viewportWidth.value = window.innerWidth }
onMounted(() => window.addEventListener('resize', onResize))
onBeforeUnmount(() => window.removeEventListener('resize', onResize))

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
</style>
