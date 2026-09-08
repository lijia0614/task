import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  { path: '/login', name: 'login', component: () => import('../views/Login.vue') },
  {
    path: '/',
    name: 'layout',
    component: () => import('../layout/Layout.vue'),
    redirect: '/tasks',
    children: [
      { path: 'tasks', name: 'task-list', component: () => import('../views/TaskList.vue') },
      { path: 'tasks/create', name: 'task-create', component: () => import('../views/TaskCreate.vue') },
      { path: 'tasks/:id', name: 'task-detail', component: () => import('../views/TaskDetail.vue') },
      { path: 'reports/pending', name: 'report-review', component: () => import('../views/ReportReview.vue') },
      { path: 'groups', name: 'group-manage', component: () => import('../views/GroupManage.vue') },
      { path: 'users', name: 'user-manage', component: () => import('../views/UserManage.vue') },
      { path: 'notifications', name: 'notification-center', component: () => import('../views/NotificationCenter.vue') },
      { path: 'reports', name: 'admin-report', component: () => import('../views/ReportOverview.vue') }
    ]
  }
]

const router = createRouter({ history: createWebHistory(), routes })

/** 安全解析本地用户信息（损坏 JSON 不抛异常） */
function loadRole() {
  const raw = localStorage.getItem('user')
  if (!raw) return null
  try {
    return JSON.parse(raw)?.role || null
  } catch {
    return null
  }
}

// 守卫用路由 name 而非 to.path：Vue Router 对 '/users/'（尾斜杠）等也解析到
// 同一路由记录，精确 path 比较会漏掉这些变体，导致越权访问绕过
router.beforeEach((to) => {
  if (to.name !== 'login' && !localStorage.getItem('token')) return '/login'
  if (to.name === 'login' && localStorage.getItem('token')) return '/tasks'
  // 创建与审核仅管理员/组长；直接访问 URL 的越权用户安全返回任务列表
  if (to.name === 'task-create' || to.name === 'report-review') {
    const role = loadRole()
    if (role !== 'ADMIN' && role !== 'LEADER') return '/tasks'
  }
  // 用户目录包含账号与组织信息，只允许管理员直接访问
  if (to.name === 'user-manage' && loadRole() !== 'ADMIN') return '/tasks'
  // 报表仅管理员可访问
  if (to.name === 'admin-report' && loadRole() !== 'ADMIN') return '/tasks'
})

export default router
