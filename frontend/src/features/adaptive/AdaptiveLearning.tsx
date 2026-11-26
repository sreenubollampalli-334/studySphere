import { FeatureHeader } from '../../components/FeatureHeader'
import { useState } from 'react'
import { all, displayInstant, Feedback, Field, field, Pending, useMutation, useResource, write } from '../productivity/common'

interface Topic {
  topicId: string
  topicName: string
  subjectName: string
  evidenceCount: number
  score: number | null
  state: 'INSUFFICIENT' | 'WEAK' | 'DEVELOPING' | 'MASTERED'
}

interface Recommendation {
  id: string
  title: string
  reason: string
}

interface Proposal {
  id: string
  title: string
  startDate: string
  endDate: string
  minutesPerDay: number
  status: string
  tasks: { title: string; dueAt: string; minutes: number }[]
  planId: string | null
}

const topicState = {
  INSUFFICIENT: 'Not enough evidence',
  WEAK: 'Needs practice',
  DEVELOPING: 'Developing',
  MASTERED: 'Mastered',
}

const loadTopics = () => all<Topic>('/analytics/topics')
const loadRecommendations = () => all<Recommendation>('/recommendations')
const loadProposals = () => all<Proposal>('/plans/proposals')

export function AdaptivePage({ navigate }: { navigate: (path: string) => void }) {
  const topics = useResource(loadTopics)
  const recommendations = useResource(loadRecommendations)
  const feedback = useMutation(recommendations.reload)
  return <>
    <FeatureHeader title="Adaptive learning" description="Find topics to revisit and review proposed study plans." />
    <section className="workspace-panel">
      <h2>Topic mastery</h2>
      {topics.data ? topics.data.length ? <ul>{topics.data.map(topic => <li key={topic.topicId}>
        <strong>{topic.topicName}</strong> · {topic.subjectName}
        <div>{topicState[topic.state]}{topic.score !== null && <> · Score {topic.score}</>}
          {' · '}{topic.evidenceCount} {topic.evidenceCount === 1 ? 'observation' : 'observations'}</div>
      </li>)}</ul> : <p>Complete quizzes or flashcard reviews linked to a topic to see mastery here.</p>
        : <Pending error={topics.error} retry={topics.retry} />}
    </section>
    <section className="workspace-panel">
      <h2>Recommendations</h2>
      <Feedback value={feedback.feedback} />
      {recommendations.data ? recommendations.data.length ? <ul>{recommendations.data.map(item => <li key={item.id}>
        <h3>{item.title}</h3>
        <p>{item.reason}</p>
        <div className="workspace-actions">
          <button aria-label={'Helpful for ' + item.title} disabled={feedback.busy} onClick={() => void feedback.run(
            () => write('/recommendations/' + item.id + '/feedback', 'POST', { status: 'HELPFUL' }), 'Feedback saved.',
          )}>Helpful</button>
          <button aria-label={'Not helpful for ' + item.title} className="button-secondary" disabled={feedback.busy} onClick={() => void feedback.run(
            () => write('/recommendations/' + item.id + '/feedback', 'POST', { status: 'NOT_HELPFUL' }), 'Feedback saved.',
          )}>Not helpful</button>
          <button aria-label={'Dismiss ' + item.title} className="button-secondary" disabled={feedback.busy} onClick={() => void feedback.run(
            () => write('/recommendations/' + item.id + '/feedback', 'POST', { status: 'DISMISSED' }), 'Recommendation dismissed.',
          )}>Dismiss</button>
        </div>
      </li>)}</ul> : <p className="empty-state">No recommendations are available yet.</p>
        : <Pending error={recommendations.error} retry={recommendations.retry} />}
    </section>
    <section className="workspace-panel">
      <h2>Study plan proposals</h2>
      <p>Preview a schedule and approve it when you’re ready to add its tasks to your planner.</p>
      <button onClick={() => navigate('/planner')}>Open planner</button>
    </section>
  </>
}

export function PlanProposals({ onApproved, timeZone }: { onApproved: () => Promise<void>; timeZone: string }) {
  const proposals = useResource(loadProposals)
  const [selectedId, setSelectedId] = useState<string>()
  const [startDate, setStartDate] = useState('')
  const [endDate, setEndDate] = useState('')
  const create = useMutation(proposals.reload)
  const approve = useMutation(async () => { await Promise.all([proposals.reload(), onApproved()]) })
  const items = proposals.data
  const selected = items?.find(item => item.id === selectedId) ?? items?.[0]

  return <section className="workspace-panel" aria-labelledby="plan-proposals-heading">
    <h2 id="plan-proposals-heading">Study plan proposals</h2>
    {items ? <>
      <form className="workspace-form study-tool-form" aria-busy={create.busy} onSubmit={event => {
        event.preventDefault()
        const form = new FormData(event.currentTarget)
        void create.run(async () => {
          const response = await write('/plans/proposals', 'POST', {
            title: field(form, 'title'), startDate, endDate, minutesPerDay: Number(field(form, 'minutesPerDay')),
          })
          const proposal = await response.json() as Proposal
          setSelectedId(proposal.id)
        }, 'Proposal ready for review.')
      }}>
        <fieldset disabled={create.busy || approve.busy}>
          <Field label="Proposal title"><input name="title" maxLength={120} required /></Field>
          <Field label="Start date"><input type="date" value={startDate} max={endDate || undefined}
            onChange={event => setStartDate(event.target.value)} required /></Field>
          <Field label="End date"><input type="date" value={endDate} min={startDate || undefined}
            onChange={event => setEndDate(event.target.value)} required /></Field>
          <Field label="Minutes available per day"><input name="minutesPerDay" type="number" min={5} max={480}
            defaultValue={30} required /></Field>
          <button>{create.busy ? 'Generating…' : 'Generate proposal'}</button>
        </fieldset>
      </form>
      <Feedback value={create.feedback} />
      {items.length > 1 && <Field label="Saved proposal"><select value={selected?.id}
        onChange={event => setSelectedId(event.target.value)}>
        {items.map(item => <option value={item.id} key={item.id}>{item.title} · {item.startDate}</option>)}
      </select></Field>}
      {selected && <article>
        <h3>{selected.title}</h3>
        <p>{selected.startDate} to {selected.endDate} · {selected.minutesPerDay} minutes available per day</p>
        {selected.tasks.length ? <ul>{selected.tasks.map((task, index) => <li key={index}>
          {task.title} · <time dateTime={task.dueAt}>{displayInstant(task.dueAt, timeZone)}</time> · {task.minutes} min
        </li>)}</ul> : <p className="empty-state">No tasks in this proposal.</p>}
        {selected.status === 'APPROVED' ? <p>Approved and added to your planner.</p>
          : <button disabled={create.busy || approve.busy || !selected.tasks.length} onClick={() => void approve.run(
            () => write('/plans/proposals/' + selected.id + '/approve', 'POST'), 'Proposal approved and added to your planner.',
          )}>{approve.busy ? 'Approving…' : 'Approve proposal'}</button>}
      </article>}
      <Feedback value={approve.feedback} />
    </> : <Pending error={proposals.error} retry={proposals.retry} />}
  </section>
}
