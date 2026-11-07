<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Clock, Delete, Plus, Refresh, Search } from '@element-plus/icons-vue'
import { api, friendlyError } from '@/api'
import type {
  WikiPage, WikiPageDetail, WikiPageVersion, WikiPageVersionDetail, WikiSearchResult, WikiSpace,
} from '@/types'
import { formatDateTime } from '@/format'

interface SectionDraft {
  heading: string
  content: string
}

const spaces = ref<WikiSpace[]>([])
const pages = ref<WikiPage[]>([])
const selectedSpaceId = ref('')
const selectedPageId = ref<string | null>(null)
const title = ref('')
const visibility = ref('PUBLIC')
const sections = ref<SectionDraft[]>([{ heading: '', content: '' }])
const meta = ref('选择空间和页面，或新建一个页面')
const pageStatus = ref('DRAFT')
const loading = ref(false)
const saving = ref(false)

const versionDialogVisible = ref(false)
const versionLoading = ref(false)
const versions = ref<WikiPageVersion[]>([])
const selectedVersion = ref<WikiPageVersionDetail | null>(null)

const query = ref('')
const searching = ref(false)
const searchResult = ref<WikiSearchResult | null>(null)

function statusLabel(code?: string | null) {
  if (code === 'PUBLISHED') return '已发布'
  if (code === 'REVIEW') return '待审核'
  return '草稿'
}

function statusType(code?: string | null) {
  if (code === 'PUBLISHED') return 'success' as const
  if (code === 'REVIEW') return 'warning' as const
  return 'info' as const
}

async function loadSpaces() {
  try {
    spaces.value = await api<WikiSpace[]>('/api/wiki/spaces')
    if (!selectedSpaceId.value && spaces.value.length) {
      selectedSpaceId.value = spaces.value[0].id
      await loadPages()
    }
  } catch (error) {
    ElMessage.error(friendlyError(error))
  }
}

async function selectSpace(space: WikiSpace) {
  selectedSpaceId.value = space.id
  selectedPageId.value = null
  pageStatus.value = 'DRAFT'
  title.value = ''
  sections.value = [{ heading: '', content: '' }]
  meta.value = `${space.name} · 选择页面或新建`
  await loadPages()
}

async function loadPages() {
  if (!selectedSpaceId.value) return
  try {
    pages.value = await api<WikiPage[]>(`/api/wiki/pages?spaceId=${encodeURIComponent(selectedSpaceId.value)}&limit=100`)
  } catch (error) {
    pages.value = []
    ElMessage.error(friendlyError(error))
  }
}

function newPage() {
  if (!selectedSpaceId.value) {
    ElMessage.warning('先选择一个知识空间')
    return
  }
  selectedPageId.value = null
  pageStatus.value = 'DRAFT'
  title.value = ''
  visibility.value = 'PUBLIC'
  sections.value = [{ heading: '', content: '' }]
  meta.value = '新建页面 · 填写标题和小节后保存'
  searchResult.value = null
}

async function openPage(page: WikiPage) {
  loading.value = true
  try {
    const detail = await api<WikiPageDetail>(`/api/wiki/pages/${page.id}`)
    selectedPageId.value = page.id
    pageStatus.value = detail.page.status
    title.value = detail.page.title
    visibility.value = detail.page.visibility
    sections.value = detail.sections.map((section) => ({
      heading: (section.headingPath || '').split(' > ').pop() || '',
      content: section.content,
    }))
    meta.value = `${detail.page.spaceName} · ${detail.page.visibility} · ${formatDateTime(detail.page.updatedAt)}`
  } catch (error) {
    ElMessage.error(friendlyError(error))
  } finally {
    loading.value = false
  }
}

function addSection() {
  sections.value.push({ heading: '', content: '' })
}

function removeSection(index: number) {
  sections.value.splice(index, 1)
}

async function savePage() {
  if (!selectedSpaceId.value && !selectedPageId.value) {
    ElMessage.warning('先选择一个知识空间')
    return
  }
  const validSections = sections.value
    .map((section) => ({ heading: section.heading.trim(), content: section.content.trim() }))
    .filter((section) => section.heading && section.content)
  if (!title.value.trim() || !validSections.length) {
    ElMessage.warning('标题和至少一个小节不能为空')
    return
  }
  saving.value = true
  try {
    let saved: WikiPageDetail
    if (selectedPageId.value) {
      saved = await api<WikiPageDetail>(`/api/wiki/pages/${selectedPageId.value}`, {
        method: 'PUT',
        body: JSON.stringify({ title: title.value.trim(), visibility: visibility.value, sections: validSections }),
      })
      meta.value = `已保存 · ${saved.page.visibility} · ${formatDateTime(saved.page.updatedAt)}`
    } else {
      saved = await api<WikiPageDetail>('/api/wiki/pages', {
        method: 'POST',
        body: JSON.stringify({
          spaceId: selectedSpaceId.value,
          title: title.value.trim(),
          visibility: visibility.value,
          sections: validSections,
        }),
      })
      selectedPageId.value = saved.page.id
      meta.value = `已创建 · ${saved.page.visibility}`
    }
    pageStatus.value = saved.page.status
    await loadPages()
    ElMessage.success(saved.page.status === 'DRAFT' ? '页面已保存为草稿，提交审核后才会进入检索' : '页面已保存')
  } catch (error) {
    ElMessage.error(friendlyError(error))
  } finally {
    saving.value = false
  }
}

async function deletePage() {
  if (!selectedPageId.value) return
  try {
    await ElMessageBox.confirm('确认删除这个页面？', '删除页面', { type: 'warning' })
    await api(`/api/wiki/pages/${selectedPageId.value}`, { method: 'DELETE' })
    selectedPageId.value = null
    title.value = ''
    sections.value = [{ heading: '', content: '' }]
    meta.value = '页面已删除'
    await loadPages()
    ElMessage.success('页面已删除')
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(friendlyError(error))
  }
}

async function reindexPage() {
  if (!selectedPageId.value) return
  try {
    const result = await api<{ sections: number }>(`/api/wiki/pages/${selectedPageId.value}/reindex`, { method: 'POST' })
    meta.value = `索引已重建 · ${result.sections} 个小节`
    ElMessage.success('索引已重建')
  } catch (error) {
    ElMessage.error(friendlyError(error))
  }
}

async function changeStatus(action: 'submit' | 'approve' | 'reject') {
  if (!selectedPageId.value) return
  try {
    const detail = await api<WikiPageDetail>(`/api/wiki/pages/${selectedPageId.value}/${action}`, {
      method: 'POST',
    })
    pageStatus.value = detail.page.status
    meta.value = `${detail.page.spaceName} · ${detail.page.visibility} · ${formatDateTime(detail.page.updatedAt)}`
    await loadPages()
    ElMessage.success(action === 'submit' ? '已提交审核' : action === 'approve' ? '已通过审核' : '已驳回')
  } catch (error) {
    ElMessage.error(friendlyError(error))
  }
}

async function openVersions() {
  if (!selectedPageId.value) return
  versionDialogVisible.value = true
  versionLoading.value = true
  selectedVersion.value = null
  try {
    versions.value = await api<WikiPageVersion[]>(`/api/wiki/pages/${selectedPageId.value}/versions`)
  } catch (error) {
    ElMessage.error(friendlyError(error))
  } finally {
    versionLoading.value = false
  }
}

async function selectVersion(version: WikiPageVersion) {
  if (!selectedPageId.value) return
  versionLoading.value = true
  try {
    selectedVersion.value = await api<WikiPageVersionDetail>(
      `/api/wiki/pages/${selectedPageId.value}/versions/${version.version}`,
    )
  } catch (error) {
    ElMessage.error(friendlyError(error))
  } finally {
    versionLoading.value = false
  }
}

async function rollbackVersion(version: WikiPageVersion) {
  if (!selectedPageId.value) return
  try {
    await ElMessageBox.confirm(`确认回滚到 v${version.version}？当前内容会保存为新版本。`, '回滚版本', {
      type: 'warning',
    })
    const detail = await api<WikiPageDetail>(`/api/wiki/pages/${selectedPageId.value}/rollback/${version.version}`, {
      method: 'POST',
    })
    title.value = detail.page.title
    visibility.value = detail.page.visibility
    pageStatus.value = detail.page.status
    sections.value = detail.sections.map((section) => ({
      heading: (section.headingPath || '').split(' > ').pop() || '',
      content: section.content,
    }))
    meta.value = `已回滚到 v${version.version} · ${detail.page.visibility} · ${formatDateTime(detail.page.updatedAt)}`
    versionDialogVisible.value = false
    await loadPages()
    ElMessage.success('已回滚')
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(friendlyError(error))
  }
}

async function search() {
  if (!query.value.trim()) return
  searching.value = true
  try {
    searchResult.value = await api<WikiSearchResult>('/api/wiki/search', {
      method: 'POST',
      body: JSON.stringify({ query: query.value.trim(), topK: 5 }),
    })
  } catch (error) {
    ElMessage.error(friendlyError(error))
  } finally {
    searching.value = false
  }
}

onMounted(loadSpaces)
</script>

<template>
  <div class="wiki-layout">
    <aside class="wiki-sidebar">
      <el-card class="panel-card" shadow="never">
        <h3>知识空间</h3>
        <ul class="wiki-list">
          <li
            v-for="space in spaces"
            :key="space.id"
            class="wiki-item"
            data-testid="wiki-space"
            :class="{ active: space.id === selectedSpaceId }"
            @click="selectSpace(space)"
          >
            <div>{{ space.name }}</div>
            <div class="sub">{{ space.slug }}</div>
          </li>
        </ul>
      </el-card>

      <el-card class="panel-card" shadow="never">
        <div class="ticket-filter">
          <h3>页面</h3>
          <el-button text type="primary" :icon="Plus" @click="newPage">新建</el-button>
        </div>
        <ul class="wiki-list">
          <li
            v-for="page in pages"
            :key="page.id"
            class="wiki-item"
            data-testid="wiki-page"
            :class="{ active: page.id === selectedPageId }"
            @click="openPage(page)"
          >
            <div>{{ page.title }}</div>
            <div class="sub">
              {{ statusLabel(page.status) }} · {{ page.visibility }} · {{ formatDateTime(page.updatedAt) }}
            </div>
          </li>
        </ul>
      </el-card>
    </aside>

    <el-card v-loading="loading" class="panel-card" shadow="never">
      <div class="detail-header">
        <div>
          <h2>{{ selectedPageId ? '编辑页面' : '知识库' }}</h2>
          <div class="detail-meta">
            <el-tag size="small" :type="statusType(pageStatus)">{{ statusLabel(pageStatus) }}</el-tag>
            <span> · {{ meta }}</span>
          </div>
        </div>
        <div class="detail-actions">
          <el-button
            v-if="selectedPageId && pageStatus === 'DRAFT'"
            type="warning"
            @click="changeStatus('submit')"
          >提交审核</el-button>
          <el-button
            v-if="selectedPageId && pageStatus === 'REVIEW'"
            type="success"
            @click="changeStatus('approve')"
          >通过</el-button>
          <el-button
            v-if="selectedPageId && pageStatus === 'REVIEW'"
            type="danger"
            plain
            @click="changeStatus('reject')"
          >驳回</el-button>
          <el-button :icon="Clock" :disabled="!selectedPageId" @click="openVersions">版本历史</el-button>
          <el-button type="primary" :loading="saving" data-testid="wiki-save" @click="savePage">保存</el-button>
          <el-button :icon="Refresh" @click="reindexPage">重建索引</el-button>
          <el-button :icon="Delete" :disabled="!selectedPageId" @click="deletePage">删除</el-button>
        </div>
      </div>

      <div class="wiki-editor">
        <div class="form-grid">
          <el-form-item label="标题">
            <el-input v-model="title" placeholder="页面标题" maxlength="255" />
          </el-form-item>
          <el-form-item label="可见性">
            <el-select v-model="visibility" class="full-width">
              <el-option label="PUBLIC（客户可见）" value="PUBLIC" />
              <el-option label="INTERNAL（仅内部）" value="INTERNAL" />
            </el-select>
          </el-form-item>
        </div>

        <div class="section-card" v-for="(section, index) in sections" :key="index">
          <div class="section-head">
            <el-input v-model="section.heading" placeholder="小节标题，例如：适用条件" maxlength="255" />
            <el-button text type="danger" @click="removeSection(index)">删除</el-button>
          </div>
          <el-input v-model="section.content" type="textarea" :rows="4" placeholder="小节正文" maxlength="8000" />
        </div>
        <el-button :icon="Plus" @click="addSection">添加小节</el-button>

        <div class="search-box">
          <div class="toolbar">
            <el-input v-model="query" placeholder="测试检索：输入一个客户问题" class="full-width" data-testid="wiki-query" @keyup.enter="search" />
            <el-button type="primary" :icon="Search" :loading="searching" data-testid="wiki-search" @click="search">检索</el-button>
          </div>
          <div v-if="searchResult" class="muted small">
            traceId={{ searchResult.traceId }} · vector={{ searchResult.stats.vectorCount }} ·
            fts={{ searchResult.stats.ftsCount }} · {{ searchResult.stats.totalMs }}ms
          </div>
          <div class="search-hit" v-for="hit in searchResult?.hits || []" :key="hit.sectionId">
            <div class="hit-title">{{ hit.pageTitle }} · {{ hit.score }}</div>
            <div class="hit-path">{{ hit.headingPath }}</div>
            <div class="hit-body">{{ hit.content }}</div>
          </div>
          <el-empty v-if="searchResult && !searchResult.hits.length" description="没有命中结果" :image-size="60" />
        </div>
      </div>
    </el-card>

    <el-dialog v-model="versionDialogVisible" title="版本历史" width="860px">
      <div v-loading="versionLoading" class="version-layout">
        <ul class="wiki-list version-list">
          <li
            v-for="version in versions"
            :key="version.id"
            class="wiki-item"
            :class="{ active: selectedVersion?.version.version === version.version }"
            @click="selectVersion(version)"
          >
            <div>v{{ version.version }} · {{ version.title }}</div>
            <div class="sub">{{ statusLabel(version.status) }} · {{ formatDateTime(version.createdAt) }}</div>
          </li>
          <el-empty v-if="!versions.length && !versionLoading" description="暂无版本" :image-size="50" />
        </ul>
        <div v-if="selectedVersion" class="version-detail">
          <div class="detail-meta">
            v{{ selectedVersion.version.version }} · {{ statusLabel(selectedVersion.version.status) }} ·
            {{ formatDateTime(selectedVersion.version.createdAt) }}
          </div>
          <div v-for="(section, index) in selectedVersion.sections" :key="index" class="section-card">
            <strong>{{ section.heading }}</strong>
            <div class="muted small pre-wrap">{{ section.content }}</div>
          </div>
          <el-button type="warning" @click="rollbackVersion(selectedVersion.version)">回滚到此版本</el-button>
        </div>
        <el-empty v-else description="选择左侧版本查看内容" :image-size="60" />
      </div>
    </el-dialog>
  </div>
</template>
