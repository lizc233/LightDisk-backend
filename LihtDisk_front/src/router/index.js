import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  { path: '/', redirect: '/disk' },
  { path: '/login', component: () => import('@/views/Login.vue') },
  {
    path: '/disk',
    component: () => import('@/views/Disk.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/recycle',
    name: 'Recycle',
    component: () => import('@/views/recycle/index.vue')
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach((to, from, next) => {
  const token = localStorage.getItem('token')
  if (to.meta.requiresAuth && !token) {
    next('/login')
  } else {
    next()
  }
})

export default router