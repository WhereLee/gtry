<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { CopyDocument, Refresh, UploadFilled } from '@element-plus/icons-vue'
import { apiUrl, errorCode } from '@/api'
import type { Attachment, CreatePublicTicketResponse, PublicTicket } from '@/types'
import {
  authorLabel, categoryLabel, formatDateTime, formatSize, statusLabel, statusType,
} from '@/format'

const TICKET_ID_KEY = 'helpdesk.portal.ticketId'
const TOKEN_KEY = 'helpdesk.portal.token'

const params = new URLSearchParams(window.location.search)
const channelKey = params.get('channel') || 'acme-demo-key'
const ticketId = ref(sessionStorage.getItem(TICKET_ID_KEY) || '')
const token = ref(sessionStorage.getItem(TOKEN_KEY) || '')
const ticket = ref<PublicTicket | null>(null)
const attachments = ref<Attachment[]>([])
const loading = ref(false)
const creating = ref(false)
const uploading = ref(false)
const replyContent = ref('')
const rating = ref(0)
const csatComment = ref('')
const createError = ref('')
const lookupError = ref('')
const attachmentInput = ref<HTMLInputElement | null>(null)

const createForm = reactive({ subject: '', description: '', name: '', email: '', website: '' })
const lookupForm = reactive({ id: '', token: '' })

const closed = computed(() => ticket.value?.status === 'CLOSED')
const alreadyRated = computed(() => Boolean(ticket.value?.csatRating))

const errorMap: Record<string, string> = {
  invalid_channel: '渠道无效，请检查访问链接',
  invalid_request: '请求不合法',
  rate_limited: '请求过于频繁，请稍后再试',
  ticket_not_found: '工单不存在或查询码已过期',
  ticket_not_closed: '工单关闭后才能评价',
}

function friendly(error: unknown) {
  const code = errorCode(error)
  return errorMap[code] || code
}

async function portalApi<T>(path: string, options: RequestInit = {}, withToken = false): Promise<T> {
  const headers = new Headers(options.headers)
  if (withToken) headers.set('X-Ticket-Token', token.value)
  else headers.set('X-Channel-Key', channelKey)
  if (options.body && !(options.body instanceof FormData) && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }
  const response = await fetch(apiUrl(path), { ...options, headers })
  if (!response.ok) throw new Error(await response.text())
  if (response.status === 204) return undefined as T
  return response.json() as Promise<T>
}

function saveSession(id: string, nextToken: string) {
  ticketId.value = id
  token.value = nextToken
  sessionStorage.setItem(TICKET_ID_KEY, id)
  sessionStorage.setItem(TOKEN_KEY, nextToken)
}

async function createTicket() {
  createError.value = ''
  if (!createForm.subject.trim() || !createForm.description.trim()) {
    createError.value = '请填写标题和问题描述'
    return
  }
  creating.value = true
  try {
    const created = await portalApi<CreatePublicTicketResponse>('/api/public/tickets', {
      method: 'POST',
      body: JSON.stringify({
        subject: createForm.subject.trim(),
        description: createForm.description.trim(),
        name: createForm.name.trim(),
        email: createForm.email.trim(),
        website: createForm.website,
      }),
    })
    saveSession(created.ticketId, created.token)
    await loadTicket(false)
    ElMessage.success('工单已提交，请保存查询码')
  } catch (error) {
    createError.value = friendly(error)
  } finally {
    creating.value = false
  }
}

async function loadTicket(silent = false) {
  if (!ticketId.value || !token.value) return
  loading.value = true
  try {
    ticket.value = await portalApi<PublicTicket>(`/api/public/tickets/${ticketId.value}`, {}, true)
    rating.value = ticket.value.csatRating || 0
    csatComment.value = ticket.value.csatComment || ''
    await loadAttachments()
  } catch (error) {
    sessionStorage.removeItem(TICKET_ID_KEY)
    sessionStorage.removeItem(TOKEN_KEY)
    ticketId.value = ''
    token.value = ''
    ticket.value = null
    if (!silent) throw error
    ElMessage.error(friendly(error))
  } finally {
    loading.value = false
  }
}

async function loadAttachments() {
  if (!ticketId.value || !token.value) return
  try {
    attachments.value = await portalApi<Attachment[]>(`/api/public/tickets/${ticketId.value}/attachments`, {}, true)
  } catch {
    attachments.value = []
  }
}

function triggerUpload() {
  attachmentInput.value?.click()
}

async function onFilePicked(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  const form = new FormData()
  form.append('file', file)
  uploading.value = true
  try {
    await fetch(apiUrl(`/api/public/tickets/${ticketId.value}/attachments`), {
      method: 'POST',
      headers: { 'X-Ticket-Token': token.value },
      body: form,
    }).then(async (response) => {
      if (!response.ok) throw new Error(await response.text())
    })
    input.value = ''
    await loadAttachments()
    ElMessage.success('附件已上传')
  } catch (error) {
    ElMessage.error(friendly(error))
  } finally {
    uploading.value = false
  }
}

async function downloadAttachment(attachment: Attachment) {
  try {
    const response = await fetch(apiUrl(`/api/public/tickets/${ticketId.value}/attachments/${attachment.id}`), {
      headers: { 'X-Ticket-Token': token.value },
    })
    if (!response.ok) throw new Error(await response.text())
    const blob = await response.blob()
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = attachment.filename
    document.body.appendChild(link)
    link.click()
    link.remove()
    URL.revokeObjectURL(url)
  } catch (error) {
    ElMessage.error(friendly(error))
  }
}

async function reply() {
  if (!replyContent.value.trim()) return
  try {
    ticket.value = await portalApi<PublicTicket>(`/api/public/tickets/${ticketId.value}/messages`, {
      method: 'POST',
      body: JSON.stringify({ content: replyContent.value.trim() }),
    }, true)
    replyContent.value = ''
    ElMessage.success('已发送')
  } catch (error) {
    ElMessage.error(friendly(error))
  }
}

async function submitCsat() {
  if (!rating.value) {
    ElMessage.warning('请先选择评分')
    return
  }
  try {
    await portalApi(`/api/public/tickets/${ticketId.value}/csat`, {
      method: 'POST',
      body: JSON.stringify({ rating: rating.value, comment: csatComment.value.trim() }),
    }, true)
    await loadTicket(false)
    ElMessage.success('感谢评价')
  } catch (error) {
    ElMessage.error(friendly(error))
  }
}

async function lookup() {
  lookupError.value = ''
  if (!lookupForm.id.trim() || !lookupForm.token.trim()) {
    lookupError.value = '请填写工单 ID 和查询码'
    return
  }
  saveSession(lookupForm.id.trim(), lookupForm.token.trim())
  try {
    await loadTicket()
  } catch (error) {
    lookupError.value = friendly(error)
  }
}

async function copyToken() {
  await navigator.clipboard.writeText(token.value)
  ElMessage.success('查询码已复制')
}

onMounted(() => {
  if (ticketId.value && token.value) loadTicket(true)
})
</script>

<template>
  <div class="portal-page">
    <header class="portal-top">
      <div class="brand">
        <span class="brand-mark small">H</span>
        <span>Helpdesk 客户中心</span>
      </div>
      <div class="muted small">渠道：{{ channelKey }}</div>
    </header>

    <main v-loading="loading" class="portal-main">
      <template v-if="!ticket">
        <el-card class="portal-card" shadow="never">
          <h1>提交问题</h1>
          <p class="muted">留下联系方式，客服会尽快处理。</p>
          <div class="portal-form">
            <el-form-item label="标题">
              <el-input v-model="createForm.subject" maxlength="255" placeholder="例如：订单退货咨询" />
            </el-form-item>
            <el-form-item label="问题描述">
              <el-input v-model="createForm.description" type="textarea" :rows="5" maxlength="20000" placeholder="请描述遇到的问题、订单号或期望的处理方式" />
            </el-form-item>
            <div class="portal-grid">
              <el-form-item label="称呼"><el-input v-model="createForm.name" maxlength="128" placeholder="可选" /></el-form-item>
              <el-form-item label="邮箱"><el-input v-model="createForm.email" type="email" maxlength="255" placeholder="可选" /></el-form-item>
            </div>
            <el-input v-model="createForm.website" style="display: none" tabindex="-1" autocomplete="off" />
            <el-alert v-if="createError" :title="createError" type="error" :closable="false" show-icon />
            <el-button type="primary" :loading="creating" @click="createTicket">提交工单</el-button>
          </div>
        </el-card>

        <el-card class="portal-card" shadow="never">
          <h2>查询已有工单</h2>
          <div class="portal-form">
            <el-form-item label="工单 ID"><el-input v-model="lookupForm.id" placeholder="创建工单时返回的 ID" /></el-form-item>
            <el-form-item label="查询码"><el-input v-model="lookupForm.token" placeholder="创建工单时返回的查询码" /></el-form-item>
            <el-alert v-if="lookupError" :title="lookupError" type="error" :closable="false" show-icon />
            <el-button @click="lookup">查询</el-button>
          </div>
        </el-card>
      </template>

      <el-card v-else class="portal-card" shadow="never">
        <div class="detail-header">
          <div>
            <h2>{{ ticket.subject }}</h2>
            <div class="detail-meta">
              <el-tag size="small" :type="statusType(ticket.status)">{{ statusLabel(ticket.status) }}</el-tag>
              · {{ categoryLabel(ticket.category) }} · {{ formatDateTime(ticket.createdAt) }}
            </div>
          </div>
          <el-button :icon="Refresh" @click="loadTicket">刷新</el-button>
        </div>

        <div class="token-box">
          <div>
            <div class="muted small">查询码（请保存，刷新页面后需要用它查询）</div>
            <code>{{ token }}</code>
          </div>
          <el-button text type="primary" :icon="CopyDocument" @click="copyToken">复制</el-button>
        </div>

        <div v-if="attachments.length" class="attachment-list">
          <div v-for="attachment in attachments" :key="attachment.id" class="attachment-row">
            <span>{{ attachment.filename }} · {{ formatSize(attachment.sizeBytes) }}</span>
            <el-button text type="primary" @click="downloadAttachment(attachment)">下载</el-button>
          </div>
        </div>

        <div v-if="!closed" class="upload-row">
          <input ref="attachmentInput" type="file" hidden accept="image/png,image/jpeg,image/gif,image/webp,application/pdf,text/plain" @change="onFilePicked" />
          <el-button :icon="UploadFilled" :loading="uploading" @click="triggerUpload">上传附件</el-button>
        </div>

        <div class="portal-messages">
          <div v-for="(message, index) in ticket.messages" :key="index" class="portal-message" :class="message.authorType.toLowerCase()">
            <div class="meta">{{ authorLabel(message.authorType) }} · {{ formatDateTime(message.createdAt) }}</div>
            <div class="body">{{ message.content }}</div>
          </div>
        </div>

        <div v-if="!closed" class="portal-form">
          <el-input v-model="replyContent" type="textarea" :rows="3" placeholder="补充说明或回复客服" />
          <el-button type="primary" @click="reply">发送</el-button>
        </div>

        <div v-if="closed" class="portal-form">
          <template v-if="alreadyRated">
            <el-alert :title="`感谢评价：${ticket.csatRating}/5`" type="success" :closable="false" show-icon />
            <div v-if="ticket.csatComment" class="muted small">{{ ticket.csatComment }}</div>
          </template>
          <template v-else>
            <div class="muted small">问题已关闭，请评价本次服务</div>
            <el-rate v-model="rating" />
            <el-input v-model="csatComment" type="textarea" :rows="2" placeholder="补充评价（可选）" maxlength="1000" />
            <el-button @click="submitCsat">提交评价</el-button>
          </template>
        </div>
      </el-card>
    </main>
  </div>
</template>
