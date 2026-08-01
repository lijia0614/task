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

router.beforeEach((to) => {
  if (to.path !== '/login' && !localStorage.getItem('token')) return '/login'
  if (to.path === '/login' && localStorage.getItem('token')) return '/tasks'
})

export default router
