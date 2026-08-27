import { mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { describe, expect, it, vi } from 'vitest'
import App from './App.vue'

vi.mock('./composables/useAgentConsole.js', () => ({ useAgentConsole: () => ({}) }))
vi.mock('./composables/useDesktopWorkspace.js', () => ({
  useDesktopWorkspace: () => ({ dispose: vi.fn(), desktop: false })
}))

describe('application shell routing', () => {
  it('does not render the workbench shell on the login route', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/login', component: { template: '<div data-test="login">Login</div>' } },
        { path: '/chat', component: { template: '<div>Chat</div>' } }
      ]
    })
    await router.push('/login')
    await router.isReady()
    const wrapper = mount(App, { global: { plugins: [router] } })
    expect(wrapper.get('[data-test="login"]').exists()).toBe(true)
    expect(wrapper.find('.codex-shell').exists()).toBe(false)
    wrapper.unmount()
  })
})
