<template>
  <div class="review-page">
    <div class="page-head review-head">
      <div>
        <span class="eyebrow">审核队列</span>
        <h2>待我审核</h2>
      </div>
      <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
    </div>

    <div v-if="error" class="section center-state">
      <el-result icon="error" title="加载失败" :sub-title="error">
        <template #extra>
          <el-button type="primary" :icon="Refresh" @click="load">重试</el-button>
        </template>
      </el-result>
    </div>

    <div v-else-if="loading" class="section loading-section">
      <div v-for="item in 3" :key="item" class="review-skeleton">
        <el-skeleton :rows="3" animated />
      </div>
    </div>

    <div v-else-if="!list.length" class="section center-state">
      <el-empty description="没有待审核的汇报" :image-size="76" />
    </div>

    <section v-else class="section queue-section">
      <div class="queue-summary">
        <span>共 {{ list.length }} 条待处理汇报</span>
        <span>按提交时间排序</span>
      </div>
      <div class="review-list">
        <article v-for="report in list" :key="report.id" class="review-item" :data-report-id="report.id">
          <div class="review-item-head">
            <div class="task-and-user">
              <el-button text type="primary" class="task-link" @click="openTask(report)">
                {{ report.taskName || `任务 #${report.taskId}` }}
              </el-button>
              <span class="user-chip"><el-icon><User /></el-icon>{{ report.userName || '未知成员' }}</span>
            </div>
            <time>{{ formatTime(report.createdAt) }}</time>
          </div>

          <p class="report-content">{{ report.content }}</p>

          <div class="review-item-foot">
            <div class="progress-block">
              <span>汇报进度</span>
              <el-progress :percentage="safeProgress(report.progress)" :stroke-width="7" :show-text="false" />
              <strong>{{ safeProgress(report.progress) }}%</strong>
            </div>
            <div class="review-actions">
              <el-button type="success" plain :icon="Check" @click="openApprove(report)">通过</el-button>
              <el-button type="danger" plain :icon="Close" @click="openReject(report)">驳回</el-button>
            </div>
          </div>
        </article>
      </div>
    </section>

    <el-dialog v-model="approveVisible" title="审核通过" :width="dialogWidth" destroy-on-close>
      <el-form label-width="82px">
        <el-form-item label="任务">
          <span class="dialog-task-name">{{ selectedReport?.taskName || '—' }}</span>
        </el-form-item>
        <el-form-item label="最终进度">
          <div class="progress-field">
            <el-input-number v-model="approveForm.progress" :min="0" :max="100" />
            <span>%</span>
          </div>
          <div class="field-help">默认使用汇报进度，可按实际完成情况调整。</div>
        </el-form-item>
        <el-form-item label="审核意见">
          <el-input v-model="approveForm.reviewComment" type="textarea" :rows="3"
                    maxlength="255" show-word-limit placeholder="可选" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="submitting" @click="approveVisible = false">取消</el-button>
        <el-button type="success" :icon="Check" :loading="submitting" @click="doApprove">确认通过</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="rejectVisible" title="审核驳回" :width="dialogWidth" destroy-on-close>
      <el-form label-width="82px">
        <el-form-item label="任务">
          <span class="dialog-task-name">{{ selectedReport?.taskName || '—' }}</span>
        </el-form-item>
        <el-form-item label="驳回理由" required>
          <el-input v-model="rejectForm.reviewComment" type="textarea" :rows="4"
                    maxlength="255" show-word-limit placeholder="说明未通过的原因和需要补充的内容" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="submitting" @click="rejectVisible = false">取消</el-button>
        <el-button type="danger" :icon="Close" :loading="submitting" @click="doReject">确认驳回</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Check, Close, Refresh, User } from '@element-plus/icons-vue'
import { pendingReports, approveReport, rejectReport } from '../api/task'

const router = useRouter()
const list = ref([])
const loading = ref(false)
const error = ref('')
const approveVisible = ref(false)
const rejectVisible = ref(false)
const selectedReport = ref(null)
const submitting = ref(false)
const viewportWidth = ref(window.innerWidth)
const approveForm = reactive({ progress: 0, reviewComment: '' })
const rejectForm = reactive({ reviewComment: '' })
let requestSeq = 0

const dialogWidth = computed(() => `${Math.min(480, viewportWidth.value - 24)}px`)
const safeProgress = value => Math.max(0, Math.min(100, Number(value) || 0))
const formatTime = value => value ? String(value).replace('T', ' ').slice(0, 16) : '—'

const load = async () => {
  const seq = ++requestSeq
  loading.value = true
  error.value = ''
  try {
    const data = await pendingReports()
    if (seq !== requestSeq) return
    list.value = data || []
  } catch (e) {
    if (seq !== requestSeq) return
    list.value = []
    error.value = e.message || '网络错误'
  } finally {
    if (seq === requestSeq) loading.value = false
  }
}

const openTask = report => router.push(`/tasks/${report.taskId}`)

const openApprove = report => {
  selectedReport.value = report
  approveForm.progress = safeProgress(report.progress)
  approveForm.reviewComment = ''
  approveVisible.value = true
}

const openReject = report => {
  selectedReport.value = report
  rejectForm.reviewComment = ''
  rejectVisible.value = true
}

const doApprove = async () => {
  if (!selectedReport.value || submitting.value) return
  submitting.value = true
  try {
    await approveReport(selectedReport.value.id, {
      progress: approveForm.progress,
      reviewComment: approveForm.reviewComment.trim()
    })
    ElMessage.success('汇报已通过')
    approveVisible.value = false
    await load()
  } finally {
    submitting.value = false
  }
}

const doReject = async () => {
  if (!rejectForm.reviewComment.trim()) return ElMessage.warning('驳回必须填写理由')
  if (!selectedReport.value || submitting.value) return
  submitting.value = true
  try {
    await rejectReport(selectedReport.value.id, { reviewComment: rejectForm.reviewComment.trim() })
    ElMessage.success('汇报已驳回')
    rejectVisible.value = false
    await load()
  } finally {
    submitting.value = false
  }
}

const onResize = () => { viewportWidth.value = window.innerWidth }
onMounted(() => {
  window.addEventListener('resize', onResize)
  load()
})
onBeforeUnmount(() => window.removeEventListener('resize', onResize))
</script>

<style scoped>
.review-page { min-width: 0; }
.review-head { margin-bottom: 12px; }
.review-head h2 { margin-top: 3px; }
.eyebrow { display: block; color: var(--color-text-muted); font-size: 11px; font-weight: 600; letter-spacing: .08em; }
.center-state { min-height: 400px; display: grid; place-items: center; }
.loading-section { display: grid; gap: 18px; }
.review-skeleton + .review-skeleton { padding-top: 18px; border-top: 1px solid var(--color-border); }
.queue-summary { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding-bottom: 10px; color: var(--color-text-muted); font-size: 12px; border-bottom: 1px solid var(--color-border-strong); }
.review-item { padding: 16px 2px; border-bottom: 1px solid var(--color-border); }
.review-item:last-child { border-bottom: 0; }
.review-item-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; min-width: 0; }
.task-and-user { display: flex; align-items: center; gap: 8px; min-width: 0; }
.task-link { max-width: 360px; padding: 0; font-weight: 600; overflow: hidden; }
.task-link :deep(span) { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.user-chip { display: inline-flex; align-items: center; gap: 4px; flex: 0 0 auto; padding-left: 8px; color: var(--color-text-secondary); font-size: 12px; border-left: 1px solid var(--color-border); }
.review-item time { flex: 0 0 auto; color: var(--color-text-muted); font-size: 12px; }
.report-content { margin: 12px 0; color: var(--color-text-secondary); line-height: 1.65; white-space: pre-wrap; overflow-wrap: anywhere; word-break: break-word; }
.review-item-foot { display: flex; align-items: center; justify-content: space-between; gap: 20px; }
.progress-block { display: flex; align-items: center; gap: 10px; min-width: 260px; color: var(--color-text-muted); font-size: 12px; }
.progress-block .el-progress { width: 180px; }.progress-block strong { width: 38px; color: var(--color-text); text-align: right; }
.review-actions { display: flex; gap: 8px; }
.progress-field { display: flex; align-items: center; gap: 6px; }
.field-help { width: 100%; margin-top: 5px; color: var(--color-text-muted); font-size: 12px; }
.dialog-task-name { max-width: 100%; overflow-wrap: anywhere; word-break: break-word; }
@media (max-width: 767px) {
  .queue-section { padding: 12px; }.queue-summary span:last-child { display: none; }.review-item { padding: 14px 0; }
  .review-item-head { align-items: flex-start; }.task-and-user { display: block; }.task-link { display: block; max-width: 62vw; }.user-chip { margin-top: 5px; padding-left: 0; border-left: 0; }
  .review-item-foot { display: block; }.progress-block { min-width: 0; width: 100%; }.progress-block .el-progress { flex: 1; width: auto; }
  .review-actions { margin-top: 12px; justify-content: flex-end; }
}
</style>
