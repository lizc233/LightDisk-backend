<template>
  <div class="landing-viewport" ref="viewportRef">
    <header class="hero-section">
      <h1 class="hero-title">工作事半功倍</h1>
      <p class="hero-slogan">
        LightDisk 提供新一代云端数字资产枢纽，帮助您轻松协作、更快捷地推进工作并确保绝对安全。
      </p>

      <div class="cta-group">
        <button class="btn-primary" @click="goToLogin">
          免费体验 LightDisk
        </button>
        <button class="btn-secondary">了解更多 <el-icon><Right /></el-icon></button>
      </div>

      <div class="trust-banner fade-up-item">
        <p>加入超过 7 亿信赖 LightDisk 的注册用户行列。</p>
        <div class="logo-group">
          <span class="logo-placeholder">ZOOM</span>
          <span class="logo-placeholder">McLaren</span>
          <span class="logo-placeholder">Hydro Flask</span>
        </div>
      </div>
    </header>

    <main class="details-section">
      <div
          class="feature-row fade-up-item"
          v-for="(item, index) in features"
          :key="index"
          :class="{ 'reverse-layout': index % 2 !== 0 }"
      >
        <div class="feature-text">
          <h3>{{ item.title }}</h3>
          <p>{{ item.desc }}</p>
          <a href="#" class="learn-more">探索此功能 <el-icon><Right /></el-icon></a>
        </div>

        <div class="feature-visual">
          <div class="mock-ui-card">
            <el-icon class="huge-icon"><component :is="item.icon" /></el-icon>
          </div>
        </div>
      </div>
    </main>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, markRaw } from 'vue'
import { useRouter } from 'vue-router'
import { Right, Cpu, Lock, Lightning, Connection } from '@element-plus/icons-vue'

const router = useRouter()
const viewportRef = ref(null) // 新增：获取外层容器的 DOM 引用

// 定义详细信息数据 (保持不变)
const features = ref([
  { title: '弹性微服务调度', desc: '基于云原生的底层架构，突破物理设备的性能壁垒...', icon: markRaw(Lightning) },
  { title: '军工级加密隔离', desc: '零信任架构。每一字节的数字资产在落盘前即完成 AES-256 加密...', icon: markRaw(Lock) },
  { title: '无缝的多端生态', desc: '打破设备与时间的孤岛。你的工作流将在浏览器、桌面和移动端之间...', icon: markRaw(Connection) }
])

let observer = null

onMounted(() => {
  // 核心修改：将 root 设置为 viewportRef.value，监听内部滚动
  const options = {
    root: viewportRef.value,
    rootMargin: '0px',
    threshold: 0.15
  }

  observer = new IntersectionObserver((entries) => {
    entries.forEach(entry => {
      if (entry.isIntersecting) {
        entry.target.classList.add('is-visible')
      }
    })
  }, options)

  const elements = document.querySelectorAll('.fade-up-item')
  elements.forEach(el => observer.observe(el))
})

onUnmounted(() => {
  if (observer) {
    observer.disconnect()
  }
})

const goToLogin = () => {
  router.push('/login')
}
</script>

<style scoped>
/* ====== 全局容器 ====== */
.landing-viewport {
  width: 100vw;
  height: 100vh;
  overflow-y: auto; /* 核心修改：内容超出时，内部出现滚动条 */
  overflow-x: hidden;
  background-color: #f7f9fa; /* 模仿 Dropbox 的清爽极浅灰背景 */
  color: #1e1e1e; /* 深色文字 */
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
  padding-bottom: 100px;
  scroll-behavior: smooth;
}

/* ====== 首屏区域 ====== */
.hero-section {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  padding: 15vh 20px 10vh;
  max-width: 800px;
  margin: 0 auto;
}

.hero-title {
  font-size: clamp(3rem, 6vw, 4.5rem);
  font-weight: 800;
  letter-spacing: -0.02em;
  margin: 0 0 24px 0;
  color: #000000;
}

.hero-slogan {
  font-size: clamp(1.1rem, 2vw, 1.3rem);
  color: #555555;
  line-height: 1.6;
  margin: 0 0 40px 0;
  max-width: 600px;
}

/* ====== 按钮组 ====== */
.cta-group {
  display: flex;
  gap: 16px;
  align-items: center;
  margin-bottom: 80px;
}

.btn-primary {
  background-color: #0061ff; /* Dropbox 标志性蓝色 */
  color: #ffffff;
  border: none;
  padding: 16px 32px;
  border-radius: 4px;
  font-size: 1.1rem;
  font-weight: 600;
  cursor: pointer;
  transition: background-color 0.2s ease;
}

.btn-primary:hover {
  background-color: #0052cc;
}

.btn-secondary {
  background: transparent;
  border: none;
  color: #000000;
  font-size: 1.1rem;
  font-weight: 600;
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 8px;
  transition: opacity 0.2s;
}

.btn-secondary:hover {
  opacity: 0.7;
}

/* ====== 信任背书区 ====== */
.trust-banner {
  text-align: center;
  width: 100%;
}

.trust-banner p {
  color: #555;
  font-size: 0.9rem;
  margin-bottom: 24px;
}

.logo-group {
  display: flex;
  justify-content: center;
  gap: 40px;
  flex-wrap: wrap;
  opacity: 0.6;
}

.logo-placeholder {
  font-size: 1.5rem;
  font-weight: 900;
  color: #888;
}

/* ====== 详细信息滚动区块 ====== */
.details-section {
  max-width: 1200px;
  margin: 0 auto;
  padding: 0 40px;
}

.feature-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 60px;
  margin-bottom: 120px;
}

/* 奇数行翻转布局：图在左，文在右 */
.feature-row.reverse-layout {
  flex-direction: row-reverse;
}

.feature-text {
  flex: 1;
  max-width: 500px;
}

.feature-text h3 {
  font-size: 2.5rem;
  font-weight: 700;
  margin-bottom: 24px;
  color: #000000;
}

.feature-text p {
  font-size: 1.2rem;
  color: #555555;
  line-height: 1.6;
  margin-bottom: 24px;
}

.learn-more {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: #000000;
  font-weight: 600;
  text-decoration: none;
  font-size: 1.1rem;
}

.learn-more:hover {
  text-decoration: underline;
}

/* 占位配图设计 */
.feature-visual {
  flex: 1;
  display: flex;
  justify-content: center;
}

.mock-ui-card {
  width: 100%;
  max-width: 500px;
  aspect-ratio: 4/3;
  background-color: #ffffff;
  border-radius: 12px;
  box-shadow: 0 20px 40px rgba(0, 0, 0, 0.08);
  display: flex;
  align-items: center;
  justify-content: center;
  transition: transform 0.3s ease;
}

.mock-ui-card:hover {
  transform: translateY(-10px);
}

.huge-icon {
  font-size: 80px;
  color: #0061ff;
  opacity: 0.8;
}

/* ====== 滚动浮动动画 (Scroll Reveal)核心类 ====== */
.fade-up-item {
  opacity: 0;
  transform: translateY(60px);
  transition: opacity 0.8s cubic-bezier(0.165, 0.84, 0.44, 1),
  transform 0.8s cubic-bezier(0.165, 0.84, 0.44, 1);
}

/* 当 IntersectionObserver 探测到元素进入视口时，会加上这个 class */
.fade-up-item.is-visible {
  opacity: 1;
  transform: translateY(0);
}

/* 移动端适配 */
@media (max-width: 768px) {
  .feature-row,
  .feature-row.reverse-layout {
    flex-direction: column;
    text-align: center;
    gap: 40px;
    margin-bottom: 80px;
  }

  .feature-text {
    max-width: 100%;
  }
}
</style>