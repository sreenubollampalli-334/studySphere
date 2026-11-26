import { FeatureHeader } from '../../components/FeatureHeader'
import { Pending, read, useResource } from '../productivity/common'

interface Overview {
  timeZone: string
  focusSeconds: number
  completedTasks: number
  quizAttempts: number
  correctAnswers: number
  totalQuestions: number
  flashcardReviews: number
  completedGoals: number
  totalGoals: number
  daily: { date: string; focusSeconds: number; quizAttempts: number; flashcardReviews: number }[]
  quizTrend: { date: string; score: number; total: number }[]
}

interface Achievements {
  xp: number
  level: number
  streak: { currentDays: number; longestDays: number }
  badges: { code: string; title: string; description: string; earned: boolean }[]
  weeklyChallenge: { title: string; progress: number; target: number; weekStart: string; completed: boolean }
}

const loadOverview = () => read<Overview>('/analytics/overview')
const loadAchievements = () => read<Achievements>('/achievements')

const percent = (score: number, total: number) => total > 0 ? `${Math.round(score / total * 100)}%` : 'No graded questions yet'

function duration(seconds: number) {
  if (seconds > 0 && seconds < 60) return 'Less than a minute'
  const minutes = Math.floor(seconds / 60)
  const hours = Math.floor(minutes / 60)
  return hours ? `${hours} hr ${minutes % 60} min` : `${minutes} min`
}

function dateLabel(date: string) {
  return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeZone: 'UTC' }).format(
    new Date(date.slice(0, 10) + 'T00:00:00Z'),
  )
}

export function AnalyticsPage() {
  const load = useResource(loadOverview)
  const data = load.data
  const peakFocus = Math.max(0, ...(data?.daily.map(day => day.focusSeconds) ?? []))
  return <><FeatureHeader title="Study analytics" description="Review your study time, task completion, and quiz results." />
    {!data ? <Pending {...load} /> : <>
      <section className="workspace-panel">
        <h2>Study activity</h2>
        <p>Calendar dates use {data.timeZone}.</p>
        <dl className="engagement-metrics personal-metrics">
          <div><dt>Focus time</dt><dd>{duration(data.focusSeconds)}</dd></div>
          <div><dt>Tasks completed</dt><dd>{data.completedTasks}</dd></div>
          <div><dt>Quiz attempts</dt><dd>{data.quizAttempts}</dd></div>
          <div><dt>Quiz accuracy</dt><dd>{percent(data.correctAnswers, data.totalQuestions)}</dd></div>
          <div><dt>Flashcard reviews</dt><dd>{data.flashcardReviews}</dd></div>
          <div><dt>Study goals completed</dt><dd>{data.completedGoals} of {data.totalGoals}</dd></div>
        </dl>
      </section>
      <section className="workspace-panel">
        <h2>Daily activity</h2>
        <p>Last {data.daily.length} days, including today.</p>
        {peakFocus > 0 && <><p className="activity-chart-label">Focus time by day</p><div className="activity-chart" aria-hidden="true">
          {data.daily.map(day => <span key={day.date} title={`${dateLabel(day.date)} · ${duration(day.focusSeconds)}`}>
            <span style={{ height: `${day.focusSeconds / peakFocus * 100}%` }} />
          </span>)}
        </div></>}
        {data.daily.some(day => day.focusSeconds || day.quizAttempts || day.flashcardReviews) ?
          <div className="engagement-table" role="region" aria-label="Daily activity table" tabIndex={0}><table>
            <thead><tr><th scope="col">Date</th><th scope="col">Focus time</th><th scope="col">Quiz attempts</th>
              <th scope="col">Card reviews</th></tr></thead>
            <tbody>{data.daily.map(day => <tr key={day.date}><th scope="row">{dateLabel(day.date)}</th>
              <td>{duration(day.focusSeconds)}</td><td>{day.quizAttempts}</td><td>{day.flashcardReviews}</td></tr>)}</tbody>
          </table></div> : <p className="empty-state">No daily activity yet.</p>}
      </section>
      <section className="workspace-panel">
        <h2>Quiz trend</h2>
        {data.quizTrend.length ? <ul className="engagement-list">{data.quizTrend.map((day, index) => <li key={`${day.date}-${index}`}>
          <strong>{dateLabel(day.date)}</strong> <span>{day.score} of {day.total} correct ({percent(day.score, day.total)})</span>
          {day.total > 0 ? <meter aria-label={`Quiz accuracy on ${dateLabel(day.date)}`} min={0} max={day.total} value={day.score} /> : null}
        </li>)}</ul> : <p>Complete a quiz to start tracking scores.</p>}
      </section>
    </>}
  </>
}

export function AchievementsPage() {
  const load = useResource(loadAchievements)
  const data = load.data
  return <><FeatureHeader title="Achievements" description="Track your badges and progress toward this week’s challenge." />
    {!data ? <Pending {...load} /> : <>
      <div className="feature-columns achievement-overview">
      <section className="workspace-panel achievement-progress">
        <h2>Your progress</h2>
        <dl className="engagement-metrics">
          <div><dt>Experience points</dt><dd>{data.xp} XP</dd></div>
          <div><dt>Level</dt><dd>{data.level}</dd></div>
          <div><dt>Current streak</dt><dd>{data.streak.currentDays} days</dd></div>
          <div><dt>Longest streak</dt><dd>{data.streak.longestDays} days</dd></div>
        </dl>
      </section>
      <section className="workspace-panel achievement-challenge">
        <h2>Weekly challenge</h2>
        <h3>{data.weeklyChallenge.title}</h3>
        <p>Week of {dateLabel(data.weeklyChallenge.weekStart)}</p>
        <p>{data.weeklyChallenge.progress} of {data.weeklyChallenge.target}
          {data.weeklyChallenge.completed ? ' · Completed' : ''}</p>
        <progress aria-label="Weekly challenge progress" max={data.weeklyChallenge.target}
          value={Math.min(data.weeklyChallenge.progress, data.weeklyChallenge.target)} />
      </section>
      </div>
      <section className="workspace-panel">
        <h2>Badges</h2>
        {data.badges.length ? <ul className="engagement-list feature-card-grid achievement-badges">{data.badges.map(badge => <li key={badge.code} data-earned={badge.earned}>
          <div className="badge-top"><span className="badge-mark" aria-hidden="true">{badge.earned ? '✦' : '◇'}</span>
            <span className="personal-status">{badge.earned ? 'Earned' : 'In progress'}</span></div>
          <strong>{badge.title}</strong>
          <p>{badge.description}</p>
        </li>)}</ul> : <p className="empty-state">No badges available yet.</p>}
      </section>
    </>}
  </>
}
