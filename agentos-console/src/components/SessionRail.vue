<script setup>
import { nextTick, onMounted, onUnmounted, ref } from 'vue'

const props = defineProps({
  sessions: { type: Array, required: true },
  currentSessionId: { type: String, default: '' },
  busy: { type: Boolean, default: false }
})

const emit = defineEmits(['select', 'create', 'rename', 'delete'])
const openSessionId = ref('')
const editingSessionId = ref('')
const draftTitle = ref('')
const renameInput = ref(null)
const pendingDeleteSession = ref(null)
const deleteConfirmButton = ref(null)

function setRenameInput(element) {
  renameInput.value = element
}

function closeMenu() {
  openSessionId.value = ''
}

function toggleMenu(id) {
  openSessionId.value = openSessionId.value === id ? '' : id
}

function selectSession(id) {
  closeMenu()
  emit('select', id)
}

async function startRename(session) {
  closeMenu()
  editingSessionId.value = session.id
  draftTitle.value = session.title
  await nextTick()
  renameInput.value?.focus()
  renameInput.value?.select()
}

function cancelRename() {
  editingSessionId.value = ''
  draftTitle.value = ''
}

function commitRename(session) {
  const title = draftTitle.value.trim()
  if (title && title !== session.title) {
    emit('rename', { id: session.id, title })
  }
  cancelRename()
}

async function requestDelete(session) {
  closeMenu()
  if (props.busy && session.id === props.currentSessionId) return
  pendingDeleteSession.value = session
  await nextTick()
  deleteConfirmButton.value?.focus()
}

function closeDeleteDialog() {
  pendingDeleteSession.value = null
}

function confirmDelete() {
  if (!pendingDeleteSession.value) return
  emit('delete', pendingDeleteSession.value.id)
  closeDeleteDialog()
}

function onDocumentKeydown(event) {
  if (event.key === 'Escape') {
    if (pendingDeleteSession.value) {
      closeDeleteDialog()
      return
    }
    closeMenu()
    cancelRename()
  }
}

onMounted(() => {
  document.addEventListener('click', closeMenu)
  document.addEventListener('keydown', onDocumentKeydown)
})

onUnmounted(() => {
  document.removeEventListener('click', closeMenu)
  document.removeEventListener('keydown', onDocumentKeydown)
})

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
      <div
        v-for="(session, index) in sessions"
        :key="session.id"
        class="session-item"
        :class="{
          selected: session.id === currentSessionId,
          'menu-open': openSessionId === session.id,
          editing: editingSessionId === session.id
        }"
      >
        <button
          v-if="editingSessionId !== session.id"
          class="session-select"
          type="button"
          @click="selectSession(session.id)"
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

        <div v-else class="session-select session-editing-row">
          <span class="session-copy">
            <input
              :ref="setRenameInput"
              v-model="draftTitle"
              class="session-rename-input"
              maxlength="60"
              aria-label="会话名称"
              @keydown.enter.prevent="commitRename(session)"
              @keydown.esc.prevent="cancelRename"
              @blur="commitRename(session)"
            >
            <small>{{ session.id }}</small>
          </span>
        </div>

        <div class="session-actions" @click.stop>
          <button
            class="session-more"
            type="button"
            :aria-expanded="openSessionId === session.id"
            :aria-label="`${session.title}的更多操作`"
            title="更多操作"
            @click="toggleMenu(session.id)"
          >
            <span></span><span></span><span></span>
          </button>

          <div v-if="openSessionId === session.id" class="session-menu" role="menu">
            <button type="button" role="menuitem" @click="startRename(session)">
              <span aria-hidden="true">✎</span> 重命名
            </button>
            <button
              type="button"
              role="menuitem"
              class="danger"
              :disabled="busy && session.id === currentSessionId"
              :title="busy && session.id === currentSessionId ? '运行中的会话无法删除' : '删除会话'"
              @click="requestDelete(session)"
            >
              <span aria-hidden="true">⌫</span> 删除
            </button>
          </div>
        </div>
      </div>
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

  <Teleport to="body">
    <Transition name="dialog-fade">
      <div
        v-if="pendingDeleteSession"
        class="dialog-backdrop"
        @click.self="closeDeleteDialog"
      >
        <section
          class="confirm-dialog"
          role="alertdialog"
          aria-modal="true"
          aria-labelledby="deleteDialogTitle"
          aria-describedby="deleteDialogDescription"
        >
          <div class="confirm-dialog-icon" aria-hidden="true">⌫</div>
          <div class="confirm-dialog-copy">
            <h2 id="deleteDialogTitle">删除这个会话？</h2>
            <p id="deleteDialogDescription">
              “{{ pendingDeleteSession.title }}”及其中的运行记录将从浏览器中移除，此操作无法撤销。
            </p>
          </div>
          <div class="confirm-dialog-actions">
            <button type="button" class="dialog-cancel" @click="closeDeleteDialog">取消</button>
            <button
              ref="deleteConfirmButton"
              type="button"
              class="dialog-confirm"
              @click="confirmDelete"
            >
              删除
            </button>
          </div>
        </section>
      </div>
    </Transition>
  </Teleport>
</template>
