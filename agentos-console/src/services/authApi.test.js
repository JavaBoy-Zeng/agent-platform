import { beforeEach, describe, expect, it, vi } from 'vitest'

import { createUser } from './authApi.js'

describe('authApi', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.restoreAllMocks()
  })

  it('surfaces the server validation detail for create-user failures', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({
      type: 'about:blank',
      title: 'Invalid authentication request',
      status: 400,
      detail: '密码至少 8 位',
      error: 'Bad Request'
    }), {
      status: 400,
      headers: { 'Content-Type': 'application/problem+json' }
    })))

    await expect(createUser('alice', 'short')).rejects.toMatchObject({
      message: '密码至少 8 位',
      status: 400
    })
  })
})
