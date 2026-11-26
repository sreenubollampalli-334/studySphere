import { FeatureHeader } from '../../components/FeatureHeader'
import { useCallback, useState } from 'react'
import { all, Feedback, field, Field, localDate, Pending, read, SubjectOptions, useMutation, useResource, write } from './common'
import type { Preferences, Subject } from './common'

interface Exam {
  id: string; subjectId: string; title: string; examDate: string; timeZone: string; topicIds: string[]
  syllabus: string | null; goal: string | null; version: number
}

const loadExams = () => Promise.all([all<Exam>('/exams'), all<Subject>('/subjects'), read<Preferences>('/me/preferences')])

function ExamForm({ exam, subjects, timeZone, reload }: { exam?: Exam; subjects: Subject[]; timeZone: string; reload: () => Promise<void> }) {
  const save = useMutation(reload)
  const [subjectId, setSubjectId] = useState(exam?.subjectId ?? '')
  const [topicIds, setTopicIds] = useState(exam?.topicIds ?? [])
  const loadTopics = useCallback(() => subjectId ? all<Subject>('/subjects/' + subjectId + '/topics') : Promise.resolve([]), [subjectId])
  const topics = useResource(loadTopics)
  const path = exam ? '/exams/' + exam.id : '/exams'
  return <form className="auth-form" onSubmit={event => {
    event.preventDefault()
    const form = event.currentTarget
    const fields = new FormData(form)
    void save.run(() => write(path, exam ? 'PATCH' : 'POST', {
      title: field(fields, 'title'), subjectId, examDate: field(fields, 'examDate'),
      timeZone: field(fields, 'timeZone'), topicIds, syllabus: field(fields, 'syllabus'),
      goal: field(fields, 'goal'), ...(exam ? { version: exam.version } : {}),
    })).then(saved => {
      if (saved && !exam) {
        form.reset()
        setSubjectId('')
        setTopicIds([])
      }
    })
  }}>
    <fieldset disabled={save.busy}>
      <Field label={exam ? 'Exam title' : 'New exam title'}><input name="title" required defaultValue={exam?.title} maxLength={150} /></Field>
      <div className="workspace-row">
        <Field label="Subject"><select required value={subjectId} onChange={event => {
          setSubjectId(event.target.value)
          setTopicIds([])
        }}><SubjectOptions subjects={subjects} required /></select></Field>
        <Field label="Exam date"><input name="examDate" type="date" required defaultValue={exam?.examDate} /></Field>
        <Field label="Exam timezone"><input name="timeZone" required defaultValue={exam?.timeZone ?? timeZone} /></Field>
      </div>
      {subjectId && <fieldset className="workspace-check-list"><legend>Topics</legend>
        {topics.error ? <Pending {...topics} /> : !topics.data ? <p role="status">Loading topics…</p>
          : topics.data.length ? topics.data.map(topic => <label className="workspace-check" key={topic.id}>
            <input type="checkbox" checked={topicIds.includes(topic.id)} onChange={event => {
              const checked = event.target.checked
              setTopicIds(ids => checked ? [...ids, topic.id] : ids.filter(id => id !== topic.id))
            }} />{topic.name}</label>) : <p className="empty-state">No topics in this subject.</p>}
      </fieldset>}
      <Field label="Syllabus"><textarea name="syllabus" rows={3} maxLength={4000} defaultValue={exam?.syllabus ?? ''} /></Field>
      <Field label="Preparation goal"><input name="goal" maxLength={500} defaultValue={exam?.goal ?? ''} /></Field>
      <div className="workspace-actions"><button>{exam ? 'Save exam' : 'Add exam'}</button>
        {exam && <button type="button" className="button-secondary" onClick={() => {
          if (window.confirm('Delete this exam?')) void save.run(() => write(path, 'DELETE'), 'Exam deleted.')
        }}>Delete exam</button>}</div>
    </fieldset>
    <Feedback value={save.feedback} />
  </form>
}

function countdown(exam: Exam) {
  const today = localDate(new Date(), exam.timeZone)
  const days = Math.round((Date.parse(exam.examDate) - Date.parse(today)) / 86_400_000)
  if (days === 0) return 'Today'
  return days < 0 ? 'Past exam' : days === 1 ? 'Tomorrow' : days + ' days left'
}

export function ExamsPage() {
  const load = useResource(loadExams)
  if (!load.data) return <><FeatureHeader title="Exam center" description="Track exam dates and the topics you need to prepare." /><Pending {...load} /></>
  const [exams, subjects, preferences] = load.data
  const zone = preferences.timeZone ?? 'UTC'
  return <>
    <FeatureHeader title="Exam center" description="Track exam dates and the topics you need to prepare." />
    <section className="workspace-panel"><h2>Your exams</h2>
      {!exams.length && <p className="empty-state">No exams scheduled.</p>}
      {exams.map(exam => <details className="productivity-details" key={exam.id}>
        <summary>{exam.title} · {countdown(exam)}</summary>
        <p>{exam.examDate} · {exam.timeZone}</p>
        <ExamForm key={exam.version} exam={exam} subjects={subjects} timeZone={zone} reload={load.reload} />
      </details>)}
    </section>
    <section className="workspace-panel"><h2>Schedule an exam</h2>
      <ExamForm subjects={subjects} timeZone={zone} reload={load.reload} />
    </section>
  </>
}
