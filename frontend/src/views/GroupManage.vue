<template>
  <div class="group-page">
    <div class="page-head group-head">
      <div>
        <span class="eyebrow">组织协作</span>
        <h2>小组管理</h2>
      </div>
      <div class="head-actions">
        <el-button :icon="Refresh" :loading="loading" @click="load()">刷新</el-button>
        <el-button v-if="auth.canCreateTask" type="primary" :icon="Plus" @click="openCreate">
          新建小组
        </el-button>
      </div>
    </div>

    <div v-if="error" class="section center-state">
      <el-result icon="error" title="加载失败" :sub-title="error">
        <template #extra>
          <el-button type="primary" :icon="Refresh" @click="load()">重试</el-button>
        </template>
      </el-result>
    </div>

    <div v-else-if="loading" class="section loading-section">
      <div v-for="item in 4" :key="item" class="group-skeleton">
        <el-skeleton :rows="2" animated />
      </div>
    </div>

    <div v-else-if="!groups.length" class="section center-state">
      <el-empty description="暂无小组" :image-size="76" />
    </div>

    <section v-else class="section group-section">
      <div class="group-summary">
        <span>共 {{ groups.length }} 个小组</span>
        <span>{{ auth.isAdmin ? '全部小组可管理' : auth.isLeader ? '本人负责的小组可管理' : '只读查看' }}</span>
      </div>
      <div class="group-list" role="table" aria-label="小组列表">
        <div class="group-list-head" role="row">
          <span role="columnheader">小组</span>
          <span role="columnheader">组长</span>
          <span role="columnheader">成员</span>
          <span role="columnheader">操作</span>
        </div>
        <article v-for="group in groups" :key="group.id" class="group-row" role="row"
                 :data-group-id="group.id">
          <div class="group-main" role="cell">
            <strong :title="group.name">{{ group.name }}</strong>
            <p :title="group.description || ''">{{ group.description || '暂无简介' }}</p>
          </div>
          <div class="group-meta" role="cell">
            <el-icon><User /></el-icon>
            <span :title="group.leaderName || ''">{{ group.leaderName || '未指定' }}</span>
          </div>
          <div class="group-meta" role="cell">
            <el-icon><UserFilled /></el-icon>
            <span>{{ Number(group.memberCount) || 0 }} 人</span>
          </div>
          <div class="group-actions" role="cell">
            <el-button size="small" :icon="UserFilled" @click="openMembers(group)">成员</el-button>
            <el-button v-if="canManage(group)" size="small" :icon="Edit" @click="openEdit(group)">
              编辑
            </el-button>
            <el-button v-if="canManage(group)" size="small" type="danger" plain :icon="Delete"
                       :loading="groupActionId === group.id" @click="removeGroup(group)">
              删除
            </el-button>
          </div>
        </article>
      </div>
    </section>

    <el-dialog v-model="memberVisible" :title="memberDialogTitle" :width="memberDialogWidth"
               destroy-on-close :close-on-click-modal="!memberSubmitting"
               :close-on-press-escape="!memberSubmitting" :show-close="!memberSubmitting"
               :before-close="beforeMemberClose" @closed="onMemberClosed">
      <div class="member-dialog-body">
        <div v-if="memberError" class="dialog-state">
          <el-result icon="error" title="成员加载失败" :sub-title="memberError">
            <template #extra>
              <el-button type="primary" :icon="Refresh" @click="loadMembers()">重试</el-button>
            </template>
          </el-result>
        </div>
        <div v-else-if="memberLoading" class="member-loading">
          <el-skeleton v-for="item in 3" :key="item" :rows="1" animated />
        </div>
        <el-empty v-else-if="!members.length" description="暂无成员" :image-size="68" />
        <div v-else class="member-list">
          <div v-for="member in members" :key="member.id" class="member-row" :data-user-id="member.id">
            <div class="member-identity">
              <span class="member-avatar">{{ memberInitial(member) }}</span>
              <div>
                <strong>{{ member.realName || '未命名用户' }}</strong>
                <span>{{ member.username || '—' }}</span>
              </div>
            </div>
            <div class="member-side">
              <el-tag v-if="member.id === currentGroup?.leaderId" size="small" type="warning" effect="plain">
                组长
              </el-tag>
              <el-button v-if="canRemoveMember(member)" size="small" type="danger" text :icon="Close"
                         :loading="memberActionId === member.id" @click="removeGroupMember(member)">
                移除
              </el-button>
            </div>
          </div>
        </div>

        <div v-if="canManage(currentGroup)" class="member-add-row">
          <el-select v-model="newMemberId" class="member-candidate" filterable clearable
                     placeholder="选择未分组用户" :disabled="memberSubmitting">
            <el-option v-for="user in memberCandidates" :key="user.id"
                       :label="`${user.realName || user.username}（${user.username}）`" :value="user.id" />
          </el-select>
          <el-button type="primary" :icon="Plus" :loading="memberActionId === 'add'"
                     :disabled="!newMemberId || memberSubmitting" @click="addGroupMember">
            添加
          </el-button>
        </div>
      </div>
    </el-dialog>

    <el-dialog v-model="formVisible" :title="formDialogTitle" :width="formDialogWidth"
               destroy-on-close :close-on-click-modal="!formSubmitting"
               :close-on-press-escape="!formSubmitting" :show-close="!formSubmitting"
               :before-close="beforeFormClose">
      <el-form label-width="74px" @submit.prevent>
        <el-form-item label="组名" required>
          <el-input v-model="groupForm.name" maxlength="100" show-word-limit placeholder="输入小组名称" />
        </el-form-item>
        <el-form-item label="组长" required>
          <el-select v-model="groupForm.leaderId" class="leader-select" filterable placeholder="选择组长"
                     :disabled="!auth.isAdmin" style="width: 100%">
            <el-option v-for="user in leaderCandidates" :key="user.id"
                       :label="`${user.realName || user.username}（${roleText(user.role)}）`" :value="user.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="简介">
          <el-input v-model="groupForm.description" type="textarea" :rows="3" maxlength="500"
                    show-word-limit placeholder="可选" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="formSubmitting" @click="formVisible = false">取消</el-button>
        <el-button type="primary" :icon="formMode === 'create' ? Plus : Check" :loading="formSubmitting"
                   @click="saveGroup">
          {{ formMode === 'create' ? '创建' : '保存' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Check, Close, Delete, Edit, Plus, Refresh, User, UserFilled } from '@element-plus/icons-vue'
import {
  addMember,
  createGroup,
  deleteGroup,
  groupMembers,
  listGroups,
  removeMember,
  updateGroup
} from '../api/group'
import { listUsers } from '../api/user'
import { useAuthStore } from '../store/auth'

const auth = useAuthStore()
const groups = ref([])
const users = ref([])
const loading = ref(false)
const error = ref('')
const currentGroup = ref(null)
const memberVisible = ref(false)
const members = ref([])
const memberLoading = ref(false)
const memberError = ref('')
const memberActionId = ref(null)
const newMemberId = ref(null)
const groupActionId = ref(null)
const formVisible = ref(false)
const formMode = ref('create')
const formSubmitting = ref(false)
const viewportWidth = ref(window.innerWidth)
const groupForm = reactive({ name: '', leaderId: null, description: '' })
let loadRequestSeq = 0
let memberRequestSeq = 0

const formDialogTitle = computed(() => formMode.value === 'create' ? '新建小组' : '编辑小组')
const memberDialogTitle = computed(() => `${currentGroup.value?.name || '小组'} · 成员`)
const formDialogWidth = computed(() => `${Math.min(500, viewportWidth.value - 24)}px`)
const memberDialogWidth = computed(() => `${Math.min(620, viewportWidth.value - 24)}px`)
const memberSubmitting = computed(() => memberActionId.value !== null)
const leaderCandidates = computed(() => users.value.filter(user => user.role === 'LEADER' || user.role === 'ADMIN'))
const memberCandidates = computed(() => users.value.filter(user => user.groupId == null))

const roleText = role => ({ ADMIN: '管理员', LEADER: '组长', EMPLOYEE: '员工' }[role] || role || '未知')
const memberInitial = member => String(member.realName || member.username || '?').slice(0, 1)
const canManage = group => !!group && (auth.isAdmin || (auth.isLeader && group.leaderId === auth.user?.id))
const canRemoveMember = member => canManage(currentGroup.value) && member.id !== currentGroup.value?.leaderId
const beforeMemberClose = done => { if (!memberSubmitting.value) done() }
const beforeFormClose = done => { if (!formSubmitting.value) done() }

const load = async ({ silent = false } = {}) => {
  const seq = ++loadRequestSeq
  if (!silent) loading.value = true
  error.value = ''
  try {
    const [groupData, userData] = await Promise.all([
      listGroups(),
      auth.canCreateTask ? listUsers({ page: 1, size: 1000 }) : Promise.resolve(null)
    ])
    if (seq !== loadRequestSeq) return
    groups.value = Array.isArray(groupData) ? groupData : []
    users.value = auth.canCreateTask && Array.isArray(userData?.records) ? userData.records : []
    if (currentGroup.value) {
      const freshGroup = groups.value.find(group => group.id === currentGroup.value.id)
      if (freshGroup) currentGroup.value = freshGroup
    }
  } catch (e) {
    if (seq !== loadRequestSeq) return
    groups.value = []
    users.value = []
    error.value = e.message || '网络错误'
  } finally {
    if (seq === loadRequestSeq) loading.value = false
  }
}

const loadMembers = async (groupId = currentGroup.value?.id) => {
  if (!groupId) return
  const seq = ++memberRequestSeq
  memberLoading.value = true
  memberError.value = ''
  try {
    const data = await groupMembers(groupId)
    if (seq !== memberRequestSeq || currentGroup.value?.id !== groupId) return
    members.value = Array.isArray(data) ? data : []
  } catch (e) {
    if (seq !== memberRequestSeq || currentGroup.value?.id !== groupId) return
    members.value = []
    memberError.value = e.message || '网络错误'
  } finally {
    if (seq === memberRequestSeq && currentGroup.value?.id === groupId) memberLoading.value = false
  }
}

const openMembers = group => {
  currentGroup.value = group
  members.value = []
  newMemberId.value = null
  memberVisible.value = true
  loadMembers(group.id)
}

const onMemberClosed = () => {
  memberRequestSeq++
  memberLoading.value = false
  memberError.value = ''
  memberActionId.value = null
  newMemberId.value = null
  currentGroup.value = null
}

const refreshCurrentGroup = async groupId => {
  await load({ silent: true })
  if (memberVisible.value && currentGroup.value?.id === groupId) await loadMembers(groupId)
}

const addGroupMember = async () => {
  if (!newMemberId.value || !currentGroup.value || memberSubmitting.value) return
  const groupId = currentGroup.value.id
  const userId = newMemberId.value
  memberActionId.value = 'add'
  try {
    await addMember(groupId, userId)
    if (currentGroup.value?.id === groupId) newMemberId.value = null
    ElMessage.success('成员已添加')
    await refreshCurrentGroup(groupId)
  } finally {
    memberActionId.value = null
  }
}

const removeGroupMember = async member => {
  if (!canRemoveMember(member) || memberSubmitting.value) return
  const groupId = currentGroup.value.id
  memberActionId.value = member.id
  try {
    await ElMessageBox.confirm(`确认将「${member.realName || member.username}」移出小组？`, '移除成员', {
      type: 'warning', confirmButtonText: '确认移除', cancelButtonText: '取消'
    })
  } catch {
    memberActionId.value = null
    return
  }
  try {
    await removeMember(groupId, member.id)
    ElMessage.success('成员已移除')
    await refreshCurrentGroup(groupId)
  } finally {
    memberActionId.value = null
  }
}

const resetForm = () => {
  groupForm.name = ''
  groupForm.leaderId = null
  groupForm.description = ''
}

const openCreate = () => {
  formMode.value = 'create'
  currentGroup.value = null
  resetForm()
  if (auth.isLeader) groupForm.leaderId = auth.user?.id || null
  formVisible.value = true
}

const openEdit = group => {
  if (!canManage(group)) return
  formMode.value = 'edit'
  currentGroup.value = group
  groupForm.name = group.name || ''
  groupForm.leaderId = group.leaderId
  groupForm.description = group.description || ''
  formVisible.value = true
}

const saveGroup = async () => {
  if (formSubmitting.value) return
  const mode = formMode.value
  const groupId = currentGroup.value?.id
  const name = groupForm.name.trim()
  const leaderId = auth.isAdmin
    ? groupForm.leaderId
    : mode === 'edit' ? currentGroup.value?.leaderId : auth.user?.id
  if (!name || !leaderId) return ElMessage.warning('请填写组名和组长')
  if (mode === 'edit' && !groupId) return ElMessage.warning('小组状态已变化，请刷新')
  const payload = { name, leaderId, description: groupForm.description.trim() }
  formSubmitting.value = true
  try {
    if (mode === 'create') {
      await createGroup(payload)
      ElMessage.success('小组已创建')
    } else {
      await updateGroup(groupId, payload)
      ElMessage.success('小组已更新')
    }
    formVisible.value = false
    resetForm()
    await load()
  } finally {
    formSubmitting.value = false
  }
}

const removeGroup = async group => {
  if (!canManage(group) || groupActionId.value !== null) return
  groupActionId.value = group.id
  try {
    await ElMessageBox.confirm(`确认删除小组「${group.name}」？`, '删除小组', {
      type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消'
    })
  } catch {
    groupActionId.value = null
    return
  }
  try {
    await deleteGroup(group.id)
    ElMessage.success('小组已删除')
    await load()
  } finally {
    groupActionId.value = null
  }
}

const onResize = () => { viewportWidth.value = window.innerWidth }
onMounted(() => {
  window.addEventListener('resize', onResize)
  load()
})
onBeforeUnmount(() => {
  loadRequestSeq++
  memberRequestSeq++
  window.removeEventListener('resize', onResize)
})
</script>

<style scoped>
.group-page { min-width: 0; }
.group-head { margin-bottom: 12px; }
.group-head h2 { margin-top: 3px; }
.eyebrow { display: block; color: var(--color-text-muted); font-size: 11px; font-weight: 600; letter-spacing: .08em; }
.head-actions { display: flex; gap: 8px; }
.center-state { min-height: 400px; display: grid; place-items: center; }
.loading-section { display: grid; gap: 16px; }
.group-skeleton + .group-skeleton { padding-top: 16px; border-top: 1px solid var(--color-border); }
.group-summary { display: flex; justify-content: space-between; gap: 12px; padding-bottom: 10px; color: var(--color-text-muted); font-size: 12px; border-bottom: 1px solid var(--color-border-strong); }
.group-list { min-width: 0; }
.group-list-head, .group-row { display: grid; grid-template-columns: minmax(220px, 1.5fr) minmax(130px, .7fr) 90px minmax(260px, auto); gap: 16px; align-items: center; }
.group-list-head { min-height: 38px; color: var(--color-text-muted); font-size: 12px; }
.group-row { min-height: 76px; padding: 12px 0; border-top: 1px solid var(--color-border); }
.group-main, .group-meta { min-width: 0; }
.group-main strong { display: block; overflow: hidden; color: var(--color-text); font-size: 14px; text-overflow: ellipsis; white-space: nowrap; }
.group-main p { margin: 5px 0 0; overflow: hidden; color: var(--color-text-secondary); font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.group-meta { display: flex; align-items: center; gap: 6px; color: var(--color-text-secondary); }
.group-meta span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.group-actions { display: flex; justify-content: flex-end; gap: 6px; }
.member-dialog-body { min-height: 220px; }
.dialog-state, .member-loading { min-height: 220px; display: grid; align-content: center; gap: 16px; }
.member-list { border-top: 1px solid var(--color-border); }
.member-row { min-height: 58px; display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 9px 0; border-bottom: 1px solid var(--color-border); }
.member-identity { display: flex; align-items: center; gap: 10px; min-width: 0; }
.member-avatar { width: 32px; height: 32px; flex: 0 0 auto; display: grid; place-items: center; color: var(--color-primary); font-size: 13px; font-weight: 600; background: #eff6ff; border: 1px solid #bfdbfe; border-radius: 50%; }
.member-identity div { min-width: 0; }
.member-identity strong, .member-identity span { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.member-identity span { margin-top: 2px; color: var(--color-text-muted); font-size: 12px; }
.member-side { display: flex; align-items: center; gap: 6px; flex: 0 0 auto; }
.member-add-row { display: flex; gap: 8px; margin-top: 16px; }
.member-candidate { flex: 1; min-width: 0; }
@media (max-width: 900px) {
  .group-list-head { display: none; }
  .group-row { grid-template-columns: minmax(0, 1fr) auto; gap: 10px; align-items: start; }
  .group-main { grid-column: 1 / -1; }
  .group-main strong, .group-main p { white-space: normal; overflow-wrap: anywhere; word-break: break-word; }
  .group-meta { align-self: center; }
  .group-actions { grid-column: 1 / -1; justify-content: flex-start; flex-wrap: wrap; }
}
@media (max-width: 520px) {
  .group-head { align-items: flex-start; }
  .head-actions { flex-wrap: wrap; justify-content: flex-end; }
  .group-section { padding: 12px; }
  .group-summary span:last-child { display: none; }
  .group-row { grid-template-columns: 1fr 1fr; }
  .member-add-row { align-items: stretch; flex-direction: column; }
  .member-side { align-items: flex-end; flex-direction: column; }
}
</style>
