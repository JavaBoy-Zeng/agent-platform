import { apiFetch, apiUrl, authHeaders, getServerUrl } from './apiConfig.js'
import { invokeDesktop } from './desktopApi.js'

/** One worker per bound task, independent of the task currently displayed in the UI. */
export function createWorkspaceBridge({ ensureGrant, onChange = () => {} }) {
  const workers = new Map()
  async function native(worker, command, payload = {}) {
    const grantId = await ensureGrant()
    if (worker.stopped && command === 'execute_workspace_operation') throw new Error('本机连接已关闭')
    return invokeDesktop(command, {
      grantId, workspaceId: worker.runtime.workspaceId, ...payload
    })
  }
  async function request(worker, path, body) {
    const response = await apiFetch(`${worker.url}/${path}`, {
      method: 'POST', headers: { 'Content-Type': 'application/json', ...worker.auth,
        ...(worker.token ? { 'X-Workspace-Token': worker.token } : {}) },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: AbortSignal.timeout(8_000)
    })
    if (!response.ok) {
      let detail
      try { detail = await response.json() } catch { /* body may be empty */ }
      const message = response.status === 404 && path === 'connect'
        ? `服务端未提供本机工作区连接接口（404）。请更新并重启服务端；desktop.sh 只更新桌面端。同时确认设置中的服务端地址：${worker.server || '当前同源服务'}`
        : (detail?.detail || `本机连接请求失败 (${response.status})`)
      const error = new Error(message)
      error.status = response.status
      throw error
    }
    const text = await response.text()
    return text ? JSON.parse(text) : null
  }
  function publish(worker) {
    onChange(worker.sessionId, { ...worker.runtime, online: worker.online,
      running: worker.running, error: worker.error || '' })
  }
  async function lock(worker, locked) {
    await native(worker, 'workspace_execution_lock', { sessionId: worker.sessionId, locked })
  }
  async function finish(worker, operation, result) {
    // Keep the result until acknowledged. Never repeat the native operation on an HTTP retry.
    worker.results.set(operation.id, result)
    await flushResults(worker)
  }
  async function flushResults(worker) {
    for (const [id, result] of worker.results) {
      await request(worker, `results/${encodeURIComponent(id)}`, result)
      worker.results.delete(id)
    }
  }
  async function execute(worker, operation) {
    if (worker.stopped || worker.seen.has(operation.id)) return
    worker.seen.add(operation.id)
    worker.active.add(operation.id)
    try {
      const result = await native(worker, 'execute_workspace_operation', {
        expectedBranch: worker.runtime.branch, operationId: operation.id,
        toolName: operation.toolName, arguments: operation.arguments, deadline: operation.deadline
      })
      await finish(worker, operation, result)
    } catch (error) {
      if (!worker.results.has(operation.id)) {
        worker.results.set(operation.id, { success: false, unavailable: true,
          error: `本机操作未能完成：${String(error)}。请核对执行状态后继续。` })
      }
    } finally {
      worker.active.delete(operation.id)
    }
  }
  async function tick(worker) {
    if (worker.stopped || worker.polling) return
    worker.polling = true
    try {
      if (getServerUrl() !== worker.server) throw new Error('服务端地址已改变，请重新连接工作区')
      // Heartbeats are independent of commands, so long tests cannot expire the connection.
      const poll = await request(worker, 'poll')
      if (worker.stopped) return
      worker.running = poll.running
      worker.online = true
      worker.error = ''
      const pending = new Set(poll.pendingIds || [])
      for (const id of worker.active) {
        if (!pending.has(id)) await invokeDesktop('cancel_workspace_operation', { operationId: id })
      }
      await invokeDesktop('workspace_execution_heartbeat', { operationIds: [...worker.active].filter(id => pending.has(id)) })
      await lock(worker, worker.running || worker.active.size > 0)
      await flushResults(worker)
      if (!worker.stopped && poll.operation) void execute(worker, poll.operation)
      // IDs no longer pending have already been acknowledged or cancelled by the server.
      for (const id of worker.seen) if (!pending.has(id) && !worker.active.has(id)) worker.seen.delete(id)
    } catch (error) {
      worker.online = false
      if ([400, 404].includes(error.status)) worker.registrationLost = true
      worker.error = String(error).replace(/^Error:\s*/, '')
      for (const id of worker.active) {
        await invokeDesktop('cancel_workspace_operation', { operationId: id }).catch(() => {})
      }
    } finally {
      worker.polling = false
      if (!worker.stopped) publish(worker)
    }
  }
  async function connect(sessionId, workspaceId) {
    const runtime = await invokeDesktop('workspace_execution_info', { grantId: await ensureGrant(), workspaceId })
    if (!runtime?.root || runtime.workspaceId !== workspaceId) throw new Error('本机执行位置验证失败')
    let worker = workers.get(sessionId)
    if (worker && !worker.registrationLost && worker.server === getServerUrl() && worker.runtime.workspaceId === workspaceId
        && worker.runtime.branch === runtime.branch && worker.runtime.root === runtime.root) {
      await tick(worker)
      if (worker.online) return worker.runtime
      if (!worker.registrationLost) throw new Error(worker.error || '本机工作区离线')
    }
    if (!worker?.registrationLost && (worker?.running || worker?.active.size)) throw new Error('任务运行中不能改变执行目录或分支')
    if (worker) await stop(worker)
    worker = { sessionId, runtime, server: getServerUrl(), auth: authHeaders(),
      url: apiUrl(`/api/desktop-workspaces/${encodeURIComponent(sessionId)}`), token: '',
      active: new Set(), seen: new Set(), results: new Map(), polling: false, stopped: false,
      online: false, running: false }
    const registration = await request(worker, 'connect', runtime)
    worker.token = registration.token
    worker.runtime = registration.runtime
    workers.set(sessionId, worker)
    await tick(worker)
    if (!worker.online) {
      await stop(worker)
      throw new Error(worker.error || '本机连接检查失败')
    }
    worker.timer = window.setInterval(() => { void tick(worker) }, 1000)
    return worker.runtime
  }
  async function stop(worker) {
    worker.stopped = true
    clearInterval(worker.timer)
    for (const id of worker.active) await invokeDesktop('cancel_workspace_operation', { operationId: id }).catch(() => {})
    await request(worker, 'disconnect').catch(() => {})
    await lock(worker, false).catch(() => {})
    workers.delete(worker.sessionId)
  }
  return {
    connect,
    async dispose() { await Promise.allSettled([...workers.values()].map(stop)) },
    async lockSession(sessionId, locked) {
      const worker = workers.get(sessionId)
      if (worker) await lock(worker, locked)
    }
  }
}
