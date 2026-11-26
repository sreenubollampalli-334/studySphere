const API_PREFIX = '/api/v1'

export interface Account {
  id: string
  email: string
  roles: string[]
}

interface Access {
  accessToken: string
  expiresInSeconds: number
}

export class AuthError extends Error {
  constructor(readonly status: number, readonly code?: string, readonly detail?: string) {
    super('The account request could not be completed.')
    this.name = 'AuthError'
  }
}

let access: { token: string; expiresAt: number } | undefined
let pendingRefresh: Promise<string> | undefined

function rememberAccess(result: Access) {
  const { accessToken, expiresInSeconds } = result
  if (typeof accessToken !== 'string' || !accessToken || !Number.isFinite(expiresInSeconds) || expiresInSeconds <= 0) {
    throw new Error('The server returned invalid session details.')
  }
  access = { token: accessToken, expiresAt: Date.now() + expiresInSeconds * 1000 }
  return accessToken
}

function currentToken() {
  return access && access.expiresAt - Date.now() > 1000 ? access.token : undefined
}

export function clearAccess() {
  access = undefined
}

async function apiRequest(path: string, options?: RequestInit) {
  const response = await fetch(API_PREFIX + path, {
    ...options,
    credentials: 'same-origin',
    cache: 'no-store',
  })
  if (!response.ok) {
    const problem: unknown = await response.json().catch(() => null)
    const code = problem && typeof problem === 'object' && 'code' in problem
      && typeof problem.code === 'string' ? problem.code : undefined
    const detail = response.headers.get('content-type')?.includes('application/problem+json')
      && problem && typeof problem === 'object' && 'detail' in problem
      && typeof problem.detail === 'string' && problem.detail.length <= 300
      ? problem.detail : undefined
    throw new AuthError(response.status, code, detail)
  }
  return response
}

function readCsrfCookie() {
  const prefix = 'XSRF-TOKEN='
  const cookie = document.cookie.split(';').map((part) => part.trim())
    .find((part) => part.startsWith(prefix))
  return cookie ? decodeURIComponent(cookie.slice(prefix.length)) : undefined
}

async function write(path: string, options: {
  method?: 'POST' | 'DELETE'
  body?: Record<string, string>
  token?: string
} = {}) {
  await apiRequest('/auth/csrf')
  const csrfToken = readCsrfCookie()
  if (!csrfToken) throw new AuthError(403, 'CSRF_COOKIE_MISSING')

  const headers = new Headers({ 'X-XSRF-TOKEN': csrfToken })
  if (options.body) headers.set('Content-Type', 'application/json')
  if (options.token) headers.set('Authorization', `Bearer ${options.token}`)
  return apiRequest(path, {
    method: options.method ?? 'POST',
    headers,
    body: options.body ? JSON.stringify(options.body) : undefined,
  })
}

// The shared refresh cookie is updated before another tab can start rotating it.
async function lockSession<T>(operation: () => Promise<T>): Promise<T> {
  if (!navigator.locks) return operation()
  return await navigator.locks.request('studysphere-auth-session', operation)
}

function refreshAccess() {
  if (!pendingRefresh) {
    pendingRefresh = lockSession(async () => {
      try {
        const response = await write('/auth/refresh')
        return rememberAccess(await response.json() as Access)
      } catch (error) {
        clearAccess()
        throw error
      }
    }).finally(() => { pendingRefresh = undefined })
  }
  return pendingRefresh
}

async function loadAccount(token: string): Promise<Account> {
  const response = await apiRequest('/me', { headers: { Authorization: `Bearer ${token}` } })
  return response.json() as Promise<Account>
}

export async function restoreAccount() {
  const token = currentToken()
  if (token) {
    try {
      return await loadAccount(token)
    } catch (error) {
      if (!(error instanceof AuthError) || error.status !== 401) throw error
      clearAccess()
    }
  }
  return loadAccount(await refreshAccess())
}

export async function authenticatedRequest(path: string, options?: RequestInit) {
  const send = (token: string) => {
    const headers = new Headers(options?.headers)
    headers.set('Authorization', `Bearer ${token}`)
    return apiRequest(path, { ...options, headers })
  }
  const token = currentToken()
  if (token) {
    try {
      return await send(token)
    } catch (error) {
      if (!(error instanceof AuthError) || error.status !== 401) throw error
      if (currentToken() === token) clearAccess()
    }
  }
  return send(currentToken() ?? await refreshAccess())
}

export function getSocketAccessToken(previous?: string) {
  const token = currentToken()
  return token && token !== previous ? Promise.resolve(token) : refreshAccess()
}

export function signIn(email: string, password: string) {
  return lockSession(async () => {
    const response = await write('/auth/login', { body: { email, password } })
    const token = rememberAccess(await response.json() as Access)
    return loadAccount(token)
  })
}

export function register(email: string, password: string) {
  return write('/auth/register', { body: { email, password } })
}

export function verifyEmail(token: string) {
  return write('/auth/verify', { body: { token } })
}

export function requestPasswordReset(email: string) {
  return write('/auth/forgot-password', { body: { email } })
}

export function resetPassword(token: string, newPassword: string) {
  return lockSession(async () => {
    await write('/auth/reset', { body: { token, newPassword } })
    clearAccess()
  })
}

export function signOut() {
  return lockSession(async () => {
    await write('/auth/logout')
    clearAccess()
  })
}

export function deleteAccount(email: string, password: string) {
  return lockSession(async () => {
    const login = await write('/auth/login', { body: { email, password } })
    const token = rememberAccess(await login.json() as Access)
    await write('/me', { method: 'DELETE', token })
    clearAccess()
  })
}

export function messageFor(error: unknown, fallback: string) {
  if (error instanceof AuthError) {
    if (error.code === 'CSRF_COOKIE_MISSING') return 'Allow cookies for StudySphere and try again.'
    if (error.status === 429) return 'Too many attempts. Wait a moment and try again.'
    if ([413, 502, 503].includes(error.status) && error.detail) return error.detail
  }
  if (error instanceof TypeError) return 'We couldn’t reach StudySphere. Check your connection and try again.'
  return fallback
}
