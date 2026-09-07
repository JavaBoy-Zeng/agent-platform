<script setup>
import { computed, inject, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getAuthUser } from '../services/apiConfig.js'
import { useLocale } from '../composables/useLocale.js'

const consoleState = inject('agentConsole')
const desktopWorkspace = inject('desktopWorkspace')
const route = useRoute()
const router = useRouter()
const { isEnglish, localeTag, t } = useLocale()

const query = ref('')
const workbenchOpen = ref(false)
const openSessionId = ref('')
const editingSessionId = ref('')
const draftTitle = ref('')
const renameInput = ref(null)
const deleteTarget = ref(null)
const deleteButton = ref(null)
const selectionMode = ref(false)
const selectedSessionIds = ref(new Set())
const collapsedGroupIds = ref(new Set())
const bulkDeleteIds = ref([])
const bulkDeleteButton = ref(null)
const bulkDeleting = ref(false)
const shortcutLabel = /Mac|iPhone|iPad|iPod/i.test(navigator.userAgentData?.platform || navigator.platform)
  ? '⌘N'
  : 'Ctrl+N'
const user = ref(getAuthUser())
const isAdmin = computed(() => (user.value?.roles || []).some(role => String(role).toUpperCase() === 'ADMIN'))
const drawerOpen = ref(false)
let dialogReturnFocus = null

const icons = {
  agents: 'M12 3v3m-5 6a5 5 0 0 1 10 0v-1a5 5 0 0 0-10 0zm-3 0H2m20 0h-2',
  runs: 'm9 7 7 5-7 5z', sessions: 'M4 5h16v14H4zM8 3v4m8-4v4M4 9h16',
  tools: 'M14.5 6.5a4 4 0 0 0-5 5L4 17l3 3 5.5-5.5a4 4 0 0 0 5-5l-3 3-3-3z',
  mcp: 'M8 5v4m8-4v4M6 9h12v2a6 6 0 0 1-6 6v3', skills: 'm13 2-8 12h6l-1 8 9-13h-6z',
  memory: 'M12 3a4 4 0 0 0-4 4v10a4 4 0 0 0 8 0V7a4 4 0 0 0-4-4zM8 9H5a2 2 0 0 0 0 4h3m8-4h3a2 2 0 0 1 0 4h-3',
  plans: 'M5 4h14v16H5zM8 8h8m-8 4h8m-8 4h5', traces: 'M4 17c3-8 5-2 8-8s5 0 8-5M4 17h4m-4 0v-4M20 4h-4m4 0v4',
  artifacts: 'm4 7 8-4 8 4-8 4zM4 7v10l8 4 8-4V7M12 11v10', approvals: 'M12 3 4 6v5c0 5 3.4 8.3 8 10 4.6-1.7 8-5 8-10V6l-8-3zM9 12l2 2 4-5',
  models: 'M7 3h10v4h4v10h-4v4H7v-4H3V7h4zM9 9h6v6H9z', evals: 'M9 11l3 3 5-6M5 4h14v16H5z',
  automations: 'M12 3v3m0 12v3M3 12h3m12 0h3M6.3 6.3l2.1 2.1m7.2 7.2 2.1 2.1m0-11.4-2.1 2.1m-7.2 7.2-2.1 2.1M9 12a3 3 0 1 0 6 0 3 3 0 0 0-6 0z'
}

const managementGroups = computed(() => [
  { label: { zh: '运行', en: 'Runtime' }, items: [
    ['/agents', { zh: '智能体', en: 'Agents' }, 'agents'], ['/runs', { zh: '运行记录', en: 'Runs' }, 'runs'],
    ['/sessions', { zh: '会话', en: 'Sessions' }, 'sessions'], ['/automations', { zh: '自动化', en: 'Automations' }, 'automations'],
    ['/approvals', { zh: '审批', en: 'Approvals' }, 'approvals']
  ] },
  { label: { zh: '能力', en: 'Capabilities' }, items: [
    ['/tools', { zh: '工具', en: 'Tools' }, 'tools'], ['/mcp', { zh: 'MCP', en: 'MCP' }, 'mcp'],
    ['/skills', { zh: '技能', en: 'Skills' }, 'skills'],
    ...(isAdmin.value ? [['/models', { zh: '模型', en: 'Models' }, 'models']] : [])
  ] },
  { label: { zh: '观察', en: 'Observability' }, items: [
    ['/memory', { zh: '记忆', en: 'Memory' }, 'memory'], ['/plans', { zh: '计划', en: 'Plans' }, 'plans'],
    ['/traces', { zh: '追踪', en: 'Traces' }, 'traces'], ['/artifacts', { zh: '产物', en: 'Artifacts' }, 'artifacts'],
    ['/evals', { zh: '评估', en: 'Evals' }, 'evals']
  ] }
])

const filteredSessions = computed(() => {
  const normalized = query.value.trim().toLowerCase()
  const visibleSessions = consoleState.sessions.value.filter(
    session => !consoleState.isSessionDraft?.(session))
  return normalized
    ? visibleSessions.filter(item => t(item.title).toLowerCase().includes(normalized))
    : visibleSessions
})
const groupedSessions = computed(() => {
  const groups = new Map()
  const pinnedSessions = []
  for (const session of filteredSessions.value) {
    if (session.pinned) {
      pinnedSessions.push(session)
      continue
    }
    const workspace = desktopWorkspace.workspaceForSession?.(session.id) || null
    const key = workspace?.id || '__default__'
    if (!groups.has(key)) {
      groups.set(key, {
        id: key,
        label: workspace?.name || t('默认目录'),
        root: workspace?.root || t('未选择本机目录'),
        sessions: []
      })
    }
    groups.get(key).sessions.push(session)
  }
  groups.forEach(group => group.sessions.sort((left, right) =>
    new Date(right.createdAt || 0).getTime() - new Date(left.createdAt || 0).getTime()))
  const directoryGroups = [...groups.values()].sort((left, right) => {
    if (left.id === '__default__') return -1
    if (right.id === '__default__') return 1
    return left.label.localeCompare(right.label, localeTag.value)
  })
  if (!pinnedSessions.length) return directoryGroups
  pinnedSessions.sort((left, right) =>
    new Date(right.pinnedAt || right.updatedAt || 0).getTime()
      - new Date(left.pinnedAt || left.updatedAt || 0).getTime())
  return [{
    id: '__pinned__',
    label: t('置顶'),
    root: t('置顶任务'),
    pinned: true,
    sessions: pinnedSessions
  }, ...directoryGroups]
})

function isGroupCollapsed(groupId) { return collapsedGroupIds.value.has(groupId) }
function toggleGroup(groupId) {
  const next = new Set(collapsedGroupIds.value)
  if (next.has(groupId)) next.delete(groupId)
  else next.add(groupId)
  collapsedGroupIds.value = next
}

function onCollapseBeforeEnter(el) {
  el.style.height = '0px'; el.style.opacity = '0'; el.style.overflow = 'hidden'
}
function onCollapseEnter(el) {
  el.offsetHeight
  el.style.height = `${el.scrollHeight}px`; el.style.opacity = '1'
}
function onCollapseBeforeLeave(el) {
  el.style.overflow = 'hidden'; el.style.height = `${el.scrollHeight}px`
}
function onCollapseLeave(el) {
  el.offsetHeight
  el.style.height = '0px'; el.style.opacity = '0'
}
function onCollapseSettled(el) {
  el.style.height = ''; el.style.opacity = ''; el.style.overflow = ''
}

function sessionBusy(session) {
  return Boolean(session.activeRunId || session.submitting)
}
const selectableSessions = computed(() => filteredSessions.value.filter(session => !sessionBusy(session)))
const allSelectableSelected = computed(() =>
  selectableSessions.value.length > 0 && selectableSessions.value.every(session => selectedSessionIds.value.has(session.id)))

function enterSelectionMode() {
  openSessionId.value = ''; editingSessionId.value = ''
  selectionMode.value = true
}
function exitSelectionMode() {
  selectionMode.value = false
  selectedSessionIds.value = new Set()
}
function toggleSessionSelection(session) {
  if (sessionBusy(session)) return
  const next = new Set(selectedSessionIds.value)
  if (next.has(session.id)) next.delete(session.id)
  else next.add(session.id)
  selectedSessionIds.value = next
}
function toggleSelectAll() {
  selectedSessionIds.value = allSelectableSelected.value
    ? new Set()
    : new Set(selectableSessions.value.map(session => session.id))
}
async function requestBulkDelete() {
  const ids = [...selectedSessionIds.value]
  if (!ids.length) return
  dialogReturnFocus = document.activeElement
  bulkDeleteIds.value = ids
  await nextTick(); bulkDeleteButton.value?.focus()
}
async function closeBulkDeleteDialog() {
  bulkDeleteIds.value = []
  await nextTick(); dialogReturnFocus?.focus?.(); dialogReturnFocus = null
}
async function confirmBulkDelete() {
  if (!bulkDeleteIds.value.length || bulkDeleting.value) return
  bulkDeleting.value = true
  try {
    await consoleState.deleteSessions([...bulkDeleteIds.value])
    await closeBulkDeleteDialog()
    exitSelectionMode()
  } finally {
    bulkDeleting.value = false
  }
}
function trapBulkDialogFocus(event) {
  if (!bulkDeleteIds.value.length) return
  if (event.key === 'Escape') { event.preventDefault(); closeBulkDeleteDialog(); return }
  if (event.key !== 'Tab') return
  const dialog = event.currentTarget
  const items = [...dialog.querySelectorAll('button:not(:disabled)')]
  if (!items.length) return
  const first = items[0]; const last = items[items.length - 1]
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus() }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus() }
}
function localized(value) { return isEnglish.value ? value.en : value.zh }
function timeLabel(value) {
  if (!value) return ''
  return new Intl.DateTimeFormat(localeTag.value, { hour: '2-digit', minute: '2-digit' }).format(new Date(value))
}
function newTask(workspaceId = '') {
  const targetWorkspaceId = typeof workspaceId === 'string' ? workspaceId : ''
  consoleState.createSession()
  if (targetWorkspaceId && targetWorkspaceId !== '__default__') desktopWorkspace.bindWorkspace(targetWorkspaceId)
  else if (desktopWorkspace.currentWorkspace?.value) desktopWorkspace.clearWorkspace()
  drawerOpen.value = false
  router.push('/chat')
}
async function toggleSessionPin(session) {
  if (!session.pinned && collapsedGroupIds.value.has('__pinned__')) {
    const next = new Set(collapsedGroupIds.value)
    next.delete('__pinned__')
    collapsedGroupIds.value = next
  }
  await consoleState.toggleSessionPin(session.id)
}
function selectTask(id) { openSessionId.value = ''; drawerOpen.value = false; consoleState.selectSession(id); router.push('/chat') }
async function beginRename(session) {
  openSessionId.value = ''
  editingSessionId.value = session.id
  draftTitle.value = t(session.title)
  await nextTick(); renameInput.value?.focus(); renameInput.value?.select()
}
function commitRename(session) {
  const value = draftTitle.value.trim()
  if (value && value !== session.title) consoleState.renameSession(session.id, value)
  editingSessionId.value = ''
}
async function requestDelete(session) {
  if (session.activeRunId || session.submitting) return
  dialogReturnFocus = document.activeElement
  openSessionId.value = ''; deleteTarget.value = session
  await nextTick(); deleteButton.value?.focus()
}
function confirmDelete() {
  if (!deleteTarget.value) return
  consoleState.deleteSession(deleteTarget.value.id); closeDeleteDialog()
}
async function closeDeleteDialog() {
  deleteTarget.value = null
  await nextTick(); dialogReturnFocus?.focus?.(); dialogReturnFocus = null
}
async function toggleTaskMenu(session, event) {
  const opening = openSessionId.value !== session.id
  openSessionId.value = opening ? session.id : ''
  if (opening && ['ArrowDown', 'Enter', ' '].includes(event.type === 'keydown' ? event.key : '')) {
    event.preventDefault(); await nextTick()
    event.currentTarget.closest('.task-row')?.querySelector('[role="menuitem"]')?.focus()
  }
}
function navigateTaskMenu(event) {
  const menu = event.currentTarget
  const items = [...menu.querySelectorAll('[role="menuitem"]:not(:disabled)')]
  const index = items.indexOf(document.activeElement)
  if (event.key === 'Escape') { event.preventDefault(); openSessionId.value = ''; return }
  if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key) || !items.length) return
  event.preventDefault()
  const next = event.key === 'Home' ? 0 : event.key === 'End' ? items.length - 1
    : event.key === 'ArrowDown' ? (index + 1) % items.length : (index - 1 + items.length) % items.length
  items[next].focus()
}
function trapDialogFocus(event) {
  if (!deleteTarget.value) return
  if (event.key === 'Escape') { event.preventDefault(); closeDeleteDialog(); return }
  if (event.key !== 'Tab') return
  const dialog = event.currentTarget
  const items = [...dialog.querySelectorAll('button:not(:disabled)')]
  if (!items.length) return
  const first = items[0]; const last = items[items.length - 1]
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus() }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus() }
}
function onDocumentClick() { openSessionId.value = '' }
function onKeydown(event) {
  const isNewTaskShortcut = (event.metaKey || event.ctrlKey)
    && !event.altKey
    && !event.shiftKey
    && event.key.toLowerCase() === 'n'
  if (isNewTaskShortcut) {
    event.preventDefault()
    if (!event.repeat && !bulkDeleteIds.value.length && !deleteTarget.value) newTask()
    return
  }
  if (event.key !== 'Escape') return
  openSessionId.value = ''; editingSessionId.value = ''
  if (bulkDeleteIds.value.length) { closeBulkDeleteDialog(); return }
  if (deleteTarget.value) { closeDeleteDialog(); return }
  if (selectionMode.value) exitSelectionMode()
}
function onAuthChanged(event) { user.value = event.detail || getAuthUser() }

onMounted(() => {
  document.addEventListener('click', onDocumentClick)
  document.addEventListener('keydown', onKeydown)
  window.addEventListener('agentos:auth-changed', onAuthChanged)
})
onUnmounted(() => {
  document.removeEventListener('click', onDocumentClick)
  document.removeEventListener('keydown', onKeydown)
  window.removeEventListener('agentos:auth-changed', onAuthChanged)
})
</script>

<template>
  <aside class="app-sidebar codex-sidebar" :class="{ 'drawer-open': drawerOpen }" :aria-label="t('Console 主导航')">
    <button class="drawer-toggle" type="button" :aria-expanded="drawerOpen" :aria-label="t('Console 主导航')" @click="drawerOpen = !drawerOpen">
      <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 7h14M5 12h14M5 17h14" /></svg>
    </button>
    <header class="sidebar-titlebar" data-tauri-drag-region>
      <router-link class="codex-brand" to="/chat"><span class="codex-brand-mark">A</span><strong>AgentOS</strong></router-link>
      <span class="desktop-dot" :class="consoleState.connection.value" :title="consoleState.connection.value"></span>
    </header>
    <div class="sidebar-primary">
      <button class="new-task-button" type="button" @click="newTask()">
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path d="M4 20h4l10.6-10.6a2.8 2.8 0 0 0-4-4L4 16v4zM13.5 6.5l4 4" />
        </svg>{{ t('新建任务') }}<kbd>{{ shortcutLabel }}</kbd>
      </button>
      <label class="task-search">
        <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="11" cy="11" r="6"/><path d="m16 16 4 4"/></svg>
        <span class="sr-only">{{ t('搜索任务') }}</span><input v-model="query" type="search" :placeholder="t('搜索任务')">
      </label>
    </div>
    <div class="sidebar-scroll codex-sidebar-scroll">
      <section class="task-groups" :aria-label="t('任务')">
        <div class="task-groups-header">
          <div class="task-groups-title">
            <strong>{{ t('任务') }}</strong>
            <span>{{ selectionMode ? t('已选 {count} 个', { count: selectedSessionIds.size }) : filteredSessions.length }}</span>
          </div>
          <button
            class="rail-select-toggle"
            :class="{ active: selectionMode }"
            type="button"
            :aria-label="t(selectionMode ? '退出多选' : '选择')"
            :disabled="!selectionMode && !selectableSessions.length"
            @click="selectionMode ? exitSelectionMode() : enterSelectionMode()"
          >
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path d="M4 6.5h3v3H4zM10 8h10M4 14.5h3v3H4zM10 16h10M4.8 7.9l.8.8 1.7-2" />
            </svg>
            {{ t(selectionMode ? '完成' : '管理') }}
          </button>
        </div>
        <div v-if="selectionMode" class="session-bulk-bar" role="toolbar" :aria-label="t('删除所选')">
          <button
            class="bulk-select-all"
            type="button"
            role="checkbox"
            :aria-checked="allSelectableSelected"
            @click="toggleSelectAll"
          >
            <span class="session-check" :class="{ checked: allSelectableSelected }" aria-hidden="true"></span>
            {{ t(allSelectableSelected ? '取消全选' : '全选') }}
          </button>
          <button
            class="bulk-delete-button"
            type="button"
            :disabled="!selectedSessionIds.size"
            @click="requestBulkDelete"
          >
            {{ t('删除所选') }}<span v-if="selectedSessionIds.size">{{ selectedSessionIds.size }}</span>
          </button>
        </div>
        <div v-for="group in groupedSessions" :key="group.id" class="task-group" :class="{ collapsed: isGroupCollapsed(group.id) }">
          <p class="task-directory-label">
            <button
              class="task-directory-toggle"
              type="button"
              :aria-expanded="!isGroupCollapsed(group.id)"
              :aria-label="t(isGroupCollapsed(group.id) ? '展开目录 {directory}' : '收起目录 {directory}', { directory: group.label })"
              :title="group.root"
              @click="toggleGroup(group.id)"
            >
              <svg v-if="group.pinned" class="task-directory-icon pinned-directory-icon" viewBox="0 0 24 24" aria-hidden="true">
                <path d="M8 3.5h8l-1 5.3 3 3v1.5H6v-1.5l3-3zM12 13.3v7.2" />
              </svg>
              <svg v-else class="task-directory-icon" viewBox="0 0 24 24" aria-hidden="true">
                <path d="M3.5 18.5V7.4c0-1 .8-1.9 1.9-1.9h3.8l2 2h7.4c1 0 1.9.8 1.9 1.9v1.1M4.2 18.5l2-6.4c.2-.7.9-1.1 1.6-1.1h12.7l-2.1 7.5H4.2z" />
              </svg>
              <span class="task-directory-name">{{ group.label }}</span>
              <small class="task-directory-count" :class="{ collapsed: isGroupCollapsed(group.id) }">{{ group.sessions.length }}</small>
            </button>
            <button
              v-if="!selectionMode && !group.pinned"
              class="task-directory-new"
              type="button"
              :aria-label="t('在 {directory} 中新建任务', { directory: group.label })"
              :title="t('在 {directory} 中新建任务', { directory: group.label })"
              @click.stop="newTask(group.id)"
            >
              <svg viewBox="0 0 24 24" aria-hidden="true">
                <path d="M12 5v14M5 12h14" />
              </svg>
            </button>
          </p>
          <Transition name="task-group-items" @before-enter="onCollapseBeforeEnter" @enter="onCollapseEnter" @after-enter="onCollapseSettled"
                      @before-leave="onCollapseBeforeLeave" @leave="onCollapseLeave" @after-leave="onCollapseSettled">
            <div v-show="!isGroupCollapsed(group.id)" class="task-group-items">
              <div class="task-group-items-inner">
                <div v-for="session in group.sessions" :key="session.id" class="task-row"
                     :class="{ active: route.path === '/chat' && session.id === consoleState.currentSessionId.value }">
            <button v-if="!selectionMode" class="task-pin" :class="{ active: session.pinned }" type="button"
                    :aria-pressed="Boolean(session.pinned)"
                    :aria-label="t(session.pinned ? '取消置顶任务：{title}' : '置顶任务：{title}', { title: t(session.title) })"
                    :title="t(session.pinned ? '取消置顶' : '置顶此任务')"
                    @click.stop="toggleSessionPin(session)">
              <svg viewBox="0 0 24 24" aria-hidden="true"><path class="pin-head" d="M8 3.5h8l-1 5.3 3 3v1.5H6v-1.5l3-3z" /><path d="M12 13.3v7.2" /></svg>
            </button>
            <button v-if="editingSessionId !== session.id" class="task-select" type="button"
                    :class="{ 'selection-control': selectionMode }"
                    :role="selectionMode ? 'checkbox' : undefined"
                    :aria-checked="selectionMode ? selectedSessionIds.has(session.id) : undefined"
                    :aria-label="selectionMode ? t('选择任务：{title}', { title: t(session.title) }) : undefined"
                    :disabled="selectionMode && sessionBusy(session)"
                    :title="selectionMode && sessionBusy(session) ? t('运行中的任务不可选择') : ''"
                    @click="selectionMode ? toggleSessionSelection(session) : selectTask(session.id)">
              <span v-if="selectionMode" class="session-check" :class="{ checked: selectedSessionIds.has(session.id) }" aria-hidden="true"></span>
              <span>{{ t(session.title) }}</span><small>{{ timeLabel(session.createdAt) }}</small>
            </button>
            <input v-else ref="renameInput" v-model="draftTitle" class="task-rename" maxlength="60"
                   @click.stop @keydown.enter.prevent="commitRename(session)" @blur="commitRename(session)">
            <button v-if="!selectionMode" class="task-more" type="button" aria-haspopup="menu" :aria-expanded="openSessionId === session.id" :aria-label="t('更多操作')"
                    @click.stop="toggleTaskMenu(session, $event)" @keydown.down.stop="toggleTaskMenu(session, $event)"
                    @keydown.enter.stop="toggleTaskMenu(session, $event)" @keydown.space.stop="toggleTaskMenu(session, $event)">•••</button>
            <div v-if="openSessionId === session.id" class="task-menu" role="menu" @click.stop @keydown="navigateTaskMenu">
              <button type="button" role="menuitem" @click="beginRename(session)">{{ t('重命名') }}</button>
              <button type="button" role="menuitem" class="danger" :disabled="session.activeRunId || session.submitting"
                      @click="requestDelete(session)">{{ t('删除') }}</button>
            </div>
          </div>
              </div>
            </div>
          </Transition>
        </div>
        <p v-if="!filteredSessions.length" class="sidebar-empty">{{ t('没有匹配的任务') }}</p>
      </section>
      <section class="workbench-nav">
        <button class="workbench-toggle" type="button" :aria-expanded="workbenchOpen" @click="workbenchOpen = !workbenchOpen">
          <span>
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 7h9m5 0h2M15 4v6M4 17h3m5 0h8M9 14v6" /></svg>
            {{ t('工作台') }}
          </span><b :class="{ open: workbenchOpen }">›</b>
        </button>
        <div v-if="workbenchOpen" class="workbench-groups">
          <section v-for="group in managementGroups" :key="group.label.en">
            <p>{{ localized(group.label) }}</p>
            <router-link v-for="item in group.items" :key="item[0]" :to="item[0]" class="workbench-link" @click="drawerOpen = false">
              <svg viewBox="0 0 24 24"><path :d="icons[item[2]]" /></svg><span>{{ localized(item[1]) }}</span>
            </router-link>
          </section>
        </div>
      </section>
    </div>
    <footer class="sidebar-account">
      <router-link to="/settings" class="account-link" @click="drawerOpen = false">
        <span class="account-avatar">{{ (user?.username || 'A').slice(0, 1).toUpperCase() }}</span>
        <span><strong>{{ user?.username || 'AgentOS' }}</strong><small>{{ desktopWorkspace.desktop ? 'Desktop' : 'Web console' }}</small></span><b>···</b>
      </router-link>
    </footer>
  </aside>

  <Teleport to="body"><Transition name="dialog-fade">
    <div v-if="deleteTarget" class="dialog-backdrop" @click.self="closeDeleteDialog">
      <section class="confirm-dialog" role="alertdialog" aria-modal="true" aria-labelledby="deleteTaskTitle" @keydown="trapDialogFocus">
        <div class="confirm-dialog-icon" aria-hidden="true">⌫</div>
        <div class="confirm-dialog-copy"><h2 id="deleteTaskTitle">{{ t('删除这个任务？') }}</h2>
          <p>{{ t('“{title}”将从服务端任务列表中移除。为满足审计要求，已生成的运行事件仍按系统留存策略保存。', { title: t(deleteTarget.title) }) }}</p></div>
        <div class="confirm-dialog-actions"><button type="button" class="dialog-cancel" @click="closeDeleteDialog">{{ t('取消') }}</button>
          <button ref="deleteButton" type="button" class="dialog-confirm" @click="confirmDelete">{{ t('删除') }}</button></div>
      </section>
    </div>
  </Transition></Teleport>

  <Teleport to="body"><Transition name="dialog-fade">
    <div v-if="bulkDeleteIds.length" class="dialog-backdrop" @click.self="closeBulkDeleteDialog">
      <section class="confirm-dialog" role="alertdialog" aria-modal="true" aria-labelledby="bulkDeleteTaskTitle" @keydown="trapBulkDialogFocus">
        <div class="confirm-dialog-icon" aria-hidden="true">⌫</div>
        <div class="confirm-dialog-copy"><h2 id="bulkDeleteTaskTitle">{{ t('批量删除任务？') }}</h2>
          <p>{{ t('选中的 {count} 个任务将从服务端任务列表中移除。为满足审计要求，已生成的运行事件仍按系统留存策略保存。', { count: bulkDeleteIds.length }) }}</p></div>
        <div class="confirm-dialog-actions"><button type="button" class="dialog-cancel" @click="closeBulkDeleteDialog">{{ t('取消') }}</button>
          <button ref="bulkDeleteButton" type="button" class="dialog-confirm" :disabled="bulkDeleting" @click="confirmBulkDelete">{{ bulkDeleting ? t('正在删除…') : t('删除 {count} 个任务', { count: bulkDeleteIds.length }) }}</button></div>
      </section>
    </div>
  </Transition></Teleport>
</template>
