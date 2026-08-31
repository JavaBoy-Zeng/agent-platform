let coreModule
let eventModule

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
