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
const emit = defineEmits(['select', 'pick'])

const root = ref(null)
const menu = ref(null)
const searchInput = ref(null)
const open = ref(false)
const query = ref('')
const visibleWorkspaces = computed(() => {
  const normalized = query.value.trim().toLocaleLowerCase()
  if (!normalized) return props.workspaces.slice(0, 6)
  return props.workspaces.filter(workspace =>
    String(workspace.name || '').toLocaleLowerCase().includes(normalized)
      || String(workspace.root || '').toLocaleLowerCase().includes(normalized))
})

function close() {
  open.value = false
  query.value = ''
}

async function toggle(event) {
  if (event?.type === 'keydown' && !['ArrowDown', 'Enter', ' '].includes(event.key)) return
  event?.preventDefault?.()
  if (props.busy) return
  open.value = !open.value
  if (open.value) {
    await nextTick()
    searchInput.value?.focus()
  }
}

function focusFirstWorkspace() {
  menu.value?.querySelector('.workspace-result')?.focus()
}

function select(workspace) {
  emit('select', workspace.id)
  close()
}

function pick() {
  emit('pick')
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

    <div v-if="currentWorkspace" class="workspace-context-fixed" :title="currentWorkspace.root">
      <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M3 7h7l2 2h9v10H3z" /></svg>
      <span><strong>{{ currentWorkspace.name }}</strong><small>{{ currentWorkspace.root }}</small></span>
      <svg class="workspace-lock" viewBox="0 0 24 24" aria-hidden="true"><path d="M7 11V8a5 5 0 0 1 10 0v3M5 11h14v10H5z" /></svg>
      <span class="sr-only">{{ t('任务目录已锁定，不允许修改') }}</span>
    </div>

    <div v-else class="workspace-context-picker">
      <button
        class="workspace-context-trigger"
        type="button"
        :disabled="busy"
        aria-haspopup="menu"
        :aria-expanded="open"
        :title="t('首次选择后不可修改')"
        @pointerdown.stop
        @click="toggle"
        @keydown="toggle"
      >
        <span v-if="busy" class="mini-loader" aria-hidden="true"></span>
        <svg v-else viewBox="0 0 24 24" aria-hidden="true"><path d="M3 7h7l2 2h9v10H3zM7 7V5h5" /></svg>
        <span>{{ t('默认目录') }}</span>
        <svg class="workspace-context-chevron" :class="{ open }" viewBox="0 0 24 24" aria-hidden="true"><path d="M7 9l5 5 5-5" /></svg>
      </button>

      <div v-if="open" ref="menu" class="composer-workspace-menu" role="menu" :aria-label="t('选择任务文件夹')" @keydown="navigate">
        <header>
          <strong>{{ t('最近') }}</strong>
        </header>
        <label class="workspace-search">
          <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="11" cy="11" r="6"/><path d="m16 16 4 4"/></svg>
          <span class="sr-only">{{ t('搜索目录') }}</span>
          <input ref="searchInput" v-model="query" type="search" autocomplete="off"
                 :placeholder="t('搜索目录名称或路径')" @keydown.down.prevent="focusFirstWorkspace">
          <button v-if="query" type="button" :aria-label="t('清除搜索')" @click="query = ''; searchInput?.focus()">×</button>
        </label>
        <button
          v-for="workspace in visibleWorkspaces"
          :key="workspace.id"
          class="workspace-result"
          type="button"
          role="menuitem"
          @click="select(workspace)"
        >
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M3 7h7l2 2h9v10H3z" /></svg>
          <span><strong>{{ workspace.name }}</strong><small>{{ workspace.root }}</small></span>
          <span></span>
        </button>
        <p v-if="!visibleWorkspaces.length" class="workspace-menu-empty">
          {{ query ? t('没有匹配的目录') : t('还没有最近使用的文件夹') }}
        </p>
        <footer>
          <button class="workspace-pick-action" type="button" role="menuitem" @click="pick">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M3 8h7l2 2h9v9H3zM8 5v6M5 8h6" /></svg>
            {{ t('选择文件夹') }}
          </button>
        </footer>
      </div>
    </div>

    <span v-if="error" class="workspace-context-error" role="status" :title="error">{{ error }}</span>
  </div>
</template>
