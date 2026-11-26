import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { authenticatedRequest } from '../auth/authApi'
import { StudentWorkspace } from './StudentWorkspace'

vi.mock('../auth/authApi', () => ({
  authenticatedRequest: vi.fn(),
  messageFor: (_error: unknown, fallback: string) => fallback,
}))

const request = vi.mocked(authenticatedRequest)
const emptyProfile = {
  displayName: null, institution: null, program: null, bio: null,
  discoverable: false, onboardingComplete: false,
}

afterEach(() => {
  cleanup()
  request.mockReset()
})

it('closes workspace navigation with Escape and returns focus to its toggle', () => {
  render(<StudentWorkspace path="/admin" navigate={vi.fn()} />)
  fireEvent.click(screen.getByRole('button', { name: 'Browse workspace' }))
  const planner = screen.getByRole('link', { name: 'Planner' })
  planner.focus()
  fireEvent.keyDown(planner, { key: 'Escape' })
  const toggle = screen.getByRole('button', { name: 'Browse workspace' })
  expect(toggle.getAttribute('aria-expanded')).toBe('false')
  expect(document.activeElement).toBe(toggle)
})

it('loads the scoped dashboard and offers onboarding when activity is empty', async () => {
  request.mockResolvedValue(Response.json({
    onboardingComplete: false, todayTasks: [], upcomingExams: [], recentResources: [],
    activeRooms: [], quizSummaries: [], recommendations: [], streak: null,
  }))
  const navigate = vi.fn()
  render(<StudentWorkspace path="/dashboard" navigate={navigate} />)

  expect(await screen.findByText('No activity has been recorded yet.')).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Finish onboarding' }))
  expect(navigate).toHaveBeenCalledWith('/onboarding')
  expect(request).toHaveBeenCalledWith('/dashboard')
})

it('persists the required setup fields before navigating to the dashboard', async () => {
  let complete = false
  request.mockImplementation(async (path, options) => {
    if (options?.method === 'PUT') {
      if (path === '/me/subjects') complete = true
      return new Response(null, { status: 200 })
    }
    if (path === '/me/profile') return Response.json({ ...emptyProfile, onboardingComplete: complete })
    if (path === '/me/preferences') return Response.json({ language: 'en', timeZone: 'UTC', learningStyles: [], skills: [] })
    if (path === '/me/subjects') return Response.json({ subjectIds: ['8'] })
    if (path.startsWith('/subjects?')) return Response.json({
      items: [{ id: '7', name: 'Databases' }, { id: '8', name: 'Physics' }], hasNext: false,
    })
    throw new Error('Unexpected path ' + path)
  })
  const navigate = vi.fn()
  render(<StudentWorkspace path="/onboarding" navigate={navigate} />)

  fireEvent.change(await screen.findByLabelText('Display name'), { target: { value: 'Asha' } })
  expect((screen.getByRole('checkbox', { name: 'Physics' }) as HTMLInputElement).checked).toBe(true)
  const search = screen.getByRole('searchbox', { name: 'Search subjects' })
  fireEvent.change(search, { target: { value: 'database' } })
  expect(screen.queryByLabelText('Physics')).toBeNull()
  fireEvent.click(screen.getByLabelText('Databases'))
  expect(screen.getByText('2 selected')).toBeTruthy()
  fireEvent.change(search, { target: { value: 'no match' } })
  expect(screen.getByText('No subjects match your search.')).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Save and open dashboard' }))
  await waitFor(() => expect(navigate).toHaveBeenCalledWith('/dashboard'))

  const writes = request.mock.calls.filter(([, options]) => options?.method === 'PUT')
  expect(writes.map(([path]) => path)).toEqual(['/me/profile', '/me/preferences', '/me/subjects'])
  expect(JSON.parse(String(writes[2][1]?.body))).toEqual({ subjectIds: ['8', '7'] })
})

it('keeps an invalid availability draft available for correction', async () => {
  request.mockImplementation(async (path, options) => {
    if (path === '/me/availability' && options?.method === 'PUT') throw new Error('Invalid slots')
    if (path === '/me/preferences') return Response.json({ language: 'en', timeZone: 'UTC', learningStyles: [], skills: [] })
    if (path === '/me/availability') return Response.json({ slots: [] })
    if (path.startsWith('/subjects?') || path.startsWith('/me/goals?')) {
      return Response.json({ items: [], hasNext: false })
    }
    throw new Error('Unexpected path ' + path)
  })
  render(<StudentWorkspace path="/settings" navigate={vi.fn()} />)

  fireEvent.click(await screen.findByRole('button', { name: 'Add time window' }))
  fireEvent.change(screen.getByLabelText('Start 1'), { target: { value: '17:00' } })
  fireEvent.change(screen.getByLabelText('End 1'), { target: { value: '16:00' } })
  fireEvent.click(screen.getByRole('button', { name: 'Save availability' }))
  expect(await screen.findByRole('alert')).toBeTruthy()
  expect((screen.getByLabelText('End 1') as HTMLInputElement).value).toBe('16:00')
})
