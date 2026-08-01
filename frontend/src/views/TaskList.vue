<template>
  <div class="section">
    <div class="page-head">
      <h2>任务列表</h2>
      <el-button v-if="auth.canCreateTask" type="primary" :icon="Plus" @click="$router.push('/tasks/create')">
        创建任务
      </el-button>
    </div>
    <el-empty description="任务列表开发中（Task 12 实现）" :image-size="80" />
  </div>
</template>

<script setup>
import { Plus } from '@element-plus/icons-vue'
import { useAuthStore } from '../store/auth'

const auth = useAuthStore()
</script>
