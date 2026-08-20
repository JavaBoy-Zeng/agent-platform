<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { useLocale } from '../composables/useLocale.js'

const { localeTag, t } = useLocale()

const props = defineProps({
  sessions: { type: Array, required: true },
  currentSessionId: { type: String, default: '' },
  loading: { type: Boolean, default: false },
  hasMore: { type: Boolean, default: false },
  historyError: { type: String, default: '' }
})

const emit = defineEmits(['select', 'create', 'rename', 'delete', 'delete-many', 'load-more'])
const openSessionId = ref('')
const editingSessionId = ref('')
const draftTitle = ref('')
const renameInput = ref(null)
const pendingDeleteSession = ref(null)
const deleteConfirmButton = ref(null)
const selectionMode = ref(false)
const selectedSessionIds = ref(new Set())
const pendingBulkDeleteIds = ref([])
const bulkDeleteConfirmButton = ref(null)

const selectableSessions = computed(() => props.sessions.filter(session => !sessionBusy(session)))
const selectedSessions = computed(() => props.sessions.filter(session =>
  selectedSessionIds.value.has(session.id) && !sessionBusy(session)))
const allSelectableSelected = computed(() => selectableSessions.value.length > 0
  && selectableSessions.value.every(session => selectedSessionIds.value.has(session.id)))

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
  if (sessionBusy(session)) return
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

function enterSelectionMode() {
  closeMenu()
  cancelRename()
  selectionMode.value = true
}

function exitSelectionMode() {
  selectionMode.value = false
  selectedSessionIds.value = new Set()
}

function toggleSessionSelection(session) {
  if (sessionBusy(session)) return
  const next = new Set(selectedSessionIds.value)
  if (next.has(session.id)) next.delete(session.id)
  else next.add(session.id)
  selectedSessionIds.value = next
}

function toggleSelectAll() {
  selectedSessionIds.value = allSelectableSelected.value
    ? new Set()
    : new Set(selectableSessions.value.map(session => session.id))
}

async function requestBulkDelete() {
  const ids = selectedSessions.value.map(session => session.id)
  if (!ids.length) return
  pendingBulkDeleteIds.value = ids
  await nextTick()
  bulkDeleteConfirmButton.value?.focus()
}

function closeBulkDeleteDialog() {
  pendingBulkDeleteIds.value = []
}

function confirmBulkDelete() {
  if (!pendingBulkDeleteIds.value.length) return
  emit('delete-many', [...pendingBulkDeleteIds.value])
  closeBulkDeleteDialog()
  exitSelectionMode()
}

function onDocumentKeydown(event) {
  if (event.key === 'Escape') {
    if (pendingBulkDeleteIds.value.length) {
      closeBulkDeleteDialog()
      return
    }
    if (pendingDeleteSession.value) {
      closeDeleteDialog()
      return
    }
    closeMenu()
    cancelRename()
    if (selectionMode.value) exitSelectionMode()
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
  return new Intl.DateTimeFormat(localeTag.value, {
    hour: '2-digit', minute: '2-digit', hour12: false
  }).format(new Date(value))
}

function stateLabel(session) {
  return session.state?.status || 'READY'
}

function sessionBusy(session) {
  return Boolean(session.activeRunId || session.submitting)
}
</script>

<template>
  <aside class="session-rail reveal reveal-1" :aria-label="t('会话列表')">
    <div class="rail-heading">
      <div>
        <span class="section-index">01</span>
        <h2>{{ selectionMode ? t('已选 {count} 个', { count: selectedSessions.length }) : t('会话') }}</h2>
      </div>
      <button
        class="rail-select-toggle"
        type="button"
        :aria-label="t(selectionMode ? '退出多选' : '选择')"
        @click="selectionMode ? exitSelectionMode() : enterSelectionMode()"
      >
        {{ t(selectionMode ? '取消' : '选择') }}
      </button>
    </div>

    <div v-if="selectionMode" class="session-bulk-bar" role="toolbar" :aria-label="t('删除所选')">
      <button
        class="bulk-select-all"
        type="button"
        role="checkbox"
        :aria-checked="allSelectableSelected"
        @click="toggleSelectAll"
      >
        <span class="session-check" :class="{ checked: allSelectableSelected }" aria-hidden="true"></span>
        {{ t(allSelectableSelected ? '取消全选' : '全选') }}
      </button>
      <button
        class="bulk-delete-button"
        type="button"
        :disabled="selectedSessions.length === 0"
        @click="requestBulkDelete"
      >
        {{ t('删除所选') }}
        <span>{{ selectedSessions.length }}</span>
      </button>
    </div>

    <div class="session-list" :aria-busy="loading">
      <div
        v-for="(session, index) in sessions"
        :key="session.id"
        class="session-item"
        :class="{
          selected: session.id === currentSessionId,
          'menu-open': openSessionId === session.id,
          editing: editingSessionId === session.id,
          'selection-mode': selectionMode,
          'bulk-selected': selectedSessionIds.has(session.id),
          'bulk-disabled': selectionMode && sessionBusy(session)
        }"
      >
        <button
          v-if="editingSessionId !== session.id"
          class="session-select"
          :class="{ 'selection-control': selectionMode }"
          type="button"
          :role="selectionMode ? 'checkbox' : undefined"
          :aria-checked="selectionMode ? selectedSessionIds.has(session.id) : undefined"
          :aria-label="selectionMode ? t('选择会话：{title}', { title: t(session.title) }) : undefined"
          :disabled="selectionMode && sessionBusy(session)"
          :title="selectionMode && sessionBusy(session) ? t('运行中的会话不可选择') : ''"
          @click="selectionMode ? toggleSessionSelection(session) : selectSession(session.id)"
        >
          <span
            v-if="selectionMode"
            class="session-check"
            :class="{ checked: selectedSessionIds.has(session.id) }"
            aria-hidden="true"
          ></span>
          <span class="session-sequence">{{ String(index + 1).padStart(2, '0') }}</span>
          <span class="session-copy">
            <strong>{{ t(session.title) }}</strong>
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
              :aria-label="t('会话名称')"
              @keydown.enter.prevent="commitRename(session)"
              @keydown.esc.prevent="cancelRename"
              @blur="commitRename(session)"
            >
            <small>{{ session.id }}</small>
          </span>
        </div>

        <div v-if="!selectionMode" class="session-actions" @click.stop>
          <button
            class="session-more"
            type="button"
            :aria-expanded="openSessionId === session.id"
            :aria-label="t('更多操作：{title}', { title: t(session.title) })"
            :title="t('更多操作')"
            @click="toggleMenu(session.id)"
          >
            <span></span><span></span><span></span>
          </button>

          <div v-if="openSessionId === session.id" class="session-menu" role="menu">
            <button type="button" role="menuitem" @click="startRename(session)">
              <span aria-hidden="true">✎</span> {{ t('重命名') }}
            </button>
            <button
              type="button"
              role="menuitem"
              class="danger"
              :disabled="sessionBusy(session)"
              :title="t(sessionBusy(session) ? '运行中的会话无法删除' : '删除会话')"
              @click="requestDelete(session)"
            >
              <span aria-hidden="true">⌫</span> {{ t('删除') }}
            </button>
          </div>
        </div>
      </div>

      <p v-if="historyError" class="session-history-note error" role="status">
        {{ t(historyError) }}
      </p>

      <button
        v-if="hasMore || loading"
        class="session-load-more"
        type="button"
        :disabled="loading"
        @click="emit('load-more')"
      >
        <span v-if="loading" class="session-loader" aria-hidden="true"></span>
        {{ t(loading ? '正在读取会话…' : '加载更早会话') }}
      </button>
    </div>

    <button class="rail-create" type="button" @click="$emit('create')">
      <span>＋</span> {{ t('新建任务通道') }}
    </button>

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
            <h2 id="deleteDialogTitle">{{ t('删除这个会话？') }}</h2>
            <p id="deleteDialogDescription">
              {{ t('“{title}”将从服务端会话列表中移除。为满足审计要求，已生成的运行事件仍按系统留存策略保存。', { title: t(pendingDeleteSession.title) }) }}
            </p>
          </div>
          <div class="confirm-dialog-actions">
            <button type="button" class="dialog-cancel" @click="closeDeleteDialog">{{ t('取消') }}</button>
            <button
              ref="deleteConfirmButton"
              type="button"
              class="dialog-confirm"
              @click="confirmDelete"
            >
              {{ t('删除') }}
            </button>
          </div>
        </section>
      </div>
    </Transition>
  </Teleport>

  <Teleport to="body">
    <Transition name="dialog-fade">
      <div
        v-if="pendingBulkDeleteIds.length"
        class="dialog-backdrop"
        @click.self="closeBulkDeleteDialog"
      >
        <section
          class="confirm-dialog"
          role="alertdialog"
          aria-modal="true"
          aria-labelledby="bulkDeleteDialogTitle"
          aria-describedby="bulkDeleteDialogDescription"
        >
          <div class="confirm-dialog-icon" aria-hidden="true">⌫</div>
          <div class="confirm-dialog-copy">
            <h2 id="bulkDeleteDialogTitle">{{ t('批量删除会话？') }}</h2>
            <p id="bulkDeleteDialogDescription">
              {{ t('选中的 {count} 个会话将从服务端会话列表中移除。为满足审计要求，已生成的运行事件仍按系统留存策略保存。', { count: pendingBulkDeleteIds.length }) }}
            </p>
          </div>
          <div class="confirm-dialog-actions">
            <button type="button" class="dialog-cancel" @click="closeBulkDeleteDialog">{{ t('取消') }}</button>
            <button
              ref="bulkDeleteConfirmButton"
              type="button"
              class="dialog-confirm"
              @click="confirmBulkDelete"
            >
              {{ t('删除 {count} 个会话', { count: pendingBulkDeleteIds.length }) }}
            </button>
          </div>
        </section>
      </div>
    </Transition>
  </Teleport>
</template>
