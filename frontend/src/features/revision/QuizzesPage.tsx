import { FeatureHeader } from '../../components/FeatureHeader'
import { useCallback, useState } from 'react'
import { all, Feedback, Field, field, Pending, read, useMutation, useResource, write } from '../productivity/common'
import { RevisionSource } from './RevisionSource'

interface Summary { id: string; title: string }
interface Question { id: string; prompt: string; options: string[] }
interface Quiz extends Summary { questions: Question[] }
interface Result { questionId: string; selectedIndex: number; correctIndex: number; correct: boolean; explanation: string }
interface Attempt { id: string; status: string; score: number | null; total: number; results: Result[] }

const loadQuizzes = () => all<Summary>('/quizzes')

export function QuizzesPage() {
  const quizzes = useResource(loadQuizzes)
  const [selected, setSelected] = useState('')
  const generation = useMutation(quizzes.reload)
  return <>
    <FeatureHeader title="Quizzes" description="Generate practice questions and revisit your results." />
    <form className="workspace-form study-tool-form" aria-busy={generation.busy} onSubmit={event => {
      event.preventDefault()
      const form = new FormData(event.currentTarget)
      void generation.run(async () => {
        const topicId = field(form, 'topicId')
        const documentId = field(form, 'documentId')
        const response = await write('/quizzes/generate', 'POST', {
          title: field(form, 'title'), count: Number(field(form, 'count')),
          ...(topicId && { topicId }), ...(documentId && { documentId }),
        })
        setSelected((await response.json() as Summary).id)
      }, 'Quiz created.')
    }}>
      <h2>Generate a quiz</h2>
      <fieldset disabled={generation.busy}>
        <Field label="Quiz title"><input name="title" required maxLength={120} /></Field>
        <RevisionSource />
        <Field label="Number of questions"><input name="count" type="number" min={1} max={10} defaultValue={5} required /></Field>
        <button>{generation.busy ? 'Generating…' : 'Generate quiz'}</button>
      </fieldset>
    </form>
    {generation.busy && <p role="status">Creating practice questions…</p>}
    <Feedback value={generation.feedback} />
    <section className="workspace-panel">
      <h2>Your quizzes</h2>
      {quizzes.data ? quizzes.data.length ? <ul className="workspace-list">{quizzes.data.map(item =>
        <li key={item.id}><button type="button" aria-pressed={selected === item.id}
          onClick={() => setSelected(item.id)}>{item.title}</button></li>)}</ul> : <p className="empty-state">No quizzes yet.</p>
        : <Pending error={quizzes.error} retry={quizzes.retry} />}
    </section>
    {selected && <QuizPractice key={selected} id={selected} />}
  </>
}

function QuizPractice({ id }: { id: string }) {
  const path = '/quizzes/' + id
  const load = useCallback(() => Promise.all([read<Quiz>(path), all<Attempt>(path + '/attempts')]), [path])
  const resource = useResource(load)
  const operation = useMutation(resource.reload)
  const [attempt, setAttempt] = useState<Attempt>()
  if (!resource.data) return <Pending error={resource.error} retry={resource.retry} />
  const [quiz, history] = resource.data
  const submitted = attempt && attempt.status === 'SUBMITTED'
  const active = attempt && !submitted
  return <section className="workspace-panel">
    <h2>{quiz.title}</h2>
    <Feedback value={operation.feedback} />
    {active ? <form className="workspace-form" onSubmit={event => {
      event.preventDefault()
      const form = new FormData(event.currentTarget)
      void operation.run(async () => {
        const response = await write('/attempts/' + attempt.id + '/submit', 'POST', {
          answers: quiz.questions.map(item => ({
            questionId: item.id, selectedIndex: Number(field(form, item.id)),
          })),
        })
        setAttempt(await response.json() as Attempt)
      }, 'Quiz graded.')
    }}>
      {quiz.questions.map(item => <fieldset key={item.id} disabled={operation.busy}>
        <legend>{item.prompt}</legend>
        {item.options.map((option, index) => <label key={index}>
          <input type="radio" name={item.id} value={index} required /> {option}
        </label>)}
      </fieldset>)}
      <button disabled={operation.busy}>Submit answers</button>
    </form> : <button disabled={operation.busy} onClick={() => {
      void operation.run(async () => {
        setAttempt(await (await write(path + '/attempts', 'POST')).json() as Attempt)
      }, 'Attempt started.')
    }}>Start quiz</button>}
    {submitted && <section className="workspace-panel">
      <h3>Score {attempt.score} / {attempt.total}</h3>
      <ol className="workspace-list">{attempt.results.map(result => {
        const question = quiz.questions.find(item => item.id === result.questionId)
        return <li key={result.questionId}><strong>{question?.prompt}</strong>
          <p>{result.correct ? 'Correct' : 'Incorrect'}. Correct answer: {question?.options[result.correctIndex]}</p>
          {result.explanation && <p>{result.explanation}</p>}
        </li>
      })}</ol>
    </section>}
    <h3>Attempt history</h3>
    {history.length ? <ul className="workspace-list">{history.map(item => <li key={item.id}>
      <button type="button" disabled={operation.busy} onClick={() => {
        void operation.run(async () => setAttempt(await read<Attempt>('/attempts/' + item.id)), 'Attempt loaded.')
      }}>{item.status === 'SUBMITTED' ? 'Review score ' + item.score + ' / ' + item.total : 'Resume attempt'} · {item.id}</button>
    </li>)}</ul> : <p className="empty-state">No attempts yet.</p>}
  </section>
}
