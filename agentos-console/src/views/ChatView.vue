<script setup>
import { computed, defineAsyncComponent, inject, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import CommandDeck from '../components/CommandDeck.vue'
import TaskHeader from '../components/TaskHeader.vue'
import TranscriptPanel from '../components/TranscriptPanel.vue'
import WorkspacePanel from '../components/WorkspacePanel.vue'
import { uploadSessionAttachments } from '../services/agentApi.js'

const TerminalPanel = defineAsyncComponent(() => import('../components/TerminalPanel.vue'))
const router = useRouter()
const desktopWorkspace = inject('desktopWorkspace')
const inspectorMode = desktopWorkspace.inspectorMode
const terminalOpen = desktopWorkspace.terminalOpen
const uploading = ref(false)
const uploadError = ref('')
const attachmentsBySession = ref({})
const fileInput = ref(null)

const {
  agentId,
  sessionId,
  prompt,
  models,
  selectedModelKey,
  currentModel,
  approvalMode,
  busy,
  currentPhase,
  messages,
  canStop,
  selectTaskModel,
  refreshServerModel,
  setApprovalMode,
  execute,
  retryMessage,
  cancelCurrentRun,
  resolveApproval,
  clearTranscript
} = inject('agentConsole')

const attachments = computed(() => attachmentsBySession.value[sessionId.value] || [])

function mergeAttachments(uploaded) {
  if (!uploaded?.length) return
  attachmentsBySession.value = {
    ...attachmentsBySession.value,
    [sessionId.value]: [...attachments.value, ...uploaded]
  }
}

/** 从本次会话的待发送列表移除附件，不删除工作区中的原文件。 */
function removeAttachment(target) {
  attachmentsBySession.value = {
    ...attachmentsBySession.value,
    [sessionId.value]: attachments.value.filter(attachment => attachment !== target)
  }
}

function openModelManagement() {
  router.push('/models')
}

onMounted(() => void refreshServerModel())

/** 桌面端走原生选择器并复制到工作区；网页端走浏览器文件选择 + Server 上传。 */
async function uploadAttachments() {
  if (uploading.value) return
  if (desktopWorkspace.available.value) {
    await uploadFromDesktop()
    return
  }
  fileInput.value?.click()
}

async function uploadFromDesktop() {
  uploading.value = true
  uploadError.value = ''
  try {
    mergeAttachments(await desktopWorkspace.uploadAttachments())
  } catch (error) {
    uploadError.value = String(error).replace(/^Error:\s*/, '')
  } finally {
    uploading.value = false
  }
}

async function onFilesPicked(event) {
  const files = Array.from(event.target.files || [])
  event.target.value = ''
  if (!files.length || uploading.value) return
  uploading.value = true
  uploadError.value = ''
  try {
    const uploaded = await uploadSessionAttachments(sessionId.value, files)
    // 标记 serverFile：仅 server 端附件路径会注入任务输入供 file_read 读取
    mergeAttachments((uploaded || []).map(attachment => ({ ...attachment, serverFile: true })))
  } catch (error) {
    uploadError.value = String(error).replace(/^Error:\s*/, '') || '附件上传失败'
  } finally {
    uploading.value = false
  }
}

async function runTask() {
  if (busy.value || desktopWorkspace.contextLoading.value) return
  try {
    const mentionedPaths = desktopWorkspace.workspaceFiles.value
      .filter(file => prompt.value.includes(`@${file.relativePath}`))
      .map(file => file.relativePath)
    const workspaceContext = await desktopWorkspace.buildRunContext(mentionedPaths)
    await execute(attachments.value, workspaceContext)
  } catch {
    // buildRunContext 已把可见错误写入工作区状态；读取失败时不发送无上下文任务。
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
          :selected-model-key="selectedModelKey"
          :current-model="currentModel"
          :approval-mode="approvalMode"
          :attachments="attachments"
          :uploading="uploading"
          :upload-available="true"
          :upload-error="uploadError"
          :workspace-available="desktopWorkspace.available.value"
          :workspaces="desktopWorkspace.workspaces.value"
          :current-workspace="desktopWorkspace.currentWorkspace.value"
          :workspace-files="desktopWorkspace.workspaceFiles.value"
          :workspace-files-loading="desktopWorkspace.fileIndexLoading.value"
          :workspace-busy="desktopWorkspace.authorizing.value || desktopWorkspace.picking.value || desktopWorkspace.contextLoading.value"
          :workspace-error="desktopWorkspace.error.value"
          :git-branches="desktopWorkspace.gitBranches.value"
          :git-branch-loading="desktopWorkspace.gitBranchLoading.value"
          :git-branch-error="desktopWorkspace.gitBranchError.value"
          :busy="busy || desktopWorkspace.contextLoading.value"
          :can-stop="canStop"
          @update:agent-id="agentId = $event"
          @update:session-id="sessionId = $event"
          @update:prompt="prompt = $event"
          @update:selected-model-key="selectTaskModel"
          @update:approval-mode="setApprovalMode"
          @manage-models="openModelManagement"
          @upload="uploadAttachments"
          @remove-attachment="removeAttachment"
          @select-workspace="desktopWorkspace.bindWorkspace"
          @pick-workspace="desktopWorkspace.pickWorkspace"
          @refresh-branches="desktopWorkspace.refreshGitBranches"
          @select-branch="desktopWorkspace.switchGitBranch"
          @run="runTask"
          @stop="cancelCurrentRun"
        />
      </section>
      <WorkspacePanel v-if="inspectorMode" />
    </div>
    <TerminalPanel v-if="terminalOpen" />
    <input ref="fileInput" type="file" multiple class="hidden-file-input" aria-hidden="true" tabindex="-1" @change="onFilesPicked">
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

.hidden-file-input {
  display: none;
}
</style>
