<script setup>
import { computed, ref, useId } from 'vue'
import { readTraceArtifact, downloadArtifact } from '../services/consoleApi.js'
const props = defineProps({ title: String, record: { type: Object, required: true } })
const expanded = ref(false)
const loading = ref(false)
const loaded = ref(false)
const fullText = ref('')
const error = ref('')
const panelId = useId()
const text = computed(() => loaded.value ? fullText.value : props.record.text || '')
const formatted = computed(() => { try { return JSON.stringify(JSON.parse(text.value), null, 2) } catch { return text.value } })
const reasoning = computed(() => {
  if (props.record.traceKind !== 'model_response' || (props.record.artifactId && !loaded.value)) return ''
  const frames = text.value.startsWith('data:')
    ? text.value.split('\n').filter(line => line.startsWith('data:')).map(line => line.slice(5).trim())
    : [text.value]
  return frames.map(frame => {
    try {
      const item = JSON.parse(frame)?.choices?.[0]
      const message = item?.delta || item?.message || {}
      return message.reasoning_content || message.reasoning || ''
    } catch { return '' }
  }).join('')
})
async function toggle() {
  expanded.value = !expanded.value
  if (!expanded.value || loaded.value || !props.record.artifactId) return
  loading.value = true
  error.value = ''
  try { fullText.value = await readTraceArtifact(props.record.artifactId); loaded.value = true }
  catch (cause) { error.value = cause.message || '加载失败，请收起后重试' }
  finally { loading.value = false }
}
async function download() {
  error.value = ''
  try { await downloadArtifact(props.record.artifactId) }
  catch (cause) { error.value = cause.message || '下载失败' }
}
</script>

<template>
  <section class="execution-trace">
    <button type="button" class="trace-trigger" :aria-expanded="expanded" :aria-controls="panelId" @click="toggle">
      <span class="trace-chevron" aria-hidden="true">{{ expanded ? '−' : '+' }}</span>
      <strong>{{ title }}</strong>
      <span class="trace-kind">{{ record.traceKind }}</span>
    </button>
    <div v-if="expanded" :id="panelId" class="trace-body">
      <p v-if="record.callId" class="trace-id">调用 {{ record.callId }}</p>
      <p v-if="record.executionInvocationId" class="trace-id">Agent 执行 {{ record.executionInvocationId }}</p>
      <p v-if="loading" role="status">正在加载完整记录…</p>
      <p v-if="error" role="alert" class="trace-error">{{ error }}</p>
      <template v-if="record.traceKind === 'model_response' && !loading && !error">
        <h4>模型接口返回的推理内容</h4>
        <pre v-if="reasoning">{{ reasoning }}</pre>
        <p v-else>接口未返回独立推理字段；下方保留原始响应，不代表模型没有进行推理。</p>
      </template>
      <p v-if="record.archived === false" class="trace-error">未生成独立归档文件，当前正文保留在事件记录中。</p>
      <h4>完整记录{{ record.artifactId && !loaded ? '预览' : '' }}</h4>
      <pre tabindex="0">{{ formatted }}</pre>
      <button v-if="record.artifactId" type="button" class="trace-download" @click="download">下载原始记录</button>
    </div>
  </section>
</template>

<style scoped>
.execution-trace { overflow: hidden; border: 0; border-radius: 0; background: transparent; }
.trace-trigger { display: flex; gap: 9px; align-items: center; width: 100%; min-height: 32px; padding: 4px 0; border: 0; color: var(--dim); background: transparent; font-size: 12px; text-align: left; cursor: pointer; }
.trace-trigger:focus-visible, .trace-download:focus-visible, pre:focus-visible { outline: 2px solid var(--accent); outline-offset: -2px; }
.trace-trigger:hover { color: var(--muted); background: transparent; }
.trace-trigger strong { font-weight: 520; }
.trace-chevron { display: grid; width: 24px; height: 24px; border-radius: 6px; place-items: center; color: var(--dim); background: var(--surface-soft); font: 15px var(--mono); }
.trace-kind { margin-left: auto; color: var(--dim); font: 11px var(--mono); overflow-wrap: anywhere; }
.trace-body { margin: 5px 0 3px 12px; padding: 2px 0 12px 20px; border-top: 0; border-left: 1px solid var(--line-soft); font-size: 12px; }
.trace-id { color: var(--muted); overflow-wrap: anywhere; font-family: var(--mono); }
h4 { margin: 16px 0 8px; font-weight: 600; }
pre { padding: 12px; background: var(--code-block-bg); color: var(--code-block-text); font: 12px/1.6 var(--mono); white-space: pre-wrap; overflow-wrap: anywhere; max-height: 480px; overflow: auto; border-radius: 6px; }
.trace-error { color: var(--danger); }
.trace-download { background: var(--surface); border: 1px solid var(--line); border-radius: 6px; padding: 7px 12px; cursor: pointer; }
</style>
