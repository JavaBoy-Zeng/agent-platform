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
export const getSession = (sessionId) => request(`/api/sessions/${encodeURIComponent(sessionId)}`)
export const getUsage = (sessionId) => request(`/api/usage/${encodeURIComponent(sessionId)}`)
export const getTraces = (sessionId) => request(`/api/traces?sessionId=${encodeURIComponent(sessionId)}`)
export const getArtifacts = (sessionId) => request(`/api/artifacts?sessionId=${encodeURIComponent(sessionId)}`)
export const deleteArtifact = (artifactId) => request(`/api/artifacts/${encodeURIComponent(artifactId)}`, { method: 'DELETE' })
export const getMemory = (sessionId, agentId = 'main-agent') => {
  const query = new URLSearchParams({ sessionId, agentId, recentLimit: '20' })
  return request(`/api/memories?${query}`)
}

export function downloadArtifact(artifactId) {
  const link = document.createElement('a')
  link.href = `/api/artifacts/${encodeURIComponent(artifactId)}`
  link.click()
}
