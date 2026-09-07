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
  getUsage,
  getSessionEvents,
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
const TERMINAL_STATUSES = new Set(['COMPLETED', 'FAILED', 'CANCELLED', 'WAITING'])
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
  return Boolean(session?.activeRunId || session?.submitting)
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
  const agentId = ref('main-agent')
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
  const streamAbortController = new AbortController()
  let disposed = false

  const currentSession = computed(() =>
    sessions.value.find((session) => session.id === currentSessionId.value) || null)
  const currentSessionDraft = computed(() => isUnsavedDraft(currentSession.value))
  const currentModel = computed(() =>
    models.value.find(model => model.key === selectedModelKey.value) || null)

  const messages = computed(() => currentSession.value?.messages || [])
  const busy = computed(() => isSessionBusy(currentSession.value))
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
    localStorage.setItem(storageKey, JSON.stringify(cache))
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
      const traces = await getSessionEvents(session.id)
      if (disposed) return
      const recovered = []
      const ordered = [...(Array.isArray(traces) ? traces : [])]
        .sort((left, right) => new Date(left.startedAt) - new Date(right.startedAt))
      for (const trace of ordered) {
        const events = Array.isArray(trace.events) ? trace.events : []
        const started = events.find(event => event.type === 'AGENT_STARTED')
        const completed = [...events].reverse().find(event => event.type === 'AGENT_COMPLETED')
        const failed = [...events].reverse().find(event => event.type === 'AGENT_FAILED')
        if (started?.message) {
          recovered.push({
            id: `${trace.invocationId}:user`,
            role: 'user',
            content: started.message,
            createdAt: started.timestamp || trace.startedAt
          })
        }
        recovered.push(...recoverOperationGroups(trace, events))
        if (completed?.message) {
          recovered.push({
            id: `${trace.invocationId}:assistant`,
            role: 'assistant',
            content: completed.message,
            createdAt: completed.timestamp || trace.endedAt
          })
        } else if (failed?.message) {
          recovered.push({
            id: `${trace.invocationId}:failed`,
            role: 'error',
            content: failed.message,
            createdAt: failed.timestamp || trace.endedAt
          })
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

  // 操作记录只从事件存储中的 TOOL_CALL_* 领域事件重建，与实时 SSE 事件同源，
  // 避免模型生成的文本描述进入执行记录。
  function recoverOperationGroups(trace, events) {
    const groups = []
    events.forEach((event, eventIndex) => {
      if (event.type !== 'TOOL_CALL_COMPLETED' && event.type !== 'TOOL_CALL_FAILED') return
      const data = event.data || {}
      const kind = classifyTool(data.toolName || '')
      appendOperationGroup(groups, {
        id: `${trace.invocationId}:ops:${eventIndex}`,
        kind,
        item: {
          toolName: data.toolName || '',
          arguments: data.arguments || {},
          summary: data.summary || event.message || '',
          success: event.type === 'TOOL_CALL_COMPLETED',
          status: event.type === 'TOOL_CALL_COMPLETED' ? 'COMPLETED' : 'FAILED'
        },
        createdAt: event.timestamp || trace.startedAt
      })
    })
    return groups
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

  function streamStage(eventName, data) {
    switch (eventName) {
      case 'tool_started':
      case 'tool_completed':
      case 'file_read':
      case 'file_edited':
      case 'command_executed':
        return 3
      case 'assistant_message':
      case 'final_answer':
      case 'error':
        return 5
      case 'progress': {
        const source = data?.sourceEvent
        if (source === 'run_started' || source === 'route_decided') return 1
        if (source === 'plan_created' || source === 'replan') return 2
        if (source === 'decision') return 5
        return null
      }
      default:
        return null
    }
  }

  // 文件读取、命令执行、文件修改等操作记录必须来源于 Runtime 真实工具事件，
  // 这里仅按 toolName 分类，绝不依据模型生成的文本描述。
  function classifyTool(toolName) {
    if (toolName === 'file_read') return 'read'
    if (toolName === 'file_write') return 'edit'
    if (toolName === 'run_command') return 'command'
    return 'tool'
  }

  function appendOperation(session, kind, item) {
    appendOperationGroup(session.messages, {
      id: randomId('ops'),
      kind,
      item,
      createdAt: nowIso()
    })
  }

  function applyOperationEvent(session, data, message = '') {
    const calls = Array.isArray(data?.toolCalls) && data.toolCalls.length
      ? data.toolCalls
      : [{ toolName: data?.toolName || '', arguments: data?.arguments || {} }]
    for (const call of calls) {
      const toolName = call.toolName || data?.toolName || 'tool'
      appendOperation(session, classifyTool(toolName), {
        toolName,
        arguments: call.arguments || {},
        summary: data?.summary || message,
        success: data?.success !== false,
        status: data?.status || ''
      })
    }
  }

  function appendAssistantDelta(session, runId, delta) {
    if (!delta) return
    const last = session.messages[session.messages.length - 1]
    if (last && last.role === 'assistant' && last.streaming) {
      last.content += delta
    } else {
      addMessage(session, 'assistant', delta, {
        id: `${runId}:assistant:${randomId('seg')}`,
        streaming: true
      })
    }
  }

  function finalizeAssistantAnswer(session, answer) {
    const streaming = session.messages.filter(
      message => message.role === 'assistant' && message.streaming)
    if (!streaming.length) {
      if (answer) addMessage(session, 'assistant', answer)
      return
    }
    const last = streaming[streaming.length - 1]
    if (answer && last.content !== answer) last.content = answer
    streaming.forEach(message => { message.streaming = false })
  }

  function handleChatEvent(session, runId, eventName, payload) {
    const data = payload?.data || {}
    if (eventName === 'assistant_message') {
      appendAssistantDelta(session, runId, payload?.message || '')
    } else if (eventName === 'tool_started') {
      session.phase = payload?.message
        || (data.toolName ? `正在执行 ${data.toolName}` : '正在执行工具')
    } else if (eventName === 'progress') {
      session.phase = payload?.message || ''
    } else if (eventName === 'error') {
      session.runError = payload?.message || session.runError
      session.phase = ''
    } else if (eventName === 'final_answer') {
      finalizeAssistantAnswer(session, payload?.message || '')
      session.phase = ''
    } else if (eventName === 'tool_completed'
      || eventName === 'file_read'
      || eventName === 'file_edited'
      || eventName === 'command_executed') {
      applyOperationEvent(session, data, payload?.message || '')
      session.phase = ''
    }
    session.updatedAt = nowIso()
  }

  function handleStreamEvent(session, packet, messageId = '') {
    const eventName = packet.event
    const payload = packet.data || {}
    const stage = streamStage(eventName, payload?.data)
    if (stage) session.activeStage = stage
    if (eventName === 'state') {
      session.activeStage = 5
      if (payload?.state) {
        session.state = payload.state
        if (payload.state.status === 'WAITING') setPendingApproval(session, payload)
      }
      return
    }
    handleChatEvent(session, packet.runId || session.activeRunId || 'run', eventName, payload)
  }

  function applyRunSnapshot(session, snapshot) {
    if (!snapshot?.state) return
    session.state = snapshot.state
    if (snapshot.state.status === 'WAITING' && snapshot.pendingAction) {
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

  async function refreshRunUsage(session, targetId, baseline) {
    if (!targetId) return
    try {
      const usage = await getUsage(session.id)
      const total = Number(usage?.totalTokens || 0)
      const tokenUsage = Number.isFinite(baseline)
        ? Math.max(0, total - baseline)
        : total
      const target = session.messages.find(message => message.id === targetId)
      if (target) {
        target.tokenUsage = tokenUsage
        target.tokenUsageScope = Number.isFinite(baseline) ? 'run' : 'session'
        persist()
      }
    } catch {
      // Token accounting must never change the run result.
    }
  }

  function finalizeRun(session, snapshot) {
    const runId = snapshot.runId || session.activeRunId
    const boundary = Number(session.runBoundary || 0)
    const task = session.runTask || ''
    const usageBaseline = Number.isFinite(session.runUsageBase)
      ? session.runUsageBase
      : null
    applyRunSnapshot(session, snapshot)

    if (snapshot.state.status === 'COMPLETED') {
      const output = snapshot.state.output || ''
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
    } else if (snapshot.state.status === 'CANCELLED') {
      markAssistantDone(session)
      addTerminalMessage(
        session, `${runId}:cancelled`, 'event', session.runError || '任务已取消。')
    } else if (snapshot.state.status === 'FAILED') {
      markAssistantDone(session)
      addTerminalMessage(
        session, `${runId}:failed`, 'error',
        session.runError || snapshot.state.error || 'Agent 未能完成任务。')
    }

    session.activeRunId = ''
    session.phase = ''
    if (snapshot.state.status === 'WAITING') {
      session.updatedAt = nowIso()
      persist()
      return
    }
    const resultMessage = markRunResult(
      session, snapshot.state.status, boundary, task, runId)
    session.runBoundary = 0
    session.runError = ''
    session.runTask = ''
    session.runUsageBase = null
    session.updatedAt = nowIso()
    persist()
    void refreshRunUsage(session, resultMessage?.id, usageBaseline)
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
    const envelope = packet.data || {}
    const sequence = Number(envelope.sequence || packet.id || 0)
    if (sequence && sequence <= Number(session.lastSequence || 0)) return
    if (sequence) session.lastSequence = sequence
    handleStreamEvent(session, {
      event: envelope.type || packet.event,
      data: envelope.data,
      runId
    })
    persist()
  }

  async function recoverMissingRun(session, runId) {
    session.activeRunId = ''
    try {
      const state = await getAgentState(session.id)
      if (state) {
        finalizeRun(session, {
          runId,
          state,
          lastSequence: session.lastSequence || 0
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
          if (TERMINAL_STATUSES.has(snapshot.state.status)
              && Number(session.lastSequence || 0) >= Number(snapshot.lastSequence || 0)) {
            finalizeRun(session, snapshot)
            return snapshot
          }

          const finalSnapshot = await streamAgentRun(
            runId,
            session.lastSequence || 0,
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
    const normalizedAgentId = sanitizeId(agentId.value, 'main-agent')
    const session = ensureSession(normalizedSessionId, task)
    sessionId.value = normalizedSessionId
    agentId.value = normalizedAgentId
    addMessage(session, 'user', task)
    // 本轮运行的操作分组从用户消息之后开始，避免与历史轮次合并
    session.runBoundary = session.messages.length
    session.runError = ''
    session.runTask = task
    session.runUsageBase = null
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
      try {
        const usage = await getUsage(normalizedSessionId)
        session.runUsageBase = Number(usage?.totalTokens || 0)
      } catch {
        session.runUsageBase = null
      }
      const attributes = {
        source: 'agentos-console',
        approvalMode: approvalMode.value
      }
      attributes.modelId = currentModel.value.id
      if (workspaceContext?.name) {
        attributes.workspaceName = workspaceContext.name
        attributes.workspaceContextFiles = workspaceContext.files?.length || 0
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
      session.lastSequence = 0
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
      const target = session.messages[session.messages.length - 1]
      void refreshRunUsage(session, target.id, session.runUsageBase)
      session.runBoundary = 0
      session.runTask = ''
      session.runUsageBase = null
    } finally {
      session.submitting = false
      if (!session.activeRunId) stopPipeline(session)
      persist()
    }
  }

  async function resolveApproval(messageId, approved) {
    if (busy.value || !currentSession.value) return
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
      session.activeRunId = ''
      session.state = response.state
      if (response.state.status === 'COMPLETED') {
        addMessage(session, 'assistant', response.state.output || '任务已完成。')
      } else if (response.state.status === 'WAITING') {
        setPendingApproval(session, response)
      } else if (!approved && response.state.error === 'human approval rejected') {
        addMessage(session, 'event', '操作已拒绝，任务已停止。')
      } else {
        addMessage(session, 'error', response.state.error || 'Agent 未能完成任务。')
      }
      if (response.state.status !== 'WAITING') {
        const result = markRunResult(
          session,
          response.state.status,
          Number(session.runBoundary || 0),
          session.runTask || '',
          response.invocationId || '')
        void refreshRunUsage(session, result?.id, session.runUsageBase)
        session.runBoundary = 0
        session.runTask = ''
        session.runUsageBase = null
      }
    } catch (error) {
      connection.value = error instanceof TypeError ? 'offline' : 'online'
      addMessage(session, 'error', error.message || '处理审批操作失败')
    } finally {
      session.submitting = false
      stopPipeline(session)
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
      if (TERMINAL_STATUSES.has(result.run?.state?.status)) {
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
