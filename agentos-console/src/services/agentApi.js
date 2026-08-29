import { apiFetch, apiUrl, authHeaders } from './apiConfig.js'

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
  const response = await apiFetch(apiUrl('/api/agents/runs'), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...authHeaders() },
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
  let id = ''
  const dataLines = []
  for (const line of block.split('\n')) {
    if (line.startsWith('event:')) event = line.slice(6).trim()
    if (line.startsWith('id:')) id = line.slice(3).trim()
    if (line.startsWith('data:')) dataLines.push(line.slice(5).trimStart())
  }
  if (!dataLines.length) return null
  const text = dataLines.join('\n')
  try {
    return { event, id, data: JSON.parse(text) }
  } catch {
    return { event, id, data: text }
  }
}

async function consumeEventStream(response, onEvent, finalValue) {
  if (!response.body) {
    throw new AgentApiError('浏览器未提供可读取的 SSE 响应体', response.status)
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let finalResponse = null

  const consume = (parsed) => {
    if (!parsed) return
    onEvent(parsed)
    const value = finalValue(parsed)
    if (value) finalResponse = value
  }

  while (true) {
    const { value, done } = await reader.read()
    buffer += decoder.decode(value || new Uint8Array(), { stream: !done })
      .replace(/\r\n/g, '\n')
    let boundary = buffer.indexOf('\n\n')
    while (boundary >= 0) {
      consume(parseEventBlock(buffer.slice(0, boundary)))
      buffer = buffer.slice(boundary + 2)
      boundary = buffer.indexOf('\n\n')
    }
    if (done) break
  }

  consume(parseEventBlock(buffer.trim()))
  return finalResponse
}

/**
 * 使用 POST + SSE 流式执行 Agent。浏览器原生 EventSource 仅支持 GET，因此这里直接解析
 * fetch 的 ReadableStream，同时保留结构化 POST 请求体。
 */
export async function runAgentStream(payload, onEvent = () => {}) {
  const response = await apiFetch(apiUrl('/api/agents/runs/stream'), {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      ...authHeaders()
    },
    body: JSON.stringify(payload)
  })

  if (!response.ok) {
    const body = await readBody(response)
    throw new AgentApiError(body?.detail || 'Agent 流式运行请求失败', response.status)
  }
  const finalResponse = await consumeEventStream(
    response, onEvent, parsed => parsed.event === 'state' ? parsed.data : null)
  if (!finalResponse) {
    throw new AgentApiError('SSE 连接结束前未收到 Agent 最终状态', response.status)
  }
  return finalResponse
}

/** 创建与页面连接解耦的后台 Agent 运行。 */
export async function createAgentRun(payload) {
  const response = await apiFetch(apiUrl('/api/agent-runs'), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...authHeaders() },
    body: JSON.stringify(payload)
  })
  const body = await readBody(response)
  if (!response.ok) {
    throw new AgentApiError(body?.detail || '无法创建后台 Agent 运行', response.status)
  }
  return body
}

/** 查询后台运行快照。 */
export async function getAgentRun(runId) {
  const response = await fetch(
    apiUrl(`/api/agent-runs/${encodeURIComponent(runId)}`),
    { headers: authHeaders() }
  )
  const body = await readBody(response)
  if (!response.ok) {
    throw new AgentApiError(body?.detail || '无法读取后台 Agent 运行', response.status)
  }
  return body
}

/**
 * 上传会话附件到 Server 文件访问根目录，返回 {name, relativePath, size} 列表。
 * 注意不能手动设置 Content-Type，需由浏览器自动携带 multipart boundary。
 */
export async function uploadSessionAttachments(sessionId, files) {
  const form = new FormData()
  form.append('sessionId', sessionId)
  for (const file of files) form.append('files', file)
  const response = await apiFetch(apiUrl('/api/attachments'), {
    method: 'POST',
    headers: authHeaders(),
    body: form
  })
  const body = await readBody(response)
  if (!response.ok) {
    throw new AgentApiError(body?.detail || '附件上传失败', response.status)
  }
  return body
}

/** 从指定序号之后补播事件，并继续订阅实时事件。 */
export async function streamAgentRun(runId, afterSequence = 0, onEvent = () => {}) {
  const query = new URLSearchParams({ after: String(Math.max(0, afterSequence || 0)) })
  const response = await fetch(
    apiUrl(`/api/agent-runs/${encodeURIComponent(runId)}/events?${query}`),
    { headers: { Accept: 'text/event-stream', ...authHeaders() } }
  )
  if (!response.ok) {
    const body = await readBody(response)
    throw new AgentApiError(body?.detail || '无法订阅后台 Agent 事件', response.status)
  }
  const finalResponse = await consumeEventStream(
    response,
    onEvent,
    parsed => parsed.event === 'state' ? parsed.data?.data : null
  )
  if (!finalResponse) {
    throw new AgentApiError('后台事件流已断开，将尝试恢复', response.status)
  }
  return finalResponse
}

/** 显式取消后台运行。页面断开不会调用该接口。 */
export async function cancelAgentRun(runId) {
  const response = await apiFetch(apiUrl(`/api/agent-runs/${encodeURIComponent(runId)}/cancel`), {
    method: 'POST',
    headers: authHeaders()
  })
  const body = await readBody(response)
  if (!response.ok) {
    throw new AgentApiError(body?.detail || '无法取消后台 Agent 运行', response.status)
  }
  return body
}

export async function getAgentState(sessionId) {
  const response = await fetch(
    apiUrl(`/api/agents/${encodeURIComponent(sessionId)}/state`),
    { headers: authHeaders() }
  )
  const body = await readBody(response)

  if (!response.ok && response.status !== 404) {
    throw new AgentApiError(body?.detail || '无法读取 Agent 状态', response.status)
  }
  return response.status === 404 ? null : body
}

export async function getPendingAction(sessionId) {
  const response = await fetch(
    apiUrl(`/api/agents/${encodeURIComponent(sessionId)}/pending-action`),
    { headers: authHeaders() }
  )
  if (response.status === 204) return null
  const body = await readBody(response)
  if (!response.ok) {
    throw new AgentApiError(body?.detail || '无法读取待审批操作', response.status)
  }
  return body
}

export async function resolvePendingAction(invocationId, pendingActionId, approved) {
  const response = await fetch(
    apiUrl(`/api/agents/invocations/${encodeURIComponent(invocationId)}/resolution`),
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...authHeaders() },
      body: JSON.stringify({ pendingActionId, approved, data: {} })
    }
  )
  const body = await readBody(response)
  if (!response.ok) {
    throw new AgentApiError(body?.detail || '处理审批操作失败', response.status)
  }
  return body
}

/** 连通性探针：超时或非 2xx 都视为不可达。 */
export async function getServerHealth({ timeoutMs = 5000 } = {}) {
  const response = await apiFetch(apiUrl('/api/health'), {
    headers: authHeaders(),
    signal: AbortSignal.timeout(timeoutMs)
  })
  if (!response.ok) {
    throw new AgentApiError(`server 响应异常 (${response.status})`, response.status)
  }
  const body = await readBody(response)
  if (body?.status !== 'UP') {
    throw new AgentApiError('server 状态异常', response.status)
  }
  return body
}
