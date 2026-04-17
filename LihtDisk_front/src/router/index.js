import { createRouter, createWebHistory } from 'vue-router'
import BasicLayout from '../layouts/BasicLayout.vue'
import { ref, reactive } from 'vue'
import axios from 'axios'
import { ElMessage } from 'element-plus'


const shareDialogVisible = ref(false)
const sharing = ref(false)
const currentFile = ref(null) // 当前选中的文件行
const shareResult = ref(null) // 存储后端返回的分享信息

const routes = [
  {
    path: '/',
    redirect: '/disk'
  },
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/Login.vue')
  },
  {
    path: '/',
    component: BasicLayout,
    // 将权限校验加在父组件上，这样它所有的 children 都需要登录才能访问
    meta: { requiresAuth: true },
    children: [
      {
        path: 'disk',
        name: 'Disk',
        component: () => import('@/views/Disk.vue')
      },
      {
        path: 'share',
        name: 'Share',
        component: () => import('@/views/share/index.vue')
      },
      {
        path: 'recycle',
        name: 'Recycle',
        component: () => import('@/views/recycle/index.vue')
      },
      {
        path: 'admin',
        name: 'Admin',
        component: () => import('@/views/admin/index.vue')
      }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

const shareForm = reactive({
  expireType: 1 // 默认为7天
})

// --- 打开分享弹窗 ---
const handleShare = (row) => {
  currentFile.value = row
  shareResult.value = null // 重置结果
  shareDialogVisible.value = true
}

// --- 调用后端接口创建链接 ---
const createShareLink = async () => {
  if (!currentFile.value) return

  sharing.value = true
  try {
    const res = await axios.post('http://localhost:8080/share/create', {
      fileId: currentFile.value.id,
      expireType: shareForm.expireType
    })

    // 假设后端返回的数据结构为 { shareUrl: '...', extractCode: '...' }
    shareResult.value = res.data
    ElMessage.success('链接创建成功')
  } catch (error) {
    ElMessage.error('创建分享失败，请检查网络或后端状态')
    console.error(error)
  } finally {
    sharing.value = false
  }
}

// --- 一键复制功能 ---
const copyText = (text) => {
  navigator.clipboard.writeText(text).then(() => {
    ElMessage.success('已复制到剪贴板')
  }).catch(() => {
    ElMessage.error('复制失败，请手动选择复制')
  })
}

// 保留你写好的路由守卫！
router.beforeEach((to, from, next) => {
  const token = localStorage.getItem('token')

  // 使用 to.matched.some 可以确保即使访问子路由，也能识别到父路由上的 requiresAuth
  if (to.matched.some(record => record.meta.requiresAuth) && !token) {
    // 没登录，踢回登录页
    next('/login')
  } else {
    // 已登录或访问不需要权限的页面，放行
    next()
  }
})

export default router