import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { authenticatedRequest } from '../auth/authApi'
import { StudentWorkspace } from '../student/StudentWorkspace'
import { instant, localDate } from './common'

vi.mock('../auth/authApi', () => ({
  authenticatedRequest: vi.fn(),
  AuthError: class extends Error {},
  messageFor: (_error: unknown, fallback: string) => fallback,
}))

const request = vi.mocked(authenticatedRequest)
const prefs = { language: 'en', timeZone: 'UTC', learningStyles: [], skills: [], dailyFocusGoalMinutes: 30 }
const emptyPage = () => Response.json({ items: [], hasNext: false })

afterEach(() => {
  cleanup()
  request.mockReset()
})

it('loads a task on its selected calendar date and creates a recurring task', async () => {
  const day = localDate(new Date(), 'UTC')
  request.mockImplementation(async (path, options) => {
    if (path === '/tasks' && options?.method === 'POST') return Response.json({ id: '2' }, { status: 201 })
    if (path.startsWith('/tasks?')) return Response.json({ items: [{
      id: '1', title: 'Review notes', planId: null, parentId: null, priority: 'HIGH',
      state: 'OPEN', dueAt: day + 'T12:00:00Z', recurrenceRule: null, recurrenceZone: null, version: 0,
    }], hasNext: false })
    if (path === '/me/preferences') return Response.json(prefs)
    if (path.startsWith('/plans?') || path.startsWith('/subjects?')) return emptyPage()
    throw new Error('Unexpected request ' + path)
  })
  render(<StudentWorkspace path="/planner" navigate={vi.fn()} />)
  expect(await screen.findByText(/Review notes ·/, { selector: 'li' })).toBeTruthy()
  const title = screen.getByLabelText('New task title')
  const form = within(title.closest('form')!)
  fireEvent.change(title, { target: { value: 'Read one chapter' } })
  fireEvent.change(form.getByLabelText('Due at (UTC)'), { target: { value: day + 'T14:30' } })
  fireEvent.change(form.getByLabelText('Repeat'), { target: { value: 'DAILY' } })
  fireEvent.click(screen.getByRole('button', { name: 'Add task' }))
  await waitFor(() => {
    const options = request.mock.calls.find(([path, init]) => path === '/tasks' && init?.method === 'POST')?.[1]
    expect(JSON.parse(String(options?.body))).toMatchObject({
      title: 'Read one chapter', dueAt: day + 'T14:30:00.000Z', recurrenceRule: 'DAILY', recurrenceZone: 'UTC',
    })
  })
})

it('converts selected-zone calendar times and handles daylight-saving boundaries', () => {
  expect(instant('2026-11-24T14:30', 'Asia/Kolkata')).toBe('2026-11-24T09:00:00.000Z')
  expect(instant('2026-11-01T01:30', 'America/New_York')).toBe('2026-11-01T05:30:00.000Z')
  expect(() => instant('2026-03-08T02:30', 'America/New_York')).toThrow('This local time does not exist')
})

it('shows an exam countdown against the exam timezone', async () => {
  const tomorrow = new Date(Date.now() + 86_400_000)
  request.mockImplementation(async path => {
    if (path === '/me/preferences') return Response.json(prefs)
    if (path.startsWith('/exams?')) return Response.json({ items: [{
      id: '9', subjectId: '7', title: 'Databases final', examDate: localDate(tomorrow, 'UTC'),
      timeZone: 'UTC', topicIds: [], syllabus: null, goal: null, version: 0,
    }], hasNext: false })
    if (path.startsWith('/subjects')) return emptyPage()
    throw new Error('Unexpected request ' + path)
  })
  render(<StudentWorkspace path="/exams" navigate={vi.fn()} />)
  expect(await screen.findByText('Databases final · Tomorrow')).toBeTruthy()
})

it('restores an active timer and refreshes history after completion', async () => {
  const now = Date.now()
  let completed = false
  request.mockImplementation(async (path, options) => {
    if (path === '/focus/sessions/19/complete' && options?.method === 'POST') {
      completed = true
      return Response.json({ id: '19' })
    }
    if (path.startsWith('/focus/sessions?')) return Response.json({ items: [{
      id: '19', subjectId: null, focusMinutes: 25, breakMinutes: 0,
      state: completed ? 'COMPLETED' : 'ACTIVE',
      startedAt: new Date(now - 120_000).toISOString(),
      completedAt: completed ? new Date(now).toISOString() : null,
    }], hasNext: false })
    if (path === '/focus/summary') return Response.json({
      date: localDate(new Date(), 'UTC'), timeZone: 'UTC', completedMinutes: completed ? 2 : 0, dailyFocusGoalMinutes: 30,
    })
    if (path === '/me/preferences') return Response.json(prefs)
    if (path.startsWith('/subjects?')) return emptyPage()
    throw new Error('Unexpected request ' + path)
  })
  render(<StudentWorkspace path="/focus" navigate={vi.fn()} />)
  expect((await screen.findByRole('timer')).textContent).toMatch(/^2[23]:/)
  fireEvent.click(screen.getByRole('button', { name: 'Finish focus session' }))
  expect(await screen.findByText('Session recorded.')).toBeTruthy()
  expect(screen.getByRole('listitem').textContent).toContain('2 minutes')
  expect(screen.getByText(/2 minutes recorded/)).toBeTruthy()
})
