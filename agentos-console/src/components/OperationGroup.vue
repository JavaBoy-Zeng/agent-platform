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
  read: { label: '已读取', unit: '个文件' },
  edit: { label: '已编辑', unit: '个文件' },
  command: { label: '已执行', unit: '条命令' },
  tool: { label: '已调用', unit: '个工具' }
}

const meta = computed(() =>
  GROUP_META[props.kind] || { label: '已执行', unit: '项操作' })
const failedCount = computed(() =>
  props.items.filter(item => !item.success).length)
const activeCount = computed(() =>
  props.items.filter(item => ['RUNNING', 'APPROVED'].includes(item.status)).length)
const waitingCount = computed(() =>
  props.items.filter(item => item.status === 'WAITING').length)
const groupLabel = computed(() => {
  if (waitingCount.value) return '等待批准'
  if (activeCount.value) return '正在执行'
  return meta.value.label
})

function statusText(item) {
  if (!item.success) return t('失败')
  if (item.status === 'RUNNING' || item.status === 'APPROVED') return t('执行中')
  if (item.status === 'WAITING') return t('等待批准')
  if (item.status === 'FAILED') return t('失败')
  return t('完成')
}

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
  <div class="op-group" :class="[`op-${kind}`, { expanded, 'has-failures': failedCount, active: activeCount, waiting: waitingCount }]">
    <button
      class="op-summary"
      type="button"
      :aria-expanded="expanded"
      @click="$emit('toggle')"
    >
      <span class="op-icon" aria-hidden="true">
        <svg v-if="kind === 'read'" viewBox="0 0 24 24">
          <path d="M6.5 3.5h7l4 4v13h-11zM13.5 3.5v4h4M9.5 12h5M9.5 15.5h5" />
        </svg>
        <svg v-else-if="kind === 'edit'" viewBox="0 0 24 24">
          <path d="M5 19h3l10-10-3-3L5 16zM13.5 7.5l3 3M5 12V4.5h8M12 19H5" />
        </svg>
        <svg v-else-if="kind === 'command'" viewBox="0 0 24 24">
          <path d="M4 5.5h16v13H4zM7.5 10l2.5 2-2.5 2M12 15h4.5" />
        </svg>
        <svg v-else viewBox="0 0 24 24">
          <path d="M8.5 4.5h7v3h3v7h-3v3h-7v-3h-3v-7h3zM10 9.5h4v4h-4z" />
        </svg>
      </span>
      <span class="op-label">{{ t(groupLabel) }}</span>
      <span class="op-separator" aria-hidden="true"></span>
      <span class="op-count"><strong>{{ items.length }}</strong> {{ t(meta.unit) }}</span>
      <span v-if="failedCount" class="op-failed-count">
        {{ t('失败') }} {{ failedCount }}
      </span>
      <svg class="op-chevron" :class="{ open: expanded }" viewBox="0 0 16 16" aria-hidden="true">
        <path d="m6 4 4 4-4 4" />
      </svg>
    </button>
    <Transition name="op-reveal">
      <div v-if="expanded" class="op-details">
        <div v-for="(item, index) in items" :key="item.toolCallId || index"
             class="op-item" :class="{ failed: !item.success, running: item.status === 'RUNNING', waiting: item.status === 'WAITING' }">
          <header class="op-item-head">
            <code class="op-tool">{{ item.toolName }}</code>
            <code v-if="entryTarget(item)" class="op-target">{{ entryTarget(item) }}</code>
            <span class="op-status-badge" :class="String(item.status || 'COMPLETED').toLowerCase()">
              {{ statusText(item) }}
            </span>
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
    </Transition>
  </div>
</template>
