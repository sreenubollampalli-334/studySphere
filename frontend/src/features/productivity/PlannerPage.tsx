import { FeatureHeader } from '../../components/FeatureHeader'
import { lazy, Suspense, useState } from 'react'
import { all, displayInstant, Feedback, field, Field, instant, localDate, localDateTime, Pending, read, SubjectOptions, useMutation, useResource, write } from './common'
import type { Preferences, Subject } from './common'

const PlanProposals = lazy(() => import('../adaptive/AdaptiveLearning').then(module => ({ default: module.PlanProposals })))

interface Plan { id: string; title: string; subjectId: string | null; startDate: string | null; endDate: string | null; version: number }
interface Task {
  id: string; title: string; planId: string | null; parentId: string | null; priority: string
  state: string; dueAt: string | null; recurrenceRule: string | null; recurrenceZone: string | null; version: number
}
interface Reminder { id: string; scheduledAt: string; state: string }

const loadPlanner = () => Promise.all([
  all<Plan>('/plans'), all<Task>('/tasks'), all<Subject>('/subjects'), read<Preferences>('/me/preferences'),
])

function PlanForm({ plan, subjects, reload }: { plan?: Plan; subjects: Subject[]; reload: () => Promise<void> }) {
  const save = useMutation(reload)
  const path = plan ? '/plans/' + plan.id : '/plans'
  return <form className="auth-form" onSubmit={event => {
    event.preventDefault()
    const form = event.currentTarget
    const fields = new FormData(form)
    void save.run(() => write(path, plan ? 'PATCH' : 'POST', {
      title: field(fields, 'title'), subjectId: field(fields, 'subjectId'),
      startDate: field(fields, 'startDate'), endDate: field(fields, 'endDate'),
      ...(plan ? { version: plan.version } : {}),
    })).then(saved => { if (saved && !plan) form.reset() })
  }}>
    <fieldset disabled={save.busy}>
      <Field label={plan ? 'Plan title' : 'New plan title'}><input name="title" required defaultValue={plan?.title} maxLength={160} /></Field>
      <div className="workspace-row">
        <Field label="Subject"><select name="subjectId" defaultValue={plan?.subjectId ?? ''}><SubjectOptions subjects={subjects} /></select></Field>
        <Field label="Start date"><input name="startDate" type="date" defaultValue={plan?.startDate ?? ''} /></Field>
        <Field label="End date"><input name="endDate" type="date" defaultValue={plan?.endDate ?? ''} /></Field>
      </div>
      <div className="workspace-actions"><button>{plan ? 'Save plan' : 'Add plan'}</button>
        {plan && <button className="button-secondary" type="button" onClick={() => {
          if (window.confirm('Delete this plan?')) void save.run(() => write(path, 'DELETE'), 'Plan deleted.')
        }}>Delete plan</button>}</div>
    </fieldset>
    <Feedback value={save.feedback} />
  </form>
}

function Reminders({ taskId, timeZone }: { taskId: string; timeZone: string }) {
  const [items, setItems] = useState<Reminder[]>()
  const [failure, setFailure] = useState(false)
  const path = '/tasks/' + taskId + '/reminders'
  const reload = async () => {
    setItems(await all<Reminder>(path))
    setFailure(false)
  }
  const save = useMutation(reload)
  return <details className="productivity-details" onToggle={event => {
    if (event.currentTarget.open && !items) void reload().catch(() => setFailure(true))
  }}>
    <summary>Reminders</summary>
    {failure ? <p role="alert">Could not load reminders. Close and reopen to retry.</p>
      : !items ? <p role="status">Loading reminders…</p>
      : items.length ? <ul>{items.map(item => <li key={item.id} className="workspace-actions">
        <span>{displayInstant(item.scheduledAt, timeZone)}</span>
        <button type="button" className="button-secondary" disabled={save.busy}
          onClick={() => void save.run(() => write(path + '/' + item.id, 'DELETE'), 'Reminder removed.')}>Remove reminder</button>
      </li>)}</ul> : <p className="empty-state">No reminders set.</p>}
    <form className="auth-form" onSubmit={event => {
      event.preventDefault()
      const form = event.currentTarget
      const fields = new FormData(form)
      void save.run(() => write(path, 'POST', { scheduledAt: instant(field(fields, 'scheduledAt'), timeZone) }), 'Reminder scheduled.')
        .then(saved => { if (saved) form.reset() })
    }}><fieldset disabled={save.busy}>
      <Field label={'Remind at (' + timeZone + ')'}><input name="scheduledAt" type="datetime-local" required /></Field>
      <button>Schedule reminder</button>
    </fieldset><Feedback value={save.feedback} /></form>
  </details>
}

function TaskForm({ task, plans, tasks, timeZone, reload }: {
  task?: Task; plans: Plan[]; tasks: Task[]; timeZone: string; reload: () => Promise<void>
}) {
  const save = useMutation(reload)
  const path = task ? '/tasks/' + task.id : '/tasks'
  const originalLocalDue = task?.dueAt ? localDateTime(new Date(task.dueAt), timeZone) : ''
  return <form className="auth-form" onSubmit={event => {
    event.preventDefault()
    const form = event.currentTarget
    const fields = new FormData(form)
    const recurrenceRule = field(fields, 'recurrenceRule')
    const localDue = field(fields, 'dueAt')
    void save.run(() => write(path, task ? 'PATCH' : 'POST', {
      title: field(fields, 'title'), planId: field(fields, 'planId'),
      parentId: field(fields, 'parentId'),
      dueAt: task?.dueAt && localDue === originalLocalDue ? task.dueAt : instant(localDue, timeZone),
      priority: field(fields, 'priority'), recurrenceRule: recurrenceRule,
      recurrenceZone: recurrenceRule ? field(fields, 'recurrenceZone') : '',
      ...(task ? { version: task.version } : {}),
    })).then(saved => { if (saved && !task) form.reset() })
  }}>
    <fieldset disabled={save.busy}>
      <Field label={task ? 'Task title' : 'New task title'}><input name="title" required defaultValue={task?.title} maxLength={160} /></Field>
      <div className="workspace-row">
        <Field label="Plan"><select name="planId" defaultValue={task?.planId ?? ''}><option value="">No plan</option>
          {plans.map(plan => <option key={plan.id} value={plan.id}>{plan.title}</option>)}</select></Field>
        <Field label="Parent task"><select name="parentId" defaultValue={task?.parentId ?? ''}><option value="">No parent</option>
          {tasks.filter(item => item.id !== task?.id).map(item => <option key={item.id} value={item.id}>{item.title}</option>)}</select></Field>
        <Field label="Priority"><select name="priority" defaultValue={task?.priority ?? 'MEDIUM'}>
          <option value="LOW">Low</option><option value="MEDIUM">Medium</option><option value="HIGH">High</option>
        </select></Field>
      </div>
      <div className="workspace-row">
        <Field label={'Due at (' + timeZone + ')'}><input name="dueAt" type="datetime-local"
          defaultValue={originalLocalDue} /></Field>
        <Field label="Repeat"><select name="recurrenceRule" defaultValue={task?.recurrenceRule ?? ''}>
          <option value="">Never</option><option value="DAILY">Daily</option><option value="WEEKLY">Weekly</option>
        </select></Field>
        <Field label="Recurrence timezone"><input name="recurrenceZone" defaultValue={task?.recurrenceZone ?? timeZone} /></Field>
      </div>
      <div className="workspace-actions"><button>{task ? 'Save task' : 'Add task'}</button>
        {task && task.state === 'OPEN' && <button type="button" className="button-secondary"
          onClick={() => void save.run(() => write(path, 'PATCH', { version: task.version, state: 'COMPLETED' }), 'Task marked complete.')}>Complete task</button>}
        {task && <button type="button" className="button-secondary" onClick={() => {
          if (window.confirm('Delete this task?')) void save.run(() => write(path, 'DELETE'), 'Task deleted.')
        }}>Delete task</button>}
      </div>
    </fieldset>
    <Feedback value={save.feedback} />
  </form>
}

function Calendar({ tasks, timeZone }: { tasks: Task[]; timeZone: string }) {
  const [date, setDate] = useState(() => localDate(new Date(), timeZone))
  const [mode, setMode] = useState('day')
  const [status, setStatus] = useState('')
  const daysInRange = mode === 'week' ? 7 : 1
  function offsetDay(offset: number) {
    const day = new Date(date + 'T00:00:00Z')
    day.setUTCDate(day.getUTCDate() + offset)
    return day.toISOString().slice(0, 10)
  }
  const days = date ? Array.from({ length: daysInRange }, (_, offset) => offsetDay(offset)) : []
  const dueByDay = new Map(days.map(day => [day, [] as (Task & { dueAt: string })[]]))
  for (const task of tasks) {
    const dueAt = task.dueAt
    if (!dueAt || (status && task.state !== status)) continue
    dueByDay.get(localDate(new Date(dueAt), timeZone))?.push({ ...task, dueAt })
  }
  return <section className="workspace-panel">
    <h2>Calendar</h2>
    <div className="workspace-row">
      <Field label="First day"><input type="date" value={date} onChange={event => setDate(event.target.value)} /></Field>
      <Field label="Range"><select value={mode} onChange={event => setMode(event.target.value)}><option value="day">Day</option><option value="week">7 days</option></select></Field>
      <Field label="Task status"><select value={status} onChange={event => setStatus(event.target.value)}><option value="">All</option><option value="OPEN">Open</option><option value="COMPLETED">Completed</option></select></Field>
    </div>
    <div className="workspace-actions calendar-controls">
      <button type="button" className="button-secondary" disabled={!date} onClick={() => setDate(offsetDay(-daysInRange))}>Previous</button>
      <button type="button" className="button-secondary" onClick={() => setDate(localDate(new Date(), timeZone))}>Today</button>
      <button type="button" className="button-secondary" disabled={!date} onClick={() => setDate(offsetDay(daysInRange))}>Next</button>
    </div>
    <p>Calendar times use {timeZone}.</p>
    {days.some(day => dueByDay.get(day)?.length) ? <div className={mode === 'week' ? 'calendar-week' : ''}>
      {days.map(day => {
        const due = (dueByDay.get(day) ?? []).sort((left, right) => left.dueAt.localeCompare(right.dueAt))
        return <section className="calendar-day" key={day}>
          <h3>{new Intl.DateTimeFormat(undefined, { timeZone: 'UTC', weekday: 'short', month: 'short', day: 'numeric' })
            .format(new Date(day + 'T00:00:00Z'))}</h3>
          {due.length ? <ul>{due.map(task => <li key={task.id}>
            {task.title} · {displayInstant(task.dueAt, timeZone)}{task.state === 'COMPLETED' ? ' · Completed' : ''}
          </li>)}</ul> : <p className="empty-state">No tasks due.</p>}
        </section>
      })}
    </div> : <p className="empty-state">No due tasks in this range.</p>}
  </section>
}

export function PlannerPage() {
  const load = useResource(loadPlanner)
  const [showProposals, setShowProposals] = useState(false)
  if (!load.data) return <><FeatureHeader title="Study planner" description="See what’s due and organize tasks into study plans." /><Pending {...load} /></>
  const [plans, tasks, subjects, preferences] = load.data
  const timeZone = preferences.timeZone ?? 'UTC'
  return <>
    <FeatureHeader title="Study planner" description="See what’s due and organize tasks into study plans.">
      <button className="button-secondary" aria-expanded={showProposals}
        onClick={() => setShowProposals(current => !current)}>
        {showProposals ? 'Hide study plan proposals' : 'Show study plan proposals'}
      </button>
    </FeatureHeader>
    {showProposals && <Suspense fallback={<p role="status">Loading proposals…</p>}>
      <PlanProposals onApproved={load.reload} timeZone={timeZone} />
    </Suspense>}
    <Calendar tasks={tasks} timeZone={timeZone} />
    <div className="feature-columns">
    <section className="workspace-panel"><h2>Study plans</h2>
      {!plans.length && <p className="empty-state">No study plans yet.</p>}
      {plans.map(plan => <details key={plan.id} className="productivity-details"><summary>{plan.title}</summary>
        <PlanForm key={plan.version} plan={plan} subjects={subjects} reload={load.reload} />
      </details>)}
      <PlanForm subjects={subjects} reload={load.reload} />
    </section>
    <section className="workspace-panel"><h2>Tasks</h2>
      {!tasks.length && <p className="empty-state">No tasks yet.</p>}
      {tasks.map(task => <details key={task.id} className="productivity-details">
        <summary>{task.title} · {task.state === 'OPEN' ? 'Open' : 'Completed'}</summary>
        {task.dueAt && <p>Due {displayInstant(task.dueAt, timeZone)}</p>}
        <TaskForm key={task.version} task={task} plans={plans} tasks={tasks} timeZone={timeZone} reload={load.reload} />
        <Reminders taskId={task.id} timeZone={timeZone} />
      </details>)}
      <TaskForm plans={plans} tasks={tasks} timeZone={timeZone} reload={load.reload} />
    </section>
    </div>
  </>
}
