<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useLocale } from '../composables/useLocale.js'
import { getAuthToken, setAuthUser, setAuthToken } from '../services/apiConfig.js'
import { login } from '../services/authApi.js'

const { t } = useLocale()
const router = useRouter()

const username = ref('')
const password = ref('')
const submitting = ref(false)
const errorMessage = ref('')

onMounted(() => {
  // 已持有令牌则直接进入工作台（路由守卫同时兜底）。
  if (getAuthToken()) router.replace('/chat')
})

async function submit() {
  if (submitting.value) return
  submitting.value = true
  errorMessage.value = ''
  try {
    const result = await login(username.value.trim(), password.value)
    setAuthToken(result.token)
    setAuthUser(result.user)
    router.replace('/chat')
  } catch (error) {
    errorMessage.value = error?.message || t('登录失败，请稍后再试')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <section class="login-view">
    <form class="login-card" @submit.prevent="submit">
      <header class="login-head">
        <span class="brand-mark" aria-hidden="true"><span>A</span></span>
        <h1>AgentOS</h1>
        <p>{{ t('登录以继续') }}</p>
      </header>

      <label class="field">
        <span>{{ t('用户名') }}</span>
        <input
          v-model="username"
          type="text"
          autocomplete="username"
          spellcheck="false"
          autofocus
        >
      </label>

      <label class="field">
        <span>{{ t('密码') }}</span>
        <input
          v-model="password"
          type="password"
          autocomplete="current-password"
        >
      </label>

      <p v-if="errorMessage" class="error">{{ errorMessage }}</p>

      <button class="submit" type="submit" :disabled="submitting || !username || !password">
        {{ submitting ? t('登录中…') : t('登录') }}
      </button>
    </form>
  </section>
</template>

<style scoped>
.login-view {
  flex: 1;
  min-height: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  color: var(--text);
  background: var(--canvas);
}

.login-card {
  width: 100%;
  max-width: 360px;
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 28px 26px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: var(--surface-soft);
}

.login-head {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
}

.brand-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  border-radius: 10px;
  background: var(--text);
  color: var(--canvas);
  font-family: var(--mono);
  font-size: 13px;
  letter-spacing: 0.02em;
}

.login-head h1 {
  margin: 0;
  font-size: 18px;
  font-weight: 650;
  letter-spacing: -0.03em;
}

.login-head p {
  margin: 0;
  color: var(--dim);
  font-size: 10px;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.field > span {
  color: var(--dim);
  font-family: var(--mono);
  font-size: 9px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.field input {
  width: 100%;
  padding: 9px 11px;
  border: 1px solid var(--line);
  border-radius: 7px;
  color: var(--text);
  background: var(--canvas);
  font-size: 12px;
}

.error {
  margin: 0;
  color: #c0392b;
  font-size: 10px;
  word-break: break-all;
}

.submit {
  margin-top: 4px;
  padding: 9px 14px;
  border: 1px solid var(--text);
  border-radius: 8px;
  background: var(--text);
  color: var(--canvas);
  font-size: 12px;
  cursor: pointer;
}

.submit:disabled {
  opacity: 0.5;
  cursor: default;
}
</style>
