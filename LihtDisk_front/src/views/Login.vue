<template>
  <div class="login-viewport">
    <nav class="login-nav">
      <div class="logo" @click="router.push('/')">LightDisk</div>
    </nav>

    <div class="login-content fade-up">
      <el-card class="login-card" shadow="never">
        <div class="card-header">
          <h2>登录到 LightDisk</h2>
          <p>请输入您的账号信息以继续</p>
        </div>

        <el-form :model="form" :rules="rules" ref="formRef" label-position="top">
          <el-form-item label="用户名" prop="username">
            <el-input
                v-model="form.username"
                placeholder="请输入用户名"
                size="large"
            />
          </el-form-item>

          <el-form-item label="密码" prop="password">
            <el-input
                v-model="form.password"
                type="password"
                placeholder="请输入密码"
                show-password
                size="large"
            />
          </el-form-item>

          <div class="form-options">
            <el-checkbox v-model="rememberMe">记住我</el-checkbox>
            <span class="forgot-pass">忘记密码？</span>
          </div>

          <el-button
              type="primary"
              class="btn-login"
              @click="handleLogin"
              :loading="loading"
          >
            登录
          </el-button>
        </el-form>

        <div class="divider">
          <span>或</span>
        </div>

        <div class="secondary-actions">
          <el-button class="btn-outline" plain size="large">
            使用 Google 账号登录
          </el-button>
          <p class="signup-tip">
            还没有账号？ <span class="link-text">立即创建一个账号</span>
          </p>
        </div>
      </el-card>

      <footer class="login-footer">
        <span>隐私政策</span>
        <span>服务条款</span>
        <span>© 2024 LightDisk</span>
      </footer>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'

const router = useRouter()
const loading = ref(false)
const formRef = ref(null)
const rememberMe = ref(false)

const form = reactive({
  username: '',
  password: '',
})

const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

const handleLogin = async () => {
  const valid = await formRef.value?.validate()
  if (!valid) return

  loading.value = true

  // 模拟请求延迟
  setTimeout(async () => {
    localStorage.setItem('token', 'mock-token')
    localStorage.setItem('userInfo', JSON.stringify({ username: form.username, role: 0 }))

    ElMessage({
      message: '登录成功，欢迎回来',
      type: 'success',
      duration: 2000
    })

    await router.push('/app/disk')
    loading.value = false
  }, 800)
}

// 入场动画处理
const isVisible = ref(false)
onMounted(() => {
  isVisible.value = true
})
</script>

<style scoped>
/* ====== 基础容器：与 Landing 保持一致的背景色 ====== */
.login-viewport {
  width: 100vw;
  height: 100vh;
  background-color: #f7f9fa;
  display: flex;
  flex-direction: column;
  align-items: center;
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
  overflow: hidden;
}

/* ====== 顶部导航风格 ====== */
.login-nav {
  width: 100%;
  padding: 24px 40px;
  display: flex;
  justify-content: flex-start;
}

.logo {
  font-size: 24px;
  font-weight: 800;
  color: #000;
  cursor: pointer;
  letter-spacing: -1px;
}

/* ====== 登录卡片容器 ====== */
.login-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  width: 100%;
  padding-bottom: 10vh;
}

.login-card {
  width: 100%;
  max-width: 440px;
  border: 1px solid #e5e7eb !important;
  border-radius: 4px; /* 遵循 SaaS 风格的微圆角 */
  padding: 20px;
}

.card-header {
  text-align: center;
  margin-bottom: 32px;
}

.card-header h2 {
  font-size: 28px;
  font-weight: 700;
  color: #000;
  margin-bottom: 8px;
}

.card-header p {
  color: #6b7280;
  font-size: 14px;
}

/* ====== 表单样式自定义 ====== */
:deep(.el-form-item__label) {
  font-weight: 600;
  color: #374151;
  padding-bottom: 4px;
}

:deep(.el-input__wrapper) {
  box-shadow: 0 0 0 1px #d1d5db inset;
  border-radius: 4px;
}

:deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 2px #0061ff inset !important;
}

.form-options {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 24px;
  font-size: 14px;
}

.forgot-pass {
  color: #0061ff;
  cursor: pointer;
}

/* ====== 按钮样式 ====== */
.btn-login {
  width: 100%;
  height: 48px;
  font-size: 16px;
  font-weight: 600;
  background-color: #0061ff;
  border-color: #0061ff;
  border-radius: 4px;
}

.btn-login:hover {
  background-color: #0052cc;
}

.divider {
  display: flex;
  align-items: center;
  margin: 32px 0;
  color: #9ca3af;
  font-size: 13px;
}

.divider::before, .divider::after {
  content: "";
  flex: 1;
  height: 1px;
  background-color: #e5e7eb;
}

.divider span {
  padding: 0 12px;
}

.btn-outline {
  width: 100%;
  height: 48px;
  border-radius: 4px;
  color: #374151;
  font-weight: 600;
}

.signup-tip {
  text-align: center;
  margin-top: 24px;
  font-size: 14px;
  color: #6b7280;
}

.link-text {
  color: #0061ff;
  font-weight: 600;
  cursor: pointer;
}

/* ====== 底部版权信息 ====== */
.login-footer {
  margin-top: 40px;
  display: flex;
  gap: 24px;
  color: #9ca3af;
  font-size: 12px;
}

/* ====== 入场动画 ====== */
.fade-up {
  animation: fadeUpIn 0.6s cubic-bezier(0.16, 1, 0.3, 1);
}

@keyframes fadeUpIn {
  from {
    opacity: 0;
    transform: translateY(20px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}
</style>