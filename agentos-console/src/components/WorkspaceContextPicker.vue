<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { useLocale } from '../composables/useLocale.js'

const { t } = useLocale()
const props = defineProps({
  available: { type: Boolean, default: false },
  workspaces: { type: Array, default: () => [] },
  currentWorkspace: { type: Object, default: null },
  busy: { type: Boolean, default: false },
  error: { type: String, default: '' }
})
const emit = defineEmits(['select', 'pick', 'clear'])

const root = ref(null)
const menu = ref(null)
const open = ref(false)
const recentWorkspaces = computed(() => props.workspaces.slice(0, 6))

function close() {
  open.value = false
}

async function toggle(event) {
  if (event?.type === 'keydown' && !['ArrowDown', 'Enter', ' '].includes(event.key)) return
  event?.preventDefault?.()
  if (props.busy) return
  open.value = !open.value
  if (open.value && ['ArrowDown', 'Enter', ' '].includes(event?.key)) {
    await nextTick()
    menu.value?.querySelector('[role="menuitem"]')?.focus()
  }
}

function select(workspace) {
  emit('select', workspace.id)
  close()
}

function pick() {
  emit('pick')
  close()
}

function clear() {
  emit('clear')
  close()
}

function navigate(event) {
  const items = [...(menu.value?.querySelectorAll('[role="menuitem"]:not(:disabled)') || [])]
  if (!items.length) return
  const index = items.indexOf(document.activeElement)
  if (event.key === 'Escape') {
    event.preventDefault()
    close()
    root.value?.querySelector('.workspace-context-trigger')?.focus()
    return
  }
  if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return
  event.preventDefault()
  const next = event.key === 'Home' ? 0
    : event.key === 'End' ? items.length - 1
      : event.key === 'ArrowDown' ? (index + 1) % items.length
        : (index - 1 + items.length) % items.length
  items[next].focus()
}

function closeOutside(event) {
  if (!root.value?.contains(event.target)) close()
}

function onEscape(event) {
  if (event.key === 'Escape') close()
}

onMounted(() => {
  document.addEventListener('pointerdown', closeOutside)
  document.addEventListener('keydown', onEscape)
})
onUnmounted(() => {
  document.removeEventListener('pointerdown', closeOutside)
  document.removeEventListener('keydown', onEscape)
})
</script>

<template>
  <div v-if="available" ref="root" class="workspace-context-row" :class="{ 'has-workspace': currentWorkspace }">
    <span class="workspace-location" :title="t('任务将在本机工作区中运行')">
      <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 5h16v12H4zM8 21h8M12 17v4" /></svg>
      {{ t('本地') }}
    </span>

    <div class="workspace-context-picker">
      <button
        class="workspace-context-trigger"
        type="button"
        :disabled="busy"
        aria-haspopup="menu"
        :aria-expanded="open"
        :title="currentWorkspace?.root || t('选择文件夹（可选）')"
        @pointerdown.stop
        @click="toggle"
        @keydown="toggle"
      >
        <span v-if="busy" class="mini-loader" aria-hidden="true"></span>
        <svg v-else viewBox="0 0 24 24" aria-hidden="true"><path d="M3 7h7l2 2h9v10H3zM7 7V5h5" /></svg>
        <span>{{ currentWorkspace?.name || t('选择文件夹（可选）') }}</span>
        <svg class="workspace-context-chevron" :class="{ open }" viewBox="0 0 24 24" aria-hidden="true"><path d="M7 9l5 5 5-5" /></svg>
      </button>

      <div v-if="open" ref="menu" class="composer-workspace-menu" role="menu" :aria-label="t('选择任务文件夹')" @keydown="navigate">
        <header>
          <small>WORKSPACE</small>
          <strong>{{ t('最近') }}</strong>
        </header>
        <button
          v-for="workspace in recentWorkspaces"
          :key="workspace.id"
          type="button"
          role="menuitem"
          :class="{ selected: workspace.id === currentWorkspace?.id }"
          @click="select(workspace)"
        >
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M3 7h7l2 2h9v10H3z" /></svg>
          <span><strong>{{ workspace.name }}</strong><small>{{ workspace.root }}</small></span>
          <svg v-if="workspace.id === currentWorkspace?.id" class="menu-check" viewBox="0 0 24 24" aria-hidden="true"><path d="M5 12l4 4L19 6" /></svg>
        </button>
        <p v-if="!recentWorkspaces.length" class="workspace-menu-empty">{{ t('还没有最近使用的文件夹') }}</p>
        <footer>
          <button type="button" role="menuitem" @click="pick">
            <span aria-hidden="true">＋</span>{{ t('选择其他文件夹') }}
          </button>
          <button v-if="currentWorkspace" type="button" role="menuitem" @click="clear">
            {{ t('不使用文件夹') }}
          </button>
        </footer>
      </div>
    </div>

    <span v-if="error" class="workspace-context-error" role="status" :title="error">{{ error }}</span>
  </div>
</template>
