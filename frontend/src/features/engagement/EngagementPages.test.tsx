import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { authenticatedRequest } from '../auth/authApi'
import { AchievementsPage, AnalyticsPage } from './EngagementPages'
import { NotificationsPage } from './NotificationsPage'

vi.mock('../auth/authApi', async importOriginal => ({
  ...await importOriginal<typeof import('../auth/authApi')>(),
  authenticatedRequest: vi.fn(),
}))

const request = vi.mocked(authenticatedRequest)

afterEach(() => {
  cleanup()
  request.mockReset()
})

it('displays actual analytics and retains an empty quiz state', async () => {
  request.mockResolvedValue(Response.json({
    timeZone: 'Asia/Kolkata', focusSeconds: 3900, completedTasks: 3, quizAttempts: 0,
    correctAnswers: 0, totalQuestions: 0, flashcardReviews: 2, completedGoals: 2, totalGoals: 3,
    daily: [{ date: '2026-11-24', focusSeconds: 3900, quizAttempts: 0, flashcardReviews: 2 }],
    quizTrend: [],
  }))
  render(<AnalyticsPage />)
  expect(await screen.findByText('2 of 3')).toBeTruthy()
  expect(screen.getByRole('table')).toBeTruthy()
  expect(screen.getAllByText('1 hr 5 min')).toHaveLength(2)
  expect(screen.getByText('No graded questions yet')).toBeTruthy()
  expect(request).toHaveBeenCalledWith('/analytics/overview')
})

it('displays achievement progress from the API', async () => {
  request.mockResolvedValue(Response.json({
    xp: 240, level: 3, streak: { currentDays: 2, longestDays: 4 },
    weeklyChallenge: { title: 'Study on three days', progress: 3, target: 3, weekStart: '2026-11-23', completed: true },
    badges: [{ code: 'START', title: 'Get started', description: 'Study for the first time', earned: true }],
  }))
  render(<AchievementsPage />)
  expect(await screen.findByText('240 XP')).toBeTruthy()
  expect(screen.getByText('Earned')).toBeTruthy()
  expect(screen.getByText('2 days')).toBeTruthy()
  expect(screen.getByRole('progressbar', { name: 'Weekly challenge progress' }).getAttribute('value')).toBe('3')
})

it('marks a notice as read and saves notification preferences', async () => {
  let readAt: string | null = null
  let preferences = { taskReminders: true, invitations: true, messages: true, exams: true, revision: true }
  request.mockImplementation(async (path, options) => {
    if (path === '/notifications?page=0&size=20') return Response.json({
      items: [{ id: '1', type: 'TASK', title: 'Study session', body: 'Your task is due.', createdAt: '2026-11-25T09:00:00Z', readAt }],
      page: 0, size: 20, hasNext: false, unreadCount: readAt === null ? 1 : 0,
    })
    if (path === '/notifications/1/read' && options?.method === 'PUT') {
      readAt = '2026-11-25T09:05:00Z'
      return new Response(null, { status: 204 })
    }
    if (path === '/me/notification-preferences') {
      if (options?.method === 'PUT') preferences = JSON.parse(String(options.body)) as typeof preferences
      return Response.json(preferences)
    }
    throw new Error(path)
  })

  render(<NotificationsPage />)
  fireEvent.click(await screen.findByRole('button', { name: /Mark as read.*Study session/ }))
  expect(await screen.findByText('0 unread')).toBeTruthy()
  expect(screen.getByText('Read')).toBeTruthy()
  fireEvent.click(screen.getByRole('checkbox', { name: 'New messages' }))
  fireEvent.click(screen.getByRole('button', { name: 'Save preferences' }))
  expect(await screen.findByText('Notification preferences saved.')).toBeTruthy()
  expect(preferences.messages).toBe(false)
  expect(preferences.taskReminders).toBe(true)
})
