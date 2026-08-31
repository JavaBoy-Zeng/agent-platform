import { apiFetch, apiUrl, authHeaders } from './apiConfig.js'
import { AgentApiError } from './agentApi.js'

async function request(path, options = {}) {
  const response = await apiFetch(apiUrl(path), {
    ...options,
    headers: { ...authHeaders(), ...(options.headers || {}) }
  })
  if (response.status === 204) return null
  const body = await response.json().catch(() => null)
  if (!response.ok) {
    throw new AgentApiError(body?.detail || `自动化请求失败 (${response.status})`, response.status)
  }
  return body
}

const json = (method, body) => ({
  method,
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(body)
})

export const getAutomations = () => request('/api/automations')
export const getAutomation = id => request(`/api/automations/${encodeURIComponent(id)}`)
export const createAutomation = body => request('/api/automations', json('POST', body))
export const updateAutomation = (id, body) =>
  request(`/api/automations/${encodeURIComponent(id)}`, json('PUT', body))
export const deleteAutomation = id =>
  request(`/api/automations/${encodeURIComponent(id)}`, { method: 'DELETE' })
export const setAutomationEnabled = (id, enabled) =>
  request(`/api/automations/${encodeURIComponent(id)}/enabled`, json('PATCH', { enabled }))
export const runAutomation = id =>
  request(`/api/automations/${encodeURIComponent(id)}/run`, { method: 'POST' })
export const previewAutomationSchedule = trigger =>
  request('/api/automations/schedule-preview', json('POST', trigger))

export const getAutomationExecutions = ({ automationId = '', status = '', offset = 0, limit = 20 } = {}) => {
  const query = new URLSearchParams({ automationId, status, offset: String(offset), limit: String(limit) })
  return request(`/api/automation-executions?${query}`)
}

export const heartbeatAutomationDesktop = body =>
  request('/api/automation-desktop/heartbeat', json('POST', body))
export const claimAutomationExecution = clientId =>
  request('/api/automation-desktop/claim', json('POST', { clientId }))
export const startAutomationExecution = (executionId, body) =>
  request(`/api/automation-desktop/executions/${encodeURIComponent(executionId)}/start`, json('POST', body))
export const failAutomationExecution = (executionId, body) =>
  request(`/api/automation-desktop/executions/${encodeURIComponent(executionId)}/fail`, json('POST', body))
