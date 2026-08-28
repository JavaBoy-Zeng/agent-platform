<script setup>
import { computed, defineAsyncComponent, inject, ref } from 'vue'
import CommandDeck from '../components/CommandDeck.vue'
import TaskHeader from '../components/TaskHeader.vue'
import TranscriptPanel from '../components/TranscriptPanel.vue'
import WorkspacePanel from '../components/WorkspacePanel.vue'

const TerminalPanel = defineAsyncComponent(() => import('../components/TerminalPanel.vue'))
const desktopWorkspace = inject('desktopWorkspace')
const inspectorMode = desktopWorkspace.inspectorMode
const terminalOpen = desktopWorkspace.terminalOpen
const uploading = ref(false)
const uploadError = ref('')
const attachmentsBySession = ref({})

const {
  agentId,
  sessionId,
  prompt,
  models,
  selectedModelId,
  approvalMode,
  busy,
  currentPhase,
  messages,
  canStop,
  selectModel,
  addModel,
  setApprovalMode,
  execute,
  retryMessage,
  cancelCurrentRun,
  resolveApproval,
  clearTranscript
} = inject('agentConsole')

const attachments = computed(() => attachmentsBySession.value[sessionId.value] || [])

async function uploadAttachments() {
  if (uploading.value) return
  uploading.value = true
  uploadError.value = ''
  try {
    const uploaded = await desktopWorkspace.uploadAttachments()
    if (uploaded?.length) {
      attachmentsBySession.value = {
        ...attachmentsBySession.value,
        [sessionId.value]: [...attachments.value, ...uploaded]
      }
    }
  } catch (error) {
    uploadError.value = String(error).replace(/^Error:\s*/, '')
  } finally {
    uploading.value = false
  }
}
</script>

<template>
  <div class="chat-view">
    <TaskHeader />
    <div class="task-body" :class="{ 'with-inspector': inspectorMode }">
      <section class="mission-workspace reveal reveal-2" aria-label="Agent conversation">
        <TranscriptPanel
          :messages="messages"
          :busy="busy"
          :phase="currentPhase"
          @clear="clearTranscript"
          @retry="retryMessage"
          @resolve-approval="resolveApproval($event.messageId, $event.approved)"
        />
        <CommandDeck
          :agent-id="agentId"
          :session-id="sessionId"
          :prompt="prompt"
          :models="models"
          :selected-model-id="selectedModelId"
          :approval-mode="approvalMode"
          :attachments="attachments"
          :uploading="uploading"
          :upload-available="desktopWorkspace.available.value"
          :upload-error="uploadError"
          :workspace-available="desktopWorkspace.available.value"
          :workspaces="desktopWorkspace.workspaces.value"
          :current-workspace="desktopWorkspace.currentWorkspace.value"
          :workspace-busy="desktopWorkspace.authorizing.value || desktopWorkspace.picking.value"
          :workspace-error="desktopWorkspace.error.value"
          :busy="busy"
          :can-stop="canStop"
          @update:agent-id="agentId = $event"
          @update:session-id="sessionId = $event"
          @update:prompt="prompt = $event"
          @update:selected-model-id="selectModel"
          @update:approval-mode="setApprovalMode"
          @add-model="addModel"
          @upload="uploadAttachments"
          @select-workspace="desktopWorkspace.bindWorkspace"
          @pick-workspace="desktopWorkspace.pickWorkspace"
          @clear-workspace="desktopWorkspace.clearWorkspace"
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
