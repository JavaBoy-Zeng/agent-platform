import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import { useAgentConsole } from './useAgentConsole.js'

const api = vi.hoisted(() => ({
  getSessionRunHistory: vi.fn(async () => []),
  getModelCatalog: vi.fn(async () => []),
  getModelManagement: vi.fn(async () => ({ providers: [], models: [] })),
  getSessionPage: vi.fn(async () => ({ items: [], total: 0, hasMore: false })),
  getAgentState: vi.fn(async () => null),
  getAgentRun: vi.fn(),
  streamAgentRun: vi.fn(),
  createAgentRun: vi.fn(),
  resolvePendingAction: vi.fn(),
  updateSessionPinned: vi.fn(async (_id, pinned) => ({ state: {
    pinned,
    pinnedAt: pinned ? '2026-08-30T09:00:00Z' : ''
  } }))
}))

vi.mock('../services/agentApi.js', () => ({
  cancelAgentRun: vi.fn(),
  createAgentRun: (...args) => api.createAgentRun(...args),
  getAgentState: (...args) => api.getAgentState(...args),
  getAgentRun: (...args) => api.getAgentRun(...args),
  getPendingAction: vi.fn(),
  resolvePendingAction: (...args) => api.resolvePendingAction(...args),
  streamAgentRun: (...args) => api.streamAgentRun(...args)
}))

vi.mock('../services/consoleApi.js', () => ({
  deleteSessionRecord: vi.fn(),
  deleteSessionRecords: vi.fn(),
  getModelCatalog: (...args) => api.getModelCatalog(...args),
  getModelManagement: (...args) => api.getModelManagement(...args),
  getSessionRunHistory: (...args) => api.getSessionRunHistory(...args),
  getSessionPage: (...args) => api.getSessionPage(...args),
  updateSessionPinned: (...args) => api.updateSessionPinned(...args),
  updateSessionTitle: vi.fn()
}))

function mountConsole(ownerId = 'alice') {
  let consoleState
  const Harness = defineComponent({
    setup() {
      consoleState = useAgentConsole(ownerId)
      return () => h('div')
    }
  })
  const wrapper = mount(Harness)
  return { wrapper, get consoleState() { return consoleState } }
}

describe('agent console drafts', () => {
  it('removes unowned legacy data and reuses one transient draft from the account cache', async () => {
    localStorage.setItem('agentos.console.sessions.v1', JSON.stringify([
      { id: 'foreign-task', title: '旧共享任务', serverBacked: true, messages: [{ role: 'user', content: 'secret' }] }
    ]))
    localStorage.setItem('agentos.console.sessions.v2.alice', JSON.stringify([
      { id: 'draft-1', title: '未命名任务', state: null, messages: [] },
      { id: 'draft-2', title: '未命名任务', state: null, messages: [] },
      { id: 'task-1', title: '正式任务', serverBacked: true, state: null, messages: [] }
    ]))
    localStorage.setItem('agentos.console.active-session.v2.alice', 'draft-2')
    api.getSessionPage.mockResolvedValueOnce({
      items: [{ sessionId: 'task-1', state: { displayTitle: '正式任务' } }],
      total: 1,
      hasMore: false
    })
    const harness = mountConsole()
    await flushPromises()

    expect(harness.consoleState.sessions.value.map(session => session.id)).toEqual(['task-1'])

    const firstDraft = harness.consoleState.createSession()
    const secondDraft = harness.consoleState.createSession()

    expect(secondDraft.id).toBe(firstDraft.id)
    expect(harness.consoleState.sessions.value).toHaveLength(2)
    expect(harness.consoleState.currentSessionDraft.value).toBe(true)
    expect(localStorage.getItem('agentos.console.sessions.v1')).toBeNull()
    expect(JSON.parse(localStorage.getItem('agentos.console.sessions.v2.alice'))).toEqual([
      expect.objectContaining({ id: 'task-1' })
    ])
    expect(localStorage.getItem('agentos.console.active-session.v2.alice')).toBeNull()
    harness.wrapper.unmount()
  })

  it('loads enabled configured models without selecting a default', async () => {
    api.getModelCatalog.mockResolvedValueOnce([{
        id: 'model-deepseek', modelId: 'deepseek-v4-pro', modelType: 'BUILT_IN',
        providerName: 'DeepSeek', providerType: 'DEEPSEEK', enabled: true
      }])
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
    api.getModelCatalog.mockResolvedValueOnce([{
        id: 'model-deepseek', modelId: 'deepseek-v4-pro', modelType: 'BUILT_IN',
        providerName: 'DeepSeek', providerType: 'DEEPSEEK', enabled: true
      }])
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
    expect(JSON.parse(localStorage.getItem('agentos.console.sessions.v2.alice'))[0]).toEqual(
      expect.objectContaining({ id: 'session-pinned', pinned: false }))
    harness.wrapper.unmount()
  })

  it('keeps demo and admin transcript caches isolated', async () => {
    localStorage.setItem('agentos.console.sessions.v2.demo', JSON.stringify([
      { id: 'demo-task', title: 'demo', serverBacked: true, messages: [{ role: 'user', content: 'demo secret' }] }
    ]))
    localStorage.setItem('agentos.console.sessions.v2.admin', JSON.stringify([
      { id: 'admin-task', title: 'admin', serverBacked: true, messages: [{ role: 'user', content: 'admin secret' }] }
    ]))

    api.getSessionPage.mockResolvedValueOnce({
      items: [{ sessionId: 'demo-task', state: { displayTitle: 'demo' } }], total: 1, hasMore: false
    })
    const demo = mountConsole('demo')
    await flushPromises()
    expect(demo.consoleState.sessions.value.map(item => item.id)).toContain('demo-task')
    expect(demo.consoleState.sessions.value.map(item => item.id)).not.toContain('admin-task')
    demo.wrapper.unmount()

    api.getSessionPage.mockResolvedValueOnce({
      items: [{ sessionId: 'admin-task', state: { displayTitle: 'admin' } }], total: 1, hasMore: false
    })
    const admin = mountConsole('admin')
    await flushPromises()
    expect(admin.consoleState.sessions.value.map(item => item.id)).toContain('admin-task')
    expect(admin.consoleState.sessions.value.map(item => item.id)).not.toContain('demo-task')
    admin.wrapper.unmount()
  })

  it('stores the actual runtime identity rather than a proposed route or request identity', async () => {
    localStorage.setItem('agentos.console.sessions.v2.identity', JSON.stringify([{
      id: 'identity-task', title: 'running', serverBacked: true,
      activeRunId: 'identity-run', lastSeq: 0, messages: []
    }]))
    api.getSessionPage.mockResolvedValueOnce({ items: [
      { sessionId: 'identity-task', state: { displayTitle: 'running' } }
    ], total: 1, hasMore: false })
    api.getAgentRun.mockResolvedValueOnce({ runId: 'identity-run', lastSeq: 5, status: 'RUNNING', output: '' })
    api.streamAgentRun.mockImplementationOnce(async (_id, _after, onEvent) => {
      const send = (seq, event, data, itemId = 'msg-1') => onEvent({ data: {
        schemaVersion: '1', eventId: `evt-${seq}`, runId: 'identity-run', turnId: 'turn-1',
        sessionId: 'identity-task', itemId, agentId: 'react-agent', parentRunId: '',
        seq, timestamp: '2026-09-10T01:00:00Z', visibility: 'USER', event, data
      } })
      send(1, 'run.started', { objective: 'answer' }, 'identity-run')
      send(2, 'status', { text: '正在生成回答' }, 'identity-run')
      send(3, 'message.started', { role: 'assistant' })
      send(4, 'message.delta', { delta: 'partial', offset: 0, contentLength: 7 })
      send(5, 'message.completed', { role: 'assistant', content: 'answer' })
      return { runId: 'identity-run', lastSeq: 6, status: 'COMPLETED', output: 'answer', durationMs: 10, usage: { totalTokens: 1 } }
    })
    const harness = mountConsole('identity')
    await flushPromises()
    expect(harness.consoleState.executingAgentId.value).toBe('react-agent')
    expect(harness.consoleState.messages.value.find(message => message.role === 'assistant'))
      .toMatchObject({ agentId: 'react-agent', content: 'answer' })
    harness.wrapper.unmount()
  })

  it('aborts the previous account stream when its shell is destroyed', async () => {
    localStorage.setItem('agentos.console.sessions.v2.demo', JSON.stringify([{
      id: 'running-task', title: 'running', serverBacked: true,
      activeRunId: 'run-1', lastSeq: 0, messages: []
    }]))
    api.getSessionPage.mockResolvedValueOnce({
      items: [{ sessionId: 'running-task', state: { displayTitle: 'running' } }],
      total: 1,
      hasMore: false
    })
    api.getAgentRun.mockResolvedValueOnce({
      runId: 'run-1', lastSeq: 1, status: 'RUNNING', output: ''
    })
    let streamSignal
    api.streamAgentRun.mockImplementationOnce((_runId, _after, _onEvent, signal) => {
      streamSignal = signal
      return new Promise(() => {})
    })

    const harness = mountConsole('demo')
    await flushPromises()
    expect(streamSignal?.aborted).toBe(false)

    harness.wrapper.unmount()
    expect(streamSignal?.aborted).toBe(true)
  })

  it('continues an approved action through a background run instead of waiting on the resolution request', async () => {
    const harness = mountConsole()
    await flushPromises()
    const session = harness.consoleState.createSession()
    const approval = {
      id: 'approval-message-1', role: 'approval', content: '读取网页',
      invocationId: 'invocation-1', pendingActionId: 'pending-1', resolved: false
    }
    session.messages.push(approval)
    session.activeRunId = 'run-1'
    session.lastSeq = 2
    session.runTask = '读取网页并总结'
    api.resolvePendingAction.mockResolvedValueOnce({
      runId: 'run-1', invocationId: 'invocation-1', lastSeq: 3,
      status: 'RUNNING', output: '', error: null
    })
    api.getAgentRun.mockResolvedValueOnce({
      runId: 'run-1', invocationId: 'invocation-1', lastSeq: 2,
      status: 'COMPLETED', output: '网页总结完成', error: null
    })

    await harness.consoleState.resolveApproval(approval.id, true)

    expect(api.resolvePendingAction).toHaveBeenCalledWith(
      'invocation-1', 'pending-1', true)
    expect(api.getAgentRun).toHaveBeenCalledWith(
      'run-1', expect.any(AbortSignal))
    expect(approval).toEqual(expect.objectContaining({ resolved: true, approved: true }))
    expect(session.messages).toContainEqual(expect.objectContaining({
      role: 'assistant', content: '网页总结完成'
    }))
    expect(session.activeRunId).toBe('')
    harness.wrapper.unmount()
  })
})


describe('structured run history', () => {
  it('restores messages, tools and artifacts without exposing internal traces', async () => {
    api.getSessionPage.mockResolvedValueOnce({ items: [{ sessionId: 'trace-session', state: { displayTitle: 'Trace' } }], total: 1, hasMore: false })
    const base = { schemaVersion: '1', runId: 'run-1', turnId: 'turn-1',
      sessionId: 'trace-session', agentId: 'workspace-agent', parentRunId: '', visibility: 'USER' }
    api.getSessionRunHistory.mockResolvedValueOnce([
      { ...base, eventId: 'evt-1', itemId: 'run-1', seq: 1, event: 'run.started', timestamp: '2026-09-10T01:00:00Z', data: { objective: '调查测试' } },
      { ...base, eventId: 'evt-2', itemId: 'tool-1', seq: 2, event: 'tool.started', timestamp: '2026-09-10T01:00:01Z', data: { toolCallId: 'tool-1', toolName: 'file_read', arguments: { path: 'A.java' } } },
      { ...base, eventId: 'evt-3', itemId: 'tool-1', seq: 3, event: 'tool.completed', timestamp: '2026-09-10T01:00:02Z', data: { toolCallId: 'tool-1', toolName: 'file_read', summary: '读取完成' } },
      { ...base, eventId: 'evt-4', itemId: 'artifact-1', seq: 4, event: 'artifact.created', timestamp: '2026-09-10T01:00:03Z', data: { artifactId: 'artifact-1', filename: 'report.md', contentType: 'text/markdown', sizeBytes: 120 } },
      { ...base, eventId: 'evt-5', itemId: 'msg-1', seq: 5, event: 'message.completed', timestamp: '2026-09-10T01:00:04Z', data: { content: '已完成' } },
      { ...base, eventId: 'evt-6', itemId: 'run-1', seq: 6, event: 'run.completed', timestamp: '2026-09-10T01:00:05Z', data: { snapshot: { status: 'COMPLETED', durationMs: 5000, usage: { totalTokens: 12 } } } }
    ])
    const harness = mountConsole('trace-reader')
    await flushPromises()
    await harness.consoleState.selectSession('trace-session')
    await flushPromises()
    const messages = harness.consoleState.sessions.value.find(s => s.id === 'trace-session').messages
    expect(messages.some(message => message.role === 'trace')).toBe(false)
    expect(messages.find(message => message.role === 'assistant')).toMatchObject({ id: 'msg-1', content: '已完成' })
    expect(messages.find(message => message.role === 'artifact')).toMatchObject({ artifactId: 'artifact-1', filename: 'report.md' })
    expect(messages.find(message => message.role === 'ops').items[0]).toMatchObject({ toolCallId: 'tool-1', status: 'COMPLETED' })
    harness.wrapper.unmount()
  })
})
