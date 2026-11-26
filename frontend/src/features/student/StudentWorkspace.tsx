import { FeatureHeader } from '../../components/FeatureHeader'
import { lazy, Suspense, useEffect, useRef, useState } from 'react'
import type { FormEvent, ReactNode } from 'react'
import { authenticatedRequest, messageFor } from '../auth/authApi'
import { PlannerPage } from '../productivity/PlannerPage'
import { ExamsPage } from '../productivity/ExamsPage'
import { FocusPage } from '../productivity/FocusPage'
import { ResourcesPage } from '../resources/ResourcesPage'
import { DocumentPage, DocumentsPage, isDocumentPath } from '../documents/DocumentsPage'
import { PartnersPage } from '../network/PartnersPage'
import { MessagesPage } from '../network/MessagesPage'

import { RoomPage, RoomsPage } from '../rooms/RoomsPage'

const MentorPage = lazy(() => import('../mentor/MentorPage').then((module) => ({ default: module.MentorPage })))
const QuizzesPage = lazy(() => import('../revision/QuizzesPage').then(module => ({ default: module.QuizzesPage })))
const FlashcardsPage = lazy(() => import('../revision/FlashcardsPage').then(module => ({ default: module.FlashcardsPage })))
const AdaptivePage = lazy(() => import('../adaptive/AdaptiveLearning').then(module => ({ default: module.AdaptivePage })))
const CommunitiesPage = lazy(() => import('../community/CommunitiesPage').then(module => ({ default: module.CommunitiesPage })))
const CommunityPage = lazy(() => import('../community/CommunitiesPage').then(module => ({ default: module.CommunityPage })))
const AnalyticsPage = lazy(() => import('../engagement/EngagementPages').then(module => ({ default: module.AnalyticsPage })))
const AchievementsPage = lazy(() => import('../engagement/EngagementPages').then(module => ({ default: module.AchievementsPage })))
const NotificationsPage = lazy(() => import('../engagement/NotificationsPage').then(module => ({ default: module.NotificationsPage })))
const AdminPage = lazy(() => import('../admin/AdminPage').then(module => ({ default: module.AdminPage })))

export const workspacePaths = ['/dashboard', '/onboarding', '/profile', '/settings', '/planner', '/exams', '/focus', '/resources', '/documents', '/partners', '/messages', '/rooms', '/ai-mentor', '/quizzes', '/flashcards', '/adaptive', '/communities', '/analytics', '/achievements', '/notifications', '/admin']
export const isRoomPath = (path: string) => /^\/rooms\/[1-9]\d*$/.test(path)
export const isCommunityPath = (path: string) => /^\/communities\/[1-9]\d*$/.test(path)

interface Profile {
  displayName: string | null
  institution: string | null
  program: string | null
  bio: string | null
  discoverable: boolean
  onboardingComplete: boolean
}

interface Preferences {
  language: string | null
  timeZone: string | null
  learningStyles: string[]
  skills: string[]
  dailyFocusGoalMinutes?: number | null
}

interface Subject { id: string; name: string }
interface Slot { day: string; start: string; end: string }
interface Goal { id: string; subjectId: string | null; description: string; targetDate: string | null; status: string }
interface Page<T> { items: T[]; hasNext: boolean }
interface Dashboard {
  onboardingComplete: boolean
  todayTasks: unknown[]
  upcomingExams: unknown[]
  recentResources: unknown[]
  activeRooms: unknown[]
  quizSummaries: unknown[]
  recommendations: unknown[]
  streak: number | null
}

async function read<T>(path: string): Promise<T> {
  return (await authenticatedRequest(path)).json() as Promise<T>
}

async function write(path: string, method: string, data?: unknown) {
  await authenticatedRequest(path, {
    method,
    ...(data === undefined ? {} : { headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data) }),
  })
}

async function paged<T>(path: string) {
  const items: T[] = []
  for (let page = 0; ; page++) {
    const result = await read<Page<T>>(path + '?page=' + page + '&size=100')
    items.push(...result.items)
    if (!result.hasNext) return items
  }
}

function useLoad<T>(load: () => Promise<T>) {
  const [value, setValue] = useState<T>()
  const [error, setError] = useState('')
  const [attempt, setAttempt] = useState(0)
  useEffect(() => {
    let active = true
    load().then(
      (data) => { if (active) setValue(data) },
      (failure: unknown) => { if (active) setError(messageFor(failure, 'We couldn’t load this page.')) },
    )
    return () => { active = false }
  }, [load, attempt])
  return {
    value, error,
    retry: () => { setError(''); setValue(undefined); setAttempt((previous) => previous + 1) },
  }
}

function useSave() {
  const [saving, setSaving] = useState(false)
  const [feedback, setFeedback] = useState({ error: false, text: '' })
  async function save(action: () => Promise<void>, failureMessage = 'We couldn’t save your changes. Check your entries and try again.') {
    setFeedback({ error: false, text: '' })
    setSaving(true)
    try {
      await action()
      setFeedback({ error: false, text: 'Changes saved.' })
    } catch (failure) {
      setFeedback({ error: true, text: messageFor(failure, failureMessage) })
    } finally {
      setSaving(false)
    }
  }
  return { saving, save, feedback }
}

function Feedback({ feedback }: { feedback: { error: boolean; text: string } }) {
  return feedback.text && <p className={feedback.error ? 'notice notice-error' : 'notice'}
    role={feedback.error ? 'alert' : 'status'}>{feedback.text}</p>
}

function Pending({ error, retry }: { error: string; retry: () => void }) {
  return error
    ? <><p className="notice notice-error" role="alert">{error}</p><button onClick={retry}>Try again</button></>
    : <p role="status">Loading…</p>
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return <label className="workspace-field"><span>{label}</span>{children}</label>
}

const loadProfile = () => read<Profile>('/me/profile')
const loadSetup = () => Promise.all([
  loadProfile(), read<Preferences>('/me/preferences'), paged<Subject>('/subjects'),
  read<{ subjectIds: string[] }>('/me/subjects'),
])
const loadSettings = () => Promise.all([
  read<Preferences>('/me/preferences'), read<{ slots: Slot[] }>('/me/availability'),
  paged<Subject>('/subjects'), paged<Goal>('/me/goals'),
])
const loadDashboard = () => read<Dashboard>('/dashboard')

function profileValues(form: FormData) {
  return {
    displayName: String(form.get('displayName') ?? '').trim(),
    institution: String(form.get('institution') ?? '').trim(),
    program: String(form.get('program') ?? '').trim(),
    bio: String(form.get('bio') ?? '').trim(),
    discoverable: form.has('discoverable'),
  }
}

function ProfileFields({ profile }: { profile: Profile }) {
  return <>
    <Field label="Display name"><input name="displayName" defaultValue={profile.displayName ?? ''} required maxLength={100} /></Field>
    <Field label="Institution"><input name="institution" defaultValue={profile.institution ?? ''} maxLength={160} /></Field>
    <Field label="Program"><input name="program" defaultValue={profile.program ?? ''} maxLength={160} /></Field>
    <Field label="Bio"><textarea name="bio" defaultValue={profile.bio ?? ''} rows={3} maxLength={1200} /></Field>
    <label className="workspace-check"><input type="checkbox" name="discoverable" defaultChecked={profile.discoverable} /> Allow my profile to be discovered</label>
  </>
}

function preferenceValues(form: FormData) {
  const list = (name: string) => String(form.get(name) ?? '').split(',').map((item) => item.trim()).filter(Boolean)
  const focusGoal = String(form.get('dailyFocusGoalMinutes') ?? '')
  return {
    language: String(form.get('language') ?? '').trim(),
    timeZone: String(form.get('timeZone') ?? '').trim(),
    learningStyles: list('learningStyles'),
    skills: list('skills'),
    dailyFocusGoalMinutes: focusGoal ? Number(focusGoal) : null,
  }
}

function PreferenceFields({ prefs }: { prefs: Preferences }) {
  const browserZone = Intl.DateTimeFormat().resolvedOptions().timeZone
  return <>
    <Field label="Language code"><input name="language" defaultValue={prefs.language ?? 'en'} placeholder="en" required maxLength={35} /></Field>
    <Field label="Time zone"><input name="timeZone" defaultValue={prefs.timeZone ?? browserZone} placeholder="Asia/Kolkata" required maxLength={80} /></Field>
    <Field label="Learning styles (comma-separated)"><input name="learningStyles" defaultValue={prefs.learningStyles.join(', ')} /></Field>
    <Field label="Skills (comma-separated)"><input name="skills" defaultValue={prefs.skills.join(', ')} /></Field>
    <Field label="Daily focus goal in minutes"><input name="dailyFocusGoalMinutes" type="number" min={1} max={1440}
      defaultValue={prefs.dailyFocusGoalMinutes ?? ''} /></Field>
  </>
}

function ProfileScreen() {
  const load = useLoad(loadProfile)
  const { save, saving, feedback } = useSave()
  return <>
    <FeatureHeader title="Your profile" description="Choose what other members can learn about you." />
    {load.value ? <form className="auth-form" onSubmit={(event) => {
      event.preventDefault()
      const values = profileValues(new FormData(event.currentTarget))
      void save(() => write('/me/profile', 'PUT', values))
    }}>
      <fieldset disabled={saving}>
        <h2>Profile details</h2>
        <div className="personal-fields"><ProfileFields profile={load.value} /></div>
        <button>Save profile</button>
      </fieldset>
      <Feedback feedback={feedback} />
    </form> : <Pending {...load} />}
  </>
}

function SubjectPicker({ subjects, savedIds }: { subjects: Subject[]; savedIds: string[] }) {
  const [query, setQuery] = useState('')
  const [selected, setSelected] = useState(() => new Set(subjects.filter(subject => savedIds.includes(subject.id)).map(subject => subject.id)))
  const search = query.trim().toLocaleLowerCase()
  const matches = subjects.filter(subject => subject.name.toLocaleLowerCase().includes(search))

  return <section className="personal-form-section" aria-labelledby="setup-subjects">
    <h2 id="setup-subjects">Subjects</h2>
    {subjects.length ? <>
      <div className="subject-picker-toolbar">
        <Field label="Search subjects"><input type="search" value={query} onChange={event => setQuery(event.target.value)}
          onKeyDown={event => { if (event.key === 'Enter' && !event.nativeEvent.isComposing) event.preventDefault() }} /></Field>
        <span className="personal-status" role="status">{selected.size} selected</span>
      </div>
      {matches.length ? <div className="personal-subjects">
        {matches.map(subject => <label className="workspace-check" key={subject.id}>
          <input type="checkbox" checked={selected.has(subject.id)} onChange={event => {
            const checked = event.target.checked
            setSelected(previous => {
              const next = new Set(previous)
              if (checked) next.add(subject.id)
              else next.delete(subject.id)
              return next
            })
          }} />{subject.name}
        </label>)}
      </div> : <p className="empty-state" role="status">No subjects match your search.</p>}
      {Array.from(selected, id => <input key={id} type="hidden" name="subjectIds" value={id} />)}
    </> : <p className="empty-state">No subjects are available yet.</p>}
  </section>
}

function SetupScreen({ navigate }: { navigate: (path: string) => void }) {
  const load = useLoad(loadSetup)
  const { save, saving, feedback } = useSave()
  const data = load.value
  return <>
    <FeatureHeader title="Set up your study profile" description="Add your subjects and preferences to personalize your workspace." />
    {data ? <form className="auth-form" onSubmit={(event) => {
      event.preventDefault()
      const form = new FormData(event.currentTarget)
      const subjectIds = form.getAll('subjectIds').map(String)
      void save(async () => {
        if (!subjectIds.length) throw new Error('No subjects selected')
        await write('/me/profile', 'PUT', profileValues(form))
        await write('/me/preferences', 'PUT', preferenceValues(form))
        await write('/me/subjects', 'PUT', { subjectIds })
        if (!(await loadProfile()).onboardingComplete) throw new Error('Setup remains incomplete')
        navigate('/dashboard')
      }, subjectIds.length ? undefined : 'Choose at least one subject to finish setup.')
    }}>
      <fieldset disabled={saving}>
        <section className="personal-form-section"><h2>Profile details</h2>
          <div className="personal-fields"><ProfileFields profile={data[0]} /></div>
        </section>
        <section className="personal-form-section"><h2>Study preferences</h2>
          <div className="personal-fields"><PreferenceFields prefs={data[1]} /></div>
        </section>
        <SubjectPicker subjects={data[2]} savedIds={data[3].subjectIds} />
        <button>Save and open dashboard</button>
      </fieldset><Feedback feedback={feedback} />
    </form> : <Pending {...load} />}
  </>
}

const DAYS = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY']

function AvailabilityForm({ initial }: { initial: Slot[] }) {
  const [slots, setSlots] = useState(() => initial.map((slot, position) => ({ ...slot, key: position })))
  const [nextKey, setNextKey] = useState(initial.length)
  const { saving, save, feedback } = useSave()
  function update(key: number, field: keyof Slot, value: string) {
    setSlots((previous) => previous.map((slot) => slot.key === key ? { ...slot, [field]: value } : slot))
  }
  return <form className="auth-form workspace-panel" onSubmit={(event) => {
    event.preventDefault()
    void save(() => write('/me/availability', 'PUT', { slots: slots.map(({ day, start, end }) => ({ day, start: start.slice(0, 5), end: end.slice(0, 5) })) }),
      'Check that each time window ends after it starts and that windows on the same day do not overlap.')
  }}>
    <h2>Weekly availability</h2>
    <fieldset disabled={saving}>
      {!slots.length && <p className="empty-state">No availability set.</p>}
      {slots.map((slot, index) => <div className="workspace-row" key={slot.key}>
        <Field label={`Day ${index + 1}`}><select value={slot.day} onChange={(event) => update(slot.key, 'day', event.target.value)}>
          {DAYS.map((day) => <option key={day}>{day}</option>)}</select></Field>
        <Field label={`Start ${index + 1}`}><input type="time" required value={slot.start} onChange={(event) => update(slot.key, 'start', event.target.value)} /></Field>
        <Field label={`End ${index + 1}`}><input type="time" required value={slot.end} onChange={(event) => update(slot.key, 'end', event.target.value)} /></Field>
        <button type="button" className="button-secondary" onClick={() => setSlots((previous) => previous.filter((item) => item.key !== slot.key))}>Remove</button>
      </div>)}
      <div className="workspace-actions">
        <button type="button" className="button-secondary" onClick={() => {
          setSlots((previous) => [...previous, { key: nextKey, day: 'MONDAY', start: '', end: '' }])
          setNextKey((previous) => previous + 1)
        }}>Add time window</button> <button>Save availability</button>
      </div>
    </fieldset><Feedback feedback={feedback} />
  </form>
}

function GoalForm({ goal, subjects, reload }: { goal?: Goal; subjects: Subject[]; reload: () => Promise<void> }) {
  const { saving, save, feedback } = useSave()
  const path = goal ? '/me/goals/' + goal.id : '/me/goals'
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const form = event.currentTarget
    const fields = new FormData(form)
    void save(async () => {
      await write(path, goal ? 'PATCH' : 'POST', {
        description: String(fields.get('description')).trim(),
        subjectId: fields.get('subjectId'),
        targetDate: fields.get('targetDate'),
        ...(goal ? { status: fields.get('status') } : {}),
      })
      if (!goal) form.reset()
      await reload()
    })
  }
  return <form className="auth-form" onSubmit={submit}>
    <fieldset disabled={saving}>
      <Field label={goal ? 'Goal description' : 'New goal'}><input name="description" required defaultValue={goal?.description} maxLength={500} /></Field>
      <div className="workspace-row">
        <Field label="Subject"><select name="subjectId" defaultValue={goal?.subjectId ?? ''}>
          <option value="">Any subject</option>{subjects.map((subject) => <option key={subject.id} value={subject.id}>{subject.name}</option>)}
        </select></Field>
        <Field label="Target date"><input name="targetDate" type="date" defaultValue={goal?.targetDate ?? ''} /></Field>
        {goal && <Field label="Goal status"><select name="status" defaultValue={goal.status}>
          <option value="ACTIVE">Active</option><option value="COMPLETED">Completed</option>
        </select></Field>}
      </div>
      <div className="workspace-actions"><button>{goal ? 'Save goal' : 'Add goal'}</button>
        {goal && <button type="button" className="button-secondary" onClick={() => {
          void save(async () => { await write(path, 'DELETE'); await reload() })
        }}>Delete goal</button>}</div>
    </fieldset><Feedback feedback={feedback} />
  </form>
}

function SettingsForm({ data }: { data: Awaited<ReturnType<typeof loadSettings>> }) {
  const [goals, setGoals] = useState(data[3])
  const { save, saving, feedback } = useSave()
  const reload = async () => setGoals(await paged<Goal>('/me/goals'))
  return <>
    <form className="auth-form workspace-panel" onSubmit={(event) => {
      event.preventDefault()
      const values = preferenceValues(new FormData(event.currentTarget))
      void save(() => write('/me/preferences', 'PUT', values))
    }}><h2>Preferences</h2><fieldset disabled={saving}>
      <div className="personal-fields"><PreferenceFields prefs={data[0]} /></div>
      <button>Save preferences</button>
    </fieldset>
      <Feedback feedback={feedback} />
    </form>
    <AvailabilityForm initial={data[1].slots} />
    <section className="workspace-panel"><h2>Study goals</h2>
      {goals.length ? goals.map((goal) => <GoalForm key={goal.id} goal={goal} subjects={data[2]} reload={reload} />) : <p className="empty-state">No study goals yet.</p>}
      <GoalForm subjects={data[2]} reload={reload} />
    </section>
  </>
}

function SettingsScreen() {
  const load = useLoad(loadSettings)
  return <><FeatureHeader title="Study settings" description="Manage your study preferences, availability, and goals." />{load.value ? <SettingsForm data={load.value} /> : <Pending {...load} />}</>
}

const launchActions = [
  { path: '/focus', icon: '◷', name: 'Focus timer', description: 'Start a timed study session.' },
  { path: '/resources', icon: '▤', name: 'Resource library', description: 'Find your notes and course materials.' },
  { path: '/ai-mentor', icon: '✦', name: 'AI Study Mentor', description: 'Get help with a topic or question.' },
] as const

function DashboardScreen({ navigate }: { navigate: (path: string) => void }) {
  const load = useLoad(loadDashboard)
  const dashboard = load.value
  const sections = dashboard ? [
    ['Tasks today', dashboard.todayTasks.length], ['Upcoming exams', dashboard.upcomingExams.length],
    ['Recent resources', dashboard.recentResources.length], ['Active rooms', dashboard.activeRooms.length],
    ['Quiz summaries', dashboard.quizSummaries.length], ['Recommendations', dashboard.recommendations.length],
  ].filter(([, count]) => count !== 0) : []
  return <>
    <h1 tabIndex={-1}>Your dashboard</h1>
    <section className="dashboard-hero" aria-labelledby="dashboard-plan-heading">
      <div className="dashboard-hero-copy">
        <h2 id="dashboard-plan-heading">Make time to study.</h2>
        <p>Organize assignments, deadlines, and upcoming study sessions in your planner.</p>
        <button onClick={() => navigate('/planner')}>Open planner <span aria-hidden="true">↗</span></button>
      </div>
      <div className="brand-orbit" aria-hidden="true">
        <img src="/studysphere-mark.webp" alt="" width="200" height="200" />
      </div>
    </section>
    {dashboard ? <>
      {!dashboard.onboardingComplete && <section className="workspace-panel dashboard-setup">
        <div><h2>Finish your setup</h2>
          <p>Add your study preferences and choose at least one subject.</p></div>
        <button onClick={() => navigate('/onboarding')}>Finish onboarding</button>
      </section>}
      <section className="dashboard-launch" aria-labelledby="dashboard-tools-heading">
        <h2 id="dashboard-tools-heading">Study tools</h2>
        <div className="dashboard-launch-grid">
          {launchActions.map(action => (
            <button className="launch-card" key={action.path} onClick={() => navigate(action.path)}>
              <span className="launch-icon" aria-hidden="true">{action.icon}</span>
              <span className="launch-copy"><strong>{action.name}</strong><span>{action.description}</span></span>
              <span className="launch-arrow" aria-hidden="true">↗</span>
            </button>
          ))}
        </div>
      </section>
      <section className="workspace-panel"><h2>Study activity</h2>
        {sections.length || dashboard.streak !== null
          ? <dl className="engagement-metrics">{sections.map(([name, count]) => <div key={name}><dt>{name}</dt><dd>{count}</dd></div>)}
            {dashboard.streak !== null && <div><dt>Study streak</dt><dd>{dashboard.streak} days</dd></div>}</dl>
          : <p className="empty-state">No activity has been recorded yet.</p>}
        <div className="workspace-actions">
          <button className="button-secondary" onClick={() => navigate('/adaptive')}>View mastery and recommendations</button>
          <button className="button-secondary" onClick={() => navigate('/analytics')}>View study analytics</button>
          <button className="button-secondary" onClick={() => navigate('/achievements')}>View achievements</button>
        </div>
      </section>
    </> : <Pending {...load} />}
  </>
}

const navigation = [
  { label: 'Workspace', links: [
    ['/dashboard', 'Dashboard'], ['/planner', 'Planner'], ['/exams', 'Exam center'], ['/focus', 'Focus'],
  ] },
  { label: 'Study tools', links: [
    ['/resources', 'Resources'], ['/ai-mentor', 'AI Study Mentor'], ['/documents', 'Study documents'],
    ['/quizzes', 'Quizzes'], ['/flashcards', 'Flashcards'], ['/adaptive', 'Adaptive learning'],
  ] },
  { label: 'Community', links: [
    ['/partners', 'Partners'], ['/messages', 'Messages'], ['/rooms', 'Study rooms'], ['/communities', 'Communities'],
  ] },
  { label: 'Personal', links: [
    ['/analytics', 'Analytics'], ['/achievements', 'Achievements'], ['/notifications', 'Notifications'],
    ['/onboarding', 'Onboarding'], ['/profile', 'Profile'], ['/settings', 'Settings'], ['/account', 'Account'],
  ] },
] as const

export function StudentWorkspace({ path, navigate, isAdmin = false }: {
  path: string; navigate: (path: string) => void; isAdmin?: boolean
}) {
  const [menuOpen, setMenuOpen] = useState(false)
  const menuButton = useRef<HTMLButtonElement>(null)
  const groups = isAdmin
    ? [...navigation, { label: 'Operations', links: [['/admin', 'Administration']] as const }]
    : navigation
  return <div className="workspace-layout">
    <div className="workspace-sidebar" onKeyDown={event => {
      if (event.key === 'Escape' && menuOpen) {
        event.preventDefault()
        setMenuOpen(false)
        menuButton.current?.focus()
      }
    }}>
      <button ref={menuButton} className="workspace-menu button-secondary" type="button" aria-expanded={menuOpen}
        aria-controls="workspace-navigation" onClick={() => setMenuOpen(!menuOpen)}>
        {menuOpen ? 'Close navigation' : 'Browse workspace'}<span aria-hidden="true">{menuOpen ? '−' : '+'}</span>
      </button>
      <nav className="workspace-nav" id="workspace-navigation" aria-label="Study workspace" data-open={menuOpen}>
        {groups.map(group => <div className="nav-group" key={group.label}>
          <p>{group.label}</p>
          {group.links.map(([href, label]) => (
            <a key={href} href={href}
              aria-current={path === href ? 'page' : path.startsWith(href + '/') ? 'location' : undefined}
              onClick={(event) => {
                if (!event.ctrlKey && !event.metaKey && !event.shiftKey && !event.altKey && event.button === 0) {
                  event.preventDefault()
                  if (href !== path) navigate(href)
                  else if (menuOpen) {
                    setMenuOpen(false)
                    menuButton.current?.focus()
                  }
                }
              }}>{label}</a>
          ))}
        </div>)}
      </nav>
    </div>
    <main className="account-card workspace-content" id="auth-content" tabIndex={-1} data-screen={path.split('/')[1]}>
    {path === '/admin' && (isAdmin
      ? <Suspense fallback={<p role="status">Loading administration…</p>}><AdminPage /></Suspense>
      : <><h1 tabIndex={-1}>Access denied</h1><p>This page requires an administrator account.</p></>)}
    {path === '/dashboard' && <DashboardScreen navigate={navigate} />}
    {path === '/onboarding' && <SetupScreen navigate={navigate} />}
    {path === '/profile' && <ProfileScreen />}
    {path === '/settings' && <SettingsScreen />}
    {path === '/planner' && <PlannerPage />}
    {path === '/exams' && <ExamsPage />}
    {path === '/focus' && <FocusPage />}
    {path === '/resources' && <ResourcesPage navigate={navigate} />}
    {path === '/documents' && <DocumentsPage navigate={navigate} />}
    {path === '/quizzes' && <Suspense fallback={<p role="status">Loading quizzes…</p>}><QuizzesPage /></Suspense>}
    {path === '/flashcards' && <Suspense fallback={<p role="status">Loading flashcards…</p>}><FlashcardsPage /></Suspense>}
    {path === '/adaptive' && <Suspense fallback={<p role="status">Loading adaptive learning…</p>}><AdaptivePage navigate={navigate} /></Suspense>}
    {path === '/analytics' && <Suspense fallback={<p role="status">Loading analytics…</p>}><AnalyticsPage /></Suspense>}
    {path === '/achievements' && <Suspense fallback={<p role="status">Loading achievements…</p>}><AchievementsPage /></Suspense>}
    {path === '/notifications' && <Suspense fallback={<p role="status">Loading notifications…</p>}><NotificationsPage /></Suspense>}
    {isDocumentPath(path) && <DocumentPage key={path} id={path.slice('/documents/'.length)} navigate={navigate} />}
    {path === '/partners' && <PartnersPage openConversation={id => {
      navigate('/messages')
      window.history.replaceState(null, '', '/messages?conversation=' + encodeURIComponent(id))
    }} />}
    {path === '/messages' && <MessagesPage preferredId={new URLSearchParams(window.location.search).get('conversation')} />}
    {path === '/rooms' && <RoomsPage navigate={navigate} />}
    {path === '/communities' && <Suspense fallback={<p role="status">Loading communities…</p>}><CommunitiesPage navigate={navigate} /></Suspense>}
    {isCommunityPath(path) && <Suspense fallback={<p role="status">Loading community…</p>}>
      <CommunityPage key={path} id={path.slice('/communities/'.length)} navigate={navigate} />
    </Suspense>}
    {path === '/ai-mentor' && <Suspense fallback={<p role="status">Loading Study Mentor…</p>}><MentorPage /></Suspense>}
    {isRoomPath(path) && <RoomPage roomId={path.slice('/rooms/'.length)} navigate={navigate} />}
    </main>
  </div>
}
