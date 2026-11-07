<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { api, friendlyError } from '@/api'
import type { Report } from '@/types'
import { categoryLabel, formatCost, formatSeconds, priorityLabel } from '@/format'

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
        <p class="page-sub">近 30 天工单、SLA、AI 采纳和客户评价</p>
      </div>
      <el-button :icon="Refresh" size="small" @click="load">刷新</el-button>
    </div>

    <template v-if="report">
      <div class="metric-strip">
        <div class="metric">
          <span>工单总数</span>
          <b>{{ report.tickets.total }}</b>
          <em>待处理 {{ report.tickets.open }} · 处理中 {{ report.tickets.pending }} · 已关闭 {{ report.tickets.closed }}</em>
        </div>
        <div class="metric">
          <span>首次响应</span>
          <b>{{ formatSeconds(report.tickets.first_response_avg_seconds) }}</b>
          <em>平均耗时</em>
        </div>
        <div class="metric">
          <span>解决耗时</span>
          <b>{{ formatSeconds(report.tickets.resolution_avg_seconds) }}</b>
          <em>平均耗时</em>
        </div>
        <div class="metric">
          <span>SLA 超时</span>
          <b>{{ report.tickets.sla_breached }}</b>
          <em>近 30 天</em>
        </div>
        <div class="metric">
          <span>AI 采纳率</span>
          <b>{{ report.aiFeedback.adoptionRate }}%</b>
          <em>采纳 {{ report.aiFeedback.adopt }} · 修改 {{ report.aiFeedback.edit }} · 驳回 {{ report.aiFeedback.reject }}</em>
        </div>
        <div class="metric">
          <span>客户评分</span>
          <b>{{ report.tickets.csat_avg ? `${Number(report.tickets.csat_avg).toFixed(2)} / 5` : '暂无' }}</b>
          <em>{{ report.tickets.csat_count }} 条评价</em>
        </div>
        <div class="metric">
          <span>本月 tokens</span>
          <b>{{ Number(report.usage.tokensThisMonth).toLocaleString() }}</b>
          <em>预算 {{ Number(report.usage.monthlyTokenBudget).toLocaleString() }}</em>
        </div>
        <div class="metric">
          <span>本月成本</span>
          <b>{{ formatCost(report.usage.costMicrosThisMonth) }}</b>
          <em>按当前单价估算</em>
        </div>
      </div>

      <div class="split-panels">
        <section class="panel">
          <div class="panel-head"><h3>分类分布</h3></div>
          <el-table :data="report.byCategory" size="small">
            <el-table-column label="分类" min-width="140">
              <template #default="{ row }">{{ categoryLabel(row.category) }}</template>
            </el-table-column>
            <el-table-column prop="count" label="数量" width="80" align="right" />
          </el-table>
        </section>

        <section class="panel">
          <div class="panel-head"><h3>优先级分布</h3></div>
          <el-table :data="report.byPriority" size="small">
            <el-table-column label="优先级" min-width="140">
              <template #default="{ row }">{{ priorityLabel(row.priority) }}</template>
            </el-table-column>
            <el-table-column prop="count" label="数量" width="80" align="right" />
          </el-table>
        </section>
      </div>
    </template>
  </div>
</template>
