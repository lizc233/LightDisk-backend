<template>
  <div style="padding: 50px; text-align: center;">
    <h1>网盘主界面</h1>
    <p>如果看到这段文字，说明跳转成功！</p>
    <button @click="logout">退出登录</button>
  </div>
</template>

<script setup>
import { useRouter } from 'vue-router'

const router = useRouter()

const logout = () => {
  localStorage.removeItem('token')
  localStorage.removeItem('userInfo')
  router.push('/login')
}
</script>