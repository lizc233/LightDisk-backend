<template>
  <div class="dropbox-container">
    <div class="sub-sidebar">
      <div class="sidebar-header">
        <h2 class="title">所有文件</h2>
      </div>
      <nav class="nav-group">
        <div class="nav-item active">
          <el-icon><Folder /></el-icon> <span>全部文件</span>
        </div>
      </nav>

      <div class="nav-divider"></div>

      <div class="nav-section">
        <h3 class="nav-label">快速筛选</h3>
        <nav class="nav-group">
          <div class="nav-item"><el-icon><Document /></el-icon> <span>PDF 文档</span></div>
          <div class="nav-item"><el-icon><VideoCamera /></el-icon> <span>视频文件</span></div>
          <div class="nav-item"><el-icon><Picture /></el-icon> <span>图片素材</span></div>
        </nav>
      </div>
    </div>

    <div class="main-content">
      <header class="content-header">
        <el-input
            v-model="searchQuery"
            placeholder="搜索文件、文件夹..."
            class="search-input"
            :prefix-icon="Search"
            clearable
        />
      </header>

      <div class="action-grid">
        <div class="action-tile dark">
          <el-icon><Plus /></el-icon>
          <span>创建</span>
        </div>
        <div class="action-tile light">
          <el-icon><Upload /></el-icon>
          <span>上传</span>
        </div>
        <div class="action-tile light">
          <el-icon><FolderAdd /></el-icon>
          <span>新建文件夹</span>
        </div>
      </div>

      <div class="filter-pills">
        <span class="pill active">最近使用</span>
        <span class="pill">PDF</span>
        <span class="pill">视频</span>
        <span class="pill">文档</span>
      </div>

      <div class="file-list-wrapper">
        <el-table :data="tableData" class="custom-table" @row-click="handleRowClick">
          <el-table-column label="名称" min-width="300">
            <template #default="scope">
              <div class="file-info-cell">
                <el-icon class="file-icon" :class="scope.row.type"><Document /></el-icon>
                <span class="file-name">{{ scope.row.name }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="date" label="修改时间" width="180" />
          <el-table-column label="" width="120" align="right">
            <template #default="scope">
              <div class="row-actions">
                <el-button link type="primary" @click.stop="handleShare(scope.row)">分享</el-button>
                <el-button link :icon="MoreFilled" />
              </div>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <el-dialog
        v-model="shareDialogVisible"
        title="分享文件"
        width="420px"
        class="custom-dialog"
        destroy-on-close
    >
      <div v-if="!shareResult" class="share-box">
        <p class="share-desc">链接有效期</p>
        <el-radio-group v-model="shareForm.expireType" class="modern-radio">
          <el-radio-button :label="0">永久</el-radio-button>
          <el-radio-button :label="1">7天</el-radio-button>
          <el-radio-button :label="2">30天</el-radio-button>
        </el-radio-group>
      </div>
      <div v-else class="share-success">
        <el-result icon="success" title="分享链接已就绪">
          <template #extra>
            <div class="copy-field">
              <el-input v-model="shareResult.shareUrl" readonly>
                <template #append>
                  <el-button @click="copyText(shareResult.shareUrl)">复制链接</el-button>
                </template>
              </el-input>
            </div>
          </template>
        </el-result>
      </div>
      <template #footer>
        <div class="dialog-footer">
          <el-button @click="shareDialogVisible = false">关闭</el-button>
          <el-button v-if="!shareResult" type="primary" :loading="sharing" @click="createShareLink">创建分享</el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import axios from 'axios'
import { ElMessage } from 'element-plus'
import { Folder, Document, VideoCamera, Picture, Search, Plus, Upload, FolderAdd, MoreFilled } from '@element-plus/icons-vue'

const searchQuery = ref('')
const tableData = ref([
  { id: 888, name: '毕业论文最终稿.pdf', type: 'pdf', date: '2026-04-17 10:00' },
  { id: 889, name: '项目演示视频.mp4', type: 'video', date: '2026-04-16 15:30' },
  { id: 890, name: '代码架构图.png', type: 'img', date: '2026-04-15 09:20' }
])

// 分享逻辑
const shareDialogVisible = ref(false)
const sharing = ref(false)
const currentFile = ref(null)
const shareResult = ref(null)
const shareForm = reactive({ expireType: 1 })

const handleShare = (row) => {
  currentFile.value = row
  shareResult.value = null
  shareDialogVisible.value = true
}

const createShareLink = async () => {
  sharing.value = true
  try {
    const res = await axios.post('http://localhost:8080/share/create', {
      fileId: currentFile.value.id,
      expireType: shareForm.expireType
    })
    shareResult.value = res.data
    ElMessage.success('分享成功')
  } catch (e) {
    ElMessage.error('创建失败，请检查后端')
  } finally {
    sharing.value = false
  }
}

const copyText = (text) => {
  navigator.clipboard.writeText(text).then(() => ElMessage.success('已复制'))
}
</script>

<style scoped>
.dropbox-container { display: flex; height: 100%; background: #fff; }

/* 侧边栏样式 */
.sub-sidebar { width: 240px; background: #f7f9fa; border-right: 1px solid #e6e9eb; padding: 32px 16px; }
.title { font-size: 20px; font-weight: 700; margin-bottom: 24px; padding-left: 12px; }
.nav-label { font-size: 12px; color: #637282; margin: 24px 0 12px 12px; text-transform: uppercase; }
.nav-item {
  display: flex; align-items: center; gap: 12px; padding: 10px 12px;
  border-radius: 8px; cursor: pointer; color: #1e1919; transition: all 0.2s;
}
.nav-item:hover { background: #e6e9eb; }
.nav-item.active { background: #0061fe; color: #fff; }
.nav-divider { height: 1px; background: #e6e9eb; margin: 20px 12px; }

/* 主区域样式 */
.main-content { flex: 1; padding: 32px 48px; overflow-y: auto; }
.search-input { max-width: 560px; margin-bottom: 32px; }
:deep(.search-input .el-input__wrapper) {
  background: #f7f9fa; border-radius: 24px; box-shadow: none !important; border: 1px solid transparent;
}
:deep(.search-input .el-input__wrapper:hover) { border-color: #0061fe; }

/* 磁贴按键 */
.action-grid { display: flex; gap: 16px; margin-bottom: 40px; }
.action-tile {
  width: 130px; height: 90px; border-radius: 12px; display: flex; flex-direction: column;
  justify-content: center; align-items: flex-start; padding: 16px; cursor: pointer; transition: 0.2s;
}
.action-tile.dark { background: #1e1919; color: #fff; }
.action-tile.light { border: 1px solid #e6e9eb; }
.action-tile:hover { transform: translateY(-2px); box-shadow: 0 4px 12px rgba(0,0,0,0.08); }

/* 胶囊过滤器 */
.filter-pills { display: flex; gap: 8px; margin-bottom: 24px; }
.pill {
  padding: 6px 16px; border-radius: 20px; background: #f7f9fa; font-size: 14px;
  cursor: pointer; transition: 0.2s; border: 1px solid transparent;
}
.pill:hover { background: #e6e9eb; }
.pill.active { background: #1e1919; color: #fff; }

/* 表格定制 */
.file-info-cell { display: flex; align-items: center; gap: 12px; font-weight: 500; }
.file-icon { font-size: 20px; }
.pdf { color: #fa4b4b; }
.video { color: #0061fe; }
.img { color: #00a870; }
.custom-table { --el-table-header-text-color: #637282; }
</style>