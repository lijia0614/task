<template>
  <div class="section">
    <div class="page-head"><h2>{{ title }}</h2></div>
    <el-empty description="暂无数据" :image-size="80" />
  </div>
</template>

<script setup>
defineProps({
  title: { type: String, required: true }
})
</script>
