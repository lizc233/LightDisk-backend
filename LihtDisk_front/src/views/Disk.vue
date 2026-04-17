<template>
  <div class="disk-container">
    <div class="operation-bar">
      <el-button type="primary">上传文件</el-button>
      <el-button>新建文件夹</el-button>
    </div>

    <el-table :data="tableData" style="width: 100%; margin-top: 20px;">
      <el-table-column prop="name" label="文件名" />
      <el-table-column prop="size" label="大小" width="180" />
      <el-table-column prop="date" label="修改时间" width="180" />
      <el-table-column label="操作" width="200">
        <template #default="scope">
          <el-button link type="primary" @click="handleShare(scope.row)">分享</el-button>
          <el-button link type="danger">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog
        v-model="shareDialogVisible"
        title="分享文件"
        width="400px"
        class="share-dialog-glass"
        :append-to-body="true"
        destroy-on-close
    >
      <div v-if="!shareResult" class="share-setup">
        <p class="share-label">设置链接有效期</p>
        <el-radio-group v-model="shareForm.expireType" class="expiry-group">
          <el-radio-button :label="0">永久有效</el-radio-button>
          <el-radio-button :label="1">7天</el-radio-button>
          <el-radio-button :label="2">30天</el-radio-button>
        </el-radio-group>
      </div>

      <div v-else class="share-result">
        <el-alert title="分享链接已生成" type="success" :closable="false" show-icon />
        <div class="result-item" style="margin-top: 15px;">
          <span class="item-label" style="display:block; margin-bottom: 5px; color: #606266; font-size: 13px;">分享链接</span>
          <el-input v-model="shareResult.shareUrl" readonly>
            <template #append>
              <el-button @click="copyText(shareResult.shareUrl)">复制</el-button>
            </template>
          </el-input>
        </div>
        <div class="result-item" style="margin-top: 15px;">
          <span class="item-label" style="display:block; margin-bottom: 5px; color: #606266; font-size: 13px;">提取码</span>
          <el-input v-model="shareResult.extractCode" readonly style="width: 120px" />
        </div>
      </div>

      <template #footer>
        <div class="dialog-footer">
          <el-button @click="shareDialogVisible = false">取消</el-button>
          <el-button
              type="primary"
              :loading="sharing"
              @click="createShareLink"
              v-if="!shareResult"
          >
            创建链接
          </el-button>
          <el-button type="primary" @click="shareDialogVisible = false" v-else>完成</el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import axios from 'axios'
import { ElMessage } from 'element-plus'

// --- 伪造几条测试文件数据，注意第一条的 id 是 888，对应你后端的假数据 ---
const tableData = ref([
  { id: 888, name: '测试机密文件.pdf', size: '2.4 MB', date: '2026-04-17' },
  { id: 889, name: '项目架构图.png', size: '1.1 MB', date: '2026-04-16' }
])

// 控制弹窗显示/隐藏
const shareDialogVisible = ref(false)
// 控制按钮的 loading 圈圈
const sharing = ref(false)
// 记录当前点击的是哪一行文件
const currentFile = ref(null)
// 存储后端返回的链接和提取码
const shareResult = ref(null)

// 弹窗表单数据
const shareForm = reactive({
  expireType: 1 // 默认选中 7天
})

// 点击列表里的【分享】按钮时触发
const handleShare = (row) => {
  currentFile.value = row // 把当前这行的文件数据存下来
  shareResult.value = null // 清空上一次的生成结果
  shareDialogVisible.value = true // 弹出对话框
}

// 点击弹窗里的【创建链接】按钮时触发
const createShareLink = async () => {
  if (!currentFile.value) return

  sharing.value = true
  try {
    // 发送 POST 请求给你写的 Spring Boot 后端
    const res = await axios.post('http://localhost:8080/share/create', {
      fileId: currentFile.value.id,
      expireType: shareForm.expireType
    })

    // 把后端返回的数据赋给 shareResult，页面会自动切换到成功视图
    shareResult.value = res.data
    ElMessage.success('链接创建成功')
  } catch (error) {
    ElMessage.error('创建分享失败，请检查网络或后端状态')
    console.error(error)
  } finally {
    sharing.value = false
  }
}

// 复制文本功能
const copyText = (text) => {
  navigator.clipboard.writeText(text).then(() => {
    ElMessage.success('已复制到剪贴板')
  }).catch(() => {
    ElMessage.error('复制失败，请手动选择复制')
  })
}
</script>

<style scoped>
/* 稍微加点边距让页面好看点 */
.disk-container {
  padding: 10px;
}

.operation-bar {
  display: flex;
  gap: 10px;
}

.share-setup {
  text-align: center;
  padding: 15px 0 25px 0;
}

.share-label {
  margin-bottom: 15px;
  color: #303133;
  font-weight: bold;
}
</style>