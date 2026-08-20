<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { useLocale } from '../composables/useLocale.js'

const { t } = useLocale()

const props = defineProps({
  agentId: { type: String, required: true },
  sessionId: { type: String, required: true },
  prompt: { type: String, required: true },
  busy: { type: Boolean, default: false },
  canStop: { type: Boolean, default: false }
})

const emit = defineEmits(['update:agentId', 'update:sessionId', 'update:prompt', 'run', 'stop'])

const characterCount = computed(() => String(props.prompt.length).padStart(4, '0'))

const presetSources = [
  '分析当前任务并给出下一步行动建议',
  '汇总本次会话的关键结论',
  '检查执行计划中的潜在风险'
]
const presets = computed(() => presetSources.map(t))
const presetLabels = computed(() => ['分析任务', '汇总结论', '检查风险'].map(t))

const promptInput = ref(null)

/** 粘贴或内容变化后滚动到顶部，确保看到开头而不是 caret 位置。 */
function scrollToTop() {
  const el = promptInput.value
  if (el) el.scrollTop = 0
}

function onInput(event) {
  emit('update:prompt', event.target.value)
  nextTick(scrollToTop)
}

/** Enter 发送，Shift+Enter 换行；中文输入法组词确认的 Enter 不触发发送。 */
function onKeydown(event) {
  if (event.key !== 'Enter') return
  if (event.isComposing || event.keyCode === 229) return
  if (event.shiftKey) return
  event.preventDefault()
  if (!props.busy && props.prompt.trim()) emit('run')
}

watch(() => props.prompt, () => nextTick(scrollToTop))
</script>

<template>
  <form class="command-deck" @submit.prevent="$emit('run')">
    <div class="deck-topline">
      <label class="identity-field">
        <span>AGENT</span>
        <input :value="agentId" autocomplete="off" aria-label="Agent ID"
               @input="$emit('update:agentId', $event.target.value)">
      </label>
      <label class="identity-field session-field">
        <span>SESSION</span>
        <input :value="sessionId" autocomplete="off" aria-label="Session ID"
               @input="$emit('update:sessionId', $event.target.value)">
      </label>
      <span class="deck-mode">BACKGROUND / RESUMABLE</span>
    </div>

    <div class="prompt-frame">
      <label for="promptInput">{{ t('任务指令') }}</label>
      <textarea
        id="promptInput"
        ref="promptInput"
        :value="prompt"
        rows="3"
        maxlength="2000"
        :placeholder="t('描述目标、限制条件和期望结果……')"
        required
        @input="onInput"
        @keydown="onKeydown"
      ></textarea>
      <div class="prompt-meta">
        <span><kbd>Enter</kbd> {{ t('执行') }} · <kbd>Shift</kbd> + <kbd>Enter</kbd> {{ t('换行') }}</span>
        <span>{{ characterCount }} / 2000</span>
      </div>
    </div>

    <div class="deck-actions">
      <div class="prompt-presets" :aria-label="t('示例指令')">
        <button v-for="(preset, index) in presets" :key="preset" type="button"
                @click="$emit('update:prompt', preset)">
          {{ presetLabels[index] }}
        </button>
      </div>
      <button v-if="busy && canStop" class="stop-button" type="button" @click="$emit('stop')">
        <span class="run-label">{{ t('停止任务') }}</span>
        <span class="stop-icon" aria-hidden="true"></span>
      </button>
      <button v-else class="run-button" type="submit" :disabled="busy || !prompt.trim()">
        <span class="run-label">{{ t(busy ? '处理中' : '执行任务') }}</span>
        <span v-if="!busy" class="run-arrow" aria-hidden="true">↗</span>
        <span v-else class="run-loader" aria-hidden="true"><i></i><i></i><i></i></span>
      </button>
    </div>
  </form>
</template>
