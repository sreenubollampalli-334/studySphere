import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { DocumentPage, indexResource } from './DocumentsPage'
import { authenticatedRequest } from '../auth/authApi'

vi.mock('../auth/authApi', async importOriginal => ({
  ...await importOriginal<typeof import('../auth/authApi')>(),
  authenticatedRequest: vi.fn(),
}))

const request = vi.mocked(authenticatedRequest)
const base = { id: '17', resourceId: '8', title: 'Lecture notes', owned: true, error: null }

afterEach(() => {
  cleanup()
  request.mockReset()
})

describe('study documents', () => {
  it('indexes the selected private resource and renders grounded PDF excerpts', async () => {
    request.mockResolvedValueOnce(Response.json({ ...base, status: 'PENDING' }))
      .mockResolvedValueOnce(Response.json({ ...base, status: 'READY' }))
      .mockResolvedValueOnce(Response.json({
        answer: 'Photosynthesis converts light into chemical energy.',
        citations: [{ chunkId: '21', page: 3, text: 'Photosynthesis stores energy in sugars.' }],
      }))
    expect(await indexResource('8')).toMatchObject({ id: '17', status: 'PENDING' })
    expect(request).toHaveBeenCalledWith('/documents', expect.objectContaining({
      method: 'POST', body: '{"resourceId":"8"}',
    }))
    render(<DocumentPage id="17" navigate={vi.fn()} />)
    fireEvent.change(await screen.findByLabelText('Question'), { target: { value: 'How is energy stored?' } })
    fireEvent.click(screen.getByRole('button', { name: 'Ask question' }))
    expect(await screen.findByText('Page 3')).toBeTruthy()
    expect(screen.getByText('Photosynthesis stores energy in sugars.')).toBeTruthy()
    expect(request).toHaveBeenCalledWith('/documents/17/ask', expect.objectContaining({
      method: 'POST', body: '{"question":"How is energy stored?"}',
    }))
  })

  it('retries failed indexing and hides asking while it is queued', async () => {
    request.mockResolvedValueOnce(Response.json({ ...base, error: 'Index offline', status: 'FAILED' }))
      .mockResolvedValueOnce(Response.json({ ...base, status: 'PENDING' }))
      .mockResolvedValueOnce(Response.json({ ...base, status: 'PENDING' }))
    render(<DocumentPage id="17" navigate={vi.fn()} />)
    expect((await screen.findByRole('alert')).textContent).toContain('Index offline')
    fireEvent.click(screen.getByRole('button', { name: 'Retry indexing' }))
    await waitFor(() => expect(screen.getByText('Queued')).toBeTruthy())
    expect(screen.queryByRole('button', { name: 'Ask question' })).toBeNull()
  })
})
