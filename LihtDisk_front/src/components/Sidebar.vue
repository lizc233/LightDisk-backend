<template>
  <div class="sidebar">
    <div class="logo">
      <el-icon :size="28"><FolderOpened /></el-icon>
      <span>LightDisk</span>
    </div>
    <el-menu
      :default-active="currentView"
      class="sidebar-menu"
      @select="handleSelect"
    >
      <el-menu-item index="all">
        <el-icon><Folder /></el-icon>
        <span>全部文件</span>
      </el-menu-item>
      <el-menu-item index="images">
        <el-icon><Picture /></el-icon>
        <span>图片</span>
      </el-menu-item>
      <el-menu-item index="docs">
        <el-icon><Document /></el-icon>
        <span>文档</span>
      </el-menu-item>
      <el-menu-item index="videos">
        <el-icon><VideoCamera /></el-icon>
        <span>视频</span>
      </el-menu-item>
      <el-menu-item index="shared">
        <el-icon><Share /></el-icon>
        <span>分享</span>
      </el-menu-item>
      <el-menu-item index="recycle">
        <el-icon><Delete /></el-icon>
        <span>回收站</span>
      </el-menu-item>
    </el-menu>
  </div>
</template>

<script setup>
import { defineProps, defineEmits } from 'vue'
import { FolderOpened, Folder, Picture, Document, VideoCamera, Share, Delete } from '@element-plus/icons-vue'

const props = defineProps({
  currentView: String,
})
const emit = defineEmits(['change-view'])

const handleSelect = (key) => {
  emit('change-view', key)
}
</script>

<style scoped>
.sidebar {
  width: 240px;
  background: white;
  border-right: 1px solid #e8e8e8;
  display: flex;
  flex-direction: column;
}
.logo {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 20px 24px;
  font-size: 18px;
  font-weight: 500;
  color: #409eff;
  border-bottom: 1px solid #e8e8e8;
}
.sidebar-menu {
  flex: 1;
  border-right: none;
}
</style>