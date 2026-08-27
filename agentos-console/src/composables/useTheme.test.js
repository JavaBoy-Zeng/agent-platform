import { describe, expect, it } from 'vitest'
import { useTheme } from './useTheme.js'

describe('theme preference', () => {
  it('supports system, light and dark modes', () => {
    const theme = useTheme()
    theme.setTheme('dark')
    expect(theme.theme.value).toBe('dark')
    expect(document.documentElement.dataset.theme).toBe('dark')
    expect(localStorage.getItem('agentos.theme.v1')).toBe('dark')
    theme.setTheme('light')
    expect(document.documentElement.dataset.theme).toBe('light')
    theme.setTheme('system')
    expect(document.documentElement.dataset.theme).toBe('system')
    expect(document.documentElement.style.colorScheme).toBe('light dark')
  })

  it('ignores unsupported values', () => {
    const theme = useTheme()
    theme.setTheme('light')
    theme.setTheme('sepia')
    expect(theme.theme.value).toBe('light')
  })
})
