import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h, ref } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import { setAuthToken, setAuthUser } from '../services/apiConfig.js'
import { useDesktopWorkspace } from './useDesktopWorkspace.js'

const desktop = vi.hoisted(() => ({ invoke: vi.fn() }))
vi.mock('../services/desktopApi.js', () => ({
  isDesktop: () => true,
  invokeDesktop: (...args) => desktop.invoke(...args)
}))

describe('desktop workspace state', () => {
  it('authorizes ADMIN users and stores task associations locally', async () => {
    desktop.invoke.mockImplementation(async command => {
      if (command === 'authorize_workspace') {
        return { grantId: 'grant-1', user: 'alice', expiresAt: Date.now() + 60_000 }
      }
      if (command === 'list_workspaces') {
        return [{ id: 'workspace-1', name: 'repo', root: '/local/repo', gitRepository: true }]
      }
      if (command === 'upload_attachments') {
        return [{ name: 'brief.pdf', relativePath: 'brief.pdf', size: 42 }]
      }
      if (command === 'workspace_context') {
        return {
          name: 'repo',
          tree: ['ReadMe.md', 'pom.xml', 'src/'],
          files: [{ path: 'ReadMe.md', content: '# Repo', truncated: false }],
          truncated: false
        }
      }
      if (command === 'workspace_file_index') {
        return [{ name: 'ReadMe.md', relativePath: 'ReadMe.md', language: 'markdown' }]
      }
      return null
    })
    setAuthToken('test-token')
    setAuthUser({ username: 'alice', roles: ['USER', 'ADMIN'] })
    const agentConsole = { currentSessionId: ref('task-1') }
    let workspace
    const Harness = defineComponent({
      setup() {
        workspace = useDesktopWorkspace(agentConsole)
        return () => h('div')
      }
    })
    const wrapper = mount(Harness)
    await flushPromises()
    expect(workspace.available.value).toBe(true)
    expect(desktop.invoke).toHaveBeenCalledWith('authorize_workspace', expect.objectContaining({ token: 'test-token' }))
    workspace.bindWorkspace('workspace-1')
    expect(workspace.currentWorkspace.value?.id).toBe('workspace-1')
    expect(JSON.parse(localStorage.getItem('agentos.session-workspaces.v1'))).toEqual({ 'task-1': 'workspace-1' })
    workspace.clearWorkspace()
    expect(workspace.currentWorkspace.value).toBeNull()
    expect(JSON.parse(localStorage.getItem('agentos.session-workspaces.v1'))).toEqual({})
    workspace.bindWorkspace('workspace-1')
    await expect(workspace.uploadAttachments()).resolves.toEqual([
      { name: 'brief.pdf', relativePath: 'brief.pdf', size: 42 }
    ])
    expect(desktop.invoke).toHaveBeenCalledWith('upload_attachments', expect.objectContaining({
      grantId: 'grant-1', workspaceId: 'workspace-1'
    }))
    await expect(workspace.buildRunContext(['src/Main.java'])).resolves.toEqual(expect.objectContaining({ name: 'repo' }))
    expect(desktop.invoke).toHaveBeenCalledWith('workspace_context', expect.objectContaining({
      grantId: 'grant-1', workspaceId: 'workspace-1', mentionedPaths: ['src/Main.java']
    }))
    expect(workspace.contextLoading.value).toBe(false)
    wrapper.unmount()
    await flushPromises()
  })

  it('opens a native folder picker and binds the selected workspace to the task', async () => {
    desktop.invoke.mockImplementation(async command => {
      if (command === 'authorize_workspace') {
        return { grantId: 'grant-2', user: 'alice', expiresAt: Date.now() + 60_000 }
      }
      if (command === 'list_workspaces') {
        return [{ id: 'workspace-2', name: 'agent-platform', root: '/local/agent-platform', gitRepository: true }]
      }
      if (command === 'pick_workspace') {
        return { id: 'workspace-2', name: 'agent-platform', root: '/local/agent-platform', gitRepository: true }
      }
      return null
    })
    setAuthToken('test-token')
    setAuthUser({ username: 'alice', roles: ['USER', 'WORKSPACE'] })
    const agentConsole = { currentSessionId: ref('task-2') }
    let workspace
    const Harness = defineComponent({
      setup() {
        workspace = useDesktopWorkspace(agentConsole)
        return () => h('div')
      }
    })
    const wrapper = mount(Harness)
    await flushPromises()

    await workspace.pickWorkspace()

    expect(desktop.invoke).toHaveBeenCalledWith('pick_workspace', { grantId: 'grant-2' })
    expect(workspace.currentWorkspace.value?.root).toBe('/local/agent-platform')
    expect(workspace.picking.value).toBe(false)
    wrapper.unmount()
    await flushPromises()
  })

  it('can pick a folder for a future task without changing the active task', async () => {
    desktop.invoke.mockImplementation(async command => {
      if (command === 'authorize_workspace') {
        return { grantId: 'grant-3', user: 'alice', expiresAt: Date.now() + 60_000 }
      }
      if (command === 'list_workspaces') {
        return [{ id: 'workspace-3', name: 'future-task', root: '/local/future-task', gitRepository: false }]
      }
      if (command === 'pick_workspace') {
        return { id: 'workspace-3', name: 'future-task', root: '/local/future-task', gitRepository: false }
      }
      return null
    })
    setAuthToken('test-token')
    setAuthUser({ username: 'alice', roles: ['USER', 'WORKSPACE'] })
    const agentConsole = { currentSessionId: ref('existing-task') }
    let workspace
    const Harness = defineComponent({
      setup() {
        workspace = useDesktopWorkspace(agentConsole)
        return () => h('div')
      }
    })
    const wrapper = mount(Harness)
    await flushPromises()

    const picked = await workspace.pickWorkspace({ bind: false })

    expect(picked?.id).toBe('workspace-3')
    expect(workspace.currentWorkspace.value).toBeNull()
    expect(localStorage.getItem('agentos.session-workspaces.v1')).toBeNull()
    wrapper.unmount()
    await flushPromises()
  })
})
