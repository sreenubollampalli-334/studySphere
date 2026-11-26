import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { authenticatedRequest } from '../auth/authApi'
import { MessagesPage } from './MessagesPage'
import { PartnersPage } from './PartnersPage'

const socket = vi.hoisted(() => ({ reconnect: vi.fn(), event: vi.fn() }))
vi.mock('./useConversationSocket', () => ({
  useConversationSocket: (_id: string, event: typeof socket.event, reconnect: typeof socket.reconnect) => {
    socket.event = event
    socket.reconnect = reconnect
    return { connected: true, sendTyping: vi.fn() }
  },
}))
vi.mock('../auth/authApi', () => ({
  authenticatedRequest: vi.fn(),
  AuthError: class extends Error {},
  messageFor: (_failure: unknown, fallback: string) => fallback,
}))

const request = vi.mocked(authenticatedRequest)
const page = (items: unknown[] = [], hasNext = false) => Response.json({ items, hasNext })
const message = (id: string, body: string) => ({
  id, senderId: '12', body, createdAt: '2026-11-25T10:00:00Z', read: false, attachmentName: null, deleted: false,
})

afterEach(() => {
  cleanup()
  request.mockReset()
})

it('sends connection requests and starts messaging from an accepted connection', async () => {
  let requested = false
  request.mockImplementation(async (path, options) => {
    if (path.startsWith('/partners?')) return page([{ userId: '7', displayName: 'Asha', score: 72, reasons: ['Shared subjects'] }])
    if (path === '/connections/requests' && options?.method === 'POST') {
      requested = true
      return new Response(null, { status: 201 })
    }
    if (path === '/connections/requests') return Response.json(requested
      ? [{ id: '1', userId: '7', displayName: 'Asha', direction: 'OUTGOING', status: 'PENDING' }] : [])
    if (path === '/connections') return Response.json([{ userId: '8', displayName: 'Lee' }])
    if (path === '/conversations' && options?.method === 'POST') return Response.json({ id: '31' }, { status: 201 })
    return Response.json([])
  })
  const openConversation = vi.fn()
  render(<PartnersPage openConversation={openConversation} />)
  expect(await screen.findByText('72% match')).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Connect with Asha' }))
  expect(await screen.findByText('Request pending')).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Message Lee' }))
  await waitFor(() => expect(openConversation).toHaveBeenCalledWith('31'))
})

it('recovers missed messages after reconnect and keeps read history deduplicated', async () => {
  let reconnected = false
  const initial = message('1', 'First message')
  const missed = message('2', 'Missed while disconnected')
  request.mockImplementation(async (path, options) => {
    if (path.startsWith('/conversations?')) return page([{ id: '31', peerId: '12', peerName: 'Lee', unreadCount: 0 }])
    if (path.includes('afterId=1')) return page(reconnected ? [missed] : [])
    if (path === '/conversations/31/messages?size=50') return page(reconnected ? [initial, missed] : [initial])
    if (path === '/conversations/31/read' && options?.method === 'POST') return new Response(null, { status: 204 })
    throw new Error('Unexpected request ' + path)
  })
  render(<MessagesPage preferredId="31" />)
  expect(await screen.findByText('First message')).toBeTruthy()
  reconnected = true
  act(() => { socket.reconnect() })
  expect(await screen.findByText('Missed while disconnected')).toBeTruthy()
  expect(screen.getAllByText('First message')).toHaveLength(1)
  await waitFor(() => expect(request).toHaveBeenCalledWith('/conversations/31/messages?afterId=1&size=50'))
})
