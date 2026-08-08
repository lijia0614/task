<template>
  <div class="section">
    <div class="page-head"><h2>创建任务</h2></div>

    <el-steps :active="step" align-center class="create-steps">
      <el-step title="基本信息" />
      <el-step title="分配对象" />
      <el-step title="附件" />
    </el-steps>

    <!-- 第一步：基本信息 -->
    <el-form v-show="step === 0" ref="form1Ref" :model="form1" :rules="form1Rules"
             label-width="80px" class="step-body">
      <el-form-item label="任务名称" prop="name">
        <el-input v-model="form1.name" maxlength="100" placeholder="请输入任务名称（必填）" />
      </el-form-item>
      <el-form-item label="任务简介" prop="description">
        <el-input v-model="form1.description" type="textarea" :rows="4" placeholder="任务简介（选填）" />
      </el-form-item>
      <el-form-item label="完成时间" prop="deadline">
        <el-date-picker v-model="form1.deadline" type="datetime" placeholder="选择完成时间（选填）"
                        value-format="YYYY-MM-DDTHH:mm:ss" />
      </el-form-item>
    </el-form>

    <!-- 第二步：分配对象 -->
    <div v-show="step === 1" class="step-body">
      <el-form label-width="80px">
        <el-form-item label="分配方式">
          <el-radio-group v-model="assignType" @change="onAssignTypeChange">
            <el-radio value="INDIVIDUAL">分配给个人</el-radio>
            <el-radio value="GROUP">分配给小组</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="分配对象">
          <el-select v-if="assignType === 'INDIVIDUAL'" v-model="assigneeId" class="assign-select"
                     placeholder="选择员工" filterable>
            <el-option v-for="u in userCandidates" :key="u.id"
                       :label="`${u.realName}（${u.username}）`" :value="u.id" />
          </el-select>
          <el-select v-else v-model="assigneeId" class="assign-select" placeholder="选择小组" filterable
                     @change="onGroupChange">
            <el-option v-for="g in groups" :key="g.id"
                       :label="`${g.name}（${g.memberCount}人）`" :value="g.id" />
          </el-select>
        </el-form-item>
      </el-form>

      <template v-if="assignType === 'GROUP' && groupMemberList.length">
        <div class="weight-block">
          <div class="weight-head">
            <span class="weight-title">成员权重（默认均分，每项 1-100，合计必须为 100）</span>
            <el-tag :type="weightSum === 100 ? 'success' : 'danger'" size="small" effect="plain">
              合计 {{ weightSum }}%
            </el-tag>
          </div>
          <div v-for="m in groupMemberList" :key="m.id" class="weight-row">
            <span class="weight-name" :title="m.realName">{{ m.realName }}</span>
            <el-input-number v-model="weights[m.id]" :min="1" :max="100" :step="1" size="small"
                             class="weight-input" controls-position="right" />
            <span class="weight-unit">%</span>
          </div>
        </div>
      </template>
    </div>

    <!-- 第三步：附件 -->
    <div v-show="step === 2" class="step-body">
      <el-upload multiple :auto-upload="true" :http-request="doUpload"
                 v-model:file-list="fileList" :before-remove="beforeFileRemove" class="attach-upload">
        <el-button :icon="Upload" :loading="uploadingCount > 0">选择附件</el-button>
        <template #tip>
          <div class="el-upload__tip">
            支持多选，上传完成自动加入任务附件{{ uploadingCount > 0 ? `（${uploadingCount} 个上传中）` : '' }}
          </div>
        </template>
      </el-upload>
    </div>

    <!-- 操作栏 -->
    <div class="step-actions">
      <el-button v-if="step > 0" @click="step--">上一步</el-button>
      <div class="action-right">
        <el-button @click="cancel" :disabled="uploadingCount > 0 || hasFailedCleanup">取消</el-button>
        <el-button v-if="step < 2" type="primary" @click="next">下一步</el-button>
        <el-button v-else type="primary" :loading="submitting"
                   :disabled="uploadingCount > 0 || hasFailedCleanup" @click="submit">
          {{ uploadingCount > 0 ? `附件上传中（${uploadingCount}）`
             : hasFailedCleanup ? '附件清理失败，请重试移除' : '创建任务' }}
        </el-button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Upload } from '@element-plus/icons-vue'
import { listUserCandidates } from '../api/user'
import { listGroups, groupMembers } from '../api/group'
import { uploadFile, deleteFile } from '../api/file'
import { createTask } from '../api/task'

const router = useRouter()
const step = ref(0)
const submitting = ref(false)

/* ---------- 第一步：基本信息 ---------- */
const form1Ref = ref(null)
const form1 = reactive({ name: '', description: '', deadline: null })
const form1Rules = {
  name: [
    { required: true, message: '请输入任务名称', trigger: 'blur' },
    { max: 100, message: '任务名称不能超过 100 字', trigger: 'blur' },
    { validator: (_r, v, cb) => (v && v.trim() ? cb() : cb(new Error('任务名称不能为空'))), trigger: 'blur' }
  ]
}

/* ---------- 第二步：分配对象 ---------- */
const assignType = ref('INDIVIDUAL')
const assigneeId = ref(null)
const userCandidates = ref([])
const groups = ref([])
const groupMemberList = ref([])
const weights = reactive({})

/** 小组成员加载防竞态：只接受最新一次切换的响应；加载中禁止进入下一步 */
let memberReqSeq = 0
const membersLoading = ref(false)

const weightSum = computed(() => Object.values(weights).reduce((a, b) => a + (b || 0), 0))
const weightsValid = computed(() => {
  if (!groupMemberList.value.length) return false
  if (weightSum.value !== 100) return false
  return groupMemberList.value.every((m) => {
    const w = weights[m.id]
    return typeof w === 'number' && w >= 1 && w <= 100
  })
})

/** 切换分配类型：先使在途的成员请求失效，再清空旧选择，避免把用户 id 当组 id */
const onAssignTypeChange = () => {
  memberReqSeq++
  assigneeId.value = null
  groupMemberList.value = []
  Object.keys(weights).forEach((k) => delete weights[k])
  membersLoading.value = false
}

/** 选择小组：按后端返回顺序（id 升序）加载成员并默认均分；防竞态 + 加载中禁止下一步 */
const onGroupChange = async (groupId) => {
  const seq = ++memberReqSeq
  membersLoading.value = true
  Object.keys(weights).forEach((k) => delete weights[k])
  groupMemberList.value = []
  if (!groupId) {
    if (seq === memberReqSeq) membersLoading.value = false
    return
  }
  try {
    const members = await groupMembers(groupId)
    if (seq !== memberReqSeq) return // 过期响应丢弃
    groupMemberList.value = members
    if (!members.length) {
      ElMessage.warning('该小组没有成员，无法分配任务')
      return
    }
    const n = members.length
    const base = Math.floor(100 / n)
    const rest = 100 % n
    members.forEach((m, i) => { weights[m.id] = base + (i < rest ? 1 : 0) })
  } catch (e) {
    if (seq !== memberReqSeq) return // 过期失败也丢弃；错误提示由拦截器统一处理
  } finally {
    if (seq === memberReqSeq) membersLoading.value = false
  }
}

/* ---------- 第三步：附件 ---------- */
const fileList = ref([])
const attachmentIds = ref([])
const uploadingCount = ref(0)
/** 清理失败的服务器文件 id 集合：不依赖 Element Plus 重新入列后可能变化的 uid */
const failedCleanup = reactive(new Set())
const hasFailedCleanup = computed(() => failedCleanup.size > 0)

/** 上传生命周期：uid → 'uploading' | 'done' | 'removed'；同一 uid 只处理一次 */
const uploadStatus = new Map()
/** Element Plus 的自定义上传项不保证保留 response，单独保存稳定的服务器文件 id */
const serverFileIds = new Map()

const doUpload = async ({ file, onSuccess, onError }) => {
  if (uploadStatus.has(file.uid)) return // 同一 uid 不重复计数/不重复 POST
  uploadStatus.set(file.uid, 'uploading')
  uploadingCount.value++
  try {
    const data = await uploadFile(file)
    serverFileIds.set(file.uid, data.id)
    if (uploadStatus.get(file.uid) === 'removed') {
      // 上传期间被用户移除：晚到的成功结果作废，不加入附件；立即删除服务器上的临时文件
      if (data?.id) {
        try {
          await deleteFile(data.id)
          failedCleanup.delete(data.id)
          serverFileIds.delete(file.uid)
        } catch {
          // 清理失败必须可见且可重试：恢复为 done 状态并放回列表，用户可再次移除重试删除；
          // 清理成功前禁止创建任务/取消（hasFailedCleanup 禁用按钮）
          uploadStatus.set(file.uid, 'done')
          fileList.value.push({
            name: file.name,
            size: file.size,
            uid: file.uid,
            status: 'success',
            response: data
          })
          failedCleanup.add(data.id)
          ElMessage.error('附件删除失败，请重试')
        }
      }
      return
    }
    uploadStatus.set(file.uid, 'done')
    attachmentIds.value.push(data.id)
    onSuccess(data)
  } catch (e) {
    uploadStatus.delete(file.uid) // 失败后可重新选择上传
    serverFileIds.delete(file.uid)
    onError(e)
  } finally {
    uploadingCount.value--
  }
}

const beforeFileRemove = async (file) => {
  const st = uploadStatus.get(file.uid)
  if (st === 'uploading') {
    // 上传中移除：标记作废，晚到的成功结果不得加入附件（计数由 doUpload finally 递减）
    uploadStatus.set(file.uid, 'removed')
    return true
  }
  const fileId = file.response?.id ?? serverFileIds.get(file.uid)
  if (fileId) {
    // 删除服务器记录成功后才允许 Element Plus 移除列表项；失败则原项留在列表中重试
    try {
      await deleteFile(fileId)
      attachmentIds.value = attachmentIds.value.filter((id) => id !== fileId)
      failedCleanup.delete(fileId)
      uploadStatus.delete(file.uid)
      serverFileIds.delete(file.uid)
      return true
    } catch (e) {
      ElMessage.error('附件删除失败，请重试')
      failedCleanup.add(fileId)
      return false
    }
  }
  uploadStatus.delete(file.uid)
  serverFileIds.delete(file.uid)
  return true
}

/** 取消：先删除本页已上传但尚未创建任务的临时附件，失败不离开页面；上传中/清理失败中禁止取消避免孤儿 */
const cancel = async () => {
  if (submitting.value) return
  if (uploadingCount.value > 0) return ElMessage.warning('附件上传中，请稍候')
  if (failedCleanup.size > 0) return ElMessage.warning('附件清理未完成，请先重试移除')
  const ids = attachmentIds.value.slice()
  if (ids.length) {
    // 后端 delete 非幂等（文件不存在→400）：只保留失败的 id 待重试，已删除的从列表移除
    const results = await Promise.allSettled(ids.map((id) => deleteFile(id)))
    const failedIds = ids.filter((_, i) => results[i].status === 'rejected')
    attachmentIds.value = attachmentIds.value.filter((id) => failedIds.includes(id))
    if (failedIds.length) {
      ElMessage.error('部分临时附件删除失败，请重试后再取消')
      return
    }
  }
  router.push('/tasks')
}

/* ---------- 步骤流转 ---------- */
const next = async () => {
  if (step.value === 0) {
    const ok = await form1Ref.value.validate().catch(() => false)
    if (!ok) return
    step.value++
  } else if (step.value === 1) {
    if (membersLoading.value) return ElMessage.warning('成员加载中，请稍候')
    if (!assigneeId.value) return ElMessage.warning('请选择分配对象')
    if (assignType.value === 'GROUP') {
      if (!groupMemberList.value.length) return ElMessage.warning('该小组没有成员，无法分配')
      if (!weightsValid.value) return ElMessage.warning('权重不合法：每项 1-100 且合计必须为 100')
    }
    step.value++
  }
}

const submit = async () => {
  if (submitting.value) return
  if (uploadingCount.value > 0) return ElMessage.warning('附件上传中，请稍候')
  if (failedCleanup.size > 0) return ElMessage.warning('存在清理失败的附件，请先重试移除')
  const payload = {
    name: form1.name.trim(),
    description: form1.description,
    deadline: form1.deadline,
    assignType: assignType.value,
    assigneeId: assigneeId.value,
    weights: assignType.value === 'GROUP' ? groupMemberList.value.map((m) => weights[m.id]) : null,
    attachmentIds: attachmentIds.value
  }
  submitting.value = true
  try {
    await createTask(payload)
    ElMessage.success('创建成功')
    router.push('/tasks')
  } catch (e) {
    // 创建失败保留表单，不跳转；错误提示由 request 拦截器统一处理
  } finally {
    submitting.value = false
  }
}

onMounted(async () => {
  userCandidates.value = await listUserCandidates()
  groups.value = await listGroups()
})
</script>

<style scoped>
.create-steps {
  margin: 8px 0 24px;
}

.step-body {
  max-width: 640px;
}

.assign-select {
  width: 300px;
}

.weight-block {
  margin-top: 16px;
  border-top: 1px solid var(--color-border);
  padding-top: 12px;
}

.weight-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.weight-title {
  font-size: 13px;
  color: var(--color-text-secondary);
}

.weight-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 0;
}

.weight-name {
  width: 140px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
  color: var(--color-text);
}

.weight-input {
  width: 110px;
}

.weight-unit {
  font-size: 13px;
  color: var(--color-text-muted);
}

.attach-upload {
  max-width: 640px;
}

.step-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 24px;
  padding-top: 16px;
  border-top: 1px solid var(--color-border);
}

.action-right {
  display: flex;
  gap: 8px;
}

@media (max-width: 767px) {
  .assign-select {
    width: 100%;
  }

  .weight-row {
    display: block;
    position: relative;
  }

  .weight-name {
    display: block;
    width: auto;
    margin-bottom: 4px;
  }

  .weight-input {
    display: block;
    width: 100%;
  }

  .weight-unit {
    position: absolute;
    right: 8px;
    bottom: 8px;
  }

  .step-body {
    max-width: none;
  }
}
</style>
