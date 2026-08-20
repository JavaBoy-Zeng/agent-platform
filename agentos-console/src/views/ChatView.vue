<script setup>
import { inject } from 'vue'
import CommandDeck from '../components/CommandDeck.vue'
import SessionRail from '../components/SessionRail.vue'
import TelemetryRail from '../components/TelemetryRail.vue'
import TranscriptPanel from '../components/TranscriptPanel.vue'
import { useLocale } from '../composables/useLocale.js'

const { t } = useLocale()

const {
  sessions,
  currentSessionId,
  agentId,
  sessionId,
  prompt,
  busy,
  activeStage,
  messages,
  canStop,
  runtimeState,
  loadingSessions,
  sessionHistoryError,
  hasMoreSessions,
  createSession,
  renameSession,
  deleteSession,
  deleteSessions,
  selectSession,
  loadMoreSessions,
  execute,
  cancelCurrentRun,
  resolveApproval,
  clearTranscript
} = inject('agentConsole')
</script>

<template>
  <div class="chat-view">
    <SessionRail
      :sessions="sessions"
      :current-session-id="currentSessionId"
      :loading="loadingSessions"
      :has-more="hasMoreSessions"
      :history-error="sessionHistoryError"
      @select="selectSession"
      @create="createSession"
      @rename="renameSession($event.id, $event.title)"
      @delete="deleteSession"
      @delete-many="deleteSessions"
      @load-more="loadMoreSessions"
    />

    <section class="mission-workspace reveal reveal-2" aria-labelledby="consoleTitle">
      <header class="mission-intro">
        <div class="eyebrow"><span></span> LIVE AGENT RUNNER</div>
        <h1 id="consoleTitle">{{ t('意图进入，') }} <em>{{ t('行动发生。') }}</em></h1>
        <p>{{ t('向主 Agent 下达任务。运行时将建立上下文、生成计划、调用工具，并留下可追踪的状态结果。') }}</p>
      </header>

      <CommandDeck
        :agent-id="agentId"
        :session-id="sessionId"
        :prompt="prompt"
        :busy="busy"
        :can-stop="canStop"
        @update:agent-id="agentId = $event"
        @update:session-id="sessionId = $event"
        @update:prompt="prompt = $event"
        @run="execute"
        @stop="cancelCurrentRun"
      />

      <TranscriptPanel
        :messages="messages"
        :busy="busy"
        @clear="clearTranscript"
        @resolve-approval="resolveApproval($event.messageId, $event.approved)"
      />
    </section>

    <TelemetryRail :runtime-state="runtimeState" :active-stage="activeStage" />
  </div>
</template>

<style scoped>
.chat-view {
  display: grid;
  min-width: 0;
  min-height: 0;
  grid-template-columns: 260px minmax(0, 1fr);
  flex: 1;
}

@media (max-width: 700px) {
  .chat-view {
    display: flex;
    min-height: calc(100dvh - 108px);
    flex-direction: column;
  }
}
</style>
