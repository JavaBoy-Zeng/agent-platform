<script setup>
import { computed, inject } from 'vue'
import { useLocale } from '../composables/useLocale.js'

const consoleState = inject('agentConsole')
const workspace = inject('desktopWorkspace')
const { t } = useLocale()
const currentSession = computed(() => consoleState.sessions.value.find(item => item.id === consoleState.currentSessionId.value))
const available = workspace.available
const currentWorkspace = workspace.currentWorkspace
const inspectorMode = workspace.inspectorMode
const terminalOpen = workspace.terminalOpen

function toggleInspector(mode) {
  inspectorMode.value = inspectorMode.value === mode ? '' : mode
}
</script>

<template>
  <header class="task-header" data-tauri-drag-region>
    <div class="task-heading">
      <strong>{{ t(currentSession?.title || '未命名任务') }}</strong>
      <span><i :class="consoleState.connection.value"></i>{{ consoleState.busy.value ? t('正在运行') : t('已就绪') }}</span>
    </div>
    <div class="task-header-actions" data-tauri-drag-region="false">
      <template v-if="available && currentWorkspace">
        <button class="header-icon-button" type="button" :class="{ active: inspectorMode === 'files' }"
                :aria-pressed="inspectorMode === 'files'" :title="t('文件')" @click="toggleInspector('files')"><svg viewBox="0 0 24 24"><path d="M3 6h7l2 2h9v10H3z"/></svg></button>
        <button class="header-icon-button diff-toggle" type="button" :class="{ active: inspectorMode === 'diff' }"
                :aria-pressed="inspectorMode === 'diff'" aria-label="Git Diff" title="Git Diff" @click="toggleInspector('diff')">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M7 4v12a3 3 0 0 0 3 3h7M7 8l-3-3 3-3M17 16l3 3-3 3"/></svg>
        </button>
        <button class="header-icon-button terminal-toggle" type="button" :class="{ active: terminalOpen }"
                :aria-pressed="terminalOpen" :aria-label="t('终端')" :title="t('终端')" @click="terminalOpen = !terminalOpen">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m5 7 5 5-5 5M12 17h7"/></svg>
        </button>
      </template>
    </div>
  </header>
</template>
