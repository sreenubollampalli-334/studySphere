import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { App } from './App'

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

it('reports backend health from the API', async () => {
  const fetch = vi.fn().mockResolvedValue(Response.json({ status: 'UP' }))
  vi.stubGlobal('fetch', fetch)
  render(<App />)

  expect(screen.getByRole('status').textContent).toBe('Checking service status…')
  expect(await screen.findByText('Service available')).toBeTruthy()
  expect(fetch).toHaveBeenCalledWith('/actuator/health', { signal: expect.any(AbortSignal) })
})

it('recovers when a retry succeeds', async () => {
  const fetch = vi.fn()
    .mockResolvedValueOnce(Response.json({ status: 'DOWN' }, { status: 503 }))
    .mockResolvedValueOnce(Response.json({ status: 'UP' }))
  vi.stubGlobal('fetch', fetch)
  render(<App />)

  fireEvent.click(await screen.findByRole('button', { name: 'Try again' }))
  expect(await screen.findByText('Service available')).toBeTruthy()
})
