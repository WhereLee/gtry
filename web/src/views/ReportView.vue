<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { api, friendlyError } from '@/api'
import type { Report } from '@/types'
import { categoryLabel, formatCost, formatSeconds, priorityLabel } from '@/format'
import StatCard from '@/components/StatCard.vue'

const report = ref<Report | null>(null)
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    report.value = await api<Report>('/api/reports/tenant?days=30')
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
        <h2>报表</h2>
        <div class="muted small">近 30 天工单、SLA、AI 采纳、CSAT 与成本</div>
      </div>
      <el-button :icon="Refresh" @click="load">刷新</el-button>
    </div>

    <template v-if="report">
      <div class="stat-grid">
        <StatCard label="工单总数" :value="report.tickets.total" :hint="`待处理 ${report.tickets.open} · 处理中 ${report.tickets.pending} · 已关闭 ${report.tickets.closed}`" />
        <StatCard label="首次响应均值" :value="formatSeconds(report.tickets.first_response_avg_seconds)" />
        <StatCard label="解决均值" :value="formatSeconds(report.tickets.resolution_avg_seconds)" />
        <StatCard label="SLA 超时" :value="report.tickets.sla_breached" />
        <StatCard label="AI 采纳率" :value="`${report.aiFeedback.adoptionRate}%`" :hint="`采纳 ${report.aiFeedback.adopt} · 修改 ${report.aiFeedback.edit} · 驳回 ${report.aiFeedback.reject}`" />
        <StatCard label="CSAT" :value="report.tickets.csat_avg ? `${Number(report.tickets.csat_avg).toFixed(2)} / 5` : '暂无'" :hint="`${report.tickets.csat_count} 条评价`" />
        <StatCard label="本月 tokens" :value="Number(report.usage.tokensThisMonth).toLocaleString()" :hint="`预算 ${Number(report.usage.monthlyTokenBudget).toLocaleString()}`" />
        <StatCard label="本月成本" :value="formatCost(report.usage.costMicrosThisMonth)" hint="按当前单价估算" />
      </div>

      <div class="form-grid">
        <el-card class="content-card" shadow="never">
          <h3>分类分布</h3>
          <el-table :data="report.byCategory" size="small">
            <el-table-column label="分类" min-width="140">
              <template #default="{ row }">{{ categoryLabel(row.category) }}</template>
            </el-table-column>
            <el-table-column prop="count" label="数量" width="90" />
          </el-table>
        </el-card>

        <el-card class="content-card" shadow="never">
          <h3>优先级分布</h3>
          <el-table :data="report.byPriority" size="small">
            <el-table-column label="优先级" min-width="140">
              <template #default="{ row }">{{ priorityLabel(row.priority) }}</template>
            </el-table-column>
            <el-table-column prop="count" label="数量" width="90" />
          </el-table>
        </el-card>
      </div>
    </template>
  </div>
</template>
