import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { authenticatedRequest } from '../auth/authApi'
import { StudentWorkspace } from '../student/StudentWorkspace'

vi.mock('../auth/authApi', () => ({
  authenticatedRequest: vi.fn(),
  AuthError: class extends Error {},
  messageFor: (_error: unknown, fallback: string) => fallback,
}))

const request = vi.mocked(authenticatedRequest)
const resource = {
  id: '1', title: 'Lecture notes', fileName: 'lecture.txt', sizeBytes: 120, tags: ['physics'],
  folderId: null, owned: true,
}
const page = (items: unknown[] = []) => Response.json({ items, page: 0, size: 20, hasNext: false })

afterEach(() => {
  cleanup()
  request.mockReset()
})

it('uploads a multipart file and searches by title and tag', async () => {
  request.mockImplementation(async (path, options) => {
    if (path === '/resources' && options?.method === 'POST') return Response.json(resource, { status: 201 })
    if (path.startsWith('/resources?')) return page([resource])
    return page()
  })
  render(<StudentWorkspace path="/resources" navigate={vi.fn()} />)
  expect(await screen.findByRole('heading', { name: 'Resources' })).toBeTruthy()
  const file = new File(['notes'], 'lecture.txt', { type: 'text/plain' })
  fireEvent.change(await screen.findByLabelText('File'), { target: { files: [file] } })
  fireEvent.change(screen.getByLabelText('Title'), { target: { value: 'Lecture notes' } })
  fireEvent.submit(screen.getByRole('button', { name: 'Upload' }).closest('form')!)
  expect(await screen.findByText('File uploaded.')).toBeTruthy()
  const upload = request.mock.calls.find(([path, options]) => path === '/resources' && options?.method === 'POST')
  expect(upload?.[1]?.body).toBeInstanceOf(FormData)
  expect((upload?.[1]?.body as FormData).get('title')).toBe('Lecture notes')

  fireEvent.change(screen.getByLabelText('Search resources'), { target: { value: 'Lecture' } })
  fireEvent.change(screen.getByLabelText('Filter by tag'), { target: { value: 'physics' } })
  fireEvent.click(screen.getByRole('button', { name: 'Search' }))
  await waitFor(() => expect(request).toHaveBeenCalledWith('/resources?page=0&size=20&q=Lecture&tag=physics'))
})

it('only shows access controls to the owner and can revoke access', async () => {
  request.mockImplementation(async (path, options) => {
    if (path.startsWith('/resources?')) return page([resource, {
      ...resource, id: '2', owned: false, title: 'Shared worksheet',
    }])
    if (path === '/resources/1/permissions') return Response.json([{ userId: '4', email: 'student@example.test' }])
    if (path === '/resources/1/permissions/4' && options?.method === 'DELETE') return new Response(null, { status: 204 })
    return page()
  })
  render(<StudentWorkspace path="/resources" navigate={vi.fn()} />)
  fireEvent.click(await screen.findByRole('button', { name: 'Manage Shared worksheet' }))
  expect(screen.queryByLabelText('Share with email')).toBeNull()
  fireEvent.click(screen.getByRole('button', { name: 'Manage Lecture notes' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Revoke student@example.test' }))
  await waitFor(() => expect(request).toHaveBeenCalledWith('/resources/1/permissions/4', { method: 'DELETE' }))
})
