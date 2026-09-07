import { AgentApiError } from './agentApi.js'
import { apiUrl, authHeaders } from './apiConfig.js'

async function readBody(response) {
  const text = await response.text()
  if (!text) return null
  try {
    return JSON.parse(text)
  } catch {
    return { detail: text }
  }
}

function errorDetail(body, fallback, status) {
  const message = typeof body === 'string'
    ? body
    : body?.detail || body?.message || body?.error
  return new AgentApiError(message || fallback, status)
}

async function request(path, options = {}) {
  const response = await fetch(apiUrl(path), {
    ...options,
    headers: { ...authHeaders(), ...(options.headers || {}) }
  })
  const body = await readBody(response)
  if (!response.ok) throw errorDetail(body, '请求失败', response.status)
  return body
}

/** 读取非管理员模型调用限频策略。 */
export const getNonAdminCallLimit = () =>
  request('/api/admin/settings/non-admin-call-limit')

/**
 * 更新非管理员模型调用限频策略。
 *
 * @param {{enabled: boolean, maxCalls?: number, windowSeconds?: number}} body
 *        enabled=false 时关闭窗口并清零次数；windowSeconds 缺省时回退到 1800（30 分钟）。
 */
export const updateNonAdminCallLimit = (body) =>
  request('/api/admin/settings/non-admin-call-limit', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  })