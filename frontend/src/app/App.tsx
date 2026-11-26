import { useEffect, useState } from 'react'

const messages = {
  checking: 'Checking service status…',
  available: 'Service available',
  unavailable: 'Service unavailable',
}

export function App() {
  const [state, setState] = useState<keyof typeof messages>('checking')
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    const controller = new AbortController()
    const timeout = window.setTimeout(() => controller.abort(), 5000)
    let disposed = false

    async function checkHealth() {
      try {
        const response = await fetch('/actuator/health', { signal: controller.signal })
        if (!response.ok) {
          if (!disposed) setState('unavailable')
          return
        }
        const result: unknown = await response.json()
        const healthy = typeof result === 'object' && result !== null
          && 'status' in result && result.status === 'UP'
        if (!disposed) setState(healthy ? 'available' : 'unavailable')
      } catch {
        if (!disposed) setState('unavailable')
      } finally {
        window.clearTimeout(timeout)
      }
    }

    void checkHealth()

    return () => {
      disposed = true
      window.clearTimeout(timeout)
      controller.abort()
    }
  }, [attempt])

  return (
    <main className="status-card">
      <div className="brand"><img src="/studysphere-mark.webp" alt="" width="40" height="40" /></div>
      <h1>StudySphere</h1>
      <p>Learn Smarter. Together.</p>
      <p role="status">{messages[state]}</p>
      {state === 'unavailable' && (
        <button onClick={() => {
          setState('checking')
          setAttempt((previous) => previous + 1)
        }}>Try again</button>
      )}
    </main>
  )
}
