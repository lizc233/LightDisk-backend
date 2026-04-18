<template>
  <el-container class="layout-container">
    <el-aside width="240px" class="aside-glass">
      <div class="logo-section">
        <div class="logo-icon">L</div>
        <span class="logo-text">LightDisk</span>
      </div>

      <el-menu
          :default-active="activeMenu"
          class="side-menu"
          router
      >
        <el-menu-item index="/app/disk">
          <el-icon><Document /></el-icon>
          <span>全部文件</span>
        </el-menu-item>
        <el-menu-item index="/app/share">
          <el-icon><Share /></el-icon>
          <span>我的分享</span>
        </el-menu-item>
        <el-menu-item index="/app/recycle">
          <el-icon><Delete /></el-icon>
          <span>回收站</span>
        </el-menu-item>
        <el-menu-item index="/app/admin">
          <el-icon><Setting /></el-icon>
          <span>后台管理</span>
        </el-menu-item>
      </el-menu>

      <div class="notice-card">
        <div class="notice-title">系统公告</div>
        <el-carousel height="80px" direction="vertical" :autoplay="true" indicator-position="none">
          <el-carousel-item v-for="item in notices" :key="item">
            <p class="notice-item">{{ item }}</p>
          </el-carousel-item>
        </el-carousel>
      </div>
    </el-aside>

    <el-container class="main-container">
      <el-header class="header-glass">
        <div class="header-left">
          <el-breadcrumb separator="/">
            <el-breadcrumb-item>首页</el-breadcrumb-item>
            <el-breadcrumb-item>{{ currentPageName }}</el-breadcrumb-item>
          </el-breadcrumb>
        </div>

        <div class="header-right">
          <div class="storage-info">
            <span class="storage-label">存储空间</span>
            <el-progress
                :percentage="40"
                :stroke-width="8"
                stroke-linecap="round"
                :show-text="false"
                class="storage-bar"
            />
            <span class="storage-text">2.0GB / 5.0GB</span>
          </div>

          <el-dropdown trigger="click">
            <div class="user-profile">
              <el-avatar :size="32" src="https://cube.elemecdn.com/0/88/03b0d39583f48206768a7534e55bcpng.png" />
              <span class="username">李炤辰</span>
            </div>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item>个人中心</el-dropdown-item>
                <el-dropdown-item>设置</el-dropdown-item>
                <el-dropdown-item divided style="color: #f56c6c;">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <el-main class="page-main">
        <div class="content-card">
          <router-view v-slot="{ Component }">
            <transition name="fade-transform" mode="out-in">
              <component :is="Component" />
            </transition>
          </router-view>
        </div>
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { Document, Share, Delete, Setting } from '@element-plus/icons-vue'

const route = useRoute()

// 当前激活的菜单
const activeMenu = computed(() => route.path)

// 当前页面名称显示
const currentPageName = computed(() => {
  const map = {
    '/disk': '全部文件',
    '/share': '我的分享',
    '/recycle': '回收站',
    '/admin': '后台管理'
  }
  return map[route.path] || '网盘'
})

// 模拟公告数据
const notices = [
  "🎉 LightDisk V2.0 视觉升级完成！",
  "🚀 新增 Redis 缓存优化，访问更丝滑。",
  "💎 VIP 会员特权现已开启预览版。",
  "⚡️ 修复了回收站批量还原的 Bug。"
]
</script>

<style scoped>
/* 基础容器 */
.layout-container {
  height: 100vh;
  background-color: #f5f7fa; /* 非常浅的灰白色背景 */
  overflow: hidden;
}

/* 侧边栏毛玻璃感 */
.aside-glass {
  background: rgba(255, 255, 255, 0.7);
  backdrop-filter: blur(12px);
  border-right: 1px solid rgba(255, 255, 255, 0.3);
  display: flex;
  flex-direction: column;
  box-shadow: 4px 0 15px rgba(0, 0, 0, 0.02);
  z-index: 10;
}

.logo-section {
  padding: 30px 24px;
  display: flex;
  align-items: center;
  gap: 12px;
}

.logo-icon {
  width: 32px;
  height: 32px;
  background: linear-gradient(135deg, #409eff, #79bbff);
  color: white;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  font-weight: bold;
  box-shadow: 0 4px 10px rgba(64, 158, 255, 0.3);
}

.logo-text {
  font-size: 20px;
  font-weight: 600;
  color: #303133;
  letter-spacing: 0.5px;
}

/* 菜单样式与交互感应 */
.side-menu {
  border-right: none !important;
  background: transparent !important;
  flex: 1;
}

.side-menu :deep(.el-menu-item) {
  margin: 8px 16px;
  border-radius: 12px;
  height: 50px;
  line-height: 50px;
  transition: all 0.3s cubic-bezier(0.25, 0.8, 0.25, 1);
}

.side-menu :deep(.el-menu-item:hover) {
  background-color: rgba(64, 158, 255, 0.1) !important;
  transform: translateX(5px); /* 鼠标滑动弹出感 */
  color: #409eff;
}

.side-menu :deep(.el-menu-item.is-active) {
  background-color: #409eff !important;
  color: #fff !important;
  box-shadow: 0 4px 12px rgba(64, 158, 255, 0.3);
}

/* 顶部 Header 毛玻璃 */
.header-glass {
  background: rgba(255, 255, 255, 0.6);
  backdrop-filter: blur(10px);
  border-bottom: 1px solid rgba(255, 255, 255, 0.3);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 30px;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 40px;
}

.storage-info {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 4px;
}

.storage-label {
  font-size: 11px;
  color: #909399;
}

.storage-bar {
  width: 120px;
}

.storage-text {
  font-size: 11px;
  color: #606266;
}

.user-profile {
  display: flex;
  align-items: center;
  gap: 10px;
  cursor: pointer;
  padding: 4px 8px;
  border-radius: 20px;
  transition: background 0.3s;
}

.user-profile:hover {
  background: rgba(0, 0, 0, 0.04);
}

.username {
  font-size: 14px;
  font-weight: 500;
  color: #303133;
}

/* 内容卡片 */
.page-main {
  padding: 24px;
}

.content-card {
  background: #ffffff;
  border-radius: 16px;
  min-height: calc(100vh - 130px);
  padding: 24px;
  box-shadow: 0 8px 30px rgba(0, 0, 0, 0.04); /* 极轻阴影 */
}

/* 系统公告卡片样式 */
.notice-card {
  margin: 20px 16px;
  padding: 16px;
  background: rgba(255, 255, 255, 0.5);
  border-radius: 12px;
  border: 1px solid rgba(255, 255, 255, 0.8);
}

.notice-title {
  font-size: 12px;
  font-weight: bold;
  color: #409eff;
  margin-bottom: 8px;
  display: flex;
  align-items: center;
  gap: 4px;
}

.notice-item {
  font-size: 12px;
  color: #606266;
  line-height: 1.6;
  margin: 0;
}

/* 路由切换过渡动画 */
.fade-transform-enter-active,
.fade-transform-leave-active {
  transition: all 0.4s;
}

.fade-transform-enter-from {
  opacity: 0;
  transform: translateX(-15px);
}

.fade-transform-leave-to {
  opacity: 0;
  transform: translateX(15px);
}
</style>