import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  { path: '/login', component: () => import('../views/Login.vue') },
  {
    path: '/',
    component: () => import('../layout/Layout.vue'),
    redirect: '/tasks',
    children: [
      { path: 'tasks', component: () => import('../views/TaskList.vue') },
      { path: 'tasks/create', component: () => import('../views/TaskCreate.vue') },
      { path: 'tasks/:id', component: () => import('../views/TaskDetail.vue') },
      { path: 'reports/pending', component: () => import('../views/ReportReview.vue') },
      { path: 'groups', component: () => import('../views/GroupManage.vue') },
      { path: 'users', component: () => import('../views/UserManage.vue') }
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

router.beforeEach((to) => {
  if (to.path !== '/login' && !localStorage.getItem('token')) return '/login'
  if (to.path === '/login' && localStorage.getItem('token')) return '/tasks'
  // 创建与审核仅管理员/组长；直接访问 URL 的越权用户安全返回任务列表
  if (to.path === '/tasks/create' || to.path === '/reports/pending') {
    const role = loadRole()
    if (role !== 'ADMIN' && role !== 'LEADER') return '/tasks'
  }
})

export default router
