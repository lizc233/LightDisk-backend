<template>
  <div class="login-container">
    <el-card class="login-card">
      <h2>LightDisk · 轻量云盘</h2>
      <el-form :model="form" :rules="rules" ref="formRef">
        <el-form-item prop="username">
          <el-input v-model="form.username" placeholder="用户名" prefix-icon="User" />
        </el-form-item>
        <el-form-item prop="password">
          <el-input v-model="form.password" type="password" placeholder="密码" prefix-icon="Lock" />
        </el-form-item>
        <el-button type="primary" @click="handleLogin" :loading="loading" style="width: 100%">登录</el-button>
        <div class="demo-tip">演示账号：任意输入即可登录</div>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'

const router = useRouter()
const loading = ref(false)
const formRef = ref(null)

const form = reactive({
  username: '',
  password: '',
})

const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

const handleLogin = async () => {
  await formRef.value?.validate()
  loading.value = true

  // 直接操作 localStorage
  localStorage.setItem('token', 'mock-token')
  localStorage.setItem('userInfo', JSON.stringify({ username: form.username, role: 0 }))

  ElMessage.success('登录成功')
  await router.push('/disk')
  loading.value = false
}
</script>

<style scoped>
.login-container {
  display: flex;
  justify-content: center;
  align-items: center;
  height: 100vh;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
}
.login-card {
  width: 400px;
  text-align: center;
}
.login-card h2 {
  margin-bottom: 24px;
  color: #333;
}
.demo-tip {
  margin-top: 16px;
  font-size: 12px;
  color: #999;
}
</style>