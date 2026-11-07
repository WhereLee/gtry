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
import NotificationBell from '@/components/NotificationBell.vue'

const user = ref<CurrentUser | null>(null)
const tab = ref('tickets')
const booting = ref(Boolean(getToken()))
const usage = ref<UsageSummary | null>(null)
const usageLoading = ref(false)

let usageTimer: number | undefined
let usageLoadedAt = 0

const isTenantAdmin = computed(() => user.value?.role === 'TENANT_ADMIN')
const isPlatformAdmin = computed(() => user.value?.role === 'PLATFORM_ADMIN')
const portalLink = computed(() => {
  const key = user.value?.tenantSlug === 'cloudwise' ? 'cloudwise-demo-key' : 'acme-demo-key'
  return `./portal.html?channel=${key}`
})
const usagePercent = computed(() => {
  if (!usage.value || usage.value.monthlyTokenBudget <= 0) return 0
  return Math.min(100, (usage.value.tokensThisMonth / usage.value.monthlyTokenBudget) * 100)
})
const pageTitle = computed(() => ({
  tickets: '工单',
  wiki: '知识库',
  usage: '用量',
  report: '报表',
  team: '团队',
  ai: 'AI 设置',
  platform: '平台管理',
}[tab.value] || '工作台'))

async function loadUsage(force = false) {
  if (!user.value || isPlatformAdmin.value || usageLoading.value) return
  if (!force && Date.now() - usageLoadedAt < 15_000) return
  usageLoading.value = true
  try {
    usage.value = await api<UsageSummary>('/api/usage/summary')
    usageLoadedAt = Date.now()
  } catch {
    usage.value = null
  } finally {
    usageLoading.value = false
  }
}

function onUsage() {
  if (usageTimer) window.clearTimeout(usageTimer)
  usageTimer = window.setTimeout(() => loadUsage(true), 400)
}

async function boot() {
  if (!getToken()) {
    booting.value = false
    return
  }
  try {
    user.value = await api<CurrentUser>('/api/auth/me')
    await loadUsage(true)
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
  await loadUsage(true)
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
  if (usageTimer) window.clearTimeout(usageTimer)
})
</script>

<template>
  <div v-if="booting" class="boot-screen">
    <el-skeleton :rows="4" animated />
  </div>

  <LoginView v-else-if="!user" @logged-in="onLoggedIn" />

  <div v-else class="app-shell">
    <aside class="side-nav">
      <div class="side-brand">
        <span class="brand-mark">H</span>
        <div class="brand-copy">
          <b>Helpdesk</b>
          <span>{{ user.tenantSlug }}</span>
        </div>
      </div>

      <el-menu class="side-menu" :default-active="tab" @select="onSelect">
        <el-menu-item index="tickets"><el-icon><ChatDotRound /></el-icon><span>工单</span></el-menu-item>
        <el-menu-item index="wiki"><el-icon><Collection /></el-icon><span>知识库</span></el-menu-item>
        <el-menu-item index="usage"><el-icon><DataLine /></el-icon><span>用量</span></el-menu-item>
        <el-menu-item v-if="!isPlatformAdmin" index="report"><el-icon><TrendCharts /></el-icon><span>报表</span></el-menu-item>
        <el-menu-item v-if="isTenantAdmin" index="team"><el-icon><User /></el-icon><span>团队</span></el-menu-item>
        <el-menu-item v-if="isTenantAdmin" index="ai"><el-icon><Setting /></el-icon><span>AI 设置</span></el-menu-item>
        <el-menu-item v-if="isPlatformAdmin" index="platform"><el-icon><Monitor /></el-icon><span>平台</span></el-menu-item>
      </el-menu>

      <div class="side-foot">
        <div class="side-user">
          <span class="user-avatar">{{ user.displayName.slice(0, 1) }}</span>
          <div class="user-copy">
            <b>{{ user.displayName }}</b>
            <span>{{ user.role === 'TENANT_ADMIN' ? '管理员' : user.role === 'PLATFORM_ADMIN' ? '平台管理员' : '客服' }}</span>
          </div>
        </div>
        <el-button text size="small" @click="logout">退出</el-button>
      </div>
    </aside>

    <div class="app-frame">
      <header class="app-topbar">
        <div class="topbar-title">{{ pageTitle }}</div>
        <div class="top-actions">
          <button v-if="usage && !isPlatformAdmin" class="usage-chip" type="button" @click="tab = 'usage'">
            <span>{{ Number(usage.tokensThisMonth).toLocaleString() }} tokens</span>
            <span class="usage-bar"><i :style="{ width: `${usagePercent}%` }" /></span>
          </button>
          <el-link v-if="!isPlatformAdmin" :href="portalLink" target="_blank">客户门户</el-link>
          <NotificationBell />
        </div>
      </header>

      <main class="app-main">
        <TicketsView v-if="tab === 'tickets'" :user="user" @usage="onUsage" />
        <WikiView v-else-if="tab === 'wiki'" />
        <UsageView v-else-if="tab === 'usage'" />
        <ReportView v-else-if="tab === 'report'" />
        <TeamView v-else-if="tab === 'team'" />
        <AiView v-else-if="tab === 'ai'" />
        <PlatformView v-else-if="tab === 'platform'" />
      </main>
    </div>
  </div>
</template>
