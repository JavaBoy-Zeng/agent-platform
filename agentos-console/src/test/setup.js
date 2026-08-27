import { afterEach } from 'vitest'

afterEach(() => {
  localStorage.clear()
  document.documentElement.removeAttribute('data-theme')
  document.documentElement.style.colorScheme = ''
  delete window.__TAURI_INTERNALS__
})
