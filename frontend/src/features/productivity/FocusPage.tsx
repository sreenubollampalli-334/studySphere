import { FeatureHeader } from '../../components/FeatureHeader'
import { useEffect, useState } from 'react'
import { all, displayInstant, Feedback, field, Field, Pending, read, SubjectOptions, useMutation, useResource, write } from './common'
import type { Preferences, Subject } from './common'

interface Session {
  id: string; subjectId: string | null; focusMinutes: number; breakMinutes: number
  state: 'ACTIVE' | 'COMPLETED'; startedAt: string; completedAt: string | null
}
interface Summary {
  date: string; timeZone: string; completedMinutes: number; dailyFocusGoalMinutes: number | null
}

const loadFocus = () => Promise.all([
  all<Session>('/focus/sessions'), read<Summary>('/focus/summary'),
  read<Preferences>('/me/preferences'), all<Subject>('/subjects'),
])

function Countdown({ startedAt, minutes }: { startedAt: string; minutes: number }) {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 1000)
    return () => window.clearInterval(timer)
  }, [])
  const seconds = Math.max(0, Math.ceil((Date.parse(startedAt) + minutes * 60_000 - now) / 1000))
  return <p className="focus-countdown" role="timer" aria-label="Time remaining">
    {Math.floor(seconds / 60)}:{String(seconds % 60).padStart(2, '0')}
  </p>
}

function FocusSession({ sessions, subjects, reload, timeZone }: {
  sessions: Session[]; subjects: Subject[]; reload: () => Promise<void>; timeZone: string
}) {
  const save = useMutation(reload)
  const active = sessions.find(item => item.state === 'ACTIVE')
  const last = sessions.find(item => item.state === 'COMPLETED')
  const [breakId, setBreakId] = useState<string>()
  useEffect(() => {
    if (!last?.completedAt || last.breakMinutes === 0) return
    const end = Date.parse(last.completedAt) + last.breakMinutes * 60_000
    const timer = window.setTimeout(() => setBreakId(last.id), Math.max(0, end - Date.now()))
    return () => window.clearTimeout(timer)
  }, [last])
  const showBreak = last?.completedAt && last.breakMinutes > 0
    && Date.parse(last.completedAt) + last.breakMinutes * 60_000 > Date.now() && breakId !== last.id
  return <section className="workspace-panel">
    <h2>{active ? 'Current session' : showBreak ? 'Break' : 'Start a focus session'}</h2>
    {active ? <>
      <p>Started {displayInstant(active.startedAt, timeZone)}.</p>
      <Countdown startedAt={active.startedAt} minutes={active.focusMinutes} />
      <button disabled={save.busy} onClick={() => void save.run(
        () => write('/focus/sessions/' + active.id + '/complete', 'POST'), 'Session recorded.',
      )}>Finish focus session</button>
    </> : <>
      {showBreak && last.completedAt && <>
        <Countdown startedAt={last.completedAt} minutes={last.breakMinutes} />
        <button type="button" className="button-secondary" onClick={() => setBreakId(last.id)}>Skip break</button>
      </>}
      {!showBreak && <form className="auth-form" onSubmit={event => {
        event.preventDefault()
        const fields = new FormData(event.currentTarget)
        void save.run(() => write('/focus/sessions', 'POST', {
          subjectId: field(fields, 'subjectId') || null,
          focusMinutes: Number(field(fields, 'focusMinutes')), breakMinutes: Number(field(fields, 'breakMinutes')),
        }), 'Focus session started.')
      }}><fieldset disabled={save.busy}>
        <div className="workspace-row">
          <Field label="Subject"><select name="subjectId"><SubjectOptions subjects={subjects} /></select></Field>
          <Field label="Focus minutes"><input type="number" name="focusMinutes" required min={1} max={180} defaultValue={25} /></Field>
          <Field label="Break minutes"><input type="number" name="breakMinutes" required min={0} max={60} defaultValue={5} /></Field>
        </div>
        <button>Start focus session</button>
      </fieldset></form>}
    </>}
    <Feedback value={save.feedback} />
  </section>
}

function DailyGoal({ summary, preferences, reload }: {
  summary: Summary; preferences: Preferences; reload: () => Promise<void>
}) {
  const save = useMutation(reload)
  return <section className="workspace-panel">
    <h2>Today’s focus goal</h2>
    <p>{summary.completedMinutes} minutes recorded for {summary.date} ({summary.timeZone}).
      {summary.dailyFocusGoalMinutes === null ? ' No daily goal set.' : ' Goal: ' + summary.dailyFocusGoalMinutes + ' minutes.'}</p>
    <form className="auth-form" onSubmit={event => {
      event.preventDefault()
      const minutes = field(new FormData(event.currentTarget), 'goal')
      void save.run(() => write('/me/preferences', 'PUT', {
        ...preferences, language: preferences.language ?? 'en', timeZone: preferences.timeZone ?? summary.timeZone,
        dailyFocusGoalMinutes: minutes ? Number(minutes) : null,
      }), 'Daily focus goal saved.')
    }}><fieldset disabled={save.busy}>
      <Field label="Daily focus goal in minutes"><input name="goal" type="number" min={1} max={1440}
        defaultValue={summary.dailyFocusGoalMinutes ?? ''} /></Field>
      <button>Save daily focus goal</button>
    </fieldset><Feedback value={save.feedback} /></form>
  </section>
}

export function FocusPage() {
  const load = useResource(loadFocus)
  if (!load.data) return <><FeatureHeader title="Focus" description="Set a timer, track your study sessions, and work toward a daily goal." /><Pending {...load} /></>
  const [sessions, summary, preferences, subjects] = load.data
  const completed = sessions.filter(item => item.state === 'COMPLETED')
  return <>
    <FeatureHeader title="Focus" description="Set a timer, track your study sessions, and work toward a daily goal." />
    <div className="feature-columns focus-workspace">
      <FocusSession sessions={sessions} subjects={subjects} reload={load.reload} timeZone={summary.timeZone} />
      <DailyGoal summary={summary} preferences={preferences} reload={load.reload} />
    </div>
    <section className="workspace-panel">
      <h2>Session history</h2>
      {completed.length ? <ul>{completed.map(item => {
        const name = subjects.find(subject => subject.id === item.subjectId)?.name
        const elapsed = Math.max(0, Math.min(item.focusMinutes, Math.floor(
          (Date.parse(item.completedAt ?? item.startedAt) - Date.parse(item.startedAt)) / 60_000,
        )))
        return <li key={item.id}>{displayInstant(item.startedAt, summary.timeZone)} · {elapsed} minutes{name && ' · ' + name}</li>
      })}</ul> : <p className="empty-state">No completed focus sessions yet.</p>}
    </section>
  </>
}
