<script setup>
import { provide } from 'vue'
import { useRouter } from 'vue-router'
import SystemHeader from './components/SystemHeader.vue'
import AppSidebar from './components/AppSidebar.vue'
import { useAgentConsole } from './composables/useAgentConsole.js'

const agentConsole = useAgentConsole()
const router = useRouter()
provide('agentConsole', agentConsole)

function createChatSession() {
  agentConsole.createSession()
  router.push('/chat')
}
</script>

<template>
  <a class="skip-link" href="#workspace">跳到操作区</a>

  <div class="app-shell">
    <SystemHeader :connection="agentConsole.connection.value" @new-session="createChatSession" />

    <main id="workspace" class="main-layout">
      <AppSidebar />
      <router-view />
    </main>

    <footer class="status-footer">
      <span><i></i> AGENTOS KERNEL ONLINE</span>
      <span class="footer-marquee">USER → MAIN AGENT → PLANNER → TOOL → OBSERVATION → DECISION</span>
      <span>CONSOLE v0.1</span>
    </footer>
  </div>
</template>

<style scoped>
.main-layout {
  display: flex;
  min-height: 0;
  flex: 1;
}
</style>
