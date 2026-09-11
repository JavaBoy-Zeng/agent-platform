import { computed, onMounted, onUnmounted, ref } from 'vue'
import {
  cancelAgentRun,
  createAgentRun,
  getAgentState,
  getAgentRun,
  getPendingAction,
  resolvePendingAction,
  streamAgentRun
} from '../services/agentApi.js'
import { buildRunInput } from '../utils/runInput.js'
import {
  deleteSessionRecord,
  deleteSessionRecords,
  getModelCatalog,
  getModelManagement,
  getSessionRunHistory,
  getSessionPage,
  updateSessionPinned,
  updateSessionTitle
} from '../services/consoleApi.js'
import { appendOperationGroup, compactOperationGroups } from '../utils/operationGroups.js'

const LEGACY_STORAGE_KEY = 'agentos.console.sessions.v1'
const LEGACY_ACTIVE_SESSION_KEY = 'agentos.console.active-session.v1'
const STORAGE_KEY_PREFIX = 'agentos.console.sessions.v2'
const ACTIVE_SESSION_KEY_PREFIX = 'agentos.console.active-session.v2'
const SESSION_PAGE_SIZE = 20
const SESSION_CACHE_SIZE = 20
const TERMINAL_STATUSES = new Set(['COMPLETED', 'FAILED', 'CANCELLED'])
const EPHEMERAL_EVENTS = new Set(['status', 'message.delta', 'tool.input.delta'])
const APPROVAL_MODE_KEY = 'agentos.console.approval-mode.v1'
function randomId(prefix) {
  const token = globalThis.crypto?.randomUUID?.()
    || `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}-${Math.random().toString(36).slice(2)}`
  return `${prefix}-${token}`
}

function scopedStorageKey(prefix, ownerId) {
  return `${prefix}.${encodeURIComponent(ownerId)}`
}

function sanitizeId(value, fallback) {
  const sanitized = String(value || '')
    .trim()
    .replace(/[^a-zA-Z0-9._-]+/g, '-')
    .replace(/^-+|-+$/g, '')
  return sanitized || fallback
}

function nowIso() {
  return new Date().toISOString()
}

function newSession(id = randomId('session')) {
  return {
    id,
    title: '未命名任务',
    createdAt: nowIso(),
    updatedAt: nowIso(),
    pinned: false,
    pinnedAt: '',
    state: null,
    activeStage: 0,
    phase: '',
    runBoundary: 0,
    runError: '',
    submitting: false,
    messages: []
  }
}

function isSessionBusy(session) {
  return Boolean(session?.submitting || (session?.activeRunId
    && !['WAITING', 'COMPLETED', 'FAILED', 'CANCELLED'].includes(session?.state?.status)))
}

function sessionTitle(remote, cached) {
  const displayTitle = String(remote.state?.displayTitle || '').trim()
  if (displayTitle) return displayTitle
  if (cached?.title && !['未命名任务', '未命名项目'].includes(cached.title)) return cached.title
  const objective = String(remote.state?.lastObjective || '').trim()
  if (!objective) return '未命名任务'
  return objective.length > 28 ? `${objective.slice(0, 28)}…` : objective
}

function mergeRemoteSession(remote, cached) {
  return {
    ...newSession(remote.sessionId),
    ...cached,
    id: remote.sessionId,
    title: sessionTitle(remote, cached),
    createdAt: remote.createdAt || cached?.createdAt || nowIso(),
    updatedAt: remote.lastActiveAt || cached?.updatedAt || nowIso(),
    pinned: remote.state?.pinned === true,
    pinnedAt: String(remote.state?.pinnedAt || ''),
    serverBacked: true,
    serverState: remote.state || {},
    submitting: false,
    activeStage: cached?.activeRunId ? Number(cached.activeStage || 1) : 0,
    messages: Array.isArray(cached?.messages) ? cached.messages : []
  }
}

export function isUnsavedDraft(session) {
  return Boolean(session)
    && !session.serverBacked
    && !session.activeRunId
    && !session.state
    && (!Array.isArray(session.messages) || session.messages.length === 0)
}

export function useAgentConsole(ownerId = '') {
  const owner = String(ownerId || '').trim()
  const storageKey = scopedStorageKey(STORAGE_KEY_PREFIX, owner)
  const activeSessionKey = scopedStorageKey(ACTIVE_SESSION_KEY_PREFIX, owner)
  const sessions = ref([])
  const currentSessionId = ref('')
  const agentId = ref('plan-execute-agent')
  const sessionId = ref('')
  const prompt = ref('')
  const models = ref([])
  const selectedModelKey = ref('')
  const approvalMode = ref(localStorage.getItem(APPROVAL_MODE_KEY) || 'FULL_ACCESS')
  const connection = ref('standby')
  const loadingSessions = ref(false)
  const sessionHistoryError = ref('')
  const serverSessionTotal = ref(0)
  const loadedServerSessions = ref(0)
  const serverHasMoreSessions = ref(false)
  const monitoredRuns = new Map()
  const pipelineTimers = new Map()
  const pendingMessageDeltas = new Map()
  const streamAbortController = new AbortController()
  let disposed = false

  const currentSession = computed(() =>
    sessions.value.find((session) => session.id === currentSessionId.value) || null)
  const currentSessionDraft = computed(() => isUnsavedDraft(currentSession.value))
  const currentModel = computed(() =>
    models.value.find(model => model.key === selectedModelKey.value) || null)

  const messages = computed(() => currentSession.value?.messages || [])
  const busy = computed(() => isSessionBusy(currentSession.value))
  const executingAgentId = computed(() => currentSession.value?.executingAgentId || '')
  const activeStage = computed(() => Number(currentSession.value?.activeStage || 0))
  const canStop = computed(() => Boolean(currentSession.value?.activeRunId))
  const runtimeState = computed(() => currentSession.value?.state || {
    status: 'READY',
    iteration: 0,
    output: '',
    error: '',
    updatedAt: null
  })
  const hasMoreSessions = computed(() => serverHasMoreSessions.value)
  const currentPhase = computed(() => String(
    currentSession.value?.phase
    || (isSessionBusy(currentSession.value) ? '正在运行' : '')).trim())

  function persist() {
    if (disposed || !owner) return
    // 空白草稿只存在于当前页面生命周期；首次发送消息后才进入任务缓存和目录。
    const persistableSessions = sessions.value.filter(session => !isUnsavedDraft(session))
    const recent = persistableSessions.slice(0, SESSION_CACHE_SIZE)
    const active = currentSession.value
    const cache = active && !isUnsavedDraft(active) && !recent.some(session => session.id === active.id)
      ? [...recent.slice(0, SESSION_CACHE_SIZE - 1), active]
      : recent
    // status 和 delta 只服务于实时 UI；浏览器缓存也只保存可重建的稳定记录。
    const cached = cache.map(session => ({
      ...session,
      phase: '',
      messages: session.messages.filter(message => message.role !== 'trace'
          && !(message.role === 'assistant' && message.streaming))
        .map(message => ({ ...message, streaming: false }))
    }))
    try { localStorage.setItem(storageKey, JSON.stringify(cached)) }
    catch { /* 浏览器缓存容量不足不应中断运行；服务端仍可回放记录。 */ }
    if (currentSessionId.value && !isUnsavedDraft(active)) {
      localStorage.setItem(activeSessionKey, currentSessionId.value)
    } else {
      localStorage.removeItem(activeSessionKey)
    }
  }

  function mergeSessionPage(items, reset) {
    const cachedById = new Map(sessions.value.map(session => [session.id, session]))
    const remoteSessions = items.map(remote => mergeRemoteSession(remote, cachedById.get(remote.sessionId)))

    if (reset) {
      const remoteIds = new Set(remoteSessions.map(session => session.id))
      const localOnly = sessions.value.filter(session =>
        isUnsavedDraft(session) && !remoteIds.has(session.id))
      sessions.value = [...localOnly, ...remoteSessions]
      return
    }

    const existingIds = new Set(sessions.value.map(session => session.id))
    sessions.value.push(...remoteSessions.filter(session => !existingIds.has(session.id)))
  }

  async function loadSessionPage(reset = false) {
    if (loadingSessions.value) return
    loadingSessions.value = true
    sessionHistoryError.value = ''
    const offset = reset ? 0 : loadedServerSessions.value
    try {
      const page = await getSessionPage(offset, SESSION_PAGE_SIZE)
      if (disposed) return
      connection.value = 'online'
      mergeSessionPage(Array.isArray(page?.items) ? page.items : [], reset)
      serverSessionTotal.value = Number(page?.total || 0)
      loadedServerSessions.value = offset + Number(page?.items?.length || 0)
      serverHasMoreSessions.value = Boolean(page?.hasMore)
      persist()
    } catch (error) {
      connection.value = error instanceof TypeError ? 'offline' : connection.value
      sessionHistoryError.value = '无法读取服务端会话，当前显示浏览器缓存。'
    } finally {
      loadingSessions.value = false
    }
  }

  function loadMoreSessions() {
    return loadSessionPage(false)
  }

  async function hydrateTranscript(session) {
    if (!session.serverBacked || session.historyLoaded || session.messages.length) return
    session.historyLoading = true
    try {
      const events = await getSessionRunHistory(session.id)
      if (disposed) return
      const recovered = []
      const replay = { messages: recovered, executingAgentId: '' }
      const boundaries = new Map()
      const ordered = [...(Array.isArray(events) ? events : [])]
        .sort((left, right) => new Date(left.timestamp) - new Date(right.timestamp)
          || Number(left.seq || 0) - Number(right.seq || 0))
      for (const event of ordered) {
        if (event.event === 'run.started') {
          recovered.push({
            id: `${event.turnId}:user`, role: 'user',
            content: event.data?.objective || '', createdAt: event.timestamp
          })
          boundaries.set(event.runId, recovered.length)
        } else if (event.event === 'message.completed') {
          recovered.push({
            id: event.itemId, role: 'assistant', agentId: event.agentId,
            content: event.data?.content || '', createdAt: event.timestamp
          })
        } else if (event.event === 'tool.started'
          || event.event === 'tool.awaiting_approval'
          || event.event === 'tool.approved'
          || event.event === 'tool.completed' || event.event === 'tool.failed') {
          applyToolEvent(replay, event)
        } else if (event.event === 'artifact.created') {
          applyArtifactEvent(replay, event)
        } else if (['run.completed', 'run.failed', 'run.cancelled'].includes(event.event)) {
          const snapshot = event.data?.snapshot || {}
          const boundary = boundaries.get(event.runId) || 0
          const target = [...recovered.slice(boundary)].reverse()
            .find(message => message.role === 'assistant' || message.role === 'error')
          if (target) {
            target.runEnd = true
            target.runStatus = snapshot.status
            target.runId = event.runId
            target.durationMs = snapshot.durationMs
            target.tokenUsageDetails = snapshot.usage
          } else if (snapshot.status !== 'COMPLETED') {
            recovered.push({
              id: `${event.runId}:terminal`,
              role: snapshot.status === 'FAILED' ? 'error' : 'event',
              content: snapshot.error?.message || (snapshot.status === 'CANCELLED' ? '任务已取消。' : ''),
              createdAt: event.timestamp, runEnd: true, runStatus: snapshot.status,
              durationMs: snapshot.durationMs, tokenUsageDetails: snapshot.usage
            })
          }
        }
      }
      session.messages = recovered
      session.historyLoaded = true
      connection.value = 'online'
      persist()
    } catch (error) {
      connection.value = error instanceof TypeError ? 'offline' : connection.value
    } finally {
      session.historyLoading = false
    }
  }

  function activateSession(session) {
    currentSessionId.value = session.id
    sessionId.value = session.id
    if (!disposed && owner) localStorage.setItem(activeSessionKey, session.id)
  }

  function createSession() {
    if (isUnsavedDraft(currentSession.value)) {
      prompt.value = ''
      activateSession(currentSession.value)
      persist()
      return currentSession.value
    }
    const session = newSession()
    sessions.value.unshift(session)
    activateSession(session)
    prompt.value = ''
    persist()
    return session
  }

  async function renameSession(id, title) {
    const session = sessions.value.find((item) => item.id === id)
    const normalizedTitle = String(title || '').trim()
    if (!session || !normalizedTitle) return
    const previousTitle = session.title
    session.title = normalizedTitle
    session.updatedAt = nowIso()
    persist()
    if (!session.serverBacked) return
    try {
      await updateSessionTitle(id, normalizedTitle)
      connection.value = 'online'
    } catch (error) {
      session.title = previousTitle
      connection.value = error instanceof TypeError ? 'offline' : connection.value
      sessionHistoryError.value = '会话重命名未能保存到服务端。'
      persist()
    }
  }

  async function toggleSessionPin(id) {
    const session = sessions.value.find((item) => item.id === id)
    if (!session) return false
    const previousPinned = Boolean(session.pinned)
    const previousPinnedAt = String(session.pinnedAt || '')
    session.pinned = !previousPinned
    session.pinnedAt = session.pinned ? nowIso() : ''
    persist()
    if (!session.serverBacked) return true
    try {
      const remote = await updateSessionPinned(id, session.pinned)
      session.pinned = remote?.state?.pinned === true
      session.pinnedAt = String(remote?.state?.pinnedAt || session.pinnedAt || '')
      connection.value = 'online'
      persist()
      return true
    } catch (error) {
      session.pinned = previousPinned
      session.pinnedAt = previousPinnedAt
      connection.value = error instanceof TypeError ? 'offline' : connection.value
      sessionHistoryError.value = '会话置顶状态未能保存到服务端。'
      persist()
      return false
    }
  }

  async function deleteSession(id, { skipRemote = false } = {}) {
    let index = sessions.value.findIndex((item) => item.id === id)
    if (index < 0 || isSessionBusy(sessions.value[index])) return false

    const target = sessions.value[index]
    if (target.serverBacked && !skipRemote) {
      try {
        await deleteSessionRecord(id)
        serverSessionTotal.value = Math.max(0, serverSessionTotal.value - 1)
        loadedServerSessions.value = Math.max(0, loadedServerSessions.value - 1)
        connection.value = 'online'
      } catch (error) {
        connection.value = error instanceof TypeError ? 'offline' : connection.value
        sessionHistoryError.value = '会话未能从服务端删除。'
        return false
      }
    }

    index = sessions.value.findIndex((item) => item.id === id)
    if (index < 0) return false

    const deletingCurrentSession = id === currentSessionId.value
    sessions.value.splice(index, 1)

    if (deletingCurrentSession) {
      const nextSession = sessions.value[Math.min(index, sessions.value.length - 1)]
      if (nextSession) {
        activateSession(nextSession)
        prompt.value = ''
      } else {
        createSession()
        return true
      }
    }

    persist()
    return true
  }

  async function deleteSessions(ids) {
    const uniqueIds = [...new Set(Array.isArray(ids) ? ids : [])]
    const remoteIds = uniqueIds.filter(id => {
      const session = sessions.value.find(item => item.id === id)
      return session?.serverBacked && !isSessionBusy(session)
    })
    const failedRemoteIds = new Set()
    let deletedRemoteCount = 0

    if (remoteIds.length) {
      try {
        const result = await deleteSessionRecords(remoteIds)
        const notFound = result?.notFound || []
        const missing = result?.missing || []
        // 服务端确认不存在的会话同样按"已删除"处理，避免侧栏卡死（不计入失败）。
        deletedRemoteCount = Number(result?.deleted || 0) + notFound.length + missing.length
        connection.value = 'online'
      } catch (error) {
        // 兼容尚未升级批量接口的旧服务端，桌面客户端仍可退回逐条删除。
        if (error?.status === 404 || error?.status === 405) {
          for (const id of remoteIds) {
            try {
              await deleteSessionRecord(id)
              deletedRemoteCount += 1
            } catch (innerError) {
              if (innerError?.status !== 404) failedRemoteIds.add(id)
              else deletedRemoteCount += 1
            }
          }
        } else {
          remoteIds.forEach(id => failedRemoteIds.add(id))
          connection.value = error instanceof TypeError ? 'offline' : connection.value
        }
      }
      serverSessionTotal.value = Math.max(0, serverSessionTotal.value - deletedRemoteCount)
      loadedServerSessions.value = Math.max(0, loadedServerSessions.value - deletedRemoteCount)
    }

    let failed = failedRemoteIds.size
    for (const id of uniqueIds) {
      if (failedRemoteIds.has(id)) continue
      if (!await deleteSession(id, { skipRemote: true })) failed += 1
    }
    if (failed > 0) {
      sessionHistoryError.value = '部分会话未能从服务端删除。'
    }
    return { deleted: uniqueIds.length - failed, failed }
  }

  async function selectSession(id) {
    const session = sessions.value.find((item) => item.id === id)
    if (!session) return
    activateSession(session)
    void hydrateTranscript(session)

    if (session.activeRunId) {
      void monitorRun(session)
      return
    }

    try {
      const state = await getAgentState(id)
      connection.value = 'online'
      if (state) {
        session.state = state
        if (state.status === 'WAITING') {
          const waiting = await getPendingAction(id)
          if (waiting) setPendingApproval(session, waiting)
        }
        persist()
      }
    } catch {
      connection.value = 'offline'
    }
  }

  function ensureSession(id, firstPrompt) {
    let session = sessions.value.find((item) => item.id === id)
    if (!session) {
      session = newSession(id)
      sessions.value.unshift(session)
    }
    if (['未命名任务', '未命名项目'].includes(session.title)) {
      session.title = firstPrompt.length > 28 ? `${firstPrompt.slice(0, 28)}…` : firstPrompt
    }
    activateSession(session)
    return session
  }

  function addMessage(session, role, content, extra = {}) {
    session.messages.push({
      id: randomId('message'),
      role,
      content,
      createdAt: nowIso(),
      ...(role === 'assistant' ? { agentId: session.executingAgentId || '' } : {}),
      ...extra
    })
    session.updatedAt = nowIso()
  }

  async function refreshServerModel() {
    // 优先调用公开的 /api/models：所有登录用户（含非 admin）都能拿到已启用模型目录；
    // 403/旧版本后端再回退到 admin 专属的 /api/model-management。
    let configured = null
    try {
      configured = await getModelCatalog()
    } catch (catalogError) {
      try {
        const snapshot = await getModelManagement()
        configured = Array.isArray(snapshot?.models) ? snapshot.models : []
      } catch {
        configured = []
      }
    }
    if (!Array.isArray(configured)) configured = []
    models.value = configured
      .filter(model => model?.id)
      .map(model => ({
        key: model.id,
        id: model.id,
        vendorModelId: model.modelId,
        name: model.modelId,
        modelType: model.modelType,
        provider: model.providerName,
        providerType: model.providerType
      }))
    if (!models.value.some(model => model.key === selectedModelKey.value)) {
      selectedModelKey.value = ''
    }
  }

  function selectTaskModel(key) {
    const normalized = String(key || '')
    if (models.value.some(model => model.key === normalized)) selectedModelKey.value = normalized
  }

  function setApprovalMode(mode) {
    const normalized = ['REQUEST_APPROVAL', 'RISK_BASED', 'FULL_ACCESS'].includes(mode)
      ? mode
      : 'FULL_ACCESS'
    approvalMode.value = normalized
    localStorage.setItem(APPROVAL_MODE_KEY, normalized)
  }

  function startPipeline(session) {
    const pendingTimer = pipelineTimers.get(session.id)
    if (pendingTimer) window.clearTimeout(pendingTimer)
    pipelineTimers.delete(session.id)
    session.activeStage = 1
  }

  function stopPipeline(session) {
    session.activeStage = 5
    const pendingTimer = pipelineTimers.get(session.id)
    if (pendingTimer) window.clearTimeout(pendingTimer)
    const timer = window.setTimeout(() => {
      pipelineTimers.delete(session.id)
      if (!isSessionBusy(session)) {
        session.activeStage = 0
        persist()
      }
    }, 700)
    pipelineTimers.set(session.id, timer)
  }

  // 文件读取、命令执行、文件修改等操作记录必须来源于 Runtime 真实工具事件，
  // 这里仅按 toolName 分类，绝不依据模型生成的文本描述。
  function classifyTool(toolName) {
    if (toolName === 'file_read') return 'read'
    if (toolName === 'file_write') return 'edit'
    if (toolName === 'run_command') return 'command'
    return 'tool'
  }

  function appendOperation(session, kind, item, createdAt = nowIso()) {
    return appendOperationGroup(session.messages, {
      id: randomId('ops'),
      kind,
      item,
      createdAt
    })
  }

  function findToolItem(session, toolCallId) {
    for (const message of session.messages) {
      if (message.role !== 'ops') continue
      const item = message.items?.find(candidate => candidate.toolCallId === toolCallId)
      if (item) return item
    }
    return null
  }

  function applyToolEvent(session, event) {
    const data = event.data || {}
    const toolCallId = String(data.toolCallId || event.itemId || '')
    if (!toolCallId) return
    let item = findToolItem(session, toolCallId)
    if (!item) {
      const toolName = data.toolName || 'tool'
      item = {
        toolCallId,
        toolName,
        arguments: data.arguments || {},
        summary: '',
        outputRef: '',
        truncated: false,
        success: true,
        status: 'RUNNING'
      }
      appendOperation(session, classifyTool(toolName), item, event.timestamp || nowIso())
    }
    if (data.toolName) item.toolName = data.toolName
    if (data.arguments && Object.keys(data.arguments).length) item.arguments = data.arguments
    if (data.summary) item.summary = data.summary
    if (data.outputRef) item.outputRef = data.outputRef
    item.truncated = Boolean(data.truncated)
    const states = {
      'tool.started': ['RUNNING', true],
      'tool.awaiting_approval': ['WAITING', true],
      'tool.approved': ['APPROVED', true],
      'tool.completed': ['COMPLETED', true],
      'tool.failed': ['FAILED', false]
    }
    const [status, success] = states[event.event] || [item.status, item.success]
    item.status = status
    item.success = success
    if (data.error) {
      item.error = data.error
      item.summary = data.error.message || item.summary
    }
    if (event.event === 'tool.awaiting_approval') session.phase = '等待批准工具调用'
    else if (event.event === 'tool.started') session.phase = data.toolName
      ? `正在执行 ${data.toolName}` : '正在执行工具'
    else if (['tool.completed', 'tool.failed'].includes(event.event)) session.phase = ''
  }

  function applyArtifactEvent(session, event) {
    const data = event.data || {}
    const artifactId = String(data.artifactId || event.itemId || '')
    if (!artifactId || session.messages.some(message =>
      message.role === 'artifact' && message.artifactId === artifactId)) return
    addMessage(session, 'artifact', data.filename || 'Agent 产物', {
      id: event.itemId,
      artifactId,
      filename: data.filename || '',
      contentType: data.contentType || '',
      sizeBytes: Number(data.sizeBytes || 0),
      agentId: event.agentId || '',
      createdAt: event.timestamp || nowIso()
    })
  }

  function ensureAssistantMessage(session, event) {
    let message = session.messages.find(candidate =>
      candidate.role === 'assistant' && candidate.id === event.itemId)
    if (!message) {
      addMessage(session, 'assistant', '', {
        id: event.itemId,
        agentId: event.agentId || '',
        createdAt: event.timestamp || nowIso(),
        streaming: true
      })
      message = session.messages.at(-1)
    }
    return message
  }

  function flushMessageDelta(session, itemId) {
    const key = `${session.id}:${itemId}`
    const pending = pendingMessageDeltas.get(key)
    if (!pending) return
    if (pending.timer) window.clearTimeout(pending.timer)
    pendingMessageDeltas.delete(key)
    const message = session.messages.find(candidate => candidate.id === itemId)
    if (message) message.content += pending.chunks.join('')
  }

  function queueMessageDelta(session, event) {
    const delta = String(event.data?.delta || '')
    if (!delta) return
    const message = ensureAssistantMessage(session, event)
    const key = `${session.id}:${event.itemId}`
    let pending = pendingMessageDeltas.get(key)
    if (!pending) {
      pending = { chunks: [], timer: 0 }
      pendingMessageDeltas.set(key, pending)
    }
    pending.chunks.push(delta)
    if (!pending.timer) {
      pending.timer = window.setTimeout(() => flushMessageDelta(session, message.id), 50)
    }
  }

  function completeAssistantMessage(session, event) {
    flushMessageDelta(session, event.itemId)
    const message = ensureAssistantMessage(session, event)
    // completed 是最终事实：即使中间 delta 丢失，也用完整快照校正。
    message.content = String(event.data?.content || '')
    message.streaming = false
    message.agentId = event.agentId || message.agentId || ''
    message.createdAt = event.timestamp || message.createdAt
    session.phase = ''
  }

  function handleStreamEvent(session, event) {
    if (!event?.event) return
    if (event.agentId) session.executingAgentId = event.agentId
    switch (event.event) {
      case 'run.started':
        session.activeStage = 1
        break
      case 'status':
        session.phase = String(event.data?.text || '')
        break
      case 'message.started':
        session.activeStage = 5
        ensureAssistantMessage(session, event)
        break
      case 'message.delta':
        session.activeStage = 5
        queueMessageDelta(session, event)
        break
      case 'message.completed':
        session.activeStage = 5
        completeAssistantMessage(session, event)
        break
      case 'tool.started':
      case 'tool.awaiting_approval':
      case 'tool.approved':
      case 'tool.completed':
      case 'tool.failed':
        session.activeStage = 3
        applyToolEvent(session, event)
        break
      case 'artifact.created':
        applyArtifactEvent(session, event)
        break
      case 'usage':
        session.runUsage = event.data?.aggregate || event.data || null
        break
      case 'error':
        session.runError = event.data?.error?.message || event.data?.message || session.runError
        break
      case 'run.waiting':
      case 'run.completed':
      case 'run.failed':
      case 'run.cancelled':
        applyRunSnapshot(session, event.data?.snapshot)
        break
    }
    session.updatedAt = nowIso()
  }

  function applyRunSnapshot(session, snapshot) {
    if (!snapshot?.status) return
    session.runSnapshot = snapshot
    session.state = {
      ...session.state,
      status: snapshot.status,
      output: snapshot.output || '',
      error: snapshot.error?.message || '',
      updatedAt: snapshot.updatedAt || nowIso()
    }
    if (snapshot.status === 'WAITING' && snapshot.pendingAction) {
      setPendingApproval(session, snapshot)
    }
    persist()
  }

  function markRunResult(session, status, boundary, task, runId = '') {
    const messages = session.messages.slice(boundary)
    let target = status === 'COMPLETED'
      ? [...messages].reverse().find(message => message.role === 'assistant')
      : [...messages].reverse().find(message => message.role === 'error' || message.role === 'event')
    if (!target) return null
    const partialAnswer = [...messages].reverse().find(message => message.role === 'assistant')
    target.runEnd = true
    target.runStatus = status
    target.retryPrompt = task
    target.copyContent = status === 'COMPLETED'
      ? target.content
      : (partialAnswer?.content || target.content)
    target.runId = runId
    return target
  }

  function finalizeRun(session, snapshot) {
    const runId = snapshot.runId || session.activeRunId
    const boundary = Number(session.runBoundary || 0)
    const task = session.runTask || ''
    applyRunSnapshot(session, snapshot)
    const status = snapshot.status

    if (status === 'WAITING') {
      session.updatedAt = nowIso()
      persist()
      return
    }

    if (status === 'COMPLETED') {
      const output = snapshot.output || ''
      const streaming = session.messages.filter(
        message => message.role === 'assistant' && message.streaming)
      if (streaming.length) {
        const last = streaming[streaming.length - 1]
        if (output && last.content !== output) last.content = output
        streaming.forEach(message => { message.streaming = false })
      } else if (output && !session.messages.some(
        message => message.role === 'assistant' && message.content === output)) {
        addMessage(session, 'assistant', output)
      }
    } else if (status === 'CANCELLED') {
      markAssistantDone(session)
      addTerminalMessage(
        session, `${runId}:cancelled`, 'event', session.runError || '任务已取消。')
    } else if (status === 'FAILED') {
      markAssistantDone(session)
      const rejected = snapshot.error?.code === 'APPROVAL_REJECTED'
      addTerminalMessage(
        session, `${runId}:failed`, rejected ? 'event' : 'error',
        rejected
          ? '操作已拒绝，任务已停止。'
          : (session.runError || snapshot.error?.message || 'Agent 未能完成任务。'))
    }

    session.activeRunId = ''
    session.phase = ''
    const resultMessage = markRunResult(
      session, status, boundary, task, runId)
    if (resultMessage) {
      resultMessage.durationMs = Number(snapshot.durationMs || 0)
      resultMessage.tokenUsageDetails = snapshot.usage || session.runUsage || null
    }
    session.runBoundary = 0
    session.runError = ''
    session.runTask = ''
    session.runUsage = null
    session.updatedAt = nowIso()
    persist()
  }

  function markAssistantDone(session) {
    session.messages.forEach(message => {
      if (message.role === 'assistant') message.streaming = false
    })
  }

  function addTerminalMessage(session, id, role, content) {
    if (!session.messages.some(message => message.id === id)) {
      addMessage(session, role, content, { id })
    }
  }

  function handleBackgroundEvent(session, runId, packet) {
    const event = packet.data || {}
    const sequence = Number(event.seq || packet.id || 0)
    if (event.runId && event.runId !== runId) return
    if (sequence && sequence <= Number(session.lastSeq || 0)) return
    if (sequence) session.lastSeq = sequence
    handleStreamEvent(session, event)
    if (!EPHEMERAL_EVENTS.has(event.event)) persist()
  }

  async function recoverMissingRun(session, runId) {
    session.activeRunId = ''
    try {
      const state = await getAgentState(session.id)
      if (state) {
        finalizeRun(session, {
          runId,
          status: state.status,
          output: state.output || '',
          error: state.error ? { code: 'RUN_FAILED', message: state.error, retryable: false } : null,
          lastSeq: session.lastSeq || 0
        })
        return
      }
    } catch {
      connection.value = 'offline'
    }
    session.state = {
      ...session.state,
      status: 'FAILED',
      error: '后台运行记录已不存在，可能是服务端发生过重启。',
      updatedAt: nowIso()
    }
    addTerminalMessage(session, `${runId}:missing`, 'error', session.state.error)
    persist()
  }

  function delay(milliseconds) {
    return new Promise(resolve => window.setTimeout(resolve, milliseconds))
  }

  async function monitorRun(session) {
    const runId = session.activeRunId
    if (!runId) return null
    if (monitoredRuns.has(runId)) return monitoredRuns.get(runId)

    const monitoring = (async () => {
      startPipeline(session)
      // 恢复监控时旧缓存可能缺少运行边界；从当前位置起算，避免混入上一轮的操作分组
      if (!session.runBoundary) session.runBoundary = session.messages.length
      let retryCount = 0
      while (!disposed && session.activeRunId === runId) {
        try {
          const snapshot = await getAgentRun(runId, streamAbortController.signal)
          if (disposed) return null
          connection.value = 'online'
          applyRunSnapshot(session, snapshot)
          if (TERMINAL_STATUSES.has(snapshot.status)
              && Number(session.lastSeq || 0) >= Number(snapshot.lastSeq || 0)) {
            finalizeRun(session, snapshot)
            return snapshot
          }

          const finalSnapshot = await streamAgentRun(
            runId,
            session.lastSeq || 0,
            packet => {
              if (!disposed) handleBackgroundEvent(session, runId, packet)
            },
            streamAbortController.signal
          )
          if (disposed) return null
          connection.value = 'online'
          finalizeRun(session, finalSnapshot)
          return finalSnapshot
        } catch (error) {
          if (disposed || error?.name === 'AbortError') return null
          if (error?.status === 404) {
            await recoverMissingRun(session, runId)
            return null
          }
          connection.value = error instanceof TypeError ? 'offline' : 'online'
          retryCount += 1
          await delay(Math.min(5000, 500 * (2 ** Math.min(retryCount, 4))))
        }
      }
      return null
    })().finally(() => {
      monitoredRuns.delete(runId)
      if (!session.activeRunId) stopPipeline(session)
      persist()
    })

    monitoredRuns.set(runId, monitoring)
    return monitoring
  }

  function setPendingApproval(session, response) {
    const action = response.pendingAction
    if (!action || session.messages.some(message =>
      message.role === 'approval'
      && message.pendingActionId === action.pendingActionId
      && !message.resolved)) return
    const argumentsSummary = action.payload?.arguments || {}
    addMessage(session, 'approval', action.description || action.title || '等待人工审批', {
      invocationId: response.invocationId,
      pendingActionId: action.pendingActionId,
      title: action.title || '需要批准操作',
      payload: {
        toolCallId: action.payload?.toolCallId || '',
        workspaceReconnect: Boolean(action.payload?.workspaceReconnect),
        toolName: action.payload?.toolName || '',
        riskLevel: action.payload?.riskLevel || '',
        arguments: {
          path: argumentsSummary.path || '',
          mode: action.payload?.toolName === 'file_write'
            ? (argumentsSummary.mode || 'CREATE_NEW')
            : (argumentsSummary.mode || ''),
          createParentDirectories: Boolean(argumentsSummary.createParentDirectories),
          repository: argumentsSummary.repository || '',
          paths: Array.isArray(argumentsSummary.paths) ? argumentsSummary.paths.slice(0, 100) : [],
          message: argumentsSummary.message || ''
        }
      },
      resolved: false
    })
  }

  async function execute(attachments = [], workspaceContext = null) {
    const task = prompt.value.trim()
    if (!task || busy.value) return
    if (!currentModel.value?.id) throw new Error('请先选择一个已启用的模型')

    const normalizedSessionId = sanitizeId(sessionId.value, randomId('session'))
    const normalizedAgentId = sanitizeId(agentId.value, 'plan-execute-agent')
    const session = ensureSession(normalizedSessionId, task)
    sessionId.value = normalizedSessionId
    agentId.value = normalizedAgentId
    session.executingAgentId = ''
    addMessage(session, 'user', task)
    // 本轮运行的操作分组从用户消息之后开始，避免与历史轮次合并
    session.runBoundary = session.messages.length
    session.runError = ''
    session.runTask = task
    session.runUsage = null
    session.phase = '正在启动任务'
    session.state = {
      status: 'RUNNING',
      iteration: (session.state?.iteration || 0) + 1,
      output: '',
      error: '',
      updatedAt: nowIso()
    }
    session.submitting = true
    prompt.value = ''
    startPipeline(session)
    persist()

    try {
      const attributes = {
        source: 'agentos-console',
        approvalMode: approvalMode.value
      }
      attributes.modelId = currentModel.value.id
      if (workspaceContext?.name) {
        attributes.workspaceName = workspaceContext.name
        attributes.workspaceContextFiles = workspaceContext.files?.length || 0
        if (workspaceContext.workspaceId) {
          attributes.workspaceId = workspaceContext.workspaceId
          attributes.workspaceSessionId = normalizedSessionId
          attributes.workspaceRuntime = workspaceContext.workspaceRuntime
        }
      }
      const run = await createAgentRun({
        agentId: normalizedAgentId,
        sessionId: normalizedSessionId,
        input: buildRunInput(task, attachments, workspaceContext),
        attributes
      })
      connection.value = 'online'
      session.activeRunId = run.runId
      session.submitting = false
      session.lastSeq = Number(run.lastSeq || 0)
      applyRunSnapshot(session, run)
      await monitorRun(session)
    } catch (error) {
      connection.value = error instanceof TypeError ? 'offline' : 'online'
      const message = error.message || '无法连接 AgentOS Server'
      session.state = {
        ...session.state,
        status: 'FAILED',
        error: message,
        updatedAt: nowIso()
      }
      addMessage(session, 'error', message, {
        runEnd: true,
        runStatus: 'FAILED',
        retryPrompt: task,
        copyContent: message
      })
      session.runBoundary = 0
      session.runTask = ''
      session.runUsage = null
    } finally {
      session.submitting = false
      if (!session.activeRunId) stopPipeline(session)
      persist()
    }
  }

  async function resolveApproval(messageId, approved) {
    if (!currentSession.value || currentSession.value.submitting) return
    const session = currentSession.value
    const message = session.messages.find(item => item.id === messageId)
    if (!message || message.role !== 'approval' || message.resolved) return
    session.submitting = true
    startPipeline(session)
    try {
      const response = await resolvePendingAction(
        message.invocationId, message.pendingActionId, approved)
      connection.value = 'online'
      message.resolved = true
      message.approved = approved
      session.activeRunId = response.runId
      session.phase = approved ? '已批准，正在恢复任务' : '正在处理拒绝操作'
      applyRunSnapshot(session, response)
      session.submitting = false
      persist()
      await monitorRun(session)
    } catch (error) {
      connection.value = error instanceof TypeError ? 'offline' : 'online'
      addMessage(session, 'error', error.message || '处理审批操作失败')
    } finally {
      session.submitting = false
      if (!session.activeRunId) stopPipeline(session)
      persist()
    }
  }

  async function cancelCurrentRun() {
    const session = currentSession.value
    const runId = session?.activeRunId
    if (!session || !runId) return
    try {
      const result = await cancelAgentRun(runId)
      connection.value = 'online'
      addTerminalMessage(session, `${runId}:cancel-requested`, 'event', '已请求停止当前任务。')
      if (TERMINAL_STATUSES.has(result.run?.status)) {
        finalizeRun(session, result.run)
      } else {
        applyRunSnapshot(session, result.run)
      }
    } catch (error) {
      connection.value = error instanceof TypeError ? 'offline' : 'online'
      addTerminalMessage(
        session, `${runId}:cancel-error`, 'error', error.message || '停止任务失败')
      persist()
    }
  }

  function clearTranscript() {
    if (!currentSession.value) return
    currentSession.value.messages = []
    persist()
  }

  async function retryMessage(task) {
    if (busy.value || !String(task || '').trim()) return
    prompt.value = String(task).trim()
    await execute()
  }

  onMounted(async () => {
    // 旧缓存没有账号归属，不将它分配给任何后续登录用户。
    localStorage.removeItem(LEGACY_STORAGE_KEY)
    localStorage.removeItem(LEGACY_ACTIVE_SESSION_KEY)
    if (!owner) return
    void refreshServerModel()
    try {
      const stored = JSON.parse(localStorage.getItem(storageKey) || '[]')
      // 旧版本会持久化空白任务；升级后直接丢弃，避免继续污染任务目录。
      sessions.value = Array.isArray(stored) ? stored.filter(session => !isUnsavedDraft(session)) : []
      sessions.value.forEach(session => {
        if (session.title === '未命名项目') session.title = '未命名任务'
        session.messages = compactOperationGroups(session.messages)
        session.submitting = false
        session.activeStage = session.activeRunId
          ? Number(session.activeStage || 1)
          : 0
      })
    } catch {
      sessions.value = []
    }

    const preferredSessionId = localStorage.getItem(activeSessionKey)
    const cachedInitial = sessions.value.find(session => session.id === preferredSessionId)
      || sessions.value[0]
    if (cachedInitial) activateSession(cachedInitial)

    await loadSessionPage(true)

    if (sessions.value.length) {
      const initial = sessions.value.find(session => session.id === preferredSessionId)
        || sessions.value.find(session => session.id === currentSessionId.value)
        || sessions.value[0]
      void selectSession(initial.id)
    } else {
      createSession()
    }
  })

  function dispose() {
    if (disposed) return
    disposed = true
    streamAbortController.abort()
    pipelineTimers.forEach(timer => window.clearTimeout(timer))
    pipelineTimers.clear()
    pendingMessageDeltas.forEach(pending => window.clearTimeout(pending.timer))
    pendingMessageDeltas.clear()
    sessions.value = []
    currentSessionId.value = ''
    sessionId.value = ''
  }

  onUnmounted(dispose)

  return {
    sessions,
    currentSessionId,
    currentSessionDraft,
    agentId,
    executingAgentId,
    sessionId,
    prompt,
    models,
    selectedModelKey,
    currentModel,
    approvalMode,
    busy,
    connection,
    activeStage,
    currentPhase,
    messages,
    canStop,
    runtimeState,
    loadingSessions,
    sessionHistoryError,
    serverSessionTotal,
    hasMoreSessions,
    createSession,
    isSessionDraft: isUnsavedDraft,
    renameSession,
    toggleSessionPin,
    deleteSession,
    deleteSessions,
    selectSession,
    loadMoreSessions,
    refreshSessions: () => loadSessionPage(true),
    selectTaskModel,
    refreshServerModel,
    setApprovalMode,
    execute,
    retryMessage,
    cancelCurrentRun,
    resolveApproval,
    clearTranscript,
    dispose
  }
}
