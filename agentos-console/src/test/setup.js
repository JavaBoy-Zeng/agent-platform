import { afterEach } from 'vitest'

// Node 26 自带实验性 localStorage 存根（未启用 --localstorage-file 时为 undefined），
// 导致 vitest 不会把 jsdom 的 localStorage 复制到测试全局；这里手动桥接。
if (typeof globalThis.localStorage === 'undefined' && globalThis.jsdom?.window) {
  Object.defineProperty(globalThis, 'localStorage', {
    get: () => globalThis.jsdom.window.localStorage,
    configurable: true
  })
}

afterEach(() => {
  localStorage.clear()
  document.documentElement.removeAttribute('data-theme')
  document.documentElement.style.colorScheme = ''
  delete window.__TAURI_INTERNALS__
})
