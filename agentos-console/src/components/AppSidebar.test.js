import { mount } from '@vue/test-utils'
import { ref } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import AppSidebar from './AppSidebar.vue'

const routerPush = vi.hoisted(() => vi.fn())
const mountedWrappers = []
vi.mock('vue-router', () => ({
  useRoute: () => ({ path: '/chat' }),
  useRouter: () => ({ push: routerPush })
}))

function mountSidebar(sessions = [], workspaceAssociations = {}) {
  const consoleState = {
    sessions: ref(sessions),
    currentSessionId: ref('existing-task'),
    currentSessionDraft: ref(true),
    connection: ref('online'),
    createSession: vi.fn(() => { consoleState.currentSessionId.value = 'new-task' }),
    isSessionDraft: vi.fn(session => Boolean(session.draft)),
    selectSession: vi.fn(),
    renameSession: vi.fn(),
    toggleSessionPin: vi.fn(async id => {
      const session = consoleState.sessions.value.find(item => item.id === id)
      if (!session) return false
      session.pinned = !session.pinned
      session.pinnedAt = session.pinned ? new Date().toISOString() : ''
      return true
    }),
    deleteSession: vi.fn(),
    deleteSessions: vi.fn(async ids => ({ deleted: ids.length, failed: 0 }))
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
    workspaceForSession: vi.fn(sessionId => {
      const workspaceId = workspaceAssociations[sessionId]
      return workspaceId === 'workspace-1'
        ? { id: 'workspace-1', name: 'agent-platform', root: '/local/agent-platform', gitRepository: true }
        : null
    }),
    bindWorkspace: vi.fn(),
    clearWorkspace: vi.fn(),
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
  mountedWrappers.push(wrapper)
  return { wrapper, consoleState, desktopWorkspace }
}

describe('AppSidebar new task navigation', () => {
  beforeEach(() => routerPush.mockReset())
  afterEach(() => {
    mountedWrappers.splice(0).forEach(wrapper => wrapper.unmount())
  })

  it('creates a task immediately and opens the chat workspace without a dialog', async () => {
    const { wrapper, consoleState, desktopWorkspace } = mountSidebar()

    expect(wrapper.get('.new-task-button').text()).toMatch(/新建任务|New task/)
    expect(wrapper.get('.new-task-button svg path').attributes('d')).toContain('M4 20h4')
    await wrapper.get('.new-task-button').trigger('click')

    expect(consoleState.createSession).toHaveBeenCalledOnce()
    expect(wrapper.find('.new-task-dialog').exists()).toBe(false)
    expect(desktopWorkspace.bindWorkspace).not.toHaveBeenCalled()
    expect(routerPush).toHaveBeenCalledWith('/chat')
  })

  it.each([
    ['Command', { metaKey: true }],
    ['Control', { ctrlKey: true }]
  ])('creates a new task with %s+N and prevents the browser action', async (_modifier, modifier) => {
    const { wrapper, consoleState } = mountSidebar()
    const event = new KeyboardEvent('keydown', {
      key: 'n', ...modifier, bubbles: true, cancelable: true
    })

    document.dispatchEvent(event)
    await wrapper.vm.$nextTick()

    expect(event.defaultPrevented).toBe(true)
    expect(wrapper.find('.new-task-dialog').exists()).toBe(false)
    expect(consoleState.createSession).toHaveBeenCalledOnce()
    expect(routerPush).toHaveBeenCalledWith('/chat')
  })
})

describe('AppSidebar session bulk delete', () => {
  beforeEach(() => routerPush.mockReset())
  afterEach(() => {
    mountedWrappers.splice(0).forEach(wrapper => wrapper.unmount())
  })

  it('exposes a visible manage action and deletes the selected sessions after confirmation', async () => {
    const sessions = [
      { id: 'session-1', title: '需求分析', updatedAt: new Date().toISOString() },
      { id: 'session-2', title: '接口设计', updatedAt: new Date().toISOString() }
    ]
    const { wrapper, consoleState } = mountSidebar(sessions)

    expect(wrapper.get('.rail-select-toggle').text()).toMatch(/管理|Manage/)
    await wrapper.get('.rail-select-toggle').trigger('click')
    const sessionControls = wrapper.findAll('.task-select.selection-control')
    expect(sessionControls).toHaveLength(2)

    await sessionControls[0].trigger('click')
    await sessionControls[1].trigger('click')
    expect(wrapper.get('.bulk-delete-button').attributes('disabled')).toBeUndefined()
    await wrapper.get('.bulk-delete-button').trigger('click')
    expect(wrapper.get('#bulkDeleteTaskTitle').text()).toMatch(/批量删除任务|Delete selected tasks/)

    await wrapper.get('.confirm-dialog .dialog-confirm').trigger('click')
    expect(consoleState.deleteSessions).toHaveBeenCalledWith(['session-1', 'session-2'])
  })

  it('does not include a running session when selecting all', async () => {
    const sessions = [
      { id: 'idle', title: '空闲会话', updatedAt: new Date().toISOString() },
      { id: 'running', title: '运行中', activeRunId: 'run-1', updatedAt: new Date().toISOString() }
    ]
    const { wrapper } = mountSidebar(sessions)

    await wrapper.get('.rail-select-toggle').trigger('click')
    await wrapper.get('.bulk-select-all').trigger('click')

    const controls = wrapper.findAll('.task-select.selection-control')
    expect(controls[0].attributes('aria-checked')).toBe('true')
    expect(controls[1].attributes('disabled')).toBeDefined()
    expect(wrapper.get('.bulk-delete-button').text()).toContain('1')
  })
})

describe('AppSidebar task directory groups', () => {
  afterEach(() => {
    mountedWrappers.splice(0).forEach(wrapper => wrapper.unmount())
  })

  it('collects every task without a workspace in the default folder', () => {
    const sessions = [
      { id: 'unbound-1', title: '较早任务', createdAt: '2026-08-27T09:00:00Z', updatedAt: '2026-08-29T09:00:00Z' },
      { id: 'bound-1', title: '接口设计', createdAt: '2026-08-28T09:00:00Z', updatedAt: '2026-08-28T09:00:00Z' },
      { id: 'unbound-2', title: '最新任务', createdAt: '2026-08-29T09:00:00Z', updatedAt: '2026-08-27T09:00:00Z' }
    ]
    const { wrapper } = mountSidebar(sessions, { 'bound-1': 'workspace-1' })
    const groups = wrapper.findAll('.task-group')

    expect(groups).toHaveLength(2)
    expect(groups[0].get('.task-directory-label').text()).toMatch(/默认目录|Default folder/)
    expect(groups[0].get('.task-directory-icon path').attributes('d')).toContain('M4.2 18.5l2-6.4')
    expect(groups[0].findAll('.task-row')).toHaveLength(2)
    expect(groups[0].findAll('.task-row')[0].text()).toContain('最新任务')
    expect(groups[0].findAll('.task-row')[1].text()).toContain('较早任务')
    expect(groups[1].get('.task-directory-label').text()).toContain('agent-platform')
    expect(groups[1].findAll('.task-row')).toHaveLength(1)
  })

  it('creates a blank task from a directory add action and keeps that directory context', async () => {
    const sessions = [
      { id: 'bound-1', title: '接口设计', createdAt: '2026-08-28T09:00:00Z' }
    ]
    const { wrapper, consoleState, desktopWorkspace } = mountSidebar(sessions, { 'bound-1': 'workspace-1' })
    const directoryGroup = wrapper.get('.task-group')
    const createButton = directoryGroup.get('.task-directory-new')

    expect(createButton.attributes('aria-label')).toMatch(/agent-platform/)
    expect(createButton.get('path').attributes('d')).toBe('M12 5v14M5 12h14')
    await createButton.trigger('click')

    expect(consoleState.createSession).toHaveBeenCalledOnce()
    expect(desktopWorkspace.bindWorkspace).toHaveBeenCalledWith('workspace-1')
    expect(routerPush).toHaveBeenCalledWith('/chat')
  })

  it('keeps unsent drafts out of the task directory', () => {
    const sessions = [
      { id: 'draft-1', title: '未命名任务', createdAt: '2026-08-30T09:00:00Z', draft: true },
      { id: 'task-1', title: '正式任务', createdAt: '2026-08-29T09:00:00Z' }
    ]
    const { wrapper } = mountSidebar(sessions)

    expect(wrapper.findAll('.task-row')).toHaveLength(1)
    expect(wrapper.get('.task-row').text()).toContain('正式任务')
    expect(wrapper.text()).not.toContain('未命名任务')
  })

  it('uses an open, unboxed control icon for the workbench entry', () => {
    const { wrapper } = mountSidebar()

    expect(wrapper.get('.workbench-toggle path').attributes('d')).toContain('M4 7h9')
  })

  it('shows the pinned directory only while pinned tasks exist', () => {
    const sessions = [
      { id: 'pinned-1', title: '置顶对话', createdAt: '2026-08-29T09:00:00Z', pinned: true, pinnedAt: '2026-08-30T09:00:00Z' },
      { id: 'regular-1', title: '普通对话', createdAt: '2026-08-28T09:00:00Z' }
    ]
    const { wrapper } = mountSidebar(sessions)
    const groups = wrapper.findAll('.task-group')

    expect(groups).toHaveLength(2)
    expect(groups[0].get('.task-directory-label').text()).toMatch(/置顶|Pinned/)
    expect(groups[0].find('.pinned-directory-icon').exists()).toBe(true)
    expect(groups[0].get('.task-row').text()).toContain('置顶对话')
    expect(groups[1].get('.task-directory-label').text()).toMatch(/默认目录|Default folder/)
    expect(wrapper.findAll('.task-row').filter(row => row.text().includes('置顶对话'))).toHaveLength(1)
  })

  it('moves a task into and out of the pinned directory from its leading pin control', async () => {
    const sessions = [
      { id: 'task-1', title: '接口设计', createdAt: '2026-08-28T09:00:00Z', pinned: false }
    ]
    const { wrapper, consoleState } = mountSidebar(sessions)

    expect(wrapper.find('.pinned-directory-icon').exists()).toBe(false)
    const pinButton = wrapper.get('.task-pin')
    expect(pinButton.attributes('aria-pressed')).toBe('false')
    await pinButton.trigger('click')

    expect(consoleState.toggleSessionPin).toHaveBeenCalledWith('task-1')
    expect(wrapper.get('.pinned-directory-icon').exists()).toBe(true)
    const pinnedButton = wrapper.get('.task-pin.active')
    expect(pinnedButton.attributes('aria-pressed')).toBe('true')
    await pinnedButton.trigger('click')

    expect(wrapper.find('.pinned-directory-icon').exists()).toBe(false)
  })
})
