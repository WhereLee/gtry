<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import { api, friendlyError } from '@/api'
import type { TeamUser } from '@/types'

const users = ref<TeamUser[]>([])
const loading = ref(false)
const creating = ref(false)
const form = reactive({
  email: '',
  displayName: '',
  role: 'AGENT',
  password: '',
})

async function load() {
  loading.value = true
  try {
    users.value = await api<TeamUser[]>('/api/users')
  } catch (error) {
    ElMessage.error(friendlyError(error))
  } finally {
    loading.value = false
  }
}

async function createUser() {
  if (!form.email.trim() || !form.displayName.trim() || form.password.length < 8) {
    ElMessage.warning('请填写邮箱、名称，密码至少 8 位')
    return
  }
  creating.value = true
  try {
    await api('/api/users', {
      method: 'POST',
      body: JSON.stringify({
        email: form.email.trim(),
        displayName: form.displayName.trim(),
        role: form.role,
        password: form.password,
      }),
    })
    form.email = ''
    form.displayName = ''
    form.role = 'AGENT'
    form.password = ''
    await load()
    ElMessage.success('成员已创建')
  } catch (error) {
    ElMessage.error(friendlyError(error))
  } finally {
    creating.value = false
  }
}

async function updateUser(user: TeamUser, payload: { role?: string; status?: string }) {
  try {
    await api(`/api/users/${user.id}`, {
      method: 'PATCH',
      body: JSON.stringify(payload),
    })
    await load()
    ElMessage.success('成员已更新')
  } catch (error) {
    ElMessage.error(friendlyError(error))
  }
}

onMounted(load)
</script>

<template>
  <div v-loading="loading" class="page-view">
    <div class="page-header">
      <div>
        <h2>团队</h2>
        <div class="muted small">创建坐席、调整角色、停用账号</div>
      </div>
      <el-button :icon="Refresh" @click="load">刷新</el-button>
    </div>

    <el-card class="content-card" shadow="never">
      <h3>新增成员</h3>
      <div class="form-grid">
        <el-form-item label="邮箱"><el-input v-model="form.email" type="email" /></el-form-item>
        <el-form-item label="显示名称"><el-input v-model="form.displayName" /></el-form-item>
        <el-form-item label="角色">
          <el-select v-model="form.role" class="full-width">
            <el-option label="AGENT" value="AGENT" />
            <el-option label="TENANT_ADMIN" value="TENANT_ADMIN" />
          </el-select>
        </el-form-item>
        <el-form-item label="初始密码"><el-input v-model="form.password" type="password" show-password placeholder="至少 8 位" /></el-form-item>
      </div>
      <el-button type="primary" :icon="Plus" :loading="creating" @click="createUser">创建成员</el-button>
    </el-card>

    <el-card class="content-card" shadow="never">
      <h3>成员列表</h3>
      <el-table :data="users" size="small">
        <el-table-column label="成员" min-width="220">
          <template #default="{ row }">
            <div>{{ row.displayName }}</div>
            <div class="muted small">{{ row.email }}</div>
          </template>
        </el-table-column>
        <el-table-column label="角色" width="190">
          <template #default="{ row }">
            <el-select v-model="row.role" size="small">
              <el-option label="AGENT" value="AGENT" />
              <el-option label="TENANT_ADMIN" value="TENANT_ADMIN" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'ACTIVE' ? 'success' : 'danger'">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="230">
          <template #default="{ row }">
            <el-button size="small" @click="updateUser(row, { role: row.role })">保存角色</el-button>
            <el-button size="small" :type="row.status === 'ACTIVE' ? 'danger' : 'success'" plain @click="updateUser(row, { status: row.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE' })">
              {{ row.status === 'ACTIVE' ? '停用' : '启用' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>
