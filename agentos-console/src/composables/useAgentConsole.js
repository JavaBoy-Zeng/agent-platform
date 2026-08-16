import { computed, onMounted, ref } from 'vue'
import {
  cancelAgentRun,
  createAgentRun,
  getAgentState,
  getAgentRun,
  getPendingAction,
  resolvePendingAction,
  streamAgentRun
} from '../services/agentApi.js'

const STORAGE_KEY = 'agentos.console.sessions.v1'
const ACTIVE_SESSION_KEY = 'agentos.console.active-session.v1'
const TERMINAL_STATUSES = new Set(['COMPLETED', 'FAILED', 'CANCELLED', 'WAITING'])

function randomId(prefix) {
  const token = globalThis.crypto?.randomUUID?.().slice(0, 8)
    || Math.random().toString(36).slice(2, 10)
  return `${prefix}-${token}`
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
    state: null,
    messages: []
  }
}

export function useAgentConsole() {
  const sessions = ref([])
  const currentSessionId = ref('')
  const agentId = ref('main-agent')
  const sessionId = ref('')
  const prompt = ref('')
  const busy = ref(false)
  const connection = ref('standby')
  const activeStage = ref(0)
  const monitoredRuns = new Map()

  const currentSession = computed(() =>
    sessions.value.find((session) => session.id === currentSessionId.value) || null)

  const messages = computed(() => currentSession.value?.messages || [])
  const canStop = computed(() => Boolean(currentSession.value?.activeRunId))
  const runtimeState = computed(() => currentSession.value?.state || {
    status: 'READY',
    iteration: 0,
    output: '',
    error: '',
    updatedAt: null
  })

  function persist() {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(sessions.value.slice(0, 20)))
    if (currentSessionId.value) {
      localStorage.setItem(ACTIVE_SESSION_KEY, currentSessionId.value)
    }
  }

  function activateSession(session) {
    currentSessionId.value = session.id
    sessionId.value = session.id
    localStorage.setItem(ACTIVE_SESSION_KEY, session.id)
  }

  function createSession() {
    const session = newSession()
    sessions.value.unshift(session)
    activateSession(session)
    prompt.value = ''
    persist()
    return session
  }

  function renameSession(id, title) {
    const session = sessions.value.find((item) => item.id === id)
    const normalizedTitle = String(title || '').trim()
    if (!session || !normalizedTitle) return
    session.title = normalizedTitle
    session.updatedAt = nowIso()
    persist()
  }

  function deleteSession(id) {
    const index = sessions.value.findIndex((item) => item.id === id)
    if (index < 0 || (busy.value && id === currentSessionId.value)) return

    const deletingCurrentSession = id === currentSessionId.value
    sessions.value.splice(index, 1)

    if (deletingCurrentSession) {
      const nextSession = sessions.value[Math.min(index, sessions.value.length - 1)]
      if (nextSession) {
        activateSession(nextSession)
        prompt.value = ''
      } else {
        createSession()
        return
      }
    }

    persist()
  }

  async function selectSession(id) {
    const session = sessions.value.find((item) => item.id === id)
    if (!session) return
    activateSession(session)

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
    if (session.title === '未命名任务') {
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

  function startPipeline() {
    activeStage.value = 1
  }

  function stopPipeline() {
    activeStage.value = 5
    window.setTimeout(() => {
      activeStage.value = 0
    }, 700)
  }

  function streamStage(event) {
    return {
      run_started: 1,
      plan_created: 2,
      tool_started: 3,
      tool_finished: 3,
      observation: 4,
      decision: 5,
      replan: 2,
      run_completed: 5,
      run_failed: 5
    }[event] || activeStage.value
  }

  function eventMessage(event, data) {
    const details = data?.data || {}
    if (event === 'plan_created' && details.outcome !== 'COMPLETE') {
      return `PLAN / ${details.type || ''} / ${data.message || ''}`
    }
    if (event === 'tool_started') {
      return `TOOL / ${details.toolName || ''} / ${data.message || ''}`
    }
    if (event === 'observation') {
      return `OBSERVATION / ${details.toolName || ''}\n${data.message || '(empty result)'}`
    }
    if (event === 'decision') {
      if (details.pendingActionId) return ''
      return `DECISION / ${details.outcome || ''} / ${data.message || ''}`
    }
    if (event === 'replan') {
      return `REPLAN ${details.replanCount || ''} / ${data.message || ''}`
    }
    return ''
  }

  function handleStreamEvent(session, packet, messageId = '') {
    activeStage.value = streamStage(packet.event)
    if (packet.event === 'output_delta') {
      const runId = session.activeRunId || 'run'
      const assistantId = `${runId}:assistant`
      let message = session.messages.find(item => item.id === assistantId)
      if (!message) {
        addMessage(session, 'assistant', '', { id: assistantId, streaming: true })
        message = session.messages.find(item => item.id === assistantId)
      }
      message.content += packet.data?.message || ''
      message.streaming = true
      session.updatedAt = nowIso()
      persist()
      return
    }
    const content = eventMessage(packet.event, packet.data)
    if (content && (!messageId || !session.messages.some(message => message.id === messageId))) {
      addMessage(session, 'event', content, messageId ? { id: messageId } : {})
    }
    if (packet.event === 'state' && packet.data?.state) {
      session.state = packet.data.state
      if (packet.data.state.status === 'WAITING') setPendingApproval(session, packet.data)
    }
    persist()
  }

  function applyRunSnapshot(session, snapshot) {
    if (!snapshot?.state) return
    session.state = snapshot.state
    if (snapshot.state.status === 'WAITING' && snapshot.pendingAction) {
      setPendingApproval(session, snapshot)
    }
    persist()
  }

  function finalizeRun(session, snapshot) {
    const runId = snapshot.runId || session.activeRunId
    applyRunSnapshot(session, snapshot)
    const assistantId = `${runId}:assistant`
    const existingAssistant = session.messages.find(message => message.id === assistantId)

    if (snapshot.state.status === 'COMPLETED') {
      if (existingAssistant) {
        existingAssistant.content = snapshot.state.output || '任务已完成。'
        existingAssistant.streaming = false
      } else {
        addMessage(session, 'assistant', snapshot.state.output || '任务已完成。', {
          id: assistantId
        })
      }
    } else if (snapshot.state.status === 'CANCELLED') {
      addTerminalMessage(
        session, `${runId}:cancelled`, 'event', snapshot.state.error || '任务已取消。')
    } else if (snapshot.state.status === 'FAILED') {
      addTerminalMessage(
        session, `${runId}:failed`, 'error', snapshot.state.error || 'Agent 未能完成任务。')
    }

    session.activeRunId = ''
    session.updatedAt = nowIso()
    persist()
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
      data: envelope.data
    }, sequence ? `${runId}:${sequence}` : '')
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
      busy.value = true
      startPipeline()
      let retryCount = 0
      while (session.activeRunId === runId) {
        try {
          const snapshot = await getAgentRun(runId)
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
            packet => handleBackgroundEvent(session, runId, packet)
          )
          connection.value = 'online'
          finalizeRun(session, finalSnapshot)
          return finalSnapshot
        } catch (error) {
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
      if (currentSessionId.value === session.id) {
        busy.value = false
        stopPipeline()
      }
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

  async function execute() {
    const task = prompt.value.trim()
    if (!task || busy.value) return

    const normalizedSessionId = sanitizeId(sessionId.value, randomId('session'))
    const normalizedAgentId = sanitizeId(agentId.value, 'main-agent')
    const session = ensureSession(normalizedSessionId, task)
    sessionId.value = normalizedSessionId
    agentId.value = normalizedAgentId
    addMessage(session, 'user', task)
    session.state = {
      status: 'RUNNING',
      iteration: (session.state?.iteration || 0) + 1,
      output: '',
      error: '',
      updatedAt: nowIso()
    }
    prompt.value = ''
    busy.value = true
    startPipeline()
    persist()

    try {
      const run = await createAgentRun({
        agentId: normalizedAgentId,
        sessionId: normalizedSessionId,
        input: task,
        attributes: { source: 'agentos-console' }
      })
      connection.value = 'online'
      session.activeRunId = run.runId
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
      addMessage(session, 'error', message)
    } finally {
      busy.value = false
      if (!session.activeRunId) stopPipeline()
      persist()
    }
  }

  async function resolveApproval(messageId, approved) {
    if (busy.value || !currentSession.value) return
    const session = currentSession.value
    const message = session.messages.find(item => item.id === messageId)
    if (!message || message.role !== 'approval' || message.resolved) return
    busy.value = true
    startPipeline()
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
    } catch (error) {
      connection.value = error instanceof TypeError ? 'offline' : 'online'
      addMessage(session, 'error', error.message || '处理审批操作失败')
    } finally {
      busy.value = false
      stopPipeline()
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

  onMounted(() => {
    try {
      const stored = JSON.parse(localStorage.getItem(STORAGE_KEY) || '[]')
      sessions.value = Array.isArray(stored) ? stored : []
    } catch {
      sessions.value = []
    }

    if (sessions.value.length) {
      const activeSessionId = localStorage.getItem(ACTIVE_SESSION_KEY)
      const initial = sessions.value.find(session => session.id === activeSessionId)
        || sessions.value[0]
      void selectSession(initial.id)
    } else {
      createSession()
    }
  })

  return {
    sessions,
    currentSessionId,
    agentId,
    sessionId,
    prompt,
    busy,
    connection,
    activeStage,
    messages,
    canStop,
    runtimeState,
    createSession,
    renameSession,
    deleteSession,
    selectSession,
    execute,
    cancelCurrentRun,
    resolveApproval,
    clearTranscript
  }
}
