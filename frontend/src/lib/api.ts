import type { ApiResponse, PageMeta } from './types'
import { IS_DEMO } from './demo'
import { demoFetch } from '@/demo/mockApi'

const BASE = '/api'

// Demo builds answer every /api call in the browser from seed-derived fixtures;
// real builds hit the Spring Boot backend. Everything above the transport
// (envelopes, 401 refresh, error mapping) is shared.
const apiFetch: (url: string, init?: RequestInit) => Promise<Response> = IS_DEMO
  ? demoFetch
  : (url, init) => fetch(url, init)

/** Raised when the backend returns an error envelope or a non-2xx status. */
export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code?: string,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

interface RequestOptions {
  method?: string
  body?: unknown
  signal?: AbortSignal
}

// Single-flight promise for token refresh: ensures concurrent 401s share one refresh,
// preventing multiple refresh calls that would consume a rotating refresh token.
let refreshPromise: Promise<boolean> | null = null

async function request<T>(path: string, opts: RequestOptions = {}): Promise<ApiResponse<T>> {
  const token = getAccessToken()
  const res = await apiFetch(`${BASE}${path}`, {
    method: opts.method ?? 'GET',
    headers: {
      Accept: 'application/json',
      ...(opts.body !== undefined ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined,
    signal: opts.signal,
  })

  // On 401: join or start a single shared refresh, then retry the original request once.
  // Skip for /auth/refresh itself to prevent infinite loops.
  if (res.status === 401 && !path.startsWith('/auth/refresh')) {
    if (!refreshPromise) {
      refreshPromise = attemptRefresh().finally(() => { refreshPromise = null })
    }
    const refreshed = await refreshPromise
    if (refreshed) {
      const newToken = getAccessToken()
      const retryRes = await apiFetch(`${BASE}${path}`, {
        method: opts.method ?? 'GET',
        headers: {
          Accept: 'application/json',
          ...(opts.body !== undefined ? { 'Content-Type': 'application/json' } : {}),
          ...(newToken ? { Authorization: `Bearer ${newToken}` } : {}),
        },
        body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined,
        signal: opts.signal,
      })

      let retryEnvelope: ApiResponse<T> | null = null
      try {
        retryEnvelope = (await retryRes.json()) as ApiResponse<T>
      } catch {
        // non-JSON body
      }

      if (!retryRes.ok || !retryEnvelope?.success) {
        const err = retryEnvelope?.error
        throw new ApiError(err?.message ?? `요청 실패 (${retryRes.status})`, retryRes.status, err?.code)
      }
      return retryEnvelope
    }
    // Refresh failed — tokens already cleared in attemptRefresh; let UI redirect.
    throw new ApiError('인증이 만료되었습니다. 다시 로그인해주세요.', 401)
  }

  let envelope: ApiResponse<T> | null = null
  try {
    envelope = (await res.json()) as ApiResponse<T>
  } catch {
    // non-JSON body
  }

  if (!res.ok || !envelope?.success) {
    const err = envelope?.error
    throw new ApiError(err?.message ?? `요청 실패 (${res.status})`, res.status, err?.code)
  }
  return envelope
}

/** Attempts a token refresh. Returns true on success, false on failure. */
async function attemptRefresh(): Promise<boolean> {
  const refreshToken = getRefreshToken()
  if (!refreshToken) return false

  try {
    const res = await apiFetch(`${BASE}/auth/refresh`, {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ refreshToken }),
    })

    if (!res.ok) {
      clearTokens()
      return false
    }

    type RefreshPayload = { accessToken: string; refreshToken: string; expiresInSec: number }
    const envelope = (await res.json()) as ApiResponse<RefreshPayload>
    if (!envelope.success || !envelope.data) {
      clearTokens()
      return false
    }

    setAccessToken(envelope.data.accessToken)
    setRefreshToken(envelope.data.refreshToken)
    return true
  } catch {
    clearTokens()
    return false
  }
}

/** Returns the data payload, throwing ApiError on failure. */
export async function apiGet<T>(path: string, signal?: AbortSignal): Promise<T> {
  const env = await request<T>(path, { signal })
  return env.data as T
}

/** Returns data + pagination meta for list endpoints. */
export async function apiGetPage<T>(
  path: string,
  signal?: AbortSignal,
): Promise<{ data: T; meta: PageMeta | undefined }> {
  const env = await request<T>(path, { signal })
  return { data: env.data as T, meta: env.meta }
}

export async function apiPost<T>(path: string, body?: unknown): Promise<T> {
  const env = await request<T>(path, { method: 'POST', body })
  return env.data as T
}

export async function apiPatch<T>(path: string, body?: unknown): Promise<T> {
  const env = await request<T>(path, { method: 'PATCH', body })
  return env.data as T
}

export async function apiDelete<T>(path: string): Promise<T> {
  const env = await request<T>(path, { method: 'DELETE' })
  return env.data as T
}

/** POST with extra headers (e.g. Idempotency-Key). */
export async function apiPostWithHeaders<T>(
  path: string,
  body?: unknown,
  headers?: Record<string, string>,
): Promise<T> {
  const buildHeaders = (tok: string | null) => ({
    Accept: 'application/json',
    ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
    ...(tok ? { Authorization: `Bearer ${tok}` } : {}),
    ...headers,
  })

  const res = await apiFetch(`${BASE}${path}`, {
    method: 'POST',
    headers: buildHeaders(getAccessToken()),
    body: body !== undefined ? JSON.stringify(body) : undefined,
  })

  // On 401: join or start the shared refresh, then retry once.
  if (res.status === 401 && !path.startsWith('/auth/refresh')) {
    if (!refreshPromise) {
      refreshPromise = attemptRefresh().finally(() => { refreshPromise = null })
    }
    const refreshed = await refreshPromise
    if (refreshed) {
      const retryRes = await apiFetch(`${BASE}${path}`, {
        method: 'POST',
        headers: buildHeaders(getAccessToken()),
        body: body !== undefined ? JSON.stringify(body) : undefined,
      })
      let retryEnvelope: ApiResponse<T> | null = null
      try {
        retryEnvelope = (await retryRes.json()) as ApiResponse<T>
      } catch {
        // non-JSON body
      }
      if (!retryRes.ok || !retryEnvelope?.success) {
        const err = retryEnvelope?.error
        throw new ApiError(err?.message ?? `요청 실패 (${retryRes.status})`, retryRes.status, err?.code)
      }
      return retryEnvelope.data as T
    }
    throw new ApiError('인증이 만료되었습니다. 다시 로그인해주세요.', 401)
  }

  let envelope: ApiResponse<T> | null = null
  try {
    envelope = (await res.json()) as ApiResponse<T>
  } catch {
    // non-JSON body
  }

  if (!res.ok || !envelope?.success) {
    const err = envelope?.error
    throw new ApiError(err?.message ?? `요청 실패 (${res.status})`, res.status, err?.code)
  }
  return envelope.data as T
}

// --- Auth token storage (in-memory + localStorage mirror) -------------------
const ACCESS_TOKEN_KEY = 'olive.accessToken'
const REFRESH_TOKEN_KEY = 'olive.refreshToken'

let accessToken: string | null = null

export function getAccessToken(): string | null {
  if (accessToken) return accessToken
  accessToken = localStorage.getItem(ACCESS_TOKEN_KEY)
  return accessToken
}

export function setAccessToken(token: string | null): void {
  accessToken = token
  if (token) localStorage.setItem(ACCESS_TOKEN_KEY, token)
  else localStorage.removeItem(ACCESS_TOKEN_KEY)
}

export function getRefreshToken(): string | null {
  return localStorage.getItem(REFRESH_TOKEN_KEY)
}

export function setRefreshToken(token: string | null): void {
  if (token) localStorage.setItem(REFRESH_TOKEN_KEY, token)
  else localStorage.removeItem(REFRESH_TOKEN_KEY)
}

/** Clears both access and refresh tokens (used on logout or auth failure). */
export function clearTokens(): void {
  setAccessToken(null)
  setRefreshToken(null)
}
