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
const user = ref(getAuthUser())
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
  models: 'M7 3h10v4h4v10h-4v4H7v-4H3V7h4zM9 9h6v6H9z', evals: 'M9 11l3 3 5-6M5 4h14v16H5z'
}

const managementGroups = [
  { label: { zh: '运行', en: 'Runtime' }, items: [
    ['/agents', { zh: '智能体', en: 'Agents' }, 'agents'], ['/runs', { zh: '运行记录', en: 'Runs' }, 'runs'],
    ['/sessions', { zh: '会话', en: 'Sessions' }, 'sessions'], ['/approvals', { zh: '审批', en: 'Approvals' }, 'approvals']
  ] },
  { label: { zh: '能力', en: 'Capabilities' }, items: [
    ['/tools', { zh: '工具', en: 'Tools' }, 'tools'], ['/mcp', { zh: 'MCP', en: 'MCP' }, 'mcp'],
    ['/skills', { zh: '技能', en: 'Skills' }, 'skills'], ['/models', { zh: '模型', en: 'Models' }, 'models']
  ] },
  { label: { zh: '观察', en: 'Observability' }, items: [
    ['/memory', { zh: '记忆', en: 'Memory' }, 'memory'], ['/plans', { zh: '计划', en: 'Plans' }, 'plans'],
    ['/traces', { zh: '追踪', en: 'Traces' }, 'traces'], ['/artifacts', { zh: '产物', en: 'Artifacts' }, 'artifacts'],
    ['/evals', { zh: '评估', en: 'Evals' }, 'evals']
  ] }
]

const filteredSessions = computed(() => {
  const normalized = query.value.trim().toLowerCase()
  return normalized
    ? consoleState.sessions.value.filter(item => t(item.title).toLowerCase().includes(normalized))
    : consoleState.sessions.value
})
const groupedSessions = computed(() => {
  const today = new Date(); today.setHours(0, 0, 0, 0)
  const yesterday = new Date(today); yesterday.setDate(yesterday.getDate() - 1)
  const labels = [t('今天'), t('昨天'), t('更早')]
  const groups = new Map(labels.map(label => [label, []]))
  for (const session of filteredSessions.value) {
    const date = new Date(session.updatedAt || 0)
    const key = date >= today ? t('今天') : date >= yesterday ? t('昨天') : t('更早')
    groups.get(key).push(session)
  }
  return labels.map(label => ({ label, sessions: groups.get(label) })).filter(group => group.sessions.length)
})

function localized(value) { return isEnglish.value ? value.en : value.zh }
function timeLabel(value) {
  if (!value) return ''
  return new Intl.DateTimeFormat(localeTag.value, { hour: '2-digit', minute: '2-digit' }).format(new Date(value))
}
function newTask() {
  const inheritedWorkspace = desktopWorkspace.currentWorkspace.value?.id
  consoleState.createSession()
  if (inheritedWorkspace) desktopWorkspace.bindWorkspace(inheritedWorkspace)
  drawerOpen.value = false
  router.push('/chat')
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
  if (event.key !== 'Escape') return
  openSessionId.value = ''; editingSessionId.value = ''
  if (deleteTarget.value) closeDeleteDialog()
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
      <button class="new-task-button" type="button" @click="newTask">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 5v14M5 12h14" /></svg>{{ t('新建任务') }}<kbd>⌘N</kbd>
      </button>
      <label class="task-search">
        <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="11" cy="11" r="6"/><path d="m16 16 4 4"/></svg>
        <span class="sr-only">{{ t('搜索任务') }}</span><input v-model="query" type="search" :placeholder="t('搜索任务')">
      </label>
    </div>
    <div class="sidebar-scroll codex-sidebar-scroll">
      <section class="task-groups" aria-label="Tasks">
        <div v-for="group in groupedSessions" :key="group.label" class="task-group">
          <p>{{ group.label }}</p>
          <div v-for="session in group.sessions" :key="session.id" class="task-row"
               :class="{ active: route.path === '/chat' && session.id === consoleState.currentSessionId.value }">
            <button v-if="editingSessionId !== session.id" class="task-select" type="button" @click="selectTask(session.id)">
              <span>{{ t(session.title) }}</span><small>{{ timeLabel(session.updatedAt) }}</small>
            </button>
            <input v-else ref="renameInput" v-model="draftTitle" class="task-rename" maxlength="60"
                   @click.stop @keydown.enter.prevent="commitRename(session)" @blur="commitRename(session)">
            <button class="task-more" type="button" aria-haspopup="menu" :aria-expanded="openSessionId === session.id" :aria-label="t('更多操作')"
                    @click.stop="toggleTaskMenu(session, $event)" @keydown.down.stop="toggleTaskMenu(session, $event)"
                    @keydown.enter.stop="toggleTaskMenu(session, $event)" @keydown.space.stop="toggleTaskMenu(session, $event)">•••</button>
            <div v-if="openSessionId === session.id" class="task-menu" role="menu" @click.stop @keydown="navigateTaskMenu">
              <button type="button" role="menuitem" @click="beginRename(session)">{{ t('重命名') }}</button>
              <button type="button" role="menuitem" class="danger" :disabled="session.activeRunId || session.submitting"
                      @click="requestDelete(session)">{{ t('删除') }}</button>
            </div>
          </div>
        </div>
        <p v-if="!filteredSessions.length" class="sidebar-empty">{{ t('没有匹配的任务') }}</p>
      </section>
      <section class="workbench-nav">
        <button class="workbench-toggle" type="button" :aria-expanded="workbenchOpen" @click="workbenchOpen = !workbenchOpen">
          <span><svg viewBox="0 0 24 24"><path d="M4 5h16v14H4zM9 5v14"/></svg>{{ t('工作台') }}</span><b :class="{ open: workbenchOpen }">›</b>
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
        <div class="confirm-dialog-copy"><h2 id="deleteTaskTitle">{{ t('删除这个会话？') }}</h2>
          <p>{{ t('“{title}”将从服务端会话列表中移除。为满足审计要求，已生成的运行事件仍按系统留存策略保存。', { title: t(deleteTarget.title) }) }}</p></div>
        <div class="confirm-dialog-actions"><button type="button" class="dialog-cancel" @click="closeDeleteDialog">{{ t('取消') }}</button>
          <button ref="deleteButton" type="button" class="dialog-confirm" @click="confirmDelete">{{ t('删除') }}</button></div>
      </section>
    </div>
  </Transition></Teleport>
</template>
