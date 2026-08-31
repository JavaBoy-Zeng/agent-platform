import { flushPromises, mount } from '@vue/test-utils'
import { ref } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import TerminalPanel from './TerminalPanel.vue'

const terminalHarness = vi.hoisted(() => ({ instances: [] }))
const listenDesktop = vi.hoisted(() => vi.fn(async () => vi.fn()))

vi.mock('../services/desktopApi.js', () => ({ listenDesktop }))
vi.mock('@xterm/xterm', () => ({
  Terminal: class {
    constructor(options) {
      this.options = options
      this.cols = 90
      this.rows = 22
      this.write = vi.fn()
      this.writeln = vi.fn()
      this.dispose = vi.fn()
      this.focus = vi.fn()
      terminalHarness.instances.push(this)
    }
    loadAddon(addon) { this.addon = addon }
    open(element) { this.element = element }
    onData(handler) { this.dataHandler = handler }
  }
}))
vi.mock('@xterm/addon-fit', () => ({
  FitAddon: class { fit = vi.fn() }
}))

let wrapper

beforeEach(() => {
  terminalHarness.instances.length = 0
  listenDesktop.mockClear()
  vi.stubGlobal('ResizeObserver', class {
    observe() {}
    disconnect() {}
  })
})

afterEach(() => {
  wrapper?.unmount()
  vi.unstubAllGlobals()
})

function mountTerminal(call) {
  wrapper = mount(TerminalPanel, {
    global: {
      provide: {
        desktopWorkspace: { terminalOpen: ref(true), call }
      }
    }
  })
}

describe('TerminalPanel', () => {
  it('creates a native PTY and requests a fresh prompt after the canvas mounts', async () => {
    const call = vi.fn(async command => command === 'terminal_create'
      ? { id: 'terminal-1', workspaceId: 'workspace-1', shell: '/bin/zsh', cwd: '/workspace' }
      : undefined)
    mountTerminal(call)
    await flushPromises()

    expect(call).toHaveBeenCalledWith('terminal_create', { cols: 90, rows: 22 })
    expect(call).toHaveBeenCalledWith('terminal_write', { sessionId: 'terminal-1', data: '\r' })
    expect(terminalHarness.instances[0].element).toBeTruthy()
    expect(wrapper.find('.terminal-state').exists()).toBe(false)
  })

  it('shows a retryable error instead of failing silently', async () => {
    const call = vi.fn(async command => {
      if (command === 'terminal_create') throw new Error('无法启动 Shell')
    })
    mountTerminal(call)
    await flushPromises()

    expect(wrapper.get('.terminal-state.error').text()).toContain('无法启动 Shell')
    expect(wrapper.get('.terminal-state button').text()).toMatch(/重试|Retry/)
  })
})
