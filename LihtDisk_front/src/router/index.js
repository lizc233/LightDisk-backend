import { createRouter, createWebHistory } from 'vue-router'
import BasicLayout from '../layouts/BasicLayout.vue'

const routes = [
  {
    path: '/', // 根路径，对应浏览器直接访问 http://localhost:5173/
    name: 'Landing',
    component: () => import('@/views/Landing.vue'),
    meta: {
      requiresAuth: false, // 首页不需要鉴权
      layout: 'empty' // 或者别的，只要不使用 BasicLayout 即可
    }
  },
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/Login.vue')
  },
  {
    path: '/register',
    // 确保这里的路径和你 Register.vue 的实际存放位置一致
    component: () => import('../views/Register.vue')
  },
  {
    path: '/app', // 网盘系统入口
    component: BasicLayout,
    meta: { requiresAuth: true }, // 需要登录
    children: [
      {
        path: 'disk',  // 访问路径是 http://localhost:5173/app/disk
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

// 全局路由守卫
router.beforeEach((to, from, next) => {
  const token = localStorage.getItem('token')

  if (to.matched.some(record => record.meta.requiresAuth) && !token) {
    // 没登录，踢回登录页
    next('/login')
  } else {
    // 已登录或访问不需要权限的页面，放行
    next()
  }
})

export default router