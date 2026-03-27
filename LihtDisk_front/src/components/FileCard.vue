<template>
  <div class="file-card" @click="handleClick">
    <div class="icon">
      <el-icon :size="48">
        <component :is="iconName" />
      </el-icon>
    </div>
    <div class="name">{{ item.name }}</div>
    <div class="info" v-if="item.type !== 'folder'">{{ item.size }}</div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { Folder, Document, Picture, VideoCamera, Headset } from '@element-plus/icons-vue'

const props = defineProps({
  item: Object,
})
const emit = defineEmits(['click'])

const iconName = computed(() => {
  if (props.item.type === 'folder') return Folder
  const ext = props.item.name.split('.').pop().toLowerCase()
  const map = {
    pdf: Document,
    png: Picture,
    jpg: Picture,
    docx: Document,
    mp4: VideoCamera,
    mp3: Headset,
  }
  return map[ext] || Document
})

const handleClick = () => {
  emit('click', props.item)
}
</script>

<style scoped>
.file-card {
  background: white;
  border-radius: 12px;
  padding: 16px 12px;
  text-align: center;
  cursor: pointer;
  transition: all 0.2s;
  border: 1px solid transparent;
}
.file-card:hover {
  box-shadow: 0 4px 12px rgba(0,0,0,0.1);
  border-color: #e0e0e0;
}
.icon {
  margin-bottom: 12px;
  color: #ffb74d;
}
.name {
  font-size: 13px;
  word-break: break-word;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.info {
  font-size: 12px;
  color: #999;
  margin-top: 4px;
}
</style>