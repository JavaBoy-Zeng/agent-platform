export class AgentApiError extends Error {
  constructor(message, status) {
    super(message)
    this.name = 'AgentApiError'
    this.status = status
  }
}

async function readBody(response) {
  const text = await response.text()
  if (!text) return null

  try {
    return JSON.parse(text)
  } catch {
    return { detail: text }
  }
}

export async function runAgent(payload) {
  const response = await fetch('/api/agents/runs', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  })
  const body = await readBody(response)

  if (!response.ok) {
    throw new AgentApiError(body?.detail || 'Agent 运行请求失败', response.status)
  }
  return body
}

export async function getAgentState(sessionId) {
  const response = await fetch(`/api/agents/${encodeURIComponent(sessionId)}/state`)
  const body = await readBody(response)

  if (!response.ok && response.status !== 404) {
    throw new AgentApiError(body?.detail || '无法读取 Agent 状态', response.status)
  }
  return response.status === 404 ? null : body
}
