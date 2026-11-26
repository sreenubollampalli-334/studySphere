import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { authenticatedRequest } from '../auth/authApi'
import { AdaptivePage, PlanProposals } from './AdaptiveLearning'

vi.mock('../auth/authApi', async importOriginal => ({
  ...await importOriginal<typeof import('../auth/authApi')>(),
  authenticatedRequest: vi.fn(),
}))

const request = vi.mocked(authenticatedRequest)
const page = (...items: unknown[]) => Response.json({ items, hasNext: false })

afterEach(() => {
  cleanup()
  request.mockReset()
})

it('shows insufficient evidence and submits feedback for the selected recommendation', async () => {
  let dismissed = false
  request.mockImplementation(async (path, options) => {
    if (path.startsWith('/analytics/topics?')) return page({
      topicId: '2', topicName: 'Normalization', subjectName: 'Databases',
      evidenceCount: 1, score: null, state: 'INSUFFICIENT',
    })
    if (path.startsWith('/recommendations?')) return dismissed ? page() : page({
      id: '4', title: 'Review candidate keys', reason: 'Recent results show this needs practice.',
    })
    if (path === '/recommendations/4/feedback') {
      expect(JSON.parse(String(options?.body))).toEqual({ status: 'DISMISSED' })
      dismissed = true
      return new Response(null, { status: 204 })
    }
    throw new Error(path)
  })
  render(<AdaptivePage navigate={vi.fn()} />)
  expect(await screen.findByText(/Not enough evidence/)).toBeTruthy()
  fireEvent.click(await screen.findByRole('button', { name: 'Dismiss Review candidate keys' }))
  expect(await screen.findByText('No recommendations are available yet.')).toBeTruthy()
})

it('previews scheduled work before approving and refreshes the planner after approval', async () => {
  const proposal = {
    id: '8', title: 'Database week', startDate: '2030-12-12', endDate: '2030-12-13', minutesPerDay: 30,
    status: 'PREVIEW', tasks: [{ title: 'Review normalization', dueAt: '2030-12-12T10:00:00Z', minutes: 25 }],
    planId: null,
  }
  let saved: typeof proposal | undefined
  const refresh = vi.fn(async () => {})
  request.mockImplementation(async (path, options) => {
    if (path.startsWith('/plans/proposals?')) return saved ? page(saved) : page()
    if (path === '/plans/proposals') {
      expect(JSON.parse(String(options?.body))).toEqual({
        title: proposal.title, startDate: proposal.startDate, endDate: proposal.endDate, minutesPerDay: 30,
      })
      saved = proposal
      return Response.json(saved)
    }
    if (path === '/plans/proposals/8/approve') {
      saved = { ...proposal, status: 'APPROVED' }
      return Response.json(saved)
    }
    throw new Error(path)
  })
  render(<PlanProposals onApproved={refresh} timeZone="UTC" />)
  fireEvent.change(await screen.findByLabelText('Proposal title'), { target: { value: proposal.title } })
  fireEvent.change(screen.getByLabelText('Start date'), { target: { value: proposal.startDate } })
  fireEvent.change(screen.getByLabelText('End date'), { target: { value: proposal.endDate } })
  fireEvent.click(screen.getByRole('button', { name: 'Generate proposal' }))
  expect(await screen.findByText(/Review normalization/)).toBeTruthy()
  expect(refresh).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button', { name: 'Approve proposal' }))
  expect(await screen.findByText('Approved and added to your planner.')).toBeTruthy()
  expect(refresh).toHaveBeenCalledTimes(1)
})
