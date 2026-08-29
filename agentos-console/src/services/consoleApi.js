import { AgentApiError } from './agentApi.js'
import { apiFetch, apiUrl, authHeaders } from './apiConfig.js'

async function request(path, options = {}) {
  const response = await apiFetch(apiUrl(path), {
    ...options,
    headers: { ...authHeaders(), ...(options.headers || {}) }
  })
  if (response.status === 204) return null
  const contentType = response.headers.get('content-type') || ''
  const body = contentType.includes('application/json')
    ? await response.json()
    : await response.text()
  if (!response.ok) {
    const detail = typeof body === 'string' ? body : body?.detail
    throw new AgentApiError(detail || `请求失败 (${response.status})`, response.status)
  }
  return body
}

export const getConsoleCatalog = () => request('/api/console/catalog')
export const getAgentDetail = (agentId) =>
  request(`/api/console/agents/${encodeURIComponent(agentId)}`)
export const getSession = (sessionId) => request(`/api/sessions/${encodeURIComponent(sessionId)}`)
export const getSessions = (limit = 50) =>
  request(`/api/sessions?limit=${encodeURIComponent(limit)}`)
export const getSessionPage = (offset = 0, limit = 20) => {
  const query = new URLSearchParams({ offset: String(offset), limit: String(limit) })
  return request(`/api/sessions/page?${query}`)
}
export const updateSessionTitle = (sessionId, title) =>
  request(`/api/sessions/${encodeURIComponent(sessionId)}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ title })
  })
export const deleteSessionRecord = (sessionId) =>
  request(`/api/sessions/${encodeURIComponent(sessionId)}`, { method: 'DELETE' })
export const getUsage = (sessionId) => request(`/api/usage/${encodeURIComponent(sessionId)}`)
export const getTraces = (sessionId) => request(`/api/traces?sessionId=${encodeURIComponent(sessionId)}`)
export const getArtifacts = (sessionId) => request(`/api/artifacts?sessionId=${encodeURIComponent(sessionId)}`)
export const deleteArtifact = (artifactId) => request(`/api/artifacts/${encodeURIComponent(artifactId)}`, { method: 'DELETE' })
export const getAgentRuns = () => request('/api/agent-runs')

export const getModelManagement = () => request('/api/model-management')
export const createModelProvider = (provider) =>
  request('/api/model-management/providers', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(provider)
  })
export const updateModelProvider = (providerId, provider) =>
  request(`/api/model-management/providers/${encodeURIComponent(providerId)}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(provider)
  })
export const deleteModelProvider = (providerId) =>
  request(`/api/model-management/providers/${encodeURIComponent(providerId)}`, { method: 'DELETE' })
export const testModelProvider = (providerId) =>
  request(`/api/model-management/providers/${encodeURIComponent(providerId)}/test`, { method: 'POST' })
export const assignModelRoute = (routeKey, route) =>
  request(`/api/model-management/routes/${encodeURIComponent(routeKey)}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(route)
  })

/** 按会话读取领域事件轨迹，按 Invocation 分组。 */
export const getSessionEvents = (sessionId, type = '') => {
  const query = new URLSearchParams({ sessionId })
  if (type) query.set('type', type)
  return request(`/api/events?${query}`)
}

/** 提交一次工具轨迹评估。 */
export const evaluateInvocation = (invocationId, evalCase) =>
  request(`/api/evaluations/${encodeURIComponent(invocationId)}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(evalCase)
  })

export const getMemory = (sessionId, agentId = 'main-agent') => {
  const query = new URLSearchParams({ sessionId, agentId, recentLimit: '20' })
  return request(`/api/memories?${query}`)
}

export async function downloadArtifact(artifactId) {
  const url = apiUrl(`/api/artifacts/${encodeURIComponent(artifactId)}`)
  const headers = authHeaders()
  // 无鉴权时用原生下载（链接导航不受 CORS 限制）；带 Key 时必须走 fetch 才能带上请求头
  if (!Object.keys(headers).length) {
    const link = document.createElement('a')
    link.href = url
    link.click()
    return
  }
  const response = await fetch(url, { headers })
  if (!response.ok) {
    throw new AgentApiError(`下载产物失败 (${response.status})`, response.status)
  }
  const blob = await response.blob()
  const disposition = response.headers.get('content-disposition') || ''
  const filenameMatch = disposition.match(/filename\*?=(?:UTF-8''|")?([^";]+)/i)
  const blobUrl = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = blobUrl
  link.download = filenameMatch
    ? decodeURIComponent(filenameMatch[1].replace(/"$/, ''))
    : `artifact-${artifactId}`
  link.click()
  URL.revokeObjectURL(blobUrl)
}
