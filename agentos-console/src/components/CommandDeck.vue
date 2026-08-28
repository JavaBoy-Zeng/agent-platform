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
  workspaceBusy: { type: Boolean, default: false },
  workspaceError: { type: String, default: '' }
})

const emit = defineEmits([
  'update:agentId', 'update:sessionId', 'update:prompt', 'update:selectedModelId',
  'update:approvalMode', 'add-model', 'upload', 'select-workspace', 'pick-workspace',
  'clear-workspace', 'run', 'stop'
])

const promptInput = ref(null)
const deck = ref(null)
const modelApiIdInput = ref(null)
const openMenu = ref('')
const addingModel = ref(false)
const modelApiId = ref('')

const selectedModel = computed(() =>
  props.models.find(model => model.id === props.selectedModelId)
  || props.models[0]
  || { id: 'minimax-h3', name: t('Server 默认模型'), modelId: '' })
const selectedModelLabel = computed(() => selectedModel.value.modelId || selectedModel.value.name)

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
  nextTick(scrollToTop)
}

function onKeydown(event) {
  if (event.key !== 'Enter' || event.isComposing || event.keyCode === 229 || event.shiftKey) return
  event.preventDefault()
  if (!props.busy && props.prompt.trim()) emit('run')
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
      <label for="promptInput">{{ t('任务指令') }}</label>
      <textarea id="promptInput" ref="promptInput" :value="prompt" rows="3" maxlength="2000"
                :placeholder="t('描述目标、限制条件和期望结果……')" required
                @input="onInput" @keydown="onKeydown"></textarea>
    </div>

    <div v-if="attachments.length || uploadError" class="attachment-strip" aria-live="polite">
      <span v-for="attachment in attachments" :key="attachment.relativePath" class="attachment-chip">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M8.5 12.5l5.9-5.9a3 3 0 014.2 4.2l-7.3 7.3a5 5 0 01-7.1-7.1l7-7" /></svg>{{ attachment.name }}
      </span>
      <span v-if="uploadError" class="attachment-error">{{ uploadError }}</span>
    </div>

    <div class="deck-actions">
      <div class="deck-left-actions">
        <button class="deck-icon-button attach-button" type="button" :disabled="uploading || !uploadAvailable"
                :aria-label="t('上传附件到共奏目录')" :title="uploadAvailable ? t('上传附件到共奏目录') : t('附件上传仅在桌面端可用')"
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
