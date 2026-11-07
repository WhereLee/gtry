<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { api, friendlyError } from '@/api'
import type { DailyUsage, UsageSummary } from '@/types'
import { formatCost } from '@/format'

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
        <p class="page-sub">本月 AI 调用、预算和近 14 天消耗</p>
      </div>
      <el-button :icon="Refresh" size="small" @click="load">刷新</el-button>
    </div>

    <div v-if="summary" class="metric-strip">
      <div class="metric">
        <span>本月 tokens</span>
        <b>{{ Number(summary.tokensThisMonth).toLocaleString() }}</b>
        <em>预算 {{ Number(summary.monthlyTokenBudget).toLocaleString() }}</em>
      </div>
      <div class="metric">
        <span>预算使用</span>
        <b>{{ (utilization * 100).toFixed(1) }}%</b>
        <em>{{ utilization >= 0.9 ? '接近上限' : '正常' }}</em>
      </div>
      <div class="metric">
        <span>本月成本</span>
        <b>{{ formatCost(summary.costMicrosThisMonth) }}</b>
        <em>按当前单价估算</em>
      </div>
      <div class="metric">
        <span>限流</span>
        <b>{{ summary.rateLimitPerMinute }} 次/分</b>
        <em>租户级固定窗口</em>
      </div>
    </div>

    <section class="panel">
      <div class="panel-head">
        <h3>近 14 天 token</h3>
        <span class="pane-sub">悬停查看当日成本</span>
      </div>
      <div v-if="daily.some((day) => Number(day.tokens) > 0)" class="usage-chart">
        <div v-for="day in daily" :key="day.date" class="usage-column">
          <div class="usage-value">{{ Number(day.tokens).toLocaleString() }}</div>
          <div
            class="usage-bar"
            :class="{ zero: Number(day.tokens) === 0 }"
            :style="{ height: `${Math.max(3, Math.round((Number(day.tokens) / maxTokens) * 150))}px` }"
            :title="`${day.date}: ${Number(day.tokens).toLocaleString()} tokens · ${formatCost(day.costMicros)}`"
          />
          <div class="usage-date">{{ day.date.slice(5) }}</div>
        </div>
      </div>
      <div v-else class="chart-empty">近 14 天暂无 AI 用量</div>
    </section>
  </div>
</template>
