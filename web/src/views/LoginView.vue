<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, friendlyError, setToken } from '@/api'
import type { CurrentUser, LoginResponse } from '@/types'

const emit = defineEmits<{ loggedIn: [user: CurrentUser] }>()

const tenantSlug = ref('acme')
const email = ref('agent@acme.test')
const password = ref('agent123')
const loading = ref(false)
const error = ref('')

async function submit() {
  error.value = ''
  loading.value = true
  try {
    const result = await api<LoginResponse>('/api/auth/login', {
      method: 'POST',
      body: JSON.stringify({
        tenantSlug: tenantSlug.value,
        email: email.value,
        password: password.value,
      }),
    })
    setToken(result.token)
    emit('loggedIn', result.user)
  } catch (err) {
    error.value = friendlyError(err)
    ElMessage.error(error.value)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <div class="login-brand">
      <div class="brand-mark">H</div>
      <div>
        <h1>Helpdesk</h1>
        <p>多租户 AI 工单与客服平台</p>
      </div>
    </div>

    <el-card class="login-card" shadow="never">
      <h2>登录工作台</h2>
      <p class="muted">使用租户、邮箱和密码登录</p>
      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="租户">
          <el-select v-model="tenantSlug" class="full-width" data-testid="login-tenant">
            <el-option label="acme（电商客服）" value="acme" />
            <el-option label="cloudwise（SaaS 客服）" value="cloudwise" />
            <el-option label="platform（平台管理）" value="platform" />
          </el-select>
        </el-form-item>
        <el-form-item label="邮箱">
          <el-input v-model="email" autocomplete="username" data-testid="login-email" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="password" type="password" show-password autocomplete="current-password" data-testid="login-password" @keyup.enter="submit" />
        </el-form-item>
        <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
        <el-button class="full-width login-submit" type="primary" :loading="loading" data-testid="login-submit" @click="submit">
          登录
        </el-button>
      </el-form>
      <div class="login-hint">
        <span>客服：agent@acme.test / agent123</span>
        <span>管理员：admin@acme.test / admin123</span>
      </div>
    </el-card>
  </div>
</template>
