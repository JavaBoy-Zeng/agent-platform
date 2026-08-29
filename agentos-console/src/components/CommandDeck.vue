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
  models: { type: Array, default: () => [] },
  selectedModelId: { type: String, default: 'minimax-h3' },
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
  workspaceError: { type: String, default: '' }
})

const emit = defineEmits([
  'update:agentId', 'update:sessionId', 'update:prompt', 'update:selectedModelId',
  'update:approvalMode', 'add-model', 'upload', 'select-workspace', 'pick-workspace',
  'clear-workspace', 'remove-attachment', 'run', 'stop'
])

const promptInput = ref(null)
const deck = ref(null)
const modelApiIdInput = ref(null)
const openMenu = ref('')
const addingModel = ref(false)
const modelApiId = ref('')
const mentionState = ref(null)
const mentionActiveIndex = ref(0)

const selectedModel = computed(() =>
  props.models.find(model => model.id === props.selectedModelId)
  || props.models[0]
  || { id: 'minimax-h3', name: t('Server 默认模型'), modelId: '' })
const selectedModelLabel = computed(() => selectedModel.value.modelId || selectedModel.value.name)
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
  if (!props.busy && props.prompt.trim()) emit('run')
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

function toggleMenu(name) {
  openMenu.value = openMenu.value === name ? '' : name
}

function selectPermission(id) {
  emit('update:approvalMode', id)
  openMenu.value = ''
}

function selectModel(id) {
  emit('update:selectedModelId', id)
  openMenu.value = ''
}

function showAddModel() {
  openMenu.value = ''
  addingModel.value = true
  modelApiId.value = ''
  nextTick(() => modelApiIdInput.value?.focus())
}

function closeAddModel() {
  addingModel.value = false
  modelApiId.value = ''
}

function submitModel() {
  const modelId = modelApiId.value.trim()
  if (!modelId) return
  emit('add-model', { modelId })
  closeAddModel()
}

function closeMenus(event) {
  if (!deck.value?.contains(event.target)) openMenu.value = ''
}

function onEscape(event) {
  if (event.key !== 'Escape') return
  if (mentionState.value) closeMention()
  if (addingModel.value) closeAddModel()
  else openMenu.value = ''
}

watch(() => props.prompt, () => nextTick(scrollToTop))
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
  <form ref="deck" class="command-deck" @submit.prevent="$emit('run')">
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
      </div>

      <div class="deck-right-actions">
        <div class="deck-menu-wrap model-wrap">
          <button class="model-trigger" type="button" aria-haspopup="listbox" :aria-expanded="openMenu === 'model'"
                  @pointerdown.stop @click="toggleMenu('model')">
            <span>{{ selectedModelLabel }}</span><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M7 9l5 5 5-5" /></svg>
          </button>
          <div v-if="openMenu === 'model'" class="deck-popover model-menu" role="listbox" :aria-label="t('模型管理')">
            <header><strong>{{ t('模型管理') }}</strong><small>{{ t('选择本次任务使用的模型') }}</small></header>
            <button v-for="model in models" :key="model.id" type="button" role="option"
                    :aria-selected="selectedModelId === model.id" :class="{ selected: selectedModelId === model.id }"
                    @click="selectModel(model.id)">
              <span><strong>{{ model.modelId || model.name }}</strong><small>{{ model.modelId ? t('厂商模型 ID') : t('跟随 Server 当前模型') }}</small></span>
              <svg v-if="selectedModelId === model.id" class="menu-check" viewBox="0 0 24 24" aria-hidden="true"><path d="M5 12l4 4L19 6" /></svg>
            </button>
            <footer><button type="button" @click="showAddModel"><span>＋</span>{{ t('添加模型') }}</button></footer>
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
      @clear="$emit('clear-workspace')"
    />
  </form>

  <Teleport to="body">
    <div v-if="addingModel" class="app-dialog-backdrop" @pointerdown.self="closeAddModel">
      <section class="model-dialog" role="dialog" aria-modal="true" aria-labelledby="addModelTitle">
        <header><div><small>MODEL REGISTRY</small><h2 id="addModelTitle">{{ t('添加模型') }}</h2></div><button type="button" :aria-label="t('关闭')" @click="closeAddModel">×</button></header>
        <form @submit.prevent="submitModel">
          <label><span>{{ t('厂商模型 ID') }}</span><input ref="modelApiIdInput" v-model="modelApiId" type="text" maxlength="160" placeholder="MiniMax-M2.1" autocomplete="off"><small>{{ t('请填写当前 Server 模型端点支持的真实模型 ID。') }}</small></label>
          <footer><button type="button" @click="closeAddModel">{{ t('取消') }}</button><button class="dialog-primary" type="submit" :disabled="!modelApiId.trim()">{{ t('添加并使用') }}</button></footer>
        </form>
      </section>
    </div>
  </Teleport>
</template>
