import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { authenticatedRequest } from '../auth/authApi'
import { RoomPage, RoomsPage } from './RoomsPage'

const socket = vi.hoisted(() => ({ refresh: () => {} }))
vi.mock('./useRoomSocket', () => ({
  useRoomSocket: (_id: string, refresh: () => void) => {
    socket.refresh = refresh
    return true
  },
}))
vi.mock('../auth/authApi', () => ({
  authenticatedRequest: vi.fn(),
  AuthError: class extends Error {},
  messageFor: (_error: unknown, fallback: string) => fallback,
}))

const request = vi.mocked(authenticatedRequest)
const room = {
  id: '4', name: 'Database lab', description: 'Practice relational design',
  visibility: 'PRIVATE', capacity: 5, memberCount: 1, role: null,
}
const page = (items: unknown[]) => Response.json({ items, hasNext: false })

afterEach(() => {
  cleanup()
  request.mockReset()
})

it('opens an invitation from the study room list', async () => {
  request.mockImplementation(async path => path.startsWith('/rooms?') ? page([]) : Response.json([room]))
  const navigate = vi.fn()
  render(<RoomsPage navigate={navigate} />)
  fireEvent.click(await screen.findByRole('button', { name: 'Database lab' }))
  expect(navigate).toHaveBeenCalledWith('/rooms/4')
})

it('joins an invited room and recovers chat after reconnect', async () => {
  let joined = false
  let reconnected = false
  const first = { id: '1', body: 'First room message', senderName: 'Lee', createdAt: '2026-11-25T10:00:00Z', pinned: false }
  request.mockImplementation(async (path, options) => {
    if (path === '/rooms/4') return Response.json(joined ? { ...room, role: 'MEMBER', memberCount: 2 } : room)
    if (path === '/rooms/4/members/me' && options?.method === 'PUT') { joined = true; return Response.json(room) }
    if (path.startsWith('/rooms/4/messages?')) return page(reconnected
      ? [first, { ...first, id: '2', body: 'Recovered room message' }] : [first])
    if (path.startsWith('/resources?')) return page([])
    if (path.startsWith('/rooms/4/')) return Response.json([])
    throw new Error('Unexpected request ' + path)
  })
  render(<RoomPage roomId="4" navigate={vi.fn()} />)
  const join = await screen.findByRole('button', { name: 'Join room' })
  expect(screen.queryByRole('heading', { name: 'Room chat' })).toBeNull()
  fireEvent.click(join)
  expect(await screen.findByText('First room message')).toBeTruthy()
  expect(screen.queryByRole('button', { name: 'Pin message' })).toBeNull()
  reconnected = true
  act(() => socket.refresh())
  expect(await screen.findByText('Recovered room message')).toBeTruthy()
  expect(screen.getAllByText('First room message')).toHaveLength(1)
  await waitFor(() => expect(request).toHaveBeenCalledWith('/rooms/4/members/me', { method: 'PUT' }))
})

it('prevents a join attempt when a displayed room is full', async () => {
  request.mockResolvedValueOnce(Response.json({ ...room, memberCount: 5 }))
  render(<RoomPage roomId="4" navigate={vi.fn()} />)
  expect(await screen.findByText('This room is full.')).toBeTruthy()
  expect((screen.getByRole('button', { name: 'Join room' }) as HTMLButtonElement).disabled).toBe(true)
})
