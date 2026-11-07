<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { api, friendlyError } from '@/api'
import type { AiConfig } from '@/types'

const form = reactive<AiConfig>({
  model: 'deepseek-flash',
  topK: 5,
  extraInstructions: '',
  enabled: true,
})
const loading = ref(false)
const saving = ref(false)

async function load() {
  loading.value = true
  try {
    const config = await api<AiConfig>('/api/ai/config')
    Object.assign(form, config)
  } catch (error) {
    ElMessage.error(friendlyError(error))
  } finally {
    loading.value = false
  }
}

async function save() {
  saving.value = true
  try {
    await api('/api/ai/config', {
      method: 'PUT',
      body: JSON.stringify({
        model: form.model.trim(),
        topK: Number(form.topK || 5),
        extraInstructions: form.extraInstructions.trim(),
        enabled: form.enabled,
      }),
    })
    ElMessage.success('AI 设置已保存')
  } catch (error) {
    ElMessage.error(friendlyError(error))
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <div v-loading="loading" class="page-view narrow-page">
    <div class="page-header">
      <div>
        <h2>AI 设置</h2>
        <p class="page-sub">控制建议回复使用的模型、检索条数和补充规则</p>
      </div>
      <el-button :icon="Refresh" size="small" @click="load">刷新</el-button>
    </div>

    <section class="panel">
      <div class="form-grid">
        <el-form-item label="模型">
          <el-input v-model="form.model" maxlength="64" size="small" />
        </el-form-item>
        <el-form-item label="检索条数">
          <el-input-number v-model="form.topK" :min="1" :max="10" size="small" class="full-width" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.enabled" active-text="启用" inactive-text="关闭" />
        </el-form-item>
      </div>
      <el-form-item label="租户补充规则">
        <el-input
          v-model="form.extraInstructions"
          type="textarea"
          :rows="7"
          maxlength="4000"
          show-word-limit
          placeholder="例如：回答时优先说明退货时效；不要承诺具体到账时间。"
        />
      </el-form-item>
      <div class="form-actions">
        <el-button type="primary" :loading="saving" @click="save">保存 AI 设置</el-button>
      </div>
    </section>
  </div>
</template>
