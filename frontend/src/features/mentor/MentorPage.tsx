import { FeatureHeader } from '../../components/FeatureHeader'
import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import Markdown from 'react-markdown'
import remarkMath from 'remark-math'
import rehypeKatex from 'rehype-katex'
import 'katex/dist/katex.min.css'
import { AuthError, authenticatedRequest, messageFor } from '../auth/authApi'
import { readMentorStream } from './mentorStream'

interface Conversation { id: string; title: string; updatedAt: string }
interface Turn { id: string; role: string; content: string; createdAt: string }
interface Page<T> { items: T[]; hasNext: boolean }

const modes = [
  ['EXPLAIN', 'Explain'], ['SIMPLIFY', 'Simplify'], ['COMPARE', 'Compare'], ['SUMMARIZE', 'Summarize'],
  ['PRACTICE', 'Practice'], ['QUIZ', 'Quiz'], ['INTERVIEW', 'Interview'], ['HINTS', 'Hints'], ['EXAM', 'Exam'],
] as const
const base = '/ai/conversations'
const mathPlugins: NonNullable<React.ComponentProps<typeof Markdown>['rehypePlugins']> = [
  [rehypeKatex, { trust: false, maxExpand: 100, maxSize: 20 }],
]

function jsonWrite(method: string, data: unknown): RequestInit {
  return { method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data) }
}

async function read<T>(path: string, signal?: AbortSignal): Promise<T> {
  return (await authenticatedRequest(path, { signal })).json()
}

function notice(error: unknown, fallback: string) {
  if (error instanceof AuthError) {
    if (error.status === 429) return 'The mentor is at its request limit. Try again later.'
    if (error.status === 503) return error.detail ?? 'The mentor provider is unavailable right now.'
  }
  return messageFor(error, fallback)
}

export function MentorPage() {
  const [history, setHistory] = useState<Conversation[]>([])
  const [historyPage, setHistoryPage] = useState(0)
  const [moreHistory, setMoreHistory] = useState(false)
  const [selected, setSelected] = useState<string>()
  const [turns, setTurns] = useState<Turn[]>([])
  const [turnPage, setTurnPage] = useState(0)
  const [moreTurns, setMoreTurns] = useState(false)
  const [loading, setLoading] = useState(true)
  const [loadingTurns, setLoadingTurns] = useState(false)
  const [busy, setBusy] = useState(false)
  const [streaming, setStreaming] = useState(false)
  const [confirmDelete, setConfirmDelete] = useState(false)
  const [prompt, setPrompt] = useState('')
  const [sentPrompt, setSentPrompt] = useState('')
  const [draft, setDraft] = useState('')
  const [mode, setMode] = useState<string>('EXPLAIN')
  const [error, setError] = useState('')
  const [status, setStatus] = useState('')
  const [reload, setReload] = useState(0)
  const [reloadTurns, setReloadTurns] = useState(0)
  const active = useRef<AbortController | null>(null)

  useEffect(() => {
    const abort = new AbortController()
    read<Page<Conversation>>(base, abort.signal).then((page) => {
      if (abort.signal.aborted) return
      setHistory(page.items)
      setHistoryPage(0)
      setMoreHistory(page.hasNext)
    }).catch((failure: unknown) => {
      if (!abort.signal.aborted) setError(notice(failure, 'Could not load conversations.'))
    }).finally(() => { if (!abort.signal.aborted) setLoading(false) })
    return () => abort.abort()
  }, [reload])

  useEffect(() => {
    if (!selected) return
    const abort = new AbortController()
    read<Page<Turn>>(base + '/' + selected + '/messages', abort.signal).then((page) => {
      if (abort.signal.aborted) return
      setTurns([...page.items].reverse())
      setTurnPage(0)
      setMoreTurns(page.hasNext)
      setDraft('')
      setSentPrompt('')
    }).catch((failure: unknown) => {
      if (!abort.signal.aborted) setError(notice(failure, 'Could not load this conversation.'))
    }).finally(() => { if (!abort.signal.aborted) setLoadingTurns(false) })
    return () => abort.abort()
  }, [selected, reloadTurns])

  useEffect(() => () => { active.current?.abort() }, [])

  const conversation = history.find((item) => item.id === selected)

  function choose(id?: string) {
    setSelected(id)
    setTurns([])
    setMoreTurns(false)
    setLoadingTurns(Boolean(id))
    setError('')
    setStatus('')
    setConfirmDelete(false)
    setDraft('')
    setSentPrompt('')
  }

  async function action(run: () => Promise<void>, fallback: string) {
    setBusy(true)
    setError('')
    try { await run() }
    catch (failure) { setError(notice(failure, fallback)) }
    finally { setBusy(false) }
  }

  async function send(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (busy || loadingTurns || !prompt.trim() || active.current) return
    const abort = new AbortController()
    active.current = abort
    let conversationId = selected
    let requested = false
    let failed = false
    setStreaming(true)
    setError('')
    setStatus('Generating a reply…')
    setDraft('')
    setSentPrompt(prompt.trim())
    try {
      if (!conversationId) {
        const created: Conversation = await (await authenticatedRequest(base,
          { ...jsonWrite('POST', { title: 'General study' }), signal: abort.signal })).json()
        conversationId = created.id
        setHistory((old) => [created, ...old])
      }
      requested = true
      const response = await authenticatedRequest(base + '/' + conversationId + '/stream',
        { ...jsonWrite('POST', { message: prompt.trim(), mode }), signal: abort.signal })
      setPrompt('')
      await readMentorStream(response, (update) => {
        if (update.type === 'delta') setDraft((text) => text + update.text)
        if (update.type === 'error') { failed = true; setError(update.message) }
      })
      setStatus(failed ? '' : 'Reply saved.')
    } catch (failure) {
      if (abort.signal.aborted) setStatus('Reply stopped.')
      else {
        setStatus('')
        setError(notice(failure, 'Could not finish the reply. Please try again.'))
      }
    } finally {
      active.current = null
      setStreaming(false)
      if (conversationId && (requested || conversationId !== selected)) {
        setLoadingTurns(true)
        setSelected(conversationId)
        setReloadTurns((previous) => previous + 1)
        setReload((previous) => previous + 1)
      } else {
        setSentPrompt('')
      }
    }
  }

  const controlsDisabled = busy || streaming || loadingTurns

  return <section className="workspace-panel mentor-page">
    <FeatureHeader title="AI Study Mentor" description="Work through a question or return to a saved conversation." />
    {error && <p className="notice-error" role="alert">{error}</p>}
    <div className="mentor-layout">
      <aside aria-label="Conversation history">
        <h2>Conversations</h2>
        <button disabled={controlsDisabled} onClick={() => choose()}>New conversation</button>
        {loading ? <p role="status">Loading conversations…</p> : history.length === 0 && <p className="empty-state">No saved conversations.</p>}
        {history.map((item) => <button className="button-secondary mentor-conversation" key={item.id}
          aria-current={item.id === selected ? 'true' : undefined} disabled={controlsDisabled}
          onClick={() => { if (item.id !== selected) choose(item.id) }}>{item.title}</button>)}
        {moreHistory && <button className="button-secondary" disabled={controlsDisabled} onClick={() => void action(async () => {
          const next = await read<Page<Conversation>>(base + '?page=' + (historyPage + 1))
          setHistory((old) => [...old, ...next.items.filter((item) => !old.some((saved) => saved.id === item.id))])
          setHistoryPage(historyPage + 1)
          setMoreHistory(next.hasNext)
        }, 'Could not load more conversations.')}>More conversations</button>}
      </aside>
      <div className="mentor-main">
        <h2>{conversation?.title ?? 'New conversation'}</h2>
        {conversation && <div className="mentor-management">
          <form key={conversation.id + conversation.title} onSubmit={(event) => {
            event.preventDefault()
            const title = String(new FormData(event.currentTarget).get('title')).trim()
            if (!title || title === conversation.title) return
            void action(async () => {
              const updated: Conversation = await (await authenticatedRequest(base + '/' + conversation.id,
                jsonWrite('PATCH', { title }))).json()
              setHistory((old) => old.map((item) => item.id === updated.id ? updated : item))
            }, 'Could not rename the conversation.')
          }}>
            <label>Conversation title<input name="title" required maxLength={160} defaultValue={conversation.title} disabled={controlsDisabled} /></label>
            <button className="button-secondary" disabled={controlsDisabled}>Rename</button>
          </form>
          {confirmDelete ? <>
            <button disabled={controlsDisabled} onClick={() => void action(async () => {
              await authenticatedRequest(base + '/' + conversation.id, { method: 'DELETE' })
              setHistory((old) => old.filter((item) => item.id !== conversation.id))
              choose()
              setStatus('Conversation deleted.')
              setReload((previous) => previous + 1)
            }, 'Could not delete the conversation.')}>Confirm deletion</button>
            <button className="button-secondary" onClick={() => setConfirmDelete(false)}>Keep conversation</button>
          </> : <button className="button-secondary" disabled={controlsDisabled}
            onClick={() => setConfirmDelete(true)}>Delete conversation</button>}
        </div>}
        {loadingTurns && <p role="status">Loading messages…</p>}
        {selected && moreTurns && <button className="button-secondary" disabled={controlsDisabled}
          onClick={() => void action(async () => {
            const next = await read<Page<Turn>>(base + '/' + selected + '/messages?page=' + (turnPage + 1))
            setTurns((old) => [...next.items.filter((item) => !old.some((saved) => saved.id === item.id)).reverse(), ...old])
            setTurnPage(turnPage + 1)
            setMoreTurns(next.hasNext)
          }, 'Could not load earlier messages.')}>Load earlier messages</button>}
        {turns.length > 0 && <ol className="mentor-messages" aria-label="Messages">
          {turns.map((turn) => <li key={turn.id}>
            <strong>{turn.role === 'assistant' ? 'Study Mentor' : 'You'}</strong>
            {turn.role === 'assistant' ? <Markdown skipHtml disallowedElements={['img']}
              remarkPlugins={[remarkMath]} rehypePlugins={mathPlugins}>{turn.content}</Markdown>
              : <p>{turn.content}</p>}
          </li>)}
        </ol>}
        {sentPrompt && <div className="mentor-draft"><strong>You</strong><p>{sentPrompt}</p>
          {draft && <><strong>Study Mentor</strong><p>{draft}</p></>}</div>}
        <form className="mentor-compose" onSubmit={(event) => void send(event)}>
          <label>Study mode<select value={mode} disabled={controlsDisabled} onChange={(event) => setMode(event.target.value)}>
            {modes.map(([value, label]) => <option value={value} key={value}>{label}</option>)}
          </select></label>
          <label>Your question<textarea required maxLength={4000} value={prompt} disabled={controlsDisabled}
            onChange={(event) => setPrompt(event.target.value)} rows={3} /></label>
          {streaming ? <button type="button" onClick={() => active.current?.abort()}>Stop reply</button>
            : <button disabled={controlsDisabled || !prompt.trim()}>Send</button>}
        </form>
        {status && <p role="status">{status}</p>}
      </div>
    </div>
  </section>
}
