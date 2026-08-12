import { computed, onMounted, ref } from 'vue'
import {
  getAgentState,
  getPendingAction,
  resolvePendingAction,
  runAgentStream
} from '../services/agentApi.js'

const STORAGE_KEY = 'agentos.console.sessions.v1'

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

  const currentSession = computed(() =>
    sessions.value.find((session) => session.id === currentSessionId.value) || null)

  const messages = computed(() => currentSession.value?.messages || [])
  const runtimeState = computed(() => currentSession.value?.state || {
    status: 'READY',
    iteration: 0,
    output: '',
    error: '',
    updatedAt: null
  })

  function persist() {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(sessions.value.slice(0, 20)))
  }

  function activateSession(session) {
    currentSessionId.value = session.id
    sessionId.value = session.id
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

  function handleStreamEvent(session, packet) {
    activeStage.value = streamStage(packet.event)
    const content = eventMessage(packet.event, packet.data)
    if (content) addMessage(session, 'event', content)
    if (packet.event === 'state' && packet.data?.state) {
      session.state = packet.data.state
      if (packet.data.state.status === 'WAITING') setPendingApproval(session, packet.data)
    }
    persist()
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
          mode: argumentsSummary.mode || 'CREATE_NEW',
          createParentDirectories: Boolean(argumentsSummary.createParentDirectories)
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
      const response = await runAgentStream({
        agentId: normalizedAgentId,
        sessionId: normalizedSessionId,
        input: task,
        attributes: { source: 'agentos-console' }
      }, (event) => handleStreamEvent(session, event))
      connection.value = 'online'
      session.state = response.state
      if (response.state.status === 'COMPLETED') {
        addMessage(session, 'assistant', response.state.output || '任务已完成。')
      } else if (response.state.status === 'WAITING') {
        setPendingApproval(session, response)
      } else if (response.state.status === 'CANCELLED') {
        addMessage(session, 'event', response.state.error || '任务已取消。')
      } else {
        addMessage(session, 'error', response.state.error || 'Agent 未能完成任务。')
      }
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
      stopPipeline()
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
      void selectSession(sessions.value[0].id)
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
    runtimeState,
    createSession,
    renameSession,
    deleteSession,
    selectSession,
    execute,
    resolveApproval,
    clearTranscript
  }
}
