import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { AuthApp } from './AuthApp'
import { clearAccess, restoreAccount } from './authApi'

const account = { id: '42', email: 'student@example.test', roles: ['STUDENT'] }
const password = 'correct horse battery'
const token = { accessToken: 'current-access', expiresInSeconds: 900 }

function mockApi(responses: Record<string, () => Response>) {
  const requests = vi.fn(async (path: string, request?: RequestInit) => {
    if (path === '/api/v1/auth/csrf') return new Response(null, { status: 204 })
    const respond = responses[(request?.method ?? 'GET') + ' ' + path]
    if (!respond) throw new Error('Unexpected request to ' + path)
    return respond()
  })
  vi.stubGlobal('fetch', requests)
  return requests
}

function openPage(path: string) {
  window.history.replaceState(null, '', path)
  render(<AuthApp />)
}

beforeEach(() => {
  clearAccess()
  document.cookie = 'XSRF-TOKEN=test-csrf; path=/'
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  document.cookie = 'XSRF-TOKEN=; path=/; max-age=0'
})

it('signs in with CSRF and reads the account with a bearer token', async () => {
  const requests = mockApi({
    'POST /api/v1/auth/login': () => Response.json(token),
    'GET /api/v1/me': () => Response.json(account),
  })
  openPage('/login')

  fireEvent.change(screen.getByLabelText('Email address'), { target: { value: account.email } })
  fireEvent.change(screen.getByLabelText('Password'), { target: { value: password } })
  fireEvent.click(screen.getByRole('button', { name: 'Sign in' }))

  expect(await screen.findByText(account.email)).toBeTruthy()
  expect(window.location.pathname).toBe('/account')
  expect(requests.mock.calls.map(([path]) => path)).toEqual([
    '/api/v1/auth/csrf', '/api/v1/auth/login', '/api/v1/me',
  ])
  expect(new Headers(requests.mock.calls[1][1]?.headers).get('X-XSRF-TOKEN')).toBe('test-csrf')
  expect(new Headers(requests.mock.calls[2][1]?.headers).get('Authorization')).toBe('Bearer current-access')
})

it('coalesces simultaneous account restorations into one refresh', async () => {
  const requests = mockApi({
    'POST /api/v1/auth/refresh': () => Response.json(token),
    'GET /api/v1/me': () => Response.json(account),
  })
  const [first, second] = await Promise.all([restoreAccount(), restoreAccount()])

  expect(first).toEqual(account)
  expect(second).toEqual(account)
  expect(requests.mock.calls.filter(([path]) => path === '/api/v1/auth/refresh')).toHaveLength(1)
})

it('shows the verification requirement on an unverified sign-in', async () => {
  mockApi({
    'POST /api/v1/auth/login': () => Response.json({ code: 'EMAIL_VERIFICATION_REQUIRED' }, { status: 403 }),
  })
  openPage('/login')

  fireEvent.change(screen.getByLabelText('Email address'), { target: { value: account.email } })
  fireEvent.change(screen.getByLabelText('Password'), { target: { value: password } })
  fireEvent.click(screen.getByRole('button', { name: 'Sign in' }))

  expect((await screen.findByRole('alert')).textContent).toBe(
    'Verify your email using the link we sent, then sign in again.',
  )
  expect(window.location.pathname).toBe('/login')
})

it('redirects a visitor without a refresh session to sign in', async () => {
  mockApi({ 'POST /api/v1/auth/refresh': () => new Response(null, { status: 401 }) })
  openPage('/account')

  expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeTruthy()
  expect(window.location.pathname).toBe('/login')
})

it('blocks direct administration navigation for students without requesting moderation data', async () => {
  const requests = mockApi({
    'POST /api/v1/auth/refresh': () => Response.json(token),
    'GET /api/v1/me': () => Response.json(account),
  })
  openPage('/admin')
  expect(await screen.findByRole('heading', { name: 'Access denied' })).toBeTruthy()
  expect(screen.queryByRole('link', { name: 'Administration' })).toBeNull()
  expect(requests.mock.calls.some(([path]) => path.startsWith('/api/v1/admin'))).toBe(false)
})

it('lets an administrator hide content and resolve a report', async () => {
  let reviewed = false
  const report = { kind: 'COMMUNITY_POST', targetId: '11', reporterId: '15', subjectId: '16',
    status: 'OPEN', createdAt: '2026-11-24T09:00:00Z', reason: 'Spam', content: 'Flagged question' }
  const requests = mockApi({
    'POST /api/v1/auth/refresh': () => Response.json(token),
    'GET /api/v1/me': () => Response.json({ ...account, roles: ['ADMIN'] }),
    'GET /api/v1/admin/reports?status=OPEN&page=0&size=20': () =>
      Response.json({ items: reviewed ? [] : [report], total: reviewed ? 0 : 1, page: 0, size: 20 }),
    'PATCH /api/v1/admin/reports/COMMUNITY_POST/11/15': () => {
      reviewed = true
      return new Response(null, { status: 204 })
    },
  })
  openPage('/admin')
  expect(await screen.findByRole('heading', { name: 'Administration' })).toBeTruthy()
  expect(screen.getByRole('link', { name: 'Administration' }).getAttribute('aria-current')).toBe('page')
  expect(await screen.findByText('Flagged question')).toBeTruthy()
  fireEvent.change(screen.getByLabelText('Moderation reason'), { target: { value: 'Confirmed spam' } })
  fireEvent.click(screen.getByRole('button', { name: 'Hide content and resolve' }))
  expect(await screen.findByText('Report resolved.')).toBeTruthy()
  expect(await screen.findByText('There are no reports with this status.')).toBeTruthy()
  const request = requests.mock.calls.find(([path, init]) => path.endsWith('/COMMUNITY_POST/11/15') && init?.method === 'PATCH')
  expect(request?.[1]?.body).toBe(JSON.stringify({ status: 'RESOLVED', reason: 'Confirmed spam', hideContent: true }))
})

it('keeps the registration acknowledgment generic', async () => {
  const requests = mockApi({ 'POST /api/v1/auth/register': () => new Response(null, { status: 202 }) })
  openPage('/register')

  fireEvent.change(screen.getByLabelText('Email address'), { target: { value: account.email } })
  fireEvent.change(screen.getByLabelText('New password'), { target: { value: password } })
  fireEvent.click(screen.getByRole('button', { name: 'Create account' }))

  expect(await screen.findByText('If your email is eligible, you’ll receive a verification link.')).toBeTruthy()
  expect(requests.mock.calls[1][1]?.body).toBe(JSON.stringify({ email: account.email, password }))
})

it('keeps the reset-link acknowledgment generic', async () => {
  mockApi({ 'POST /api/v1/auth/forgot-password': () => new Response(null, { status: 202 }) })
  openPage('/forgot-password')
  fireEvent.change(screen.getByLabelText('Email address'), { target: { value: account.email } })
  fireEvent.click(screen.getByRole('button', { name: 'Send reset link' }))

  expect(await screen.findByText('If an account matches that email, you’ll receive a password reset link.')).toBeTruthy()
})

it('removes the verification token from the URL and submits it only on confirmation', async () => {
  const requests = mockApi({ 'POST /api/v1/auth/verify': () => new Response(null, { status: 204 }) })
  openPage('/verify-email#token=verify-123')

  expect(window.location.hash).toBe('')
  expect(requests).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button', { name: 'Verify email' }))

  expect(await screen.findByRole('heading', { name: 'Email verified' })).toBeTruthy()
  expect(requests.mock.calls[1][1]?.body).toBe(JSON.stringify({ token: 'verify-123' }))
})

it('resets a password using a fragment token and the new password', async () => {
  const requests = mockApi({ 'POST /api/v1/auth/reset': () => new Response(null, { status: 204 }) })
  openPage('/reset-password#token=reset-123')

  expect(window.location.hash).toBe('')
  fireEvent.change(screen.getByLabelText('New password'), { target: { value: password } })
  fireEvent.click(screen.getByRole('button', { name: 'Reset password' }))

  expect(await screen.findByRole('heading', { name: 'Password reset' })).toBeTruthy()
  expect(requests.mock.calls[1][1]?.body).toBe(JSON.stringify({ token: 'reset-123', newPassword: password }))
})

it('signs out and returns to the sign-in page', async () => {
  const requests = mockApi({
    'POST /api/v1/auth/refresh': () => Response.json(token),
    'GET /api/v1/me': () => Response.json(account),
    'POST /api/v1/auth/logout': () => new Response(null, { status: 204 }),
  })
  openPage('/account')

  fireEvent.click(await screen.findByRole('button', { name: 'Sign out' }))

  expect(await screen.findByText('You’ve been signed out.')).toBeTruthy()
  const logout = requests.mock.calls.find(([path]) => path === '/api/v1/auth/logout')
  expect(new Headers(logout?.[1]?.headers).get('X-XSRF-TOKEN')).toBe('test-csrf')
})

it('closes account deletion with Escape and restores focus without a delete request', async () => {
  const requests = mockApi({
    'POST /api/v1/auth/refresh': () => Response.json(token),
    'GET /api/v1/me': () => Response.json(account),
  })
  openPage('/account')
  const trigger = await screen.findByRole('button', { name: 'Delete account' })
  fireEvent.click(trigger)
  const input = screen.getByLabelText('Password')
  expect(document.activeElement).toBe(input)
  fireEvent.keyDown(input, { key: 'Escape' })
  expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Delete account' }))
  expect(requests.mock.calls.some(([, options]) => options?.method === 'DELETE')).toBe(false)
})

it('reauthenticates with the supplied password before deleting the account', async () => {
  const requests = mockApi({
    'POST /api/v1/auth/refresh': () => Response.json(token),
    'GET /api/v1/me': () => Response.json(account),
    'POST /api/v1/auth/login': () => Response.json({ ...token, accessToken: 'reauthenticated' }),
    'DELETE /api/v1/me': () => new Response(null, { status: 204 }),
  })
  openPage('/account')

  fireEvent.click(await screen.findByRole('button', { name: 'Delete account' }))
  fireEvent.change(screen.getByLabelText('Password'), { target: { value: password } })
  fireEvent.click(screen.getByRole('button', { name: 'Permanently delete account' }))

  expect(await screen.findByText('Your account has been deleted.')).toBeTruthy()
  const login = requests.mock.calls.find(([path]) => path === '/api/v1/auth/login')
  const deletion = requests.mock.calls.find(([path, options]) => path === '/api/v1/me' && options?.method === 'DELETE')
  expect(login?.[1]?.body).toBe(JSON.stringify({ email: account.email, password }))
  expect(new Headers(deletion?.[1]?.headers).get('Authorization')).toBe('Bearer reauthenticated')
})
