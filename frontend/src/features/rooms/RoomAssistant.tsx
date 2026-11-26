import { useCallback, useState } from 'react'
import type { FormEvent } from 'react'
import { Feedback, Field, field, Pending, read, useMutation, useResource, write } from '../productivity/common'

interface Proposal {
  id: string; kind: 'TASK' | 'QUIZ'; title: string; prompt?: string; options?: string[]; approved: boolean
}
interface Summary { id: string; text: string; proposals: Proposal[] }
interface Assistant { consented: boolean; allConsented: boolean; summaries: Summary[]; disclosure: string }
interface Note { id: string; text: string; authorId: string }

export function RoomAssistant({ path, moderate }: { path: string; moderate: boolean }) {
  const load = useCallback(() => Promise.all([
    read<Assistant>(path + '/assistant'), read<Note[]>(path + '/notes'), read<{ id: string }>('/me'),
  ]), [path])
  const resource = useResource(load)
  const mutation = useMutation(resource.reload)
  const [acknowledged, setAcknowledged] = useState(false)
  if (!resource.data) return <Pending error={resource.error} retry={resource.retry} />

  const [assistant, notes, account] = resource.data
  const change = (suffix: string, method: string, payload: unknown, feedback: string) =>
    mutation.run(() => write(path + suffix, method, payload), feedback)
  async function addNote(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const form = event.currentTarget
    if (await change('/notes', 'POST', { text: field(new FormData(form), 'text') }, 'Note added.')) form.reset()
  }
  return <>
    <Feedback value={mutation.feedback} />
    <section><h3>Shared notes</h3>
      {notes.length === 0 ? <p className="empty-state">No shared notes yet.</p> : <ul className="workspace-list">{notes.map(note => <li key={note.id}>
        <p className="workspace-text">{note.text}</p>
        {(moderate || note.authorId === account.id) && <button disabled={mutation.busy} className="button-secondary"
          onClick={() => void change('/notes/' + note.id, 'DELETE', undefined, 'Note removed.')}>Remove note</button>}
      </li>)}</ul>}
      <form onSubmit={addNote}><fieldset disabled={mutation.busy}>
        <Field label="Shared note"><textarea name="text" required maxLength={4000} /></Field><button>Add note</button>
      </fieldset></form>
    </section>
    <section><h3>AI room summaries</h3>
      <p>{assistant.disclosure}</p>
      <button className="button-secondary" disabled={mutation.busy} onClick={() => {
        setAcknowledged(false)
        void change('/assistant/consent', 'PUT', { consented: !assistant.consented },
          assistant.consented ? 'Consent withdrawn.' : 'Consent saved.')
      }}>{assistant.consented ? 'Withdraw my consent' : 'Consent to AI summaries'}</button>
      <p>{assistant.allConsented ? 'Everyone has consented.' : 'Waiting for every room member to consent.'}</p>
      {moderate && <div>
        <Field label="I confirm this information was disclosed to the room">
          <input type="checkbox" checked={acknowledged} disabled={!assistant.allConsented || mutation.busy}
            onChange={event => setAcknowledged(event.target.checked)} />
        </Field>
        <button disabled={mutation.busy || !assistant.allConsented || !acknowledged} onClick={() => {
          void change('/summaries', 'POST', { disclosed: true }, 'Room summary generated.')
        }}>Generate room summary</button>
      </div>}
      {assistant.summaries.length === 0 ? <p className="empty-state">No summaries yet.</p> :
        <ul className="workspace-list">{assistant.summaries.map(summary => <li key={summary.id}>
          <p className="workspace-text">{summary.text}</p>
          {summary.proposals.length > 0 && <><h4>Suggested next steps</h4>
            <ul className="workspace-list">{summary.proposals.map(proposal => <li key={proposal.id}>
              <strong>{proposal.title}</strong><p>{proposal.kind === 'TASK' ? 'Study task' : 'Practice quiz'}</p>
              {proposal.prompt && <p>{proposal.prompt}</p>}
              {proposal.options && proposal.options.length > 0 && <ul>{proposal.options.map((option, index) =>
                <li key={index}>{option}</li>)}</ul>}
              {proposal.approved ? <p>Approved</p> : moderate && <button disabled={mutation.busy}
                onClick={() => void change('/assistant/proposals/' + proposal.id + '/approve', 'POST', undefined,
                  proposal.kind === 'TASK' ? 'Task saved to your planner.' : 'Quiz saved to your quizzes.')}>
                {proposal.kind === 'TASK' ? 'Approve and save task' : 'Approve and save quiz'}
              </button>}
            </li>)}</ul>
          </>}
        </li>)}</ul>}
    </section>
  </>
}
