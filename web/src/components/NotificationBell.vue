<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { Bell } from '@element-plus/icons-vue'
import { api, friendlyError } from '@/api'
import type { NotificationItem } from '@/types'
import { formatDateTime } from '@/format'

const unreadCount = ref(0)
const items = ref<NotificationItem[]>([])
const loading = ref(false)
const visible = ref(false)

async function load() {
  loading.value = true
  try {
    const [count, list] = await Promise.all([
      api<{ count: number }>('/api/notifications/unread-count'),
      api<NotificationItem[]>('/api/notifications?limit=20'),
    ])
    unreadCount.value = Number(count.count || 0)
    items.value = list
  } catch {
    // notifications are non-critical; keep the workbench usable
  } finally {
    loading.value = false
  }
}

async function loadCount() {
  try {
    const count = await api<{ count: number }>('/api/notifications/unread-count')
    unreadCount.value = Number(count.count || 0)
  } catch {
    // notifications are non-critical
  }
}

async function markRead(item: NotificationItem) {
  if (item.readAt) return
  const previous = item.readAt
  item.readAt = new Date().toISOString()
  unreadCount.value = Math.max(0, unreadCount.value - 1)
  try {
    await api(`/api/notifications/${item.id}/read`, { method: 'POST' })
  } catch (error) {
    item.readAt = previous
    unreadCount.value += 1
    console.warn(friendlyError(error))
  }
}

async function markAllRead() {
  try {
    await api('/api/notifications/read-all', { method: 'POST' })
    const now = new Date().toISOString()
    items.value.forEach((item) => { item.readAt = item.readAt || now })
    unreadCount.value = 0
  } catch (error) {
    console.warn(friendlyError(error))
  }
}

function onChanged() {
  if (visible.value) load()
  else loadCount()
}

onMounted(() => {
  loadCount()
  window.addEventListener('helpdesk:notifications-changed', onChanged)
})

onBeforeUnmount(() => {
  window.removeEventListener('helpdesk:notifications-changed', onChanged)
})
</script>

<template>
  <el-popover v-model:visible="visible" placement="bottom-end" :width="360" trigger="click" @show="load">
    <template #reference>
      <el-badge :value="unreadCount" :hidden="unreadCount === 0" :max="99">
        <el-button text :icon="Bell" aria-label="通知" />
      </el-badge>
    </template>
    <div v-loading="loading" class="notification-panel">
      <div class="notification-head">
        <b>通知</b>
        <el-button text type="primary" @click="markAllRead">全部已读</el-button>
      </div>
      <el-empty v-if="!items.length && !loading" description="暂无通知" :image-size="50" />
      <div
        v-for="item in items"
        :key="item.id"
        class="notification-item"
        :class="{ unread: !item.readAt }"
        @click="markRead(item)"
      >
        <div class="notification-title">{{ item.title }}</div>
        <div v-if="item.body" class="notification-body">{{ item.body }}</div>
        <div class="notification-time">{{ formatDateTime(item.createdAt) }}</div>
      </div>
    </div>
  </el-popover>
</template>
