<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { api, friendlyError } from '@/api'
import type { DailyUsage, UsageSummary } from '@/types'
import { formatCost } from '@/format'
import StatCard from '@/components/StatCard.vue'

const summary = ref<UsageSummary | null>(null)
const daily = ref<DailyUsage[]>([])
const loading = ref(false)

const utilization = computed(() => {
  if (!summary.value || summary.value.monthlyTokenBudget <= 0) return 0
  return summary.value.tokensThisMonth / summary.value.monthlyTokenBudget
})

const maxTokens = computed(() => Math.max(1, ...daily.value.map((day) => Number(day.tokens))))

async function load() {
  loading.value = true
  try {
    const [nextSummary, nextDaily] = await Promise.all([
      api<UsageSummary>('/api/usage/summary'),
      api<DailyUsage[]>('/api/usage/daily?days=14'),
    ])
    summary.value = nextSummary
    daily.value = nextDaily
  } catch (error) {
    ElMessage.error(friendlyError(error))
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div v-loading="loading" class="page-view">
    <div class="page-header">
      <div>
        <h2>用量与成本</h2>
        <div class="muted small">本月 token、预算、成本与近 14 天趋势</div>
      </div>
      <el-button :icon="Refresh" @click="load">刷新</el-button>
    </div>

    <div v-if="summary" class="stat-grid">
      <StatCard label="本月 tokens" :value="Number(summary.tokensThisMonth).toLocaleString()" :hint="`预算 ${Number(summary.monthlyTokenBudget).toLocaleString()}`" />
      <StatCard label="预算使用率" :value="`${(utilization * 100).toFixed(1)}%`" :hint="utilization >= 0.9 ? '接近或超过预算' : '预算充足'" />
      <StatCard label="本月成本" :value="formatCost(summary.costMicrosThisMonth)" hint="按当前单价估算" />
      <StatCard label="限流" :value="`${summary.rateLimitPerMinute} 次/分钟`" hint="租户级固定窗口" />
      <StatCard label="预算进度" :value="`${(utilization * 100).toFixed(1)}%`">
        <div class="progress-track">
          <div class="progress-fill" :style="{ width: `${Math.min(100, Math.max(0, utilization * 100))}%` }" />
        </div>
      </StatCard>
    </div>

    <el-card class="content-card" shadow="never">
      <h3>近 14 天 token</h3>
      <div class="chart">
        <div v-for="day in daily" :key="day.date" class="chart-column">
          <div
            class="chart-bar"
            :class="{ zero: Number(day.tokens) === 0 }"
            :style="{ height: `${Math.max(2, Math.round((Number(day.tokens) / maxTokens) * 150))}px` }"
            :title="`${day.date}: ${Number(day.tokens).toLocaleString()} tokens · ${formatCost(day.costMicros)}`"
          />
          <div class="chart-date">{{ day.date.slice(5) }}</div>
        </div>
      </div>
    </el-card>
  </div>
</template>
