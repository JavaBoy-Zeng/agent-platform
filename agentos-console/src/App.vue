<script setup>
import { computed, onMounted, onUnmounted, provide } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppSidebar from './components/AppSidebar.vue'
import ConnectionBanner from './components/ConnectionBanner.vue'
import { useAgentConsole } from './composables/useAgentConsole.js'
import { useDesktopWorkspace } from './composables/useDesktopWorkspace.js'
import { useLocale } from './composables/useLocale.js'
import { useTheme } from './composables/useTheme.js'
import { clearAuth, getAuthToken, setAuthUser } from './services/apiConfig.js'
import { getMe } from './services/authApi.js'

const agentConsole = useAgentConsole()
const router = useRouter()
const route = useRoute()
const { t } = useLocale()
const theme = useTheme()
const desktopWorkspace = useDesktopWorkspace(agentConsole)
provide('agentConsole', agentConsole)
provide('desktopWorkspace', desktopWorkspace)
provide('theme', theme)

const isLogin = computed(() => route.path === '/login')

let onUnauthorized
let identityTimer

async function refreshIdentity() {
  if (!getAuthToken() || isLogin.value) return
  try { setAuthUser(await getMe()) } catch { /* connection banner owns offline feedback */ }
}

onMounted(() => {
  // 任一接口返回 401（令牌过期）→ 清凭据并回到登录页。
  onUnauthorized = () => {
    desktopWorkspace.dispose()
    clearAuth()
    router.push('/login')
  }
  window.addEventListener('agentos:unauthorized', onUnauthorized)
  window.addEventListener('agentos:logout', desktopWorkspace.dispose)
  refreshIdentity()
  identityTimer = window.setInterval(refreshIdentity, 50_000)
})

onUnmounted(() => {
  window.removeEventListener('agentos:unauthorized', onUnauthorized)
  window.removeEventListener('agentos:logout', desktopWorkspace.dispose)
  clearInterval(identityTimer)
})
</script>

<template>
  <a class="skip-link" href="#workspace">{{ t('跳到操作区') }}</a>

  <router-view v-if="isLogin" />

  <div v-else class="app-shell codex-shell">
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
