<script setup>
import { renderMarkdown } from '../utils/markdown.js'
import { useLocale } from '../composables/useLocale.js'

const { localeTag, t } = useLocale()

defineProps({
  messages: { type: Array, required: true },
  busy: { type: Boolean, default: false }
})

defineEmits(['clear', 'resolve-approval'])

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
        <header>
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
        <div
          v-else
          class="message-content markdown-body"
          v-html="renderMarkdown(message.content)"
        ></div>
      </article>

      <article v-if="busy" class="message message-agent message-pending">
        <header><span class="message-sequence">··</span><strong>MAIN AGENT</strong><time>PROCESSING</time></header>
        <div class="thinking-line"><i></i><i></i><i></i><span>{{ t('Agent Loop 正在运行') }}</span></div>
      </article>
    </div>
  </section>
</template>
