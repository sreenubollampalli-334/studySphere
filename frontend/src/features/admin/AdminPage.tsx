import { useCallback, useEffect, useRef, useState } from 'react'
import type { FormEvent, ReactNode } from 'react'
import { FeatureHeader } from '../../components/FeatureHeader'
import { displayInstant, Feedback, Field, field, Pending, read, useMutation, useResource, write } from '../productivity/common'

type ReportStatus = 'OPEN' | 'RESOLVED' | 'DISMISSED'
const reportNames = {
  PRIVATE_MESSAGE: 'Private message', COMMUNITY_POST: 'Community question', COMMUNITY_COMMENT: 'Community reply',
}
interface Report {
  kind: keyof typeof reportNames
  targetId: string
  reporterId: string
  subjectId: string
  status: ReportStatus
  reason: string | null
  content: string | null
  createdAt: string
}
interface Restriction { userId: string; reason: string; createdAt: string; expiresAt: string | null }
interface Audit { id: string; actorId: string; action: string; target: string; reason: string; createdAt: string }
interface Usage {
  provider: string
  requests: number
  completed: number
  inputCharacters: number
  outputCharacters: number
  estimatedCostUsd: number | null
}
interface Page<T> { items: T[]; total: number; page: number; size: number }
const sections = ['Reports', 'Restrictions', 'Audit log', 'AI usage'] as const
const size = 20

function timestamp(value: string) {
  return <time dateTime={value}>{displayInstant(value, 'UTC')} UTC</time>
}

function usePage<T>(path: string) {
  const [page, setPage] = useState(0)
  const load = useCallback(() => read<Page<T>>(path + (path.includes('?') ? '&' : '?') + `page=${page}&size=${size}`), [path, page])
  return { ...useResource(load), setPage }
}

function PagedList<T>({ data, empty, busy, setPage, children }: {
  data: Page<T>; empty: string; busy?: boolean; setPage: (page: number) => void; children: ReactNode
}) {
  return <>
    {data.items.length ? <ul className="workspace-list">{children}</ul> : <p className="empty-state">{empty}</p>}
    {(data.total > data.size || data.page > 0) && <div className="workspace-actions" role="group" aria-label="Pagination">
      <button type="button" className="button-secondary" disabled={busy || data.page === 0}
        onClick={() => setPage(data.page - 1)}>Previous page</button>
      <span>Page {data.page + 1}</span>
      <button type="button" className="button-secondary" disabled={busy || (data.page + 1) * data.size >= data.total}
        onClick={() => setPage(data.page + 1)}>Next page</button>
    </div>}
  </>
}

function ReportQueue({ status }: { status: ReportStatus }) {
  const reports = usePage<Report>('/admin/reports?status=' + status)
  const mutation = useMutation(reports.reload)

  function review(event: FormEvent<HTMLFormElement>, report: Report, hide: boolean, next: 'RESOLVED' | 'DISMISSED') {
    event.preventDefault()
    const form = new FormData(event.currentTarget)
    void mutation.run(() => write(`/admin/reports/${report.kind}/${report.targetId}/${report.reporterId}`, 'PATCH', {
      status: next, reason: field(form, 'reason'), hideContent: hide && next === 'RESOLVED',
    }), next === 'RESOLVED' ? 'Report resolved.' : 'Report dismissed.')
  }

  if (!reports.data) return <Pending error={reports.error} retry={reports.retry} />
  return <>
    <Feedback value={mutation.feedback} />
    <PagedList data={reports.data} empty="There are no reports with this status." busy={mutation.busy} setPage={reports.setPage}>
      {reports.data.items.map(report => <li key={`${report.kind}/${report.targetId}/${report.reporterId}`}>
        <h3>{reportNames[report.kind]} #{report.targetId}</h3>
        <p>Reported by user #{report.reporterId} · Author #{report.subjectId} · {timestamp(report.createdAt)}</p>
        {report.reason && <p className="workspace-text">Report reason: {report.reason}</p>}
        {report.content && <blockquote className="workspace-text">{report.content}</blockquote>}
        {status === 'OPEN' && <form className="auth-form" onSubmit={event => {
          const action = (event.nativeEvent as SubmitEvent).submitter?.getAttribute('value')
          review(event, report, action === 'hide', action === 'dismiss' ? 'DISMISSED' : 'RESOLVED')
        }}>
          <fieldset disabled={mutation.busy}>
            <Field label="Moderation reason"><textarea name="reason" required maxLength={500} rows={2} /></Field>
            <div className="workspace-actions"><button value="resolve">Resolve report</button>
              <button value="hide" className="button-secondary">Hide content and resolve</button>
              <button value="dismiss" className="button-secondary">Dismiss report</button></div>
          </fieldset>
        </form>}
      </li>)}
    </PagedList>
  </>
}

function Reports() {
  const [status, setStatus] = useState<ReportStatus>('OPEN')
  return <section className="workspace-panel">
    <h2>Moderation reports</h2>
    <Field label="Report status"><select value={status} onChange={event => setStatus(event.target.value as ReportStatus)}>
      <option value="OPEN">Open</option><option value="RESOLVED">Resolved</option><option value="DISMISSED">Dismissed</option>
    </select></Field>
    <ReportQueue key={status} status={status} />
  </section>
}

function Restrictions() {
  const restrictions = usePage<Restriction>('/admin/restrictions')
  const mutation = useMutation(restrictions.reload)

  function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const target = event.currentTarget
    const form = new FormData(target)
    const expires = field(form, 'expiresAt')
    void mutation.run(() => write('/admin/restrictions/' + field(form, 'userId'), 'PUT', {
      reason: field(form, 'reason'), expiresAt: expires ? new Date(expires + 'Z').toISOString() : null,
    }), 'User restriction saved.').then(saved => { if (saved) target.reset() })
  }

  return <section className="workspace-panel">
    <h2>User restrictions</h2>
    <Feedback value={mutation.feedback} />
    {restrictions.data ? <>
      <form className="auth-form" onSubmit={save}>
        <fieldset disabled={mutation.busy}>
          <legend>Set or update a restriction</legend>
          <Field label="User ID"><input name="userId" required inputMode="numeric" pattern="[1-9][0-9]*" maxLength={19} /></Field>
          <Field label="Restriction reason"><textarea name="reason" required maxLength={500} rows={2} /></Field>
          <Field label="Expiry in UTC (optional)"><input name="expiresAt" type="datetime-local" /></Field>
          <button>Save restriction</button>
        </fieldset>
      </form>
      <PagedList data={restrictions.data} empty="No user restrictions." busy={mutation.busy} setPage={restrictions.setPage}>
        {restrictions.data.items.map(restriction => <li key={restriction.userId}>
          <h3>User #{restriction.userId}</h3>
          <p className="workspace-text">{restriction.reason}</p>
          <p>Created {timestamp(restriction.createdAt)} · {restriction.expiresAt ? <>Expires {timestamp(restriction.expiresAt)}</> : 'Permanent'}</p>
          <form className="auth-form" onSubmit={event => {
            event.preventDefault()
            const reason = field(new FormData(event.currentTarget), 'reason')
            void mutation.run(() => write(`/admin/restrictions/${restriction.userId}`, 'DELETE', { reason }), 'User restriction lifted.')
          }}><fieldset disabled={mutation.busy}>
            <Field label="Reason for lifting restriction"><input name="reason" required maxLength={500} /></Field>
            <button className="button-secondary">Lift restriction</button>
          </fieldset></form>
        </li>)}
      </PagedList>
    </> : <Pending error={restrictions.error} retry={restrictions.retry} />}
  </section>
}

function AuditLog() {
  const audits = usePage<Audit>('/admin/audits')
  return <section className="workspace-panel">
    <h2>Audit log</h2>
    {audits.data ? <PagedList data={audits.data} empty="No audit events recorded." setPage={audits.setPage}>
      {audits.data.items.map(audit => <li key={audit.id}>
        <h3>{audit.action.replaceAll('_', ' ').toLowerCase()}</h3>
        <p>Administrator #{audit.actorId} · {timestamp(audit.createdAt)}</p>
        <p className="workspace-text">{audit.target}</p>
        {audit.reason && <p className="workspace-text">{audit.reason}</p>}
      </li>)}
    </PagedList> : <Pending error={audits.error} retry={audits.retry} />}
  </section>
}

const currency = new Intl.NumberFormat(undefined, { style: 'currency', currency: 'USD', maximumFractionDigits: 6 })

function UsageSummary() {
  const [days, setDays] = useState('30')
  const load = useCallback(() => read<{ days: number; items: Usage[] }>('/admin/usage?days=' + days), [days])
  const usage = useResource(load)
  return <section className="workspace-panel">
    <h2>AI provider usage</h2>
    <Field label="Reporting period"><select value={days} onChange={event => setDays(event.target.value)}>
      <option value="7">Last 7 days</option><option value="30">Last 30 days</option><option value="90">Last 90 days</option>
    </select></Field>
    {usage.data ? (usage.data.items.length ? <ul className="workspace-list">{usage.data.items.map(item => <li key={item.provider}>
      <h3>{item.provider}</h3>
      <dl>
        <dt>Requests</dt><dd>{item.requests.toLocaleString()}</dd>
        <dt>Completed</dt><dd>{item.completed.toLocaleString()}</dd>
        <dt>Input characters</dt><dd>{item.inputCharacters.toLocaleString()}</dd>
        <dt>Output characters</dt><dd>{item.outputCharacters.toLocaleString()}</dd>
        <dt>Estimated cost (USD)</dt><dd>{item.estimatedCostUsd == null ? 'Unavailable' : currency.format(item.estimatedCostUsd)}</dd>
      </dl>
    </li>)}</ul> : <p className="empty-state">No AI activity recorded in this period.</p>) : <Pending error={usage.error} retry={usage.retry} />}
  </section>
}

export function AdminPage() {
  const [section, setSection] = useState<typeof sections[number]>('Reports')
  const heading = useRef<HTMLHeadingElement>(null)
  useEffect(() => { heading.current?.focus() }, [])
  return <>
    <FeatureHeader title="Administration" description="Review moderation reports, account restrictions, and system activity." headingRef={heading} />
    <div className="workspace-actions feature-tabs" role="group" aria-label="Administration tools">
      {sections.map(option => <button key={option} className={section === option ? undefined : 'button-secondary'}
        aria-pressed={section === option} onClick={() => setSection(option)}>{option}</button>)}
    </div>
    {section === 'Reports' && <Reports />}
    {section === 'Restrictions' && <Restrictions />}
    {section === 'Audit log' && <AuditLog />}
    {section === 'AI usage' && <UsageSummary />}
  </>
}
