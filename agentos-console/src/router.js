import { createRouter, createWebHistory } from 'vue-router'

import ChatView from './views/ChatView.vue'
import ManagementView from './views/ManagementView.vue'

const routes = [
  { path: '/', redirect: '/chat' },
  { path: '/chat', component: ChatView, name: 'Chat' },
  ...['agents', 'runs', 'sessions', 'tools', 'mcp', 'skills', 'memory', 'plans',
    'traces', 'artifacts', 'approvals', 'models', 'evals'].map(section => ({
    path: `/${section}`,
    component: ManagementView,
    name: section,
    meta: { section }
  }))
]

export const router = createRouter({
  history: createWebHistory(),
  routes
})
