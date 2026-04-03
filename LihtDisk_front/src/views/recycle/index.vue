<template>
  <div class="recycle-container">
    <div class="toolbar">
      <div class="toolbar-left">
        <el-input
            v-model="searchKeyword"
            placeholder="搜索回收站文件..."
            prefix-icon="Search"
            clearable
            class="search-input"
            @keyup.enter="fetchData"
        />

        <el-button
            type="primary"
            plain
            icon="RefreshLeft"
            :disabled="selectedFiles.length === 0"
            @click="handleBatchRestore"
        >
          批量还原
        </el-button>
        <el-button
            type="danger"
            icon="Delete"
            :disabled="selectedFiles.length === 0"
            @click="handleBatchDelete"
        >
          彻底删除
        </el-button>
      </div>

      <div class="toolbar-right">
        <el-radio-group v-model="viewMode" size="default">
          <el-radio-button label="table" title="列表视图">
            <el-icon><Menu /></el-icon>
          </el-radio-button>
          <el-radio-button label="grid" title="网格视图">
            <el-icon><Grid /></el-icon>
          </el-radio-button>
        </el-radio-group>
      </div>
    </div>

    <div class="main-content" v-loading="loading" element-loading-text="拼命加载中...">

      <el-empty
          v-if="fileList.length === 0 && !loading"
          description="回收站空空如也，就像你的钱包一样"
          image="https://cdn-icons-png.flaticon.com/512/7694/7694503.png"
          :image-size="150"
      />

      <template v-else>
        <el-table
            v-if="viewMode === 'table'"
            :data="filteredList"
            style="width: 100%"
            @selection-change="handleSelectionChange"
        >
          <el-table-column type="selection" width="55" />

          <el-table-column label="文件名" min-width="200" sortable prop="name">
            <template #default="{ row }">
              <div class="file-name-col">
                <el-icon size="20" class="file-icon"><component :is="getFileIcon(row)" /></el-icon>
                <span>{{ row.name }}</span>
              </div>
            </template>
          </el-table-column>

          <el-table-column label="大小" width="120" sortable prop="size">
            <template #default="{ row }">{{ formatSize(row.size) }}</template>
          </el-table-column>

          <el-table-column label="类型" width="100" prop="type" />

          <el-table-column label="删除时间" width="180" sortable prop="deleteTime" />

          <el-table-column label="剩余有效期" width="120" sortable prop="expireDays">
            <template #default="{ row }">
              <span :class="{'danger-text': row.expireDays <= 3}">
                {{ row.expireDays }} 天
              </span>
            </template>
          </el-table-column>

          <el-table-column label="操作" width="180" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" icon="RefreshLeft" @click="handleRestore(row)">还原</el-button>
              <el-button link type="danger" icon="Delete" @click="handleDelete(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>

        <div v-else class="grid-view">
          <el-card
              v-for="item in filteredList"
              :key="item.id"
              class="file-card"
              shadow="hover"
          >
            <el-checkbox class="card-checkbox" v-model="item.selected" @change="syncGridSelection" />

            <div class="card-image">
              <img v-if="item.type === 'image'" :src="item.thumbUrl" alt="缩略图" />
              <img v-else-if="item.type === 'pdf'" src="https://cdn-icons-png.flaticon.com/512/3143/3143460.png" alt="PDF" />
              <el-icon v-else size="50" color="#909399"><Document /></el-icon>
            </div>

            <div class="card-info">
              <div class="card-name" :title="item.name">{{ item.name }}</div>
              <div class="card-meta">剩 {{ item.expireDays }} 天</div>
            </div>

            <div class="card-actions">
              <el-button circle type="primary" icon="RefreshLeft" @click="handleRestore(item)" title="还原" />
              <el-button circle type="danger" icon="Delete" @click="handleDelete(item)" title="彻底删除" />
            </div>
          </el-card>
        </div>
      </template>

    </div>
  </div>
</template>

<script setup>
import {ref, computed, onMounted} from 'vue'
import {ElMessage, ElMessageBox} from 'element-plus'
// 引入图标
import {Search, RefreshLeft, Delete, Menu, Grid, Document, Picture, VideoCamera} from '@element-plus/icons-vue'

// ==== 状态定义 ====
const loading = ref(false)
const searchKeyword = ref('')
const viewMode = ref('table') // 'table' 或 'grid'
const selectedFiles = ref([])

// 模拟后端数据 (你可以替换成真实的 axios 请求)
const fileList = ref([
  {
    id: 1,
    name: '项目架构图.png',
    size: 2560000,
    type: 'image',
    deleteTime: '2023-10-25 10:00',
    expireDays: 2,
    thumbUrl: 'https://images.unsplash.com/photo-1618401471353-b98afee0b2eb?auto=format&fit=crop&w=200&q=80',
    selected: false
  },
  {
    id: 2,
    name: 'Q3财务报表.pdf',
    size: 1048576,
    type: 'pdf',
    deleteTime: '2023-10-26 14:30',
    expireDays: 15,
    selected: false
  },
  {
    id: 3,
    name: '后端接口文档.docx',
    size: 512000,
    type: 'doc',
    deleteTime: '2023-10-20 09:15',
    expireDays: 7,
    selected: false
  },
])

// ==== 计算属性 ====
// 前端本地搜索过滤
const filteredList = computed(() => {
  if (!searchKeyword.value) return fileList.value
  return fileList.value.filter(item => item.name.toLowerCase().includes(searchKeyword.value.toLowerCase()))
})

// ==== 方法定义 ====

// 模拟获取数据
const fetchData = () => {
  loading.value = true
  setTimeout(() => {
    loading.value = false
  }, 800) // 假装请求了0.8秒
}

// 表格多选事件
const handleSelectionChange = (val) => {
  selectedFiles.value = val
}

// 网格模式下的多选同步
const syncGridSelection = () => {
  selectedFiles.value = fileList.value.filter(item => item.selected)
}

// 获取文件图标 (根据类型)
const getFileIcon = (row) => {
  if (row.type === 'image') return 'Picture'
  if (row.type === 'video') return 'VideoCamera'
  return 'Document'
}

// 格式化文件大小
const formatSize = (bytes) => {
  if (bytes === 0) return '0 B'
  const k = 1024
  const sizes = ['B', 'KB', 'MB', 'GB', 'TB']
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  return (bytes / Math.pow(k, i)).toPrecision(3) + ' ' + sizes[i]
}

// --- 操作类 ---

// 单个还原
const handleRestore = (row) => {
  ElMessage.success(`已还原文件：${row.name}`)
  // TODO: 调用后端接口
  fileList.value = fileList.value.filter(item => item.id !== row.id)
}

// 单个删除
const handleDelete = (row) => {
  ElMessageBox.confirm(
      `确定要彻底删除 [${row.name}] 吗？操作不可逆！`,
      '危险警告',
      {confirmButtonText: '灰飞烟灭', cancelButtonText: '手滑了', type: 'error'}
  ).then(() => {
    ElMessage.success('文件已彻底删除')
    fileList.value = fileList.value.filter(item => item.id !== row.id)
  }).catch(() => {
  })
}

// 批量还原
const handleBatchRestore = () => {
  ElMessage.success(`成功还原 ${selectedFiles.value.length} 个文件`)
  // TODO: 传 selectedFiles.value.map(i => i.id) 给后端
}

// 批量删除
const handleBatchDelete = () => {
  ElMessageBox.confirm(
      `确定要清空选中的 ${selectedFiles.value.length} 个文件吗？此操作不可挽回！`,
      '批量删除警告',
      {confirmButtonText: '全部删除', cancelButtonText: '再想想', type: 'error'}
  ).then(() => {
    ElMessage.success('选中文件已全部清理')
    // TODO: 调用后端接口
  }).catch(() => {
  })
}

// 初始化加载
onMounted(() => {
  fetchData()
})
</script>

<style scoped>
.recycle-container {
  padding: 20px;
  background-color: #fff;
  min-height: calc(100vh - 40px); /* 假设没有顶部导航的高度 */
}

/* 顶部工具栏 */
.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}

.toolbar-left {
  display: flex;
  gap: 15px;
}

.search-input {
  width: 250px;
}

/* 表格列图标 */
.file-name-col {
  display: flex;
  align-items: center;
  gap: 8px;
}

.file-icon {
  color: #409EFF;
}

.danger-text {
  color: #F56C6C;
  font-weight: bold;
}

/* 网格视图 (卡片式) */
.grid-view {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
  gap: 20px;
  padding: 10px 0;
}

.file-card {
  position: relative;
  text-align: center;
  cursor: pointer;
  transition: all 0.3s ease;
}

.file-card:hover {
  transform: translateY(-5px);
}

.card-checkbox {
  position: absolute;
  top: 8px;
  left: 8px;
  z-index: 10;
}

.card-image {
  height: 100px;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 10px;
  overflow: hidden;
  border-radius: 4px;
}

.card-image img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.card-info {
  line-height: 1.5;
}

.card-name {
  font-size: 13px;
  color: #303133;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.card-meta {
  font-size: 12px;
  color: #F56C6C;
}

/* 悬浮操作栏 */
.card-actions {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100px;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  opacity: 0;
  transition: opacity 0.3s ease;
  border-radius: 4px 4px 0 0;
}

.file-card:hover .card-actions {
  opacity: 1;
}
</style>