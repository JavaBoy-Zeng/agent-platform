<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useLocale } from '../composables/useLocale.js'

const props = defineProps({
  connection: { type: String, default: 'standby' }
})

defineEmits(['new-session'])

const { locale, localeTag, isEnglish, setLocale, toggleLocale, t } = useLocale()

const clock = ref('--:--:--')
let timer

const connectionLabel = computed(() => ({
  online: 'API ONLINE',
  offline: 'API OFFLINE',
  standby: 'API STANDBY'
})[props.connection] || 'API STANDBY')

function updateClock() {
  clock.value = new Intl.DateTimeFormat(localeTag.value, {
    hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false
  }).format(new Date())
}

onMounted(() => {
  updateClock()
  timer = window.setInterval(updateClock, 1000)
})

onUnmounted(() => window.clearInterval(timer))
watch(locale, updateClock)
</script>

<template>
  <header class="masthead">
    <router-link class="brand" to="/chat" :aria-label="t('AgentOS 首页')">
      <span class="brand-mark" aria-hidden="true">
        <span>AO</span>
        <i></i>
      </span>
      <span class="brand-copy">
        <strong>AGENT/OS</strong>
        <small>MISSION CONSOLE</small>
      </span>
    </router-link>

    <div class="system-strip" :aria-label="t('系统状态')">
      <span class="signal" :class="connection"></span>
      <span>{{ connectionLabel }}</span>
      <span class="system-divider"></span>
      <span>CONTROL PLANE / 01</span>
    </div>

    <div class="header-tools">
      <div class="locale-switch" role="group" :aria-label="t('界面语言')">
        <button
          type="button"
          :class="{ active: locale === 'zh' }"
          :aria-pressed="locale === 'zh'"
          @click="setLocale('zh')"
        >中</button>
        <button
          type="button"
          :class="{ active: locale === 'en' }"
          :aria-pressed="locale === 'en'"
          @click="setLocale('en')"
        >EN</button>
      </div>
      <button
        class="locale-mobile-button"
        type="button"
        :aria-label="t(isEnglish ? '切换为中文' : '切换为英文')"
        @click="toggleLocale"
      >{{ isEnglish ? '中' : 'EN' }}</button>
      <div class="clock" :aria-label="t('当前时间')">
        <span>LOCAL</span>
        <strong>{{ clock }}</strong>
      </div>
      <button class="icon-button" type="button" :aria-label="t('新建会话')" :title="t('新建会话')"
              @click="$emit('new-session')">
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path d="M12 5v14M5 12h14" />
        </svg>
      </button>
    </div>
  </header>
</template>
