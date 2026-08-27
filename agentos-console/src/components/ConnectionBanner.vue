<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useLocale } from '../composables/useLocale.js'
import { getServerUrl } from '../services/apiConfig.js'
import { getServerHealth } from '../services/agentApi.js'

const { t } = useLocale()
const router = useRouter()

const unreachable = ref(false)
let timer

const targetLabel = computed(() => {
  const url = getServerUrl()
  return url || t('与页面同源')
})

async function check() {
  try {
    await getServerHealth({ timeoutMs: 4000 })
    unreachable.value = false
  } catch {
    unreachable.value = true
  }
}

function openSettings() {
  router.push('/settings')
}

onMounted(() => {
  check()
  timer = window.setInterval(check, 30000)
})

onUnmounted(() => window.clearInterval(timer))
</script>

<template>
  <transition name="banner">
    <div v-if="unreachable" class="connection-banner" role="alert">
      <span class="dot" aria-hidden="true"></span>
      <p>{{ t('服务端暂不可达，请求将被拒绝。请检查地址或前往设置页重新配置。') }}<code>{{ targetLabel }}</code></p>
      <button type="button" @click="openSettings">{{ t('打开设置') }}</button>
    </div>
  </transition>
</template>

<style scoped>
.connection-banner {
  /* 抬升层叠上下文，避免被工作区的滚动内容遮挡点击 */
  position: relative;
  z-index: 30;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 7px 20px;
  border-bottom: 1px solid rgba(192, 57, 43, 0.35);
  background: #fdecea;
}

.connection-banner .dot {
  flex: 0 0 auto;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #c0392b;
  box-shadow: 0 0 0 4px rgba(192, 57, 43, 0.12);
}

.connection-banner p {
  flex: 1;
  min-width: 0;
  margin: 0;
  color: #c0392b;
  font-size: 10px;
  word-break: break-all;
}

.connection-banner code {
  margin-left: 6px;
  font-family: var(--mono);
  font-size: 9px;
}

.connection-banner button {
  flex: 0 0 auto;
  padding: 3px 10px;
  border: 1px solid rgba(192, 57, 43, 0.45);
  border-radius: 6px;
  background: transparent;
  color: #c0392b;
  font-size: 10px;
  cursor: pointer;
}

.banner-enter-active,
.banner-leave-active {
  transition: opacity 0.25s;
}

.banner-enter-from,
.banner-leave-to {
  opacity: 0;
}
</style>
