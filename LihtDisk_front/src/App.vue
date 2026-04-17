<template>
  <router-view />
</template>

<script setup>
// 这里不需要写逻辑，纯粹作为路由出口
</script>

<style>
/* 保留你的全局重置样式 */
* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

/* 关键追加：确保根节点占满全屏，否则布局无法 100% 撑开 */
html, body, #app {
  height: 100%;
}

body {
  font-family: 'Segoe UI', 'Helvetica Neue', Arial, sans-serif;
  background-color: #f5f5f5;
  /* 防止页面出现不必要的全局滚动条 */
  overflow: hidden;
}
</style>