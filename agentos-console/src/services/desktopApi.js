let coreModule
let eventModule
let openerModule

/** Tauri 在 WebView 中注入该对象；浏览器构建不会暴露任何本机能力。 */
export const isDesktop = () => Boolean(window.__TAURI_INTERNALS__)

async function core() {
  if (!isDesktop()) throw new Error('本机工作区仅在 AgentOS Desktop 中可用')
  coreModule ||= import('@tauri-apps/api/core')
  return coreModule
}

async function events() {
  if (!isDesktop()) throw new Error('本机终端仅在 AgentOS Desktop 中可用')
  eventModule ||= import('@tauri-apps/api/event')
  return eventModule
}

export async function invokeDesktop(command, payload = {}) {
  const { invoke } = await core()
  return invoke(command, payload)
}

export async function listenDesktop(event, handler) {
  const { listen } = await events()
  return listen(event, message => handler(message.payload))
}

/** 只允许通过系统浏览器打开 HTTP(S) 地址，避免桌面 WebView 离开应用。 */
export async function openExternalUrl(value) {
  const url = new URL(String(value || ''))
  if (!['http:', 'https:'].includes(url.protocol)) throw new Error('仅支持打开 HTTP(S) 链接')
  if (isDesktop()) {
    openerModule ||= import('@tauri-apps/plugin-opener')
    const { openUrl } = await openerModule
    return openUrl(url.href)
  }
  window.open(url.href, '_blank', 'noopener,noreferrer')
}
