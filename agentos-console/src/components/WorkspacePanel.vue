<script setup>
import { computed, inject, ref, watch } from 'vue'
import FileTreeNode from './FileTreeNode.vue'
import { useLocale } from '../composables/useLocale.js'

const workspace = inject('desktopWorkspace')
const { t } = useLocale()
const rootEntries = ref([])
const file = ref(null)
const status = ref(null)
const diff = ref(null)
const loading = ref(false)
const error = ref('')
const diffScope = ref('working')

const mode = workspace.inspectorMode
const currentWorkspace = workspace.currentWorkspace
const diffLines = computed(() => (diff.value?.patch || '').split('\n'))

async function loadFiles() {
  if (!currentWorkspace.value) return
  loading.value = true; error.value = ''; file.value = null
  try { rootEntries.value = await workspace.call('list_directory', { relativePath: '' }) }
  catch (cause) { error.value = String(cause) }
  finally { loading.value = false }
}
async function openFile(entry) {
  loading.value = true; error.value = ''
  try { file.value = await workspace.call('read_file', { relativePath: entry.relativePath }) }
  catch (cause) { error.value = String(cause) }
  finally { loading.value = false }
}
async function loadStatus() {
  if (!currentWorkspace.value?.gitRepository) return
  loading.value = true; error.value = ''; diff.value = null
  try { status.value = await workspace.call('git_status') }
  catch (cause) { error.value = String(cause) }
  finally { loading.value = false }
}
async function loadDiff(path = null) {
  loading.value = true; error.value = ''
  try { diff.value = await workspace.call('git_diff', { scope: diffScope.value, path }) }
  catch (cause) { error.value = String(cause) }
  finally { loading.value = false }
}
function lineClass(line) {
  if (line.startsWith('+++') || line.startsWith('---')) return 'diff-file-line'
  if (line.startsWith('+')) return 'diff-add'
  if (line.startsWith('-')) return 'diff-remove'
  if (line.startsWith('@@')) return 'diff-hunk'
  return ''
}

watch([currentWorkspace, mode], ([selected, selectedMode]) => {
  if (!selected) return
  if (selectedMode === 'files') loadFiles()
  if (selectedMode === 'diff') loadStatus()
}, { immediate: true })
watch(diffScope, () => { if (mode.value === 'diff') loadStatus() })
</script>

<template>
  <aside class="workspace-panel" :aria-label="mode === 'files' ? t('文件') : 'Git Diff'">
    <header class="workspace-panel-head">
      <div><small>{{ currentWorkspace?.name }}</small><strong>{{ mode === 'files' ? t('文件') : t('更改') }}</strong></div>
      <button type="button" :aria-label="t('关闭')" @click="mode = ''">×</button>
    </header>
    <div v-if="!currentWorkspace" class="workspace-empty">
      <span>⌘</span><strong>{{ t('选择一个工作区') }}</strong><p>{{ t('为当前任务选择本机项目目录。') }}</p>
      <button type="button" @click="workspace.pickWorkspace">{{ t('打开文件夹') }}</button>
    </div>
    <template v-else-if="mode === 'files'">
      <div class="workspace-toolbar"><span>{{ currentWorkspace.root }}</span><button type="button" @click="loadFiles">↻</button></div>
      <div class="file-browser" :class="{ previewing: file }">
        <nav class="file-tree" aria-label="File tree">
          <FileTreeNode v-for="entry in rootEntries" :key="entry.relativePath" :entry="entry" @select="openFile" />
        </nav>
        <section v-if="file" class="file-preview">
          <header><span>{{ file.relativePath }}</span><button type="button" @click="file = null">×</button></header>
          <div v-if="file.binary" class="binary-state"><strong>BINARY FILE</strong><span>{{ file.size }} bytes</span></div>
          <pre v-else><code>{{ file.content }}</code></pre>
          <footer v-if="file.truncated">{{ t('内容已截断') }} · 1 MiB</footer>
        </section>
      </div>
    </template>
    <template v-else>
      <div v-if="!currentWorkspace.gitRepository" class="workspace-empty"><span>⌥</span><strong>{{ t('不是 Git 仓库') }}</strong><p>{{ t('请选择 Git 仓库根目录。') }}</p></div>
      <template v-else>
        <div class="diff-summary">
          <div><strong>{{ status?.branch || 'HEAD' }}</strong><small v-if="status?.upstream">{{ status.upstream }} · ↑{{ status.ahead }} ↓{{ status.behind }}</small></div>
          <div class="segmented-control" role="group" aria-label="Diff scope">
            <button type="button" :class="{ active: diffScope === 'working' }" @click="diffScope = 'working'">{{ t('工作区') }}</button>
            <button type="button" :class="{ active: diffScope === 'staged' }" @click="diffScope = 'staged'">{{ t('暂存区') }}</button>
          </div>
        </div>
        <div v-if="!diff" class="changes-list">
          <button v-for="entry in status?.entries || []" :key="`${entry.oldPath}-${entry.path}`" type="button" @click="loadDiff(entry.path)">
            <span class="change-code">{{ entry.indexStatus }}{{ entry.worktreeStatus }}</span>
            <span>{{ entry.path }}</span><small v-if="entry.oldPath">← {{ entry.oldPath }}</small>
          </button>
          <div v-if="status && !status.entries.length" class="clean-state">✓ {{ t('工作区没有更改') }}</div>
          <button v-if="status?.entries?.length" class="view-all-diff" type="button" @click="loadDiff()">{{ t('查看全部更改') }}</button>
        </div>
        <section v-else class="diff-viewer">
          <header><button type="button" @click="diff = null">← {{ t('返回') }}</button><span><b>+{{ diff.additions }}</b><i>-{{ diff.deletions }}</i></span></header>
          <pre><code><span v-for="(line, index) in diffLines" :key="index" :class="lineClass(line)">{{ line }}
</span></code></pre>
          <footer v-if="diff.truncated">{{ t('Diff 已达到显示上限') }}</footer>
        </section>
      </template>
    </template>
    <div v-if="loading" class="panel-loading"><i></i></div>
    <p v-if="error" class="panel-error">{{ error }}</p>
  </aside>
</template>
