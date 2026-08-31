import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import { useAgentConsole } from './useAgentConsole.js'

const api = vi.hoisted(() => ({
  getModelManagement: vi.fn(async () => ({ providers: [], models: [] })),
  getSessionPage: vi.fn(async () => ({ items: [], total: 0, hasMore: false })),
  getAgentState: vi.fn(async () => null),
  createAgentRun: vi.fn(),
  updateSessionPinned: vi.fn(async (_id, pinned) => ({ state: {
    pinned,
    pinnedAt: pinned ? '2026-08-30T09:00:00Z' : ''
  } }))
}))

vi.mock('../services/agentApi.js', () => ({
  cancelAgentRun: vi.fn(),
  createAgentRun: (...args) => api.createAgentRun(...args),
  getAgentState: (...args) => api.getAgentState(...args),
  getAgentRun: vi.fn(),
  getPendingAction: vi.fn(),
  resolvePendingAction: vi.fn(),
  streamAgentRun: vi.fn()
}))

vi.mock('../services/consoleApi.js', () => ({
  deleteSessionRecord: vi.fn(),
  deleteSessionRecords: vi.fn(),
  getModelManagement: (...args) => api.getModelManagement(...args),
  getUsage: vi.fn(),
  getSessionEvents: vi.fn(),
  getSessionPage: (...args) => api.getSessionPage(...args),
  updateSessionPinned: (...args) => api.updateSessionPinned(...args),
  updateSessionTitle: vi.fn()
}))

function mountConsole() {
  let consoleState
  const Harness = defineComponent({
    setup() {
      consoleState = useAgentConsole()
      return () => h('div')
    }
  })
  const wrapper = mount(Harness)
  return { wrapper, get consoleState() { return consoleState } }
}

describe('agent console drafts', () => {
  it('removes legacy blank tasks and reuses one transient draft', async () => {
    localStorage.setItem('agentos.console.sessions.v1', JSON.stringify([
      { id: 'draft-1', title: '未命名任务', state: null, messages: [] },
      { id: 'draft-2', title: '未命名任务', state: null, messages: [] },
      { id: 'task-1', title: '正式任务', serverBacked: true, state: null, messages: [] }
    ]))
    localStorage.setItem('agentos.console.active-session.v1', 'draft-2')
    const harness = mountConsole()
    await flushPromises()

    expect(harness.consoleState.sessions.value.map(session => session.id)).toEqual(['task-1'])

    const firstDraft = harness.consoleState.createSession()
    const secondDraft = harness.consoleState.createSession()

    expect(secondDraft.id).toBe(firstDraft.id)
    expect(harness.consoleState.sessions.value).toHaveLength(2)
    expect(harness.consoleState.currentSessionDraft.value).toBe(true)
    expect(JSON.parse(localStorage.getItem('agentos.console.sessions.v1'))).toEqual([
      expect.objectContaining({ id: 'task-1' })
    ])
    expect(localStorage.getItem('agentos.console.active-session.v1')).toBeNull()
    harness.wrapper.unmount()
  })

  it('loads enabled configured models without selecting a default', async () => {
    api.getModelManagement.mockResolvedValueOnce({
      providers: [],
      models: [{
        id: 'model-deepseek', modelId: 'deepseek-v4-pro', modelType: 'BUILT_IN',
        providerName: 'DeepSeek', providerType: 'DEEPSEEK', enabled: true
      }]
    })
    const harness = mountConsole()
    await flushPromises()

    expect(harness.consoleState.models.value).toEqual([
      expect.objectContaining({ key: 'model-deepseek', name: 'deepseek-v4-pro' })
    ])
    expect(harness.consoleState.currentModel.value).toBeNull()
    harness.wrapper.unmount()
  })

  it('rejects submission before creating a task when no model is selected', async () => {
    const harness = mountConsole()
    await flushPromises()
    harness.consoleState.createSession()
    harness.consoleState.prompt.value = 'missing model'

    await expect(harness.consoleState.execute()).rejects.toThrow('请先选择一个已启用的模型')

    expect(api.createAgentRun).not.toHaveBeenCalled()
    expect(harness.consoleState.prompt.value).toBe('missing model')
    harness.wrapper.unmount()
  })

  it('sends only the selected platform model id with the current task', async () => {
    api.getModelManagement.mockResolvedValueOnce({
      providers: [],
      models: [{
        id: 'model-deepseek', modelId: 'deepseek-v4-pro', modelType: 'BUILT_IN',
        providerName: 'DeepSeek', providerType: 'DEEPSEEK', enabled: true
      }]
    })
    api.createAgentRun.mockRejectedValueOnce(new Error('stop after request capture'))
    const harness = mountConsole()
    await flushPromises()
    harness.consoleState.createSession()
    harness.consoleState.selectTaskModel('model-deepseek')
    harness.consoleState.prompt.value = 'use DeepSeek for this task'

    await harness.consoleState.execute()

    expect(api.createAgentRun).toHaveBeenLastCalledWith(expect.objectContaining({
      attributes: expect.objectContaining({
        modelId: 'model-deepseek'
      })
    }))
    expect(api.createAgentRun.mock.calls.at(-1)[0].attributes).not.toHaveProperty('providerId')
    harness.wrapper.unmount()
  })

  it('hydrates and persists a server-backed pinned task', async () => {
    api.getSessionPage.mockResolvedValueOnce({
      items: [{
        sessionId: 'session-pinned',
        createdAt: '2026-08-29T09:00:00Z',
        lastActiveAt: '2026-08-30T08:00:00Z',
        state: { displayTitle: '关键任务', pinned: true, pinnedAt: '2026-08-30T09:00:00Z' }
      }],
      total: 1,
      hasMore: false
    })
    const harness = mountConsole()
    await flushPromises()

    const session = harness.consoleState.sessions.value.find(item => item.id === 'session-pinned')
    expect(session).toEqual(expect.objectContaining({ pinned: true, pinnedAt: '2026-08-30T09:00:00Z' }))

    await harness.consoleState.toggleSessionPin('session-pinned')

    expect(api.updateSessionPinned).toHaveBeenCalledWith('session-pinned', false)
    expect(session.pinned).toBe(false)
    expect(JSON.parse(localStorage.getItem('agentos.console.sessions.v1'))[0]).toEqual(
      expect.objectContaining({ id: 'session-pinned', pinned: false }))
    harness.wrapper.unmount()
  })
})
