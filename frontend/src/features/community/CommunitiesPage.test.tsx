import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { authenticatedRequest } from '../auth/authApi'
import { RoomAssistant } from '../rooms/RoomAssistant'
import { CommunityPage } from './CommunitiesPage'

vi.mock('../auth/authApi', () => ({
  authenticatedRequest: vi.fn(),
  AuthError: class extends Error {},
  messageFor: (_failure: unknown, fallback: string) => fallback,
}))

const request = vi.mocked(authenticatedRequest)
const page = (items: unknown[]) => Response.json({ items, hasNext: false })
const empty = () => new Response(null, { status: 204 })

afterEach(() => { cleanup(); request.mockReset() })

it('loads a community discussion only after joining and reuses the single vote endpoint', async () => {
  let joined = false
  let vote = 0
  request.mockImplementation(async (path, options) => {
    if (path === '/communities/5') return Response.json({ id: '5', name: 'Database practice', role: joined ? 'MEMBER' : null })
    if (path === '/communities/5/members/me') { joined = true; return empty() }
    if (path === '/me') return Response.json({ id: '7' })
    if (path === '/posts/1/vote') { vote = options?.method === 'DELETE' ? 0 : 1; return empty() }
    if (path.startsWith('/posts?')) return page([{ id: '1', title: 'Index design', body: 'When should we add an index?',
      authorId: '8', score: vote, myVote: vote }])
    return page([])
  })
  render(<CommunityPage id="5" navigate={vi.fn()} />)
  const join = await screen.findByRole('button', { name: 'Join community' })
  expect(request.mock.calls.some(([path]) => path.startsWith('/posts?'))).toBe(false)
  fireEvent.click(join)
  fireEvent.click(await screen.findByRole('button', { name: 'Upvote Index design' }))
  await waitFor(() => expect(screen.getByRole('button', { name: 'Upvote Index design' }).getAttribute('aria-pressed')).toBe('true'))
  fireEvent.click(screen.getByRole('button', { name: 'Upvote Index design' }))
  await waitFor(() => expect(request).toHaveBeenCalledWith('/posts/1/vote', { method: 'DELETE' }))
})

it('requires all-room consent and explicit disclosure before generating a summary, then approves a task separately', async () => {
  let consent = false
  let generated = false
  request.mockImplementation(async (path, options) => {
    if (path === '/me') return Response.json({ id: '7' })
    if (path.endsWith('/notes')) return Response.json([])
    if (path.endsWith('/consent')) { consent = true; return empty() }
    if (path.endsWith('/summaries')) { generated = true; return empty() }
    if (path.endsWith('/approve')) return empty()
    if (path.endsWith('/assistant')) return Response.json({
      consented: consent, allConsented: consent, disclosure: 'Room content may be sent to the AI provider.',
      summaries: generated ? [{ id: '3', text: 'Practice SQL joins.', proposals: [{ id: '2', kind: 'TASK', title: 'Practice joins', approved: false }] }] : [],
    })
    throw new Error('Unexpected request ' + path + ' ' + options?.method)
  })
  render(<RoomAssistant path="/rooms/1" moderate />)
  const generate = await screen.findByRole<HTMLButtonElement>('button', { name: 'Generate room summary' })
  expect(generate.disabled).toBe(true)
  fireEvent.click(screen.getByRole('button', { name: 'Consent to AI summaries' }))
  await screen.findByText('Everyone has consented.')
  expect(generate.disabled).toBe(true)
  fireEvent.click(screen.getByRole('checkbox', { name: 'I confirm this information was disclosed to the room' }))
  fireEvent.click(generate)
  const approve = await screen.findByRole('button', { name: 'Approve and save task' })
  expect(request.mock.calls.some(([path]) => path.endsWith('/approve'))).toBe(false)
  fireEvent.click(approve)
  await waitFor(() => expect(request).toHaveBeenCalledWith('/rooms/1/assistant/proposals/2/approve', { method: 'POST' }))
})
