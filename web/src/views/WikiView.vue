<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, Plus, Refresh, Search } from '@element-plus/icons-vue'
import { api, friendlyError } from '@/api'
import type { WikiPage, WikiPageDetail, WikiSearchResult, WikiSpace } from '@/types'
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
const loading = ref(false)
const saving = ref(false)

const query = ref('')
const searching = ref(false)
const searchResult = ref<WikiSearchResult | null>(null)

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
    if (selectedPageId.value) {
      const detail = await api<WikiPageDetail>(`/api/wiki/pages/${selectedPageId.value}`, {
        method: 'PUT',
        body: JSON.stringify({ title: title.value.trim(), visibility: visibility.value, sections: validSections }),
      })
      meta.value = `已保存 · ${detail.page.visibility} · ${formatDateTime(detail.page.updatedAt)}`
    } else {
      const detail = await api<WikiPageDetail>('/api/wiki/pages', {
        method: 'POST',
        body: JSON.stringify({
          spaceId: selectedSpaceId.value,
          title: title.value.trim(),
          visibility: visibility.value,
          sections: validSections,
        }),
      })
      selectedPageId.value = detail.page.id
      meta.value = `已创建 · ${detail.page.visibility}`
    }
    await loadPages()
    ElMessage.success('页面已保存')
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
            <div class="sub">{{ page.visibility }} · {{ formatDateTime(page.updatedAt) }}</div>
          </li>
        </ul>
      </el-card>
    </aside>

    <el-card v-loading="loading" class="panel-card" shadow="never">
      <div class="detail-header">
        <div>
          <h2>{{ selectedPageId ? '编辑页面' : '知识库' }}</h2>
          <div class="detail-meta">{{ meta }}</div>
        </div>
        <div class="detail-actions">
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
  </div>
</template>
