<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Download, Plus, Refresh, Search, UploadFilled } from '@element-plus/icons-vue'
import { api, apiUrl, download, friendlyError, getToken } from '@/api'
import type {
  Agent, Attachment, CurrentUser, Ticket, TicketDetail, TicketMessage, TicketPage, ToolCall,
} from '@/types'
import {
  CATEGORY_LABELS, PRIORITY_LABELS, authorLabel, categoryLabel, formatDateTime, formatSize,
  priorityLabel, statusLabel,
} from '@/format'

defineProps<{ user: CurrentUser }>()
const emit = defineEmits<{ usage: [] }>()

const tickets = ref<Ticket[]>([])
const agents = ref<Agent[]>([])
const statusFilter = ref('')
const searchQuery = ref('')
const page = ref(1)
const pageSize = ref(20)
const total = ref(0)
const totalPages = ref(0)

const activeId = ref<string | null>(null)
const detail = ref<TicketDetail | null>(null)
const attachments = ref<Attachment[]>([])
const toolCalls = ref<ToolCall[]>([])
const loadingTickets = ref(false)
const loadingDetail = ref(false)
const creating = ref(false)
const createVisible = ref(false)

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

const createForm = reactive({ subject: '', description: '' })
const categoryOptions = Object.entries(CATEGORY_LABELS).map(([value, label]) => ({ value, label }))
const priorityOptions = Object.entries(PRIORITY_LABELS).map(([value, label]) => ({ value, label }))

const activeTicket = computed(() => detail.value?.ticket || null)
const canReply = computed(() => Boolean(activeId.value) && !streaming.value)

let listController: AbortController | null = null
let detailController: AbortController | null = null
let streamController: AbortController | null = null
let searchTimer: number | undefined

interface OpenTicketOptions {
  keepDraft?: boolean
}

function isAbort(error: unknown) {
  return typeof error === 'object' && error !== null && (error as { name?: string }).name === 'AbortError'
}

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

function queueTime(value: string) {
  const diff = Date.now() - new Date(value).getTime()
  if (diff < 60_000) return '刚刚'
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟前`
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} 小时前`
  return new Date(value).toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit' })
}

function statusClass(status: string) {
  return `status-${status.toLowerCase()}`
}

function customerLine(ticket: Ticket) {
  return ticket.customerName || ticket.customerEmail || categoryLabel(ticket.category)
}

function syncTriage(ticket: Ticket) {
  triage.category = ticket.category || 'OTHER'
  triage.priority = ticket.priority || 'NORMAL'
  triage.assigneeId = ticket.assigneeId || ''
}

function resetStream() {
  streamText.value = ''
  streamCitations.value = []
  streamMessageId.value = null
  streamDone.value = false
}

function cancelStream() {
  if (streamController) {
    streamController.abort()
    streamController = null
  }
  streaming.value = false
}

function scrollToBottom() {
  if (messageBox.value) messageBox.value.scrollTop = messageBox.value.scrollHeight
}

async function loadTickets(options: { selectFirst?: boolean } = {}) {
  listController?.abort()
  const controller = new AbortController()
  listController = controller
  loadingTickets.value = true
  try {
    const params = new URLSearchParams({ page: String(page.value), size: String(pageSize.value) })
    if (statusFilter.value) params.set('status', statusFilter.value)
    if (searchQuery.value.trim()) params.set('q', searchQuery.value.trim())
    const result = await api<TicketPage>(`/api/tickets/page?${params.toString()}`, { signal: controller.signal })
    if (controller.signal.aborted) return
    tickets.value = result.items
    total.value = result.total
    totalPages.value = result.totalPages
    const lastPage = Math.max(1, result.totalPages)
    if (page.value > lastPage) {
      page.value = lastPage
      await loadTickets(options)
      return
    }
    if (options.selectFirst && result.items.length) {
      const current = result.items.find((ticket) => ticket.id === activeId.value)
      if (!current) await openTicket(result.items[0].id)
    }
  } catch (error) {
    if (!isAbort(error)) ElMessage.error(friendlyError(error))
  } finally {
    if (listController === controller) loadingTickets.value = false
  }
}

async function loadAgents() {
  try {
    agents.value = await api<Agent[]>('/api/users/agents')
  } catch {
    agents.value = []
  }
}

async function openTicket(id: string, options: OpenTicketOptions = {}) {
  detailController?.abort()
  cancelStream()
  const controller = new AbortController()
  detailController = controller
  activeId.value = id
  loadingDetail.value = true
  if (!options.keepDraft) {
    detail.value = null
    attachments.value = []
    toolCalls.value = []
    replyContent.value = ''
    internalNote.value = false
  }
  resetStream()
  try {
    const next = await api<TicketDetail>(`/api/tickets/${id}`, { signal: controller.signal })
    if (controller.signal.aborted) return
    detail.value = next
    syncTriage(next.ticket)
    const [nextAttachments, nextToolCalls] = await Promise.all([
      api<Attachment[]>(`/api/tickets/${id}/attachments`, { signal: controller.signal }).catch(() => []),
      api<ToolCall[]>(`/api/tickets/${id}/tool-calls`, { signal: controller.signal }).catch(() => []),
    ])
    if (controller.signal.aborted) return
    attachments.value = nextAttachments
    toolCalls.value = nextToolCalls
    await nextTick()
    scrollToBottom()
  } catch (error) {
    if (!isAbort(error)) ElMessage.error(friendlyError(error))
  } finally {
    if (detailController === controller) loadingDetail.value = false
  }
}

async function refreshAll() {
  await loadTickets({ selectFirst: !activeId.value })
  if (activeId.value) await openTicket(activeId.value, { keepDraft: true })
}

async function reloadDetail() {
  if (!activeId.value) return
  detailController?.abort()
  const controller = new AbortController()
  detailController = controller
  loadingDetail.value = true
  try {
    const next = await api<TicketDetail>(`/api/tickets/${activeId.value}`, { signal: controller.signal })
    if (controller.signal.aborted) return
    detail.value = next
    syncTriage(next.ticket)
    patchTicket(next.ticket)
    resetStream()
    await nextTick()
    scrollToBottom()
  } catch (error) {
    if (!isAbort(error)) ElMessage.error(friendlyError(error))
  } finally {
    if (detailController === controller) loadingDetail.value = false
  }
}

function onStatusChange() {
  page.value = 1
  loadTickets({ selectFirst: true })
}

function onPageChange(next: number) {
  page.value = next
  loadTickets({ selectFirst: true })
}

function onSearchInput() {
  if (searchTimer) window.clearTimeout(searchTimer)
  searchTimer = window.setTimeout(() => {
    page.value = 1
    loadTickets({ selectFirst: true })
  }, 300)
}

function onSearchClear() {
  if (searchTimer) window.clearTimeout(searchTimer)
  page.value = 1
  loadTickets({ selectFirst: true })
}

async function createTicket() {
  if (!createForm.subject.trim() || !createForm.description.trim()) {
    ElMessage.warning('请填写标题和问题描述')
    return
  }
  creating.value = true
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
    createVisible.value = false
    statusFilter.value = ''
    searchQuery.value = ''
    page.value = 1
    await loadTickets()
    await openTicket(ticket.id)
  } catch (error) {
    ElMessage.error(friendlyError(error))
  } finally {
    creating.value = false
  }
}

function patchTicket(updated: Ticket) {
  const index = tickets.value.findIndex((ticket) => ticket.id === updated.id)
  if (index >= 0) {
    const query = searchQuery.value.trim().toLowerCase()
    const matchesStatus = !statusFilter.value || updated.status === statusFilter.value
    const matchesQuery = !query
      || updated.subject.toLowerCase().includes(query)
      || (updated.customerName || '').toLowerCase().includes(query)
      || (updated.customerEmail || '').toLowerCase().includes(query)
    if (matchesStatus && matchesQuery) {
      tickets.value[index] = updated
    } else {
      tickets.value.splice(index, 1)
      total.value = Math.max(0, total.value - 1)
      totalPages.value = Math.ceil(total.value / pageSize.value)
      const lastPage = Math.max(1, totalPages.value)
      if (page.value > lastPage) {
        page.value = lastPage
        loadTickets()
      }
    }
  }
  if (detail.value?.ticket.id === updated.id) detail.value.ticket = updated
}

async function retriage() {
  if (!activeId.value) return
  try {
    const updated = await api<Ticket>(`/api/tickets/${activeId.value}/triage`, { method: 'POST' })
    patchTicket(updated)
    syncTriage(updated)
    ElMessage.success('已重新分诊')
  } catch (error) {
    ElMessage.error(friendlyError(error))
  }
}

async function applyTriage() {
  if (!activeId.value) return
  try {
    const updated = await api<Ticket>(`/api/tickets/${activeId.value}/triage`, {
      method: 'PATCH',
      body: JSON.stringify({
        category: triage.category,
        priority: triage.priority,
        assigneeId: triage.assigneeId || null,
      }),
    })
    patchTicket(updated)
    syncTriage(updated)
    ElMessage.success('分诊已保存')
  } catch (error) {
    ElMessage.error(friendlyError(error))
  }
}

async function sendReply() {
  if (!activeId.value || !replyContent.value.trim()) return
  const wasInternal = internalNote.value
  const endpoint = wasInternal
    ? `/api/tickets/${activeId.value}/notes`
    : `/api/tickets/${activeId.value}/messages`
  try {
    const message = await api<TicketMessage>(endpoint, {
      method: 'POST',
      body: JSON.stringify({ content: replyContent.value.trim() }),
    })
    if (detail.value) {
      detail.value.messages.push(message)
      const now = new Date().toISOString()
      const updated = { ...detail.value.ticket, updatedAt: now }
      if (!wasInternal) {
        updated.status = 'PENDING'
        updated.firstResponseAt = updated.firstResponseAt || now
      }
      detail.value.ticket = updated
      patchTicket(updated)
    }
    replyContent.value = ''
    internalNote.value = false
    await nextTick()
    scrollToBottom()
  } catch (error) {
    ElMessage.error(friendlyError(error))
  }
}

async function closeTicket() {
  if (!activeId.value) return
  try {
    await ElMessageBox.confirm('确认关闭这个工单？', '关闭工单', { type: 'warning' })
    const updated = await api<Ticket>(`/api/tickets/${activeId.value}/status?status=CLOSED`, {
      method: 'PATCH',
    })
    patchTicket(updated)
    ElMessage.success('工单已关闭')
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
    const uploaded = await api<Attachment>(`/api/tickets/${activeId.value}/attachments`, {
      method: 'POST',
      body: form,
    })
    attachments.value.push(uploaded)
    input.value = ''
    attachmentInternal.value = false
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
  const controller = new AbortController()
  streamController = controller
  streaming.value = true
  resetStream()
  try {
    const response = await fetch(apiUrl(`/api/tickets/${activeId.value}/ai-suggest`), {
      method: 'POST',
      headers: { Authorization: `Bearer ${getToken()}` },
      signal: controller.signal,
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
      scrollToBottom()
    }
  } catch (error) {
    if (!isAbort(error)) streamText.value += `\n[生成失败：${friendlyError(error)}]`
  } finally {
    if (streamController === controller) {
      streaming.value = false
      streamController = null
      emit('usage')
    }
  }
}

async function feedback(action: 'ADOPT' | 'EDIT' | 'REJECT', editedContent?: string) {
  if (!activeId.value || !streamMessageId.value) return
  try {
    await api(`/api/tickets/${activeId.value}/messages/${streamMessageId.value}/feedback`, {
      method: 'POST',
      body: JSON.stringify({ action, editedContent: editedContent || null }),
    })
    await reloadDetail()
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
  await Promise.all([loadTickets({ selectFirst: true }), loadAgents()])
})

onBeforeUnmount(() => {
  listController?.abort()
  detailController?.abort()
  cancelStream()
  if (searchTimer) window.clearTimeout(searchTimer)
})
</script>

<template>
  <div class="tickets-layout">
    <aside class="queue-pane">
      <div class="queue-head">
        <div class="queue-title-row">
          <div>
            <div class="pane-title">工单队列</div>
            <div class="pane-sub">共 {{ total }} 条</div>
          </div>
          <div class="queue-actions">
            <el-button size="small" :icon="Refresh" circle :loading="loadingTickets" @click="refreshAll" />
            <el-button size="small" type="primary" :icon="Plus" data-testid="ticket-create" @click="createVisible = true">新建</el-button>
          </div>
        </div>
        <el-input
          v-model="searchQuery"
          size="small"
          clearable
          placeholder="搜索标题、客户"
          :prefix-icon="Search"
          @input="onSearchInput"
          @clear="onSearchClear"
          @keyup.enter="onSearchInput"
        />
        <el-radio-group v-model="statusFilter" size="small" class="status-tabs" @change="onStatusChange">
          <el-radio-button label="">全部</el-radio-button>
          <el-radio-button label="OPEN">待处理</el-radio-button>
          <el-radio-button label="PENDING">处理中</el-radio-button>
          <el-radio-button label="CLOSED">已关闭</el-radio-button>
        </el-radio-group>
      </div>

      <div v-loading="loadingTickets" class="queue-list">
        <button
          v-for="ticket in tickets"
          :key="ticket.id"
          class="queue-item"
          :class="{ active: ticket.id === activeId, breached: ticket.slaBreached }"
          data-testid="ticket-item"
          @click="openTicket(ticket.id)"
        >
          <div class="queue-item-top">
            <span class="queue-subject">{{ ticket.subject }}</span>
            <span class="queue-time">{{ queueTime(ticket.updatedAt) }}</span>
          </div>
          <div class="queue-item-meta">
            <span class="status-dot" :class="statusClass(ticket.status)" />
            <span>{{ statusLabel(ticket.status) }}</span>
            <span class="dot-sep">·</span>
            <span>{{ priorityLabel(ticket.priority) }}</span>
            <span class="dot-sep">·</span>
            <span class="truncate">{{ customerLine(ticket) }}</span>
            <span v-if="ticket.slaBreached" class="sla-flag">SLA</span>
          </div>
        </button>
        <div v-if="!tickets.length && !loadingTickets" class="queue-empty">没有匹配的工单</div>
      </div>

      <div class="queue-foot">
        <span class="pane-sub">第 {{ page }} / {{ Math.max(1, totalPages) }} 页</span>
        <el-pagination
          small
          background
          layout="prev, pager, next"
          :total="total"
          :page-size="pageSize"
          :current-page="page"
          @current-change="onPageChange"
        />
      </div>
    </aside>

    <section class="detail-pane">
      <div v-if="!activeTicket && loadingDetail" class="detail-empty">加载中…</div>
      <div v-else-if="!activeTicket" class="detail-empty">
        <div class="empty-title">未选择工单</div>
        <div class="pane-sub">从左侧队列选择一条工单</div>
      </div>
      <template v-else>
        <header class="detail-head">
          <div class="detail-head-main">
            <div class="detail-kicker">
              {{ activeTicket.id.slice(0, 8).toUpperCase() }} · {{ categoryLabel(activeTicket.category) }}
            </div>
            <h1 data-testid="ticket-detail-subject">{{ activeTicket.subject }}</h1>
            <div class="detail-meta-line">
              <span class="status-dot" :class="statusClass(activeTicket.status)" />
              <span>{{ statusLabel(activeTicket.status) }}</span>
              <span class="dot-sep">·</span>
              <span>{{ priorityLabel(activeTicket.priority) }}</span>
              <span class="dot-sep">·</span>
              <span>{{ formatDateTime(activeTicket.createdAt) }}</span>
              <span v-if="activeTicket.slaBreached" class="sla-flag">SLA 超时</span>
            </div>
          </div>
          <div class="detail-actions">
            <el-button size="small" type="primary" :loading="streaming" data-testid="ticket-ai" @click="suggest">AI 建议</el-button>
            <el-button size="small" :disabled="activeTicket.status === 'CLOSED'" data-testid="ticket-close" @click="closeTicket">关闭工单</el-button>
          </div>
        </header>

        <div v-loading="loadingDetail" class="conversation">
          <div ref="messageBox" class="conversation-scroll">
            <div
              v-for="message in detail?.messages || []"
              :key="message.id"
              class="msg"
              :class="[message.authorType.toLowerCase(), { internal: message.visibility === 'INTERNAL' }]"
            >
              <div class="msg-head">
                <span class="msg-author">{{ authorLabel(message.authorType) }}</span>
                <span v-if="message.visibility === 'INTERNAL'" class="msg-flag">内部</span>
                <span class="msg-time">{{ formatDateTime(message.createdAt) }}</span>
                <span v-if="message.tokens" class="msg-time">{{ message.tokens }} tokens</span>
              </div>
              <div class="msg-body">{{ message.content }}</div>
              <div v-if="messageCitations(message).length" class="cite-row">
                <span v-for="(citation, index) in messageCitations(message)" :key="index" class="cite">
                  {{ citation.title }}{{ citation.heading ? ' / ' + citation.heading : '' }}
                </span>
              </div>
            </div>

            <div v-if="streaming || streamText" class="msg ai">
              <div class="msg-head">
                <span class="msg-author">AI 草稿</span>
                <span class="msg-time">{{ streaming ? '生成中' : '待处理' }}</span>
              </div>
              <div class="msg-body" :class="{ cursor: streaming }">{{ streamText || '正在检索知识库…' }}</div>
              <div v-if="streamDone && streamMessageId" class="ai-actions">
                <el-button size="small" type="primary" @click="feedback('ADOPT')">采纳</el-button>
                <el-button size="small" @click="openEdit">修改后发送</el-button>
                <el-button size="small" type="danger" plain @click="feedback('REJECT')">驳回</el-button>
              </div>
            </div>

            <div v-if="!detail?.messages.length && !streamText" class="conversation-empty">暂无消息</div>
          </div>

          <div class="composer">
            <el-input
              v-model="replyContent"
              type="textarea"
              :rows="3"
              resize="none"
              placeholder="人工回复客户，Ctrl+Enter 发送"
              data-testid="ticket-reply"
              @keydown.ctrl.enter="sendReply"
              @keydown.meta.enter="sendReply"
            />
            <div class="composer-foot">
              <el-checkbox v-model="internalNote">内部备注</el-checkbox>
              <span class="pane-sub">Ctrl + Enter 发送</span>
              <el-button type="primary" :disabled="!canReply" data-testid="ticket-send" @click="sendReply">发送回复</el-button>
            </div>
          </div>
        </div>
      </template>
    </section>

    <aside v-if="activeTicket" class="props-pane">
      <div class="prop-block">
        <div class="prop-title">分诊</div>
        <div class="prop-row"><span>分类</span><b>{{ categoryLabel(activeTicket.category) }}</b></div>
        <div class="prop-row"><span>优先级</span><b>{{ priorityLabel(activeTicket.priority) }}</b></div>
        <div class="prop-row">
          <span>负责人</span>
          <b>{{ agents.find((agent) => agent.id === activeTicket?.assigneeId)?.displayName || '未分配' }}</b>
        </div>
        <div class="prop-reason">{{ activeTicket.triageSource || 'RULE' }} · {{ activeTicket.triageReason || '未记录' }}</div>

        <el-select v-model="triage.category" size="small" class="prop-control">
          <el-option v-for="option in categoryOptions" :key="option.value" :label="option.label" :value="option.value" />
        </el-select>
        <el-select v-model="triage.priority" size="small" class="prop-control">
          <el-option v-for="option in priorityOptions" :key="option.value" :label="option.label" :value="option.value" />
        </el-select>
        <el-select v-model="triage.assigneeId" size="small" class="prop-control" placeholder="负责人">
          <el-option label="不指定" value="" />
          <el-option v-for="agent in agents" :key="agent.id" :label="`${agent.displayName} · ${agent.email}`" :value="agent.id" />
        </el-select>
        <div class="prop-actions">
          <el-button size="small" type="primary" @click="applyTriage">保存分诊</el-button>
          <el-button size="small" text @click="retriage">重新分诊</el-button>
        </div>
      </div>

      <div class="prop-block">
        <div class="prop-title">客户</div>
        <div class="prop-value">{{ activeTicket.customerName || '未填写称呼' }}</div>
        <div class="pane-sub">{{ activeTicket.customerEmail || '无邮箱' }}</div>
      </div>

      <div v-if="activeTicket.csatRating" class="prop-block">
        <div class="prop-title">客户评分</div>
        <el-rate :model-value="activeTicket.csatRating" disabled size="small" />
        <div class="pane-sub">{{ activeTicket.csatComment || '无补充评价' }}</div>
      </div>

      <div class="prop-block">
        <div class="prop-title">附件</div>
        <div v-for="attachment in attachments" :key="attachment.id" class="prop-file">
          <div class="prop-file-name">
            <span class="truncate">{{ attachment.filename }}</span>
            <span class="pane-sub">{{ formatSize(attachment.sizeBytes) }}</span>
          </div>
          <div class="prop-file-actions">
            <span class="file-visibility">{{ attachment.visibility === 'INTERNAL' ? '内部' : '客户可见' }}</span>
            <el-button text size="small" :icon="Download" @click="downloadAttachment(attachment)" />
          </div>
        </div>
        <div v-if="!attachments.length" class="pane-sub">无附件</div>
        <input ref="attachmentInput" type="file" hidden accept="image/png,image/jpeg,image/gif,image/webp,application/pdf,text/plain" @change="onFilePicked" />
        <div class="prop-actions">
          <el-button size="small" :icon="UploadFilled" :loading="uploading" @click="triggerUpload">上传</el-button>
          <el-checkbox v-model="attachmentInternal" size="small">内部</el-checkbox>
        </div>
      </div>

      <div v-if="toolCalls.length" class="prop-block">
        <div class="prop-title">工具调用</div>
        <div v-for="call in toolCalls" :key="call.id" class="tool-call">
          <div>{{ call.tool_name }} · {{ call.status }}</div>
          <div class="pane-sub">{{ parseToolResult(call) }}</div>
        </div>
      </div>

      <div class="prop-block">
        <div class="prop-title">时间</div>
        <div class="prop-row"><span>创建</span><b>{{ formatDateTime(activeTicket.createdAt) }}</b></div>
        <div class="prop-row"><span>首次响应</span><b>{{ formatDateTime(activeTicket.firstResponseAt) }}</b></div>
        <div class="prop-row"><span>SLA 截止</span><b>{{ formatDateTime(activeTicket.slaDueAt) }}</b></div>
      </div>
    </aside>

    <el-drawer v-model="createVisible" title="新建工单" size="420px">
      <div class="drawer-form">
        <label class="field-label">标题</label>
        <el-input v-model="createForm.subject" placeholder="工单标题" maxlength="255" data-testid="ticket-subject" />
        <label class="field-label">问题描述</label>
        <el-input v-model="createForm.description" type="textarea" :rows="6" placeholder="客户问题描述" maxlength="20000" data-testid="ticket-description" />
        <div class="drawer-actions">
          <el-button @click="createVisible = false">取消</el-button>
          <el-button type="primary" :loading="creating" @click="createTicket">新建工单</el-button>
        </div>
      </div>
    </el-drawer>

    <el-dialog v-model="editVisible" title="修改后发送" width="640px">
      <el-input v-model="editContent" type="textarea" :rows="10" />
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" @click="confirmEdit">发送</el-button>
      </template>
    </el-dialog>
  </div>
</template>
