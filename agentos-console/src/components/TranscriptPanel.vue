<script setup>
defineProps({
  messages: { type: Array, required: true },
  busy: { type: Boolean, default: false }
})

defineEmits(['clear'])

function timeLabel(value) {
  return new Intl.DateTimeFormat('zh-CN', {
    hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false
  }).format(new Date(value))
}

function roleLabel(role) {
  return { user: 'OPERATOR', assistant: 'MAIN AGENT', error: 'SYSTEM ERROR' }[role] || role
}
</script>

<template>
  <section class="transcript" aria-labelledby="transcriptTitle">
    <header class="transcript-header">
      <div>
        <span class="section-index">02</span>
        <h2 id="transcriptTitle">运行记录</h2>
      </div>
      <button class="text-button" type="button" :disabled="!messages.length" @click="$emit('clear')">
        清空视图
      </button>
    </header>

    <div class="message-feed" aria-live="polite">
      <div v-if="!messages.length && !busy" class="empty-state">
        <div class="orbit" aria-hidden="true">
          <span></span><i></i><b></b>
        </div>
        <strong>等待首条指令</strong>
        <p>当前通道已建立。输入任务以启动 Agent Loop。</p>
        <small>CHANNEL / MAIN-AGENT / READY</small>
      </div>

      <article v-for="(message, index) in messages" :key="message.id"
               class="message" :class="`message-${message.role}`">
        <header>
          <span class="message-sequence">{{ String(index + 1).padStart(2, '0') }}</span>
          <strong>{{ roleLabel(message.role) }}</strong>
          <time>{{ timeLabel(message.createdAt) }}</time>
        </header>
        <p>{{ message.content }}</p>
      </article>

      <article v-if="busy" class="message message-agent message-pending">
        <header><span class="message-sequence">··</span><strong>MAIN AGENT</strong><time>PROCESSING</time></header>
        <div class="thinking-line"><i></i><i></i><i></i><span>Agent Loop 正在运行</span></div>
      </article>
    </div>
  </section>
</template>
