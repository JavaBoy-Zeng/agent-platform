<script setup>
import { computed, inject } from 'vue'

const consoleState = inject('agentConsole')
const activeRuns = computed(() => consoleState.sessions.value.filter(item => item.activeRunId).length)
const waiting = computed(() => consoleState.sessions.value.filter(item => item.state?.status === 'WAITING').length)

const icons = {
  chat: 'M4 5.5A2.5 2.5 0 0 1 6.5 3h11A2.5 2.5 0 0 1 20 5.5v7a2.5 2.5 0 0 1-2.5 2.5H10l-5 4v-4.4A2.5 2.5 0 0 1 4 12.5z',
  agents: 'M12 3v3m-5 6a5 5 0 0 1 10 0v-1a5 5 0 0 0-10 0zm-3 0H2m20 0h-2M9.5 12h.01m4.99 0h.01',
  runs: 'm9 7 7 5-7 5z', sessions: 'M4 5h16v14H4zM8 3v4m8-4v4M4 9h16',
  tools: 'M14.5 6.5a4 4 0 0 0-5 5L4 17l3 3 5.5-5.5a4 4 0 0 0 5-5l-3 3-3-3z',
  mcp: 'M8 5v4m8-4v4M6 9h12v2a6 6 0 0 1-6 6v3',
  skills: 'm13 2-8 12h6l-1 8 9-13h-6z', memory: 'M12 3a4 4 0 0 0-4 4v10a4 4 0 0 0 8 0V7a4 4 0 0 0-4-4zM8 9H5a2 2 0 0 0 0 4h3m8-4h3a2 2 0 0 1 0 4h-3',
  plans: 'M5 4h14v16H5zM8 8h8m-8 4h8m-8 4h5', traces: 'M4 17c3-8 5-2 8-8s5 0 8-5M4 17h4m-4 0v-4M20 4h-4m4 0v4',
  artifacts: 'm4 7 8-4 8 4-8 4zM4 7v10l8 4 8-4V7M12 11v10', approvals: 'M12 3 4 6v5c0 5 3.4 8.3 8 10 4.6-1.7 8-5 8-10V6l-8-3zM9 12l2 2 4-5',
  models: 'M7 3h10v4h4v10h-4v4H7v-4H3V7h4zM9 9h6v6H9z'
}

const groups = [
  { label: 'OPERATE', items: [{ path: '/chat', label: 'Chat', icon: 'chat' }, { path: '/agents', label: 'Agents', icon: 'agents' }, { path: '/runs', label: 'Runs', icon: 'runs', badge: activeRuns }, { path: '/sessions', label: 'Sessions', icon: 'sessions' }] },
  { label: 'EXTEND', items: [{ path: '/tools', label: 'Tools', icon: 'tools' }, { path: '/mcp', label: 'MCP', icon: 'mcp' }, { path: '/skills', label: 'Skills', icon: 'skills' }, { path: '/models', label: 'Models', icon: 'models' }] },
  { label: 'OBSERVE', items: [{ path: '/memory', label: 'Memory', icon: 'memory' }, { path: '/plans', label: 'Plans', icon: 'plans' }, { path: '/traces', label: 'Traces', icon: 'traces' }, { path: '/artifacts', label: 'Artifacts', icon: 'artifacts' }, { path: '/approvals', label: 'Approvals', icon: 'approvals', badge: waiting }] }
]
</script>

<template>
  <aside class="app-sidebar" aria-label="Console 主导航">
    <div class="sidebar-scroll">
      <section v-for="group in groups" :key="group.label" class="nav-group">
        <p>{{ group.label }}</p>
        <nav>
          <router-link v-for="item in group.items" :key="item.path" :to="item.path" class="nav-link">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path :d="icons[item.icon]" /></svg>
            <span>{{ item.label }}</span>
            <b v-if="item.badge?.value">{{ item.badge.value }}</b>
          </router-link>
        </nav>
      </section>
    </div>
    <div class="sidebar-node">
      <span class="node-pulse"></span>
      <div><strong>LOCAL RUNTIME</strong><small>NODE / HEALTHY</small></div>
    </div>
  </aside>
</template>
