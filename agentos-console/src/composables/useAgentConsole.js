import { computed, onMounted, ref } from 'vue'
import { getAgentState, runAgent } from '../services/agentApi.js'

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
  let pipelineTimer

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

  async function selectSession(id) {
    const session = sessions.value.find((item) => item.id === id)
    if (!session) return
    activateSession(session)

    try {
      const state = await getAgentState(id)
      connection.value = 'online'
      if (state) {
        session.state = state
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

  function addMessage(session, role, content) {
    session.messages.push({
      id: randomId('message'),
      role,
      content,
      createdAt: nowIso()
    })
    session.updatedAt = nowIso()
  }

  function startPipeline() {
    window.clearInterval(pipelineTimer)
    activeStage.value = 1
    pipelineTimer = window.setInterval(() => {
      activeStage.value = Math.min(activeStage.value + 1, 4)
    }, 420)
  }

  function stopPipeline() {
    window.clearInterval(pipelineTimer)
    activeStage.value = 4
    window.setTimeout(() => {
      activeStage.value = 0
    }, 700)
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
      const response = await runAgent({
        agentId: normalizedAgentId,
        sessionId: normalizedSessionId,
        input: task,
        attributes: { source: 'agentos-console' }
      })
      connection.value = 'online'
      session.state = response.state
      if (response.state.status === 'COMPLETED') {
        addMessage(session, 'assistant', response.state.output || '任务已完成。')
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
      activateSession(sessions.value[0])
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
    selectSession,
    execute,
    clearTranscript
  }
}
