import { useCallback, useEffect, useState } from 'react'
import type { ReactNode } from 'react'
import { authenticatedRequest, AuthError, messageFor } from '../auth/authApi'

interface Page<T> { items: T[]; hasNext: boolean }
export interface Subject { id: string; name: string }
export interface Preferences {
  language: string | null
  timeZone: string | null
  learningStyles: string[]
  skills: string[]
  dailyFocusGoalMinutes?: number | null
}

export async function read<T>(path: string): Promise<T> {
  return (await authenticatedRequest(path)).json() as Promise<T>
}

export async function all<T>(path: string) {
  const items: T[] = []
  for (let page = 0; ; page++) {
    const query = new URLSearchParams({ page: String(page), size: '100' })
    const result = await read<Page<T>>(path + (path.includes('?') ? '&' : '?') + query)
    items.push(...result.items)
    if (!result.hasNext) return items
  }
}

export function write(path: string, method: string, data?: unknown) {
  return authenticatedRequest(path, { method, ...(data === undefined ? {} : {
    headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data),
  }) })
}

export function useResource<T>(readData: () => Promise<T>) {
  const [result, setResult] = useState<{ source: () => Promise<T>; data?: T; error?: string }>()
  const [revision, setRevision] = useState(0)
  useEffect(() => {
    let mounted = true
    readData().then(data => { if (mounted) setResult({ source: readData, data }) })
      .catch(failure => { if (mounted) setResult({
        source: readData, error: messageFor(failure, 'Could not load this page.'),
      }) })
    return () => { mounted = false }
  }, [readData, revision])

  const reload = useCallback(async () => {
    setResult({ source: readData, data: await readData() })
  }, [readData])

  return {
    data: result?.source === readData ? result.data : undefined,
    error: result?.source === readData ? result.error ?? '' : '',
    reload,
    retry: () => { setResult(undefined); setRevision(value => value + 1) },
  }
}

export function useMutation(reload: () => Promise<void>) {
  const [busy, setBusy] = useState(false)
  const [feedback, setFeedback] = useState<{ text: string; error: boolean }>()
  async function run(change: () => Promise<unknown>, success = 'Changes saved.') {
    setBusy(true)
    setFeedback(undefined)
    try {
      await change()
    } catch (error) {
      const fallback = error instanceof AuthError && error.status === 409
        ? 'There is a conflict with saved data. Reload the page before trying again.'
        : 'Could not save the change. Check your entries and try again.'
      setFeedback({ text: error instanceof InvalidLocalDate ? error.message : messageFor(error, fallback), error: true })
      setBusy(false)
      return false
    }
    try {
      await reload()
      setFeedback({ text: success, error: false })
    } catch {
      setFeedback({ text: 'The change was saved. Reload the page to see it.', error: true })
    } finally {
      setBusy(false)
    }
    return true
  }
  return { busy, feedback, run }
}

export function Feedback({ value }: { value?: { text: string; error: boolean } }) {
  return value && <p className={value.error ? 'notice notice-error' : 'notice'} role={value.error ? 'alert' : 'status'}>{value.text}</p>
}

export function Pending({ error, retry }: { error: string; retry: () => void }) {
  return error
    ? <><Feedback value={{ text: error, error: true }} /><button type="button" onClick={retry}>Try again</button></>
    : <p role="status">Loading…</p>
}

export function Field({ label, children }: { label: string; children: ReactNode }) {
  return <label className="workspace-field"><span>{label}</span>{children}</label>
}

export function SubjectOptions({ subjects, required = false }: { subjects: Subject[]; required?: boolean }) {
  return <><option value="">{required ? 'Select a subject' : 'No subject'}</option>
    {subjects.map(subject => <option key={subject.id} value={subject.id}>{subject.name}</option>)}</>
}

export function field(form: FormData, name: string) {
  return String(form.get(name) ?? '').trim()
}

class InvalidLocalDate extends Error {}

export function localDate(instant: Date, timeZone: string) {
  return localDateTime(instant, timeZone).slice(0, 10)
}

export function localDateTime(instant: Date, timeZone: string) {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
    hourCycle: 'h23',
  }).formatToParts(instant)
  const get = (type: Intl.DateTimeFormatPartTypes) => parts.find(part => part.type === type)?.value ?? ''
  return [get('year'), get('month'), get('day')].join('-') + 'T' + get('hour') + ':' + get('minute')
}

export function instant(value: string, timeZone: string) {
  if (!value) return ''
  const wallTime = Date.parse(value + 'Z')
  // Samples on each side distinguish both offsets when local clocks change.
  const day = 86_400_000
  const candidate = [-day, 0, day].map(offset => {
    const sample = wallTime + offset
    const observed = Date.parse(localDateTime(new Date(sample), timeZone) + 'Z')
    return wallTime - (observed - sample)
  }).sort((left, right) => left - right)
    .find(epoch => localDateTime(new Date(epoch), timeZone) === value)
  if (candidate === undefined) throw new InvalidLocalDate('This local time does not exist in ' + timeZone + '. Choose a different time.')
  return new Date(candidate).toISOString()
}

export function displayInstant(value: string, timeZone: string) {
  return new Intl.DateTimeFormat(undefined, { timeZone, dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
}
