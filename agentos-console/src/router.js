import { createRouter, createWebHistory } from 'vue-router'

import ChatView from './views/ChatView.vue'
import LoginView from './views/LoginView.vue'
import ManagementView from './views/ManagementView.vue'
import SettingsView from './views/SettingsView.vue'
import { getAuthToken } from './services/apiConfig.js'

const routes = [
  { path: '/', redirect: '/chat' },
  { path: '/chat', component: ChatView, name: 'Chat' },
  { path: '/login', component: LoginView, name: 'Login' },
  { path: '/settings', component: SettingsView, name: 'Settings' },
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

router.beforeEach(to => {
  const authenticated = Boolean(getAuthToken())
  if (!authenticated && to.path !== '/login') return '/login'
  if (authenticated && to.path === '/login') return '/chat'
  return true
})
