<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import AppSelect from './AppSelect.vue'
import {
  assignModelRoute, createModelProvider, deleteModelProvider, getModelManagement,
  testModelProvider, updateModelProvider
} from '../services/consoleApi.js'

defineProps({ usage: { type: Object, default: () => ({}) } })

const snapshot = ref({ providers: [], routes: [] })
const loading = ref(true)
const busy = ref('')
const error = ref('')
const toast = ref('')
const editor = ref(null)
const deleteTarget = ref(null)
const firstField = ref(null)

const providerTypes = [
  { value: 'OPENAI', label: 'OpenAI', description: 'Official Chat Completions API' },
  { value: 'MINIMAX', label: 'MiniMax', description: 'OpenAI-compatible endpoint' },
  { value: 'CC_SWITCH', label: 'CC Switch', description: 'Local compatibility proxy' },
  { value: 'OPENAI_COMPATIBLE', label: 'Compatible', description: 'Any compatible endpoint' }
]
const responseFormats = [
  { value: 'JSON_SCHEMA', label: 'JSON Schema', description: 'Strict planner output' },
  { value: 'JSON_OBJECT', label: 'JSON Object', description: 'Loose structured output' },
  { value: 'NONE', label: 'None', description: 'Prompt-only compatibility' }
]
const enabledProviders = computed(() => snapshot.value.providers.filter(item => item.enabled))
const providerOptions = computed(() => enabledProviders.value.map(item => ({
  value: item.id, label: item.displayName, description: `${item.providerType} · ${item.endpoint}`
})))

function routeByKey(key) {
  return snapshot.value.routes.find(route => route.routeKey === key)
}

function providerById(id) {
  return snapshot.value.providers.find(provider => provider.id === id)
}

function modelOptions(providerId) {
  return (providerById(providerId)?.models || []).map(model => ({ value: model, label: model }))
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    snapshot.value = await getModelManagement()
  } catch (cause) {
    error.value = cause.message || '模型配置读取失败'
  } finally {
    loading.value = false
  }
}

function blankForm() {
  return {
    id: '', displayName: '', providerType: 'OPENAI_COMPATIBLE',
    protocol: 'CHAT_COMPLETIONS', endpoint: 'https://api.openai.com/v1/chat/completions',
    apiKey: '', modelsText: 'gpt-5.2', defaultModel: 'gpt-5.2',
    responseFormat: 'JSON_SCHEMA', reasoningSplit: false, enabled: true, hasApiKey: false
  }
}

function openCreate() {
  editor.value = blankForm()
  focusEditor()
}

function openEdit(provider) {
  editor.value = {
    ...provider,
    apiKey: '',
    modelsText: provider.models.join('\n')
  }
  focusEditor()
}

function focusEditor() {
  nextTick(() => firstField.value?.focus())
}

function closeEditor() {
  if (!busy.value) editor.value = null
}

async function saveProvider() {
  const value = editor.value
  const models = value.modelsText.split(/[\n,]/).map(item => item.trim()).filter(Boolean)
  const payload = {
    displayName: value.displayName,
    providerType: value.providerType,
    protocol: 'CHAT_COMPLETIONS',
    endpoint: value.endpoint,
    apiKey: value.apiKey,
    models,
    defaultModel: value.defaultModel,
    responseFormat: value.responseFormat,
    reasoningSplit: value.reasoningSplit,
    enabled: value.enabled
  }
  busy.value = 'save'
  error.value = ''
  try {
    if (value.id) await updateModelProvider(value.id, payload)
    else await createModelProvider(payload)
    editor.value = null
    toast.value = value.id ? 'Provider 已更新' : 'Provider 已创建'
    await load()
  } catch (cause) {
    error.value = cause.message || '保存失败'
  } finally {
    busy.value = ''
  }
}

async function testProvider(provider) {
  busy.value = `test:${provider.id}`
  error.value = ''
  try {
    const result = await testModelProvider(provider.id)
    toast.value = `${result.message} · ${result.latencyMs} ms`
    await load()
  } catch (cause) {
    error.value = cause.message || '连接测试失败'
  } finally {
    busy.value = ''
  }
}

async function toggleProvider(provider) {
  busy.value = `toggle:${provider.id}`
  try {
    await updateModelProvider(provider.id, {
      displayName: provider.displayName,
      providerType: provider.providerType,
      protocol: provider.protocol,
      endpoint: provider.endpoint,
      apiKey: '',
      models: provider.models,
      defaultModel: provider.defaultModel,
      responseFormat: provider.responseFormat,
      reasoningSplit: provider.reasoningSplit,
      enabled: !provider.enabled
    })
    await load()
  } catch (cause) {
    error.value = cause.message || '状态更新失败'
  } finally {
    busy.value = ''
  }
}

async function confirmDelete() {
  const target = deleteTarget.value
  busy.value = `delete:${target.id}`
  try {
    await deleteModelProvider(target.id)
    deleteTarget.value = null
    toast.value = 'Provider 已删除'
    await load()
  } catch (cause) {
    error.value = cause.message || '删除失败'
  } finally {
    busy.value = ''
  }
}

async function updateRoute(routeKey, field, value) {
  const current = routeByKey(routeKey)
  const providerId = field === 'providerId' ? value : current?.providerId
  const provider = providerById(providerId)
  const modelId = field === 'modelId' ? value
    : (provider?.models.includes(current?.modelId) ? current.modelId : provider?.defaultModel)
  if (!providerId || !modelId) return
  busy.value = `route:${routeKey}`
  try {
    await assignModelRoute(routeKey, { providerId, modelId })
    toast.value = `${routeKey.toUpperCase()} 路由已切换`
    await load()
  } catch (cause) {
    error.value = cause.message || '路由更新失败'
  } finally {
    busy.value = ''
  }
}

function statusLabel(provider) {
  if (!provider.enabled) return 'DISABLED'
  return provider.lastStatus || 'UNTESTED'
}

function keydown(event) {
  if (event.key !== 'Escape') return
  if (deleteTarget.value && !busy.value) deleteTarget.value = null
  else closeEditor()
}

onMounted(() => {
  load()
  document.addEventListener('keydown', keydown)
})
onUnmounted(() => document.removeEventListener('keydown', keydown))
</script>

<template>
  <section class="model-console" aria-label="Model provider management">
    <div class="routing-rack">
      <header class="rack-heading">
        <div><small>LIVE PATCH BAY</small><h2>Inference routes</h2></div>
        <span><i></i> DATABASE CONTROLLED</span>
      </header>
      <div class="route-grid">
        <article v-for="routeKey in ['planner', 'chat']" :key="routeKey" class="route-channel">
          <div class="channel-index">{{ routeKey === 'planner' ? 'A' : 'B' }}</div>
          <div class="channel-copy">
            <small>{{ routeKey === 'planner' ? 'STRUCTURED REASONING' : 'DIRECT + TOOL CHAT' }}</small>
            <strong>{{ routeKey.toUpperCase() }}</strong>
          </div>
          <div class="route-field">
            <span>PROVIDER</span>
            <AppSelect
              :model-value="routeByKey(routeKey)?.providerId || ''"
              :options="providerOptions"
              :disabled="busy === `route:${routeKey}`"
              placeholder="Choose provider"
              aria-label="Select route provider"
              @update:model-value="updateRoute(routeKey, 'providerId', $event)"
            />
          </div>
          <div class="route-arrow">→</div>
          <div class="route-field">
            <span>MODEL</span>
            <AppSelect
              :model-value="routeByKey(routeKey)?.modelId || ''"
              :options="modelOptions(routeByKey(routeKey)?.providerId)"
              :disabled="busy === `route:${routeKey}`"
              placeholder="Choose model"
              aria-label="Select route model"
              @update:model-value="updateRoute(routeKey, 'modelId', $event)"
            />
          </div>
        </article>
      </div>
    </div>

    <div class="provider-toolbar">
      <div><small>SECURE PROVIDER VAULT</small><strong>{{ snapshot.providers.length }} configured endpoints</strong></div>
      <button class="add-provider" type="button" @click="openCreate"><b>＋</b> ADD PROVIDER</button>
    </div>

    <p v-if="error" class="model-error" role="alert">{{ error }}</p>
    <div v-if="loading" class="model-loading">READING POSTGRESQL CONFIGURATION…</div>
    <div v-else class="provider-grid">
      <article v-for="provider in snapshot.providers" :key="provider.id" class="provider-card" :class="{ disabled: !provider.enabled }">
        <header>
          <div class="provider-monogram">{{ provider.displayName.slice(0, 2).toUpperCase() }}</div>
          <div><small>{{ provider.providerType }}</small><h3>{{ provider.displayName }}</h3></div>
          <span class="provider-status" :class="provider.lastStatus.toLowerCase()"><i></i>{{ statusLabel(provider) }}</span>
        </header>
        <div class="endpoint-line"><span>ENDPOINT</span><code>{{ provider.endpoint }}</code></div>
        <dl>
          <div><dt>DEFAULT MODEL</dt><dd>{{ provider.defaultModel }}</dd></div>
          <div><dt>MODELS</dt><dd>{{ provider.models.length }}</dd></div>
          <div><dt>SECRET</dt><dd>{{ provider.hasApiKey ? 'ENCRYPTED' : 'LOCAL / NONE' }}</dd></div>
          <div><dt>FORMAT</dt><dd>{{ provider.responseFormat }}</dd></div>
        </dl>
        <p v-if="provider.lastError" class="provider-fault">{{ provider.lastError }}</p>
        <footer>
          <button type="button" :disabled="busy === `test:${provider.id}`" @click="testProvider(provider)">{{ busy === `test:${provider.id}` ? 'TESTING…' : 'TEST LINK' }}</button>
          <button type="button" @click="openEdit(provider)">EDIT</button>
          <button type="button" @click="toggleProvider(provider)">{{ provider.enabled ? 'DISABLE' : 'ENABLE' }}</button>
          <button class="danger" type="button" @click="deleteTarget = provider">DELETE</button>
        </footer>
      </article>
      <button v-if="!snapshot.providers.length" class="empty-provider" type="button" @click="openCreate">
        <b>＋</b><strong>CONNECT THE FIRST PROVIDER</strong><span>OpenAI, MiniMax, CC Switch or any compatible endpoint</span>
      </button>
    </div>

    <transition name="model-toast"><div v-if="toast" class="model-toast" role="status" @click="toast = ''">{{ toast }}</div></transition>

    <transition name="model-dialog">
      <div v-if="editor" class="model-backdrop" @click.self="closeEditor">
        <form class="provider-editor" role="dialog" aria-modal="true" aria-labelledby="providerEditorTitle" @submit.prevent="saveProvider">
          <header><div><small>PROVIDER CONFIGURATION</small><h2 id="providerEditorTitle">{{ editor.id ? 'Edit connection' : 'New connection' }}</h2></div><button type="button" aria-label="Close" @click="closeEditor">×</button></header>
          <div class="editor-grid">
            <label class="wide"><span>DISPLAY NAME</span><input ref="firstField" v-model.trim="editor.displayName" required autocomplete="off" placeholder="Production OpenAI" /></label>
            <div><span>PROVIDER TYPE</span><AppSelect v-model="editor.providerType" :options="providerTypes" aria-label="Provider type" /></div>
            <label class="wide"><span>CHAT COMPLETIONS ENDPOINT</span><input v-model.trim="editor.endpoint" required type="url" autocomplete="off" placeholder="https://api.openai.com/v1/chat/completions" /></label>
            <label class="wide"><span>API KEY <em>{{ editor.id && editor.hasApiKey ? 'LEAVE BLANK TO KEEP CURRENT' : '' }}</em></span><input v-model="editor.apiKey" :required="!editor.id && editor.providerType !== 'CC_SWITCH'" type="password" autocomplete="new-password" placeholder="sk-…" /></label>
            <label class="wide"><span>MODELS <em>ONE PER LINE OR COMMA SEPARATED</em></span><textarea v-model="editor.modelsText" required rows="4" placeholder="gpt-5.2&#10;gpt-5.2-mini"></textarea></label>
            <label><span>DEFAULT MODEL</span><input v-model.trim="editor.defaultModel" required autocomplete="off" /></label>
            <div><span>PLANNER FORMAT</span><AppSelect v-model="editor.responseFormat" :options="responseFormats" aria-label="Planner response format" /></div>
            <label class="switch-field"><input v-model="editor.reasoningSplit" type="checkbox" /><span><b>Reasoning split</b><small>Read reasoning_content separately</small></span></label>
            <label class="switch-field"><input v-model="editor.enabled" type="checkbox" /><span><b>Provider enabled</b><small>Available to runtime routes</small></span></label>
          </div>
          <footer><button type="button" @click="closeEditor">CANCEL</button><button class="save" type="submit" :disabled="busy === 'save'">{{ busy === 'save' ? 'SAVING…' : 'SAVE PROVIDER' }}</button></footer>
        </form>
      </div>
    </transition>

    <transition name="model-dialog">
      <div v-if="deleteTarget" class="model-backdrop" @click.self="deleteTarget = null">
        <section class="delete-dialog" role="alertdialog" aria-modal="true" aria-labelledby="deleteProviderTitle">
          <div class="delete-mark">!</div><div><small>DESTRUCTIVE OPERATION</small><h2 id="deleteProviderTitle">Delete {{ deleteTarget.displayName }}?</h2><p>The encrypted credential and endpoint configuration will be permanently removed. Providers assigned to a live route must be switched first.</p></div>
          <footer><button type="button" @click="deleteTarget = null">CANCEL</button><button class="danger" type="button" :disabled="busy === `delete:${deleteTarget.id}`" @click="confirmDelete">DELETE PROVIDER</button></footer>
        </section>
      </div>
    </transition>
  </section>
</template>

<style scoped>
.model-console { display: grid; gap: 18px; color: var(--text); }
.routing-rack { overflow: visible; border: 1px solid var(--line); border-radius: 10px; background: linear-gradient(135deg, var(--canvas), var(--surface)); box-shadow: inset 0 1px rgba(255,255,255,.45); }
.rack-heading, .provider-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 16px 18px; }
.rack-heading { border-bottom: 1px solid var(--line); }
.rack-heading small, .provider-toolbar small, .provider-card small, .provider-editor small, .delete-dialog small { color: var(--dim); font-family: var(--mono); font-size: 8px; letter-spacing: .14em; }
.rack-heading h2 { margin: 3px 0 0; font-family: var(--font); font-size: 18px; font-weight: 560; }
.rack-heading > span { display: flex; align-items: center; gap: 7px; color: var(--dim); font-family: var(--mono); font-size: 8px; letter-spacing: .1em; }
.rack-heading > span i { width: 7px; height: 7px; border-radius: 50%; background: #54a46d; box-shadow: 0 0 0 4px rgba(84,164,109,.12); }
.route-grid { display: grid; gap: 1px; background: var(--line); }
.route-channel { display: grid; grid-template-columns: 44px minmax(130px,.7fr) minmax(170px,1fr) 28px minmax(170px,1fr); align-items: center; gap: 12px; padding: 14px 18px; background: var(--canvas); }
.channel-index { display: grid; width: 34px; height: 34px; place-items: center; border: 1px solid var(--line); border-radius: 50%; font-family: var(--mono); font-size: 11px; }
.channel-copy { display: grid; gap: 3px; }.channel-copy small, .route-field > span { color: var(--dim); font-family: var(--mono); font-size: 7px; letter-spacing: .1em; }.channel-copy strong { font-family: var(--mono); font-size: 11px; letter-spacing: .12em; }
.route-field { display: grid; min-width: 0; gap: 5px; }.route-arrow { color: var(--dim); text-align: center; }
.provider-toolbar { padding: 4px 0 0; }.provider-toolbar > div { display: grid; gap: 4px; }.provider-toolbar strong { font-size: 13px; font-weight: 520; }
.add-provider { display: flex; align-items: center; gap: 8px; min-height: 36px; padding: 0 14px; border: 1px solid var(--text); border-radius: 6px; color: var(--canvas); background: var(--text); font-family: var(--mono); font-size: 9px; letter-spacing: .08em; }.add-provider b { font-size: 16px; font-weight: 300; }
.provider-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(340px, 1fr)); gap: 12px; }
.provider-card { display: grid; min-width: 0; border: 1px solid var(--line); border-radius: 9px; background: var(--canvas); transition: transform 150ms ease, border-color 150ms ease; }.provider-card:hover { transform: translateY(-2px); border-color: var(--muted); }.provider-card.disabled { opacity: .62; }
.provider-card > header { display: grid; grid-template-columns: 38px 1fr auto; align-items: center; gap: 11px; padding: 15px; border-bottom: 1px solid var(--line-soft); }.provider-card h3 { margin: 2px 0 0; font-size: 14px; font-weight: 560; }
.provider-monogram { display: grid; width: 36px; height: 36px; place-items: center; border-radius: 6px; color: var(--canvas); background: var(--text); font-family: var(--mono); font-size: 10px; }
.provider-status { display: flex; align-items: center; gap: 6px; font-family: var(--mono); font-size: 8px; }.provider-status i { width: 6px; height: 6px; border-radius: 50%; background: #a7a7a7; }.provider-status.connected i { background: #54a46d; box-shadow: 0 0 0 3px rgba(84,164,109,.12); }.provider-status.failed i { background: #cf5b51; }
.endpoint-line { display: grid; gap: 5px; padding: 13px 15px; }.endpoint-line span, .provider-card dt { color: var(--dim); font-family: var(--mono); font-size: 7px; letter-spacing: .1em; }.endpoint-line code { overflow: hidden; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.provider-card dl { display: grid; grid-template-columns: 1fr 1fr; margin: 0; border-top: 1px solid var(--line-soft); }.provider-card dl div { display: grid; gap: 4px; padding: 11px 15px; border-right: 1px solid var(--line-soft); border-bottom: 1px solid var(--line-soft); }.provider-card dd { margin: 0; overflow: hidden; font-family: var(--mono); font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }
.provider-fault { margin: 0; padding: 10px 15px; color: #a13f38; background: rgba(207,91,81,.07); font-size: 10px; }
.provider-card > footer { display: flex; flex-wrap: wrap; gap: 6px; padding: 12px 15px; }.provider-card button, .provider-editor button, .delete-dialog button { min-height: 30px; padding: 0 10px; border: 1px solid var(--line); border-radius: 5px; color: var(--text); background: var(--surface); font-family: var(--mono); font-size: 8px; letter-spacing: .06em; }.provider-card button:hover, .provider-editor button:hover { border-color: var(--text); }.provider-card button.danger, .delete-dialog button.danger { margin-left: auto; color: #a13f38; }.provider-card button:focus-visible, .add-provider:focus-visible, .provider-editor button:focus-visible, .delete-dialog button:focus-visible { outline: 2px solid var(--text); outline-offset: 2px; }
.empty-provider { display: grid; min-height: 240px; place-items: center; align-content: center; gap: 9px; border: 1px dashed var(--muted); border-radius: 9px; color: var(--dim); background: transparent; }.empty-provider b { font-size: 26px; font-weight: 200; }.empty-provider strong { color: var(--text); font-family: var(--mono); font-size: 10px; letter-spacing: .1em; }.empty-provider span { font-size: 10px; }
.model-error { margin: 0; padding: 11px 13px; border-left: 3px solid #cf5b51; color: #9c3731; background: rgba(207,91,81,.07); font-size: 11px; }.model-loading { padding: 35px; color: var(--dim); font-family: var(--mono); font-size: 9px; text-align: center; letter-spacing: .1em; }
.model-toast { position: fixed; z-index: 100; right: 24px; bottom: 24px; padding: 11px 15px; border: 1px solid var(--line); border-radius: 7px; color: var(--canvas); background: var(--text); box-shadow: 0 15px 45px rgba(0,0,0,.2); font-size: 11px; cursor: pointer; }
.model-backdrop { position: fixed; z-index: 90; inset: 0; display: grid; padding: 24px; place-items: center; overflow: auto; background: rgba(16,16,16,.58); backdrop-filter: blur(5px); }
.provider-editor, .delete-dialog { width: min(720px, 100%); border: 1px solid var(--line); border-radius: 10px; color: var(--text); background: var(--canvas); box-shadow: 0 30px 90px rgba(0,0,0,.32); }
.provider-editor > header { display: flex; align-items: center; justify-content: space-between; padding: 18px 20px; border-bottom: 1px solid var(--line); }.provider-editor h2, .delete-dialog h2 { margin: 3px 0 0; font-size: 19px; font-weight: 560; }.provider-editor > header button { width: 32px; padding: 0; font-size: 18px; }
.editor-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 15px; padding: 20px; }.editor-grid label, .editor-grid > div { display: grid; align-content: start; gap: 6px; }.editor-grid .wide { grid-column: 1 / -1; }.editor-grid label > span, .editor-grid > div > span { color: var(--dim); font-family: var(--mono); font-size: 8px; letter-spacing: .08em; }.editor-grid em { float: right; font-size: 7px; font-style: normal; }
.editor-grid input:not([type="checkbox"]), .editor-grid textarea { width: 100%; box-sizing: border-box; border: 1px solid var(--line); border-radius: 5px; color: var(--text); background: var(--surface); font: 10px var(--mono); outline: none; }.editor-grid input:not([type="checkbox"]) { height: 34px; padding: 0 10px; }.editor-grid textarea { padding: 9px 10px; resize: vertical; }.editor-grid input:focus, .editor-grid textarea:focus { border-color: var(--text); box-shadow: 0 0 0 3px var(--line-soft); }
.switch-field { grid-template-columns: 34px 1fr; align-items: center; min-height: 52px; padding: 0 12px; border: 1px solid var(--line-soft); border-radius: 6px; }.switch-field input { appearance: none; width: 30px; height: 17px; margin: 0; border: 1px solid var(--muted); border-radius: 20px; background: var(--surface); transition: 150ms ease; }.switch-field input::after { display: block; width: 11px; height: 11px; margin: 2px; border-radius: 50%; background: var(--muted); content: ''; transition: 150ms ease; }.switch-field input:checked { border-color: var(--text); background: var(--text); }.switch-field input:checked::after { background: var(--canvas); transform: translateX(13px); }.switch-field input:focus-visible { outline: 2px solid var(--text); outline-offset: 2px; }.switch-field span { display: grid; gap: 3px; }.switch-field b { font-size: 11px; font-weight: 520; }.switch-field small { letter-spacing: 0; }
.provider-editor > footer, .delete-dialog > footer { display: flex; justify-content: flex-end; gap: 8px; padding: 14px 20px; border-top: 1px solid var(--line); }.provider-editor button.save { color: var(--canvas); border-color: var(--text); background: var(--text); }
.delete-dialog { display: grid; grid-template-columns: 54px 1fr; padding: 20px; gap: 16px; }.delete-mark { display: grid; width: 46px; height: 46px; place-items: center; border: 1px solid #cf5b51; border-radius: 50%; color: #cf5b51; font: 20px var(--mono); }.delete-dialog p { max-width: 520px; color: var(--dim); font-size: 11px; line-height: 1.6; }.delete-dialog > footer { grid-column: 1 / -1; margin: 0 -20px -20px; }
.model-dialog-enter-active,.model-dialog-leave-active,.model-toast-enter-active,.model-toast-leave-active { transition: opacity 150ms ease, transform 150ms ease; }.model-dialog-enter-from,.model-dialog-leave-to,.model-toast-enter-from,.model-toast-leave-to { opacity: 0; transform: translateY(5px); }
@media (max-width: 850px) { .route-channel { grid-template-columns: 36px 1fr; }.route-field { grid-column: 1 / -1; }.route-arrow { display: none; }.editor-grid { grid-template-columns: 1fr; }.editor-grid .wide { grid-column: auto; } }
</style>
