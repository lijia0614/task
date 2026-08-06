<template>
  <div class="detail-page">
    <div v-if="error" class="section center-state">
      <el-result icon="error" title="任务加载失败" :sub-title="error">
        <template #extra>
          <el-button type="primary" :icon="Refresh" @click="load">重试</el-button>
        </template>
      </el-result>
    </div>

    <template v-else-if="loading">
      <div class="section loading-section">
        <el-skeleton :rows="5" animated />
      </div>
      <div class="section loading-section"><el-skeleton :rows="4" animated /></div>
    </template>

    <template v-else-if="task">
      <div class="detail-toolbar">
        <el-button text :icon="ArrowLeft" @click="backToTasks">返回任务列表</el-button>
        <el-tag :type="task.status === 'DONE' ? 'success' : 'primary'" effect="plain">
          {{ taskStatusLabel(task.status) }}
        </el-tag>
      </div>

      <section class="section overview-section">
        <div class="page-head">
          <div>
            <div class="eyebrow">任务详情 · #{{ task.id }}</div>
            <h2>{{ task.name }}</h2>
          </div>
          <div class="overview-progress">
            <strong>{{ safeProgress(task.progress) }}%</strong>
            <span>整体进度</span>
          </div>
        </div>
        <p class="task-description">{{ task.description || '暂无任务简介' }}</p>
        <div class="task-meta-grid">
          <div><span>分配者</span><strong>{{ task.creatorName || '—' }}</strong></div>
          <div><span>分配对象</span><strong>{{ task.assignType === 'GROUP' ? '小组' : '个人' }} · {{ task.assigneeName || '—' }}</strong></div>
          <div><span>截止时间</span><strong>{{ formatDate(task.deadline) }}</strong></div>
          <div><span>完成时间</span><strong>{{ formatDate(task.doneAt) }}</strong></div>
        </div>
        <el-progress :percentage="safeProgress(task.progress)" :stroke-width="8"
                     :color="task.status === 'DONE' ? 'var(--color-success)' : 'var(--color-primary)'"
                     :show-text="false" />
      </section>

      <section class="section">
        <div class="section-heading">
          <div><span class="eyebrow">执行情况</span><h3>成员进度</h3></div>
          <span class="section-count">{{ task.members?.length || 0 }} 人</span>
        </div>
        <div v-if="task.members?.length" class="member-list">
          <div v-for="member in task.members" :key="member.id" class="member-row">
            <div class="member-identity">
              <span class="member-mark"><el-icon><UserFilled /></el-icon></span>
              <div><strong>{{ member.realName || '未知成员' }}</strong><small v-if="task.assignType === 'GROUP'">权重 {{ member.weight }}%</small></div>
            </div>
            <div class="member-progress">
              <el-progress :percentage="safeProgress(member.progress)" :stroke-width="7" :show-text="false" />
              <strong>{{ safeProgress(member.progress) }}%</strong>
            </div>
          </div>
        </div>
        <el-empty v-else description="暂无成员" :image-size="56" />
      </section>

      <section class="section attachment-section">
        <div class="section-heading">
          <div><span class="eyebrow">资料</span><h3>附件</h3></div>
          <span class="section-count">{{ task.attachments?.length || 0 }} 个</span>
        </div>
        <div v-if="task.attachments?.length" class="attachment-list">
          <a v-for="attachment in task.attachments" :key="attachment.id" class="attachment-row"
             :href="attachment.fileUrl" target="_blank" rel="noopener">
            <el-icon><Paperclip /></el-icon>
            <span class="attachment-name">{{ attachment.fileName }}</span>
            <span class="attachment-size">{{ formatSize(attachment.fileSize) }}</span>
          </a>
        </div>
        <el-empty v-else description="暂无附件" :image-size="56" />
      </section>

      <section class="section reports-section">
        <div class="section-heading">
          <div><span class="eyebrow">进展记录</span><h3>汇报记录</h3></div>
          <span class="section-count">{{ reports.length }} 条</span>
        </div>
        <el-empty v-if="!reports.length" description="暂无可见汇报" :image-size="56" />
        <div v-else class="report-list">
          <article v-for="report in reports" :key="report.id" class="report-item"
                   :class="`report-status-${String(report.status).toLowerCase()}`" :data-report-id="report.id">
            <div class="report-head">
              <div class="report-author">
                <span class="report-dot" :class="`dot-${String(report.status).toLowerCase()}`" />
                <strong>{{ report.userName || '未知用户' }}</strong>
                <span class="report-time">{{ formatTime(report.createdAt) }}</span>
              </div>
              <el-tag size="small" effect="plain" :type="reportStatusType(report.status)">
                {{ reportStatusLabel(report.status) }}
              </el-tag>
            </div>
            <p class="report-content">{{ report.content }}</p>
            <div class="report-progress-line">
              <span>汇报进度 {{ report.progress }}%</span>
              <span v-if="report.status === 'APPROVED'">最终进度 {{ report.finalProgress ?? report.progress }}%</span>
            </div>

            <div v-if="report.reviewedAt && report.status !== 'WITHDRAWN'" class="review-detail">
              <div class="review-heading"><el-icon><ChatDotRound /></el-icon><strong>审核详情</strong></div>
              <span>{{ report.reviewerName || '审核人' }} · {{ formatTime(report.reviewedAt) }}</span>
              <p>{{ report.reviewComment || '未填写审核意见' }}</p>
            </div>

            <div v-if="isReportOwner(report)" class="report-actions">
              <el-button v-if="report.status === 'PENDING'" text type="warning" :icon="Remove"
                         :loading="actionId === report.id" @click="withdraw(report)">撤回</el-button>
              <template v-if="report.status === 'WITHDRAWN'">
                <el-button text type="primary" :icon="EditPen" @click="startEdit(report)">编辑汇报</el-button>
                <el-button text type="primary" :icon="Upload" :loading="actionId === report.id"
                           @click="resubmit(report)">重新提交</el-button>
              </template>
            </div>

            <div v-if="report.status === 'WITHDRAWN' && editingReportId === report.id" class="report-editor">
              <el-input v-model="editForm.content" type="textarea" :rows="3" maxlength="2000" show-word-limit />
              <div class="editor-actions">
                <el-input-number v-model="editForm.progress" :min="myProgress" :max="100" />
                <span class="editor-unit">%</span>
                <el-button type="primary" :loading="savingEdit" :icon="Check" @click="saveEdit(report)">保存修改</el-button>
                <el-button text @click="cancelEdit">取消</el-button>
              </div>
            </div>

            <div class="report-comments">
              <div v-if="reportCommentErrors[report.id]" class="comment-load-error">
                <span>{{ reportCommentErrors[report.id] }}</span>
                <el-button text size="small" :icon="Refresh" :loading="reportCommentLoading.has(report.id)"
                           @click="loadReportCommentList(report.id)">重试</el-button>
              </div>
              <template v-else>
                <div v-for="comment in reportCommentMap[report.id] || []" :key="comment.id" class="comment-row">
                  <div><strong>{{ comment.userName || '用户' }}</strong><span class="comment-time">{{ formatTime(comment.createdAt) }}</span></div>
                  <p>{{ comment.content }}</p>
                  <el-button v-if="comment.parentId === 0" text size="small" :icon="ChatLineRound"
                             @click="setReplyTarget(`report-${report.id}-${comment.id}`)">回复</el-button>
                  <div v-if="comment.parentId === 0 && replyTarget === `report-${report.id}-${comment.id}`" class="reply-editor">
                    <el-input v-model="replyInputs[replyTarget]" size="small" placeholder="写下回复"
                              :disabled="replySubmitting.has(comment.id)" @keyup.enter="submitReply(comment, report.id)" />
                    <el-button size="small" type="primary" :icon="Position" :loading="replySubmitting.has(comment.id)"
                               @click="submitReply(comment, report.id)">发送</el-button>
                  </div>
                </div>
                <div class="comment-compose">
                  <el-input v-model="commentInputs[report.id]" size="small" placeholder="评论这条汇报"
                            :disabled="reportCommentSubmitting.has(report.id)" @keyup.enter="addReportComment(report)" />
                  <el-button size="small" type="primary" plain :icon="ChatLineRound"
                             :loading="reportCommentSubmitting.has(report.id)" @click="addReportComment(report)">评论</el-button>
                </div>
              </template>
            </div>
          </article>
        </div>

        <div v-if="isMyTask && !myPendingReport" class="new-report">
          <div class="new-report-heading"><span class="eyebrow">我是成员</span><h3>提交新汇报</h3></div>
          <el-input v-model="reportForm.content" type="textarea" :rows="3" maxlength="2000" show-word-limit
                    placeholder="描述本次完成内容、风险或下一步计划" />
          <div class="new-report-actions">
            <span>目标进度</span>
            <el-input-number v-model="reportForm.progress" :min="myProgress" :max="100" />
            <span>% · 当前进度 {{ myProgress }}%</span>
            <el-button type="primary" :icon="Upload" :loading="submittingReport" @click="submitNewReport">提交汇报</el-button>
          </div>
        </div>
        <el-alert v-else-if="isMyTask && myPendingReport" class="pending-hint" type="warning" :closable="false"
                  title="已有汇报待审核，可在上方汇报记录中撤回后重新编辑。" />
      </section>

      <section class="section task-comments-section">
        <div class="section-heading">
          <div><span class="eyebrow">协作</span><h3>任务评论</h3></div>
          <span class="section-count">{{ taskCommentList.length }} 条</span>
        </div>
        <el-empty v-if="!taskCommentList.length" description="暂无评论" :image-size="56" />
        <div v-else class="task-comment-list">
          <div v-for="comment in topTaskComments" :key="comment.id" class="task-comment">
            <div class="comment-row">
              <div><strong>{{ comment.userName || '用户' }}</strong><span class="comment-time">{{ formatTime(comment.createdAt) }}</span></div>
              <p>{{ comment.content }}</p>
              <el-button text size="small" :icon="ChatLineRound" @click="setReplyTarget(`task-${comment.id}`)">回复</el-button>
            </div>
            <div v-for="reply in repliesOf(comment)" :key="reply.id" class="task-reply">
              <strong>{{ reply.userName || '用户' }}</strong><span> 回复 {{ comment.userName || '评论者' }}：</span>{{ reply.content }}
            </div>
            <div v-if="replyTarget === `task-${comment.id}`" class="reply-editor">
              <el-input v-model="replyInputs[replyTarget]" size="small" placeholder="写下回复"
                        :disabled="replySubmitting.has(comment.id)" @keyup.enter="submitReply(comment)" />
              <el-button size="small" type="primary" :icon="Position" :loading="replySubmitting.has(comment.id)"
                         @click="submitReply(comment)">发送</el-button>
            </div>
          </div>
        </div>
        <div class="comment-compose task-compose">
          <el-input v-model="taskComment" placeholder="评论任务" :disabled="taskCommentSubmitting"
                    @keyup.enter="addTaskComment" />
          <el-button type="primary" :icon="ChatLineRound" :loading="taskCommentSubmitting"
                     @click="addTaskComment">评论任务</el-button>
        </div>
      </section>
    </template>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  ArrowLeft, ChatDotRound, ChatLineRound, Check, EditPen, Paperclip, Position,
  Refresh, Remove, Upload, UserFilled
} from '@element-plus/icons-vue'
import {
  getTask, listReports, submitReport, withdrawReport, updateReport, resubmitReport
} from '../api/task'
import {
  taskComments, addTaskComment as addTaskCommentApi, reportComments,
  addReportComment as addReportCommentApi, replyComment
} from '../api/comment'
import { useAuthStore } from '../store/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const task = ref(null)
const reports = ref([])
const taskCommentList = ref([])
const reportCommentMap = reactive({})
const commentInputs = reactive({})
const taskComment = ref('')
const replyInputs = reactive({})
const replyTarget = ref('')
const taskCommentSubmitting = ref(false)
const reportCommentSubmitting = reactive(new Set())
const replySubmitting = reactive(new Set())
const reportCommentLoading = reactive(new Set())
const reportCommentErrors = reactive({})
const reportForm = reactive({ content: '', progress: 0 })
const editForm = reactive({ content: '', progress: 0 })
const editingReportId = ref(null)
const loading = ref(false)
const error = ref('')
const submittingReport = ref(false)
const savingEdit = ref(false)
const actionId = ref(null)
let loadSeq = 0
const reportCommentRequestSeq = new Map()

const currentUserId = computed(() => auth.user?.id)
const isMyTask = computed(() => task.value?.members?.some(m => m.userId === currentUserId.value))
const myMember = computed(() => task.value?.members?.find(m => m.userId === currentUserId.value))
const myProgress = computed(() => Math.max(0, Math.min(100, myMember.value?.progress ?? 0)))
const myPendingReport = computed(() => reports.value.find(r => r.userId === currentUserId.value && r.status === 'PENDING'))
const topTaskComments = computed(() => taskCommentList.value.filter(c => c.parentId === 0))

const safeProgress = p => Math.max(0, Math.min(100, Number(p) || 0))
const taskStatusLabel = status => status === 'DONE' ? '已完成' : '进行中'
const reportStatusLabel = status => ({ PENDING: '待审核', APPROVED: '已通过', REJECTED: '已驳回', WITHDRAWN: '已撤回' }[status] || '未知状态')
const reportStatusType = status => ({ PENDING: 'warning', APPROVED: 'success', REJECTED: 'danger', WITHDRAWN: 'info' }[status] || 'info')
const formatDate = value => value ? String(value).slice(0, 10) : '未设置'
const formatTime = value => value ? String(value).replace('T', ' ').slice(0, 16) : '—'
const formatSize = value => {
  const size = Number(value) || 0
  if (size < 1024) return `${size} B`
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`
  return `${(size / 1024 / 1024).toFixed(1)} MB`
}
const isReportOwner = report => Number(report.userId) === Number(currentUserId.value)
const repliesOf = comment => taskCommentList.value.filter(c => c.parentId === comment.id)

const loadReportCommentList = async (reportId, seq = loadSeq) => {
  const requestSeq = (reportCommentRequestSeq.get(reportId) || 0) + 1
  reportCommentRequestSeq.set(reportId, requestSeq)
  reportCommentLoading.add(reportId)
  delete reportCommentErrors[reportId]
  try {
    const comments = await reportComments(reportId)
    if (seq === loadSeq && requestSeq === reportCommentRequestSeq.get(reportId)) {
      reportCommentMap[reportId] = comments || []
    }
  } catch (e) {
    if (seq === loadSeq && requestSeq === reportCommentRequestSeq.get(reportId)) {
      reportCommentErrors[reportId] = e.message || '评论加载失败'
    }
  } finally {
    if (requestSeq === reportCommentRequestSeq.get(reportId)) reportCommentLoading.delete(reportId)
  }
}

const load = async () => {
  const seq = ++loadSeq
  loading.value = true
  error.value = ''
  try {
    const taskId = route.params.id
    const [taskData, reportData, taskCommentsData] = await Promise.all([
      getTask(taskId), listReports(taskId), taskComments(taskId)
    ])
    if (seq !== loadSeq) return
    task.value = taskData
    reports.value = reportData || []
    taskCommentList.value = taskCommentsData || []
    await Promise.all(reports.value.map(report => loadReportCommentList(report.id, seq)))
    if (seq !== loadSeq) return
    reportForm.progress = myProgress.value
  } catch (e) {
    if (seq === loadSeq) error.value = e.message || '网络错误'
  } finally {
    if (seq === loadSeq) loading.value = false
  }
}

const backToTasks = () => router.push('/tasks')

const withdraw = async report => {
  if (actionId.value) return
  actionId.value = report.id
  try {
    await withdrawReport(report.id)
    ElMessage.success('汇报已撤回，可重新编辑')
    await load()
  } finally {
    actionId.value = null
  }
}

const startEdit = report => {
  editingReportId.value = report.id
  editForm.content = report.content || ''
  editForm.progress = report.progress ?? myProgress.value
}

const cancelEdit = () => { editingReportId.value = null }

const saveEdit = async report => {
  if (!editForm.content.trim() || savingEdit.value) return ElMessage.warning('请填写汇报内容')
  savingEdit.value = true
  try {
    await updateReport(report.id, { content: editForm.content.trim(), progress: editForm.progress })
    ElMessage.success('汇报已更新')
    editingReportId.value = null
    await load()
  } finally {
    savingEdit.value = false
  }
}

const resubmit = async report => {
  if (actionId.value) return
  actionId.value = report.id
  try {
    await resubmitReport(report.id)
    ElMessage.success('汇报已重新提交，等待审核')
    editingReportId.value = null
    await load()
  } finally {
    actionId.value = null
  }
}

const submitNewReport = async () => {
  if (!reportForm.content.trim() || submittingReport.value) return ElMessage.warning('请填写汇报内容')
  submittingReport.value = true
  try {
    await submitReport(task.value.id, { content: reportForm.content.trim(), progress: reportForm.progress })
    ElMessage.success('汇报已提交，等待审核')
    reportForm.content = ''
    await load()
  } finally {
    submittingReport.value = false
  }
}

const addTaskComment = async () => {
  if (!taskComment.value.trim() || taskCommentSubmitting.value) return
  taskCommentSubmitting.value = true
  try {
    await addTaskCommentApi(task.value.id, taskComment.value.trim())
    taskComment.value = ''
    taskCommentList.value = await taskComments(task.value.id)
  } finally {
    taskCommentSubmitting.value = false
  }
}

const addReportComment = async report => {
  const content = commentInputs[report.id]
  if (!content?.trim() || reportCommentSubmitting.has(report.id)) return
  reportCommentSubmitting.add(report.id)
  try {
    await addReportCommentApi(report.id, content.trim())
    commentInputs[report.id] = ''
    await loadReportCommentList(report.id)
  } finally {
    reportCommentSubmitting.delete(report.id)
  }
}

const setReplyTarget = target => { replyTarget.value = replyTarget.value === target ? '' : target }

const submitReply = async (comment, reportId = null) => {
  const target = reportId ? `report-${reportId}-${comment.id}` : `task-${comment.id}`
  const content = replyInputs[target]
  if (!content?.trim() || replySubmitting.has(comment.id)) return
  replySubmitting.add(comment.id)
  try {
    await replyComment(comment.id, content.trim())
    replyInputs[target] = ''
    replyTarget.value = ''
    if (reportId) await loadReportCommentList(reportId)
    else taskCommentList.value = await taskComments(task.value.id)
  } finally {
    replySubmitting.delete(comment.id)
  }
}

onMounted(load)
watch(() => route.params.id, load)
</script>

<style scoped>
.detail-page { min-width: 0; }
.detail-toolbar { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; }
.overview-section { border-top: 3px solid var(--color-primary); }
.overview-section .page-head > div:first-child { min-width: 0; }
.overview-section h2 { margin: 4px 0 0; font-size: 22px; line-height: 1.25; overflow-wrap: anywhere; word-break: break-word; }
.eyebrow { display: block; color: var(--color-text-muted); font-size: 11px; font-weight: 600; letter-spacing: .08em; text-transform: uppercase; }
.overview-progress { display: flex; flex-direction: column; align-items: flex-end; color: var(--color-text-muted); }
.overview-progress strong { color: var(--color-primary); font-size: 30px; line-height: 1; }
.overview-progress span { margin-top: 5px; font-size: 12px; }
.task-description { margin: 18px 0; color: var(--color-text-secondary); line-height: 1.6; }
.task-meta-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px; margin-bottom: 18px; }
.task-meta-grid div { padding: 10px 12px; background: var(--color-bg); border-left: 2px solid var(--color-border-strong); min-width: 0; }
.task-meta-grid span, .task-meta-grid strong { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.task-meta-grid span { color: var(--color-text-muted); font-size: 12px; margin-bottom: 4px; }
.task-meta-grid strong { color: var(--color-text); font-size: 13px; }
.section-heading { display: flex; justify-content: space-between; align-items: flex-end; gap: 12px; margin-bottom: 12px; }
.section-heading h3, .new-report-heading h3 { margin: 3px 0 0; font-size: 16px; }
.section-count { color: var(--color-text-muted); font-size: 12px; }
.member-list { border-top: 1px solid var(--color-border); }
.member-row { display: flex; align-items: center; gap: 24px; padding: 12px 0; border-bottom: 1px solid var(--color-border); }
.member-identity { display: flex; align-items: center; gap: 10px; width: 180px; min-width: 0; }
.member-identity strong, .member-identity small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.member-identity small { margin-top: 3px; color: var(--color-text-muted); font-size: 12px; }
.member-mark { display: grid; place-items: center; width: 30px; height: 30px; flex: 0 0 30px; color: var(--color-primary); background: #eaf0ff; border-radius: 50%; }
.member-progress { display: flex; align-items: center; gap: 12px; flex: 1; min-width: 0; }
.member-progress .el-progress { flex: 1; }
.member-progress strong { width: 40px; text-align: right; font-size: 13px; }
.attachment-list { border-top: 1px solid var(--color-border); }
.attachment-row { display: flex; align-items: center; gap: 8px; padding: 10px 4px; color: var(--color-primary); border-bottom: 1px solid var(--color-border); text-decoration: none; }
.attachment-row:hover { background: var(--color-bg); }
.attachment-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.attachment-size { margin-left: auto; color: var(--color-text-muted); font-size: 12px; }
.report-list { display: grid; gap: 10px; }
.report-item { padding: 14px; border: 1px solid var(--color-border); border-left: 3px solid var(--color-border-strong); background: var(--color-surface); }
.report-status-pending { border-left-color: var(--color-warning); }
.report-status-approved { border-left-color: var(--color-success); }
.report-status-rejected { border-left-color: var(--color-danger); }
.report-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.report-author { display: flex; align-items: center; gap: 8px; min-width: 0; }
.report-author strong { min-width: 0; max-width: 240px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.report-dot { width: 8px; height: 8px; border-radius: 50%; background: var(--color-border-strong); flex: 0 0 8px; }
.dot-pending { background: var(--color-warning); }.dot-approved { background: var(--color-success); }.dot-rejected { background: var(--color-danger); }.dot-withdrawn { background: var(--color-text-muted); }
.report-time, .comment-time { color: var(--color-text-muted); font-size: 12px; }
.report-content { margin: 12px 0 8px; white-space: pre-wrap; line-height: 1.6; }
.report-progress-line { display: flex; gap: 18px; color: var(--color-text-secondary); font-size: 12px; }
.review-detail { margin-top: 12px; padding: 10px 12px; background: #f4f7fb; border-left: 2px solid var(--color-primary); color: var(--color-text-secondary); font-size: 12px; }
.review-heading { display: flex; align-items: center; gap: 5px; color: var(--color-text); margin-bottom: 4px; }
.review-detail p { margin: 5px 0 0; white-space: pre-wrap; }
.report-actions { display: flex; gap: 2px; margin-top: 6px; }
.report-editor { margin-top: 10px; padding: 10px; background: var(--color-bg); }
.editor-actions, .new-report-actions, .comment-compose, .reply-editor { display: flex; align-items: center; gap: 8px; }
.editor-actions { margin-top: 8px; flex-wrap: wrap; }.editor-unit { color: var(--color-text-muted); margin-left: -4px; }
.report-comments { margin-top: 12px; padding-top: 10px; border-top: 1px solid var(--color-border); }
.comment-load-error { display: flex; align-items: center; justify-content: space-between; gap: 8px; color: var(--color-danger); font-size: 12px; }
.comment-row { position: relative; padding: 5px 0 5px 0; font-size: 13px; }.comment-row strong { margin-right: 8px; }.comment-row p { margin: 4px 0; color: var(--color-text-secondary); white-space: pre-wrap; }
.comment-row > .el-button { padding: 0; }
.reply-editor { margin: 5px 0 0 18px; max-width: 460px; }
.comment-compose { margin-top: 10px; }.comment-compose .el-input { flex: 1; min-width: 0; }
.new-report { margin-top: 16px; padding-top: 16px; border-top: 1px solid var(--color-border-strong); }.new-report-actions { margin-top: 10px; flex-wrap: wrap; color: var(--color-text-secondary); font-size: 13px; }.new-report-actions .el-button { margin-left: auto; }
.pending-hint { margin-top: 16px; }
.task-comment-list { border-top: 1px solid var(--color-border); }.task-comment { padding: 8px 0; border-bottom: 1px solid var(--color-border); }.task-reply { margin: 4px 0 0 20px; color: var(--color-text-secondary); font-size: 13px; }
.task-compose { max-width: 640px; }
.loading-section { margin-bottom: 12px; }.center-state { min-height: 360px; display: grid; place-items: center; }
.task-description, .report-content, .review-detail p, .comment-row p, .task-reply { overflow-wrap: anywhere; word-break: break-word; }
@media (max-width: 767px) {
  .overview-section h2 { font-size: 19px; }.overview-progress strong { font-size: 24px; }.task-meta-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .member-row { display: block; }.member-identity { width: auto; margin-bottom: 8px; }.report-head { align-items: flex-start; }.report-author strong { max-width: 38vw; }.report-time { display: none; }.report-progress-line { flex-wrap: wrap; gap: 6px 14px; }
  .new-report-actions .el-button { width: 100%; margin-left: 0; }.comment-compose { align-items: stretch; }.reply-editor { margin-left: 0; }
}
</style>
