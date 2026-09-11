<script setup>
import { computed, ref } from 'vue'
import { renderMarkdown } from '../utils/markdown.js'
import { openExternalUrl } from '../services/desktopApi.js'
import OperationGroup from './OperationGroup.vue'
import ArtifactBlock from './ArtifactBlock.vue'
import { useLocale } from '../composables/useLocale.js'

const { localeTag, t } = useLocale()

const props = defineProps({
  messages: { type: Array, required: true },
  busy: { type: Boolean, default: false },
  phase: { type: String, default: '' },
  executingAgentId: { type: String, default: '' }
})

const emit = defineEmits(['clear', 'resolve-approval', 'retry'])
const copiedMessageId = ref('')

function timeLabel(value) {
  return new Intl.DateTimeFormat(localeTag.value, {
    hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false
  }).format(new Date(value))
}

const conversationTurns = computed(() => {
  const turns = []
  let currentTurn = null

  props.messages.forEach(message => {
    if (message.role === 'user') {
      currentTurn = { id: `turn-${message.id}`, user: message, records: [] }
      turns.push(currentTurn)
      return
    }
    if (!currentTurn) {
      currentTurn = { id: `turn-${message.id}`, user: null, records: [] }
      turns.push(currentTurn)
    }
    currentTurn.records.push(message)
  })
  return turns
})

function elapsedLabel(start, end) {
  const elapsed = Math.max(0, new Date(end).getTime() - new Date(start).getTime())
  if (!Number.isFinite(elapsed) || elapsed < 1000) return t('少于 1 秒')
  const seconds = Math.floor(elapsed / 1000)
  const minutes = Math.floor(seconds / 60)
  const hours = Math.floor(minutes / 60)
  const remainderMinutes = minutes % 60
  const remainderSeconds = seconds % 60
  if (hours) return `${hours}时${remainderMinutes}分${remainderSeconds}秒`
  if (minutes) return `${minutes}分${remainderSeconds}秒`
  return `${seconds}秒`
}

function runDurationLabel(message, startedAt) {
  const duration = Number(message.durationMs)
  if (Number.isFinite(duration) && duration >= 0) {
    return elapsedLabel(0, duration)
  }
  return elapsedLabel(startedAt, message.createdAt)
}

function statusLabel(message) {
  if (message.runStatus === 'CANCELLED') return t('已停止')
  if (message.runStatus === 'FAILED') return t('执行失败')
  return t('已完成')
}

function showTaskStatus(message) {
  return Boolean(message.runEnd || (!props.busy && message.role === 'assistant'))
}

function relativeTime(value) {
  const elapsed = Math.max(0, Date.now() - new Date(value).getTime())
  if (!Number.isFinite(elapsed) || elapsed < 60_000) return t('刚刚')
  const minutes = Math.floor(elapsed / 60_000)
  if (minutes < 60) return `${minutes} ${t('分钟')}`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours} ${t('小时')}`
  return `${Math.floor(hours / 24)} ${t('天')}`
}

function tokenSummary(message) {
  const usage = message.tokenUsageDetails || message.usage || {}
  const total = Number(message.tokenUsage ?? usage.totalTokens)
  if (!Number.isFinite(total)) return ''
  const parts = [`Tokens: ${total.toLocaleString(localeTag.value)}`]
  const input = Number(usage.inputTokens ?? usage.promptTokens)
  const output = Number(usage.outputTokens ?? usage.completionTokens)
  const cache = Number(usage.cacheTokens ?? usage.cachedTokens)
  if (Number.isFinite(input)) parts.push(`in ${input.toLocaleString(localeTag.value)}`)
  if (Number.isFinite(output)) parts.push(`out ${output.toLocaleString(localeTag.value)}`)
  if (Number.isFinite(cache)) parts.push(`cache ${cache.toLocaleString(localeTag.value)}`)
  return parts.join(', ')
}

function toggleOps(message) {
  message.expanded = !message.expanded
}

function openMessageLink(event) {
  const link = event.target.closest?.('a[href]')
  if (!link || !event.currentTarget.contains(link)) return
  event.preventDefault()
  openExternalUrl(link.href).catch(error => console.error('打开链接失败', error))
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
        <small>CHANNEL / AGENT / READY</small>
      </div>

      <section v-for="(turn, turnIndex) in conversationTurns" :key="turn.id" class="conversation-turn">
        <div v-if="turn.user" class="user-message-row">
          <div class="user-message-bubble markdown-body" v-html="renderMarkdown(turn.user.content)" @click="openMessageLink"></div>
          <time :datetime="turn.user.createdAt" :title="timeLabel(turn.user.createdAt)">{{ relativeTime(turn.user.createdAt) }}</time>
        </div>

        <div class="agent-execution-stream">
          <template v-for="message in turn.records" :key="message.id">
            <article v-if="message.role === 'assistant'" class="assistant-message">
              <div v-if="showTaskStatus(message)" class="task-status-line" :class="{ abnormal: message.runStatus && message.runStatus !== 'COMPLETED' }">
                <svg v-if="message.runStatus === 'FAILED' || message.runStatus === 'CANCELLED'" viewBox="0 0 16 16" aria-hidden="true"><path d="M4.5 4.5l7 7m0-7-7 7" /></svg>
                <svg v-else viewBox="0 0 16 16" aria-hidden="true"><path d="m3.5 8 3 3 6-7" /></svg>
                <span>{{ statusLabel(message) }}</span>
                <span v-if="turn.user">{{ runDurationLabel(message, turn.user.createdAt) }}</span>
                <span class="status-chevron" aria-hidden="true">›</span>
              </div>
              <div class="assistant-content markdown-body" v-html="renderMarkdown(message.content)" @click="openMessageLink"></div>
              <footer class="message-metadata">
                <button type="button" :aria-label="t('复制回答')" :title="copiedMessageId === message.id ? t('已复制') : t('复制回答')" @click="copyMessage(message)">
                  <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M5.5 5.5h7v7h-7zM3.5 10.5h-1v-8h8v1" /></svg>
                </button>
                <button type="button" :disabled="busy || !message.retryPrompt" :aria-label="t('重试')" :title="t('重试')" @click="emit('retry', message.retryPrompt)">
                  <svg viewBox="0 0 16 16" aria-hidden="true"><circle cx="4" cy="4" r="1.5"/><circle cx="4" cy="12" r="1.5"/><circle cx="12" cy="6" r="1.5"/><path d="M4 5.5v5M5.5 10.5c3.7 0 5-1.5 5-3" /></svg>
                </button>
                <time :datetime="message.createdAt" :title="timeLabel(message.createdAt)">{{ relativeTime(message.createdAt) }}</time>
                <span v-if="tokenSummary(message)" class="token-usage">{{ tokenSummary(message) }}</span>
              </footer>
            </article>

            <div v-else-if="message.role === 'artifact'" class="execution-record artifact-record">
              <ArtifactBlock :artifact="message" />
            </div>

            <div v-else-if="message.role === 'approval'" class="execution-record approval-record">
              <div class="approval-card">
                <strong>{{ t(message.title) }}</strong>
                <div class="approval-description markdown-body" v-html="renderMarkdown(message.content)" @click="openMessageLink"></div>
                <dl v-if="message.payload?.toolName">
                  <dt>{{ t('工具') }}</dt><dd>{{ message.payload.toolName }}</dd>
                  <template v-if="message.payload.arguments?.path"><dt>{{ t('目标') }}</dt><dd>{{ message.payload.arguments.path }}</dd></template>
                  <template v-if="message.payload.arguments?.mode"><dt>{{ t('模式') }}</dt><dd>{{ message.payload.arguments.mode }}</dd></template>
                  <template v-if="message.payload.arguments?.repository"><dt>{{ t('仓库') }}</dt><dd>{{ message.payload.arguments.repository }}</dd></template>
                  <template v-if="message.payload.arguments?.message"><dt>{{ t('提交说明') }}</dt><dd>{{ message.payload.arguments.message }}</dd></template>
                  <template v-if="message.payload.arguments?.paths?.length">
                    <dt>{{ t('提交文件') }}</dt><dd><ul><li v-for="path in message.payload.arguments.paths" :key="path">{{ path }}</li></ul></dd>
                  </template>
                </dl>
                <div v-if="!message.resolved" class="approval-actions">
                  <button type="button" class="approval-reject" :disabled="busy" @click="$emit('resolve-approval', { messageId: message.id, approved: false })">{{ t('拒绝') }}</button>
                  <button type="button" class="approval-accept" :disabled="busy" @click="$emit('resolve-approval', { messageId: message.id, approved: true })">{{ message.payload?.workspaceReconnect ? t('重新连接后继续') : t('批准并继续') }}</button>
                </div>
                <span v-else class="approval-resolution" :class="{ approved: message.approved }">{{ t(message.approved ? '已批准并恢复执行' : '已拒绝') }}</span>
              </div>
            </div>

            <div v-else-if="message.role === 'ops'" class="execution-record operation-record">
              <OperationGroup :kind="message.kind" :items="message.items" :expanded="message.expanded" @toggle="toggleOps(message)" />
            </div>

            <article v-else class="execution-record runtime-record" :class="`runtime-${message.role}`">
              <span class="runtime-record-label">{{ message.role === 'error' ? t('错误') : t('运行状态') }}</span>
              <div class="markdown-body" v-html="renderMarkdown(message.content)" @click="openMessageLink"></div>
              <footer v-if="message.runEnd" class="message-metadata">
                <span v-if="resultStatus(message)" class="runtime-result-status">{{ resultStatus(message) }}</span>
                <button type="button" :aria-label="t('复制回答')" :title="t('复制回答')" @click="copyMessage(message)">
                  <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M5.5 5.5h7v7h-7zM3.5 10.5h-1v-8h8v1" /></svg>
                </button>
                <button type="button" :disabled="busy || !message.retryPrompt" :aria-label="t('重试')" :title="t('重试')" @click="emit('retry', message.retryPrompt)">
                  <svg viewBox="0 0 16 16" aria-hidden="true"><path d="M13 7.5a5 5 0 1 0-1.5 3.6M13 3.5v4h-4" /></svg>
                </button>
                <time :datetime="message.createdAt">{{ relativeTime(message.createdAt) }}</time>
                <span v-if="tokenSummary(message)" class="token-usage">{{ tokenSummary(message) }}</span>
              </footer>
            </article>
          </template>

          <div v-if="busy && turnIndex === conversationTurns.length - 1" class="assistant-pending" role="status">
            <span class="thinking-dots" aria-hidden="true"><i></i><i></i><i></i></span>
            <span>{{ phase || t('Agent Loop 正在运行') }}</span>
          </div>
        </div>
      </section>

      <section v-if="busy && !conversationTurns.length" class="conversation-turn pending-turn">
        <div class="agent-execution-stream">
          <div class="assistant-pending" role="status">
            <span class="thinking-dots" aria-hidden="true"><i></i><i></i><i></i></span>
            <span>{{ phase || t('Agent Loop 正在运行') }}</span>
          </div>
        </div>
      </section>
    </div>
  </section>
</template>
