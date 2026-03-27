<template>
  <el-dialog v-model="visible" title="上传进度" width="400px" :close-on-click-modal="false" :close-on-press-escape="false">
    <div v-for="(file, idx) in files" :key="idx" class="progress-item">
      <div class="progress-header">
        <span>{{ file.name }}</span>
        <span>{{ file.progress }}%</span>
      </div>
      <el-progress :percentage="file.progress" :status="file.status === 'error' ? 'exception' : undefined" />
    </div>
  </el-dialog>
</template>

<script setup>
import { defineProps, defineModel } from 'vue'

const visible = defineModel()
const props = defineProps({
  files: Array,
})
</script>

<style scoped>
.progress-item {
  margin-bottom: 16px;
}
.progress-header {
  display: flex;
  justify-content: space-between;
  margin-bottom: 4px;
  font-size: 13px;
}
</style>