<script setup>
import { onUnmounted, provide } from 'vue'
import AppSidebar from './AppSidebar.vue'
import ConnectionBanner from './ConnectionBanner.vue'
import { useAgentConsole } from '../composables/useAgentConsole.js'
import { useDesktopWorkspace } from '../composables/useDesktopWorkspace.js'
import { useAutomationDesktop } from '../composables/useAutomationDesktop.js'
import { useTheme } from '../composables/useTheme.js'

const props = defineProps({ username: { type: String, required: true } })
const agentConsole = useAgentConsole(props.username)
const desktopWorkspace = useDesktopWorkspace(agentConsole, { ownerId: props.username })
const automationDesktop = useAutomationDesktop(desktopWorkspace)
const theme = useTheme()

provide('agentConsole', agentConsole)
provide('desktopWorkspace', desktopWorkspace)
provide('automationDesktop', automationDesktop)
provide('theme', theme)

onUnmounted(() => {
  agentConsole.dispose()
  desktopWorkspace.dispose()
})
</script>

<template>
  <div class="app-shell codex-shell">
    <AppSidebar />
    <main id="workspace" class="main-layout codex-main">
      <ConnectionBanner />
      <router-view />
    </main>
  </div>
</template>

<style scoped>
.main-layout {
  min-height: 0;
}
</style>
