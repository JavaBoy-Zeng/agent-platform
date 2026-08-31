import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { getAuthToken, getAuthUser, getServerUrl } from '../services/apiConfig.js'
import { invokeDesktop, isDesktop } from '../services/desktopApi.js'

const SESSION_WORKSPACES_KEY = 'agentos.session-workspaces.v2'
const LEGACY_SESSION_WORKSPACE_KEY = 'agentos.session-workspaces.v1'

export const DEFAULT_WORKSPACE_POLICY = Object.freeze({
  maxDirectoriesPerTask: 1,
  lockAfterFirstBinding: true
})

function normalizeWorkspaceIds(value) {
  const candidates = Array.isArray(value) ? value : value ? [value] : []
  return [...new Set(candidates.map(item => String(item || '').trim()).filter(Boolean))]
}

function normalizeAssociations(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return {}
  return Object.fromEntries(Object.entries(value)
    .map(([sessionId, workspaceIds]) => [String(sessionId), normalizeWorkspaceIds(workspaceIds)])
    .filter(([sessionId, workspaceIds]) => sessionId && workspaceIds.length))
}

function persistAssociations(associations) {
  try {
    localStorage.setItem(SESSION_WORKSPACES_KEY, JSON.stringify(associations))
    localStorage.removeItem(LEGACY_SESSION_WORKSPACE_KEY)
  } catch { /* local only */ }
}

function readAssociations() {
  try {
    const stored = localStorage.getItem(SESSION_WORKSPACES_KEY)
    if (stored !== null) return normalizeAssociations(JSON.parse(stored))

    const legacy = localStorage.getItem(LEGACY_SESSION_WORKSPACE_KEY)
    if (legacy === null) return {}
    const migrated = normalizeAssociations(JSON.parse(legacy))
    persistAssociations(migrated)
    return migrated
  } catch {
    return {}
  }
}

export function useDesktopWorkspace(agentConsole, options = {}) {
  const requestedMaxDirectories = Number(options.workspacePolicy?.maxDirectoriesPerTask)
  const workspacePolicy = Object.freeze({
    maxDirectoriesPerTask: Number.isFinite(requestedMaxDirectories) && requestedMaxDirectories >= 1
      ? Math.floor(requestedMaxDirectories)
      : DEFAULT_WORKSPACE_POLICY.maxDirectoriesPerTask,
    lockAfterFirstBinding: options.workspacePolicy?.lockAfterFirstBinding
      ?? DEFAULT_WORKSPACE_POLICY.lockAfterFirstBinding
  })
  const desktop = isDesktop()
  const grant = ref(null)
  const workspaces = ref([])
  const associations = ref(readAssociations())
  const authorizing = ref(false)
  const picking = ref(false)
  const contextLoading = ref(false)
  const fileIndexLoading = ref(false)
  const workspaceFiles = ref([])
  const gitBranches = ref([])
  const gitBranchLoading = ref(false)
  const gitBranchError = ref('')
  const error = ref('')
  const inspectorMode = ref('')
  const terminalOpen = ref(false)
  let refreshTimer
  let fileIndexRequest = 0

  const authUser = ref(getAuthUser())
  // ADMIN 是系统超级管理员；桌面端本机能力不应因旧管理员账户缺少后来新增的
  // WORKSPACE 角色而被整块隐藏。普通用户仍必须显式授予 WORKSPACE。
  const hasRole = computed(() => authUser.value?.roles?.some(
    role => role === 'WORKSPACE' || role === 'ADMIN'))
  const currentSessionId = computed(() => agentConsole.currentSessionId.value)
  const currentWorkspaceIds = computed(() => workspaceIdsForSession(currentSessionId.value))
  const currentWorkspaces = computed(() => workspacesForSession(currentSessionId.value))
  // 当前产品只启用一个锁定工作区；保留首项兼容现有终端、Git 和文件上下文调用。
  const currentWorkspaceId = computed(() => currentWorkspaceIds.value[0] || '')
  const currentWorkspace = computed(() => currentWorkspaces.value[0] || null)
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
    if (!currentSessionId.value || !workspaceId) return false
    const normalizedWorkspaceId = String(workspaceId)
    const existing = workspaceIdsForSession(currentSessionId.value)
    if (existing.includes(normalizedWorkspaceId)) return true
    // 尚未发送首条消息的草稿可以在目录入口之间切换；正式任务仍保持首次绑定后锁定。
    if (agentConsole.currentSessionDraft?.value && existing.length) {
      associations.value = {
        ...associations.value,
        [currentSessionId.value]: [normalizedWorkspaceId]
      }
      error.value = ''
      persistAssociations(associations.value)
      return true
    }
    if (workspacePolicy.lockAfterFirstBinding && existing.length) {
      error.value = '当前任务目录已锁定，不能修改'
      return false
    }
    if (existing.length >= workspacePolicy.maxDirectoriesPerTask) {
      error.value = `当前任务最多绑定 ${workspacePolicy.maxDirectoriesPerTask} 个目录`
      return false
    }
    associations.value = {
      ...associations.value,
      [currentSessionId.value]: [...existing, normalizedWorkspaceId]
    }
    error.value = ''
    persistAssociations(associations.value)
    return true
  }

  function workspaceIdsForSession(sessionId) {
    return [...(associations.value[String(sessionId || '')] || [])]
  }

  function workspacesForSession(sessionId) {
    return workspaceIdsForSession(sessionId)
      .map(workspaceId => workspaces.value.find(item => item.id === workspaceId))
      .filter(Boolean)
  }

  function workspaceForSession(sessionId) {
    return workspacesForSession(sessionId)[0] || null
  }

  function unbindWorkspace(workspaceId, sessionId = currentSessionId.value) {
    const normalizedSessionId = String(sessionId || '')
    const normalizedWorkspaceId = String(workspaceId || '')
    const existing = workspaceIdsForSession(normalizedSessionId)
    if (!normalizedSessionId || !normalizedWorkspaceId || !existing.includes(normalizedWorkspaceId)) return true
    if (workspacePolicy.lockAfterFirstBinding) {
      error.value = '当前任务目录已锁定，不能修改'
      return false
    }
    const remaining = existing.filter(id => id !== normalizedWorkspaceId)
    const next = { ...associations.value }
    if (remaining.length) next[normalizedSessionId] = remaining
    else delete next[normalizedSessionId]
    associations.value = next
    error.value = ''
    persistAssociations(next)
    return true
  }

  function clearWorkspace() {
    if (!currentSessionId.value || !currentWorkspaceIds.value.length) return true
    if (workspacePolicy.lockAfterFirstBinding && !agentConsole.currentSessionDraft?.value) {
      error.value = '当前任务目录已锁定，不能修改'
      return false
    }
    const next = { ...associations.value }
    delete next[currentSessionId.value]
    associations.value = next
    error.value = ''
    persistAssociations(next)
    return true
  }

  async function pickWorkspace({ bind = true } = {}) {
    if (picking.value) return null
    picking.value = true
    error.value = ''
    try {
      const grantId = await ensureGrant()
      const workspace = await invokeDesktop('pick_workspace', { grantId })
      if (workspace) {
        await refreshWorkspaces()
        if (bind) bindWorkspace(workspace.id)
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

  async function refreshFileIndex() {
    const request = ++fileIndexRequest
    const workspace = currentWorkspace.value
    workspaceFiles.value = []
    if (!workspace) {
      fileIndexLoading.value = false
      return
    }
    fileIndexLoading.value = true
    try {
      const grantId = await ensureGrant()
      const files = await invokeDesktop('workspace_file_index', {
        grantId,
        workspaceId: workspace.id
      })
      if (request === fileIndexRequest) workspaceFiles.value = files
    } catch (cause) {
      if (request === fileIndexRequest) {
        error.value = String(cause).replace(/^Error:\s*/, '') || '无法读取项目文件列表'
      }
    } finally {
      if (request === fileIndexRequest) fileIndexLoading.value = false
    }
  }

  async function buildRunContext(mentionedPaths = []) {
    if (!currentWorkspace.value) return null
    if (contextLoading.value) throw new Error('正在读取本地项目上下文')
    contextLoading.value = true
    error.value = ''
    try {
      return await call('workspace_context', { mentionedPaths })
    } catch (cause) {
      error.value = String(cause).replace(/^Error:\s*/, '') || '无法读取本地项目上下文'
      throw cause
    } finally {
      contextLoading.value = false
    }
  }

  /** 为自动化任务按其固定工作区读取最新上下文，不改变当前 Chat 会话绑定。 */
  async function buildAutomationContext(workspaceId, mentionedPaths = []) {
    const normalized = String(workspaceId || '')
    if (!normalized) throw new Error('自动化任务未绑定工作区')
    const grantId = await ensureGrant()
    const workspace = workspaces.value.find(item => item.id === normalized)
    if (!workspace) throw new Error('自动化任务绑定的工作区已被移除或目录已失效')
    return invokeDesktop('workspace_context', {
      grantId,
      workspaceId: normalized,
      mentionedPaths
    })
  }

  async function automationFileIndex(workspaceId) {
    const normalized = String(workspaceId || '')
    if (!normalized) return []
    const grantId = await ensureGrant()
    if (!workspaces.value.some(item => item.id === normalized)) return []
    return invokeDesktop('workspace_file_index', { grantId, workspaceId: normalized })
  }

  async function refreshGitBranches() {
    const workspace = currentWorkspace.value
    gitBranches.value = []
    gitBranchError.value = ''
    if (!workspace?.gitRepository) return []
    gitBranchLoading.value = true
    try {
      const branches = await call('git_branches')
      gitBranches.value = Array.isArray(branches) ? branches : []
      return gitBranches.value
    } catch (cause) {
      gitBranchError.value = String(cause).replace(/^Error:\s*/, '') || '无法读取 Git 分支'
      return []
    } finally {
      gitBranchLoading.value = false
    }
  }

  async function switchGitBranch(branch) {
    if (!currentWorkspace.value?.gitRepository || gitBranchLoading.value) return false
    const target = String(branch || '').trim()
    if (!target) return false
    gitBranchLoading.value = true
    gitBranchError.value = ''
    try {
      await call('git_switch_branch', { branch: target })
      await refreshGitBranches()
      return true
    } catch (cause) {
      gitBranchError.value = String(cause).replace(/^Error:\s*/, '') || '无法切换 Git 分支'
      return false
    } finally {
      gitBranchLoading.value = false
    }
  }

  async function forgetWorkspace(workspaceId) {
    const grantId = await ensureGrant()
    await invokeDesktop('forget_workspace', { grantId, workspaceId })
    const next = { ...associations.value }
    Object.keys(next).forEach(key => {
      const remaining = normalizeWorkspaceIds(next[key]).filter(id => id !== workspaceId)
      if (remaining.length) next[key] = remaining
      else delete next[key]
    })
    associations.value = next
    persistAssociations(next)
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
  watch(currentWorkspace, () => {
    refreshFileIndex()
    refreshGitBranches()
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
    contextLoading,
    fileIndexLoading,
    error,
    workspaces,
    workspacePolicy,
    currentWorkspaceIds,
    currentWorkspaces,
    currentWorkspace,
    workspaceIdsForSession,
    workspacesForSession,
    workspaceForSession,
    workspaceFiles,
    gitBranches,
    gitBranchLoading,
    gitBranchError,
    inspectorMode,
    terminalOpen,
    authorize,
    bindWorkspace,
    unbindWorkspace,
    clearWorkspace,
    pickWorkspace,
    uploadAttachments,
    buildRunContext,
    buildAutomationContext,
    automationFileIndex,
    refreshGitBranches,
    switchGitBranch,
    refreshFileIndex,
    forgetWorkspace,
    refreshWorkspaces,
    call,
    dispose
  }
}
