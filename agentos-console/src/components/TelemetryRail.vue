<script setup>
import { computed } from 'vue'
import { agentLabel } from '../utils/agentIdentity.js'
import { useLocale } from '../composables/useLocale.js'

const { localeTag, t } = useLocale()

const props = defineProps({
  runtimeState: { type: Object, required: true },
  activeStage: { type: Number, default: 0 },
  executingAgentId: { type: String, default: '' }
})

const description = computed(() => t(({
  READY: '运行时空闲，等待任务输入。',
  RUNNING: 'Agent Loop 正在处理当前任务。',
  COMPLETED: '任务执行完成，状态已归档。',
  FAILED: '任务执行失败，请检查运行记录。',
  WAITING: '高风险操作正在等待人工批准。',
  CANCELLED: '当前任务已被取消。'
})[props.runtimeState.status] || '等待运行时状态。'))

const updatedTime = computed(() => {
  if (!props.runtimeState.updatedAt) return '--:--:--'
  return new Intl.DateTimeFormat(localeTag.value, {
    hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false
  }).format(new Date(props.runtimeState.updatedAt))
})

const stageSources = [
  ['AGENT', '建立本次运行上下文'],
  ['PLANNER', '生成最小可执行计划'],
  ['TOOL', '调用注册工具'],
  ['OBSERVATION', '摘要化工具执行结果'],
  ['DECISION', '完成或继续规划']
]
const stages = computed(() => stageSources.map(([name, description], index) => [
  index === 0 ? agentLabel(props.executingAgentId) : name, t(description)
]))

function stageClass(index) {
  const stage = index + 1
  return {
    active: props.activeStage === stage,
    done: props.activeStage > stage
      || (props.activeStage === 0 && props.runtimeState.status === 'COMPLETED')
  }
}
</script>

<template>
  <aside class="telemetry-rail reveal reveal-3" :aria-label="t('运行遥测')">
    <section class="telemetry-card state-card">
      <header><span class="section-index">03</span><span>RUNTIME STATE</span></header>
      <div class="state-readout" :data-state="runtimeState.status">
        <span class="state-pulse"></span>
        <strong>{{ runtimeState.status }}</strong>
      </div>
      <p>{{ description }}</p>
      <dl class="metric-grid">
        <div><dt>ITERATION</dt><dd>{{ String(runtimeState.iteration || 0).padStart(2, '0') }}</dd></div>
        <div><dt>UPDATED</dt><dd>{{ updatedTime }}</dd></div>
      </dl>
    </section>

    <section class="telemetry-card pipeline-card">
      <header><span class="section-index">04</span><span>EXECUTION PIPELINE</span></header>
      <ol class="pipeline">
        <li v-for="(stage, index) in stages" :key="stage[0]" :class="stageClass(index)">
          <i>{{ String(index + 1).padStart(2, '0') }}</i>
          <span>{{ stage[0] }}</span>
          <small>{{ stage[1] }}</small>
        </li>
      </ol>
    </section>

    <section class="telemetry-card safety-card">
      <header><span class="section-index">05</span><span>SAFETY GATE</span></header>
      <div class="safety-status">
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path d="M12 3 5 6v5c0 4.6 2.8 8.2 7 10 4.2-1.8 7-5.4 7-10V6l-7-3Z" />
          <path d="m9 12 2 2 4-4" />
        </svg>
        <div><strong>HITL ARMED</strong><span>{{ t('MEDIUM+ 需要人工批准') }}</span></div>
      </div>
    </section>

    <section class="endpoint-note">
      <span>ACTIVE ENDPOINT</span>
      <code>POST /api/agent-runs</code>
      <code>GET /api/agent-runs/{id}/events</code>
    </section>
  </aside>
</template>
