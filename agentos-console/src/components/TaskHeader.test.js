import { mount } from '@vue/test-utils'
import { ref } from 'vue'
import { afterEach, describe, expect, it } from 'vitest'
import TaskHeader from './TaskHeader.vue'

let wrapper

afterEach(() => wrapper?.unmount())

function mountHeader() {
  const terminalOpen = ref(false)
  const workspace = {
    available: ref(true),
    currentWorkspace: ref({ id: 'workspace-1', name: 'agent-platform', root: '/workspace' }),
    workspaces: ref([
      { id: 'workspace-1', name: 'agent-platform', root: '/workspace/agent-platform' },
      { id: 'workspace-2', name: 'admin-console', root: '/workspace/web/admin-console' }
    ]),
    inspectorMode: ref(''),
    terminalOpen,
    bindWorkspace() {},
    pickWorkspace() {}
  }
  wrapper = mount(TaskHeader, {
    global: {
      provide: {
        agentConsole: {
          sessions: ref([{ id: 'session-1', title: 'Terminal task' }]),
          currentSessionId: ref('session-1'),
          connection: ref('online'),
          busy: ref(false)
        },
        desktopWorkspace: workspace
      }
    }
  })
  return { terminalOpen }
}

describe('TaskHeader workspace tools', () => {
  it('shows Terminal next to Git Diff and toggles the shared bottom-panel state', async () => {
    const { terminalOpen } = mountHeader()

    expect(wrapper.find('.header-tool-group').exists()).toBe(false)
    expect(wrapper.find('.workspace-picker').exists()).toBe(false)
    expect(wrapper.get('.diff-toggle').attributes('aria-label')).toBe('Git Diff')
    expect(wrapper.get('.terminal-toggle').attributes('aria-label')).toMatch(/终端|Terminal/)
    expect(wrapper.get('.diff-toggle').classes()).toContain('header-icon-button')
    expect(wrapper.get('.terminal-toggle').classes()).toContain('header-icon-button')
    expect(wrapper.get('.terminal-toggle').attributes('aria-pressed')).toBe('false')

    await wrapper.get('.terminal-toggle').trigger('click')

    expect(terminalOpen.value).toBe(true)
    expect(wrapper.get('.terminal-toggle').attributes('aria-pressed')).toBe('true')
  })
})
