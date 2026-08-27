<script setup>
import { inject, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { Terminal } from '@xterm/xterm'
import { FitAddon } from '@xterm/addon-fit'
import '@xterm/xterm/css/xterm.css'
import { listenDesktop } from '../services/desktopApi.js'
import { useLocale } from '../composables/useLocale.js'

const workspace = inject('desktopWorkspace')
const terminalOpen = workspace.terminalOpen
const { t } = useLocale()
const tabs = ref([])
const activeId = ref('')
const containers = new Map()
const panel = ref(null)
const panelHeight = ref(250)
let unlistenOutput
let unlistenExit
let resizeObserver
let themeObserver
let resizeStart = null

function beginPanelResize(event) {
  resizeStart = { y: event.clientY, height: panel.value?.getBoundingClientRect().height || panelHeight.value }
  window.addEventListener('pointermove', resizePanel)
  window.addEventListener('pointerup', endPanelResize, { once: true })
  document.body.style.cursor = 'row-resize'
  document.body.style.userSelect = 'none'
}
function resizePanel(event) {
  if (!resizeStart) return
  panelHeight.value = Math.max(150, Math.min(window.innerHeight * .62, resizeStart.height - (event.clientY - resizeStart.y)))
}
function endPanelResize() {
  resizeStart = null
  window.removeEventListener('pointermove', resizePanel)
  document.body.style.cursor = ''
  document.body.style.userSelect = ''
}
function resizePanelFromKeyboard(event) {
  if (!['ArrowUp', 'ArrowDown'].includes(event.key)) return
  event.preventDefault()
  panelHeight.value = Math.max(150, Math.min(window.innerHeight * .62,
    panelHeight.value + (event.key === 'ArrowUp' ? 20 : -20)))
}
function refreshTerminalThemes() {
  const next = terminalTheme()
  for (const tab of tabs.value) tab.terminal.options.theme = next
}

function setContainer(id, element) { if (element) containers.set(id, element); else containers.delete(id) }
function activeTab() { return tabs.value.find(item => item.meta.id === activeId.value) }
function terminalTheme() {
  const dark = document.documentElement.dataset.theme === 'dark'
    || (document.documentElement.dataset.theme === 'system' && matchMedia('(prefers-color-scheme: dark)').matches)
  return dark
    ? { background: '#171717', foreground: '#dededc', cursor: '#ffffff', selectionBackground: '#3a3a3a' }
    : { background: '#fbfbfa', foreground: '#242423', cursor: '#111111', selectionBackground: '#d9d9d6' }
}
async function createTerminal() {
  const meta = await workspace.call('terminal_create', { cols: 90, rows: 22 })
  const terminal = new Terminal({
    cursorBlink: true, convertEol: false, fontFamily: '"SFMono-Regular", "Cascadia Code", monospace',
    fontSize: 12, lineHeight: 1.35, scrollback: 5000, theme: terminalTheme()
  })
  const fit = new FitAddon(); terminal.loadAddon(fit)
  const tab = { meta, terminal, fit, exited: false }
  tabs.value.push(tab); activeId.value = meta.id
  await nextTick()
  terminal.open(containers.get(meta.id)); fit.fit()
  terminal.onData(data => workspace.call('terminal_write', { sessionId: meta.id, data }).catch(cause => terminal.writeln(`\r\n[AgentOS] ${cause}`)))
  terminal.focus()
  await resizeNative(tab)
}
async function resizeNative(tab = activeTab()) {
  if (!tab) return
  tab.fit.fit()
  try { await workspace.call('terminal_resize', { sessionId: tab.meta.id, cols: tab.terminal.cols, rows: tab.terminal.rows }) } catch { /* terminal may have exited */ }
}
async function closeTerminal(tab) {
  try { if (!tab.exited) await workspace.call('terminal_close', { sessionId: tab.meta.id }) } catch { /* already closed */ }
  tab.terminal.dispose(); containers.delete(tab.meta.id)
  const index = tabs.value.indexOf(tab); tabs.value.splice(index, 1)
  if (activeId.value === tab.meta.id) activeId.value = tabs.value[Math.max(0, index - 1)]?.meta.id || ''
  if (!tabs.value.length) terminalOpen.value = false
}
function decode(value) {
  const binary = atob(value); const bytes = new Uint8Array(binary.length)
  for (let index = 0; index < binary.length; index++) bytes[index] = binary.charCodeAt(index)
  return bytes
}

onMounted(async () => {
  unlistenOutput = await listenDesktop('terminal-output', payload => {
    tabs.value.find(item => item.meta.id === payload.sessionId)?.terminal.write(decode(payload.dataBase64))
  })
  unlistenExit = await listenDesktop('terminal-exit', payload => {
    const tab = tabs.value.find(item => item.meta.id === payload.sessionId)
    if (tab) { tab.exited = true; tab.terminal.writeln(`\r\n\x1b[2m[${t('进程已结束')}]\x1b[0m`) }
  })
  resizeObserver = new ResizeObserver(() => resizeNative())
  if (panel.value) resizeObserver.observe(panel.value)
  themeObserver = new MutationObserver(refreshTerminalThemes)
  themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] })
  await createTerminal()
})
onUnmounted(() => {
  unlistenOutput?.(); unlistenExit?.(); resizeObserver?.disconnect(); themeObserver?.disconnect()
  endPanelResize()
  for (const tab of [...tabs.value]) closeTerminal(tab)
})
watch(activeId, async () => { await nextTick(); const tab = activeTab(); if (tab) { resizeNative(tab); tab.terminal.focus() } })
</script>

<template>
  <section ref="panel" class="terminal-panel" :style="{ height: `${panelHeight}px` }" aria-label="Terminal">
    <div class="terminal-resize-handle" role="separator" aria-orientation="horizontal" aria-valuemin="150"
         :aria-valuemax="Math.round(window.innerHeight * .62)" :aria-valuenow="Math.round(panelHeight)" tabindex="0"
         @pointerdown.prevent="beginPanelResize" @keydown="resizePanelFromKeyboard"></div>
    <header class="terminal-tabs">
      <div><button v-for="(tab, index) in tabs" :key="tab.meta.id" type="button" :class="{ active: activeId === tab.meta.id }" @click="activeId = tab.meta.id">
        <span>›_</span> Terminal {{ index + 1 }}<i v-if="tab.exited">●</i><b @click.stop="closeTerminal(tab)">×</b>
      </button><button class="terminal-add" type="button" :title="t('新建终端')" @click="createTerminal">＋</button></div>
      <button class="terminal-close-panel" type="button" :aria-label="t('关闭')" @click="terminalOpen = false">×</button>
    </header>
    <div class="terminal-canvases">
      <div v-for="tab in tabs" :key="tab.meta.id" :ref="element => setContainer(tab.meta.id, element)" class="terminal-canvas" :class="{ active: activeId === tab.meta.id }"></div>
    </div>
  </section>
</template>
