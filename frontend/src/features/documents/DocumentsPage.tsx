import { FeatureHeader } from '../../components/FeatureHeader'
import { useCallback, useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { AuthError, messageFor } from '../auth/authApi'
import { Field, Pending, read, useMutation, useResource, write } from '../productivity/common'
import { Feedback } from '../productivity/common'

interface StudyDocument {
  id: string
  resourceId: string
  title: string
  status: 'PENDING' | 'PROCESSING' | 'READY' | 'FAILED'
  error: string | null
  owned: boolean
}

interface Citation { chunkId: string; page: number | null; text: string }
interface Answer { answer: string; citations: Citation[] }
interface Page { items: StudyDocument[]; hasNext: boolean }

export const isDocumentPath = (path: string) => /^\/documents\/[1-9]\d*$/.test(path)

export async function indexResource(resourceId: string) {
  return (await write('/documents', 'POST', { resourceId })).json() as Promise<StudyDocument>
}

const POLL_INTERVAL = 2000
const statusLabels = { PENDING: 'Queued', PROCESSING: 'Processing', READY: 'Ready', FAILED: 'Indexing failed' }

export function DocumentsPage({ navigate }: { navigate: (path: string) => void }) {
  const [page, setPage] = useState(0)
  const load = useCallback(() => read<Page>('/documents?page=' + page + '&size=20'), [page])
  const documents = useResource(load)
  const data = documents.data
  return <>
    <FeatureHeader title="Study documents" description="Open an indexed document to ask questions and review cited passages.">
      <button onClick={() => navigate('/resources')}>Open resource library</button>
    </FeatureHeader>
    {data ? <>
      {data.items.length ? <ul className="workspace-list feature-card-grid">
        {data.items.map(item => <li key={item.id}>
          <strong>{item.title}</strong>
          <p><span className="personal-status">{statusLabels[item.status]}</span>{item.owned ? '' : ' · Shared with you'}</p>
          <button onClick={() => navigate('/documents/' + item.id)}>Open {item.title}</button>
        </li>)}
      </ul> : <p className="empty-state">There are no study documents on this page. Index a PDF or TXT from the resource library to get started.</p>}
      <div className="workspace-actions">
        {page > 0 && <button className="button-secondary" onClick={() => setPage(page - 1)}>Previous page</button>}
        {data.hasNext && <button className="button-secondary" onClick={() => setPage(page + 1)}>Next page</button>}
      </div>
    </> : <Pending error={documents.error} retry={documents.retry} />}
  </>
}

export function DocumentPage({ id, navigate }: { id: string; navigate: (path: string) => void }) {
  const endpoint = '/documents/' + id
  const load = useCallback(() => read<StudyDocument>(endpoint + '/status'), [endpoint])
  const document = useResource(load)
  const item = document.data
  const pending = item?.status === 'PENDING' || item?.status === 'PROCESSING'
  const [pollError, setPollError] = useState('')
  const [pollAttempt, setPollAttempt] = useState(0)
  const [answer, setAnswer] = useState<Answer>()
  const [askError, setAskError] = useState('')
  const [asking, setAsking] = useState(false)
  const retry = useMutation(document.reload)
  const remove = useMutation(async () => { navigate('/documents') })

  useEffect(() => {
    if (!pending) return
    let active = true
    let timer: number
    const update = async () => {
      try {
        await document.reload()
        if (active) timer = window.setTimeout(() => { void update() }, POLL_INTERVAL)
      } catch (error) {
        if (active) setPollError(messageFor(error, 'Could not refresh indexing status.'))
      }
    }
    timer = window.setTimeout(() => { void update() }, POLL_INTERVAL)
    return () => { active = false; window.clearTimeout(timer) }
  }, [pending, document.reload, pollAttempt])

  async function ask(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const question = String(new FormData(event.currentTarget).get('question') ?? '').trim()
    if (!question) return
    setAsking(true)
    setAskError('')
    setAnswer(undefined)
    try {
      const response = await write(endpoint + '/ask', 'POST', { question })
      setAnswer(await response.json() as Answer)
    } catch (error) {
      const fallback = error instanceof AuthError && error.status === 409
        ? 'This document is still indexing. Refresh its status before asking again.'
        : 'Could not answer this question. Try again.'
      setAskError(messageFor(error, fallback))
    } finally {
      setAsking(false)
    }
  }

  return <>
    <FeatureHeader title={item?.title ?? 'Study document'}>
      <button className="button-secondary" onClick={() => navigate('/documents')}>Back to study documents</button>
    </FeatureHeader>
    {!item ? <Pending error={document.error} retry={document.retry} /> : <>
      <p role="status">{statusLabels[item.status]}</p>
      {item.status === 'FAILED' && item.error && <p role="alert">{item.error}</p>}
      {pending && pollError && <>
        <p role="alert">{pollError}</p>
        <button className="button-secondary" onClick={() => { setPollError(''); setPollAttempt(attempt => attempt + 1) }}>
          Resume status updates
        </button>
      </>}
      <Feedback value={retry.feedback} />
      <Feedback value={remove.feedback} />
      {item.status === 'FAILED' && item.owned &&
        <button disabled={retry.busy || remove.busy} onClick={() => {
          setPollError('')
          void retry.run(() => write(endpoint + '/retry', 'POST'), 'Indexing queued again.')
        }}>Retry indexing</button>}
      {item.status === 'READY' && <>
        <form className="workspace-form study-tool-form" aria-busy={asking} onSubmit={ask}>
          <h2>Ask about this document</h2>
          <Field label="Question"><textarea name="question" maxLength={2000} required /></Field>
          <button disabled={asking || remove.busy}>Ask question</button>
        </form>
        {asking && <p role="status">Looking for an answer…</p>}
        {askError && <p role="alert">{askError}</p>}
        {answer && <DocumentAnswer answer={answer} />}
      </>}
      {item.owned && <p><button className="button-secondary" disabled={asking || retry.busy || remove.busy} onClick={() => {
        if (window.confirm('Remove indexing for ' + item.title + '? Your resource file will be kept.')) {
          void remove.run(() => write(endpoint, 'DELETE'))
        }
      }}>Remove document index</button></p>}
    </>}
  </>
}

function DocumentAnswer({ answer }: { answer: Answer }) {
  return <section className="workspace-panel" aria-label="Document answer">
    <h2>Answer</h2>
    <p style={{ whiteSpace: 'pre-wrap' }}>{answer.answer}</p>
    {answer.citations.length ? <>
      <h3>Sources</h3>
      <ol className="workspace-list">{answer.citations.map(citation => <li key={citation.chunkId}>
        <strong>{citation.page === null ? 'Text excerpt' : 'Page ' + citation.page}</strong>
        <blockquote>{citation.text}</blockquote>
      </li>)}</ol>
    </> : <p className="empty-state">No source passages were found for this question.</p>}
  </section>
}
