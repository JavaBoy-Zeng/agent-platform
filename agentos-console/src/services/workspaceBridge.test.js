import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { createWorkspaceBridge } from './workspaceBridge.js'

const mocks = vi.hoisted(() => ({ native: vi.fn(), fetch: vi.fn() }))
vi.mock('./desktopApi.js', () => ({ invokeDesktop: (...args) => mocks.native(...args) }))
vi.mock('./apiConfig.js', () => ({
  apiFetch: (...args) => mocks.fetch(...args), apiUrl: path => `https://agent.test${path}`,
  getServerUrl: () => 'https://agent.test', authHeaders: () => ({ Authorization: 'Bearer user-token' })
}))
const runtime = { workspaceId: 'w1', root: '/real/project', branch: 'main', device: 'My Mac', osName: 'macos' }
const response = value => ({ ok: true, text: async () => value === undefined ? '' : JSON.stringify(value) })
let bridge
let polls
beforeEach(() => {
  vi.useFakeTimers()
  polls = []
  mocks.native.mockReset().mockImplementation(async command => command === 'workspace_execution_info' ? runtime :
    command === 'execute_workspace_operation' ? { success: true, data: { cwd: runtime.root, stdout: runtime.root } } : null)
  mocks.fetch.mockReset().mockImplementation(async url => {
    if (url.endsWith('/connect')) return response({ token: 'mailbox-token', runtime })
    if (url.endsWith('/poll')) return response(polls.shift() || { running: false, pendingIds: [] })
    return response()
  })
  bridge = createWorkspaceBridge({ ensureGrant: async () => 'grant' })
})
afterEach(async () => { await bridge.dispose(); vi.useRealTimers() })

describe('desktop workspace execution bridge', () => {
  it('binds a real native directory before any execution and routes commands to the captured task', async () => {
    await bridge.connect('task-1', 'w1')
    expect(mocks.fetch).toHaveBeenCalledWith(expect.stringContaining('/task-1/connect'),
      expect.objectContaining({ body: JSON.stringify(runtime) }))
    polls.push({ running: true, pendingIds: ['op-1'], operation: {
      id: 'op-1', toolName: 'run_command', arguments: { command: 'pwd' }, deadline: Date.now() + 10000
    } })
    await vi.advanceTimersByTimeAsync(1000)
    await flushPromises()
    expect(mocks.native).toHaveBeenCalledWith('execute_workspace_operation', expect.objectContaining({
      workspaceId: 'w1', grantId: 'grant', expectedBranch: 'main', toolName: 'run_command', arguments: { command: 'pwd' }
    }))
    expect(mocks.fetch).toHaveBeenCalledWith(expect.stringContaining('/task-1/results/op-1'),
      expect.objectContaining({ headers: expect.objectContaining({ 'X-Workspace-Token': 'mailbox-token' }) }))
  })
  it('does not fall back when native preflight fails', async () => {
    mocks.native.mockRejectedValueOnce(new Error('directory disappeared'))
    await expect(bridge.connect('task-1', 'w1')).rejects.toThrow('directory disappeared')
    expect(mocks.fetch).not.toHaveBeenCalled()
  })
  it('keeps heartbeats alive during a command and cancels when server no longer expects it', async () => {
    let finish
    const pending = new Promise(resolve => { finish = resolve })
    mocks.native.mockImplementation(async command => command === 'workspace_execution_info' ? runtime :
      command === 'execute_workspace_operation' ? pending : null)
    await bridge.connect('task-1', 'w1')
    polls.push({ running: true, pendingIds: ['op-2'], operation: {
      id: 'op-2', toolName: 'run_command', arguments: { command: 'mvn test' }, deadline: Date.now() + 120000
    } })
    await vi.advanceTimersByTimeAsync(1000)
    polls.push({ running: false, pendingIds: [] })
    await vi.advanceTimersByTimeAsync(1000)
    expect(mocks.native).toHaveBeenCalledWith('cancel_workspace_operation', { operationId: 'op-2' })
    finish({ success: false, error: 'cancelled' })
    await flushPromises()
  })
  it('retries result delivery without rerunning a write', async () => {
    await bridge.connect('task-1', 'w1')
    let resultAttempts = 0
    mocks.fetch.mockImplementation(async url => {
      if (url.endsWith('/results/write-1') && resultAttempts++ === 0) throw new Error('network interrupted')
      if (url.endsWith('/poll')) return response(polls.shift() || { running: true, pendingIds: ['write-1'] })
      return response()
    })
    polls.push({ running: true, pendingIds: ['write-1'], operation: {
      id: 'write-1', toolName: 'file_write', arguments: { path: 'pom.xml', content: 'source' }, deadline: Date.now() + 10000
    } })
    await vi.advanceTimersByTimeAsync(2000)
    await flushPromises()
    expect(mocks.native.mock.calls.filter(([name]) => name === 'execute_workspace_operation')).toHaveLength(1)
    expect(resultAttempts).toBe(2)
  })
})

it('explains that a missing connect endpoint requires a server update', async () => {
  mocks.fetch.mockResolvedValue({ ok: false, status: 404, json: async () => ({ error: 'Not Found' }) })
  await expect(bridge.connect('task-1', 'w1')).rejects.toThrow('请更新并重启服务端')
  expect(mocks.native.mock.calls.some(([name]) => name === 'execute_workspace_operation')).toBe(false)
})
