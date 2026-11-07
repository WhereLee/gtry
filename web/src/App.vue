<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  ChatDotRound, Collection, DataLine, Monitor, Setting, TrendCharts, User,
} from '@element-plus/icons-vue'
import { api, getToken, setToken } from '@/api'
import type { CurrentUser, UsageSummary } from '@/types'
import LoginView from '@/views/LoginView.vue'
import TicketsView from '@/views/TicketsView.vue'
import WikiView from '@/views/WikiView.vue'
import UsageView from '@/views/UsageView.vue'
import ReportView from '@/views/ReportView.vue'
import TeamView from '@/views/TeamView.vue'
import AiView from '@/views/AiView.vue'
import PlatformView from '@/views/PlatformView.vue'

const user = ref<CurrentUser | null>(null)
const tab = ref('tickets')
const booting = ref(Boolean(getToken()))
const usage = ref<UsageSummary | null>(null)

const isTenantAdmin = computed(() => user.value?.role === 'TENANT_ADMIN')
const isPlatformAdmin = computed(() => user.value?.role === 'PLATFORM_ADMIN')
const portalLink = computed(() => {
  const key = user.value?.tenantSlug === 'cloudwise' ? 'cloudwise-demo-key' : 'acme-demo-key'
  return `/portal.html?channel=${key}`
})

async function loadUsage() {
  if (!user.value || isPlatformAdmin.value) return
  try {
    usage.value = await api<UsageSummary>('/api/usage/summary')
  } catch {
    usage.value = null
  }
}

async function boot() {
  if (!getToken()) {
    booting.value = false
    return
  }
  try {
    user.value = await api<CurrentUser>('/api/auth/me')
    await loadUsage()
  } catch {
    setToken(null)
    user.value = null
  } finally {
    booting.value = false
  }
}

async function onLoggedIn(next: CurrentUser) {
  user.value = next
  tab.value = 'tickets'
  await loadUsage()
}

async function logout() {
  try {
    if (getToken()) await api('/api/auth/logout', { method: 'POST' })
  } catch {
    // local logout still proceeds
  }
  setToken(null)
  user.value = null
  usage.value = null
  tab.value = 'tickets'
}

function onUnauthorized() {
  user.value = null
  usage.value = null
  ElMessage.warning('登录已过期，请重新登录')
}

function onSelect(index: string) {
  tab.value = index
}

onMounted(() => {
  window.addEventListener('helpdesk:unauthorized', onUnauthorized)
  boot()
})

onBeforeUnmount(() => {
  window.removeEventListener('helpdesk:unauthorized', onUnauthorized)
})
</script>

<template>
  <div v-if="booting" class="boot-screen">
    <el-skeleton :rows="5" animated />
  </div>

  <LoginView v-else-if="!user" @logged-in="onLoggedIn" />

  <div v-else class="app-shell">
    <header class="topbar">
      <div class="brand">
        <span class="brand-mark small">H</span>
        <span>Helpdesk</span>
      </div>

      <el-menu class="top-menu" mode="horizontal" :default-active="tab" :ellipsis="false" @select="onSelect">
        <el-menu-item index="tickets"><el-icon><ChatDotRound /></el-icon>工单</el-menu-item>
        <el-menu-item index="wiki"><el-icon><Collection /></el-icon>知识库</el-menu-item>
        <el-menu-item index="usage"><el-icon><DataLine /></el-icon>用量</el-menu-item>
        <el-menu-item v-if="!isPlatformAdmin" index="report"><el-icon><TrendCharts /></el-icon>报表</el-menu-item>
        <el-menu-item v-if="isTenantAdmin" index="team"><el-icon><User /></el-icon>团队</el-menu-item>
        <el-menu-item v-if="isTenantAdmin" index="ai"><el-icon><Setting /></el-icon>AI 设置</el-menu-item>
        <el-menu-item v-if="isPlatformAdmin" index="platform"><el-icon><Monitor /></el-icon>平台</el-menu-item>
      </el-menu>

      <div class="top-actions">
        <el-link v-if="!isPlatformAdmin" :href="portalLink" target="_blank" type="primary">客户门户</el-link>
        <el-tag v-if="usage" effect="plain" round>
          {{ Number(usage.tokensThisMonth).toLocaleString() }} / {{ Number(usage.monthlyTokenBudget).toLocaleString() }} tokens
        </el-tag>
        <el-tag effect="plain" round>{{ user.tenantSlug }}</el-tag>
        <span class="user-name">{{ user.displayName }}</span>
        <el-button text @click="logout">退出</el-button>
      </div>
    </header>

    <main class="app-main">
      <TicketsView v-if="tab === 'tickets'" :user="user" @usage="loadUsage" />
      <WikiView v-else-if="tab === 'wiki'" />
      <UsageView v-else-if="tab === 'usage'" />
      <ReportView v-else-if="tab === 'report'" />
      <TeamView v-else-if="tab === 'team'" />
      <AiView v-else-if="tab === 'ai'" />
      <PlatformView v-else-if="tab === 'platform'" />
    </main>
  </div>
</template>
