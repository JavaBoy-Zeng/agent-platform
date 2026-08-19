<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'

const props = defineProps({
  connection: { type: String, default: 'standby' }
})

defineEmits(['new-session'])

const clock = ref('--:--:--')
let timer

const connectionLabel = computed(() => ({
  online: 'API ONLINE',
  offline: 'API OFFLINE',
  standby: 'API STANDBY'
})[props.connection] || 'API STANDBY')

function updateClock() {
  clock.value = new Intl.DateTimeFormat('zh-CN', {
    hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false
  }).format(new Date())
}

onMounted(() => {
  updateClock()
  timer = window.setInterval(updateClock, 1000)
})

onUnmounted(() => window.clearInterval(timer))
</script>

<template>
  <header class="masthead">
    <router-link class="brand" to="/chat" aria-label="AgentOS 首页">
      <span class="brand-mark" aria-hidden="true">
        <span>AO</span>
        <i></i>
      </span>
      <span class="brand-copy">
        <strong>AGENT/OS</strong>
        <small>MISSION CONSOLE</small>
      </span>
    </router-link>

    <div class="system-strip" aria-label="系统状态">
      <span class="signal" :class="connection"></span>
      <span>{{ connectionLabel }}</span>
      <span class="system-divider"></span>
      <span>CONTROL PLANE / 01</span>
    </div>

    <div class="header-tools">
      <div class="clock" aria-label="当前时间">
        <span>LOCAL</span>
        <strong>{{ clock }}</strong>
      </div>
      <button class="icon-button" type="button" aria-label="新建会话" title="新建会话"
              @click="$emit('new-session')">
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path d="M12 5v14M5 12h14" />
        </svg>
      </button>
    </div>
  </header>
</template>
