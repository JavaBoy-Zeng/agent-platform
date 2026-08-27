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
  it('authorizes WORKSPACE users and stores task associations locally', async () => {
    desktop.invoke.mockImplementation(async command => {
      if (command === 'authorize_workspace') {
        return { grantId: 'grant-1', user: 'alice', expiresAt: Date.now() + 60_000 }
      }
      if (command === 'list_workspaces') {
        return [{ id: 'workspace-1', name: 'repo', root: '/local/repo', gitRepository: true }]
      }
      return null
    })
    setAuthToken('test-token')
    setAuthUser({ username: 'alice', roles: ['USER', 'WORKSPACE'] })
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
    wrapper.unmount()
    await flushPromises()
  })
})
