import { describe, expect, it } from 'vitest'
import { invokeDesktop, isDesktop, listenDesktop } from './desktopApi.js'

describe('desktop bridge web fallback', () => {
  it('does not expose native capabilities in a browser', async () => {
    expect(isDesktop()).toBe(false)
    await expect(invokeDesktop('list_workspaces')).rejects.toThrow('仅在 AgentOS Desktop')
    await expect(listenDesktop('terminal-output', () => {})).rejects.toThrow('仅在 AgentOS Desktop')
  })

  it('detects the Tauri runtime marker', () => {
    Object.defineProperty(navigator, 'platform', { configurable: true, value: 'MacIntel' })
    window.__TAURI_INTERNALS__ = {}
    expect(isDesktop()).toBe(true)
  })
})
