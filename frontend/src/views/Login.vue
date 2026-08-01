<template>
  <div class="login-page">
    <div class="login-panel section">
      <div class="login-brand">
        <h1>任务分配系统</h1>
        <p>公司内部任务管理平台</p>
      </div>
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" size="large"
               @keyup.enter="submit">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" placeholder="请输入用户名"
                    autocomplete="username" :prefix-icon="User" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input v-model="form.password" type="password" placeholder="请输入密码" show-password
                    autocomplete="current-password" :prefix-icon="Lock" />
        </el-form-item>
        <el-button type="primary" class="login-btn" :loading="loading" @click="submit">
          {{ loading ? '登录中…' : '登 录' }}
        </el-button>
      </el-form>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { User, Lock } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '../store/auth'

const auth = useAuthStore()
const router = useRouter()
const formRef = ref(null)
const loading = ref(false)
const form = reactive({ username: '', password: '' })
const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

const submit = async () => {
  if (loading.value) return
  const ok = await formRef.value.validate().catch(() => false)
  if (!ok) return
  loading.value = true
  try {
    await auth.login(form.username, form.password)
    router.push('/tasks')
  } catch (e) {
    // 登录失败提示由 request 拦截器统一处理（不重复提示）
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--color-bg);
}

.login-panel {
  width: 360px;
  max-width: calc(100vw - 32px);
  padding: 28px 24px;
}

.login-brand {
  margin-bottom: 20px;
}

.login-brand h1 {
  font-size: 20px;
  font-weight: 600;
  margin: 0 0 4px;
  color: var(--color-text);
}

.login-brand p {
  margin: 0;
  font-size: 13px;
  color: var(--color-text-muted);
}

.login-btn {
  width: 100%;
  margin-top: 4px;
}
</style>
