<template>
  <div class="login-viewport">
    <div class="visual-panel">
      <div class="dark-base"></div>
      <div class="ripple ripple-1"></div>
      <div class="ripple ripple-2"></div>
      <div class="ripple ripple-3"></div>

      <transition-group name="abstract-fade" tag="div" class="image-slider">
        <div
            v-for="(img, index) in bgImages"
            :key="img.id"
            v-show="currentIndex === index"
            class="abstract-image-wrapper"
        >
          <div
              class="bg-image"
              :style="{ backgroundImage: img.url ? `url(${img.url})` : '' }"
              :class="{ 'is-active': currentIndex === index }"
          >
            <span v-if="!img.url" class="placeholder-text">抽象配图占位 - {{ img.id }}</span>
          </div>
        </div>
      </transition-group>

      <div class="brand-logo" @click="router.push('/')">LightDisk</div>
    </div>

    <div class="form-panel">
      <div class="login-card fade-up">
        <div class="card-header">
          <h2>创建 <span class="highlight">新账号</span></h2>
          <p>加入 LightDisk，开启您的数字云端之旅</p>
        </div>

        <el-form :model="registerForm" :rules="rules" ref="formRef" label-position="top">
          <el-form-item label="用户名" prop="username">
            <el-input v-model="registerForm.username" placeholder="请设置用户名"/>
          </el-form-item>

          <el-form-item label="设置密码" prop="password">
            <el-input v-model="registerForm.password" type="password" placeholder="请设置登录密码" show-password/>
          </el-form-item>

          <el-form-item label="确认密码" prop="confirmPassword">
            <el-input v-model="registerForm.confirmPassword" type="password" placeholder="请再次输入密码" show-password/>
          </el-form-item>

          <el-button type="primary" class="btn-login" @click="handleRegister" :loading="loading">
            立即注册
          </el-button>
        </el-form>

        <div class="secondary-actions">
          <div class="auth-footer">
            <span class="tip-text">已有账号？</span>
            <el-link type="primary" underline="never" @click="router.push('/login')">返回登录</el-link>
          </div>
        </div>

        <footer class="login-footer">
          <a href="#">隐私政策</a>
          <span>·</span>
          <a href="#">服务条款</a>
          <span>·</span>
          <span>© 2024 LightDisk</span>
        </footer>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, onUnmounted } from 'vue'
import axios from 'axios'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'

const router = useRouter()
const formRef = ref(null)
const loading = ref(false)

// --- 同步 Login.vue 的图片轮播逻辑 ---
const bgImages = ref([
  {id: 1, url: '/images/pic4.png'},
  {id: 2, url: '/images/pic5.png'},
  {id: 3, url: '/images/pic6.png'}
])
const currentIndex = ref(0)
let sliderTimer = null

onMounted(() => {
  sliderTimer = setInterval(() => {
    if (bgImages.value.length > 0) {
      currentIndex.value = (currentIndex.value + 1) % bgImages.value.length
    }
  }, 6000)
})

onUnmounted(() => {
  if (sliderTimer) clearInterval(sliderTimer)
})

// --- 注册业务逻辑 ---
const registerForm = reactive({
  username: '',
  password: '',
  confirmPassword: ''
})

const rules = {
  username: [
    { required: true, message: '请起一个响亮的用户名', trigger: 'blur' },
    { min: 3, max: 20, message: '长度要在 3 到 20 个字符之间', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '密码是必须的', trigger: 'blur' },
    { min: 6, message: '密码太短了，至少要 6 位才安全', trigger: 'blur' } // 这里解决了你说的“密码太短”提示
  ],
  confirmPassword: [
    { required: true, message: '请再次输入密码', trigger: 'blur' },
    {
      validator: (rule, value, callback) => {
        // 关键点：这里必须使用你定义的 registerForm 变量名
        if (value !== registerForm.password) {
          callback(new Error('两次输入密码不一致'))
        } else {
          callback()
        }
      },
      trigger: 'blur'
    }
  ]
}

const handleRegister = async () => {
  try {
    // 1. 触发前端校验
    await formRef.value.validate()

    // 2. 这里的赋值改对了！不是 .push，是直接 = true
    loading.value = true

    const res = await axios.post('http://localhost:8080/api/register', {
      username: registerForm.username,
      password: registerForm.password
    })

    // 3. 处理成功逻辑
    if (res.data.code === 1) {
      ElMessage.success('账号创建成功！')
      setTimeout(() => router.push('/login'), 1500)
    } else {
      // 如果后端没弹异常而是返回了错误码
      const errorText = res.data.msg || res.data.message || '注册失败';
      ElMessage.error(errorText);

      console.log("后端返回的原始错误信息:", res.data.msg);
    }

  } catch (error) {
    // --- 这里的逻辑是抓取你后端 BusinessException 的核心 ---
    if (error.response && error.response.data) {
      // ✅ 抓取 GlobalExceptionHandler 抛出的那个“该用户名已被占用”
      const serverMsg = error.response.data.message || error.response.data.msg || '服务器业务异常';
      ElMessage.error(serverMsg);
      console.error("抓到后端业务报错:", serverMsg);
    } else if (error.name === 'ValidationError') {
      // 仅仅是前端表单校验（如密码太短）没过，不需要额外弹窗，输入框下有红字
      console.warn("前端校验未通过");
    } else {
      // 其他网络错误（如后端没开）
      ElMessage.error('网络连接异常，请检查后端服务');
      console.error("未分类异常:", error);
    }
  } finally {
    // 4. 无论成功失败，关掉按钮加载状态
    loading.value = false
  }
}
</script>

<style scoped>
/* ============================================================
   以下样式 1:1 完美搬运自 Login.vue，确保视觉无缝切换
   ============================================================ */
.login-viewport {
  width: 100vw; height: 100vh; display: flex; flex-direction: row;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
  overflow: hidden; background-color: #ffffff;
}

/* 左侧视觉区 */
.visual-panel { flex: 6; position: relative; background-color: #0a0a0a; overflow: hidden; display: flex; align-items: center; justify-content: center; }
.dark-base { position: absolute; top: 0; left: 0; width: 100%; height: 100%; background: linear-gradient(135deg, #0f1115 0%, #050505 100%); z-index: 0; }

/* 动态几何涟漪底层 */
.ripple { position: absolute; top: 50%; left: 50%; transform: translate(-50%, -50%); border: 1px solid rgba(255, 255, 255, 0.05); border-radius: 40% 60% 70% 30% / 40% 50% 60% 50%; z-index: 1; }
.ripple-1 { width: 80vh; height: 80vh; animation: morph-rotate 25s linear infinite; background: rgba(255, 255, 255, 0.02); }
.ripple-2 { width: 60vh; height: 60vh; animation: morph-rotate 20s linear infinite reverse; border: 1px solid rgba(0, 97, 255, 0.1); }
.ripple-3 { width: 100vh; height: 100vh; animation: morph-rotate 35s linear infinite; }

@keyframes morph-rotate {
  0% { transform: translate(-50%, -50%) rotate(0deg); border-radius: 40% 60% 70% 30% / 40% 50% 60% 50%; }
  34% { border-radius: 70% 30% 50% 50% / 30% 30% 70% 70%; }
  67% { border-radius: 100% 60% 60% 100% / 100% 100% 60% 60%; }
  100% { transform: translate(-50%, -50%) rotate(360deg); border-radius: 40% 60% 70% 30% / 40% 50% 60% 50%; }
}

/* 图片遮罩与切割 */
.image-slider { position: absolute; top: 0; left: 0; width: 100%; height: 100%; z-index: 5; }
.abstract-image-wrapper { position: absolute; top: 0; left: 0; width: 100%; height: 100%; -webkit-mask-image: -webkit-linear-gradient(345deg, rgba(0, 0, 0, 1) 40%, rgba(0, 0, 0, 0) 80%); mask-image: linear-gradient(105deg, rgba(0, 0, 0, 1) 40%, rgba(0, 0, 0, 0) 80%); }
.bg-image { width: 100%; height: 100%; background-size: cover; background-position: center; transition: transform 6s ease-out; }
.bg-image.is-active { transform: scale(1.08); }
.brand-logo { position: absolute; top: 24px; left: 24px; font-size: 24px; font-weight: 700; color: #ffffff; cursor: pointer; z-index: 10; }

/* 右侧表单区 */
.form-panel { flex: 4; background-color: #ffffff; display: flex; align-items: center; justify-content: center; padding: 0 40px; overflow-y: auto; z-index: 10; }
.login-card { width: 100%; max-width: 360px; padding: 40px 0; }
.card-header h2 { font-size: 32px; font-weight: 700; color: #1D1D1F; margin-bottom: 12px; }
.highlight { color: #007AFF; }
.card-header p { color: #6E6E73; font-size: 14px; }

/* Element Plus 覆写 */
:deep(.el-form-item) { margin-bottom: 24px; }
:deep(.el-form-item__label) { font-size: 14px; font-weight: 500; color: #424245; padding-bottom: 8px; line-height: 1; }
:deep(.el-input__wrapper) { height: 44px; border-radius: 8px; box-shadow: 0 0 0 1px #E5E5E7 inset; }
:deep(.el-input__wrapper.is-focus) { box-shadow: 0 0 0 1px #007AFF inset, 0 0 0 3px rgba(0, 122, 255, 0.2) !important; }

.btn-login { width: 100%; height: 48px; font-size: 16px; font-weight: 600; color: #ffffff; background-color: #007AFF; border: none; border-radius: 8px; margin-top: 10px; }
.auth-footer { margin-top: 24px; text-align: center; font-size: 14px; }
.tip-text { color: #637282; margin-right: 8px; }
.login-footer { margin-top: 40px; display: flex; gap: 8px; color: #86868B; font-size: 12px; justify-content: center; }

/* 入场动画 */
.fade-up { animation: fadeUpIn 0.8s cubic-bezier(0.16, 1, 0.3, 1) forwards; opacity: 0; transform: translateY(20px); }
@keyframes fadeUpIn { to { opacity: 1; transform: translateY(0); } }

.abstract-fade-enter-active, .abstract-fade-leave-active { transition: opacity 1.5s ease-in-out; }
.abstract-fade-enter-from, .abstract-fade-leave-to { opacity: 0; }
</style>