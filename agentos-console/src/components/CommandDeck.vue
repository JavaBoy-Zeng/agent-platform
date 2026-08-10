<script setup>
import { computed } from 'vue'

const props = defineProps({
  agentId: { type: String, required: true },
  sessionId: { type: String, required: true },
  prompt: { type: String, required: true },
  busy: { type: Boolean, default: false }
})

const emit = defineEmits(['update:agentId', 'update:sessionId', 'update:prompt', 'run'])

const characterCount = computed(() => String(props.prompt.length).padStart(4, '0'))

const presets = [
  '分析当前任务并给出下一步行动建议',
  '汇总本次会话的关键结论',
  '检查执行计划中的潜在风险'
]

function onShortcut(event) {
  if ((event.ctrlKey || event.metaKey) && event.key === 'Enter') {
    event.preventDefault()
    emit('run')
  }
}
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
      <span class="deck-mode">SYNC / DIRECT</span>
    </div>

    <div class="prompt-frame">
      <label for="promptInput">任务指令</label>
      <textarea
        id="promptInput"
        :value="prompt"
        rows="3"
        maxlength="2000"
        placeholder="描述目标、限制条件和期望结果……"
        required
        @input="$emit('update:prompt', $event.target.value)"
        @keydown="onShortcut"
      ></textarea>
      <div class="prompt-meta">
        <span><kbd>Ctrl</kbd> + <kbd>Enter</kbd> 执行</span>
        <span>{{ characterCount }} / 2000</span>
      </div>
    </div>

    <div class="deck-actions">
      <div class="prompt-presets" aria-label="示例指令">
        <button v-for="(preset, index) in presets" :key="preset" type="button"
                @click="$emit('update:prompt', preset)">
          {{ ['分析任务', '汇总结论', '检查风险'][index] }}
        </button>
      </div>
      <button class="run-button" type="submit" :disabled="busy || !prompt.trim()">
        <span class="run-label">{{ busy ? '执行中' : '执行任务' }}</span>
        <span v-if="!busy" class="run-arrow" aria-hidden="true">↗</span>
        <span v-else class="run-loader" aria-hidden="true"><i></i><i></i><i></i></span>
      </button>
    </div>
  </form>
</template>
