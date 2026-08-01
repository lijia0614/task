import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '../router'

const request = axios.create({ baseURL: '/api', timeout: 30000 })

/** 统一清除登录态并跳转登录页（不重复提示错误） */
function clearAuthAndRedirect() {
  localStorage.removeItem('token')
  localStorage.removeItem('user')
  if (router.currentRoute.value.path !== '/login') {
    router.push('/login')
  }
}

request.interceptors.request.use(config => {
  const token = localStorage.getItem('token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

request.interceptors.response.use(
  res => {
    const body = res.data
    if (body.code === 0) return body.data
    // HTTP 200 但业务 code=401（登录过期/未登录）也必须清 token 并跳登录
    if (body.code === 401) {
      clearAuthAndRedirect()
    } else {
      ElMessage.error(body.message || '请求失败')
    }
    return Promise.reject(new Error(body.message))
  },
  err => {
    const body = err.response?.data
    // HTTP 401（或业务 code 401）统一走清登录态，不弹错误（避免与跳转重复提示）
    if (err.response?.status === 401 || body?.code === 401) {
      clearAuthAndRedirect()
    } else {
      ElMessage.error(body?.message || '网络错误')
    }
    return Promise.reject(err)
  }
)

export default request
