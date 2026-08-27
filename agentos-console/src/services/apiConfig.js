const SERVER_URL_KEY = 'agentos.server-url'
const AUTH_TOKEN_KEY = 'agentos.auth-token'
const AUTH_USER_KEY = 'agentos.auth-user'

function normalizeBaseUrl(url) {
  return String(url || '').trim().replace(/\/+$/, '')
}

/** 返回当前 server 地址；空串表示与页面同源。 */
export function getServerUrl() {
  try {
    const stored = localStorage.getItem(SERVER_URL_KEY)
    if (stored !== null && stored.trim()) return normalizeBaseUrl(stored)
  } catch {
    // localStorage 不可用时退回构建期配置
  }
  return normalizeBaseUrl(import.meta.env?.VITE_API_BASE_URL || '')
}

/** 保存 server 地址；传空则清除自定义值回到同源模式。 */
export function setServerUrl(url) {
  const normalized = normalizeBaseUrl(url)
  try {
    if (normalized) localStorage.setItem(SERVER_URL_KEY, normalized)
    else localStorage.removeItem(SERVER_URL_KEY)
  } catch {
    // 忽略持久化失败，本次会话仍生效于后续读取
  }
  return normalized
}

export function getAuthToken() {
  try {
    return String(localStorage.getItem(AUTH_TOKEN_KEY) || '').trim()
  } catch {
    return ''
  }
}

export function setAuthToken(token) {
  try {
    if (token && String(token).trim()) localStorage.setItem(AUTH_TOKEN_KEY, String(token).trim())
    else localStorage.removeItem(AUTH_TOKEN_KEY)
  } catch {
    // 同上
  }
}

/** 登录后缓存的用户信息（username/roles），仅用于界面展示。 */
export function getAuthUser() {
  try {
    const raw = localStorage.getItem(AUTH_USER_KEY)
    return raw ? JSON.parse(raw) : null
  } catch {
    return null
  }
}

export function setAuthUser(user) {
  try {
    if (user) localStorage.setItem(AUTH_USER_KEY, JSON.stringify(user))
    else localStorage.removeItem(AUTH_USER_KEY)
  } catch {
    // 同上
  }
  window.dispatchEvent(new CustomEvent('agentos:auth-changed', { detail: user }))
}

/** 退出登录：清除令牌与缓存的用户信息。 */
export function clearAuth() {
  window.dispatchEvent(new CustomEvent('agentos:logout'))
  setAuthToken('')
  setAuthUser(null)
}

/** 拼接 API 路径：相对路径走同源（dev 由 Vite proxy 转发），绝对路径直连配置的 server。 */
export function apiUrl(path) {
  return `${getServerUrl()}${path}`
}

export function authHeaders() {
  const token = getAuthToken()
  return token ? { Authorization: `Bearer ${token}` } : {}
}

/** 401 时广播全局事件，由路由层统一跳转登录页。 */
export function notifyUnauthorized() {
  window.dispatchEvent(new CustomEvent('agentos:unauthorized'))
}

/** fetch 包装：集中处理 401（通知路由层登出）。 */
export async function apiFetch(url, options) {
  const response = await fetch(url, options)
  if (response.status === 401) notifyUnauthorized()
  return response
}
