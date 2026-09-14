<script setup>
import { ref } from 'vue'
import { downloadArtifact } from '../services/consoleApi.js'
import { useLocale } from '../composables/useLocale.js'

const props = defineProps({
  artifact: { type: Object, required: true }
})

const { t } = useLocale()
const downloading = ref(false)
const error = ref('')

function sizeLabel(bytes) {
  const size = Number(bytes || 0)
  if (!size) return ''
  if (size < 1024) return `${size} B`
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`
  return `${(size / 1024 / 1024).toFixed(1)} MB`
}

async function download() {
  if (downloading.value) return
  downloading.value = true
  error.value = ''
  try {
    await downloadArtifact(props.artifact.artifactId)
  } catch (cause) {
    error.value = cause?.message || t('下载产物失败')
  } finally {
    downloading.value = false
  }
}
</script>

<template>
  <article class="artifact-block">
    <div class="artifact-icon" aria-hidden="true">
      <svg viewBox="0 0 24 24"><path d="M6.5 3.5h7l4 4v13h-11zM13.5 3.5v4h4M9 14h6M12 10v7m-2.5-2.5L12 17l2.5-2.5" /></svg>
    </div>
    <div class="artifact-copy">
      <strong>{{ artifact.filename || artifact.content || t('Agent 产物') }}</strong>
      <span>{{ [artifact.contentType, sizeLabel(artifact.sizeBytes)].filter(Boolean).join(' · ') }}</span>
      <small v-if="error" role="alert">{{ error }}</small>
    </div>
    <button type="button" :disabled="downloading" @click="download">
      {{ downloading ? t('正在下载') : t('下载') }}
    </button>
  </article>
</template>

<style scoped>
.artifact-block {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 14px;
  border: 1px solid var(--line, #303038);
  border-radius: 10px;
  background: color-mix(in srgb, var(--panel, #18181b) 92%, white 8%);
}
.artifact-icon { width: 28px; height: 28px; color: var(--accent, #a8e063); }
.artifact-icon svg { width: 100%; fill: none; stroke: currentColor; stroke-width: 1.5; }
.artifact-copy { display: grid; min-width: 0; flex: 1; gap: 2px; }
.artifact-copy strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.artifact-copy span { color: var(--muted, #9b9ba4); font-size: 12px; }
.artifact-copy small { color: var(--danger, #ff7373); }
.artifact-block button {
  border: 1px solid var(--line, #303038);
  border-radius: 7px;
  padding: 6px 10px;
  color: inherit;
  background: var(--surface, #222228);
  cursor: pointer;
}
.artifact-block button:focus-visible { outline: 2px solid var(--accent, #a8e063); outline-offset: 2px; }
.artifact-block button:disabled { opacity: .55; cursor: wait; }
</style>
