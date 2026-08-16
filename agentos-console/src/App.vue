<script setup>
import CommandDeck from './components/CommandDeck.vue'
import SessionRail from './components/SessionRail.vue'
import SystemHeader from './components/SystemHeader.vue'
import TelemetryRail from './components/TelemetryRail.vue'
import TranscriptPanel from './components/TranscriptPanel.vue'
import { useAgentConsole } from './composables/useAgentConsole.js'

const {
  sessions,
  currentSessionId,
  agentId,
  sessionId,
  prompt,
  busy,
  connection,
  activeStage,
  messages,
  canStop,
  runtimeState,
  createSession,
  renameSession,
  deleteSession,
  selectSession,
  execute,
  cancelCurrentRun,
  resolveApproval,
  clearTranscript
} = useAgentConsole()
</script>

<template>
  <a class="skip-link" href="#workspace">跳到操作区</a>

  <div class="app-shell">
    <SystemHeader :connection="connection" @new-session="createSession" />

    <main id="workspace" class="console-layout">
      <SessionRail
        :sessions="sessions"
        :current-session-id="currentSessionId"
        :busy="busy"
        @select="selectSession"
        @create="createSession"
        @rename="renameSession($event.id, $event.title)"
        @delete="deleteSession"
      />

      <section class="mission-workspace reveal reveal-2" aria-labelledby="consoleTitle">
        <header class="mission-intro">
          <div class="eyebrow"><span></span> LIVE AGENT RUNNER</div>
          <h1 id="consoleTitle">意图进入，<em>行动发生。</em></h1>
          <p>向主 Agent 下达任务。运行时将建立上下文、生成计划、调用工具，并留下可追踪的状态结果。</p>
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
    </main>

    <footer class="status-footer">
      <span><i></i> AGENTOS KERNEL ONLINE</span>
      <span class="footer-marquee">USER → MAIN AGENT → PLANNER → TOOL → OBSERVATION → DECISION</span>
      <span>CONSOLE v0.1</span>
    </footer>
  </div>
</template>
