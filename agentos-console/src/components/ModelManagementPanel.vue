<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import AppSelect from './AppSelect.vue'
import {
  createModelProvider, deleteModelProvider, getModelManagement,
  testModelProvider, updateModelProvider
} from '../services/consoleApi.js'

const snapshot = ref({ providers: [], models: [] })
const loading = ref(true)
const busy = ref('')
const error = ref('')
const toast = ref('')
const editor = ref(null)
const editorInitial = ref(null)
const discardOpen = ref(false)
const deleteTarget = ref(null)
const firstField = ref(null)
const advancedOpen = ref(false)
const apiKeyVisible = ref(false)
let editorReturnFocus = null

const defaultAdvancedSettings = () => ({
  inputTokens: null,
  outputTokens: null,
  toolCallRounds: 500,
  imageInput: false,
  reasoningMode: 'DEFAULT',
  temperature: null,
  topP: null,
  topK: null
})

const providerPresets = {
  OPENAI: {
    label: 'OpenAI', description: 'Official Chat Completions API',
    endpoint: 'https://api.openai.com/v1/chat/completions',
    models: ['gpt-5.2', 'gpt-5.2-mini'], responseFormat: 'JSON_SCHEMA'
  },
  DEEPSEEK: {
    label: 'DeepSeek', description: 'DeepSeek official OpenAI-compatible API',
    endpoint: 'https://api.deepseek.com/chat/completions',
    models: ['deepseek-v4-pro', 'deepseek-v4-flash'], responseFormat: 'JSON_OBJECT',
    advancedSettings: { inputTokens: 1000000, outputTokens: 384000 }
  },
  GLM: {
    label: 'GLM', description: 'Z.ai / 智谱开放平台',
    endpoint: 'https://open.bigmodel.cn/api/paas/v4/chat/completions',
    models: ['glm-5.2', 'glm-5.1', 'glm-5-turbo', 'glm-4.7', 'glm-4.7-flash'],
    responseFormat: 'JSON_OBJECT'
  },
  QWEN: {
    label: 'Qwen', description: '阿里云百炼 OpenAI-compatible API',
    endpoint: 'https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions',
    models: ['qwen3.8-max', 'qwen3.7-plus', 'qwen3.7-flash', 'qwen3-coder-plus'],
    responseFormat: 'JSON_OBJECT',
    advancedSettings: { inputTokens: 1000000 }
  },
  MINIMAX: {
    label: 'MiniMax', description: 'MiniMax OpenAI-compatible endpoint',
    endpoint: 'https://api.minimax.chat/v1/text/chatcompletion_v2',
    models: ['MiniMax-M2.5'], responseFormat: 'JSON_OBJECT'
  },
  CC_SWITCH: {
    label: 'CC Switch', description: 'Local compatibility proxy',
    endpoint: 'http://127.0.0.1:3456/v1/chat/completions',
    models: ['gpt-5.2'], responseFormat: 'JSON_SCHEMA'
  },
  OPENAI_COMPATIBLE: {
    label: '自定义模型', description: 'Custom OpenAI-compatible endpoint',
    endpoint: 'https://api.openai.com/v1/chat/completions',
    models: ['gpt-5.2'], responseFormat: 'JSON_SCHEMA'
  }
}
const providerTypes = Object.entries(providerPresets).map(([value, preset]) => ({
  value, label: preset.label, description: preset.description
}))
const builtInProviderTypes = providerTypes.filter(option => option.value !== 'OPENAI_COMPATIBLE')
const responseFormats = [
  { value: 'JSON_SCHEMA', label: 'JSON Schema', description: 'Strict planner output' },
  { value: 'JSON_OBJECT', label: 'JSON Object', description: 'Loose structured output' },
  { value: 'NONE', label: 'None', description: 'Prompt-only compatibility' }
]
const apiFormats = [
  { value: 'CHAT_COMPLETIONS', label: 'OpenAI Chat Completions 格式', description: 'POST /chat/completions' }
]
const apiKeyLinks = {
  OPENAI: 'https://platform.openai.com/api-keys',
  DEEPSEEK: 'https://platform.deepseek.com/api_keys',
  GLM: 'https://open.bigmodel.cn/usercenter/apikeys',
  QWEN: 'https://bailian.console.aliyun.com/?apiKey=1',
  MINIMAX: 'https://platform.minimaxi.com/user-center/basic-information/interface-key'
}
const editorModelType = computed(() => editor.value?.providerType === 'OPENAI_COMPATIBLE'
  ? 'CUSTOM' : 'BUILT_IN')
const editorModelOptions = computed(() => {
  if (!editor.value) return []
  const values = editor.value.modelsText.split(/[\n,]/).map(item => item.trim()).filter(Boolean)
  if (editor.value.defaultModel) values.unshift(editor.value.defaultModel)
  return [...new Set(values)].map(value => ({ value, label: value }))
})
const editorDirty = computed(() => Boolean(
  editor.value
  && editorInitial.value
  && JSON.stringify(editor.value) !== JSON.stringify(editorInitial.value)
))

async function load() {
  loading.value = true
  error.value = ''
  try {
    const value = await getModelManagement()
    snapshot.value = {
      providers: Array.isArray(value?.providers) ? value.providers : [],
      models: Array.isArray(value?.models) ? value.models : []
    }
  } catch (cause) {
    error.value = explainManagementError(cause, '模型配置读取失败')
  } finally {
    loading.value = false
  }
}

function explainManagementError(cause, fallback) {
  if (cause?.status === 403) {
    return '当前账号缺少 ADMIN 角色，无法管理模型配置。请联系管理员在「用户管理」中授予 ADMIN 角色。'
  }
  return cause?.message || fallback
}

function blankForm() {
  const preset = providerPresets.OPENAI
  return {
    id: '', displayName: preset.label, providerType: 'OPENAI',
    protocol: 'CHAT_COMPLETIONS', endpoint: preset.endpoint,
    customEndpoint: 'https://api.openai.com/v1', fullUrl: false,
    apiKey: '', modelsText: preset.models.join('\n'), defaultModel: preset.models[0],
    responseFormat: preset.responseFormat, reasoningSplit: false,
    advancedSettings: defaultAdvancedSettings(), enabled: true, hasApiKey: false
  }
}

function cloneEditor(value) {
  return { ...value, advancedSettings: { ...value.advancedSettings } }
}

function openCreate() {
  editorReturnFocus = document.activeElement
  error.value = ''
  editor.value = blankForm()
  editorInitial.value = cloneEditor(editor.value)
  discardOpen.value = false
  advancedOpen.value = false
  apiKeyVisible.value = false
  focusEditor()
}

function openEdit(provider) {
  editorReturnFocus = document.activeElement
  error.value = ''
  const custom = provider.providerType === 'OPENAI_COMPATIBLE'
  editor.value = {
    ...provider,
    apiKey: '',
    customEndpoint: custom ? baseEndpoint(provider.endpoint) : provider.endpoint,
    fullUrl: !custom || !provider.endpoint.endsWith('/chat/completions'),
    modelsText: provider.models.join('\n'),
    advancedSettings: { ...defaultAdvancedSettings(), ...(provider.advancedSettings || {}) }
  }
  editorInitial.value = cloneEditor(editor.value)
  discardOpen.value = false
  advancedOpen.value = false
  apiKeyVisible.value = false
  focusEditor()
}

function baseEndpoint(endpoint) {
  return String(endpoint || '').replace(/\/+$/, '').replace(/\/chat\/completions$/, '')
}

function effectiveEndpoint(value) {
  if (value.providerType !== 'OPENAI_COMPATIBLE') return value.endpoint
  const endpoint = String(value.customEndpoint || '').trim().replace(/\/+$/, '')
  if (!endpoint || value.fullUrl) return endpoint
  return `${endpoint}/chat/completions`
}

function focusEditor() {
  nextTick(() => {
    firstField.value?.focus?.()
    firstField.value?.$el?.querySelector('button')?.focus()
  })
}

async function destroyEditor() {
  const returnFocus = editorReturnFocus
  editor.value = null
  editorInitial.value = null
  discardOpen.value = false
  advancedOpen.value = false
  apiKeyVisible.value = false
  editorReturnFocus = null
  await nextTick()
  returnFocus?.focus?.()
}

function requestCloseEditor() {
  if (busy.value) return
  if (editorDirty.value) {
    discardOpen.value = true
    return
  }
  void destroyEditor()
}

function confirmDiscardEditor() {
  if (!busy.value) void destroyEditor()
}

function updateProviderType(value) {
  const preset = providerPresets[value]
  if (!preset) return
  editor.value = {
    ...editor.value,
    providerType: value,
    displayName: value === 'OPENAI_COMPATIBLE' ? '' : preset.label,
    endpoint: preset.endpoint,
    customEndpoint: value === 'OPENAI_COMPATIBLE' ? baseEndpoint(preset.endpoint) : preset.endpoint,
    fullUrl: false,
    modelsText: preset.models.join('\n'),
    defaultModel: preset.models[0],
    responseFormat: preset.responseFormat,
    advancedSettings: {
      ...defaultAdvancedSettings(),
      ...(preset.advancedSettings || {})
    }
  }
}

function switchModelType(type) {
  updateProviderType(type === 'CUSTOM' ? 'OPENAI_COMPATIBLE' : 'OPENAI')
  focusEditor()
}

function resetEditor() {
  editor.value = cloneEditor(editorInitial.value)
  advancedOpen.value = false
  apiKeyVisible.value = false
  focusEditor()
}

function optionalNumber(value) {
  return value === '' || value === null || value === undefined ? null : Number(value)
}

function setAdvancedValue(field, value) {
  editor.value.advancedSettings[field] = value
}

function trapEditorFocus(event) {
  if (event.key !== 'Tab') return
  const items = [...event.currentTarget.querySelectorAll('a[href], button:not(:disabled), input:not(:disabled), textarea:not(:disabled)')]
  if (!items.length) return
  const first = items[0]
  const last = items[items.length - 1]
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault()
    first.focus()
  }
}

async function saveProvider() {
  const value = editor.value
  const models = value.providerType === 'OPENAI_COMPATIBLE'
    ? [value.defaultModel.trim()]
    : value.modelsText.split(/[\n,]/).map(item => item.trim()).filter(Boolean)
  const payload = {
    displayName: value.displayName || value.defaultModel,
    providerType: value.providerType,
    protocol: 'CHAT_COMPLETIONS',
    endpoint: effectiveEndpoint(value),
    apiKey: value.apiKey,
    models,
    defaultModel: value.defaultModel,
    responseFormat: value.responseFormat,
    reasoningSplit: value.reasoningSplit,
    advancedSettings: {
      ...value.advancedSettings,
      inputTokens: optionalNumber(value.advancedSettings.inputTokens),
      outputTokens: optionalNumber(value.advancedSettings.outputTokens),
      toolCallRounds: optionalNumber(value.advancedSettings.toolCallRounds),
      temperature: optionalNumber(value.advancedSettings.temperature),
      topP: optionalNumber(value.advancedSettings.topP),
      topK: optionalNumber(value.advancedSettings.topK)
    },
    enabled: value.enabled
  }
  busy.value = 'save'
  error.value = ''
  try {
    if (value.id) await updateModelProvider(value.id, payload)
    else await createModelProvider(payload)
    toast.value = value.id
      ? '模型配置已更新'
      : value.enabled
        ? '模型已创建并启用；现在可以在工作台选择'
        : '模型已创建但未启用'
    await destroyEditor()
    await load()
  } catch (cause) {
    error.value = explainManagementError(cause, '保存失败')
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
    error.value = explainManagementError(cause, '连接测试失败')
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
      advancedSettings: provider.advancedSettings,
      enabled: !provider.enabled
    })
    await load()
  } catch (cause) {
    error.value = explainManagementError(cause, '状态更新失败')
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
    toast.value = '模型配置已删除'
    await load()
  } catch (cause) {
    error.value = explainManagementError(cause, '删除失败')
  } finally {
    busy.value = ''
  }
}

function statusLabel(provider) {
  if (!provider.enabled) return '已停用'
  if (provider.lastStatus === 'CONNECTED') return '已启用 · 已连接'
  if (provider.lastStatus === 'FAILED') return '已启用 · 测试失败'
  return '已启用 · 待测试'
}

function statusClass(provider) {
  if (!provider.enabled) return 'disabled'
  return String(provider.lastStatus || 'UNTESTED').toLowerCase()
}

function keydown(event) {
  if (event.key !== 'Escape' || event.defaultPrevented) return
  if (discardOpen.value) discardOpen.value = false
  else if (deleteTarget.value && !busy.value) deleteTarget.value = null
  else requestCloseEditor()
}

onMounted(() => {
  load()
  document.addEventListener('keydown', keydown)
})
onUnmounted(() => document.removeEventListener('keydown', keydown))
</script>

<template>
  <section class="model-console" aria-label="Model provider management">
    <div class="provider-toolbar">
      <div><small>MODEL REGISTRY</small><strong>{{ snapshot.models.length }} configured models · {{ snapshot.models.filter(model => model.enabled).length }} enabled</strong></div>
      <button class="add-provider" type="button" @click="openCreate"><b>＋</b> ADD MODEL</button>
    </div>

    <p v-if="error" class="model-error" role="alert">{{ error }}</p>
    <div v-if="loading" class="model-loading">READING POSTGRESQL CONFIGURATION…</div>
    <div v-else class="provider-grid">
      <article v-for="provider in snapshot.providers" :key="provider.id" class="provider-card" :class="{ disabled: !provider.enabled }">
        <header>
          <div class="provider-monogram">{{ provider.displayName.slice(0, 2).toUpperCase() }}</div>
          <div><small>{{ provider.modelType === 'CUSTOM' ? 'CUSTOM MODEL' : `BUILT-IN · ${provider.providerType}` }}</small><h3>{{ provider.displayName }}</h3></div>
          <span class="provider-status" :class="statusClass(provider)"><i></i>{{ statusLabel(provider) }}</span>
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
        <b>＋</b><strong>ADD THE FIRST MODEL</strong><span>选择内置服务商，或配置自定义 OpenAI 兼容模型</span>
      </button>
    </div>

    <transition name="model-toast"><div v-if="toast" class="model-toast" role="status" @click="toast = ''">{{ toast }}</div></transition>

    <Teleport to="body">
      <transition name="model-dialog">
        <div v-if="editor" class="model-backdrop">
        <form class="provider-editor" role="dialog" aria-modal="true" aria-labelledby="providerEditorTitle" @keydown="trapEditorFocus" @submit.prevent="saveProvider">
          <header>
            <button class="editor-back" type="button" aria-label="返回" @click="requestCloseEditor">
              <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m15 5-7 7 7 7" /></svg>
            </button>
            <div class="editor-heading">
              <small>MODEL REGISTRY</small>
              <h2 id="providerEditorTitle">{{ editor.id ? '编辑模型' : editorModelType === 'CUSTOM' ? '添加自定义模型' : '添加内置模型' }}</h2>
            </div>
            <button class="editor-close" type="button" aria-label="关闭" @click="requestCloseEditor">×</button>
          </header>
          <div class="editor-scroll">
            <p v-if="error" class="provider-editor-error" role="alert">{{ error }}</p>
            <div class="editor-primary">
              <div class="model-kind-switch" role="tablist" aria-label="模型类型">
                <button type="button" role="tab" :aria-selected="editorModelType === 'BUILT_IN'" :class="{ active: editorModelType === 'BUILT_IN' }" @click="switchModelType('BUILT_IN')"><b>内置模型</b><small>选择预设服务商</small></button>
                <button type="button" role="tab" :aria-selected="editorModelType === 'CUSTOM'" :class="{ active: editorModelType === 'CUSTOM' }" @click="switchModelType('CUSTOM')"><b>自定义模型</b><small>连接兼容接口</small></button>
              </div>
              <div v-if="editorModelType === 'BUILT_IN'" class="primary-field">
                <label id="providerTypeLabel"><span><b aria-hidden="true">*</b>服务商</span></label>
                <AppSelect
                  ref="firstField"
                  :model-value="editor.providerType"
                  :options="builtInProviderTypes"
                  description-key="description"
                  aria-labelledby="providerTypeLabel"
                  @update:model-value="updateProviderType"
                />
              </div>
              <div v-if="editorModelType === 'BUILT_IN'" class="primary-field">
                <label id="defaultModelLabel"><span><b aria-hidden="true">*</b>模型</span></label>
                <AppSelect v-model="editor.defaultModel" :options="editorModelOptions" placeholder="选择模型" aria-labelledby="defaultModelLabel" />
              </div>
              <template v-else>
                <div class="primary-field">
                  <label id="apiFormatLabel"><span><b aria-hidden="true">*</b>API 格式</span></label>
                  <AppSelect v-model="editor.protocol" :options="apiFormats" aria-labelledby="apiFormatLabel" />
                </div>
                <label class="primary-field custom-address-field">
                  <span>
                    <span><b aria-hidden="true">*</b>自定义请求地址</span>
                    <span class="full-url-control">
                      <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M10 13a5 5 0 0 0 7.1.1l2-2a5 5 0 0 0-7.1-7.1l-1.1 1.1M14 11a5 5 0 0 0-7.1-.1l-2 2A5 5 0 0 0 12 20l1.1-1.1" /></svg>
                      完整 URL
                      <input v-model="editor.fullUrl" type="checkbox" role="switch" :aria-checked="editor.fullUrl" />
                    </span>
                  </span>
                  <small>填写兼容 OpenAI API 的服务端地址；关闭“完整 URL”时会自动补充 /chat/completions。</small>
                  <input v-model.trim="editor.customEndpoint" required type="url" autocomplete="off" :placeholder="editor.fullUrl ? '例如 https://api.example.com/v1/chat/completions' : '例如 https://api.openai.com/v1'" />
                </label>
                <label class="primary-field">
                  <span><span><b aria-hidden="true">*</b>模型 ID</span></span>
                  <input v-model.trim="editor.defaultModel" required autocomplete="off" placeholder="输入模型 ID" />
                </label>
                <label class="primary-field display-name-field">
                  <span><span>模型展示名称</span></span>
                  <small>在模型列表中展示的名称，未设置时默认显示 Model ID。</small>
                  <span class="counted-input"><input v-model.trim="editor.displayName" maxlength="32" autocomplete="off" placeholder="请输入模型展示名称" /><i>{{ editor.displayName.length }}/32</i></span>
                </label>
              </template>
              <label class="primary-field api-key-field">
                <span>
                  <span><b aria-hidden="true">*</b> API 密钥</span>
                  <a v-if="apiKeyLinks[editor.providerType]" :href="apiKeyLinks[editor.providerType]" target="_blank" rel="noreferrer">获取 API 密钥</a>
                </span>
                <span class="secret-input">
                  <input
                    v-model="editor.apiKey"
                    :required="!editor.id && editor.providerType !== 'CC_SWITCH'"
                    :type="apiKeyVisible ? 'text' : 'password'"
                    autocomplete="new-password"
                    :placeholder="editor.id && editor.hasApiKey ? '留空以保留当前密钥' : '请输入 API Key'"
                  />
                  <button type="button" :aria-label="apiKeyVisible ? '隐藏 API 密钥' : '显示 API 密钥'" :aria-pressed="apiKeyVisible" @click="apiKeyVisible = !apiKeyVisible">
                    <svg viewBox="0 0 24 24" aria-hidden="true">
                      <path v-if="apiKeyVisible" d="M2.5 12s3.5-6 9.5-6 9.5 6 9.5 6-3.5 6-9.5 6-9.5-6-9.5-6zM12 9a3 3 0 1 1 0 6" />
                      <path v-else d="m3 3 18 18M10.6 10.6A2 2 0 0 0 13.4 13.4M9.5 5.3A10.6 10.6 0 0 1 12 5c6 0 9.5 7 9.5 7a16.8 16.8 0 0 1-2.1 3M6.2 6.2C3.8 7.8 2.5 12 2.5 12s3.5 7 9.5 7a9.7 9.7 0 0 0 3.2-.5" />
                    </svg>
                  </button>
                </span>
              </label>
            </div>

            <section class="advanced-section">
              <button
                class="advanced-toggle"
                type="button"
                :aria-expanded="advancedOpen"
                aria-controls="providerAdvancedPanel"
                @click="advancedOpen = !advancedOpen"
              >
                <span>高级配置</span>
                <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m9 6 6 6-6 6" /></svg>
              </button>
              <transition name="advanced-panel">
                <div v-if="advancedOpen" id="providerAdvancedPanel" class="advanced-panel-body">
                  <section class="advanced-group context-group" aria-labelledby="contextWindowTitle">
                    <h3 id="contextWindowTitle">上下文窗口 <span>Token</span></h3>
                    <div class="token-setting">
                      <label>
                        <b>输入</b>
                        <input v-model="editor.advancedSettings.inputTokens" type="number" min="1" max="2000000" inputmode="numeric" placeholder="请输入数值，留空则使用最佳默认值" />
                      </label>
                      <div class="quick-values" aria-label="输入 Token 快捷值">
                        <button v-for="item in [[128000, '128k'], [256000, '256k'], [512000, '512k'], [1000000, '1M']]" :key="item[0]" type="button" @click="setAdvancedValue('inputTokens', item[0])">{{ item[1] }}</button>
                      </div>
                    </div>
                    <div class="token-setting">
                      <label>
                        <b>输出</b>
                        <input v-model="editor.advancedSettings.outputTokens" type="number" min="1" max="512000" inputmode="numeric" placeholder="请输入数值，留空则使用最佳默认值" />
                      </label>
                      <div class="quick-values" aria-label="输出 Token 快捷值">
                        <button v-for="item in [[4000, '4k'], [16000, '16k'], [32000, '32k'], [128000, '128k']]" :key="item[0]" type="button" @click="setAdvancedValue('outputTokens', item[0])">{{ item[1] }}</button>
                      </div>
                    </div>
                  </section>

                  <section class="advanced-group">
                    <label class="stacked-field">
                      <span>工具调用轮数</span>
                      <input v-model="editor.advancedSettings.toolCallRounds" type="number" min="1" max="500" inputmode="numeric" />
                      <small>作为模型能力配置保存，最大 500 轮；实际执行仍受 Agent 安全预算限制。</small>
                    </label>
                  </section>

                  <fieldset class="advanced-group choice-group">
                    <legend>支持图片输入 <span class="info-dot" title="声明当前模型是否可接收图片内容">i</span></legend>
                    <div class="radio-row">
                      <label><input v-model="editor.advancedSettings.imageInput" type="radio" :value="true" /><span>支持</span></label>
                      <label><input v-model="editor.advancedSettings.imageInput" type="radio" :value="false" /><span>不支持</span></label>
                    </div>
                  </fieldset>

                  <fieldset class="advanced-group choice-group">
                    <legend>思考模式 <span class="info-dot" title="DeepSeek、GLM 与 Qwen 会转换为对应厂商参数">i</span></legend>
                    <div class="radio-row reasoning-row">
                      <label><input v-model="editor.advancedSettings.reasoningMode" type="radio" value="DEFAULT" /><span>跟随模型默认配置</span></label>
                      <label><input v-model="editor.advancedSettings.reasoningMode" type="radio" value="ENABLED" /><span>开启</span></label>
                      <label><input v-model="editor.advancedSettings.reasoningMode" type="radio" value="DISABLED" /><span>关闭</span></label>
                    </div>
                  </fieldset>

                  <section class="advanced-group sampling-group" aria-labelledby="samplingTitle">
                    <h3 id="samplingTitle">采样参数 <span class="info-dot" title="留空时不发送参数，由模型服务商选择默认值">i</span></h3>
                    <label class="parameter-field"><b>Temperature</b><input v-model="editor.advancedSettings.temperature" type="number" min="0" max="2" step="0.1" inputmode="decimal" placeholder="留空使用最佳配置，或输入 0 ～ 2 之间的数值" /></label>
                    <label class="parameter-field"><b>Top P</b><input v-model="editor.advancedSettings.topP" type="number" min="0" max="1" step="0.05" inputmode="decimal" placeholder="留空使用最佳配置，或输入 0 ～ 1 之间的数值" /></label>
                    <label class="parameter-field"><b>Top K</b><input v-model="editor.advancedSettings.topK" type="number" min="1" max="100" inputmode="numeric" placeholder="留空使用最佳配置，或输入 1 ～ 100 之间的数值" /></label>
                  </section>

                  <section class="advanced-group connection-group" aria-labelledby="connectionTitle">
                    <h3 id="connectionTitle">连接与兼容性</h3>
                    <div class="editor-grid">
                      <label v-if="editor.providerType !== 'OPENAI_COMPATIBLE'"><span>显示名称</span><input v-model.trim="editor.displayName" required autocomplete="off" placeholder="Production OpenAI" /></label>
                      <label v-if="editor.providerType !== 'OPENAI_COMPATIBLE'" class="wide"><span>Chat Completions 地址</span><input v-model.trim="editor.endpoint" required type="url" autocomplete="off" placeholder="https://api.openai.com/v1/chat/completions" /></label>
                      <label v-if="editorModelType === 'BUILT_IN'" class="wide"><span>可用模型 <em>每行一个或用逗号分隔</em></span><textarea v-model="editor.modelsText" required rows="3" placeholder="gpt-5.2&#10;gpt-5.2-mini"></textarea></label>
                      <div><span>Planner 响应格式</span><AppSelect v-model="editor.responseFormat" :options="responseFormats" aria-label="Planner 响应格式" /></div>
                      <label class="switch-field"><input v-model="editor.reasoningSplit" type="checkbox" /><span><b>拆分思考内容</b><small>单独读取 reasoning_content</small></span></label>
                      <label class="switch-field"><input v-model="editor.enabled" type="checkbox" /><span><b>启用模型</b><small>允许在工作台中选择使用</small></span></label>
                    </div>
                  </section>
                </div>
              </transition>
            </section>
          </div>
          <footer>
            <p><span aria-hidden="true">i</span> 连通性测试会发起一次真实请求，会消耗少量模型 Token</p>
            <div><button type="button" @click="resetEditor">重置</button><button class="save" type="submit" :disabled="busy === 'save'">{{ busy === 'save' ? '保存中…' : editor.id ? '保存修改' : '添加模型' }}</button></div>
          </footer>
        </form>
        </div>
      </transition>
    </Teleport>

    <Teleport to="body">
      <transition name="model-dialog">
        <div v-if="discardOpen" class="model-backdrop discard-backdrop">
          <section class="discard-dialog" role="alertdialog" aria-modal="true" aria-labelledby="discardProviderTitle" aria-describedby="discardProviderDescription">
            <div class="discard-mark" aria-hidden="true">!</div>
            <div>
              <small>UNSAVED CHANGES</small>
              <h2 id="discardProviderTitle">放弃本次修改？</h2>
              <p id="discardProviderDescription">尚未保存的模型地址、密钥与高级配置都会丢失。</p>
            </div>
            <footer>
              <button type="button" @click="discardOpen = false">继续编辑</button>
              <button class="danger" type="button" @click="confirmDiscardEditor">放弃修改</button>
            </footer>
          </section>
        </div>
      </transition>
    </Teleport>

    <Teleport to="body">
      <transition name="model-dialog">
        <div v-if="deleteTarget" class="model-backdrop" @click.self="deleteTarget = null">
          <section class="delete-dialog" role="alertdialog" aria-modal="true" aria-labelledby="deleteProviderTitle">
            <div class="delete-mark">!</div><div><small>DESTRUCTIVE OPERATION</small><h2 id="deleteProviderTitle">Delete {{ deleteTarget.displayName }}?</h2><p>模型配置、加密凭据和请求地址将被永久删除；工作台将不再显示该模型。</p></div>
            <footer><button type="button" @click="deleteTarget = null">CANCEL</button><button class="danger" type="button" :disabled="busy === `delete:${deleteTarget.id}`" @click="confirmDelete">DELETE PROVIDER</button></footer>
          </section>
        </div>
      </transition>
    </Teleport>
  </section>
</template>

<style scoped>
.model-console { display: grid; gap: 18px; color: var(--text); }
.provider-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.provider-toolbar small, .provider-card small, .provider-editor small, .delete-dialog small, .discard-dialog small { color: var(--dim); font-family: var(--mono); font-size: 8px; letter-spacing: .14em; }
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
.provider-card > footer { display: flex; flex-wrap: wrap; gap: 6px; padding: 12px 15px; }.provider-card button, .provider-editor button, .delete-dialog button, .discard-dialog button { min-height: 30px; padding: 0 10px; border: 1px solid var(--line); border-radius: 5px; color: var(--text); background: var(--surface); font-family: var(--mono); font-size: 8px; letter-spacing: .06em; }.provider-card button:hover, .provider-editor button:hover, .discard-dialog button:hover { border-color: var(--text); }.provider-card button.danger, .delete-dialog button.danger, .discard-dialog button.danger { margin-left: auto; color: #a13f38; }.provider-card button:focus-visible, .add-provider:focus-visible, .provider-editor button:focus-visible, .delete-dialog button:focus-visible, .discard-dialog button:focus-visible { outline: 2px solid var(--text); outline-offset: 2px; }
.empty-provider { display: grid; min-height: 240px; place-items: center; align-content: center; gap: 9px; border: 1px dashed var(--muted); border-radius: 9px; color: var(--dim); background: transparent; }.empty-provider b { font-size: 26px; font-weight: 200; }.empty-provider strong { color: var(--text); font-family: var(--mono); font-size: 10px; letter-spacing: .1em; }.empty-provider span { font-size: 10px; }
.model-error { margin: 0; padding: 11px 13px; border-left: 3px solid #cf5b51; color: #9c3731; background: rgba(207,91,81,.07); font-size: 11px; }.model-loading { padding: 35px; color: var(--dim); font-family: var(--mono); font-size: 9px; text-align: center; letter-spacing: .1em; }
.model-toast { position: fixed; z-index: 1100; right: 24px; bottom: 24px; padding: 11px 15px; border: 1px solid var(--line); border-radius: 7px; color: var(--canvas); background: var(--text); box-shadow: 0 15px 45px rgba(0,0,0,.2); font-size: 11px; cursor: pointer; }
.model-backdrop { position: fixed; z-index: 1000; inset: 0; display: grid; padding: 24px; place-items: center; overflow: auto; isolation: isolate; background: rgba(18,18,18,.46); backdrop-filter: blur(6px); }
.provider-editor, .delete-dialog, .discard-dialog { width: min(840px, 100%); border: 1px solid color-mix(in srgb, var(--line) 80%, transparent); border-radius: 14px; color: var(--text); background: var(--canvas); box-shadow: 0 28px 80px rgba(0,0,0,.28), 0 2px 8px rgba(0,0,0,.08); }
.provider-editor { display: grid; max-height: min(780px, calc(100dvh - 48px)); grid-template-rows: auto minmax(0, 1fr) auto; overflow: hidden; }
.provider-editor > header { display: grid; grid-template-columns: 34px 1fr 34px; align-items: center; gap: 12px; min-height: 72px; padding: 0 20px; border-bottom: 1px solid var(--line-soft); }
.editor-heading { display: grid; gap: 3px; min-width: 0; }.editor-heading small { color: var(--dim); font-family: var(--mono); font-size: 7px; letter-spacing: .13em; }
.provider-editor h2, .delete-dialog h2, .discard-dialog h2 { margin: 0; font-size: 18px; font-weight: 620; letter-spacing: -.025em; }
.provider-editor > header button { display: grid; width: 34px; min-height: 34px; padding: 0; place-items: center; border-color: transparent; border-radius: 50%; background: transparent; font-family: var(--font); font-size: 24px; letter-spacing: 0; }
.provider-editor > header button:hover { border-color: var(--line); background: var(--surface); }
.provider-editor > header svg { width: 20px; height: 20px; fill: none; stroke: currentColor; stroke-linecap: round; stroke-linejoin: round; stroke-width: 1.8; }
.editor-close { justify-self: end; }.editor-scroll { min-height: 0; overflow: auto; overscroll-behavior: contain; scrollbar-gutter: stable; }
.provider-editor-error { margin: 18px 28px 0; padding: 11px 13px; border-left: 3px solid #cf5b51; color: #9c3731; background: rgba(207,91,81,.07); font-size: 11px; line-height: 1.5; }
.editor-primary { display: grid; gap: 18px; padding: 24px 28px 22px; }
.model-kind-switch { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; padding: 5px; border: 1px solid var(--line-soft); border-radius: 10px; background: var(--surface); }
.model-kind-switch button { display: grid; min-height: 54px; align-content: center; gap: 4px; border-color: transparent; border-radius: 7px; background: transparent; text-align: left; }
.model-kind-switch button.active { border-color: var(--line); background: var(--canvas); box-shadow: 0 3px 12px rgba(0,0,0,.06); }
.model-kind-switch b { font-size: 12px; font-weight: 620; }.model-kind-switch small { letter-spacing: 0; }
.primary-field { display: grid; gap: 8px; margin: 0; }
.primary-field > label, .primary-field > span:first-child { display: flex; min-height: 20px; align-items: center; color: var(--text); font-size: 13px; font-weight: 560; }
.primary-field > label { justify-content: flex-start; }.api-key-field > span:first-child, .custom-address-field > span:first-child { justify-content: space-between; }
.primary-field b { margin-right: 4px; color: #e04c3f; font-weight: 650; }
.primary-field input, .editor-grid input:not([type="checkbox"]), .editor-grid textarea, .advanced-panel-body input[type="number"] { width: 100%; box-sizing: border-box; border: 1px solid var(--line); border-radius: 7px; color: var(--text); background: var(--surface); outline: none; transition: border-color 140ms ease, box-shadow 140ms ease, background 140ms ease; }
.primary-field input { height: 46px; padding: 0 14px; font: 13px var(--font); }
.primary-field input::placeholder, .editor-grid input::placeholder, .editor-grid textarea::placeholder, .advanced-panel-body input::placeholder { color: var(--dim); }
.primary-field input:focus, .editor-grid input:focus, .editor-grid textarea:focus, .advanced-panel-body input[type="number"]:focus { border-color: var(--text); background: var(--canvas); box-shadow: 0 0 0 3px var(--line-soft); }
.primary-field :deep(.app-select) { --app-select-height: 46px; --app-select-trigger-bg: var(--surface); }
.primary-field :deep(.app-select__trigger) { padding: 0 14px; border-radius: 7px; }
.primary-field :deep(.app-select__copy strong) { font-family: var(--font); font-size: 13px; font-weight: 500; }
.api-key-field a { color: var(--text); font-size: 12px; text-decoration: underline; text-underline-offset: 3px; }
.custom-address-field > small, .display-name-field > small { margin-top: -3px; color: var(--dim); font-size: 10px; font-weight: 400; line-height: 1.5; }
.full-url-control { display: inline-flex; align-items: center; gap: 7px; color: var(--text); font-size: 11px; font-weight: 520; cursor: pointer; }
.full-url-control svg { width: 16px; height: 16px; fill: none; stroke: currentColor; stroke-linecap: round; stroke-linejoin: round; stroke-width: 1.8; }
.full-url-control input { appearance: none; width: 34px !important; height: 19px !important; margin: 0; border: 1px solid var(--muted) !important; border-radius: 20px !important; background: var(--muted) !important; box-shadow: none !important; cursor: pointer; }
.full-url-control input::after { display: block; width: 13px; height: 13px; margin: 2px; border-radius: 50%; background: var(--canvas); content: ''; transition: transform 150ms ease; }
.full-url-control input:checked { border-color: var(--text) !important; background: var(--text) !important; }
.full-url-control input:checked::after { transform: translateX(15px); }
.full-url-control input:focus-visible { outline: 2px solid var(--text); outline-offset: 2px; }
.counted-input { position: relative; display: block !important; min-height: 0 !important; }
.counted-input input { padding-right: 58px; }
.counted-input i { position: absolute; top: 50%; right: 13px; color: var(--dim); font-family: var(--mono); font-size: 10px; font-style: normal; font-weight: 400; transform: translateY(-50%); }
.secret-input { position: relative; display: block !important; min-height: 0 !important; }
.secret-input input { padding-right: 50px; }
.secret-input button { position: absolute; top: 6px; right: 6px; display: grid; width: 34px; min-height: 34px; padding: 0; place-items: center; border-color: transparent; border-radius: 6px; background: transparent; }
.secret-input button:hover { border-color: var(--line); background: var(--canvas); }.secret-input svg { width: 20px; height: 20px; fill: none; stroke: currentColor; stroke-linecap: round; stroke-linejoin: round; stroke-width: 1.7; }
.advanced-section { margin: 0 28px 18px; border-top: 1px solid var(--line-soft); }
.advanced-toggle { display: flex; width: 100%; min-height: 52px; align-items: center; justify-content: flex-start; gap: 9px; padding: 0; border: 0; color: var(--text); background: transparent; font-family: var(--font) !important; font-size: 13px !important; font-weight: 600; letter-spacing: 0 !important; }
.advanced-toggle:hover { border-color: transparent !important; color: var(--dim); }.advanced-toggle svg { width: 16px; height: 16px; fill: none; stroke: currentColor; stroke-linecap: round; stroke-linejoin: round; stroke-width: 1.8; transition: transform 160ms ease; }.advanced-toggle[aria-expanded="true"] svg { transform: rotate(90deg); }
.advanced-panel-body { padding: 0 0 26px; }
.advanced-group { display: grid; gap: 12px; margin: 0; padding: 18px 0; border: 0; border-top: 1px solid var(--line-soft); }
.advanced-group:first-child { padding-top: 8px; border-top: 0; }
.advanced-group h3, .advanced-group legend, .stacked-field > span { margin: 0; padding: 0; color: var(--text); font-size: 13px; font-weight: 610; }
.advanced-group h3 > span:not(.info-dot) { margin-left: 5px; color: var(--dim); font-size: 11px; font-weight: 480; }
.info-dot { display: inline-grid; width: 16px; height: 16px; margin-left: 4px; place-items: center; border: 1px solid var(--dim); border-radius: 50%; color: var(--dim); font-family: var(--mono); font-size: 9px; font-weight: 700; cursor: help; vertical-align: 1px; }
.token-setting { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: center; gap: 14px; }
.token-setting > label { display: grid; min-width: 0; grid-template-columns: 92px minmax(0, 1fr); }
.token-setting > label b { display: flex; min-height: 42px; box-sizing: border-box; align-items: center; padding: 0 14px; border: 1px solid var(--line); border-right: 0; border-radius: 7px 0 0 7px; background: color-mix(in srgb, var(--surface) 72%, var(--canvas)); font-size: 12px; font-weight: 610; }
.token-setting input[type="number"] { height: 42px; padding: 0 13px; border-radius: 0 7px 7px 0; font: 11px var(--mono); }
.quick-values { display: flex; min-width: 236px; justify-content: flex-end; gap: 14px; }
.quick-values button { min-height: 28px; padding: 0; border: 0; border-radius: 0; color: var(--dim); background: transparent; font-family: var(--mono); font-size: 11px; letter-spacing: 0; text-decoration: underline; text-underline-offset: 3px; }
.quick-values button:hover { color: var(--text); border-color: transparent; }
.stacked-field { display: grid; gap: 9px; }
.stacked-field input[type="number"] { height: 42px; padding: 0 13px; font: 11px var(--mono); }
.stacked-field small { color: var(--dim); font-size: 9px; line-height: 1.45; }
.radio-row { display: flex; flex-wrap: wrap; gap: 22px; }
.radio-row label { display: inline-flex; align-items: center; gap: 8px; color: var(--text); font-size: 12px; cursor: pointer; }
.radio-row input { appearance: none; width: 16px; height: 16px; margin: 0; border: 1.5px solid var(--muted); border-radius: 50%; background: var(--canvas); }
.radio-row input:checked { border: 5px solid #3478f6; }
.radio-row input:focus-visible { outline: 2px solid var(--text); outline-offset: 3px; }
.reasoning-row { gap: 24px; }
.sampling-group { gap: 10px; }
.parameter-field { display: grid; grid-template-columns: 150px minmax(0, 1fr); }
.parameter-field b { display: flex; min-height: 42px; box-sizing: border-box; align-items: center; padding: 0 14px; border: 1px solid var(--line); border-right: 0; border-radius: 7px 0 0 7px; background: color-mix(in srgb, var(--surface) 72%, var(--canvas)); font-family: var(--mono); font-size: 11px; font-weight: 500; }
.parameter-field input[type="number"] { height: 42px; padding: 0 13px; border-radius: 0 7px 7px 0; font: 11px var(--mono); }
.connection-group { padding-bottom: 0; }
.editor-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 15px; padding: 2px 0 22px; }.editor-grid label, .editor-grid > div { display: grid; align-content: start; gap: 7px; }.editor-grid .wide { grid-column: 1 / -1; }.editor-grid label > span, .editor-grid > div > span { color: var(--dim); font-size: 11px; }.editor-grid em { float: right; font-size: 9px; font-style: normal; }
.editor-grid input:not([type="checkbox"]) { height: 38px; padding: 0 11px; font: 10px var(--mono); }.editor-grid textarea { padding: 10px 11px; font: 10px/1.5 var(--mono); resize: vertical; }
.switch-field { grid-template-columns: 34px 1fr; align-items: center; min-height: 52px; padding: 0 12px; border: 1px solid var(--line-soft); border-radius: 6px; }.switch-field input { appearance: none; width: 30px; height: 17px; margin: 0; border: 1px solid var(--muted); border-radius: 20px; background: var(--surface); transition: 150ms ease; }.switch-field input::after { display: block; width: 11px; height: 11px; margin: 2px; border-radius: 50%; background: var(--muted); content: ''; transition: 150ms ease; }.switch-field input:checked { border-color: var(--text); background: var(--text); }.switch-field input:checked::after { background: var(--canvas); transform: translateX(13px); }.switch-field input:focus-visible { outline: 2px solid var(--text); outline-offset: 2px; }.switch-field span { display: grid; gap: 3px; }.switch-field b { font-size: 11px; font-weight: 520; }.switch-field small { letter-spacing: 0; }
.provider-editor > footer, .delete-dialog > footer, .discard-dialog > footer { display: flex; justify-content: flex-end; gap: 8px; padding: 13px 20px; border-top: 1px solid var(--line-soft); }.provider-editor > footer { align-items: center; justify-content: space-between; min-height: 64px; box-sizing: border-box; background: color-mix(in srgb, var(--surface) 52%, var(--canvas)); }.provider-editor > footer p { display: flex; max-width: 390px; align-items: center; gap: 8px; margin: 0; color: var(--dim); font-size: 9.5px; line-height: 1.45; }.provider-editor > footer p > span { display: grid; width: 17px; height: 17px; flex: 0 0 auto; place-items: center; border-radius: 50%; color: var(--canvas); background: var(--dim); font-family: var(--mono); font-size: 9px; font-weight: 700; }.provider-editor > footer > div { display: flex; flex: 0 0 auto; gap: 8px; }.provider-editor > footer button { min-height: 38px; padding: 0 15px; font-family: var(--font); font-size: 11px; font-weight: 580; letter-spacing: 0; }.provider-editor button.save { color: var(--canvas); border-color: var(--text); background: var(--text); }
.delete-dialog { display: grid; grid-template-columns: 54px 1fr; padding: 20px; gap: 16px; }.delete-mark { display: grid; width: 46px; height: 46px; place-items: center; border: 1px solid #cf5b51; border-radius: 50%; color: #cf5b51; font: 20px var(--mono); }.delete-dialog p { max-width: 520px; color: var(--dim); font-size: 11px; line-height: 1.6; }.delete-dialog > footer { grid-column: 1 / -1; margin: 0 -20px -20px; }
.discard-backdrop { z-index: 1010; }.discard-dialog { display: grid; width: min(460px, 100%); grid-template-columns: 48px 1fr; gap: 16px; padding: 20px; }.discard-mark { display: grid; width: 42px; height: 42px; place-items: center; border: 1px solid #cf5b51; border-radius: 50%; color: #cf5b51; font: 18px var(--mono); }.discard-dialog p { margin: 7px 0 0; color: var(--dim); font-size: 11px; line-height: 1.6; }.discard-dialog > footer { grid-column: 1 / -1; margin: 4px -20px -20px; }
.model-dialog-enter-active,.model-dialog-leave-active,.model-toast-enter-active,.model-toast-leave-active,.advanced-panel-enter-active,.advanced-panel-leave-active { transition: opacity 160ms ease, transform 160ms ease; }.model-dialog-enter-from,.model-dialog-leave-to,.model-toast-enter-from,.model-toast-leave-to { opacity: 0; }.model-dialog-enter-active :is(.provider-editor,.delete-dialog,.discard-dialog),.model-dialog-leave-active :is(.provider-editor,.delete-dialog,.discard-dialog) { transition: transform 180ms ease, opacity 180ms ease; }.model-dialog-enter-from :is(.provider-editor,.delete-dialog,.discard-dialog),.model-dialog-leave-to :is(.provider-editor,.delete-dialog,.discard-dialog) { opacity: 0; transform: translateY(8px) scale(.985); }.advanced-panel-enter-from,.advanced-panel-leave-to { opacity: 0; transform: translateY(-5px); }
@media (max-width: 850px) { .editor-grid { grid-template-columns: 1fr; }.editor-grid .wide { grid-column: auto; }.token-setting { grid-template-columns: 1fr; }.quick-values { min-width: 0; justify-content: flex-start; }.provider-editor > footer { align-items: stretch; flex-direction: column; }.provider-editor > footer p { max-width: none; }.provider-editor > footer > div { justify-content: flex-end; } }
@media (max-width: 520px) { .model-backdrop { padding: 0; }.discard-backdrop { padding: 16px; }.provider-editor { width: 100%; max-height: 100dvh; min-height: 100dvh; border: 0; border-radius: 0; }.provider-editor > header { padding: 0 12px; }.editor-primary { padding: 22px 16px; }.advanced-section { margin-inline: 16px; } }
</style>
