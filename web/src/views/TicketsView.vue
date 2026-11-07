<script setup lang="ts">
import { computed, onMounted, reactive, ref, nextTick } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Download, Plus, Refresh, UploadFilled } from '@element-plus/icons-vue'
import { api, apiUrl, download, friendlyError, getToken } from '@/api'
import type {
  Agent, Attachment, CurrentUser, Ticket, TicketDetail, TicketMessage, ToolCall,
} from '@/types'
import {
  CATEGORY_LABELS, PRIORITY_LABELS, authorLabel, categoryLabel, formatDateTime, formatSize,
  priorityLabel, priorityType, statusLabel, statusType,
} from '@/format'

defineProps<{ user: CurrentUser }>()
const emit = defineEmits<{ usage: [] }>()

const tickets = ref<Ticket[]>([])
const agents = ref<Agent[]>([])
const statusFilter = ref('')
const activeId = ref<string | null>(null)
const detail = ref<TicketDetail | null>(null)
const attachments = ref<Attachment[]>([])
const toolCalls = ref<ToolCall[]>([])
const loadingTickets = ref(false)
const loadingDetail = ref(false)

const createForm = reactive({ subject: '', description: '' })
const triage = reactive({ category: 'OTHER', priority: 'NORMAL', assigneeId: '' })
const replyContent = ref('')
const internalNote = ref(false)
const uploading = ref(false)
const attachmentInternal = ref(false)
const attachmentInput = ref<HTMLInputElement | null>(null)

const streaming = ref(false)
const streamText = ref('')
const streamCitations = ref<Array<{ title: string; heading?: string; score?: number }>>([])
const streamMessageId = ref<string | null>(null)
const streamDone = ref(false)
const messageBox = ref<HTMLElement | null>(null)

const editVisible = ref(false)
const editContent = ref('')

const categoryOptions = Object.entries(CATEGORY_LABELS).map(([value, label]) => ({ value, label }))
const priorityOptions = Object.entries(PRIORITY_LABELS).map(([value, label]) => ({ value, label }))

const activeTicket = computed(() => detail.value?.ticket || null)
const canReply = computed(() => Boolean(activeId.value) && !streaming.value)

function parseCitations(raw?: string | null) {
  if (!raw) return []
  try {
    const parsed = JSON.parse(raw)
    return Array.isArray(parsed) ? parsed : []
  } catch {
    return []
  }
}

function messageCitations(message: TicketMessage) {
  return parseCitations(message.citations)
}

async function loadTickets() {
  loadingTickets.value = true
  try {
    const suffix = statusFilter.value ? `?status=${encodeURIComponent(statusFilter.value)}` : ''
    tickets.value = await api<Ticket[]>(`/api/tickets${suffix}`)
  } catch (error) {
    ElMessage.error(friendlyError(error))
  } finally {
    loadingTickets.value = false
  }
}

async function loadAgents() {
  try {
    agents.value = await api<Agent[]>('/api/users/agents')
  } catch {
    agents.value = []
  }
}

async function createTicket() {
  if (!createForm.subject.trim() || !createForm.description.trim()) {
    ElMessage.warning('请填写标题和问题描述')
    return
  }
  try {
    const ticket = await api<Ticket>('/api/tickets', {
      method: 'POST',
      body: JSON.stringify({
        subject: createForm.subject.trim(),
        description: createForm.description.trim(),
      }),
    })
    createForm.subject = ''
    createForm.description = ''
    await loadTickets()
    await openTicket(ticket.id)
  } catch (error) {
    ElMessage.error(friendlyError(error))
  }
}

async function openTicket(id: string) {
  activeId.value = id
  loadingDetail.value = true
  try {
    detail.value = await api<TicketDetail>(`/api/tickets/${id}`)
    triage.category = detail.value.ticket.category || 'OTHER'
    triage.priority = detail.value.ticket.priority || 'NORMAL'
    triage.assigneeId = detail.value.ticket.assigneeId || ''
    replyContent.value = ''
    internalNote.value = false
    streamText.value = ''
    streamCitations.value = []
    streamMessageId.value = null
    streamDone.value = false
    await Promise.all([loadAttachments(id), loadToolCalls(id)])
    await nextTick()
    if (messageBox.value) messageBox.value.scrollTop = messageBox.value.scrollHeight
  } catch (error) {
    ElMessage.error(friendlyError(error))
  } finally {
    loadingDetail.value = false
  }
}

async function loadAttachments(ticketId: string) {
  try {
    attachments.value = await api<Attachment[]>(`/api/tickets/${ticketId}/attachments`)
  } catch {
    attachments.value = []
  }
}

async function loadToolCalls(ticketId: string) {
  try {
    toolCalls.value = await api<ToolCall[]>(`/api/tickets/${ticketId}/tool-calls`)
  } catch {
    toolCalls.value = []
  }
}

async function refreshCurrent() {
  await loadTickets()
  if (activeId.value) await openTicket(activeId.value)
}

async function retriage() {
  if (!activeId.value) return
  try {
    await api(`/api/tickets/${activeId.value}/triage`, { method: 'POST' })
    await refreshCurrent()
    ElMessage.success('已重新分诊')
  } catch (error) {
    ElMessage.error(friendlyError(error))
  }
}

async function applyTriage() {
  if (!activeId.value) return
  try {
    await api(`/api/tickets/${activeId.value}/triage`, {
      method: 'PATCH',
      body: JSON.stringify({
        category: triage.category,
        priority: triage.priority,
        assigneeId: triage.assigneeId || null,
      }),
    })
    await refreshCurrent()
    ElMessage.success('分诊已调整')
  } catch (error) {
    ElMessage.error(friendlyError(error))
  }
}

async function sendReply() {
  if (!activeId.value || !replyContent.value.trim()) return
  const endpoint = internalNote.value
    ? `/api/tickets/${activeId.value}/notes`
    : `/api/tickets/${activeId.value}/messages`
  try {
    await api(endpoint, {
      method: 'POST',
      body: JSON.stringify({ content: replyContent.value.trim() }),
    })
    replyContent.value = ''
    internalNote.value = false
    await refreshCurrent()
  } catch (error) {
    ElMessage.error(friendlyError(error))
  }
}

async function closeTicket() {
  if (!activeId.value) return
  try {
    await ElMessageBox.confirm('确认关闭这个工单？', '关闭工单', { type: 'warning' })
    await api(`/api/tickets/${activeId.value}/status?status=CLOSED`, { method: 'PATCH' })
    await refreshCurrent()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(friendlyError(error))
  }
}

function triggerUpload() {
  attachmentInput.value?.click()
}

async function onFilePicked(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file || !activeId.value) return
  const form = new FormData()
  form.append('file', file)
  form.append('visibility', attachmentInternal.value ? 'INTERNAL' : 'PUBLIC')
  uploading.value = true
  try {
    await api(`/api/tickets/${activeId.value}/attachments`, { method: 'POST', body: form })
    input.value = ''
    attachmentInternal.value = false
    await loadAttachments(activeId.value)
    ElMessage.success('附件已上传')
  } catch (error) {
    ElMessage.error(friendlyError(error))
  } finally {
    uploading.value = false
  }
}

async function downloadAttachment(attachment: Attachment) {
  try {
    await download(`/api/attachments/${attachment.id}`, attachment.filename)
  } catch (error) {
    ElMessage.error(friendlyError(error))
  }
}

function parseToolResult(call: ToolCall) {
  if (!call.result) return ''
  try {
    const parsed = JSON.parse(call.result)
    return parsed.orderNo ? `订单 ${parsed.orderNo} · ${parsed.status}` : call.result
  } catch {
    return call.result
  }
}

function handleEvent(chunk: string) {
  let event = 'message'
  const dataLines: string[] = []
  for (const line of chunk.split('\n')) {
    if (line.startsWith('event:')) event = line.slice(6).trim()
    else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim())
  }
  if (!dataLines.length) return
  const data = dataLines.join('\n')
  if (event === 'token') {
    streamText.value += JSON.parse(data).text || ''
  } else if (event === 'done') {
    const done = JSON.parse(data)
    streamMessageId.value = done.messageId || null
    streamCitations.value = parseCitations(done.citations)
    streamDone.value = true
  } else if (event === 'error') {
    streamText.value += `\n[${JSON.parse(data).message || '生成失败'}]`
  }
}

async function suggest() {
  if (!activeId.value || streaming.value) return
  streaming.value = true
  streamText.value = ''
  streamCitations.value = []
  streamMessageId.value = null
  streamDone.value = false
  try {
    const response = await fetch(apiUrl(`/api/tickets/${activeId.value}/ai-suggest`), {
      method: 'POST',
      headers: { Authorization: `Bearer ${getToken()}` },
    })
    if (!response.ok || !response.body) throw new Error(`HTTP ${response.status}`)
    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    while (true) {
      const { value, done } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      let index = buffer.indexOf('\n\n')
      while (index >= 0) {
        handleEvent(buffer.slice(0, index))
        buffer = buffer.slice(index + 2)
        index = buffer.indexOf('\n\n')
      }
      await nextTick()
      if (messageBox.value) messageBox.value.scrollTop = messageBox.value.scrollHeight
    }
  } catch (error) {
    streamText.value += `\n[生成失败：${friendlyError(error)}]`
  } finally {
    streaming.value = false
    emit('usage')
  }
}

async function feedback(action: 'ADOPT' | 'EDIT' | 'REJECT', editedContent?: string) {
  if (!activeId.value || !streamMessageId.value) return
  try {
    await api(`/api/tickets/${activeId.value}/messages/${streamMessageId.value}/feedback`, {
      method: 'POST',
      body: JSON.stringify({ action, editedContent: editedContent || null }),
    })
    await refreshCurrent()
    ElMessage.success(action === 'REJECT' ? '已驳回' : '已发送')
  } catch (error) {
    ElMessage.error(friendlyError(error))
  }
}

function openEdit() {
  editContent.value = streamText.value
  editVisible.value = true
}

async function confirmEdit() {
  if (!editContent.value.trim()) {
    ElMessage.warning('内容不能为空')
    return
  }
  editVisible.value = false
  await feedback('EDIT', editContent.value.trim())
}

onMounted(async () => {
  await Promise.all([loadTickets(), loadAgents()])
  if (tickets.value.length) await openTicket(tickets.value[0].id)
})
</script>

<template>
  <div class="tickets-layout">
    <aside class="ticket-sidebar">
      <el-card class="panel-card" shadow="never">
        <div class="create-form">
          <el-input v-model="createForm.subject" placeholder="工单标题" maxlength="255" />
          <el-input v-model="createForm.description" type="textarea" :rows="3" placeholder="客户问题描述" maxlength="20000" />
          <el-button type="primary" :icon="Plus" @click="createTicket">新建工单</el-button>
        </div>
      </el-card>

      <el-card class="panel-card ticket-sidebar" shadow="never">
        <div class="ticket-filter">
          <el-radio-group v-model="statusFilter" size="small" @change="loadTickets">
            <el-radio-button label="">全部</el-radio-button>
            <el-radio-button label="OPEN">待处理</el-radio-button>
            <el-radio-button label="PENDING">处理中</el-radio-button>
            <el-radio-button label="CLOSED">已关闭</el-radio-button>
          </el-radio-group>
          <el-button text :icon="Refresh" :loading="loadingTickets" @click="loadTickets" />
        </div>

        <ul v-loading="loadingTickets" class="ticket-list">
          <li
            v-for="ticket in tickets"
            :key="ticket.id"
            class="ticket-item"
            :class="{ active: ticket.id === activeId, 'sla-breached': ticket.slaBreached }"
            @click="openTicket(ticket.id)"
          >
            <div class="ticket-title">{{ ticket.subject }}</div>
            <div class="ticket-sub">
              <el-tag size="small" :type="statusType(ticket.status)">{{ statusLabel(ticket.status) }}</el-tag>
              <el-tag size="small" effect="plain" :type="priorityType(ticket.priority)">{{ priorityLabel(ticket.priority) }}</el-tag>
              <span class="muted small">{{ categoryLabel(ticket.category) }}</span>
              <el-tag v-if="ticket.slaBreached" size="small" type="danger" effect="dark">SLA</el-tag>
            </div>
          </li>
          <el-empty v-if="!tickets.length && !loadingTickets" description="暂无工单" :image-size="70" />
        </ul>
      </el-card>
    </aside>

    <el-card v-loading="loadingDetail" class="panel-card ticket-detail" shadow="never">
      <div v-if="!activeTicket" class="empty-detail">选择或新建一个工单</div>
      <template v-else>
        <div class="detail-header">
          <div>
            <h2>{{ activeTicket.subject }}</h2>
            <div class="detail-meta">
              <el-tag size="small" :type="statusType(activeTicket.status)">{{ statusLabel(activeTicket.status) }}</el-tag>
              <span> · {{ priorityLabel(activeTicket.priority) }} · {{ categoryLabel(activeTicket.category) }}</span>
              <span v-if="activeTicket.customerName || activeTicket.customerEmail">
                · {{ [activeTicket.customerName, activeTicket.customerEmail].filter(Boolean).join(' · ') }}
              </span>
              <span v-if="activeTicket.slaBreached"> · <el-tag size="small" type="danger">SLA 已超时</el-tag></span>
              <span> · {{ formatDateTime(activeTicket.createdAt) }}</span>
              <span v-if="activeTicket.slaDueAt"> · 首次响应截止 {{ formatDateTime(activeTicket.slaDueAt) }}</span>
            </div>
          </div>
          <div class="detail-actions">
            <el-button type="primary" :loading="streaming" @click="suggest">AI 建议回复</el-button>
            <el-button :disabled="activeTicket.status === 'CLOSED'" @click="closeTicket">关闭工单</el-button>
          </div>
        </div>

        <div class="triage-box">
          <div><span class="label">分类</span><el-tag size="small">{{ categoryLabel(activeTicket.category) }}</el-tag></div>
          <div><span class="label">优先级</span><el-tag size="small" :type="priorityType(activeTicket.priority)">{{ priorityLabel(activeTicket.priority) }}</el-tag></div>
          <div><span class="label">负责人</span><span>{{ agents.find((a) => a.id === activeTicket?.assigneeId)?.displayName || '未分配' }}</span></div>
          <div><span class="label">分诊依据</span><span class="muted small">{{ activeTicket.triageSource || 'RULE' }} · {{ activeTicket.triageReason || '未记录' }}</span></div>
          <el-button text type="primary" @click="retriage">重新分诊</el-button>
        </div>

        <div class="triage-edit">
          <el-select v-model="triage.category" placeholder="分类">
            <el-option v-for="option in categoryOptions" :key="option.value" :label="`${option.label}（${option.value}）`" :value="option.value" />
          </el-select>
          <el-select v-model="triage.priority" placeholder="优先级">
            <el-option v-for="option in priorityOptions" :key="option.value" :label="`${option.label}（${option.value}）`" :value="option.value" />
          </el-select>
          <el-select v-model="triage.assigneeId" placeholder="负责人">
            <el-option label="不指定" value="" />
            <el-option v-for="agent in agents" :key="agent.id" :label="`${agent.displayName} · ${agent.email}`" :value="agent.id" />
          </el-select>
          <el-button @click="applyTriage">调整分诊</el-button>
        </div>

        <div v-if="activeTicket.csatRating" class="triage-box">
          <div><span class="label">客户评分</span><el-rate :model-value="activeTicket.csatRating" disabled /></div>
          <div class="muted small">{{ activeTicket.csatComment || '无补充评价' }}</div>
        </div>

        <div v-if="attachments.length" class="attachment-list">
          <div v-for="attachment in attachments" :key="attachment.id" class="attachment-row">
            <span>
              {{ attachment.filename }} · {{ formatSize(attachment.sizeBytes) }}
              <el-tag size="small" :type="attachment.visibility === 'INTERNAL' ? 'warning' : 'success'" effect="plain">{{ attachment.visibility }}</el-tag>
            </span>
            <el-button text type="primary" :icon="Download" @click="downloadAttachment(attachment)">下载</el-button>
          </div>
        </div>

        <div v-if="toolCalls.length" class="attachment-list">
          <div v-for="call in toolCalls" :key="call.id" class="attachment-row">
            <span>工具 {{ call.tool_name }} · {{ call.status }} · {{ parseToolResult(call) }}</span>
          </div>
        </div>

        <div class="upload-row">
          <input ref="attachmentInput" type="file" hidden accept="image/png,image/jpeg,image/gif,image/webp,application/pdf,text/plain" @change="onFilePicked" />
          <el-button :icon="UploadFilled" :loading="uploading" @click="triggerUpload">上传附件</el-button>
          <el-checkbox v-model="attachmentInternal">内部附件</el-checkbox>
        </div>

        <div ref="messageBox" class="messages">
          <div v-for="message in detail?.messages || []" :key="message.id" class="message" :class="[message.authorType.toLowerCase(), { internal: message.visibility === 'INTERNAL' }]">
            <div class="message-meta">
              {{ authorLabel(message.authorType) }}
              <span v-if="message.visibility === 'INTERNAL'"> · 内部</span>
              <span v-if="message.tokens"> · {{ message.tokens }} tokens</span>
              <span> · {{ formatDateTime(message.createdAt) }}</span>
            </div>
            <div class="message-body">{{ message.content }}</div>
            <div v-if="messageCitations(message).length" class="citation-box">
              <b>引用来源</b>
              <span v-for="(citation, index) in messageCitations(message)" :key="index" class="citation-tag">
                {{ citation.title }}{{ citation.heading ? ' > ' + citation.heading : '' }}
              </span>
            </div>
          </div>

          <div v-if="streaming || streamText" class="message ai">
            <div class="message-meta">AI · 生成中</div>
            <div class="message-body" :class="{ cursor: streaming }">{{ streamText || '正在检索知识库…' }}</div>
            <div v-if="streamDone && streamMessageId" class="ai-actions">
              <el-button size="small" type="primary" @click="feedback('ADOPT')">采纳</el-button>
              <el-button size="small" @click="openEdit">修改后发送</el-button>
              <el-button size="small" type="danger" plain @click="feedback('REJECT')">驳回</el-button>
            </div>
          </div>
        </div>

        <div v-if="streamCitations.length" class="citation-box">
          <b>引用来源</b>
          <span v-for="(citation, index) in streamCitations" :key="index" class="citation-tag">
            {{ citation.title }}{{ citation.heading ? ' > ' + citation.heading : '' }}{{ citation.score !== undefined ? ' · ' + citation.score : '' }}
          </span>
        </div>

        <div class="reply-form">
          <el-input v-model="replyContent" type="textarea" :rows="3" placeholder="人工回复客户，Ctrl+Enter 发送" @keydown.ctrl.enter="sendReply" @keydown.meta.enter="sendReply" />
          <div class="reply-actions">
            <el-checkbox v-model="internalNote">内部备注</el-checkbox>
            <el-button type="primary" :disabled="!canReply" @click="sendReply">发送回复</el-button>
          </div>
        </div>
      </template>
    </el-card>

    <el-dialog v-model="editVisible" title="修改后发送" width="640px">
      <el-input v-model="editContent" type="textarea" :rows="10" />
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" @click="confirmEdit">发送</el-button>
      </template>
    </el-dialog>
  </div>
</template>
