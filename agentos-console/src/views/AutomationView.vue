<script setup>
import { computed, inject, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import AppSelect from '../components/AppSelect.vue'
import {
  createAutomation,
  deleteAutomation,
  getAutomationExecutions,
  getAutomations,
  previewAutomationSchedule,
  runAutomation,
  setAutomationEnabled,
  updateAutomation
} from '../services/automationApi.js'

const router = useRouter()
const consoleState = inject('agentConsole')
const desktopWorkspace = inject('desktopWorkspace')
const automationDesktop = inject('automationDesktop')

const activeTab = ref('configured')
const tasks = ref([])
const executions = ref([])
const executionPage = ref({ total: 0, offset: 0, limit: 20, hasMore: false })
const loading = ref(false)
const historyLoading = ref(false)
const error = ref('')
const toast = ref('')
const menuId = ref('')
const dialogOpen = ref(false)
const editingId = ref('')
const saving = ref(false)
const formError = ref('')
const preview = ref(null)
const previewLoading = ref(false)
const deleteTarget = ref(null)
const dialog = ref(null)
const firstInput = ref(null)
const automationFiles = ref([])
const mention = ref(null)
const mentionIndex = ref(0)
let returnFocus
let previewTimer
let refreshTimer

const defaultZone = (() => {
  try { return Intl.DateTimeFormat().resolvedOptions().timeZone || 'Asia/Shanghai' } catch { return 'Asia/Shanghai' }
})()

const form = reactive(defaultForm())

const tabs = [
  { id: 'configured', label: '已配置' },
  { id: 'history', label: '执行历史' },
  { id: 'templates', label: '任务模板' }
]
const triggerTypes = [{ value: 'PERIOD', label: '周期' }, { value: 'INTERVAL', label: '间隔' }]
const periodModes = [{ value: 'BASIC', label: '基础规则' }, { value: 'CRON', label: '高级 Cron' }]
const periodUnits = [
  { value: 'DAILY', label: '每天' },
  { value: 'WEEKLY', label: '每周' },
  { value: 'MONTHLY', label: '每月' }
]
const weekdays = ['一', '二', '三', '四', '五', '六', '日']
  .map((label, index) => ({ value: index + 1, label: `星期${label}` }))
const monthDays = Array.from({ length: 31 }, (_, index) => ({ value: index + 1, label: `${index + 1} 日` }))
const intervalUnits = [
  { value: 'MINUTES', label: '分钟' },
  { value: 'HOURS', label: '小时' },
  { value: 'DAYS', label: '天' }
]
const permissionOptions = [
  { value: 'REQUEST_APPROVAL', label: '请求批准', description: '外部动作始终等待确认' },
  { value: 'RISK_BASED', label: '风险审批', description: '仅高风险操作等待确认' },
  { value: 'FULL_ACCESS', label: '完全访问', description: '允许无人值守完成所有动作' }
]
const timeZones = (() => {
  try {
    return Intl.supportedValuesOf('timeZone').map(value => ({ value, label: value.replaceAll('_', ' ') }))
  } catch {
    return ['Asia/Shanghai', 'Asia/Tokyo', 'Europe/London', 'America/New_York']
      .map(value => ({ value, label: value }))
  }
})()

const modelOptions = computed(() => consoleState.models.value.map(model => ({
  value: model.id,
  label: model.name,
  description: `${model.provider} · ${model.providerType}`
})))
const workspaceOptions = computed(() => desktopWorkspace.workspaces.value.map(workspace => ({
  value: workspace.id,
  label: workspace.name,
  description: workspace.gitRepository ? 'Git 工作区' : '本地工作区'
})))
const canCreate = computed(() => automationDesktop.desktop && automationDesktop.clientId.value
  && desktopWorkspace.available.value)
const selectedWorkspace = computed(() => desktopWorkspace.workspaces.value
  .find(item => item.id === form.workspaceId))
const filteredMentionFiles = computed(() => {
  if (!mention.value) return []
  const query = mention.value.query.toLowerCase()
  return automationFiles.value.filter(file => !query
    || file.relativePath.toLowerCase().includes(query)
    || file.name.toLowerCase().includes(query)).slice(0, 8)
})

function defaultForm() {
  return {
    name: '',
    prompt: '',
    modelId: '',
    approvalMode: 'RISK_BASED',
    workspaceId: '',
    triggerType: 'PERIOD',
    periodMode: 'BASIC',
    periodUnit: 'DAILY',
    hour: '09',
    minute: '00',
    weekday: 1,
    monthDay: 1,
    cron: '0 9 * * *',
    every: 30,
    intervalUnit: 'MINUTES',
    timeZone: defaultZone,
    enabled: true
  }
}

function resetForm(value = null) {
  Object.assign(form, defaultForm())
  if (!value) {
    form.modelId = modelOptions.value[0]?.value || ''
    form.workspaceId = workspaceOptions.value[0]?.value || ''
    return
  }
  const trigger = value.trigger || {}
  const [hour = '09', minute = '00'] = String(trigger.time || '09:00').split(':')
  Object.assign(form, {
    name: value.name,
    prompt: value.prompt,
    modelId: value.modelId,
    approvalMode: value.approvalMode,
    workspaceId: value.workspaceId,
    triggerType: trigger.type || 'PERIOD',
    periodMode: trigger.periodMode || 'BASIC',
    periodUnit: trigger.periodUnit || 'DAILY',
    hour,
    minute,
    weekday: trigger.weekday || 1,
    monthDay: trigger.monthDay || 1,
    cron: trigger.cron || '0 9 * * *',
    every: trigger.every || 30,
    intervalUnit: trigger.intervalUnit || 'MINUTES',
    timeZone: trigger.timeZone || defaultZone,
    enabled: value.enabled
  })
}

function triggerPayload() {
  if (form.triggerType === 'INTERVAL') {
    return {
      type: 'INTERVAL',
      every: Number(form.every),
      intervalUnit: form.intervalUnit,
      timeZone: form.timeZone
    }
  }
  if (form.periodMode === 'CRON') {
    return { type: 'PERIOD', periodMode: 'CRON', cron: form.cron.trim(), timeZone: form.timeZone }
  }
  return {
    type: 'PERIOD',
    periodMode: 'BASIC',
    periodUnit: form.periodUnit,
    time: `${normalizePart(form.hour, 23)}:${normalizePart(form.minute, 59)}`,
    weekday: form.periodUnit === 'WEEKLY' ? Number(form.weekday) : null,
    monthDay: form.periodUnit === 'MONTHLY' ? Number(form.monthDay) : null,
    timeZone: form.timeZone
  }
}

function normalizePart(value, max) {
  const number = Math.min(max, Math.max(0, Number.parseInt(value, 10) || 0))
  return String(number).padStart(2, '0')
}

async function loadTasks({ quiet = false } = {}) {
  if (!quiet) loading.value = true
  try {
    tasks.value = await getAutomations()
    error.value = ''
  } catch (cause) {
    error.value = cause.message || '无法读取自动化任务'
  } finally {
    loading.value = false
  }
}

async function loadHistory(offset = 0) {
  historyLoading.value = true
  try {
    const page = await getAutomationExecutions({ offset, limit: executionPage.value.limit })
    executions.value = page.items || []
    executionPage.value = page
    error.value = ''
  } catch (cause) {
    error.value = cause.message || '无法读取执行历史'
  } finally {
    historyLoading.value = false
  }
}

async function openCreate(event) {
  if (!canCreate.value) {
    showToast('请在 AgentOS Desktop 中登录并授权一个本地工作区')
    return
  }
  returnFocus = event?.currentTarget || document.activeElement
  editingId.value = ''
  await desktopWorkspace.authorize()
  await consoleState.refreshServerModel()
  resetForm()
  dialogOpen.value = true
  formError.value = ''
  await onWorkspaceChanged(form.workspaceId)
  await nextTick()
  firstInput.value?.focus()
  schedulePreview()
}

async function openEdit(task, event) {
  returnFocus = event?.currentTarget || document.activeElement
  editingId.value = task.automationId
  resetForm(task)
  dialogOpen.value = true
  formError.value = ''
  menuId.value = ''
  await onWorkspaceChanged(form.workspaceId)
  await nextTick()
  firstInput.value?.focus()
  schedulePreview()
}

async function closeDialog({ force = false } = {}) {
  if (saving.value && !force) return
  dialogOpen.value = false
  mention.value = null
  clearTimeout(previewTimer)
  await nextTick()
  returnFocus?.focus?.()
}

async function saveTask() {
  if (saving.value) return
  formError.value = ''
  if (!form.name.trim() || !form.prompt.trim() || !form.modelId || !selectedWorkspace.value) {
    formError.value = '请完整填写任务名称、任务指令、工作区和模型'
    return
  }
  saving.value = true
  try {
    await schedulePreview(true)
    const body = {
      name: form.name.trim(),
      prompt: form.prompt.trim(),
      modelId: form.modelId,
      approvalMode: form.approvalMode,
      desktopClientId: automationDesktop.clientId.value,
      workspaceId: selectedWorkspace.value.id,
      workspaceName: selectedWorkspace.value.name,
      trigger: triggerPayload(),
      enabled: form.enabled
    }
    if (editingId.value) await updateAutomation(editingId.value, body)
    else await createAutomation(body)
    await loadTasks({ quiet: true })
    await closeDialog({ force: true })
    showToast(editingId.value ? '自动化任务已更新' : '自动化任务已创建')
  } catch (cause) {
    formError.value = cause.message || '无法保存自动化任务'
  } finally {
    saving.value = false
  }
}

async function schedulePreview(throwOnFailure = false) {
  previewLoading.value = true
  try {
    preview.value = await previewAutomationSchedule(triggerPayload())
    if (formError.value.startsWith('调度规则')) formError.value = ''
    return preview.value
  } catch (cause) {
    preview.value = null
    formError.value = `调度规则：${cause.message || '无法计算'}`
    if (throwOnFailure) throw cause
    return null
  } finally {
    previewLoading.value = false
  }
}

function queuePreview() {
  if (!dialogOpen.value) return
  clearTimeout(previewTimer)
  previewTimer = window.setTimeout(() => schedulePreview(), 300)
}

async function toggleTask(task) {
  try {
    await setAutomationEnabled(task.automationId, !task.enabled)
    await loadTasks({ quiet: true })
  } catch (cause) { showToast(cause.message || '无法更新任务状态', true) }
}

async function runNow(task) {
  menuId.value = ''
  try {
    await runAutomation(task.automationId)
    showToast('任务已排队，桌面端即将读取最新工作区')
    window.setTimeout(() => loadHistory(0), 600)
  } catch (cause) { showToast(cause.message || '无法立即执行', true) }
}

async function confirmDelete() {
  if (!deleteTarget.value) return
  try {
    await deleteAutomation(deleteTarget.value.automationId)
    deleteTarget.value = null
    await loadTasks({ quiet: true })
    showToast('任务已删除，执行历史仍然保留')
  } catch (cause) { showToast(cause.message || '无法删除任务', true) }
}

async function updateKeepAwake() {
  try {
    await automationDesktop.setKeepAwake(!automationDesktop.keepAwake.value)
    showToast(automationDesktop.keepAwake.value ? '已保持电脑唤醒' : '已允许电脑正常休眠')
  } catch (cause) { showToast(cause.message || '无法修改保持唤醒状态', true) }
}

async function openSession(execution) {
  if (!execution.sessionId) return
  await consoleState.refreshSessions()
  await consoleState.selectSession(execution.sessionId)
  router.push('/chat')
}

async function onWorkspaceChanged(value) {
  form.workspaceId = value
  automationFiles.value = value ? await desktopWorkspace.automationFileIndex(value).catch(() => []) : []
}

function onPromptInput(event) {
  form.prompt = event.target.value
  const before = form.prompt.slice(0, event.target.selectionStart)
  const match = before.match(/(?:^|\s)@([^@\s]*)$/)
  mention.value = match ? { query: match[1], start: before.length - match[1].length - 1,
    cursor: event.target.selectionStart } : null
  mentionIndex.value = 0
}

function promptKeydown(event) {
  if (!mention.value) return
  if (['ArrowDown', 'ArrowUp'].includes(event.key)) {
    event.preventDefault()
    const size = filteredMentionFiles.value.length
    if (size) mentionIndex.value = (mentionIndex.value + (event.key === 'ArrowDown' ? 1 : -1) + size) % size
  } else if (['Enter', 'Tab'].includes(event.key) && filteredMentionFiles.value.length) {
    event.preventDefault()
    selectMention(filteredMentionFiles.value[mentionIndex.value])
  } else if (event.key === 'Escape') {
    event.preventDefault(); mention.value = null
  }
}

function selectMention(file) {
  const state = mention.value
  if (!state || !file) return
  form.prompt = form.prompt.slice(0, state.start) + `@${file.relativePath} ` + form.prompt.slice(state.cursor)
  mention.value = null
}

function trapDialog(event) {
  if (event.key === 'Escape') { event.preventDefault(); closeDialog(); return }
  if (event.key !== 'Tab') return
  const items = [...dialog.value.querySelectorAll('button:not(:disabled), input:not(:disabled), textarea:not(:disabled)')]
  if (!items.length) return
  const first = items[0]; const last = items[items.length - 1]
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus() }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus() }
}

function formatDate(value) {
  if (!value) return '—'
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit'
  }).format(new Date(value))
}

function duration(execution) {
  if (!execution.startedAt || !execution.finishedAt) return '—'
  const seconds = Math.max(0, Math.round((new Date(execution.finishedAt) - new Date(execution.startedAt)) / 1000))
  return seconds < 60 ? `${seconds}s` : `${Math.floor(seconds / 60)}m ${seconds % 60}s`
}

function statusLabel(status) {
  return ({ QUEUED: '排队中', CLAIMED: '已领取', RUNNING: '执行中', WAITING: '等待审批',
    COMPLETED: '已完成', FAILED: '失败', CANCELLED: '已取消', SKIPPED_OFFLINE: '桌面离线',
    SKIPPED_OVERLAP: '重叠跳过', SKIPPED_WORKSPACE_UNAVAILABLE: '工作区不可用' })[status] || status
}

function scheduleLabel(task) {
  const t = task.trigger
  if (t.type === 'INTERVAL') return `每 ${t.every} ${{ MINUTES: '分钟', HOURS: '小时', DAYS: '天' }[t.intervalUnit]}`
  if (t.periodMode === 'CRON') return `Cron · ${t.cron}`
  if (t.periodUnit === 'DAILY') return `每天 ${t.time}`
  if (t.periodUnit === 'WEEKLY') return `每周${weekdays[t.weekday - 1]?.label.replace('星期', '')} ${t.time}`
  return `每月 ${t.monthDay} 日 ${t.time}`
}

function showToast(message, danger = false) {
  toast.value = `${danger ? '!' : ''}${message}`
  window.setTimeout(() => { if (toast.value.endsWith(message)) toast.value = '' }, 3200)
}

function closeMenus(event) {
  if (!event.target.closest('.automation-action-menu')) menuId.value = ''
}

function handleAutomationUpdated() {
  loadTasks({ quiet: true })
  if (activeTab.value === 'history') loadHistory(executionPage.value.offset)
}

watch(() => [form.triggerType, form.periodMode, form.periodUnit, form.hour, form.minute,
  form.weekday, form.monthDay, form.cron, form.every, form.intervalUnit, form.timeZone], queuePreview, { deep: true })
watch(activeTab, value => { if (value === 'history') loadHistory(0) })

onMounted(async () => {
  document.addEventListener('pointerdown', closeMenus)
  window.addEventListener('agentos:automation-updated', handleAutomationUpdated)
  await Promise.all([loadTasks(), consoleState.refreshServerModel(), desktopWorkspace.authorize()])
  refreshTimer = window.setInterval(() => loadTasks({ quiet: true }), 15_000)
})
onUnmounted(() => {
  document.removeEventListener('pointerdown', closeMenus)
  window.removeEventListener('agentos:automation-updated', handleAutomationUpdated)
  clearInterval(refreshTimer)
  clearTimeout(previewTimer)
})
</script>

<template>
  <div class="automation-view">
    <header class="automation-hero">
      <div>
        <span class="hero-eyebrow">DESKTOP ORCHESTRATION</span>
        <h1>自动化</h1>
        <p>让 AgentOS 在你指定的节奏中，持续读取真实工作区并完成任务。</p>
      </div>
      <button class="create-automation" type="button" :disabled="!canCreate" @click="openCreate">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 5v14M5 12h14"/></svg>
        手动新建
      </button>
    </header>

    <nav class="automation-tabs" aria-label="自动化页面">
      <button v-for="tab in tabs" :key="tab.id" type="button" :class="{ active: activeTab === tab.id }"
              @click="activeTab = tab.id">{{ tab.label }}<span v-if="tab.id === 'configured'">{{ tasks.length }}</span></button>
    </nav>

    <section class="desktop-status" :class="{ offline: !automationDesktop.online.value }">
      <div class="desktop-status-icon" aria-hidden="true">
        <span></span><svg viewBox="0 0 24 24"><path d="M4 5h16v11H4zM8 20h8M12 16v4"/></svg>
      </div>
      <div>
        <strong>{{ automationDesktop.desktop ? (automationDesktop.online.value ? '这台电脑已就绪' : '这台电脑当前离线') : '需要 AgentOS Desktop' }}</strong>
        <p>{{ automationDesktop.desktop ? '本地自动化仅在应用运行且电脑保持唤醒时执行；错过的触发不会补跑。' : 'Web Console 可以管理任务，但不能读取或执行本地工作区自动化。' }}</p>
      </div>
      <div class="wake-control">
        <span><strong>保持电脑唤醒</strong><small>{{ automationDesktop.keepAwakeSupported.value ? '系统休眠保护' : '当前平台不可用' }}</small></span>
        <button type="button" role="switch" :aria-checked="automationDesktop.keepAwake.value"
                :disabled="!automationDesktop.keepAwakeSupported.value" :class="{ on: automationDesktop.keepAwake.value }"
                @click="updateKeepAwake"><i></i></button>
      </div>
    </section>

    <p v-if="error" class="automation-error" role="alert">{{ error }}</p>

    <main v-if="activeTab === 'configured'" class="configured-panel">
      <div v-if="loading" class="automation-loading"><span></span>正在读取任务…</div>
      <section v-else-if="tasks.length" class="task-ledger">
        <article v-for="task in tasks" :key="task.automationId" class="automation-row" :class="{ disabled: !task.enabled }">
          <div class="task-signal"><i :class="{ live: task.enabled }"></i><span>{{ task.enabled ? 'ACTIVE' : 'PAUSED' }}</span></div>
          <div class="task-copy">
            <h2>{{ task.name }}</h2>
            <p><span>{{ task.workspaceName }}</span><b>{{ scheduleLabel(task) }}</b><small>{{ task.trigger.timeZone }}</small></p>
          </div>
          <div class="task-timing"><small>下次执行</small><strong>{{ formatDate(task.nextTriggerAt) }}</strong></div>
          <button class="run-now" type="button" :disabled="!task.enabled || !automationDesktop.online.value" @click="runNow(task)" aria-label="立即执行">
            <svg viewBox="0 0 24 24"><path d="m9 7 7 5-7 5z"/></svg>
          </button>
          <button class="task-switch" type="button" role="switch" :aria-checked="task.enabled" :class="{ on: task.enabled }"
                  :aria-label="task.enabled ? '停用任务' : '启用任务'" @click="toggleTask(task)"><i></i></button>
          <div class="automation-action-menu">
            <button type="button" aria-haspopup="menu" :aria-expanded="menuId === task.automationId" aria-label="更多操作"
                    @click.stop="menuId = menuId === task.automationId ? '' : task.automationId">•••</button>
            <div v-if="menuId === task.automationId" role="menu" @click.stop>
              <button type="button" role="menuitem" @click="openEdit(task, $event)">编辑任务</button>
              <button type="button" role="menuitem" class="danger" @click="deleteTarget = task; menuId = ''">删除任务</button>
            </div>
          </div>
        </article>
      </section>
      <section v-else class="automation-empty">
        <div class="empty-orbit"><i></i><span>+</span></div>
        <h2>让重复工作自己发生</h2>
        <p>创建第一个自动化任务，AgentOS 会在这台电脑在线时读取最新工作区并执行。</p>
        <button type="button" :disabled="!canCreate" @click="openCreate">新建自动化</button>
      </section>
    </main>

    <main v-else-if="activeTab === 'history'" class="history-panel">
      <div v-if="historyLoading" class="automation-loading"><span></span>正在读取执行历史…</div>
      <div v-else-if="executions.length" class="history-table-wrap">
        <table class="history-table">
          <thead><tr><th>任务</th><th>状态</th><th>触发</th><th>计划时间</th><th>耗时</th><th>结果</th></tr></thead>
          <tbody>
            <tr v-for="execution in executions" :key="execution.executionId">
              <td><strong>{{ execution.taskName }}</strong><small>{{ execution.workspaceName }}</small></td>
              <td><span class="status-pill" :class="execution.status.toLowerCase()">{{ statusLabel(execution.status) }}</span></td>
              <td>{{ execution.triggerSource === 'MANUAL' ? '手动' : '计划' }}</td>
              <td>{{ formatDate(execution.scheduledAt) }}</td>
              <td>{{ duration(execution) }}</td>
              <td><button v-if="execution.sessionId" type="button" @click="openSession(execution)">打开 Chat ↗</button>
                <span v-else :title="execution.errorMessage">{{ execution.errorMessage || '—' }}</span></td>
            </tr>
          </tbody>
        </table>
        <footer class="history-pagination">
          <span>共 {{ executionPage.total }} 次执行</span>
          <div><button type="button" :disabled="executionPage.offset === 0" @click="loadHistory(Math.max(0, executionPage.offset - executionPage.limit))">上一页</button>
            <button type="button" :disabled="!executionPage.hasMore" @click="loadHistory(executionPage.offset + executionPage.limit)">下一页</button></div>
        </footer>
      </div>
      <section v-else class="automation-empty compact"><div class="history-glyph">↻</div><h2>还没有执行记录</h2><p>任务第一次触发后，运行状态与关联 Chat 会显示在这里。</p></section>
    </main>

    <main v-else class="templates-panel">
      <section class="automation-empty"><div class="template-stack"><i></i><i></i><i></i></div><span class="coming-badge">COMING NEXT</span>
        <h2>任务模板正在整理</h2><p>后续会提供新闻简报、代码巡检和项目日报等可直接套用的模板。</p></section>
    </main>

    <Teleport to="body"><Transition name="automation-dialog">
      <div v-if="dialogOpen" class="automation-dialog-backdrop" @click.self="closeDialog">
        <section ref="dialog" class="automation-dialog" role="dialog" aria-modal="true" aria-labelledby="automationDialogTitle" @keydown="trapDialog">
          <header><div><span>{{ editingId ? 'EDIT AUTOMATION' : 'NEW AUTOMATION' }}</span><h2 id="automationDialogTitle">{{ editingId ? '编辑自动化任务' : '新建自动化任务' }}</h2></div>
            <button type="button" aria-label="关闭" @click="closeDialog"><svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6 6 18"/></svg></button></header>
          <div class="dialog-scroll">
            <section class="form-section identity-section"><span class="section-index">01</span><div class="section-copy"><h3>任务身份</h3><p>一个清晰的名称，以及每次执行都保持不变的目标。</p></div>
              <div class="section-fields"><label><span>任务名称</span><input ref="firstInput" v-model="form.name" maxlength="80" placeholder="例如：每日代码变更摘要"></label>
                <label class="prompt-field"><span>任务指令 <small>支持 @ 引用工作区文件</small></span>
                  <textarea v-model="form.prompt" maxlength="2000" rows="5" placeholder="描述目标、限制条件和期望结果……" @input="onPromptInput" @keydown="promptKeydown"></textarea>
                  <div v-if="mention" class="automation-mention" role="listbox">
                    <button v-for="(file, index) in filteredMentionFiles" :key="file.relativePath" type="button" role="option" :aria-selected="index === mentionIndex"
                            :class="{ active: index === mentionIndex }" @click="selectMention(file)"><strong>{{ file.name }}</strong><small>{{ file.relativePath }}</small></button>
                    <p v-if="!filteredMentionFiles.length">没有匹配的工作区文件</p>
                  </div>
                </label></div>
            </section>

            <section class="form-section"><span class="section-index">02</span><div class="section-copy"><h3>执行环境</h3><p>任务只会由这台桌面客户端读取所选工作区。</p></div>
              <div class="section-fields field-grid">
                <label><span>本地工作区</span><AppSelect :model-value="form.workspaceId" :options="workspaceOptions" placeholder="选择工作区" description-key="description" @update:model-value="onWorkspaceChanged" /></label>
                <label><span>执行模型</span><AppSelect v-model="form.modelId" :options="modelOptions" placeholder="选择已启用模型" description-key="description" /></label>
                <label><span>权限模式</span><AppSelect v-model="form.approvalMode" :options="permissionOptions" description-key="description" /></label>
                <label><span>任务时区</span><AppSelect v-model="form.timeZone" :options="timeZones" placeholder="选择时区" /></label>
              </div>
            </section>

            <section class="form-section"><span class="section-index">03</span><div class="section-copy"><h3>触发规则</h3><p>配置自然周期、Unix Cron，或从保存时刻开始的固定间隔。</p></div>
              <div class="section-fields schedule-fields">
                <div class="segmented" role="group" aria-label="触发类型"><button v-for="item in triggerTypes" :key="item.value" type="button" :class="{ active: form.triggerType === item.value }" @click="form.triggerType = item.value">{{ item.label }}</button></div>
                <template v-if="form.triggerType === 'PERIOD'">
                  <div class="segmented subtle" role="group" aria-label="周期模式"><button v-for="item in periodModes" :key="item.value" type="button" :class="{ active: form.periodMode === item.value }" @click="form.periodMode = item.value">{{ item.label }}</button></div>
                  <div v-if="form.periodMode === 'BASIC'" class="schedule-line">
                    <AppSelect v-model="form.periodUnit" :options="periodUnits" />
                    <AppSelect v-if="form.periodUnit === 'WEEKLY'" v-model="form.weekday" :options="weekdays" />
                    <AppSelect v-if="form.periodUnit === 'MONTHLY'" v-model="form.monthDay" :options="monthDays" />
                    <div class="time-field"><input v-model="form.hour" inputmode="numeric" maxlength="2" aria-label="小时" @blur="form.hour = normalizePart(form.hour, 23)"><b>:</b>
                      <input v-model="form.minute" inputmode="numeric" maxlength="2" aria-label="分钟" @blur="form.minute = normalizePart(form.minute, 59)"></div>
                  </div>
                  <label v-else class="cron-field"><span>Unix Cron · 分 时 日 月 周</span><input v-model="form.cron" maxlength="120" spellcheck="false" placeholder="0 9 * * 1-5"><small>支持 *、列表、范围和步长，例如工作日 09:00：0 9 * * 1-5</small></label>
                </template>
                <div v-else class="schedule-line interval-line"><span>每</span><input v-model.number="form.every" type="number" min="1" step="1" aria-label="间隔数量"><AppSelect v-model="form.intervalUnit" :options="intervalUnits" /></div>
                <div class="schedule-preview" :class="{ loading: previewLoading }"><span>未来三次</span><div v-if="preview"><strong>{{ preview.summary }}</strong><small v-for="time in preview.nextOccurrences" :key="time">{{ formatDate(time) }}</small></div><p v-else>{{ previewLoading ? '正在计算…' : '等待有效调度规则' }}</p></div>
              </div>
            </section>
          </div>
          <footer><div><button class="enabled-box" type="button" role="checkbox" :aria-checked="form.enabled" @click="form.enabled = !form.enabled"><i :class="{ checked: form.enabled }">✓</i><span><strong>创建后立即启用</strong><small>停用任务不会自动或手动执行</small></span></button>
              <p v-if="formError" role="alert">{{ formError }}</p></div>
            <div><button type="button" class="cancel-button" @click="closeDialog">取消</button><button type="button" class="save-button" :disabled="saving" @click="saveTask">{{ saving ? '保存中…' : editingId ? '保存修改' : '创建任务' }}</button></div></footer>
        </section>
      </div>
    </Transition></Teleport>

    <Teleport to="body"><Transition name="automation-dialog"><div v-if="deleteTarget" class="automation-dialog-backdrop" @click.self="deleteTarget = null">
      <section class="automation-confirm" role="alertdialog" aria-modal="true"><span>DELETE AUTOMATION</span><h2>删除“{{ deleteTarget.name }}”？</h2><p>任务配置会被移除，已产生的执行历史和 Chat 会话仍会保留。此操作不可撤销。</p>
        <div><button type="button" @click="deleteTarget = null">取消</button><button type="button" class="danger" @click="confirmDelete">删除任务</button></div></section>
    </div></Transition></Teleport>

    <Transition name="toast"><div v-if="toast" class="automation-toast" :class="{ danger: toast.startsWith('!') }" role="status">{{ toast.replace(/^!/, '') }}</div></Transition>
  </div>
</template>

<style scoped>
.automation-view { --ink:#11120f; --paper:#fbfbf8; --lime:#c8f04a; --green:#0b9b72; min-width:0; min-height:0; flex:1; overflow:auto; padding:46px clamp(28px,4vw,68px) 70px; color:var(--ink); background:radial-gradient(circle at 85% 4%,rgba(200,240,74,.11),transparent 25%),linear-gradient(180deg,#fff 0,#fafaf7 100%); }
.automation-hero { display:flex; align-items:flex-end; justify-content:space-between; gap:30px; max-width:1480px; margin:auto; }
.hero-eyebrow,.automation-dialog header span,.automation-confirm>span { font-family:var(--mono); font-size:10px; letter-spacing:.18em; color:#70736a; }
.automation-hero h1 { margin:8px 0 4px; font-size:clamp(38px,5vw,66px); line-height:.95; letter-spacing:-.055em; }
.automation-hero p { margin:15px 0 0; color:#64665f; font-size:15px; }
.create-automation { display:flex; min-height:48px; align-items:center; gap:10px; padding:0 20px; border:1px solid #161713; border-radius:5px; color:#fff; background:#171815; font-weight:650; cursor:pointer; box-shadow:5px 5px 0 var(--lime); }
.create-automation:disabled { opacity:.35; cursor:not-allowed; box-shadow:none; }.create-automation svg{width:18px;fill:none;stroke:currentColor;stroke-width:2}
.automation-tabs { display:flex; max-width:1480px; margin:42px auto 0; border-bottom:1px solid #d9dad4; }
.automation-tabs button { position:relative; display:flex; gap:8px; padding:15px 20px 14px 0; margin-right:28px; border:0; color:#777972; background:transparent; font-weight:650; cursor:pointer; }
.automation-tabs button span { min-width:20px; padding:2px 6px; border-radius:99px; background:#ecece7; font-family:var(--mono); font-size:9px; }.automation-tabs button.active{color:#111}.automation-tabs button.active:after{position:absolute;right:0;bottom:-1px;left:0;height:3px;background:#171815;content:""}
.desktop-status { display:grid; max-width:1480px; margin:28px auto 24px; padding:16px 18px; grid-template-columns:auto minmax(0,1fr) auto; align-items:center; gap:15px; border:1px solid #b9d8ce; border-radius:8px; background:#eef9f5; }.desktop-status.offline{border-color:#ddd5bf;background:#faf7ec}
.desktop-status-icon { position:relative; display:grid; width:38px;height:38px;place-items:center;border-radius:50%;background:#fff}.desktop-status-icon span{position:absolute;right:0;bottom:2px;width:9px;height:9px;border:2px solid #fff;border-radius:50%;background:var(--green)}.offline .desktop-status-icon span{background:#d09a28}.desktop-status-icon svg{width:19px;fill:none;stroke:#343630;stroke-width:1.7}
.desktop-status strong{font-size:13px}.desktop-status p{margin:3px 0 0;color:#64665f;font-size:11px}.wake-control{display:flex;align-items:center;gap:14px;padding-left:20px;border-left:1px solid rgba(0,0,0,.11)}.wake-control>span{display:grid;text-align:right}.wake-control small{margin-top:2px;color:#777;font-size:9px}
.wake-control button,.task-switch{position:relative;width:42px;height:24px;padding:0;border:0;border-radius:20px;background:#b9bbb4;cursor:pointer}.wake-control button i,.task-switch i{position:absolute;top:3px;left:3px;width:18px;height:18px;border-radius:50%;background:#fff;transition:transform .16s}.wake-control button.on,.task-switch.on{background:var(--green)}.wake-control button.on i,.task-switch.on i{transform:translateX(18px)}
.automation-error{max-width:1480px;margin:14px auto;padding:12px 14px;border-left:3px solid #cf4d40;background:#fff0ee;color:#8e2b22;font-size:12px}.configured-panel,.history-panel,.templates-panel{max-width:1480px;margin:auto}
.task-ledger{overflow:visible;border:1px solid #dedfd9;border-radius:9px;background:#fff;box-shadow:0 20px 50px rgba(35,36,31,.04)}.automation-row{position:relative;display:grid;min-height:90px;padding:17px 20px;grid-template-columns:82px minmax(240px,1fr) 150px 38px 42px 38px;align-items:center;gap:18px;border-bottom:1px solid #ecece7}.automation-row:last-child{border-bottom:0}.automation-row.disabled{background:#fafaf8;color:#777}
.task-signal{display:grid;justify-items:start;gap:5px;font-family:var(--mono);font-size:8px;letter-spacing:.12em}.task-signal i{width:8px;height:8px;border-radius:50%;background:#bbb}.task-signal i.live{background:var(--green);box-shadow:0 0 0 5px rgba(11,155,114,.1)}.task-copy h2{margin:0;font-size:16px;letter-spacing:-.02em}.task-copy p{display:flex;gap:9px;align-items:center;margin:8px 0 0;color:#6e7069;font-size:10px}.task-copy p span,.task-copy p b{padding:3px 7px;border-radius:4px;background:#f0f1ec;font-weight:500}.task-copy p b{color:#34362f;background:#eff7d9}.task-timing{display:grid;gap:5px}.task-timing small{color:#8a8c84;font-family:var(--mono);font-size:8px;letter-spacing:.1em}.task-timing strong{font-family:var(--mono);font-size:10px;font-weight:500}
.run-now,.automation-action-menu>button{display:grid;width:34px;height:34px;padding:0;place-items:center;border:1px solid #d4d5cf;border-radius:50%;background:#fff;cursor:pointer}.run-now:disabled{opacity:.3;cursor:not-allowed}.run-now svg{width:17px;fill:none;stroke:currentColor;stroke-width:1.8}.automation-action-menu{position:relative}.automation-action-menu>button{border:0;font-size:14px}.automation-action-menu>div{position:absolute;z-index:20;top:40px;right:0;width:140px;padding:5px;border:1px solid #ddd;border-radius:7px;background:#fff;box-shadow:0 14px 40px rgba(0,0,0,.13)}.automation-action-menu>div button{width:100%;padding:9px 10px;border:0;border-radius:4px;background:transparent;text-align:left;font-size:11px;cursor:pointer}.automation-action-menu>div button:hover{background:#f2f2ee}.automation-action-menu .danger{color:#bf3f35}
.automation-empty{display:grid;min-height:330px;place-items:center;align-content:center;text-align:center;border:1px dashed #ced0c7;border-radius:10px;background:rgba(255,255,255,.7)}.automation-empty h2{margin:22px 0 7px;font-size:22px}.automation-empty p{max-width:470px;margin:0;color:#73756d;font-size:12px;line-height:1.7}.automation-empty>button{margin-top:22px;padding:10px 16px;border:1px solid #222;border-radius:5px;background:#fff;font-weight:600;cursor:pointer}.automation-empty.compact{min-height:270px}.empty-orbit{position:relative;display:grid;width:78px;height:78px;place-items:center;border:1px solid #bec1b6;border-radius:50%}.empty-orbit:before{position:absolute;width:102px;height:34px;border:1px solid #d7d9d1;border-radius:50%;content:"";transform:rotate(-24deg)}.empty-orbit i{position:absolute;top:8px;right:8px;width:10px;height:10px;border-radius:50%;background:var(--lime)}.empty-orbit span{font-size:30px;font-weight:200}.template-stack{position:relative;width:80px;height:62px}.template-stack i{position:absolute;inset:0;border:1px solid #bfc1b9;border-radius:6px;background:#fff}.template-stack i:nth-child(1){transform:rotate(-8deg)}.template-stack i:nth-child(2){transform:rotate(5deg)}.template-stack i:nth-child(3){box-shadow:5px 5px 0 var(--lime)}.coming-badge{margin-top:25px;padding:4px 8px;border-radius:20px;background:#eef6d1;font-family:var(--mono);font-size:8px;letter-spacing:.12em}.history-glyph{font-size:40px;color:#afb1a8}
.automation-loading{display:flex;min-height:240px;align-items:center;justify-content:center;gap:10px;color:#777;font-size:12px}.automation-loading span{width:16px;height:16px;border:2px solid #ddd;border-top-color:#111;border-radius:50%;animation:spin .8s linear infinite}.history-table-wrap{overflow:hidden;border:1px solid #dedfd9;border-radius:9px;background:#fff}.history-table{width:100%;border-collapse:collapse;font-size:11px}.history-table th{padding:12px 15px;color:#777;background:#f5f5f1;font-family:var(--mono);font-size:8px;letter-spacing:.11em;text-align:left}.history-table td{padding:15px;border-top:1px solid #ecece8}.history-table td:first-child{display:grid;gap:4px}.history-table td small{color:#85877f}.history-table td:last-child{max-width:220px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.history-table td button{padding:0;border:0;color:#176b58;background:transparent;font-weight:600;cursor:pointer}.status-pill{display:inline-flex;padding:4px 7px;border-radius:99px;background:#ecece8;font-size:9px}.status-pill.completed{color:#087254;background:#e3f5ef}.status-pill.running,.status-pill.claimed,.status-pill.queued{color:#4c6300;background:#f0f8d3}.status-pill.failed,.status-pill.cancelled{color:#a33d34;background:#fae9e7}.status-pill.waiting{color:#8a5e07;background:#fff1cf}.history-pagination{display:flex;padding:12px 15px;align-items:center;justify-content:space-between;border-top:1px solid #e5e6e0;color:#777;font-size:10px}.history-pagination button{margin-left:6px;padding:6px 10px;border:1px solid #d7d8d2;border-radius:4px;background:#fff;font-size:10px;cursor:pointer}
.automation-dialog-backdrop{--paper:#fbfbf8;--lime:#c8f04a;position:fixed;z-index:100;inset:0;display:grid;padding:22px;place-items:center;color:#11120f;background:rgba(20,21,18,.55);backdrop-filter:blur(5px)}.automation-dialog{display:flex;width:min(1040px,96vw);max-height:94vh;flex-direction:column;overflow:hidden;border:1px solid #c9cbc2;border-radius:12px;background:var(--paper,#fbfbf8);box-shadow:0 30px 100px rgba(0,0,0,.3)}.automation-dialog>header{display:flex;padding:22px 25px;align-items:center;justify-content:space-between;border-bottom:1px solid #ddded7;background:#fff}.automation-dialog header h2{margin:5px 0 0;font-size:24px;letter-spacing:-.035em}.automation-dialog>header>button{display:grid;width:36px;height:36px;padding:0;place-items:center;border:1px solid #dedfd9;border-radius:50%;background:#fff;cursor:pointer}.automation-dialog header svg{width:18px;fill:none;stroke:currentColor;stroke-width:1.7}.dialog-scroll{overflow:auto}.form-section{display:grid;padding:28px 26px;grid-template-columns:48px 190px minmax(0,1fr);gap:18px;border-bottom:1px solid #e2e3dc}.section-index{display:grid;width:32px;height:32px;place-items:center;border:1px solid #d7d9d0;border-radius:50%;font-family:var(--mono);font-size:9px}.section-copy h3{margin:3px 0 7px;font-size:14px}.section-copy p{margin:0;color:#85877f;font-size:10px;line-height:1.55}.section-fields{display:grid;gap:15px}.section-fields label{position:relative;display:grid;gap:7px}.section-fields label>span,.cron-field>span{font-size:10px;font-weight:650}.section-fields label>span small{float:right;color:#8b8d84;font-size:8px;font-weight:400}.section-fields input,.section-fields textarea{width:100%;padding:11px 12px;border:1px solid #ced0c7;border-radius:6px;background:#fff;font-size:12px;resize:vertical}.section-fields textarea{min-height:105px;line-height:1.55}.field-grid{grid-template-columns:1fr 1fr}.field-grid label{min-width:0}.prompt-field{position:relative}.automation-mention{position:absolute;z-index:30;top:100%;right:0;left:0;max-height:230px;padding:6px;overflow:auto;border:1px solid #d3d5cc;border-radius:7px;background:#fff;box-shadow:0 20px 50px rgba(0,0,0,.14)}.automation-mention button{display:grid;width:100%;gap:3px;padding:8px 10px;border:0;border-radius:4px;background:transparent;text-align:left;cursor:pointer}.automation-mention button.active{background:#f0f4e1}.automation-mention small{color:#777;font-family:var(--mono);font-size:8px}.automation-mention p{padding:8px;color:#888;font-size:10px}.segmented{display:grid;padding:4px;grid-template-columns:1fr 1fr;border:1px solid #d8d9d2;border-radius:7px;background:#eeeFEa}.segmented button{padding:9px;border:0;border-radius:4px;color:#777;background:transparent;font-size:11px;cursor:pointer}.segmented button.active{color:#111;background:#fff;font-weight:650;box-shadow:0 1px 5px rgba(0,0,0,.08)}.segmented.subtle{width:260px}.schedule-line{display:flex;align-items:center;gap:10px}.schedule-line>:deep(.app-select){max-width:170px}.time-field{display:flex;height:40px;align-items:center;border:1px solid #ced0c7;border-radius:6px;background:#fff}.time-field input{width:42px;padding:0;border:0;text-align:center;font-family:var(--mono);font-size:13px}.time-field b{font-weight:400}.cron-field small{color:#7d7f77;font-size:9px}.interval-line>input{width:100px}.interval-line>:deep(.app-select){width:160px}.schedule-preview{display:grid;min-height:68px;padding:12px 14px;grid-template-columns:92px minmax(0,1fr);gap:10px;border-left:3px solid var(--lime,#c8f04a);background:#f3f6e8}.schedule-preview>span{font-family:var(--mono);font-size:9px;letter-spacing:.1em}.schedule-preview>div{display:flex;align-items:center;gap:9px;flex-wrap:wrap}.schedule-preview strong{width:100%;font-size:11px}.schedule-preview small{padding:3px 6px;border-radius:3px;background:#fff;font-family:var(--mono);font-size:8px}.automation-dialog>footer{display:flex;padding:17px 24px;align-items:center;justify-content:space-between;gap:20px;border-top:1px solid #dfe0da;background:#fff}.automation-dialog>footer>div{display:flex;align-items:center;gap:10px}.automation-dialog>footer>div:first-child{display:grid}.automation-dialog>footer p{margin:0;color:#b83f35;font-size:9px}.enabled-box{display:flex;align-items:center;gap:9px;padding:0;border:0;background:transparent;text-align:left;cursor:pointer}.enabled-box i{display:grid;width:18px;height:18px;place-items:center;border:1px solid #bbb;border-radius:4px;color:transparent;font-style:normal}.enabled-box i.checked{border-color:#111;color:#fff;background:#111}.enabled-box span{display:grid;gap:2px}.enabled-box strong{font-size:10px}.enabled-box small{color:#85877f;font-size:8px}.cancel-button,.save-button{min-height:39px;padding:0 17px;border:1px solid #cfd1c8;border-radius:5px;background:#fff;font-weight:600;cursor:pointer}.save-button{border-color:#171815;color:#fff;background:#171815;box-shadow:3px 3px 0 var(--lime,#c8f04a)}
.automation-confirm{width:min(430px,92vw);padding:28px;border-radius:10px;background:#fff;box-shadow:0 30px 80px rgba(0,0,0,.28)}.automation-confirm h2{margin:10px 0;font-size:20px}.automation-confirm p{color:#6d6f67;font-size:11px;line-height:1.6}.automation-confirm div{display:flex;justify-content:flex-end;gap:8px;margin-top:22px}.automation-confirm button{padding:9px 13px;border:1px solid #d2d3cd;border-radius:5px;background:#fff;cursor:pointer}.automation-confirm button.danger{border-color:#bf453b;color:#fff;background:#bf453b}.automation-toast{position:fixed;z-index:150;right:28px;bottom:28px;padding:12px 16px;border:1px solid #1b1c18;border-radius:6px;color:#fff;background:#1b1c18;font-size:11px;box-shadow:4px 4px 0 var(--lime)}.automation-toast.danger{background:#a63e35;box-shadow:4px 4px 0 #ffc2bb}
.automation-dialog-enter-active,.automation-dialog-leave-active,.toast-enter-active,.toast-leave-active{transition:opacity .16s ease}.automation-dialog-enter-active .automation-dialog,.automation-dialog-leave-active .automation-dialog{transition:transform .18s ease}.automation-dialog-enter-from,.automation-dialog-leave-to,.toast-enter-from,.toast-leave-to{opacity:0}.automation-dialog-enter-from .automation-dialog{transform:translateY(12px) scale(.985)}@keyframes spin{to{transform:rotate(360deg)}}
@media(max-width:900px){.automation-view{padding:32px 18px 60px}.desktop-status{grid-template-columns:auto 1fr}.wake-control{grid-column:1/-1;justify-content:flex-end;border-left:0;border-top:1px solid rgba(0,0,0,.1);padding:12px 0 0}.automation-row{grid-template-columns:65px 1fr auto auto}.task-timing{display:none}.automation-action-menu{grid-column:4}.form-section{grid-template-columns:38px 1fr}.section-copy{grid-column:2}.section-fields{grid-column:2}.history-table{min-width:760px}.history-table-wrap{overflow:auto}}@media(max-width:600px){.automation-hero{align-items:flex-start;flex-direction:column}.form-section{padding:22px 16px;grid-template-columns:1fr}.section-index,.section-copy,.section-fields{grid-column:1}.section-index{display:none}.field-grid{grid-template-columns:1fr}.automation-dialog>footer{align-items:stretch;flex-direction:column}.automation-dialog>footer>div:last-child{justify-content:flex-end}}
@media(prefers-reduced-motion:reduce){*{animation:none!important;transition:none!important}}
</style>
