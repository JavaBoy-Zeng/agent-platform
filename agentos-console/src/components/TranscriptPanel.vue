<script setup>
import { ref } from 'vue'
import { renderMarkdown } from '../utils/markdown.js'
import OperationGroup from './OperationGroup.vue'
import { useLocale } from '../composables/useLocale.js'

const { localeTag, t } = useLocale()

defineProps({
  messages: { type: Array, required: true },
  busy: { type: Boolean, default: false },
  phase: { type: String, default: '' }
})

const emit = defineEmits(['clear', 'resolve-approval', 'retry'])
const copiedMessageId = ref('')

function timeLabel(value) {
  return new Intl.DateTimeFormat(localeTag.value, {
    hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false
  }).format(new Date(value))
}

function roleLabel(role) {
  return {
    user: 'OPERATOR',
    assistant: 'MAIN AGENT',
    event: 'RUNTIME EVENT',
    approval: 'APPROVAL REQUIRED',
    error: 'SYSTEM ERROR'
  }[role] || role
}

function toggleOps(message) {
  message.expanded = !message.expanded
}

function resultStatus(message) {
  if (message.runStatus === 'CANCELLED') return t('手动终止输出')
  if (message.runStatus === 'FAILED') return t('异常返回')
  return ''
}

async function copyMessage(message) {
  const content = String(message.copyContent || message.content || '')
  if (!content) return
  try {
    await navigator.clipboard.writeText(content)
  } catch {
    const textarea = document.createElement('textarea')
    textarea.value = content
    textarea.style.position = 'fixed'
    textarea.style.opacity = '0'
    document.body.appendChild(textarea)
    textarea.select()
    document.execCommand('copy')
    textarea.remove()
  }
  copiedMessageId.value = message.id
  window.setTimeout(() => {
    if (copiedMessageId.value === message.id) copiedMessageId.value = ''
  }, 1600)
}
</script>

<template>
  <section class="transcript" aria-labelledby="transcriptTitle">
    <header class="transcript-header">
      <div>
        <span class="section-index">02</span>
        <h2 id="transcriptTitle">{{ t('运行记录') }}</h2>
      </div>
      <button class="text-button" type="button" :disabled="!messages.length" @click="$emit('clear')">
        {{ t('清空视图') }}
      </button>
    </header>

    <div class="message-feed" aria-live="polite">
      <div v-if="!messages.length && !busy" class="empty-state">
        <div class="orbit" aria-hidden="true">
          <span></span><i></i><b></b>
        </div>
        <strong>{{ t('等待首条指令') }}</strong>
        <p>{{ t('当前通道已建立。输入任务以启动 Agent Loop。') }}</p>
        <small>CHANNEL / MAIN-AGENT / READY</small>
      </div>

      <article v-for="(message, index) in messages" :key="message.id"
               class="message" :class="`message-${message.role}`">
        <header v-if="message.role !== 'ops'">
          <span class="message-sequence">{{ String(index + 1).padStart(2, '0') }}</span>
          <strong>{{ roleLabel(message.role) }}</strong>
          <time>{{ timeLabel(message.createdAt) }}</time>
        </header>
        <div v-if="message.role === 'approval'" class="approval-card">
          <strong>{{ t(message.title) }}</strong>
          <div
            class="approval-description markdown-body"
            v-html="renderMarkdown(message.content)"
          ></div>
          <dl v-if="message.payload?.toolName">
            <dt>{{ t('工具') }}</dt><dd>{{ message.payload.toolName }}</dd>
            <template v-if="message.payload.arguments?.path">
              <dt>{{ t('目标') }}</dt><dd>{{ message.payload.arguments.path }}</dd>
            </template>
            <template v-if="message.payload.arguments?.mode">
              <dt>{{ t('模式') }}</dt><dd>{{ message.payload.arguments.mode }}</dd>
            </template>
            <template v-if="message.payload.arguments?.repository">
              <dt>{{ t('仓库') }}</dt><dd>{{ message.payload.arguments.repository }}</dd>
            </template>
            <template v-if="message.payload.arguments?.message">
              <dt>{{ t('提交说明') }}</dt><dd>{{ message.payload.arguments.message }}</dd>
            </template>
            <template v-if="message.payload.arguments?.paths?.length">
              <dt>{{ t('提交文件') }}</dt>
              <dd><ul><li v-for="path in message.payload.arguments.paths" :key="path">{{ path }}</li></ul></dd>
            </template>
          </dl>
          <div v-if="!message.resolved" class="approval-actions">
            <button type="button" class="approval-reject" :disabled="busy"
                    @click="$emit('resolve-approval', { messageId: message.id, approved: false })">
              {{ t('拒绝') }}
            </button>
            <button type="button" class="approval-accept" :disabled="busy"
                    @click="$emit('resolve-approval', { messageId: message.id, approved: true })">
              {{ t('批准并继续') }}
            </button>
          </div>
          <span v-else class="approval-resolution" :class="{ approved: message.approved }">
            {{ t(message.approved ? '已批准并恢复执行' : '已拒绝') }}
          </span>
        </div>
        <OperationGroup
          v-else-if="message.role === 'ops'"
          :kind="message.kind"
          :items="message.items"
          :expanded="message.expanded"
          @toggle="toggleOps(message)"
        />
        <template v-else>
          <div class="message-content markdown-body" v-html="renderMarkdown(message.content)"></div>
          <footer v-if="message.runEnd" class="answer-actions" :class="{ abnormal: message.runStatus !== 'COMPLETED' }">
            <span v-if="resultStatus(message)" class="answer-status">
              <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 8v8M8 12h8" /></svg>{{ resultStatus(message) }}
            </span>
            <span v-if="resultStatus(message)" class="answer-divider" aria-hidden="true"></span>
            <button type="button" :aria-label="t('复制回答')" :title="t('复制回答')" @click="copyMessage(message)">
              <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M8 8h10v10H8zM6 16H4V4h12v2" /></svg>
              <span class="sr-only">{{ copiedMessageId === message.id ? t('已复制') : t('复制回答') }}</span>
            </button>
            <button type="button" :disabled="busy || !message.retryPrompt" :aria-label="t('重试')" :title="t('重试')" @click="emit('retry', message.retryPrompt)">
              <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M20 11a8 8 0 10-2.3 5.7M20 5v6h-6" /></svg>
            </button>
            <span v-if="message.tokenUsage !== undefined" class="answer-token">
              {{ t('消耗') }} <b aria-hidden="true">✦</b> {{ Number(message.tokenUsage || 0).toLocaleString(localeTag) }} token
            </span>
          </footer>
        </template>
      </article>

      <article v-if="busy" class="message message-agent message-pending">
        <header><span class="message-sequence">··</span><strong>MAIN AGENT</strong><time>PROCESSING</time></header>
        <div class="thinking-line">
          <i></i><i></i><i></i><span>{{ phase || t('Agent Loop 正在运行') }}</span>
        </div>
      </article>
    </div>
  </section>
</template>
