import { computed, onMounted, onUnmounted, ref } from 'vue'
import { getAuthToken } from '../services/apiConfig.js'
import {
  claimAutomationExecution,
  failAutomationExecution,
  getAutomation,
  heartbeatAutomationDesktop,
  startAutomationExecution
} from '../services/automationApi.js'
import { invokeDesktop, isDesktop } from '../services/desktopApi.js'

const HEARTBEAT_MS = 15_000
const CLAIM_MS = 10_000

function mentionedPaths(prompt) {
  const paths = []
  const matcher = /(?:^|\s)@([^\s@]+)/g
  let match
  while ((match = matcher.exec(String(prompt || '')))) paths.push(match[1])
  return [...new Set(paths)].slice(0, 20)
}

export function useAutomationDesktop(desktopWorkspace) {
  const desktop = isDesktop()
  const runtime = ref(null)
  const online = ref(false)
  const busy = ref(false)
  const error = ref('')
  let heartbeatTimer
  let claimTimer

  const clientId = computed(() => runtime.value?.clientId || '')
  const keepAwake = computed(() => Boolean(runtime.value?.keepAwake))
  const keepAwakeSupported = computed(() => Boolean(runtime.value?.keepAwakeSupported))

  async function loadRuntime() {
    if (!desktop) return null
    runtime.value = await invokeDesktop('automation_runtime_info')
    return runtime.value
  }

  async function heartbeat() {
    if (!desktop || !getAuthToken()) return
    try {
      const info = runtime.value || await loadRuntime()
      await heartbeatAutomationDesktop({
        clientId: info.clientId,
        platform: info.platform,
        appVersion: info.appVersion
      })
      online.value = true
      error.value = ''
    } catch (cause) {
      online.value = false
      error.value = cause?.message || String(cause)
    }
  }

  async function claim() {
    if (!desktop || !online.value || busy.value || !clientId.value) return
    busy.value = true
    try {
      const execution = await claimAutomationExecution(clientId.value)
      if (!execution) return
      try {
        const task = await getAutomation(execution.automationId)
        const context = await desktopWorkspace.buildAutomationContext(
          execution.workspaceId,
          mentionedPaths(task.prompt)
        )
        await startAutomationExecution(execution.executionId, {
          clientId: clientId.value,
          workspaceContext: context
        })
        window.dispatchEvent(new CustomEvent('agentos:automation-updated'))
      } catch (cause) {
        await failAutomationExecution(execution.executionId, {
          clientId: clientId.value,
          reason: cause?.message || String(cause)
        }).catch(() => {})
        window.dispatchEvent(new CustomEvent('agentos:automation-updated'))
      }
    } catch (cause) {
      error.value = cause?.message || String(cause)
    } finally {
      busy.value = false
    }
  }

  async function setKeepAwake(enabled) {
    if (!desktop) throw new Error('保持电脑唤醒仅在 AgentOS Desktop 中可用')
    const previous = runtime.value
    try {
      runtime.value = await invokeDesktop('set_automation_keep_awake', { enabled: Boolean(enabled) })
      error.value = ''
      return runtime.value
    } catch (cause) {
      runtime.value = previous
      error.value = cause?.message || String(cause)
      throw cause
    }
  }

  function startTimers() {
    clearInterval(heartbeatTimer)
    clearInterval(claimTimer)
    heartbeatTimer = window.setInterval(heartbeat, HEARTBEAT_MS)
    claimTimer = window.setInterval(claim, CLAIM_MS)
  }

  async function initialize() {
    if (!desktop) return
    try {
      await loadRuntime()
      await heartbeat()
      await claim()
      startTimers()
    } catch (cause) {
      error.value = cause?.message || String(cause)
    }
  }

  function dispose() {
    clearInterval(heartbeatTimer)
    clearInterval(claimTimer)
  }

  onMounted(initialize)
  onUnmounted(dispose)

  return {
    desktop,
    runtime,
    clientId,
    online,
    busy,
    error,
    keepAwake,
    keepAwakeSupported,
    heartbeat,
    claim,
    setKeepAwake,
    initialize,
    dispose
  }
}
