import { mount } from '@vue/test-utils'
import { ref } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AppSidebar from './AppSidebar.vue'

const routerPush = vi.hoisted(() => vi.fn())
vi.mock('vue-router', () => ({
  useRoute: () => ({ path: '/chat' }),
  useRouter: () => ({ push: routerPush })
}))

function mountSidebar() {
  const consoleState = {
    sessions: ref([]),
    currentSessionId: ref('existing-task'),
    connection: ref('online'),
    createSession: vi.fn(() => { consoleState.currentSessionId.value = 'new-task' }),
    selectSession: vi.fn(),
    renameSession: vi.fn(),
    deleteSession: vi.fn(),
    deleteSessions: vi.fn()
  }
  const desktopWorkspace = {
    desktop: true,
    available: ref(true),
    workspaces: ref([
      { id: 'workspace-1', name: 'agent-platform', root: '/local/agent-platform', gitRepository: true }
    ]),
    currentWorkspace: ref(null),
    picking: ref(false),
    error: ref(''),
    bindWorkspace: vi.fn(),
    pickWorkspace: vi.fn(async () => ({
      id: 'workspace-1', name: 'agent-platform', root: '/local/agent-platform', gitRepository: true
    }))
  }
  const wrapper = mount(AppSidebar, {
    global: {
      provide: { agentConsole: consoleState, desktopWorkspace },
      stubs: {
        Teleport: true,
        RouterLink: { template: '<a><slot /></a>' }
      }
    }
  })
  return { wrapper, consoleState, desktopWorkspace }
}

describe('AppSidebar new task workspace dialog', () => {
  beforeEach(() => routerPush.mockReset())

  it('creates a task only after choosing and confirming its local folder', async () => {
    const { wrapper, consoleState, desktopWorkspace } = mountSidebar()

    await wrapper.get('.new-task-button').trigger('click')
    expect(consoleState.createSession).not.toHaveBeenCalled()
    expect(wrapper.get('.new-task-dialog').attributes('aria-modal')).toBe('true')

    await wrapper.findAll('.new-task-workspaces > button')[1].trigger('click')
    await wrapper.get('.new-task-create').trigger('click')

    expect(consoleState.createSession).toHaveBeenCalledOnce()
    expect(desktopWorkspace.bindWorkspace).toHaveBeenCalledWith('workspace-1')
    expect(routerPush).toHaveBeenCalledWith('/chat')
  })

  it('opens the native picker without rebinding the active task', async () => {
    const { wrapper, desktopWorkspace } = mountSidebar()

    await wrapper.get('.new-task-button').trigger('click')
    await wrapper.get('.new-task-browse').trigger('click')

    expect(desktopWorkspace.pickWorkspace).toHaveBeenCalledWith({ bind: false })
    expect(desktopWorkspace.bindWorkspace).not.toHaveBeenCalled()
  })
})
