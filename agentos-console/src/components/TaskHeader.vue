<script setup>
import { computed, inject, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { useLocale } from '../composables/useLocale.js'

const consoleState = inject('agentConsole')
const workspace = inject('desktopWorkspace')
const { t } = useLocale()
const menuOpen = ref(false)
const currentSession = computed(() => consoleState.sessions.value.find(item => item.id === consoleState.currentSessionId.value))
const available = workspace.available
const currentWorkspace = workspace.currentWorkspace
const workspaces = workspace.workspaces
const inspectorMode = workspace.inspectorMode
const terminalOpen = workspace.terminalOpen
const menu = ref(null)

function toggleInspector(mode) {
  inspectorMode.value = inspectorMode.value === mode ? '' : mode
}
function closeMenu() { menuOpen.value = false }
async function openMenuFromKeyboard(event) {
  if (!['ArrowDown', 'Enter', ' '].includes(event.key)) return
  event.preventDefault()
  menuOpen.value = true
  await nextTick()
  menu.value?.querySelector('[role="menuitem"]')?.focus()
}
function navigateMenu(event) {
  const items = [...menu.value?.querySelectorAll('[role="menuitem"]') || []]
  if (!items.length) return
  const index = items.indexOf(document.activeElement)
  if (event.key === 'Escape') { event.preventDefault(); closeMenu(); return }
  if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return
  event.preventDefault()
  const next = event.key === 'Home' ? 0 : event.key === 'End' ? items.length - 1
    : event.key === 'ArrowDown' ? (index + 1) % items.length : (index - 1 + items.length) % items.length
  items[next].focus()
}
function onDocumentKeydown(event) { if (event.key === 'Escape') closeMenu() }
onMounted(() => {
  document.addEventListener('click', closeMenu)
  document.addEventListener('keydown', onDocumentKeydown)
})
onUnmounted(() => {
  document.removeEventListener('click', closeMenu)
  document.removeEventListener('keydown', onDocumentKeydown)
})
</script>

<template>
  <header class="task-header" data-tauri-drag-region>
    <div class="task-heading">
      <strong>{{ t(currentSession?.title || '未命名任务') }}</strong>
      <span><i :class="consoleState.connection.value"></i>{{ consoleState.busy.value ? t('正在运行') : t('已就绪') }}</span>
    </div>
    <div class="task-header-actions" data-tauri-drag-region="false">
      <div v-if="available" class="workspace-picker" @click.stop>
        <button type="button" aria-haspopup="menu" :aria-expanded="menuOpen" @click="menuOpen = !menuOpen" @keydown="openMenuFromKeyboard">
          <svg viewBox="0 0 24 24"><path d="M3 6h7l2 2h9v10H3z"/></svg>
          <span>{{ currentWorkspace?.name || t('打开工作区') }}</span><b>⌄</b>
        </button>
        <div v-if="menuOpen" ref="menu" class="workspace-menu" role="menu" @keydown="navigateMenu">
          <button v-for="item in workspaces" :key="item.id" type="button" role="menuitem"
                  :class="{ active: item.id === currentWorkspace?.id }" @click="workspace.bindWorkspace(item.id); menuOpen = false">
            <span><strong>{{ item.name }}</strong><small>{{ item.root }}</small></span><b v-if="item.id === currentWorkspace?.id">✓</b>
          </button>
          <button class="workspace-open-action" type="button" role="menuitem" @click="workspace.pickWorkspace(); menuOpen = false">＋ {{ t('打开文件夹') }}</button>
        </div>
      </div>
      <template v-if="available && currentWorkspace">
        <button class="header-icon-button" type="button" :class="{ active: inspectorMode === 'files' }"
                :title="t('文件')" @click="toggleInspector('files')"><svg viewBox="0 0 24 24"><path d="M3 6h7l2 2h9v10H3z"/></svg></button>
        <button class="header-icon-button" type="button" :class="{ active: inspectorMode === 'diff' }"
                title="Git Diff" @click="toggleInspector('diff')"><svg viewBox="0 0 24 24"><path d="M7 4v12a3 3 0 0 0 3 3h7M7 8l-3-3 3-3M17 16l3 3-3 3"/></svg></button>
        <button class="header-icon-button terminal-toggle" type="button" :class="{ active: terminalOpen }"
                :title="t('终端')" @click="terminalOpen = !terminalOpen"><span>›_</span></button>
      </template>
    </div>
  </header>
</template>
