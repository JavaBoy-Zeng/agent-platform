<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useLocale } from '../composables/useLocale.js'
import WorkspaceContextPicker from './WorkspaceContextPicker.vue'

const { t } = useLocale()

const props = defineProps({
  agentId: { type: String, required: true },
  sessionId: { type: String, required: true },
  prompt: { type: String, required: true },
  busy: { type: Boolean, default: false },
  canStop: { type: Boolean, default: false },
  currentModel: {
    type: Object,
    default: null
  },
  models: { type: Array, default: () => [] },
  selectedModelKey: { type: String, default: '' },
  approvalMode: { type: String, default: 'FULL_ACCESS' },
  attachments: { type: Array, default: () => [] },
  uploading: { type: Boolean, default: false },
  uploadAvailable: { type: Boolean, default: false },
  uploadError: { type: String, default: '' },
  workspaceAvailable: { type: Boolean, default: false },
  workspaces: { type: Array, default: () => [] },
  currentWorkspace: { type: Object, default: null },
  workspaceFiles: { type: Array, default: () => [] },
  workspaceFilesLoading: { type: Boolean, default: false },
  workspaceBusy: { type: Boolean, default: false },
  workspaceError: { type: String, default: '' },
  gitBranches: { type: Array, default: () => [] },
  gitBranchLoading: { type: Boolean, default: false },
  gitBranchError: { type: String, default: '' }
})

const emit = defineEmits([
  'update:agentId', 'update:sessionId', 'update:prompt',
  'update:selectedModelKey',
  'update:approvalMode', 'manage-models', 'upload', 'select-workspace', 'pick-workspace',
  'refresh-branches', 'select-branch', 'remove-attachment', 'run', 'stop'
])

const promptInput = ref(null)
const deck = ref(null)
const branchMenu = ref(null)
const branchSearchInput = ref(null)
const openMenu = ref('')
const mentionState = ref(null)
const mentionActiveIndex = ref(0)
const branchQuery = ref('')
const modelSelectionError = ref(false)

const selectedModelLabel = computed(() => props.currentModel?.name || t('选择模型'))
const builtInModels = computed(() => props.models.filter(model => model.modelType === 'BUILT_IN'))
const customModels = computed(() => props.models.filter(model => model.modelType === 'CUSTOM'))
const currentBranch = computed(() => props.gitBranches.find(branch => branch.current) || null)
const filteredBranches = computed(() => {
  const query = branchQuery.value.trim().toLocaleLowerCase()
  if (!query) return props.gitBranches
  return props.gitBranches.filter(branch => branch.name.toLocaleLowerCase().includes(query))
})
const mentionCandidates = computed(() => {
  const query = mentionState.value?.query?.toLowerCase() || ''
  return props.workspaceFiles
    .filter(file => !query
      || file.relativePath.toLowerCase().includes(query)
      || file.name.toLowerCase().includes(query))
    .sort((left, right) => {
      const leftPath = left.relativePath.toLowerCase()
      const rightPath = right.relativePath.toLowerCase()
      const leftName = left.name.toLowerCase()
      const rightName = right.name.toLowerCase()
      const leftRank = leftName.startsWith(query) ? 0 : leftPath.startsWith(query) ? 1 : 2
      const rightRank = rightName.startsWith(query) ? 0 : rightPath.startsWith(query) ? 1 : 2
      return leftRank - rightRank || leftPath.localeCompare(rightPath)
    })
    .slice(0, 10)
})

const permissionOptions = computed(() => [
  { id: 'REQUEST_APPROVAL', label: t('请求批准'), description: t('编辑外部文件和使用互联网时始终询问') },
  { id: 'RISK_BASED', label: t('帮我批准'), description: t('仅对检测到的风险操作请求批准') },
  { id: 'FULL_ACCESS', label: t('完全访问权限'), description: t('可不受限制地访问互联网和共奏目录中的文件') }
])

const selectedPermission = computed(() =>
  permissionOptions.value.find(option => option.id === props.approvalMode)
  || permissionOptions.value[2])

function scrollToTop() {
  if (promptInput.value) promptInput.value.scrollTop = 0
}

function onInput(event) {
  emit('update:prompt', event.target.value)
  updateMentionState(event.target.value, event.target.selectionStart)
  nextTick(scrollToTop)
}

function onKeydown(event) {
  if (mentionState.value) {
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault()
      const count = mentionCandidates.value.length
      if (count) {
        mentionActiveIndex.value = event.key === 'ArrowDown'
          ? (mentionActiveIndex.value + 1) % count
          : (mentionActiveIndex.value - 1 + count) % count
      }
      return
    }
    if ((event.key === 'Enter' || event.key === 'Tab') && mentionCandidates.value.length) {
      event.preventDefault()
      selectMention(mentionCandidates.value[mentionActiveIndex.value] || mentionCandidates.value[0])
      return
    }
    if (event.key === 'Escape') {
      event.preventDefault()
      closeMention()
      return
    }
    if (event.key === 'Enter') {
      event.preventDefault()
      return
    }
  }
  if (event.key !== 'Enter' || event.isComposing || event.keyCode === 229 || event.shiftKey) return
  event.preventDefault()
  if (!props.busy && props.prompt.trim()) requestRun()
}

function updateMentionState(value, cursor) {
  const beforeCursor = String(value || '').slice(0, cursor ?? 0)
  const match = beforeCursor.match(/(?:^|[\s([{，。！？,])@([^@\s]*)$/)
  if (!match) {
    closeMention()
    return
  }
  mentionState.value = {
    query: match[1] || '',
    start: beforeCursor.length - (match[1]?.length || 0) - 1,
    cursor: cursor ?? beforeCursor.length
  }
  mentionActiveIndex.value = 0
}

function syncMentionFromCursor() {
  const input = promptInput.value
  if (input) updateMentionState(input.value, input.selectionStart)
}

function closeMention() {
  mentionState.value = null
  mentionActiveIndex.value = 0
}

function selectMention(file) {
  const input = promptInput.value
  const state = mentionState.value
  if (!input || !state || !file) return
  const cursor = input.selectionStart ?? state.cursor
  const reference = `@${file.relativePath}`
  const separator = ' '
  const nextValue = input.value.slice(0, state.start) + reference + separator + input.value.slice(cursor)
  const nextCursor = state.start + reference.length + separator.length
  emit('update:prompt', nextValue)
  closeMention()
  nextTick(() => {
    promptInput.value?.focus()
    promptInput.value?.setSelectionRange(nextCursor, nextCursor)
  })
}

async function toggleMenu(name) {
  const opening = openMenu.value !== name
  openMenu.value = opening ? name : ''
  if (name === 'branch' && opening) {
    branchQuery.value = ''
    emit('refresh-branches')
    await nextTick()
    branchSearchInput.value?.focus()
  }
}

function selectPermission(id) {
  emit('update:approvalMode', id)
  openMenu.value = ''
}

function selectModel(key) {
  emit('update:selectedModelKey', key)
  modelSelectionError.value = false
  openMenu.value = ''
}

function requestRun() {
  if (props.busy || !props.prompt.trim()) return
  if (!props.currentModel?.id) {
    modelSelectionError.value = true
    openMenu.value = 'model'
    nextTick(() => deck.value?.querySelector('.model-option-list [role="option"]')?.focus())
    return
  }
  modelSelectionError.value = false
  emit('run')
}

function selectBranch(name) {
  if (!name || currentBranch.value?.name === name) return
  emit('select-branch', name)
}

function focusFirstBranch() {
  branchMenu.value?.querySelector('.branch-option')?.focus()
}

function navigateBranches(event) {
  if (event.key === 'Escape') {
    event.preventDefault()
    openMenu.value = ''
    deck.value?.querySelector('.branch-trigger')?.focus()
    return
  }
  if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return
  const items = [...(branchMenu.value?.querySelectorAll('.branch-option:not(:disabled)') || [])]
  if (!items.length) return
  event.preventDefault()
  const index = items.indexOf(document.activeElement)
  const next = event.key === 'Home' ? 0
    : event.key === 'End' ? items.length - 1
      : event.key === 'ArrowDown' ? (index + 1) % items.length
        : (index - 1 + items.length) % items.length
  items[next].focus()
}

function showModelManagement() {
  openMenu.value = ''
  emit('manage-models')
}

function closeMenus(event) {
  if (!deck.value?.contains(event.target)) openMenu.value = ''
}

function onEscape(event) {
  if (event.key !== 'Escape') return
  if (mentionState.value) closeMention()
  else openMenu.value = ''
}

watch(() => props.prompt, () => nextTick(scrollToTop))
watch(() => currentBranch.value?.name, (next, previous) => {
  if (previous && next && previous !== next && openMenu.value === 'branch') openMenu.value = ''
})
onMounted(() => {
  document.addEventListener('pointerdown', closeMenus)
  document.addEventListener('keydown', onEscape)
})
onUnmounted(() => {
  document.removeEventListener('pointerdown', closeMenus)
  document.removeEventListener('keydown', onEscape)
})
</script>

<template>
  <form ref="deck" class="command-deck" @submit.prevent="requestRun">
    <div class="deck-topline">
      <label class="identity-field"><span>AGENT</span><input :value="agentId" autocomplete="off" aria-label="Agent ID" @input="$emit('update:agentId', $event.target.value)"></label>
      <label class="identity-field session-field"><span>SESSION</span><input :value="sessionId" autocomplete="off" aria-label="Session ID" @input="$emit('update:sessionId', $event.target.value)"></label>
    </div>

    <div class="prompt-frame">
      <div v-if="mentionState" id="projectFileMentions" class="file-mention-menu" role="listbox" :aria-label="t('引用项目文件')">
        <header>
          <span class="mention-at" aria-hidden="true">@</span>
          <span><strong>{{ t('引用项目文件') }}</strong><small>{{ currentWorkspace?.name || t('未选择项目目录') }}</small></span>
          <kbd>↑↓</kbd><kbd>↵</kbd>
        </header>
        <div v-if="workspaceFilesLoading" class="mention-menu-state" role="status">
          <span class="mini-loader" aria-hidden="true"></span>{{ t('正在读取项目文件…') }}
        </div>
        <template v-else-if="currentWorkspace && mentionCandidates.length">
          <button v-for="(file, index) in mentionCandidates" :id="`projectFileMention-${index}`" :key="file.relativePath"
                  type="button" role="option" :aria-selected="index === mentionActiveIndex"
                  :class="{ active: index === mentionActiveIndex }"
                  @pointerdown.prevent @mouseenter="mentionActiveIndex = index" @click="selectMention(file)">
            <span class="mention-file-glyph" aria-hidden="true">{{ file.name.includes('.') ? file.name.split('.').pop().slice(0, 3) : 'TXT' }}</span>
            <span><strong>{{ file.name }}</strong><small>{{ file.relativePath }}</small></span>
            <span class="mention-file-language">{{ file.language }}</span>
          </button>
        </template>
        <div v-else class="mention-menu-state">
          {{ currentWorkspace ? t('没有匹配的项目文件') : t('请先选择项目目录') }}
        </div>
      </div>
      <label for="promptInput">{{ t('任务指令') }}</label>
      <textarea id="promptInput" ref="promptInput" :value="prompt" rows="3" maxlength="2000"
                :placeholder="t('描述目标、限制条件和期望结果……')" required
                :aria-expanded="Boolean(mentionState)" aria-controls="projectFileMentions"
                :aria-activedescendant="mentionState && mentionCandidates.length ? `projectFileMention-${mentionActiveIndex}` : undefined"
                @input="onInput" @click="syncMentionFromCursor" @keydown="onKeydown"></textarea>
    </div>

    <div v-if="attachments.length || uploadError" class="attachment-strip" aria-live="polite">
      <span v-for="attachment in attachments" :key="attachment.relativePath" class="attachment-chip">
        <svg class="attachment-icon" viewBox="0 0 24 24" aria-hidden="true"><path d="M8.5 12.5l5.9-5.9a3 3 0 014.2 4.2l-7.3 7.3a5 5 0 01-7.1-7.1l7-7" /></svg>
        <span class="attachment-name" :title="attachment.name">{{ attachment.name }}</span>
        <button class="attachment-remove" type="button"
                :aria-label="t('移除附件：{filename}', { filename: attachment.name })"
                :title="t('移除附件')" @click="$emit('remove-attachment', attachment)">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M7 7l10 10M17 7L7 17" /></svg>
        </button>
      </span>
      <span v-if="uploadError" class="attachment-error">{{ uploadError }}</span>
    </div>

    <p v-if="modelSelectionError" class="model-selection-error" role="alert">{{ t('请先选择一个已启用的模型') }}</p>

    <div class="deck-actions">
      <div class="deck-left-actions">
        <button class="deck-icon-button attach-button" type="button" :disabled="uploading || !uploadAvailable"
                :aria-label="t('上传附件')" :title="uploadAvailable ? t('上传附件') : t('附件上传仅在桌面端可用')"
                @click="$emit('upload')">
          <span v-if="uploading" class="mini-loader" aria-hidden="true"></span>
          <svg v-else viewBox="0 0 24 24" aria-hidden="true"><path d="M12 4v16M4 12h16" /></svg>
        </button>

        <div class="deck-menu-wrap permission-wrap">
          <button class="permission-trigger" :class="{ elevated: approvalMode === 'FULL_ACCESS' }" type="button"
                  aria-haspopup="menu" :aria-expanded="openMenu === 'permission'"
                  @pointerdown.stop @click="toggleMenu('permission')">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3l8 3v5c0 5-3.4 8.3-8 10-4.6-1.7-8-5-8-10V6l8-3zM12 8v5M12 16h.01" /></svg>
            <span>{{ selectedPermission.label }}</span>
          </button>
          <div v-if="openMenu === 'permission'" class="deck-popover permission-menu" role="menu">
            <button v-for="option in permissionOptions" :key="option.id" type="button" role="menuitemradio"
                    :aria-checked="approvalMode === option.id" :class="{ selected: approvalMode === option.id }"
                    @click="selectPermission(option.id)">
              <span class="permission-mark" aria-hidden="true">{{ option.id === 'REQUEST_APPROVAL' ? '✋' : option.id === 'RISK_BASED' ? '◉' : '!' }}</span>
              <span><strong>{{ option.label }}</strong><small>{{ option.description }}</small></span>
              <svg v-if="approvalMode === option.id" class="menu-check" viewBox="0 0 24 24" aria-hidden="true"><path d="M5 12l4 4L19 6" /></svg>
            </button>
          </div>
        </div>

        <div class="deck-menu-wrap branch-wrap">
          <button class="branch-trigger" type="button"
                  :disabled="!currentWorkspace?.gitRepository"
                  aria-haspopup="listbox" :aria-expanded="openMenu === 'branch'"
                  :title="currentWorkspace?.gitRepository ? t('选择 Git 分支') : t('当前目录不是 Git 仓库')"
                  @pointerdown.stop @click="toggleMenu('branch')">
            <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="6" cy="5" r="2"/><circle cx="6" cy="19" r="2"/><circle cx="18" cy="7" r="2"/><path d="M6 7v10M8 17c5 0 8-3 8-8"/></svg>
            <span>{{ currentBranch?.name || (currentWorkspace?.gitRepository ? t('分支') : t('无 Git')) }}</span>
            <svg class="branch-chevron" viewBox="0 0 24 24" aria-hidden="true"><path d="M7 9l5 5 5-5"/></svg>
          </button>
          <div v-if="openMenu === 'branch'" ref="branchMenu" class="deck-popover branch-menu"
               role="listbox" :aria-label="t('选择 Git 分支')" @keydown="navigateBranches">
            <header>
              <span><strong>{{ t('Git 分支') }}</strong><small>{{ currentWorkspace?.name }}</small></span>
              <span v-if="gitBranchLoading" class="mini-loader" aria-hidden="true"></span>
            </header>
            <label class="branch-search">
              <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="11" cy="11" r="6"/><path d="m16 16 4 4"/></svg>
              <span class="sr-only">{{ t('搜索分支') }}</span>
              <input ref="branchSearchInput" v-model="branchQuery" type="search" autocomplete="off"
                     :placeholder="t('搜索本地分支')" @keydown.down.prevent="focusFirstBranch">
            </label>
            <p v-if="gitBranchError" class="branch-menu-error" role="status">{{ gitBranchError }}</p>
            <button v-for="branch in filteredBranches" :key="branch.name" class="branch-option"
                    type="button" role="option" :aria-selected="branch.current"
                    :class="{ selected: branch.current }" :disabled="gitBranchLoading"
                    @click="selectBranch(branch.name)">
              <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="6" cy="5" r="2"/><circle cx="6" cy="19" r="2"/><circle cx="18" cy="7" r="2"/><path d="M6 7v10M8 17c5 0 8-3 8-8"/></svg>
              <span>{{ branch.name }}</span>
              <svg v-if="branch.current" class="menu-check" viewBox="0 0 24 24" aria-hidden="true"><path d="M5 12l4 4L19 6" /></svg>
            </button>
            <p v-if="!gitBranchLoading && !gitBranchError && !filteredBranches.length" class="branch-menu-empty">
              {{ branchQuery ? t('没有匹配的分支') : t('没有本地分支') }}
            </p>
          </div>
        </div>
      </div>

      <div class="deck-right-actions">
        <div class="deck-menu-wrap model-wrap">
          <button class="model-trigger" :class="{ invalid: modelSelectionError }" type="button" aria-haspopup="listbox" :aria-expanded="openMenu === 'model'"
                  @pointerdown.stop @click="toggleMenu('model')">
            <span>{{ selectedModelLabel }}</span><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M7 9l5 5 5-5" /></svg>
          </button>
          <div v-if="openMenu === 'model'" class="deck-popover model-menu" :aria-label="t('模型管理')">
            <header><strong>{{ t('选择本次任务模型') }}</strong><small>{{ t('任务的规划、工具调用与最终回答都使用该模型') }}</small></header>
            <div class="model-option-list" role="listbox" :aria-label="t('选择本次任务模型')">
              <template v-for="group in [{ type: 'BUILT_IN', label: t('内置模型'), items: builtInModels }, { type: 'CUSTOM', label: t('自定义模型'), items: customModels }]" :key="group.type">
                <p v-if="group.items.length" class="model-group-label">{{ group.label }} <span>{{ group.items.length }}</span></p>
                <button v-for="model in group.items" :key="model.key" type="button" role="option"
                        :aria-selected="selectedModelKey === model.key" :class="{ selected: selectedModelKey === model.key }"
                        @click="selectModel(model.key)">
                  <span class="model-option-mark" aria-hidden="true">{{ (model.providerType || model.provider || 'AI').slice(0, 2).toUpperCase() }}</span>
                  <span><strong>{{ model.name }}</strong><small>{{ model.provider }} · {{ model.providerType }}</small></span>
                  <svg v-if="selectedModelKey === model.key" class="menu-check" viewBox="0 0 24 24" aria-hidden="true"><path d="M5 12l4 4L19 6" /></svg>
                </button>
              </template>
              <p v-if="!models.length" class="model-menu-empty" role="status">{{ t('没有已启用的模型，请先前往模型管理添加或启用模型') }}</p>
            </div>
            <footer><button type="button" @click="showModelManagement"><span>↗</span>{{ t('管理模型') }}</button></footer>
          </div>
        </div>

        <button v-if="busy && canStop" class="stop-button" type="button" :aria-label="t('停止任务')" @click="$emit('stop')"><span class="stop-icon" aria-hidden="true"></span></button>
        <button v-else class="run-button" type="submit" :disabled="busy || !prompt.trim()" :aria-label="t('执行任务')">
          <svg v-if="!busy" viewBox="0 0 24 24" aria-hidden="true"><path d="M12 19V5M6.5 10.5L12 5l5.5 5.5" /></svg>
          <span v-else class="run-loader" aria-hidden="true"><i></i><i></i><i></i></span>
        </button>
      </div>
    </div>

    <WorkspaceContextPicker
      :available="workspaceAvailable"
      :workspaces="workspaces"
      :current-workspace="currentWorkspace"
      :busy="workspaceBusy"
      :error="workspaceError"
      @select="$emit('select-workspace', $event)"
      @pick="$emit('pick-workspace')"
    />
  </form>

</template>
