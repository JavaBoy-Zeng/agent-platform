import { AgentApiError } from './agentApi.js'
import { apiUrl, authHeaders, notifyUnauthorized } from './apiConfig.js'

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
  const message = typeof body === 'string' ? body : body?.error || body?.detail
  return new AgentApiError(message || fallback, status)
}

/** 登录并返回 { token, expiresAt, user }。 */
export async function login(username, password) {
  const response = await fetch(apiUrl('/api/auth/login'), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password })
  })
  const body = await readBody(response)
  if (!response.ok) {
    throw errorDetail(body, '登录失败', response.status)
  }
  return body
}

/** 当前登录身份。 */
export async function getMe() {
  const response = await fetch(apiUrl('/api/auth/me'), { headers: authHeaders() })
  const body = await readBody(response)
  if (response.status === 401) notifyUnauthorized()
  if (!response.ok) {
    throw errorDetail(body, '无法读取当前用户', response.status)
  }
  return body
}

/** 修改本人密码。 */
export async function changePassword(oldPassword, newPassword) {
  const response = await fetch(apiUrl('/api/auth/change-password'), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...authHeaders() },
    body: JSON.stringify({ oldPassword, newPassword })
  })
  const body = await readBody(response)
  if (response.status === 401) notifyUnauthorized()
  if (!response.ok) {
    throw errorDetail(body, '修改密码失败', response.status)
  }
  return body
}

/** 用户列表（仅 ADMIN）。 */
export async function listUsers() {
  const response = await fetch(apiUrl('/api/auth/users'), { headers: authHeaders() })
  const body = await readBody(response)
  if (response.status === 401) notifyUnauthorized()
  if (!response.ok) {
    throw errorDetail(body, '无法读取用户列表', response.status)
  }
  return body
}

/** 新建用户（仅 ADMIN）。 */
export async function createUser(username, password, roles = []) {
  const response = await fetch(apiUrl('/api/auth/users'), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...authHeaders() },
    body: JSON.stringify({ username, password, roles })
  })
  const body = await readBody(response)
  if (response.status === 401) notifyUnauthorized()
  if (!response.ok) {
    throw errorDetail(body, '创建用户失败', response.status)
  }
  return body
}

/** 完整替换用户角色（仅 ADMIN）。 */
export async function updateUserRoles(username, roles) {
  const response = await fetch(apiUrl(`/api/auth/users/${encodeURIComponent(username)}/roles`), {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json', ...authHeaders() },
    body: JSON.stringify({ roles })
  })
  const body = await readBody(response)
  if (response.status === 401) notifyUnauthorized()
  if (!response.ok) throw errorDetail(body, '更新角色失败', response.status)
  return body
}

/** 删除用户（仅 ADMIN）。 */
export async function deleteUser(username) {
  const response = await fetch(apiUrl(`/api/auth/users/${encodeURIComponent(username)}`), {
    method: 'DELETE',
    headers: authHeaders()
  })
  if (response.status === 401) notifyUnauthorized()
  if (response.status === 204) return null
  const body = await readBody(response)
  if (!response.ok) {
    throw errorDetail(body, '删除用户失败', response.status)
  }
  return body
}
