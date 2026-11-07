<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Download, Plus, Refresh } from '@element-plus/icons-vue'
import { api, download, friendlyError } from '@/api'
import type { Tenant } from '@/types'
import { formatDate } from '@/format'

const tenants = ref<Tenant[]>([])
const loading = ref(false)
const creating = ref(false)
const showDeleted = ref(false)
const auditLoading = ref(false)
const auditAction = ref('')
const auditTargetType = ref('tenant')
const auditLogs = ref<Array<Record<string, any>>>([])
const form = reactive({
  slug: '',
  name: '',
  plan: 'pro',
  monthlyTokenBudget: 200000,
  adminEmail: '',
  adminPassword: '',
  adminDisplayName: '',
})

const visibleTenants = computed(() =>
  showDeleted.value ? tenants.value : tenants.value.filter((tenant) => tenant.status !== 'DELETED'),
)

async function loadAudit() {
  auditLoading.value = true
  try {
    const params = new URLSearchParams({ limit: '100' })
    if (auditAction.value.trim()) params.set('action', auditAction.value.trim())
    if (auditTargetType.value.trim()) params.set('targetType', auditTargetType.value.trim())
    auditLogs.value = await api<Array<Record<string, any>>>(`/api/platform/audit?${params.toString()}`)
  } catch (error) {
    ElMessage.error(friendlyError(error))
  } finally {
    auditLoading.value = false
  }
}

function formatAuditDetail(detail: unknown) {
  if (detail === null || detail === undefined) return ''
  const text = typeof detail === 'string' ? detail : JSON.stringify(detail)
  try {
    return JSON.stringify(JSON.parse(text))
  } catch {
    return text
  }
}

async function load() {
  loading.value = true
  try {
    tenants.value = await api<Tenant[]>('/api/platform/tenants')
  } catch (error) {
    ElMessage.error(friendlyError(error))
  } finally {
    loading.value = false
  }
}

async function createTenant() {
  if (!form.slug.trim() || !form.name.trim() || !form.adminEmail.trim() || form.adminPassword.length < 8) {
    ElMessage.warning('请填写 slug、名称、管理员邮箱，密码至少 8 位')
    return
  }
  creating.value = true
  try {
    await api('/api/platform/tenants', {
      method: 'POST',
      body: JSON.stringify({
        slug: form.slug.trim(),
        name: form.name.trim(),
        plan: form.plan,
        monthlyTokenBudget: Number(form.monthlyTokenBudget || 200000),
        adminEmail: form.adminEmail.trim(),
        adminPassword: form.adminPassword,
        adminDisplayName: form.adminDisplayName.trim(),
      }),
    })
    form.slug = ''
    form.name = ''
    form.plan = 'pro'
    form.monthlyTokenBudget = 200000
    form.adminEmail = ''
    form.adminPassword = ''
    form.adminDisplayName = ''
    await load()
    await loadAudit()
    ElMessage.success('租户已开通')
  } catch (error) {
    ElMessage.error(friendlyError(error))
  } finally {
    creating.value = false
  }
}

async function toggleTenant(tenant: Tenant) {
  try {
    const next = tenant.status === 'ACTIVE' ? 'SUSPENDED' : 'ACTIVE'
    await api(`/api/platform/tenants/${tenant.id}/status?status=${next}`, { method: 'PATCH' })
    await load()
  } catch (error) {
    ElMessage.error(friendlyError(error))
  }
}

async function exportTenant(tenant: Tenant) {
  try {
    await download(`/api/platform/tenants/${tenant.id}/export`, `tenant-${tenant.slug}.json`)
  } catch (error) {
    ElMessage.error(friendlyError(error))
  }
}

async function pollDeletion(tenantId: string) {
  for (let i = 0; i < 30; i += 1) {
    const job = await api<{ state: string; message?: string }>(`/api/platform/tenants/${tenantId}/delete-status`)
    if (job.state === 'DONE') return
    if (job.state === 'FAILED') throw new Error(job.message || '删除失败')
    await new Promise((resolve) => setTimeout(resolve, 1000))
  }
  throw new Error('删除任务超时')
}

async function deleteTenant(tenant: Tenant) {
  try {
    const { value } = await ElMessageBox.prompt(
      `租户 ${tenant.slug} 将进入回收站，30 天后自动彻底删除。输入 slug 确认：`,
      '移入回收站',
      { confirmButtonText: '确认移入', cancelButtonText: '取消', type: 'warning' },
    )
    if (value !== tenant.slug) {
      ElMessage.warning('slug 不匹配，已取消')
      return
    }
    const job = await api<{ state: string }>(`/api/platform/tenants/${tenant.id}?confirm=${encodeURIComponent(value)}`, { method: 'DELETE' })
    if (job.state === 'RUNNING' || job.state === 'IDLE') await pollDeletion(tenant.id)
    await load()
    await loadAudit()
    ElMessage.success('租户已移入回收站')
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(friendlyError(error))
  }
}

async function restoreTenant(tenant: Tenant) {
  try {
    await api(`/api/platform/tenants/${tenant.id}/restore`, { method: 'POST' })
    await load()
    await loadAudit()
    ElMessage.success('租户已恢复')
  } catch (error) {
    ElMessage.error(friendlyError(error))
  }
}

async function purgeTenant(tenant: Tenant) {
  try {
    const { value } = await ElMessageBox.prompt(
      `彻底删除租户 ${tenant.slug} 会级联删除所有数据，且不可恢复。输入 slug 确认：`,
      '彻底删除租户',
      { confirmButtonText: '确认彻底删除', cancelButtonText: '取消', type: 'error' },
    )
    if (value !== tenant.slug) {
      ElMessage.warning('slug 不匹配，已取消')
      return
    }
    const job = await api<{ state: string }>(
      `/api/platform/tenants/${tenant.id}/purge?confirm=${encodeURIComponent(value)}`,
      { method: 'DELETE' },
    )
    if (job.state === 'RUNNING' || job.state === 'IDLE') await pollDeletion(tenant.id)
    await load()
    await loadAudit()
    ElMessage.success('租户已彻底删除')
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(friendlyError(error))
  }
}

onMounted(() => {
  load()
  loadAudit()
})
</script>

<template>
  <div v-loading="loading" class="page-view">
    <div class="page-header">
      <div>
        <h2>平台管理</h2>
        <p class="page-sub">租户生命周期、用量和数据导出</p>
      </div>
      <el-button :icon="Refresh" size="small" @click="load">刷新</el-button>
    </div>

    <section class="panel">
      <div class="panel-head"><h3>开通租户</h3></div>
      <div class="form-grid">
        <el-form-item label="Slug"><el-input v-model="form.slug" placeholder="acme-support" /></el-form-item>
        <el-form-item label="名称"><el-input v-model="form.name" placeholder="Acme Support" /></el-form-item>
        <el-form-item label="套餐">
          <el-select v-model="form.plan" class="full-width">
            <el-option label="free" value="free" />
            <el-option label="pro" value="pro" />
            <el-option label="business" value="business" />
          </el-select>
        </el-form-item>
        <el-form-item label="月度 token 预算"><el-input-number v-model="form.monthlyTokenBudget" :min="1000" :step="10000" class="full-width" /></el-form-item>
        <el-form-item label="管理员邮箱"><el-input v-model="form.adminEmail" type="email" placeholder="admin@example.com" /></el-form-item>
        <el-form-item label="管理员密码"><el-input v-model="form.adminPassword" type="password" show-password placeholder="至少 8 位" /></el-form-item>
        <el-form-item label="管理员名称"><el-input v-model="form.adminDisplayName" placeholder="Admin" /></el-form-item>
      </div>
      <div class="form-actions">
        <el-button type="primary" size="small" :icon="Plus" :loading="creating" @click="createTenant">创建租户</el-button>
      </div>
    </section>

    <section class="panel">
      <div class="ticket-filter">
        <div class="panel-head"><h3>租户列表</h3></div>
        <el-switch v-model="showDeleted" active-text="显示回收站" />
      </div>
      <div class="panel-note">删除后进入回收站，30 天后自动彻底清理。</div>
      <el-table :data="visibleTenants" size="small">
        <el-table-column label="租户" min-width="190">
          <template #default="{ row }">
            <div>{{ row.name }}</div>
            <div class="muted small">
              {{ row.slug }} · {{ formatDate(row.createdAt) }}
              <span v-if="row.deletedAt"> · 删除于 {{ formatDate(row.deletedAt) }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="105">
          <template #default="{ row }">
            <el-tag
              size="small"
              :type="row.status === 'ACTIVE' ? 'success' : row.status === 'SUSPENDED' ? 'warning' : 'danger'"
            >{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="plan" label="套餐" width="90" />
        <el-table-column prop="userCount" label="用户" width="70" />
        <el-table-column prop="ticketCount" label="工单" width="70" />
        <el-table-column label="本月用量" min-width="160">
          <template #default="{ row }">
            {{ Number(row.tokensThisMonth).toLocaleString() }} / {{ Number(row.monthlyTokenBudget).toLocaleString() }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="290">
          <template #default="{ row }">
            <template v-if="row.status === 'DELETED'">
              <el-button size="small" type="success" @click="restoreTenant(row)">恢复</el-button>
              <el-button size="small" :icon="Download" @click="exportTenant(row)">导出</el-button>
              <el-button size="small" type="danger" plain @click="purgeTenant(row)">彻底删除</el-button>
            </template>
            <template v-else>
              <el-button size="small" :disabled="row.slug === 'platform'" @click="toggleTenant(row)">
                {{ row.status === 'ACTIVE' ? '停用' : '启用' }}
              </el-button>
              <el-button size="small" :icon="Download" @click="exportTenant(row)">导出</el-button>
              <el-button size="small" type="danger" plain :disabled="row.slug === 'platform'" @click="deleteTenant(row)">移入回收站</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <section v-loading="auditLoading" class="panel">
      <div class="ticket-filter">
        <div class="panel-head"><h3>平台审计</h3></div>
        <div class="toolbar">
          <el-input v-model="auditAction" placeholder="动作，如 tenant.create" style="width: 220px" clearable />
          <el-select v-model="auditTargetType" style="width: 140px" clearable>
            <el-option label="全部目标" value="" />
            <el-option label="tenant" value="tenant" />
          </el-select>
          <el-button :icon="Refresh" :loading="auditLoading" @click="loadAudit">查询</el-button>
        </div>
      </div>
      <el-table :data="auditLogs" size="small">
        <el-table-column prop="createdAt" label="时间" width="200" />
        <el-table-column prop="action" label="动作" width="180" />
        <el-table-column prop="targetType" label="目标类型" width="100" />
        <el-table-column prop="targetId" label="目标 ID" min-width="180" />
        <el-table-column label="详情" min-width="260">
          <template #default="{ row }">
            <span class="muted small">{{ formatAuditDetail(row.detail) }}</span>
          </template>
        </el-table-column>
      </el-table>
    </section>
  </div>
</template>
