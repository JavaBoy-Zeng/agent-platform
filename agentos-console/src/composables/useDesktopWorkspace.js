import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { getAuthToken, getAuthUser, getServerUrl } from '../services/apiConfig.js'
import { invokeDesktop, isDesktop } from '../services/desktopApi.js'

const SESSION_WORKSPACE_KEY = 'agentos.session-workspaces.v1'

function readAssociations() {
  try { return JSON.parse(localStorage.getItem(SESSION_WORKSPACE_KEY) || '{}') } catch { return {} }
}

export function useDesktopWorkspace(agentConsole) {
  const desktop = isDesktop()
  const grant = ref(null)
  const workspaces = ref([])
  const associations = ref(readAssociations())
  const authorizing = ref(false)
  const picking = ref(false)
  const error = ref('')
  const inspectorMode = ref('')
  const terminalOpen = ref(false)
  let refreshTimer

  const authUser = ref(getAuthUser())
  const hasRole = computed(() => authUser.value?.roles?.includes('WORKSPACE'))
  const currentSessionId = computed(() => agentConsole.currentSessionId.value)
  const currentWorkspaceId = computed(() => associations.value[currentSessionId.value] || '')
  const currentWorkspace = computed(() =>
    workspaces.value.find(item => item.id === currentWorkspaceId.value) || null)
  const available = computed(() => desktop && hasRole.value)

  function serverUrl() {
    return getServerUrl() || import.meta.env?.VITE_API_BASE_URL || 'http://localhost:8080'
  }

  async function authorize(force = false) {
    if (!available.value) return null
    if (authorizing.value) return grant.value
    if (!force && grant.value && grant.value.expiresAt > Date.now() + 10_000) return grant.value
    authorizing.value = true
    error.value = ''
    try {
      grant.value = await invokeDesktop('authorize_workspace', {
        serverUrl: serverUrl(),
        token: getAuthToken()
      })
      await refreshWorkspaces()
      return grant.value
    } catch (cause) {
      error.value = String(cause)
      grant.value = null
      return null
    } finally {
      authorizing.value = false
    }
  }

  async function ensureGrant() {
    const current = await authorize()
    if (!current) throw new Error(error.value || '无法获得本机工作区授权')
    return current.grantId
  }

  async function refreshWorkspaces() {
    if (!grant.value) return
    workspaces.value = await invokeDesktop('list_workspaces', { grantId: grant.value.grantId })
  }

  function bindWorkspace(workspaceId) {
    if (!currentSessionId.value) return
    associations.value = { ...associations.value, [currentSessionId.value]: workspaceId }
    error.value = ''
    try { localStorage.setItem(SESSION_WORKSPACE_KEY, JSON.stringify(associations.value)) } catch { /* local only */ }
  }

  function clearWorkspace() {
    if (!currentSessionId.value) return
    const next = { ...associations.value }
    delete next[currentSessionId.value]
    associations.value = next
    error.value = ''
    try { localStorage.setItem(SESSION_WORKSPACE_KEY, JSON.stringify(next)) } catch { /* local only */ }
  }

  async function pickWorkspace() {
    if (picking.value) return null
    picking.value = true
    error.value = ''
    try {
      const grantId = await ensureGrant()
      const workspace = await invokeDesktop('pick_workspace', { grantId })
      if (workspace) {
        await refreshWorkspaces()
        bindWorkspace(workspace.id)
      }
      return workspace
    } catch (cause) {
      error.value = String(cause).replace(/^Error:\s*/, '')
      return null
    } finally {
      picking.value = false
    }
  }

  async function uploadAttachments() {
    if (!currentWorkspace.value) {
      const picked = await pickWorkspace()
      if (!picked) return []
    }
    return call('upload_attachments')
  }

  async function forgetWorkspace(workspaceId) {
    const grantId = await ensureGrant()
    await invokeDesktop('forget_workspace', { grantId, workspaceId })
    const next = { ...associations.value }
    Object.keys(next).forEach(key => { if (next[key] === workspaceId) delete next[key] })
    associations.value = next
    try { localStorage.setItem(SESSION_WORKSPACE_KEY, JSON.stringify(next)) } catch { /* local only */ }
    await refreshWorkspaces()
  }

  async function call(command, payload = {}) {
    const grantId = await ensureGrant()
    if (!currentWorkspace.value) throw new Error('请先为当前任务选择工作区')
    return invokeDesktop(command, {
      grantId,
      workspaceId: currentWorkspace.value.id,
      ...payload
    })
  }

  async function dispose() {
    clearInterval(refreshTimer)
    const active = grant.value
    grant.value = null
    inspectorMode.value = ''
    terminalOpen.value = false
    if (active) {
      try { await invokeDesktop('revoke_workspace', { grantId: active.grantId }) } catch { /* native cleanup is idempotent */ }
    }
  }

  function syncAuthorization(event) {
    authUser.value = event?.detail ?? getAuthUser()
    clearInterval(refreshTimer)
    refreshTimer = undefined
    if (available.value) {
      authorize(true)
      refreshTimer = window.setInterval(() => authorize(true), 40_000)
    } else {
      dispose()
    }
  }

  onMounted(() => {
    window.addEventListener('agentos:auth-changed', syncAuthorization)
    syncAuthorization()
  })

  watch(currentSessionId, () => {
    inspectorMode.value = ''
    terminalOpen.value = false
  })
  onUnmounted(() => {
    window.removeEventListener('agentos:auth-changed', syncAuthorization)
    dispose()
  })

  return {
    desktop,
    available,
    hasRole,
    grant,
    authorizing,
    picking,
    error,
    workspaces,
    currentWorkspace,
    inspectorMode,
    terminalOpen,
    authorize,
    bindWorkspace,
    clearWorkspace,
    pickWorkspace,
    uploadAttachments,
    forgetWorkspace,
    refreshWorkspaces,
    call,
    dispose
  }
}
