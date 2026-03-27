<template>
  <div class="breadcrumb">
    <el-breadcrumb separator="/">
      <el-breadcrumb-item
        v-for="(item, index) in path"
        :key="item.id"
        @click="navigate(index)"
      >
        {{ item.name }}
      </el-breadcrumb-item>
    </el-breadcrumb>
  </div>
</template>

<script setup>
import { defineProps, defineEmits } from 'vue'

const props = defineProps({
  path: Array,
})
const emit = defineEmits(['navigate'])

const navigate = (index) => {
  emit('navigate', index)
}
</script>

<style scoped>
.breadcrumb {
  cursor: pointer;
}
.breadcrumb :deep(.el-breadcrumb__item) {
  user-select: none;
}
</style>