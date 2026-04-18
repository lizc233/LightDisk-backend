<template>
  <div class="landing-viewport" ref="viewportRef">

    <div class="dark-hero-wrapper">
      <div class="ambient-glow"></div>

      <header class="hero-section">
        <div class="hero-carousel-container">
          <swiper
              :modules="[Autoplay, Pagination, Navigation]"
              :slides-per-view="1.6"  :centered-slides="true"
              :space-between="30"
              :loop="true"
              :autoplay="{ delay: 5000, disableOnInteraction: false }"
              :pagination="{ clickable: true, dynamicBullets: true }"
              :navigation="true"
              class="hero-swiper"
          >
            <swiper-slide v-for="(item, index) in heroImages" :key="index">
              <div class="carousel-img-card">
                <div
                    class="img-content"
                    :style="{ backgroundImage: item.url ? `url(${item.url})` : '' }"
                >
                  <span v-if="!item.url" class="placeholder-text">高端配图占位 - {{ item.id }}</span>
                </div>
                <div class="img-bottom-gradient"></div>
              </div>
            </swiper-slide>
          </swiper>
        </div>

        <div class="slogan-container">
          <p class="hero-slogan">
            提供新一代云端数字资产枢纽<br/>帮助您轻松协作、更快捷地推进工作并确保绝对安全
          </p>
        </div>

        <div class="cta-group">
          <button class="btn-primary" @click="goToLogin">
            免费体验 LightDisk
          </button>
          <button class="btn-secondary">了解更多 <el-icon><Right /></el-icon></button>
        </div>

        <div class="trust-banner fade-up-item">
          <p class="trust-title">加入超过 7 亿信赖 LightDisk 的注册用户行列</p>
          <div class="logo-group">
            <span class="logo-placeholder">ZOOM</span>
            <span class="logo-placeholder">McLaren</span>
            <span class="logo-placeholder">Hydro Flask</span>
            <span class="logo-placeholder">Dropbox</span>
            <span class="logo-placeholder">Slack</span>
          </div>
        </div>
      </header>

      <div class="theme-transition-divider"></div>
    </div>

    <main class="details-section">
      <div class="section-title-wrapper fade-up-item">
        <h2 class="section-main-title">工作事半功倍</h2>
        <div class="title-underline"></div>
      </div>

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

import { Swiper, SwiperSlide } from 'swiper/vue'
import { Autoplay, Pagination, Navigation } from 'swiper/modules'
import 'swiper/css'
import 'swiper/css/pagination'
import 'swiper/css/navigation'

const router = useRouter()
const viewportRef = ref(null)

// ====== 核心修改：复制一遍数据以完美解决 Swiper 首尾显示 Bug ======
const baseImages = [
  { id: 1, url: '/images/pictur1.png' },
  { id: 2, url: '/images/pic3.png' },
  { id: 3, url: '/images/pic2.png' }
]
// 将 3 张图扩展成 6 张，避免 loop 时 DOM 不够用
const heroImages = ref([...baseImages, ...baseImages])

const features = ref([
  { title: '弹性微服务调度', desc: '基于云原生的底层架构，突破物理设备的性能壁垒。无论是毫秒级的秒传还是超大文件的并发分块，一切都如光速般丝滑。', icon: markRaw(Lightning) },
  { title: '军工级加密隔离', desc: '零信任架构。每一字节的数字资产在落盘前即完成 AES-256 加密。在这个绝对安全的黑盒中，只有你能触碰你的数据。', icon: markRaw(Lock) },
  { title: '无缝的多端生态', desc: '打破设备与时间的孤岛。你的工作流将在浏览器、桌面和移动端之间实时跃迁，重塑下一代数字生产力空间。', icon: markRaw(Connection) }
])

let observer = null

onMounted(() => {
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
  if (observer) observer.disconnect()
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
  overflow-y: auto;
  overflow-x: hidden;
  background-color: #f7f9fa;
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
  scroll-behavior: smooth;
}

/* ====== 新增：深色首屏包裹层 ====== */
.dark-hero-wrapper {
  background-color: #000000;
  position: relative;
  width: 100%;
  min-height: 100vh; /* 确保最小高度是一屏 */
  display: flex;
  flex-direction: column;
  justify-content: space-between; /* 让内容和底部的过渡线自动撑开 */
}

/* 柔和的环境光，替代之前的脏灰色段 */
.ambient-glow {
  position: absolute;
  top: 10%;
  left: 50%;
  transform: translateX(-50%);
  width: 80vw;
  height: 40vh;
  background: radial-gradient(circle, rgba(0, 97, 255, 0.15) 0%, rgba(0, 0, 0, 0) 70%);
  pointer-events: none;
  z-index: 0;
}
/* ====== 首屏区域 ====== */
.hero-section {
  position: relative;
  z-index: 10;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center; /* 垂直居中对齐 */
  width: 100%;
  flex: 1; /* 占据除过渡线外的所有可用空间 */
  padding: 4vh 20px 0; /* 减小了顶部的 padding */
  max-width: 1200px;
  margin: 0 auto;
}

/* ====== 轮播图区域 ====== */
.hero-carousel-container {
  width: 100%;
  position: relative;
  overflow: visible;
}
.hero-swiper {
  width: 100%;
  height: 38vh; /* 使用 vh 相对高度，防止在小屏幕上显得太巨型，代替原来的 480px */
  min-height: 320px;
  max-height: 420px;
  overflow: visible !important;
  margin: 0 auto;
}
.hero-swiper .swiper-slide {
  /*  width: 60%; 从 70% 缩小到 60%，解决偏左问题，让左右露出的部分更对称 */
  /* max-width: 800px; 增加最大宽度限制 */
  height: 100%;
  transition: all 0.4s cubic-bezier(0.25, 0.8, 0.25, 1);
  opacity: 0.3;
  transform: scale(0.92);
}
.hero-swiper .swiper-slide-active {
  opacity: 1;
  transform: scale(1);
}

.carousel-img-card {
  position: relative;
  width: 100%;
  height: 100%;
  border-radius: 20px;
  overflow: hidden;
  box-shadow: 0 30px 60px rgba(0,0,0,0.5);
}

.img-content {
  width: 100%;
  height: 100%;
  background-size: cover;
  background-position: center;
}

.img-bottom-gradient {
  position: absolute;
  bottom: 0;
  left: 0;
  width: 100%;
  height: 50%;
  background: linear-gradient(180deg, rgba(0,0,0,0) 0%, rgba(0,0,0,1) 100%);
  pointer-events: none;
}

:deep(.swiper-pagination) { bottom: 20px !important; }
:deep(.swiper-button-prev),
:deep(.swiper-button-next) {
  color: white;
  background: rgba(255,255,255,0.1);
  backdrop-filter: blur(4px);
  width: 48px;
  height: 48px;
  border-radius: 50%;
  transition: background 0.3s;
}
:deep(.swiper-button-prev:hover),
:deep(.swiper-button-next:hover) { background: rgba(255,255,255,0.2); }
:deep(.swiper-button-prev:after),
:deep(.swiper-button-next:after) { font-size: 18px; }

/* ====== 重绘 Slogan ====== */
.slogan-container {
  margin: 10px 0 30px 0; /* 大幅缩小了 Margin，腾出垂直空间 */
  position: relative;
  z-index: 20;
}
.hero-slogan {
  font-size: clamp(1.1rem, 1.8vw, 1.3rem); /* 稍微调小一点字体 */
  line-height: 1.8;
  text-align: center;
  font-weight: 400;
  background: linear-gradient(180deg, #ffffff 0%, #a1a1aa 100%);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  max-width: 650px;
  letter-spacing: 1px;
}

/* ====== 按钮组 ====== */
.cta-group {
  display: flex;
  gap: 20px;
  margin-bottom: 30px; /* 从 80px 减小到 30px */
}
.btn-primary {
  background-color: #0061ff;
  color: #ffffff;
  border: none;
  padding: 14px 32px; /* 稍微调小按钮高度 */
  border-radius: 100px;
  font-size: 1.05rem;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.3s ease;
  box-shadow: 0 10px 25px rgba(0, 97, 255, 0.4);
}
.btn-primary:hover {
  background-color: #0052cc;
  transform: translateY(-2px);
  box-shadow: 0 15px 35px rgba(0, 97, 255, 0.5);
}
.btn-secondary {
  background: transparent;
  border: 1px solid rgba(255,255,255,0.2);
  color: #ffffff;
  padding: 13px 28px;
  border-radius: 100px;
  font-size: 1.05rem;
  font-weight: 600;
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 8px;
  transition: all 0.3s;
}
.btn-secondary:hover { background: rgba(255,255,255,0.1); }

/* ====== 信任背书区 ====== */
.trust-banner {
  text-align: center;
  width: 100%;
  padding: 10px 0 30px; /* 大幅减小 Padding */
}
.trust-title {
  color: rgba(255, 255, 255, 0.4);
  font-size: 0.85rem;
  margin-bottom: 20px; /* 减小下边距 */
  text-transform: uppercase;
  letter-spacing: 2px;
}
.logo-group {
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 5vw;
  flex-wrap: wrap;
}
.logo-placeholder {
  font-size: 1.4rem;
  font-weight: 800;
  color: rgba(255, 255, 255, 0.3);
  transition: color 0.3s ease;
  cursor: default;
}
.logo-placeholder:hover {
  color: rgba(255, 255, 255, 0.9);
}

/* ====== 明暗交接过渡线 ====== */
.theme-transition-divider {
  width: 100%;
  height: 60px; /* 从 80px 减小到 60px */
  background: linear-gradient(180deg, #000000 0%, #f7f9fa 100%);
}

/* ====== 详细信息滚动区块 (保持不变) ====== */
.details-section {
  background-color: #f7f9fa;
  position: relative;
  z-index: 10;
  padding: 40px 40px 100px;
  color: #000;
}
.section-title-wrapper { text-align: center; margin-bottom: 100px; }
.section-main-title { font-size: clamp(2.5rem, 5vw, 3.5rem); font-weight: 800; margin: 0; }
.title-underline { width: 60px; height: 4px; background-color: #0061ff; margin: 24px auto 0; border-radius: 2px; }

.feature-row { display: flex; align-items: center; justify-content: space-between; gap: 60px; margin-bottom: 120px; max-width: 1100px; margin-left: auto; margin-right: auto;}
.feature-row.reverse-layout { flex-direction: row-reverse; }
.feature-text { flex: 1; max-width: 500px; }
.feature-text h3 { font-size: 2.2rem; font-weight: 700; margin-bottom: 24px; }
.feature-text p { font-size: 1.15rem; color: #555555; line-height: 1.7; margin-bottom: 24px; }
.learn-more { display: inline-flex; align-items: center; gap: 8px; color: #0061ff; font-weight: 600; text-decoration: none; font-size: 1.1rem; }
.learn-more:hover { text-decoration: underline; }

.feature-visual { flex: 1; display: flex; justify-content: center; }
.mock-ui-card { width: 100%; max-width: 500px; aspect-ratio: 4/3; background-color: #ffffff; border-radius: 16px; box-shadow: 0 20px 40px rgba(0, 0, 0, 0.05); display: flex; align-items: center; justify-content: center; transition: transform 0.3s ease; border: 1px solid rgba(0,0,0,0.05);}
.mock-ui-card:hover { transform: translateY(-10px); box-shadow: 0 30px 60px rgba(0, 0, 0, 0.08); }
.huge-icon { font-size: 80px; color: #0061ff; opacity: 0.8; }

.fade-up-item { opacity: 0; transform: translateY(50px); transition: opacity 0.8s ease-out, transform 0.8s ease-out; }
.fade-up-item.is-visible { opacity: 1; transform: translateY(0); }

@media (max-width: 768px) {
  .hero-swiper { height: 280px; }
  .feature-row, .feature-row.reverse-layout { flex-direction: column; text-align: center; gap: 40px; margin-bottom: 80px; }
  .feature-text { max-width: 100%; }
}
</style>