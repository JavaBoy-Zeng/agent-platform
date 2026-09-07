<script setup>
import { computed, inject, nextTick, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useLocale } from '../composables/useLocale.js'
import { clearAuth, getAuthUser, getServerUrl, setAuthUser, setServerUrl } from '../services/apiConfig.js'
import { getServerHealth } from '../services/agentApi.js'
import {
  changePassword, createUser, deleteUser, listUsers, updateUserRoles
} from '../services/authApi.js'
import {
  getNonAdminCallLimit, updateNonAdminCallLimit
} from '../services/adminApi.js'
import AppSelect from '../components/AppSelect.vue'

const { t } = useLocale()
const router = useRouter()
const theme = inject('theme')
const selectedTheme = theme.theme
const roleOptions = ['ADMIN', 'MEMORY_ADMIN', 'WORKSPACE']
const MIN_PASSWORD_LENGTH = 8
const MAX_USERNAME_LENGTH = 128

const serverUrl = ref(getServerUrl())
const probeState = ref('idle')
const errorMessage = ref('')
const savedNotice = ref(false)

const effectiveTarget = computed(() => {
  const url = getServerUrl()
  return url || t('与页面同源')
})

const authUser = ref(getAuthUser())
const isAdmin = computed(() =>
  Array.isArray(authUser.value?.roles) && authUser.value.roles.includes('ADMIN'))

const passwordForm = ref({ old: '', next: '', confirm: '' })
const passwordState = ref('idle')
const passwordMessage = ref('')

const users = ref([])
const usersState = ref('idle')
const newUserForm = ref({ username: '', password: '', roles: ['USER'] })
const usersMessage = ref('')
const deleteTarget = ref(null)
const deleteConfirmButton = ref(null)
let dialogReturnFocus = null

onMounted(async () => {
  if (!isAdmin.value) return
  await refreshUsers()
  await refreshNonAdminLimit()
})

const nonAdminLimit = ref({ enabled: false, maxCalls: 0, windowSeconds: 0 })
const nonAdminLimitState = ref('idle')
const nonAdminLimitMessage = ref('')
const WINDOW_PRESETS = [
  { value: 300, label: '5 分钟' },
  { value: 1800, label: '30 分钟' },
  { value: 3600, label: '1 小时' },
  { value: 86400, label: '24 小时' }
]
const MAX_CALLS_PRESETS = [
  { value: 5, label: '5 次' },
  { value: 10, label: '10 次' },
  { value: 20, label: '20 次' },
  { value: 50, label: '50 次' },
  { value: 100, label: '100 次' }
]

async function refreshNonAdminLimit() {
  nonAdminLimitState.value = 'testing'
  try {
    const value = await getNonAdminCallLimit()
    nonAdminLimit.value = {
      enabled: !!value?.enabled,
      maxCalls: Number(value?.maxCalls) || 0,
      windowSeconds: Number(value?.windowSeconds) || 0
    }
    nonAdminLimitState.value = 'ok'
    nonAdminLimitMessage.value = ''
  } catch (error) {
    nonAdminLimitState.value = 'failed'
    nonAdminLimitMessage.value = error?.message || t('无法读取非管理员调用限频')
  }
}

async function submitNonAdminLimit() {
  nonAdminLimitMessage.value = ''
  nonAdminLimitState.value = 'testing'
  try {
    const value = await updateNonAdminCallLimit({
      enabled: nonAdminLimit.value.enabled,
      maxCalls: nonAdminLimit.value.enabled ? Number(nonAdminLimit.value.maxCalls) : 0,
      windowSeconds: nonAdminLimit.value.enabled ? Number(nonAdminLimit.value.windowSeconds) : 0
    })
    nonAdminLimit.value = {
      enabled: !!value?.enabled,
      maxCalls: Number(value?.maxCalls) || 0,
      windowSeconds: Number(value?.windowSeconds) || 0
    }
    nonAdminLimitState.value = 'ok'
    nonAdminLimitMessage.value = nonAdminLimit.value.enabled
      ? t('已启用限频')
      : t('已关闭限频，非管理员调用不再受限制')
  } catch (error) {
    nonAdminLimitState.value = 'failed'
    nonAdminLimitMessage.value = error?.message || t('更新失败')
  }
}

async function saveAndProbe() {
  setServerUrl(serverUrl.value)
  savedNotice.value = true
  window.setTimeout(() => {
    savedNotice.value = false
  }, 2000)

  probeState.value = 'testing'
  errorMessage.value = ''
  try {
    await getServerHealth()
    probeState.value = 'ok'
  } catch (error) {
    probeState.value = 'failed'
    if (error instanceof TypeError) {
      errorMessage.value = t('无法连接：请确认地址正确、server 已启动，且 server 的 CORS 白名单包含本应用来源。')
    } else if (error?.name === 'TimeoutError' || error?.name === 'AbortError') {
      errorMessage.value = t('连接超时：server 未在时限内响应。')
    } else {
      errorMessage.value = error?.message || t('无法连接 AgentOS Server')
    }
  }
}

function signOut() {
  clearAuth()
  router.push('/login')
}

async function submitPasswordChange() {
  passwordMessage.value = ''
  const { old, next, confirm } = passwordForm.value
  if (next !== confirm) {
    passwordState.value = 'failed'
    passwordMessage.value = t('两次输入的新密码不一致')
    return
  }
  passwordState.value = 'testing'
  try {
    await changePassword(old, next)
    passwordState.value = 'ok'
    passwordMessage.value = t('密码已修改')
    passwordForm.value = { old: '', next: '', confirm: '' }
  } catch (error) {
    passwordState.value = 'failed'
    passwordMessage.value = error?.message || t('修改密码失败')
  }
}

async function refreshUsers() {
  usersState.value = 'testing'
  try {
    users.value = await listUsers()
    usersState.value = 'ok'
    usersMessage.value = ''
  } catch (error) {
    usersState.value = 'failed'
    usersMessage.value = error?.message || t('无法读取用户列表')
  }
}

async function submitCreateUser() {
  usersMessage.value = ''
  const { username, password, roles } = newUserForm.value
  const normalizedUsername = username.trim()
  if (!normalizedUsername) {
    usersState.value = 'failed'
    usersMessage.value = t('用户名不能为空')
    return
  }
  if (normalizedUsername.length > MAX_USERNAME_LENGTH) {
    usersState.value = 'failed'
    usersMessage.value = t('用户名不能超过 128 个字符')
    return
  }
  if (password.length < MIN_PASSWORD_LENGTH) {
    usersState.value = 'failed'
    usersMessage.value = t('密码至少 8 位')
    return
  }
  usersState.value = 'testing'
  try {
    await createUser(normalizedUsername, password, roles)
    newUserForm.value = { username: '', password: '', roles: ['USER'] }
    usersMessage.value = t('用户已创建')
    await refreshUsers()
  } catch (error) {
    usersState.value = 'failed'
    usersMessage.value = error?.message || t('创建用户失败')
  }
}

async function requestRemoveUser(username) {
  dialogReturnFocus = document.activeElement
  deleteTarget.value = username
  await nextTick()
  deleteConfirmButton.value?.focus()
}

async function closeDeleteDialog() {
  deleteTarget.value = null
  await nextTick()
  dialogReturnFocus?.focus?.()
  dialogReturnFocus = null
}

function trapDialogFocus(event) {
  if (event.key === 'Escape') { event.preventDefault(); closeDeleteDialog(); return }
  if (event.key !== 'Tab') return
  const items = [...event.currentTarget.querySelectorAll('button:not(:disabled)')]
  const first = items[0]; const last = items[items.length - 1]
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus() }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus() }
}

async function removeUser() {
  if (!deleteTarget.value) return
  try {
    await deleteUser(deleteTarget.value)
    await closeDeleteDialog()
    await refreshUsers()
  } catch (error) {
    usersMessage.value = error?.message || t('删除用户失败')
  }
}

function toggleNewRole(role) {
  const roles = new Set(newUserForm.value.roles)
  if (roles.has(role)) roles.delete(role); else roles.add(role)
  roles.add('USER')
  newUserForm.value.roles = [...roles]
}

async function toggleUserRole(account, role) {
  const roles = new Set(account.roles)
  if (roles.has(role)) roles.delete(role); else roles.add(role)
  roles.add('USER')
  usersState.value = 'testing'
  usersMessage.value = ''
  try {
    const updated = await updateUserRoles(account.username, [...roles])
    users.value = users.value.map(item => item.username === updated.username ? updated : item)
    if (updated.username === authUser.value?.username) {
      authUser.value = updated
      setAuthUser(updated)
    }
    usersState.value = 'ok'
    usersMessage.value = t('角色已更新')
  } catch (error) {
    usersState.value = 'failed'
    usersMessage.value = error?.message || t('更新角色失败')
  }
}

function formatDate(iso) {
  try {
    return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'short' }).format(new Date(iso))
  } catch {
    return iso
  }
}
</script>

<template>
  <section class="settings-view">
    <header class="panel-head">
      <h1>{{ t('设置') }}</h1>
      <p>{{ t('桌面壳在此指向本地或远端 AgentOS Server；修改立即对后续请求生效。') }}</p>
    </header>

    <form class="panel-body" @submit.prevent="saveAndProbe">
      <label class="field">
        <span>{{ t('Server 地址') }}</span>
        <input
          v-model="serverUrl"
          type="url"
          :placeholder="t('http://localhost:8080（留空则与页面同源）')"
          spellcheck="false"
          autocomplete="off"
        >
        <small>{{ t('留空则与页面同源；当前生效：') }}<code>{{ effectiveTarget }}</code></small>
      </label>

      <footer class="actions">
        <button class="primary" type="submit" :disabled="probeState === 'testing'">{{ t('保存并测试') }}</button>
        <transition name="fade">
          <em v-if="savedNotice" class="saved">{{ t('设置已保存') }}</em>
        </transition>
      </footer>

      <p v-if="probeState === 'ok'" class="probe ok">{{ t('已连接，server 状态正常') }} · {{ effectiveTarget }}</p>
      <p v-else-if="probeState === 'testing'" class="probe pending">{{ t('测试中…') }}</p>
      <p v-else-if="probeState === 'failed'" class="probe bad">{{ errorMessage }}</p>
    </form>

    <section class="panel-body">
      <header class="section-head"><h2>{{ t('外观') }}</h2></header>
      <div class="appearance-row">
        <div><strong>{{ t('主题') }}</strong><small>{{ t('默认跟随系统外观') }}</small></div>
        <div class="theme-control" role="group" :aria-label="t('主题')">
          <button v-for="option in ['system', 'light', 'dark']" :key="option" type="button"
                  :class="{ active: selectedTheme === option }" :aria-pressed="selectedTheme === option"
                  @click="theme.setTheme(option)">{{ t({ system: '系统', light: '浅色', dark: '深色' }[option]) }}</button>
        </div>
      </div>
    </section>

    <section class="panel-body">
      <header class="section-head">
        <h2>{{ t('账户') }}</h2>
        <button class="ghost" type="button" @click="signOut">{{ t('登出') }}</button>
      </header>

      <p class="identity-line">
        <strong>{{ authUser?.username || '—' }}</strong>
        <code v-for="role in authUser?.roles || []" :key="role" class="role-chip">{{ role }}</code>
      </p>

      <form class="stack-form" @submit.prevent="submitPasswordChange">
        <span class="field-label">{{ t('修改密码') }}</span>
        <div class="row">
          <input
            v-model="passwordForm.old"
            type="password"
            :placeholder="t('原密码')"
            autocomplete="current-password"
          >
          <input
            v-model="passwordForm.next"
            type="password"
            :placeholder="t('新密码')"
            autocomplete="new-password"
          >
          <input
            v-model="passwordForm.confirm"
            type="password"
            :placeholder="t('确认新密码')"
            autocomplete="new-password"
          >
          <button type="submit" :disabled="passwordState === 'testing'">
            {{ t('修改密码') }}
          </button>
        </div>
        <small class="hint">{{ t('至少 8 位') }}</small>
        <p v-if="passwordMessage" :class="['probe', passwordState === 'ok' ? 'ok' : 'bad']">
          {{ passwordMessage }}
        </p>
      </form>
    </section>

    <section v-if="isAdmin" class="panel-body">
      <header class="section-head">
        <h2>{{ t('非管理员调用限频') }}</h2>
        <button class="ghost" type="button" @click="refreshNonAdminLimit">{{ t('刷新数据') }}</button>
      </header>

      <form class="stack-form" @submit.prevent="submitNonAdminLimit">
        <span class="field-label">{{ t('限频设置') }}</span>
        <div class="row">
          <label class="check">
            <input type="checkbox" v-model="nonAdminLimit.enabled">
            {{ t('启用限频') }}
          </label>
          <AppSelect
            :model-value="nonAdminLimit.maxCalls"
            :options="MAX_CALLS_PRESETS"
            :disabled="!nonAdminLimit.enabled"
            :placeholder="t('选择最大调用次数')"
            :aria-label="t('最大调用次数')"
            @update:model-value="value => nonAdminLimit.maxCalls = value"
          />
          <AppSelect
            :model-value="nonAdminLimit.windowSeconds"
            :options="WINDOW_PRESETS"
            :disabled="!nonAdminLimit.enabled"
            :placeholder="t('选择时间窗口')"
            :aria-label="t('时间窗口')"
            @update:model-value="value => nonAdminLimit.windowSeconds = value"
          />
          <button type="submit" :disabled="nonAdminLimitState === 'testing'">{{ t('保存') }}</button>
        </div>
        <p v-if="nonAdminLimitMessage" :class="['probe', nonAdminLimitState === 'ok' ? 'ok' : 'bad']">
          {{ nonAdminLimitMessage }}
        </p>
      </form>
    </section>

    <section v-if="isAdmin" class="panel-body">
      <header class="section-head">
        <h2>{{ t('用户管理') }}</h2>
        <button class="ghost" type="button" @click="refreshUsers">{{ t('刷新数据') }}</button>
      </header>

      <table class="user-table">
        <thead>
          <tr><th>{{ t('用户名') }}</th><th>ROLES</th><th>{{ t('创建时间') }}</th><th></th></tr>
        </thead>
        <tbody>
          <tr v-for="user in users" :key="user.username">
            <td><strong>{{ user.username }}</strong></td>
            <td><div class="role-editor" role="group" :aria-label="`${user.username} roles`">
              <button v-for="role in roleOptions" :key="role" type="button" :class="{ active: user.roles.includes(role) }"
                      :disabled="user.username === authUser?.username && role === 'ADMIN'"
                      :aria-pressed="user.roles.includes(role)" @click="toggleUserRole(user, role)">{{ role }}</button>
            </div></td>
            <td>{{ formatDate(user.createdAt) }}</td>
            <td>
              <button
                v-if="user.username !== authUser?.username"
                class="danger-text"
                type="button"
                @click="requestRemoveUser(user.username)"
              >{{ t('删除') }}</button>
            </td>
          </tr>
        </tbody>
      </table>

      <form class="stack-form" @submit.prevent="submitCreateUser">
        <span class="field-label">{{ t('新建用户') }}</span>
        <div class="row">
          <input
            v-model="newUserForm.username"
            type="text"
            :placeholder="t('用户名')"
            required
            :maxlength="MAX_USERNAME_LENGTH"
            spellcheck="false"
            autocomplete="off"
          >
          <input
            v-model="newUserForm.password"
            type="password"
            :placeholder="t('新密码')"
            required
            :minlength="MIN_PASSWORD_LENGTH"
            autocomplete="new-password"
          >
          <div class="role-editor new-user-roles" role="group" :aria-label="t('角色')">
            <button v-for="role in roleOptions" :key="role" type="button" :class="{ active: newUserForm.roles.includes(role) }"
                    :aria-pressed="newUserForm.roles.includes(role)" @click="toggleNewRole(role)">{{ role }}</button>
          </div>
          <button type="submit" :disabled="usersState === 'testing'">{{ t('新建用户') }}</button>
        </div>
        <small class="hint">{{ t('至少 8 位') }}</small>
        <p v-if="usersMessage" :class="['probe', usersState === 'ok' ? 'ok' : 'bad']">
          {{ usersMessage }}
        </p>
      </form>
    </section>

    <Teleport to="body"><Transition name="dialog-fade">
      <div v-if="deleteTarget" class="dialog-backdrop" @click.self="closeDeleteDialog">
        <section class="confirm-dialog" role="alertdialog" aria-modal="true" aria-labelledby="deleteUserTitle" @keydown="trapDialogFocus">
          <div class="confirm-dialog-icon">⌫</div><div class="confirm-dialog-copy">
            <h2 id="deleteUserTitle">{{ t('删除用户？') }}</h2><p>{{ t('删除用户确认', { username: deleteTarget }) }}</p>
          </div><div class="confirm-dialog-actions">
            <button type="button" class="dialog-cancel" @click="closeDeleteDialog">{{ t('取消') }}</button>
            <button ref="deleteConfirmButton" type="button" class="dialog-confirm" @click="removeUser">{{ t('删除') }}</button>
          </div>
        </section>
      </div>
    </Transition></Teleport>
  </section>
</template>

<style scoped>
.settings-view {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 18px 22px;
  overflow-y: auto;
}

.panel-head h1 {
  margin: 0;
  font-size: 13px;
  letter-spacing: 0.08em;
}

.panel-head p {
  margin: 4px 0 0;
  color: var(--dim);
  font-size: 10px;
}

.panel-body {
  display: flex;
  flex-direction: column;
  gap: 12px;
  max-width: 640px;
  padding: 16px;
  border: 1px solid rgba(127, 127, 127, 0.25);
  border-radius: 10px;
  background: rgba(127, 127, 127, 0.06);
}

.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.section-head h2 {
  margin: 0;
  font-size: 11px;
  letter-spacing: 0.08em;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.field > span,
.field-label {
  color: var(--dim);
  font-family: var(--mono);
  font-size: 9px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.field input,
.row input[type='text'],
.row input[type='password'] {
  width: 100%;
  padding: 8px 10px;
  border: 1px solid rgba(127, 127, 127, 0.3);
  border-radius: 7px;
  background: transparent;
  font-family: var(--mono);
  font-size: 11px;
}

.field small,
.hint {
  color: var(--dim);
  font-size: 9px;
}

.field small code {
  font-family: var(--mono);
}

.actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.actions button,
.ghost,
.stack-form button[type='submit'] {
  padding: 7px 14px;
  border: 1px solid rgba(127, 127, 127, 0.35);
  border-radius: 7px;
  background: transparent;
  font-size: 11px;
  cursor: pointer;
}

.actions button.primary,
.stack-form button[type='submit'] {
  border-color: var(--text);
  background: var(--text);
  color: var(--canvas);
}

.actions button:disabled,
.stack-form button[type='submit']:disabled {
  opacity: 0.55;
  cursor: default;
}

.saved {
  margin: 0;
  color: var(--muted);
  font-size: 10px;
}

.probe {
  margin: 0;
  font-family: var(--mono);
  font-size: 10px;
  word-break: break-all;
}

.probe.ok,
.identity-line strong {
  color: var(--text);
}

.probe.pending {
  color: var(--dim);
}

.probe.bad {
  color: #c0392b;
}

.identity-line {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
  font-size: 12px;
}

.role-chip {
  padding: 2px 6px;
  border-radius: 99px;
  background: rgba(16, 163, 127, 0.12);
  color: #0b7a5f;
  font-family: var(--mono);
  font-size: 8px;
}

.stack-form {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.row input[type='text'],
.row input[type='password'] {
  flex: 1;
  min-width: 130px;
}

.appearance-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.appearance-row > div:first-child {
  display: grid;
  gap: 3px;
}

.appearance-row strong {
  font-size: 11px;
}

.appearance-row small {
  color: var(--dim);
  font-size: 9px;
}

.theme-control,
.role-editor {
  display: inline-flex;
  flex-wrap: wrap;
  gap: 2px;
  padding: 3px;
  border: 1px solid var(--line-soft);
  border-radius: 9px;
  background: var(--surface);
}

.theme-control button,
.role-editor button {
  min-height: 27px;
  padding: 0 9px;
  border: 0;
  border-radius: 6px;
  color: var(--dim);
  background: transparent;
  font-family: var(--mono);
  font-size: 9px;
  cursor: pointer;
}

.theme-control button:hover,
.role-editor button:hover:not(:disabled) {
  color: var(--text);
}

.theme-control button.active,
.role-editor button.active {
  color: var(--text);
  background: var(--canvas);
  box-shadow: 0 1px 3px rgba(0, 0, 0, .1);
}

.role-editor button:disabled {
  opacity: .55;
  cursor: not-allowed;
}

.new-user-roles {
  flex: 1 1 260px;
}

.check {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 10px;
  color: var(--dim);
}

.user-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 11px;
}

.user-table th {
  padding: 6px 8px;
  border-bottom: 1px solid rgba(127, 127, 127, 0.25);
  color: var(--dim);
  font-family: var(--mono);
  font-size: 9px;
  letter-spacing: 0.08em;
  text-align: left;
}

.user-table td {
  padding: 7px 8px;
  border-bottom: 1px solid rgba(127, 127, 127, 0.12);
}

.danger-text {
  border: 0;
  background: transparent;
  color: #c0392b;
  font-size: 10px;
  cursor: pointer;
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.2s;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}

@media (max-width: 700px) {
  .settings-view { padding: 16px 12px; }
  .appearance-row { align-items: flex-start; flex-direction: column; }
  .user-table { display: block; overflow-x: auto; }
}
</style>
