export type MentorStreamEvent =
  | { type: 'delta'; text: string }
  | { type: 'complete' }
  | { type: 'error'; message: string }

export async function readMentorStream(response: Response, receive: (event: MentorStreamEvent) => void) {
  if (!response.body || !response.headers.get('content-type')?.includes('text/event-stream')) {
    throw new Error('The mentor response was unavailable.')
  }
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let pending = ''
  let event = ''
  let data: string[] = []
  let terminal = false

  function line(value: string) {
    if (value.startsWith('event:')) event = value.slice(6).trim()
    if (value.startsWith('data:')) data.push(value.slice(5).replace(/^ /, ''))
    if (value !== '') return
    if (data.length && ['delta', 'complete', 'error'].includes(event)) {
      const payload: unknown = JSON.parse(data.join('\n'))
      if (event === 'complete') {
        terminal = true
        receive({ type: 'complete' })
      } else if (typeof payload === 'object' && payload !== null) {
        if (event === 'delta' && 'text' in payload && typeof payload.text === 'string') {
          receive({ type: 'delta', text: payload.text })
        } else if (event === 'error') {
          terminal = true
          receive({ type: 'error', message: 'message' in payload && typeof payload.message === 'string'
            ? payload.message : 'The mentor could not finish the reply.' })
        }
      }
    }
    event = ''
    data = []
  }

  try {
    while (!terminal) {
      const chunk = await reader.read()
      if (chunk.done) break
      pending += decoder.decode(chunk.value, { stream: true })
      const lines = pending.split('\n')
      pending = lines.pop() ?? ''
      for (const value of lines) {
        line(value.replace(/\r$/, ''))
        if (terminal) break
      }
    }
    if (!terminal) throw new Error('The mentor connection ended before finishing the reply.')
  } finally {
    await reader.cancel().catch(() => undefined)
    reader.releaseLock()
  }
}
