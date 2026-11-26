import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { authenticatedRequest } from '../auth/authApi'
import { QuizzesPage } from './QuizzesPage'
import { FlashcardsPage } from './FlashcardsPage'

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

it('keeps topic generation available when the document catalog fails', async () => {
  request.mockImplementation(async path => {
    if (path.startsWith('/quizzes?')) return page()
    if (path.startsWith('/subjects?')) return page({ id: '7', name: 'Databases' })
    if (path.startsWith('/subjects/7/topics?')) return page({ id: '13', name: 'Normalization' })
    throw new TypeError('Document catalog unavailable')
  })
  render(<QuizzesPage />)
  const source = screen.getByLabelText('Source type')
  fireEvent.change(source, { target: { value: 'document' } })
  expect(await screen.findByRole('alert')).toBeTruthy()
  const retry = screen.getByRole('button', { name: 'Try again' })
  expect(retry.getAttribute('type')).toBe('button')
  fireEvent.change(source, { target: { value: 'topic' } })
  await screen.findByRole('option', { name: 'Databases' })
  fireEvent.change(screen.getByLabelText('Subject'), { target: { value: '7' } })
  expect(await screen.findByRole('option', { name: 'Normalization' })).toBeTruthy()
})

it('shows answers and explanations only after the server grades the attempt', async () => {
  const quiz = { id: '1', title: 'Linear equations' }
  request.mockImplementation(async (path, options) => {
    if (path.startsWith('/quizzes?')) return page(quiz)
    if (path.startsWith('/subjects?') || path.startsWith('/documents?') || path.includes('/attempts?')) return page()
    if (path === '/quizzes/1') return Response.json({ ...quiz, questions: [
      { id: '2', prompt: 'What is x if 2x = 6?', options: ['Two', 'Three'] },
    ] })
    if (path === '/quizzes/1/attempts') return Response.json({ id: '7', status: 'IN_PROGRESS', total: 1 })
    if (path === '/attempts/7/submit') {
      expect(JSON.parse(String(options?.body))).toEqual({ answers: [{ questionId: '2', selectedIndex: 1 }] })
      return Response.json({ id: '7', status: 'SUBMITTED', score: 1, total: 1,
        results: [{ questionId: '2', correctIndex: 1, correct: true, explanation: 'Divide both sides by two.' }] })
    }
    throw new Error(path)
  })
  render(<QuizzesPage />)
  fireEvent.click(await screen.findByRole('button', { name: quiz.title }))
  fireEvent.click(await screen.findByRole('button', { name: 'Start quiz' }))
  expect(screen.queryByText('Divide both sides by two.')).toBeNull()
  fireEvent.click(await screen.findByRole('radio', { name: 'Three' }))
  fireEvent.click(screen.getByRole('button', { name: 'Submit answers' }))
  expect(await screen.findByText('Score 1 / 1')).toBeTruthy()
  expect(screen.getByText('Divide both sides by two.')).toBeTruthy()
})

it('reuses a review request ID when the first response is lost', async () => {
  const submissions: unknown[] = []
  request.mockImplementation(async (path, options) => {
    if (path.startsWith('/flashcard-decks?')) return page()
    if (path.startsWith('/flashcards/due?')) return submissions.length > 1 ? page() : page({
      id: '6', front: 'Capital of India', back: 'New Delhi',
    })
    if (path === '/flashcards/6/reviews') {
      submissions.push(JSON.parse(String(options?.body)))
      if (submissions.length === 1) throw new TypeError('Response was lost')
      return Response.json({ id: '11' })
    }
    throw new Error(path)
  })
  render(<FlashcardsPage />)
  expect(screen.queryByText('New Delhi')).toBeNull()
  fireEvent.click(await screen.findByRole('button', { name: 'Reveal answer' }))
  expect(screen.getByText('New Delhi')).toBeTruthy()
  fireEvent.click(screen.getByRole('button', { name: 'Good' }))
  await screen.findByRole('alert')
  expect((screen.getByRole('button', { name: 'Easy' }) as HTMLButtonElement).disabled).toBe(true)
  fireEvent.click(screen.getByRole('button', { name: 'Good' }))
  await waitFor(() => expect(screen.getByText('No cards are due.')).toBeTruthy())
  expect(submissions).toHaveLength(2)
  expect(submissions[0]).toMatchObject({ rating: 'GOOD', requestId: expect.any(String) })
  expect(submissions[0]).toEqual(submissions[1])
})
