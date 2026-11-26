import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { authenticatedRequest } from '../auth/authApi'
import { MentorPage } from './MentorPage'
import { readMentorStream } from './mentorStream'

vi.mock('../auth/authApi', async (importOriginal) => ({
  ...await importOriginal<typeof import('../auth/authApi')>(),
  authenticatedRequest: vi.fn(),
}))
const request = vi.mocked(authenticatedRequest)
const conversation = { id: '9', title: 'General study', updatedAt: '2026-11-25T00:00:00Z' }
const page = (items: unknown[]) => Response.json({ items, hasNext: false })
const sse = { headers: { 'Content-Type': 'text/event-stream' } }
const encode = (data: string) => new TextEncoder().encode(data)

afterEach(() => { cleanup(); vi.resetAllMocks() })

describe('Study Mentor', () => {
  it('parses fragmented events and tolerates SSE comments', async () => {
    const chunks = ['event: del', 'ta\r\ndata: {"text":"Hi"}\r\n\r', '\n: idle\n\nevent: complete\ndata: {}\n\n']
    const body = new ReadableStream({ start(controller) {
      chunks.forEach((part) => controller.enqueue(encode(part)))
      controller.close()
    } })
    const receive = vi.fn()
    await readMentorStream(new Response(body, sse), receive)
    expect(receive.mock.calls).toEqual([[{ type: 'delta', text: 'Hi' }], [{ type: 'complete' }]])
  })

  it('loads saved Markdown and math without rendering raw HTML or image requests', async () => {
    request
      .mockResolvedValueOnce(page([conversation]))
      .mockResolvedValueOnce(page([{
        id: '1', role: 'assistant', content: '# Energy\n\n$E = mc^2$\n\n<img src="https://bad.example/spy">\n\n![unsafe](https://bad.example/spy)',
      }]))
    const { container } = render(<MentorPage />)
    fireEvent.click(await screen.findByRole('button', { name: conversation.title }))
    expect(await screen.findByRole('heading', { name: 'Energy' })).toBeTruthy()
    expect(container.querySelector('math')).toBeTruthy()
    expect(container.querySelector('img')).toBeNull()
  })

  it('sends the selected mode and stops the active request', async () => {
    let activeSignal: AbortSignal | undefined
    request.mockImplementation(async (path, init) => {
      if (path.endsWith('/stream')) {
        activeSignal = init?.signal ?? undefined
        return new Response(new ReadableStream({ start(controller) {
          controller.enqueue(encode('event: delta\ndata: {"text":"Partial answer"}\n\n'))
          activeSignal?.addEventListener('abort', () => controller.error(new DOMException('Aborted', 'AbortError')))
        } }), sse)
      }
      if (path.endsWith('/messages')) return page([])
      if (init?.method === 'POST') return Response.json(conversation)
      return page([])
    })
    render(<MentorPage />)
    await screen.findByText('No saved conversations.')
    fireEvent.change(screen.getByLabelText('Study mode'), { target: { value: 'HINTS' } })
    fireEvent.change(screen.getByLabelText('Your question'), { target: { value: 'Help with algebra' } })
    fireEvent.click(screen.getByRole('button', { name: 'Send' }))
    expect(await screen.findByText('Partial answer')).toBeTruthy()
    expect(request).toHaveBeenCalledWith('/ai/conversations/9/stream',
      expect.objectContaining({ method: 'POST', body: JSON.stringify({ message: 'Help with algebra', mode: 'HINTS' }) }))
    fireEvent.click(screen.getByRole('button', { name: 'Stop reply' }))
    await waitFor(() => expect(activeSignal?.aborted).toBe(true))
    expect(await screen.findByText('Reply stopped.')).toBeTruthy()
  })
})
