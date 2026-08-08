<template>
  <div class="user-page">
    <div class="page-head user-head">
      <div>
        <span class="eyebrow">身份与权限</span>
        <h2>用户管理</h2>
      </div>
      <div class="head-actions">
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        <el-button type="primary" :icon="Plus" @click="openCreate">新建用户</el-button>
      </div>
    </div>

    <section class="section filter-section" aria-label="用户筛选">
      <el-input v-model="query.keyword" class="keyword-input" clearable placeholder="搜索姓名"
                :prefix-icon="Search" @keyup.enter="search" @clear="search" />
      <el-select v-model="query.role" class="role-filter" aria-label="角色筛选" placeholder="全部角色">
        <el-option label="全部角色" value="" />
        <el-option v-for="option in roleOptions" :key="option.value"
                   :label="option.label" :value="option.value" />
      </el-select>
      <el-button type="primary" :icon="Search" @click="search">查询</el-button>
      <el-button :icon="RefreshLeft" @click="resetFilters">重置</el-button>
    </section>

    <el-alert v-if="groupsError" class="groups-error" type="warning" :closable="false" show-icon>
      <span>小组列表加载失败，新建/编辑用户暂不可用：{{ groupsError }}</span>
      <el-button link type="primary" :icon="Refresh" @click="loadGroups">重试</el-button>
    </el-alert>

    <div v-if="error" class="section center-state">
      <el-result icon="error" title="加载失败" :sub-title="error">
        <template #extra>
          <el-button type="primary" :icon="Refresh" @click="load">重试</el-button>
        </template>
      </el-result>
    </div>

    <div v-else-if="loading" class="section loading-section">
      <div v-for="item in 5" :key="item" class="user-skeleton">
        <el-skeleton :rows="1" animated />
      </div>
    </div>

    <div v-else-if="!users.length" class="section center-state">
      <el-empty :description="hasFilters ? '没有符合条件的用户' : '暂无用户'" :image-size="76" />
    </div>

    <section v-else class="section user-section">
      <div class="list-summary">
        <span>共 {{ total }} 位用户</span>
        <span>当前第 {{ query.page }} 页</span>
      </div>
      <div class="user-list" role="table" aria-label="用户列表">
        <div class="user-list-head" role="row">
          <span role="columnheader">用户</span>
          <span role="columnheader">角色</span>
          <span role="columnheader">所属小组</span>
          <span role="columnheader">操作</span>
        </div>
        <article v-for="user in users" :key="user.id" class="user-row" role="row"
                 :data-user-id="user.id">
          <div class="user-identity" role="cell">
            <span class="user-avatar" aria-hidden="true">{{ userInitial(user) }}</span>
            <div>
              <strong :title="user.realName || ''">{{ user.realName || '未命名用户' }}</strong>
              <span :title="user.username || ''">{{ user.username || '—' }}</span>
            </div>
          </div>
          <div class="user-role" role="cell">
            <el-tag :type="roleTagType(user.role)" effect="plain">{{ roleText(user.role) }}</el-tag>
          </div>
          <div class="user-group" role="cell" :title="user.groupName || ''">
            <el-icon><OfficeBuilding /></el-icon>
            <span>{{ user.groupName || '未分组' }}</span>
          </div>
          <div class="user-actions" role="cell">
            <el-button size="small" :icon="Edit" @click="openEdit(user)">编辑</el-button>
            <el-button size="small" :icon="Key" @click="openReset(user)">重置密码</el-button>
            <el-button size="small" type="danger" plain :icon="Delete"
                       :disabled="isCurrentUser(user)" :loading="deleteActionId === user.id"
                       :title="isCurrentUser(user) ? '不能删除当前登录账号' : '删除用户'"
                       @click="removeUser(user)">
              删除
            </el-button>
          </div>
        </article>
      </div>

      <el-pagination v-if="total > query.size" class="user-pagination"
                     v-model:current-page="query.page" :page-size="query.size"
                     layout="prev, pager, next, total" :total="total"
                     @current-change="load" />
    </section>

    <el-dialog v-model="formVisible" :title="formMode === 'create' ? '新建用户' : '编辑用户'"
               :width="formDialogWidth" destroy-on-close
               :close-on-click-modal="!formSubmitting" :close-on-press-escape="!formSubmitting"
               :show-close="!formSubmitting" :before-close="beforeFormClose">
      <el-form label-width="82px" @submit.prevent>
        <el-form-item label="用户名" required>
          <el-input v-model="userForm.username" class="username-input" maxlength="50"
                    autocomplete="off" :disabled="formMode === 'edit' || formSubmitting"
                    placeholder="用于登录" />
        </el-form-item>
        <el-form-item v-if="formMode === 'create'" label="初始密码" required>
          <el-input v-model="userForm.password" class="password-input" type="password"
                    show-password autocomplete="new-password" :disabled="formSubmitting"
                    placeholder="至少 6 位" />
        </el-form-item>
        <el-form-item label="姓名" required>
          <el-input v-model="userForm.realName" class="real-name-input" maxlength="50"
                    :disabled="formSubmitting" placeholder="显示姓名" />
        </el-form-item>
        <el-form-item label="角色" required>
          <el-select v-model="userForm.role" class="role-select" :disabled="formSubmitting"
                     style="width: 100%">
            <el-option v-for="option in roleOptions" :key="option.value"
                       :label="option.label" :value="option.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="所属小组">
          <el-select v-model="userForm.groupId" class="group-select" clearable filterable
                     :disabled="formSubmitting" placeholder="未分组" style="width: 100%">
            <el-option v-for="group in groups" :key="group.id" :label="group.name" :value="group.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="formSubmitting" @click="formVisible = false">取消</el-button>
        <el-button type="primary" :icon="formMode === 'create' ? Plus : Check"
                   :loading="formSubmitting" @click="saveUser">
          {{ formMode === 'create' ? '创建' : '保存' }}
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="resetVisible" :title="`重置「${resetTarget?.realName || resetTarget?.username || ''}」密码`"
               :width="resetDialogWidth" destroy-on-close
               :close-on-click-modal="!resetSubmitting" :close-on-press-escape="!resetSubmitting"
               :show-close="!resetSubmitting" :before-close="beforeResetClose" @closed="onResetClosed">
      <p class="reset-note">重置后，用户需要使用新密码重新登录。</p>
      <el-input v-model="newPassword" class="reset-password-input" type="password" show-password
                autocomplete="new-password" :disabled="resetSubmitting" placeholder="输入至少 6 位新密码"
                @keyup.enter="submitReset" />
      <template #footer>
        <el-button :disabled="resetSubmitting" @click="resetVisible = false">取消</el-button>
        <el-button type="primary" :icon="Key" :loading="resetSubmitting" @click="submitReset">
          确认重置
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Check, Delete, Edit, Key, OfficeBuilding, Plus, Refresh, RefreshLeft, Search
} from '@element-plus/icons-vue'
import { createUser, deleteUser, listUsers, resetPassword, updateUser } from '../api/user'
import { listGroups } from '../api/group'
import { useAuthStore } from '../store/auth'

const auth = useAuthStore()
const roleOptions = [
  { label: '管理员', value: 'ADMIN' },
  { label: '组长', value: 'LEADER' },
  { label: '员工', value: 'EMPLOYEE' }
]
const users = ref([])
const groups = ref([])
const total = ref(0)
const loading = ref(false)
const error = ref('')
/** 小组列表加载失败：显式可见并可重试；失败期间阻止打开新建/编辑弹窗，避免小组选项缺失仍看似有效 */
const groupsError = ref('')
const query = reactive({ page: 1, size: 10, keyword: '', role: '' })
const formVisible = ref(false)
const formMode = ref('create')
const formSubmitting = ref(false)
const editTargetId = ref(null)
const deleteActionId = ref(null)
const resetVisible = ref(false)
const resetSubmitting = ref(false)
const resetTarget = ref(null)
const newPassword = ref('')
const viewportWidth = ref(window.innerWidth)
const userForm = reactive({ username: '', password: '', realName: '', role: 'EMPLOYEE', groupId: null })
let listRequestSeq = 0
let groupRequestSeq = 0

const formDialogWidth = computed(() => `${Math.min(520, viewportWidth.value - 24)}px`)
const resetDialogWidth = computed(() => `${Math.min(440, viewportWidth.value - 24)}px`)
const hasFilters = computed(() => !!query.keyword.trim() || !!query.role)

const roleText = role => ({ ADMIN: '管理员', LEADER: '组长', EMPLOYEE: '员工' }[role] || role || '未知')
const roleTagType = role => ({ ADMIN: 'danger', LEADER: 'warning', EMPLOYEE: 'info' }[role] || 'info')
const userInitial = user => String(user.realName || user.username || '?').slice(0, 1)
const isCurrentUser = user => Number(user.id) === Number(auth.user?.id)
const beforeFormClose = done => { if (!formSubmitting.value) done() }
const beforeResetClose = done => { if (!resetSubmitting.value) done() }

const load = async () => {
  const seq = ++listRequestSeq
  const params = {
    page: query.page,
    size: query.size,
    keyword: query.keyword.trim() || undefined,
    role: query.role || undefined
  }
  loading.value = true
  error.value = ''
  try {
    const data = await listUsers(params)
    if (seq !== listRequestSeq) return
    users.value = Array.isArray(data?.records) ? data.records : []
    total.value = Number(data?.total) || 0
  } catch (e) {
    if (seq !== listRequestSeq) return
    users.value = []
    total.value = 0
    error.value = e.message || '网络错误'
  } finally {
    if (seq === listRequestSeq) loading.value = false
  }
}

const loadGroups = async () => {
  const seq = ++groupRequestSeq
  try {
    const data = await listGroups()
    if (seq === groupRequestSeq) {
      groups.value = Array.isArray(data) ? data : []
      groupsError.value = ''
    }
  } catch (e) {
    if (seq === groupRequestSeq) {
      groups.value = []
      groupsError.value = e.message || '网络错误'
    }
  }
}

const search = () => {
  query.page = 1
  load()
}

const resetFilters = () => {
  query.keyword = ''
  query.role = ''
  query.page = 1
  load()
}

const resetUserForm = () => {
  userForm.username = ''
  userForm.password = ''
  userForm.realName = ''
  userForm.role = 'EMPLOYEE'
  userForm.groupId = null
}

/** 小组加载失败时阻止新建/编辑：让用户先重试，而不是打开一个小组选项缺失的表单 */
const ensureGroupsReady = () => {
  if (groupsError.value) {
    ElMessage.warning('小组列表加载失败，请先点击重试')
    return false
  }
  return true
}

const openCreate = () => {
  if (!ensureGroupsReady()) return
  formMode.value = 'create'
  editTargetId.value = null
  resetUserForm()
  formVisible.value = true
}

const openEdit = user => {
  if (!ensureGroupsReady()) return
  formMode.value = 'edit'
  editTargetId.value = user.id
  userForm.username = user.username || ''
  userForm.password = ''
  userForm.realName = user.realName || ''
  userForm.role = user.role || 'EMPLOYEE'
  userForm.groupId = user.groupId ?? null
  formVisible.value = true
}

const saveUser = async () => {
  if (formSubmitting.value) return
  const mode = formMode.value
  const targetId = editTargetId.value
  const username = userForm.username.trim()
  const password = userForm.password
  const realName = userForm.realName.trim()
  const role = userForm.role
  const groupId = userForm.groupId ?? null
  if (!realName || !role || (mode === 'create' && (!username || password.length < 6))) {
    return ElMessage.warning(mode === 'create' ? '请填写用户名、至少 6 位密码、姓名和角色' : '请填写姓名和角色')
  }
  if (mode === 'edit' && !targetId) return ElMessage.warning('用户状态已变化，请刷新')

  formSubmitting.value = true
  try {
    if (mode === 'create') {
      await createUser({ username, password, realName, role, groupId })
      ElMessage.success('用户已创建')
    } else {
      await updateUser(targetId, { realName, role, groupId })
      ElMessage.success('用户信息已更新')
    }
    formVisible.value = false
    resetUserForm()
    await load()
  } finally {
    formSubmitting.value = false
  }
}

const openReset = user => {
  resetTarget.value = { id: user.id, realName: user.realName, username: user.username }
  newPassword.value = ''
  resetVisible.value = true
}

const onResetClosed = () => {
  if (resetSubmitting.value) return
  resetTarget.value = null
  newPassword.value = ''
}

const submitReset = async () => {
  if (resetSubmitting.value) return
  const targetId = resetTarget.value?.id
  const password = newPassword.value
  if (!targetId) return ElMessage.warning('用户状态已变化，请刷新')
  if (password.length < 6) return ElMessage.warning('请输入至少 6 位新密码')
  resetSubmitting.value = true
  try {
    await resetPassword(targetId, password)
    ElMessage.success('密码已重置')
    resetVisible.value = false
  } finally {
    resetSubmitting.value = false
  }
}

const removeUser = async user => {
  if (isCurrentUser(user) || deleteActionId.value !== null) return
  const targetId = user.id
  const targetName = user.realName || user.username
  deleteActionId.value = targetId
  try {
    await ElMessageBox.confirm(`确认删除用户「${targetName}」？此操作无法撤销。`, '删除用户', {
      type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消'
    })
  } catch {
    deleteActionId.value = null
    return
  }
  try {
    await deleteUser(targetId)
    ElMessage.success('用户已删除')
    if (users.value.length === 1 && query.page > 1) query.page--
    await load()
  } finally {
    deleteActionId.value = null
  }
}

const onResize = () => { viewportWidth.value = window.innerWidth }
onMounted(() => {
  window.addEventListener('resize', onResize)
  loadGroups()
  load()
})
onBeforeUnmount(() => {
  listRequestSeq++
  groupRequestSeq++
  window.removeEventListener('resize', onResize)
})
</script>

<style scoped>
.user-page { min-width: 0; }
.user-head h2 { margin-top: 3px; }
.eyebrow { display: block; color: var(--color-text-muted); font-size: 11px; font-weight: 600; letter-spacing: .08em; }
.head-actions { display: flex; gap: 8px; }
.filter-section { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; }
.groups-error { margin-bottom: 12px; }
.keyword-input { width: min(320px, 40vw); }
.role-filter { width: 150px; }
.center-state { min-height: 380px; display: grid; place-items: center; }
.loading-section { display: grid; gap: 15px; }
.user-skeleton + .user-skeleton { padding-top: 15px; border-top: 1px solid var(--color-border); }
.list-summary { display: flex; justify-content: space-between; gap: 12px; padding-bottom: 10px; color: var(--color-text-muted); font-size: 12px; border-bottom: 1px solid var(--color-border-strong); }
.user-list { min-width: 0; }
.user-list-head, .user-row { display: grid; grid-template-columns: minmax(210px, 1.15fr) 110px minmax(140px, .8fr) minmax(320px, auto); gap: 16px; align-items: center; }
.user-list-head { min-height: 38px; color: var(--color-text-muted); font-size: 12px; }
.user-row { min-height: 70px; padding: 10px 0; border-top: 1px solid var(--color-border); }
.user-identity { display: flex; align-items: center; gap: 10px; min-width: 0; }
.user-avatar { width: 34px; height: 34px; flex: 0 0 auto; display: grid; place-items: center; color: #1d4ed8; font-size: 13px; font-weight: 600; background: #eff6ff; border: 1px solid #bfdbfe; border-radius: 50%; }
.user-identity div, .user-group { min-width: 0; }
.user-identity strong, .user-identity span { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.user-identity strong { color: var(--color-text); font-size: 14px; }
.user-identity span { margin-top: 3px; color: var(--color-text-muted); font-size: 12px; }
.user-group { display: flex; align-items: center; gap: 6px; color: var(--color-text-secondary); }
.user-group span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.user-actions { display: flex; justify-content: flex-end; gap: 6px; }
.user-pagination { justify-content: flex-end; margin-top: 16px; padding-top: 14px; border-top: 1px solid var(--color-border); }
.reset-note { margin: 0 0 12px; color: var(--color-text-secondary); line-height: 1.6; }

@media (max-width: 1040px) {
  .user-list-head { display: none; }
  .user-row { grid-template-columns: minmax(0, 1fr) auto; gap: 10px 14px; align-items: start; }
  .user-group { align-self: center; }
  .user-actions { grid-column: 1 / -1; justify-content: flex-start; flex-wrap: wrap; }
}

@media (max-width: 640px) {
  .user-head { align-items: flex-start; }
  .head-actions { justify-content: flex-end; flex-wrap: wrap; }
  .filter-section { align-items: stretch; flex-wrap: wrap; padding: 12px; }
  .keyword-input { width: 100%; }
  .role-filter { flex: 1; min-width: 130px; }
  .user-section { padding: 12px; }
  .user-row { grid-template-columns: minmax(0, 1fr) auto; }
  .user-identity strong, .user-identity span, .user-group span {
    display: -webkit-box;
    overflow: hidden;
    white-space: normal;
    overflow-wrap: anywhere;
    word-break: break-word;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 2;
  }
  .user-role { align-self: center; }
  .user-group { grid-column: 1 / -1; }
  .user-actions { gap: 6px; }
  .user-pagination { justify-content: center; overflow: hidden; }
  .user-pagination :deep(.el-pagination__total) { display: none; }
}

@media (max-width: 390px) {
  .head-actions .el-button:first-child { display: none; }
  .user-actions .el-button { margin-left: 0; }
}
</style>
