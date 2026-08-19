<script setup>
import { computed, inject, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getAgentState, getPendingAction, resolvePendingAction } from '../services/agentApi.js'
import {
  deleteArtifact, downloadArtifact, getArtifacts, getConsoleCatalog, getMemory,
  getSession, getTraces, getUsage
} from '../services/consoleApi.js'

const route = useRoute()
const router = useRouter()
const consoleState = inject('agentConsole')
const section = computed(() => route.meta.section)
const sessions = consoleState.sessions
const selectedSessionId = ref(consoleState.currentSessionId.value || sessions.value[0]?.id || '')
const sessionMenuOpen = ref(false)
const catalog = ref(null)
const data = ref(null)
const loading = ref(false)
const error = ref('')
const query = ref('')
const detail = ref(null)
const deleteTarget = ref(null)

const pages = {
  agents: { index: '01', title: 'Agents', kicker: 'ORCHESTRATION', description: '查看当前 Agent 拓扑、职责与运行态。' },
  runs: { index: '02', title: 'Runs', kicker: 'EXECUTION LEDGER', description: '跨会话检查运行状态、迭代次数与模型消耗。' },
  sessions: { index: '03', title: 'Sessions', kicker: 'STATEFUL CONTEXT', description: '浏览会话快照、状态键与最后活动时间。' },
  tools: { index: '04', title: 'Tools', kicker: 'CAPABILITY REGISTRY', description: '审计运行时已注册工具、风险等级与参数。' },
  mcp: { index: '05', title: 'MCP', kicker: 'EXTERNAL PROTOCOL', description: '观察 MCP Server 配置和传输状态。' },
  skills: { index: '06', title: 'Skills', kicker: 'INSTRUCTION LIBRARY', description: '查看可按需注入 Agent 上下文的技能目录。' },
  memory: { index: '07', title: 'Memory', kicker: 'COGNITIVE LAYERS', description: '沿 L0–L3 检查最近对话、原子记忆、场景与画像。', session: true },
  plans: { index: '08', title: 'Plans', kicker: 'DECISION GRAPH', description: '复盘计划创建、重规划和当前执行进度。', session: true },
  traces: { index: '09', title: 'Traces', kicker: 'TIME / CAUSALITY', description: '以 Span 时间线定位一次调用链的耗时与故障。', session: true },
  artifacts: { index: '10', title: 'Artifacts', kicker: 'OUTPUT VAULT', description: '下载或治理 Agent 在运行中登记的文件产物。', session: true },
  approvals: { index: '11', title: 'Approvals', kicker: 'HUMAN GATE', description: '集中处理被风险策略挂起的外部动作。' },
  models: { index: '12', title: 'Models', kicker: 'INFERENCE ROUTING', description: '查看模型路由、Provider 与当前会话 Token 用量。', session: true }
}
const page = computed(() => pages[section.value])

const sessionLabel = computed(() => sessions.value.find(item => item.id === selectedSessionId.value)?.title || selectedSessionId.value || '暂无会话')
const searchPlaceholder = computed(() => `搜索 ${page.value?.title || ''}…`)
const normalizedQuery = computed(() => query.value.trim().toLowerCase())

const catalogItems = computed(() => {
  const key = section.value === 'mcp' ? 'mcpServers' : section.value
  const items = catalog.value?.[key] || []
  if (!normalizedQuery.value) return items
  return items.filter(item => JSON.stringify(item).toLowerCase().includes(normalizedQuery.value))
})

const runRows = computed(() => sessions.value.map(session => ({
  id: session.activeRunId || session.state?.invocationId || `session:${session.id}`,
  sessionId: session.id,
  title: session.title,
  status: session.state?.status || (session.activeRunId ? 'RUNNING' : 'IDLE'),
  iteration: session.state?.iteration || 0,
  updatedAt: session.state?.updatedAt || session.updatedAt,
  usage: data.value?.usage?.[session.id]
})).filter(row => !normalizedQuery.value || JSON.stringify(row).toLowerCase().includes(normalizedQuery.value)))

const sessionRows = computed(() => sessions.value.map(local => ({
  ...local,
  ...(data.value?.sessions?.[local.id] || {})
})).filter(row => !normalizedQuery.value || JSON.stringify(row).toLowerCase().includes(normalizedQuery.value)))

const plans = computed(() => {
  const session = sessions.value.find(item => item.id === selectedSessionId.value)
  const events = (session?.messages || []).filter(message => message.role === 'event' && /^(PLAN|REPLAN|DECISION)/.test(message.content || ''))
  return events.map((event, index) => ({
    id: event.id, title: event.content.split('\n')[0], content: event.content,
    createdAt: event.createdAt, order: index + 1
  })).reverse()
})

const summary = computed(() => {
  const values = {
    agents: [catalogItems.value.length, 'registered', activeCount.value, 'active'],
    runs: [runRows.value.length, 'observed', runRows.value.filter(r => r.status === 'RUNNING').length, 'running'],
    sessions: [sessionRows.value.length, 'local sessions', sessionRows.value.reduce((n, s) => n + (s.stateKeys?.length || 0), 0), 'state keys'],
    tools: [catalogItems.value.length, 'registered', catalogItems.value.filter(t => ['MEDIUM', 'HIGH'].includes(t.riskLevel)).length, 'gated'],
    mcp: [catalogItems.value.length, 'servers', catalogItems.value.filter(s => s.status === 'CONFIGURED').length, 'configured'],
    skills: [catalogItems.value.length, 'available', new Set(catalogItems.value.map(s => s.source?.split(':')[0])).size, 'sources'],
    memory: [memoryCount.value, 'memories', data.value?.memory?.counts?.l0 || 0, 'recent turns'],
    plans: [plans.value.length, 'events', sessions.value.find(s => s.id === selectedSessionId.value)?.state?.iteration || 0, 'iteration'],
    traces: [data.value?.traces?.length || 0, 'traces', (data.value?.traces || []).reduce((n, t) => n + (t.spanCount || 0), 0), 'spans'],
    artifacts: [data.value?.artifacts?.length || 0, 'files', formatBytes((data.value?.artifacts || []).reduce((n, a) => n + (a.sizeBytes || 0), 0)), 'stored'],
    approvals: [data.value?.approvals?.length || 0, 'waiting', sessions.value.length, 'sessions scanned'],
    models: [catalogItems.value.length, 'routes', formatNumber(data.value?.usage?.totalTokens || 0), 'tokens']
  }[section.value] || [0, 'items', 0, 'active']
  return values
})
const activeCount = computed(() => sessions.value.filter(s => ['RUNNING', 'WAITING'].includes(s.state?.status)).length)
const memoryCount = computed(() => Object.values(data.value?.memory?.counts || {}).reduce((a, b) => a + b, 0))
const memoryLayers = computed(() => {
  const memory = data.value?.memory || {}
  return [
    { id: 'L0', name: 'Recent turns', hint: '会话工作记忆', items: memory.recentTurns || [] },
    { id: 'L1', name: 'Atomic memory', hint: '稳定事实与偏好', items: memory.atomicMemories || [] },
    { id: 'L2', name: 'Scenarios', hint: '可复用任务经验', items: memory.scenarios || [] },
    { id: 'L3', name: 'Profile', hint: '长期核心画像', items: memory.profile ? [memory.profile] : [] }
  ]
})

function formatDate(value) {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.valueOf()) ? value : new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit' }).format(date)
}
function formatBytes(bytes = 0) {
  if (!bytes) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB']
  const index = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1)
  return `${(bytes / (1024 ** index)).toFixed(index ? 1 : 0)} ${units[index]}`
}
function formatNumber(value = 0) { return new Intl.NumberFormat('zh-CN').format(value) }
function statusTone(status = '') {
  const value = String(status).toUpperCase()
  if (['COMPLETED', 'READY', 'CONFIGURED', 'OK'].includes(value)) return 'success'
  if (['RUNNING', 'WAITING'].includes(value)) return 'warning'
  if (['FAILED', 'CANCELLED', 'DISABLED'].includes(value)) return 'danger'
  return 'neutral'
}
function concise(item) {
  if (typeof item === 'string') return item
  const text = item?.content || item?.summary || item?.input || item?.description || item?.value
  return text ? String(text) : JSON.stringify(item)
}

async function ensureCatalog() {
  if (catalog.value) return
  catalog.value = await getConsoleCatalog()
}

async function load() {
  loading.value = true
  error.value = ''
  detail.value = null
  try {
    if (['agents', 'tools', 'mcp', 'skills', 'models'].includes(section.value)) await ensureCatalog()
    if (section.value === 'runs') {
      const usage = {}
      await Promise.all(sessions.value.map(async session => {
        try { usage[session.id] = await getUsage(session.id) } catch { usage[session.id] = null }
      }))
      data.value = { usage }
    } else if (section.value === 'sessions') {
      const snapshots = {}
      await Promise.all(sessions.value.map(async session => {
        try { snapshots[session.id] = await getSession(session.id) } catch { snapshots[session.id] = null }
      }))
      data.value = { sessions: snapshots }
    } else if (section.value === 'memory') {
      data.value = { memory: selectedSessionId.value ? await getMemory(selectedSessionId.value) : null }
    } else if (section.value === 'traces') {
      data.value = { traces: selectedSessionId.value ? await getTraces(selectedSessionId.value) : [] }
    } else if (section.value === 'artifacts') {
      data.value = { artifacts: selectedSessionId.value ? await getArtifacts(selectedSessionId.value) : [] }
    } else if (section.value === 'approvals') {
      const approvals = (await Promise.all(sessions.value.map(async session => {
        try { return await getPendingAction(session.id) } catch { return null }
      }))).filter(Boolean)
      data.value = { approvals }
    } else if (section.value === 'models') {
      data.value = { usage: selectedSessionId.value ? await getUsage(selectedSessionId.value) : null }
    } else data.value = {}
  } catch (reason) {
    error.value = reason?.message || '无法读取 Console 数据'
  } finally {
    loading.value = false
  }
}

function chooseSession(id) {
  selectedSessionId.value = id
  sessionMenuOpen.value = false
  if (page.value.session) load()
}
function openSession(id) {
  consoleState.selectSession(id)
  router.push('/chat')
}
async function confirmDelete() {
  if (!deleteTarget.value) return
  try {
    await deleteArtifact(deleteTarget.value.artifactId)
    deleteTarget.value = null
    await load()
  } catch (reason) { error.value = reason?.message || '删除产物失败' }
}
async function decide(approval, approved) {
  loading.value = true
  try {
    await resolvePendingAction(approval.invocationId, approval.pendingAction.pendingActionId, approved)
    await load()
  } catch (reason) { error.value = reason?.message || '审批处理失败'; loading.value = false }
}

watch(section, () => { query.value = ''; load() })
watch(sessions, items => {
  if (!selectedSessionId.value && items.length) {
    selectedSessionId.value = consoleState.currentSessionId.value || items[0].id
    if (page.value.session) load()
  }
})
onMounted(load)
</script>

<template>
  <section class="management-view" :aria-labelledby="`${section}-title`">
    <header class="management-header">
      <div class="page-index">{{ page.index }}</div>
      <div class="page-copy">
        <div class="page-kicker"><span></span>{{ page.kicker }}</div>
        <h1 :id="`${section}-title`">{{ page.title }}</h1>
        <p>{{ page.description }}</p>
      </div>
      <div class="page-actions">
        <div v-if="page.session" class="session-picker" @keydown.esc="sessionMenuOpen = false">
          <button type="button" aria-haspopup="listbox" :aria-expanded="sessionMenuOpen" @click="sessionMenuOpen = !sessionMenuOpen">
            <span><small>SESSION SCOPE</small><strong>{{ sessionLabel }}</strong></span>
            <svg viewBox="0 0 24 24"><path d="m8 10 4 4 4-4" /></svg>
          </button>
          <div v-if="sessionMenuOpen" class="session-options" role="listbox">
            <button v-for="item in sessions" :key="item.id" type="button" role="option" :aria-selected="item.id === selectedSessionId" @click="chooseSession(item.id)">
              <span>{{ item.title }}</span><small>{{ item.id }}</small>
            </button>
            <p v-if="!sessions.length">先在 Chat 中创建一个会话</p>
          </div>
        </div>
        <button class="refresh-button" type="button" :disabled="loading" aria-label="刷新数据" @click="load">
          <svg viewBox="0 0 24 24"><path d="M20 7v5h-5M4 17v-5h5M18 9a7 7 0 0 0-12-2l-2 5m2 3a7 7 0 0 0 12 2l2-5" /></svg>
        </button>
      </div>
    </header>

    <div class="summary-strip">
      <div><small>TOTAL</small><strong>{{ summary[0] }}</strong><span>{{ summary[1] }}</span></div>
      <div><small>SIGNAL</small><strong>{{ summary[2] }}</strong><span>{{ summary[3] }}</span></div>
      <div><small>RUNTIME</small><strong class="online-text">ONLINE</strong><span>local node</span></div>
      <div><small>UPDATED</small><strong>{{ new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }) }}</strong><span>live snapshot</span></div>
    </div>

    <div class="content-toolbar">
      <label class="console-search">
        <svg viewBox="0 0 24 24"><circle cx="11" cy="11" r="6"/><path d="m16 16 4 4"/></svg>
        <input v-model="query" type="search" :placeholder="searchPlaceholder" />
      </label>
      <span class="scope-chip"><i></i> LIVE DATA</span>
    </div>

    <div v-if="error" class="state-banner error-banner">
      <div><strong>DATA LINK INTERRUPTED</strong><p>{{ error }}</p></div>
      <button type="button" @click="load">重试</button>
    </div>
    <div v-else-if="loading" class="loading-grid" aria-label="正在加载">
      <i v-for="n in 6" :key="n"></i>
    </div>

    <div v-else class="management-content">
      <div v-if="['agents', 'tools', 'mcp', 'skills'].includes(section)" class="registry-grid">
        <button v-for="(item, index) in catalogItems" :key="item.id || item.name" class="registry-card" type="button" @click="detail = item">
          <div class="registry-card-top">
            <span class="registry-glyph">{{ String(item.name || item.id).slice(0, 2).toUpperCase() }}</span>
            <span class="status-pill" :class="statusTone(item.status || item.riskLevel || 'READY')">{{ item.status || item.riskLevel || 'READY' }}</span>
          </div>
          <small>{{ section.toUpperCase() }} / {{ String(index + 1).padStart(2, '0') }}</small>
          <h2>{{ item.name || item.id }}</h2>
          <p>{{ item.description || `${item.transport || 'stdio'} transport endpoint` }}</p>
          <div class="card-meta"><span>{{ item.parameterCount ?? item.source ?? item.transport ?? 'runtime' }}</span><b>↗</b></div>
        </button>
      </div>

      <div v-else-if="section === 'runs'" class="data-table" role="table" aria-label="Agent Runs">
        <div class="table-row table-head" role="row"><span>RUN / SESSION</span><span>STATUS</span><span>ITERATION</span><span>TOKENS</span><span>UPDATED</span></div>
        <button v-for="row in runRows" :key="row.id" class="table-row" type="button" role="row" @click="detail = row">
          <span><strong>{{ row.title }}</strong><small>{{ row.id }}</small></span><span><i class="status-dot" :class="statusTone(row.status)"></i>{{ row.status }}</span><span>{{ String(row.iteration).padStart(2, '0') }}</span><span>{{ formatNumber(row.usage?.totalTokens) }}</span><span>{{ formatDate(row.updatedAt) }}</span>
        </button>
      </div>

      <div v-else-if="section === 'sessions'" class="data-table sessions-table" role="table" aria-label="Sessions">
        <div class="table-row table-head" role="row"><span>SESSION</span><span>USER</span><span>STATE</span><span>LAST ACTIVE</span><span></span></div>
        <div v-for="row in sessionRows" :key="row.id" class="table-row" role="row">
          <span><strong>{{ row.title }}</strong><small>{{ row.sessionId || row.id }}</small></span><span>{{ row.userId || 'default-user' }}</span><span>{{ row.stateKeys?.length || 0 }} keys</span><span>{{ formatDate(row.lastActiveAt || row.updatedAt) }}</span><span><button type="button" @click="openSession(row.id)">OPEN ↗</button></span>
        </div>
      </div>

      <div v-else-if="section === 'memory'" class="memory-stack">
        <article v-for="layer in memoryLayers" :key="layer.id" class="memory-layer">
          <header><b>{{ layer.id }}</b><div><h2>{{ layer.name }}</h2><p>{{ layer.hint }}</p></div><span>{{ layer.items.length }}</span></header>
          <div class="memory-items"><button v-for="(item, index) in layer.items" :key="index" type="button" @click="detail = item"><small>{{ layer.id }}.{{ String(index + 1).padStart(2, '0') }}</small><p>{{ concise(item) }}</p></button><p v-if="!layer.items.length" class="inline-empty">该层暂未形成记忆</p></div>
        </article>
      </div>

      <div v-else-if="section === 'plans'" class="plan-timeline">
        <article v-for="plan in plans" :key="plan.id"><div class="timeline-node">{{ String(plan.order).padStart(2, '0') }}</div><div><small>{{ formatDate(plan.createdAt) }}</small><h2>{{ plan.title }}</h2><p>{{ plan.content }}</p></div></article>
      </div>

      <div v-else-if="section === 'traces'" class="trace-list">
        <article v-for="trace in data.traces" :key="trace.traceId">
          <header><div><small>TRACE</small><h2>{{ trace.traceId }}</h2></div><span>{{ trace.durationMillis }} ms / {{ trace.spanCount }} spans</span></header>
          <button v-for="span in trace.spans" :key="span.spanId" type="button" class="span-row" @click="detail = span"><i :style="{ width: `${Math.max(4, Math.min(100, span.durationMillis / Math.max(1, trace.durationMillis) * 100))}%` }"></i><span><strong>{{ span.name }}</strong><small>{{ span.kind }} · {{ span.status }}</small></span><b>{{ span.durationMillis }} ms</b></button>
        </article>
      </div>

      <div v-else-if="section === 'artifacts'" class="artifact-grid">
        <article v-for="artifact in data.artifacts" :key="artifact.artifactId">
          <div class="file-icon">{{ artifact.filename?.split('.').pop()?.slice(0, 4).toUpperCase() || 'FILE' }}</div><div><small>{{ artifact.contentType }}</small><h2>{{ artifact.filename }}</h2><p>{{ formatBytes(artifact.sizeBytes) }} · {{ formatDate(artifact.createdAt) }}</p></div>
          <footer><button type="button" @click="downloadArtifact(artifact.artifactId)">DOWNLOAD</button><button class="danger-text" type="button" @click="deleteTarget = artifact">DELETE</button></footer>
        </article>
      </div>

      <div v-else-if="section === 'approvals'" class="approval-list">
        <article v-for="approval in data.approvals" :key="approval.pendingAction?.pendingActionId"><span class="approval-mark">!</span><div><small>RISK GATE / {{ approval.sessionId }}</small><h2>{{ approval.pendingAction?.title || approval.pendingAction?.description || '外部动作等待确认' }}</h2><p>{{ approval.pendingAction?.description || '该动作需要人工确认后才能继续运行。' }}</p></div><footer><button type="button" @click="decide(approval, false)">REJECT</button><button class="approve" type="button" @click="decide(approval, true)">APPROVE</button></footer></article>
      </div>

      <div v-else-if="section === 'models'" class="models-layout">
        <article v-for="model in catalogItems" :key="model.role" class="model-card"><small>{{ model.workload }} ROUTE</small><h2>{{ model.model }}</h2><p>{{ model.provider }}</p><div class="model-wave"><i v-for="n in 18" :key="n" :style="{ height: `${18 + ((n * 13) % 31)}%` }"></i></div><footer><span class="status-pill success">CONNECTED</span><b>{{ model.role }}</b></footer></article>
        <aside class="usage-card"><small>SESSION USAGE</small><strong>{{ formatNumber(data.usage?.totalTokens) }}</strong><span>TOTAL TOKENS</span><dl><div><dt>Prompt</dt><dd>{{ formatNumber(data.usage?.promptTokens) }}</dd></div><div><dt>Completion</dt><dd>{{ formatNumber(data.usage?.completionTokens) }}</dd></div><div><dt>Calls</dt><dd>{{ formatNumber(data.usage?.modelCalls) }}</dd></div></dl></aside>
      </div>

      <div v-if="((['agents','tools','mcp','skills'].includes(section) && !catalogItems.length) || (section === 'plans' && !plans.length) || (section === 'traces' && !data.traces?.length) || (section === 'artifacts' && !data.artifacts?.length) || (section === 'approvals' && !data.approvals?.length))" class="empty-state">
        <span>∅</span><h2>NO RECORDS IN SCOPE</h2><p>当前范围没有可展示的数据。运行一个 Agent 任务后再刷新此面板。</p>
      </div>
    </div>

    <transition name="inspector">
      <aside v-if="detail" class="detail-inspector" aria-label="详情检查器">
        <header><div><small>OBJECT INSPECTOR</small><strong>{{ detail.name || detail.title || detail.id || detail.spanId || 'DETAIL' }}</strong></div><button type="button" aria-label="关闭详情" @click="detail = null">×</button></header>
        <pre>{{ JSON.stringify(detail, null, 2) }}</pre>
      </aside>
    </transition>

    <transition name="dialog-fade">
      <div v-if="deleteTarget" class="dialog-backdrop" @click.self="deleteTarget = null">
        <section class="confirm-dialog" role="alertdialog" aria-modal="true" aria-labelledby="deleteArtifactTitle">
          <div class="confirm-dialog-icon">!</div><div class="confirm-dialog-copy"><h2 id="deleteArtifactTitle">删除产物？</h2><p>将永久删除 {{ deleteTarget.filename }}，下载链接会立即失效。此操作不可撤销。</p></div>
          <div class="confirm-dialog-actions"><button class="dialog-cancel" type="button" @click="deleteTarget = null">取消</button><button class="dialog-confirm" type="button" @click="confirmDelete">确认删除</button></div>
        </section>
      </div>
    </transition>
  </section>
</template>
