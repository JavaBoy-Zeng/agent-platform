import { AgentApiError } from './agentApi.js'

async function request(path, options) {
  const response = await fetch(path, options)
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
export const getUsage = (sessionId) => request(`/api/usage/${encodeURIComponent(sessionId)}`)
export const getTraces = (sessionId) => request(`/api/traces?sessionId=${encodeURIComponent(sessionId)}`)
export const getArtifacts = (sessionId) => request(`/api/artifacts?sessionId=${encodeURIComponent(sessionId)}`)
export const deleteArtifact = (artifactId) => request(`/api/artifacts/${encodeURIComponent(artifactId)}`, { method: 'DELETE' })
export const getAgentRuns = () => request('/api/agent-runs')

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

export function downloadArtifact(artifactId) {
  const link = document.createElement('a')
  link.href = `/api/artifacts/${encodeURIComponent(artifactId)}`
  link.click()
}
