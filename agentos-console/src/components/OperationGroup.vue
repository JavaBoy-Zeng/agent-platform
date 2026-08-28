<script setup>
import { computed } from 'vue'
import { useLocale } from '../composables/useLocale.js'

const props = defineProps({
  kind: { type: String, required: true },
  items: { type: Array, required: true },
  expanded: { type: Boolean, default: false }
})

defineEmits(['toggle'])

const { t } = useLocale()

const GROUP_META = {
  read: { label: '已读取', unit: '个文件', icon: '▤' },
  edit: { label: '已编辑', unit: '个文件', icon: '✎' },
  command: { label: '已执行', unit: '条命令', icon: '❯' },
  tool: { label: '已调用', unit: '个工具', icon: '⚙' }
}

const meta = computed(() =>
  GROUP_META[props.kind] || { label: '已执行', unit: '项操作', icon: '≡' })
const failedCount = computed(() =>
  props.items.filter(item => !item.success).length)

function entryTarget(item) {
  const args = item.arguments || {}
  return args.path || args.command || args.url || args.query || args.input || ''
}

function argumentEntries(item) {
  return Object.entries(item.arguments || {})
    .filter(([, value]) => value !== '' && value !== null && value !== undefined)
    .slice(0, 6)
}
</script>

<template>
  <div class="op-group" :class="`op-${kind}`">
    <button class="op-summary" type="button" @click="$emit('toggle')">
      <span class="op-icon" aria-hidden="true">{{ meta.icon }}</span>
      <span class="op-count">{{ t(meta.label) }} {{ items.length }} {{ t(meta.unit) }}</span>
      <span v-if="failedCount" class="op-failed-count">
        {{ t('失败') }} {{ failedCount }}
      </span>
      <span class="op-chevron" :class="{ open: expanded }" aria-hidden="true">›</span>
    </button>
    <div v-if="expanded" class="op-details">
      <div v-for="(item, index) in items" :key="index"
           class="op-item" :class="{ failed: !item.success }">
        <header class="op-item-head">
          <code class="op-tool">{{ item.toolName }}</code>
          <code v-if="entryTarget(item)" class="op-target">{{ entryTarget(item) }}</code>
          <span v-if="!item.success" class="op-failed-badge">{{ t('失败') }}</span>
        </header>
        <dl v-if="argumentEntries(item).length" class="op-args">
          <template v-for="[key, value] in argumentEntries(item)" :key="key">
            <dt>{{ key }}</dt>
            <dd>{{ value }}</dd>
          </template>
        </dl>
        <p v-if="item.summary" class="op-result">{{ item.summary }}</p>
      </div>
    </div>
  </div>
</template>
