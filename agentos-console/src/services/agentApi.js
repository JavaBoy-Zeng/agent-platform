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

function parseEventBlock(block) {
  let event = 'message'
  const dataLines = []
  for (const line of block.split('\n')) {
    if (line.startsWith('event:')) event = line.slice(6).trim()
    if (line.startsWith('data:')) dataLines.push(line.slice(5).trimStart())
  }
  if (!dataLines.length) return null
  const text = dataLines.join('\n')
  try {
    return { event, data: JSON.parse(text) }
  } catch {
    return { event, data: text }
  }
}

/**
 * 使用 POST + SSE 流式执行 Agent。浏览器原生 EventSource 仅支持 GET，因此这里直接解析
 * fetch 的 ReadableStream，同时保留结构化 POST 请求体。
 */
export async function runAgentStream(payload, onEvent = () => {}) {
  const response = await fetch('/api/agents/runs/stream', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream'
    },
    body: JSON.stringify(payload)
  })

  if (!response.ok) {
    const body = await readBody(response)
    throw new AgentApiError(body?.detail || 'Agent 流式运行请求失败', response.status)
  }
  if (!response.body) {
    throw new AgentApiError('浏览器未提供可读取的 SSE 响应体', response.status)
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let finalResponse = null

  while (true) {
    const { value, done } = await reader.read()
    buffer += decoder.decode(value || new Uint8Array(), { stream: !done })
      .replace(/\r\n/g, '\n')
    let boundary = buffer.indexOf('\n\n')
    while (boundary >= 0) {
      const parsed = parseEventBlock(buffer.slice(0, boundary))
      buffer = buffer.slice(boundary + 2)
      if (parsed) {
        onEvent(parsed)
        if (parsed.event === 'state') finalResponse = parsed.data
      }
      boundary = buffer.indexOf('\n\n')
    }
    if (done) break
  }

  const trailing = parseEventBlock(buffer.trim())
  if (trailing) {
    onEvent(trailing)
    if (trailing.event === 'state') finalResponse = trailing.data
  }
  if (!finalResponse) {
    throw new AgentApiError('SSE 连接结束前未收到 Agent 最终状态', response.status)
  }
  return finalResponse
}

export async function getAgentState(sessionId) {
  const response = await fetch(`/api/agents/${encodeURIComponent(sessionId)}/state`)
  const body = await readBody(response)

  if (!response.ok && response.status !== 404) {
    throw new AgentApiError(body?.detail || '无法读取 Agent 状态', response.status)
  }
  return response.status === 404 ? null : body
}

export async function getPendingAction(sessionId) {
  const response = await fetch(`/api/agents/${encodeURIComponent(sessionId)}/pending-action`)
  if (response.status === 204) return null
  const body = await readBody(response)
  if (!response.ok) {
    throw new AgentApiError(body?.detail || '无法读取待审批操作', response.status)
  }
  return body
}

export async function resolvePendingAction(invocationId, pendingActionId, approved) {
  const response = await fetch(
    `/api/agents/invocations/${encodeURIComponent(invocationId)}/resolution`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ pendingActionId, approved, data: {} })
    }
  )
  const body = await readBody(response)
  if (!response.ok) {
    throw new AgentApiError(body?.detail || '处理审批操作失败', response.status)
  }
  return body
}
