<template>
  <div class="section">
    <div class="page-head"><h2>{{ title }}</h2></div>
    <el-empty :description="`${title}开发中，敬请期待`" :image-size="80" />
  </div>
</template>

<script setup>
defineProps({
  title: { type: String, required: true }
})
</script>
