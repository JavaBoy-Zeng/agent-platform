<script setup>
defineProps({
  sessions: { type: Array, required: true },
  currentSessionId: { type: String, default: '' }
})

defineEmits(['select', 'create'])

function timeLabel(value) {
  if (!value) return '--:--'
  return new Intl.DateTimeFormat('zh-CN', {
    hour: '2-digit', minute: '2-digit', hour12: false
  }).format(new Date(value))
}

function stateLabel(session) {
  return session.state?.status || 'READY'
}
</script>

<template>
  <aside class="session-rail reveal reveal-1" aria-label="会话列表">
    <div class="rail-heading">
      <div>
        <span class="section-index">01</span>
        <h2>会话档案</h2>
      </div>
      <span class="count-badge">{{ String(sessions.length).padStart(2, '0') }}</span>
    </div>

    <div class="session-list">
      <button
        v-for="(session, index) in sessions"
        :key="session.id"
        type="button"
        class="session-item"
        :class="{ selected: session.id === currentSessionId }"
        @click="$emit('select', session.id)"
      >
        <span class="session-sequence">{{ String(index + 1).padStart(2, '0') }}</span>
        <span class="session-copy">
          <strong>{{ session.title }}</strong>
          <small>{{ session.id }}</small>
        </span>
        <span class="session-meta">
          <time>{{ timeLabel(session.updatedAt) }}</time>
          <i :class="stateLabel(session).toLowerCase()"></i>
        </span>
      </button>
    </div>

    <button class="rail-create" type="button" @click="$emit('create')">
      <span>＋</span> 新建任务通道
    </button>

    <div class="rail-footer">
      <span>LOCAL ARCHIVE</span>
      <span class="storage-meter"><i></i></span>
      <small>最近 20 个会话保存在浏览器</small>
    </div>
  </aside>
</template>
