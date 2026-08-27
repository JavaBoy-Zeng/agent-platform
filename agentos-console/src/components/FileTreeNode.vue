<script setup>
import { inject, ref } from 'vue'

defineOptions({ name: 'FileTreeNode' })
const props = defineProps({ entry: { type: Object, required: true }, depth: { type: Number, default: 0 } })
const emit = defineEmits(['select'])
const workspace = inject('desktopWorkspace')
const expanded = ref(false)
const loading = ref(false)
const children = ref([])

async function activate() {
  if (!props.entry.traversable) return
  if (props.entry.kind !== 'directory') {
    emit('select', props.entry)
    return
  }
  expanded.value = !expanded.value
  if (!expanded.value || children.value.length) return
  loading.value = true
  try {
    children.value = await workspace.call('list_directory', { relativePath: props.entry.relativePath })
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="file-tree-node">
    <button class="file-tree-row" type="button" :style="{ paddingLeft: `${10 + depth * 14}px` }" @click="activate">
      <span class="tree-chevron" :class="{ open: expanded, hidden: entry.kind !== 'directory' }">›</span>
      <svg v-if="entry.kind === 'directory'" viewBox="0 0 24 24" aria-hidden="true"><path d="M3 6h7l2 2h9v10H3z" /></svg>
      <svg v-else viewBox="0 0 24 24" aria-hidden="true"><path d="M6 3h8l4 4v14H6zM14 3v5h5" /></svg>
      <span>{{ entry.name }}</span>
      <i v-if="loading" class="mini-spinner"></i>
    </button>
    <div v-if="expanded" class="file-tree-children">
      <FileTreeNode v-for="child in children" :key="child.relativePath" :entry="child" :depth="depth + 1" @select="emit('select', $event)" />
      <p v-if="!loading && !children.length" class="tree-empty">Empty</p>
    </div>
  </div>
</template>
