<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AuthenticatedShell from './components/AuthenticatedShell.vue'
import { useLocale } from './composables/useLocale.js'
import { clearAuth, getAuthToken, setAuthUser } from './services/apiConfig.js'
import { getMe } from './services/authApi.js'

const router = useRouter()
const route = useRoute()
const { t } = useLocale()
// 只有服务端确认过的身份才能挂载账号级工作台，避免启动时读取伪造或过期的用户缓存。
const authUser = ref(null)

const isLogin = computed(() => route.path === '/login')

let onUnauthorized
let onAuthChanged
let identityTimer

async function refreshIdentity() {
  const token = getAuthToken()
  if (!token || isLogin.value) return
  try {
    const user = await getMe()
    if (token !== getAuthToken()) return
    authUser.value = user
    setAuthUser(user)
  } catch { /* connection banner owns offline feedback */ }
}

onMounted(() => {
  // 任一接口返回 401（令牌过期）→ 清凭据并回到登录页。
  onUnauthorized = () => {
    clearAuth()
    router.push('/login')
  }
  onAuthChanged = event => { authUser.value = event.detail || null }
  window.addEventListener('agentos:auth-changed', onAuthChanged)
  window.addEventListener('agentos:unauthorized', onUnauthorized)
  refreshIdentity()
  identityTimer = window.setInterval(refreshIdentity, 50_000)
})

onUnmounted(() => {
  window.removeEventListener('agentos:unauthorized', onUnauthorized)
  window.removeEventListener('agentos:auth-changed', onAuthChanged)
  clearInterval(identityTimer)
})
</script>

<template>
  <a class="skip-link" href="#workspace">{{ t('跳到操作区') }}</a>

  <router-view v-if="isLogin" />

  <AuthenticatedShell
    v-else-if="authUser?.username"
    :key="authUser.username"
    :username="authUser.username"
  />
</template>
