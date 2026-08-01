import { defineStore } from 'pinia'
import { login as loginApi } from '../api/auth'

/** 安全解析 localStorage.user：损坏的 JSON 返回 null 并清除，不抛异常 */
function loadUser() {
  const raw = localStorage.getItem('user')
  if (!raw) return null
  try {
    return JSON.parse(raw)
  } catch {
    localStorage.removeItem('user')
    return null
  }
}

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem('token') || '',
    user: loadUser()
  }),
  getters: {
    isAdmin: s => s.user?.role === 'ADMIN',
    isLeader: s => s.user?.role === 'LEADER',
    canCreateTask: s => s.user?.role === 'ADMIN' || s.user?.role === 'LEADER',
    roleText: s => ({ ADMIN: '管理员', LEADER: '组长', EMPLOYEE: '员工' }[s.user?.role] || '')
  },
  actions: {
    async login(username, password) {
      const data = await loginApi({ username, password })
      this.token = data.token
      this.user = data.user
      localStorage.setItem('token', data.token)
      localStorage.setItem('user', JSON.stringify(data.user))
    },
    logout() {
      this.token = ''
      this.user = null
      localStorage.removeItem('token')
      localStorage.removeItem('user')
    }
  }
})
