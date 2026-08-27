<script setup>
import { defineAsyncComponent, inject } from 'vue'
import CommandDeck from '../components/CommandDeck.vue'
import TaskHeader from '../components/TaskHeader.vue'
import TranscriptPanel from '../components/TranscriptPanel.vue'
import WorkspacePanel from '../components/WorkspacePanel.vue'

const TerminalPanel = defineAsyncComponent(() => import('../components/TerminalPanel.vue'))
const desktopWorkspace = inject('desktopWorkspace')
const inspectorMode = desktopWorkspace.inspectorMode
const terminalOpen = desktopWorkspace.terminalOpen

const {
  agentId,
  sessionId,
  prompt,
  busy,
  messages,
  canStop,
  execute,
  cancelCurrentRun,
  resolveApproval,
  clearTranscript
} = inject('agentConsole')
</script>

<template>
  <div class="chat-view">
    <TaskHeader />
    <div class="task-body" :class="{ 'with-inspector': inspectorMode }">
      <section class="mission-workspace reveal reveal-2" aria-label="Agent conversation">
        <TranscriptPanel
          :messages="messages"
          :busy="busy"
          @clear="clearTranscript"
          @resolve-approval="resolveApproval($event.messageId, $event.approved)"
        />
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
      </section>
      <WorkspacePanel v-if="inspectorMode" />
    </div>
    <TerminalPanel v-if="terminalOpen" />
  </div>
</template>

<style scoped>
.chat-view {
  display: flex;
  min-width: 0;
  min-height: 0;
  flex-direction: column;
  flex: 1;
}
</style>
