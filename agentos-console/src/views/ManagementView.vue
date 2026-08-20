<script setup>
import { computed, inject, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getPendingAction, resolvePendingAction } from '../services/agentApi.js'
import {
  deleteArtifact, downloadArtifact, evaluateInvocation, getAgentDetail, getAgentRuns,
  getArtifacts, getConsoleCatalog, getMemory, getSessionEvents, getSessions, getTraces, getUsage
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
const knownSessions = ref([])
const evalTarget = ref('')
const evalBusy = ref(false)
const evalResult = ref(null)
const evalForm = ref({
  caseId: 'console-case',
  expectedToolSequence: '',
  forbiddenTools: '',
  maxToolCalls: '',
  requiredResponseKeywords: '',
  requireCompleted: true
})

const pages = {
  agents: { index: '01', title: 'Agents', kicker: 'ORCHESTRATION', description: '查看运行时注册的 Agent 拓扑、形态与工具化状态。' },
  runs: { index: '02', title: 'Runs', kicker: 'EXECUTION LEDGER', description: '检查服务端后台运行的状态、迭代次数与事件游标。' },
  sessions: { index: '03', title: 'Sessions', kicker: 'STATEFUL CONTEXT', description: '浏览服务端会话快照、状态键与最后活动时间。' },
  tools: { index: '04', title: 'Tools', kicker: 'CAPABILITY REGISTRY', description: '审计运行时已注册工具、风险等级与参数。' },
  mcp: { index: '05', title: 'MCP', kicker: 'EXTERNAL PROTOCOL', description: '观察 MCP Server 配置和传输状态。' },
  skills: { index: '06', title: 'Skills', kicker: 'INSTRUCTION LIBRARY', description: '查看可按需注入 Agent 上下文的技能目录。' },
  memory: { index: '07', title: 'Memory', kicker: 'COGNITIVE LAYERS', description: '沿 L0–L3 检查最近对话、原子记忆、场景与画像。', session: true },
  plans: { index: '08', title: 'Plans', kicker: 'DECISION GRAPH', description: '按领域事件复盘计划创建、步骤执行与重规划轨迹。', session: true },
  traces: { index: '09', title: 'Traces', kicker: 'TIME / CAUSALITY', description: '以 Span 时间线定位一次调用链的耗时与故障。', session: true },
  artifacts: { index: '10', title: 'Artifacts', kicker: 'OUTPUT VAULT', description: '下载或治理 Agent 在运行中登记的文件产物。', session: true },
  approvals: { index: '11', title: 'Approvals', kicker: 'HUMAN GATE', description: '集中处理被风险策略挂起的外部动作。' },
  models: { index: '12', title: 'Models', kicker: 'INFERENCE ROUTING', description: '查看模型路由、Provider 与当前会话 Token 用量。', session: true },
  evals: { index: '13', title: 'Evals', kicker: 'TRAJECTORY CHECK', description: '对单次执行回放工具轨迹，校验路径而不只校验答案。', session: true }
}
const page = computed(() => pages[section.value])

const sessionLabel = computed(() =>
  sessionOptions.value.find(item => item.id === selectedSessionId.value)?.title
  || selectedSessionId.value || '暂无会话')
const searchPlaceholder = computed(() => `搜索 ${page.value?.title || ''}…`)
const normalizedQuery = computed(() => query.value.trim().toLowerCase())

/**
 * 会话作用域选项：本地档案优先（有用户命名的标题），再补上仅服务端知道的会话。
 *
 * <p>Plans、Traces 等面板的数据来自服务端，因此可选范围不能限制在浏览器档案内。</p>
 */
const sessionOptions = computed(() => {
  const options = sessions.value.map(item => ({ id: item.id, title: item.title, local: true }))
  const known = new Set(options.map(item => item.id))
  for (const remote of knownSessions.value) {
    if (known.has(remote.sessionId)) continue
    options.push({
      id: remote.sessionId,
      title: remote.state?.lastObjective || remote.sessionId,
      local: false
    })
  }
  return options
})

const catalogItems = computed(() => {
  const key = section.value === 'mcp' ? 'mcpServers' : section.value
  const items = catalog.value?.[key] || []
  if (!normalizedQuery.value) return items
  return items.filter(item => JSON.stringify(item).toLowerCase().includes(normalizedQuery.value))
})

const runRows = computed(() => (data.value?.runs || []).map(run => ({
  id: run.runId,
  sessionId: run.sessionId,
  invocationId: run.invocationId,
  title: sessions.value.find(item => item.id === run.sessionId)?.title || run.sessionId,
  status: run.state?.status || 'UNKNOWN',
  iteration: run.state?.iteration || 0,
  updatedAt: run.updatedAt,
  lastSequence: run.lastSequence,
  pendingAction: run.pendingAction,
  usage: data.value?.usage?.[run.sessionId]
})).filter(row => !normalizedQuery.value || JSON.stringify(row).toLowerCase().includes(normalizedQuery.value)))

const sessionRows = computed(() => (data.value?.sessions || []).map(remote => {
  const local = sessions.value.find(item => item.id === remote.sessionId)
  return {
    ...remote,
    id: remote.sessionId,
    title: local?.title || remote.state?.lastObjective || remote.sessionId,
    known: Boolean(local)
  }
}).filter(row => !normalizedQuery.value || JSON.stringify(row).toLowerCase().includes(normalizedQuery.value)))

/** 计划视图直接来自后端领域事件，不再解析前端展示文本。 */
const planTraces = computed(() => {
  const traces = data.value?.events || []
  if (!normalizedQuery.value) return traces
  return traces.filter(trace => JSON.stringify(trace).toLowerCase().includes(normalizedQuery.value))
})

const invocationOptions = computed(() => (data.value?.events || []).map(trace => ({
  invocationId: trace.invocationId,
  label: `${trace.invocationId.slice(0, 8)} · ${trace.eventCount} events`,
  terminalType: trace.terminalType
})))

const summary = computed(() => {
  const values = {
    agents: [catalogItems.value.length, 'registered', catalogItems.value.filter(a => a.exposedAsTool).length, 'as tools'],
    runs: [runRows.value.length, 'observed', runRows.value.filter(r => ['RUNNING', 'WAITING'].includes(r.status)).length, 'active'],
    sessions: [sessionRows.value.length, 'server sessions', sessionRows.value.reduce((n, s) => n + (s.stateKeys?.length || 0), 0), 'state keys'],
    tools: [catalogItems.value.length, 'registered', catalogItems.value.filter(t => ['MEDIUM', 'HIGH'].includes(t.riskLevel)).length, 'gated'],
    mcp: [catalogItems.value.length, 'servers', catalogItems.value.filter(s => s.status === 'CONFIGURED').length, 'configured'],
    skills: [catalogItems.value.length, 'available', new Set(catalogItems.value.map(s => s.source?.split(':')[0])).size, 'sources'],
    memory: [memoryCount.value, 'memories', data.value?.memory?.counts?.l0 || 0, 'recent turns'],
    plans: [planTraces.value.length, 'invocations', planTraces.value.reduce((n, t) => n + (t.eventCount || 0), 0), 'events'],
    traces: [data.value?.traces?.length || 0, 'traces', (data.value?.traces || []).reduce((n, t) => n + (t.spanCount || 0), 0), 'spans'],
    artifacts: [data.value?.artifacts?.length || 0, 'files', formatBytes((data.value?.artifacts || []).reduce((n, a) => n + (a.sizeBytes || 0), 0)), 'stored'],
    approvals: [data.value?.approvals?.length || 0, 'waiting', data.value?.scanned || 0, 'sessions scanned'],
    models: [catalogItems.value.length, 'routes', formatNumber(data.value?.usage?.totalTokens || 0), 'tokens'],
    evals: [invocationOptions.value.length, 'invocations', evalResult.value ? evalResult.value.findings.filter(f => f.passed).length : 0, 'checks passed']
  }[section.value] || [0, 'items', 0, 'active']
  return values
})
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

/** 各面板的空态判定集中在一处，模板不再堆叠长条件。 */
const isEmpty = computed(() => ({
  agents: !catalogItems.value.length,
  tools: !catalogItems.value.length,
  mcp: !catalogItems.value.length,
  skills: !catalogItems.value.length,
  runs: !runRows.value.length,
  sessions: !sessionRows.value.length,
  plans: !planTraces.value.length,
  traces: !data.value?.traces?.length,
  artifacts: !data.value?.artifacts?.length,
  approvals: !data.value?.approvals?.length
}[section.value] || false))

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

/** 领域事件按语义分组着色，使轨迹一眼可读。 */
function eventTone(type = '') {
  if (type.endsWith('_FAILED')) return 'danger'
  if (type.endsWith('_COMPLETED')) return 'success'
  if (type === 'HUMAN_ACTION_REQUIRED') return 'warning'
  return 'neutral'
}

/** 把事件类型压缩为时间线上的短标签。 */
function eventLabel(type = '') {
  return type.replace(/_/g, ' ')
}

/** 事件 data 中的关键字段，避免时间线上直接铺开整个 JSON。 */
function eventFacts(event) {
  const data = event?.data || {}
  return ['toolName', 'stepId', 'planId', 'outcome', 'status', 'failureType', 'type', 'pendingActionId']
    .filter(key => data[key] !== undefined && data[key] !== null && data[key] !== '')
    .map(key => `${key}=${data[key]}`)
}

async function ensureCatalog() {
  if (catalog.value) return
  catalog.value = await getConsoleCatalog()
}

/**
 * 拉取服务端会话列表，供作用域选择器覆盖本地档案之外的会话。
 *
 * <p>失败时保留已有列表：作用域选择退化为仅本地档案，不阻断当前面板。</p>
 */
async function ensureKnownSessions() {
  try {
    knownSessions.value = await getSessions(100)
  } catch {
    // 会话列表只影响作用域选择范围。
  }
  if (!selectedSessionId.value) {
    selectedSessionId.value = sessionOptions.value[0]?.id || ''
  }
}

async function load() {
  loading.value = true
  error.value = ''
  detail.value = null
  try {
    if (['agents', 'tools', 'mcp', 'skills', 'models'].includes(section.value)) await ensureCatalog()
    if (page.value.session) await ensureKnownSessions()
    if (section.value === 'runs') {
      const runs = await getAgentRuns()
      const usage = {}
      await Promise.all([...new Set(runs.map(run => run.sessionId))].map(async id => {
        try { usage[id] = await getUsage(id) } catch { usage[id] = null }
      }))
      data.value = { runs, usage }
    } else if (section.value === 'sessions') {
      data.value = { sessions: await getSessions(100) }
    } else if (section.value === 'memory') {
      data.value = { memory: selectedSessionId.value ? await getMemory(selectedSessionId.value) : null }
    } else if (section.value === 'plans' || section.value === 'evals') {
      data.value = { events: selectedSessionId.value ? await getSessionEvents(selectedSessionId.value) : [] }
      if (section.value === 'evals') {
        evalResult.value = null
        evalTarget.value = data.value.events[0]?.invocationId || ''
      }
    } else if (section.value === 'traces') {
      data.value = { traces: selectedSessionId.value ? await getTraces(selectedSessionId.value) : [] }
    } else if (section.value === 'artifacts') {
      data.value = { artifacts: selectedSessionId.value ? await getArtifacts(selectedSessionId.value) : [] }
    } else if (section.value === 'approvals') {
      // 挂起动作按会话查询：扫描服务端已知会话，而非仅浏览器本地档案。
      await ensureKnownSessions()
      const scanned = [...new Set([
        ...knownSessions.value.map(item => item.sessionId),
        ...sessions.value.map(item => item.id)
      ])]
      const approvals = (await Promise.all(scanned.map(async id => {
        try { return await getPendingAction(id) } catch { return null }
      }))).filter(Boolean)
      data.value = { approvals, scanned: scanned.length }
    } else if (section.value === 'models') {
      data.value = { usage: selectedSessionId.value ? await getUsage(selectedSessionId.value) : null }
    } else data.value = {}
  } catch (reason) {
    error.value = reason?.message || '无法读取 Console 数据'
  } finally {
    loading.value = false
  }
}

/** 把逗号分隔输入转为字符串数组，空项被丢弃。 */
function splitList(value) {
  return String(value || '').split(',').map(item => item.trim()).filter(Boolean)
}

async function runEvaluation() {
  if (!evalTarget.value || evalBusy.value) return
  evalBusy.value = true
  error.value = ''
  try {
    const form = evalForm.value
    evalResult.value = await evaluateInvocation(evalTarget.value, {
      caseId: form.caseId || 'console-case',
      expectedToolSequence: splitList(form.expectedToolSequence),
      forbiddenTools: splitList(form.forbiddenTools),
      maxToolCalls: form.maxToolCalls === '' ? null : Number(form.maxToolCalls),
      requiredResponseKeywords: splitList(form.requiredResponseKeywords),
      requireCompleted: form.requireCompleted
    })
  } catch (reason) {
    error.value = reason?.message || '评估请求失败'
  } finally {
    evalBusy.value = false
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

/** Agent 卡片点击后拉取详情（子 Agent、声明工具），失败时退回摘要。 */
async function inspectAgent(agent) {
  detail.value = agent
  try {
    detail.value = await getAgentDetail(agent.id)
  } catch {
    // 详情接口不可用时保留目录摘要，不打断浏览。
  }
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
onMounted(load)</script>

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
            <button v-for="item in sessionOptions" :key="item.id" type="button" role="option" :aria-selected="item.id === selectedSessionId" @click="chooseSession(item.id)">
              <span>{{ item.title }}</span><small>{{ item.local ? item.id : `${item.id} · server` }}</small>
            </button>
            <p v-if="!sessionOptions.length">先在 Chat 中创建一个会话</p>
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
      <div v-if="section === 'agents'" class="registry-grid">
        <button v-for="(item, index) in catalogItems" :key="item.id" class="registry-card" type="button" @click="inspectAgent(item)">
          <div class="registry-card-top">
            <span class="registry-glyph">{{ String(item.id).slice(0, 2).toUpperCase() }}</span>
            <span class="status-pill" :class="statusTone(item.status)">{{ item.status }}</span>
          </div>
          <small>{{ (item.kind || 'agent').toUpperCase() }} / {{ String(index + 1).padStart(2, '0') }}</small>
          <h2>{{ item.name || item.id }}</h2>
          <p>{{ item.description }}</p>
          <div class="card-meta">
            <span>{{ item.subAgentCount ? `${item.subAgentCount} sub-agents` : item.id }}</span>
            <b>{{ item.exposedAsTool ? '⚒' : '↗' }}</b>
          </div>
        </button>
      </div>

      <div v-else-if="['tools', 'mcp', 'skills'].includes(section)" class="registry-grid">
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
          <span><strong>{{ row.title }}</strong><small>{{ row.sessionId }}</small></span><span>{{ row.userId || 'default-user' }}</span><span>{{ row.stateKeys?.length || 0 }} keys</span><span>{{ formatDate(row.lastActiveAt) }}</span><span><button type="button" :disabled="!row.known" :title="row.known ? '在 Chat 中打开' : '该会话不在本地档案中'" @click="openSession(row.id)">OPEN ↗</button></span>
        </div>
      </div>

      <div v-else-if="section === 'memory'" class="memory-stack">
        <article v-for="layer in memoryLayers" :key="layer.id" class="memory-layer">
          <header><b>{{ layer.id }}</b><div><h2>{{ layer.name }}</h2><p>{{ layer.hint }}</p></div><span>{{ layer.items.length }}</span></header>
          <div class="memory-items"><button v-for="(item, index) in layer.items" :key="index" type="button" @click="detail = item"><small>{{ layer.id }}.{{ String(index + 1).padStart(2, '0') }}</small><p>{{ concise(item) }}</p></button><p v-if="!layer.items.length" class="inline-empty">该层暂未形成记忆</p></div>
        </article>
      </div>

      <div v-else-if="section === 'plans'" class="event-trace-list">
        <article v-for="trace in planTraces" :key="trace.invocationId">
          <header>
            <div><small>INVOCATION / {{ trace.agentId }}</small><h2>{{ trace.invocationId }}</h2></div>
            <span :class="statusTone(trace.terminalType.includes('FAILED') ? 'FAILED' : trace.terminalType.includes('COMPLETED') ? 'COMPLETED' : '')">
              {{ trace.eventCount }} events · {{ formatDate(trace.startedAt) }}
            </span>
          </header>
          <ol class="event-timeline">
            <li v-for="event in trace.events" :key="event.eventId" :class="eventTone(event.type)">
              <i></i>
              <button type="button" @click="detail = event">
                <b>{{ eventLabel(event.type) }}</b>
                <span>{{ event.message || '—' }}</span>
                <em v-if="eventFacts(event).length">{{ eventFacts(event).join(' · ') }}</em>
              </button>
              <time>{{ formatDate(event.timestamp) }}</time>
            </li>
          </ol>
        </article>
      </div>

      <div v-else-if="section === 'evals'" class="eval-layout">
        <form class="eval-form" @submit.prevent="runEvaluation">
          <label><small>INVOCATION</small>
            <select v-model="evalTarget">
              <option v-for="option in invocationOptions" :key="option.invocationId" :value="option.invocationId">{{ option.label }}</option>
            </select>
          </label>
          <label><small>CASE ID</small><input v-model="evalForm.caseId" type="text" /></label>
          <label><small>EXPECTED TOOL SEQUENCE</small><input v-model="evalForm.expectedToolSequence" type="text" placeholder="web_search, file_write" /></label>
          <label><small>FORBIDDEN TOOLS</small><input v-model="evalForm.forbiddenTools" type="text" placeholder="run_command" /></label>
          <label><small>MAX TOOL CALLS</small><input v-model="evalForm.maxToolCalls" type="number" min="1" placeholder="不限制" /></label>
          <label><small>RESPONSE KEYWORDS</small><input v-model="evalForm.requiredResponseKeywords" type="text" placeholder="结论, 建议" /></label>
          <label class="eval-check"><input v-model="evalForm.requireCompleted" type="checkbox" /><span>要求运行成功收口</span></label>
          <button type="submit" :disabled="!evalTarget || evalBusy">{{ evalBusy ? '评估中…' : '运行评估' }}</button>
        </form>

        <aside v-if="evalResult" class="eval-result">
          <header>
            <span class="status-pill" :class="evalResult.passed ? 'success' : 'danger'">{{ evalResult.passed ? 'PASSED' : 'FAILED' }}</span>
            <strong>{{ (evalResult.score * 100).toFixed(0) }}%</strong>
          </header>
          <dl>
            <div><dt>Tool calls</dt><dd>{{ evalResult.toolCallCount }}（失败 {{ evalResult.failedToolCallCount }}）</dd></div>
            <div><dt>Trajectory</dt><dd>{{ evalResult.actualToolSequence.join(' → ') || '—' }}</dd></div>
          </dl>
          <ul>
            <li v-for="finding in evalResult.findings" :key="finding.check" :class="finding.passed ? 'passed' : 'failed'">
              <b>{{ finding.passed ? '✓' : '✕' }}</b>
              <span><strong>{{ finding.check }}</strong><small>{{ finding.detail }}</small></span>
            </li>
          </ul>
          <button class="text-button" type="button" @click="detail = evalResult">查看完整结果</button>
        </aside>
        <aside v-else class="eval-hint">
          <h2>轨迹优先的回归校验</h2>
          <p>最终答案正确不代表执行路径正确。选择一次 Invocation，声明期望的工具序列、禁用工具与调用预算，服务端会回放已存储的领域事件逐项比对。</p>
          <p v-if="!invocationOptions.length">当前会话还没有已存储的执行事件，先在 Chat 中运行一次任务。</p>
        </aside>
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

      <div v-if="isEmpty" class="empty-state">
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
