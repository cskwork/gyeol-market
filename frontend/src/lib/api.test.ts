import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { apiGet, getAccessToken, getRefreshToken, setAccessToken, setRefreshToken } from './api'
import { envelope, errorEnvelope } from '@/testing/render'

// A reload with a bad access token fires several requests at once; each comes
// back 401. Only one /auth/refresh may run: the refresh token rotates.
describe('token refresh is single-flight', () => {
  let refreshCalls: number
  let resolveRefresh: () => void
  let refreshDone: Promise<void>

  beforeEach(() => {
    localStorage.clear()
    setAccessToken('bad-token')
    setRefreshToken('refresh-1')
    refreshCalls = 0
    refreshDone = new Promise((resolve) => { resolveRefresh = resolve })
  })
  afterEach(() => {
    vi.unstubAllGlobals()
    localStorage.clear()
    setAccessToken(null)
  })

  function stubBackend(delayFor: (url: string) => Promise<void> = async () => {}) {
    const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
      if (url === '/api/auth/refresh') {
        refreshCalls++
        await refreshDone
        return envelope({ accessToken: 'good-token', refreshToken: `refresh-${refreshCalls + 1}`, expiresInSec: 900 })
      }
      const auth = (init?.headers as Record<string, string> | undefined)?.Authorization
      if (auth !== 'Bearer good-token') {
        await delayFor(url)
        return errorEnvelope(401, 'UNAUTHORIZED', '인증이 필요합니다.')
      }
      return envelope({ url })
    })
    vi.stubGlobal('fetch', fetchMock)
    return fetchMock
  }

  it('shares one refresh between two concurrent 401s and retries both', async () => {
    stubBackend()
    const cart = apiGet<{ url: string }>('/cart')
    const summary = apiGet<{ url: string }>('/me/summary')
    await new Promise((resolve) => setTimeout(resolve, 0))
    resolveRefresh()

    await expect(cart).resolves.toEqual({ url: '/api/cart' })
    await expect(summary).resolves.toEqual({ url: '/api/me/summary' })
    expect(refreshCalls).toBe(1)
    expect(getAccessToken()).toBe('good-token')
    expect(getRefreshToken()).toBe('refresh-2')
  })

  it('does not refresh again for a 401 that arrives after the shared refresh finished', async () => {
    // /categories was sent with the bad token but its 401 lands only after the
    // first request's refresh completed (the case QA saw: a second /auth/refresh).
    let releaseLate!: () => void
    const late = new Promise<void>((resolve) => { releaseLate = resolve })
    stubBackend((url) => (url === '/api/categories' ? late : Promise.resolve()))
    resolveRefresh()

    const categories = apiGet<{ url: string }>('/categories')
    await expect(apiGet<{ url: string }>('/cart')).resolves.toEqual({ url: '/api/cart' })
    releaseLate()

    await expect(categories).resolves.toEqual({ url: '/api/categories' })
    expect(refreshCalls).toBe(1)
  })
})
