import { FeatureHeader } from '../../components/FeatureHeader'
import { useCallback, useState } from 'react'
import { Feedback, Pending, read, useMutation, useResource, write } from '../productivity/common'

interface Preferences {
  taskReminders: boolean
  invitations: boolean
  messages: boolean
  exams: boolean
  revision: boolean
}

interface Notice {
  id: string
  type: string
  title: string
  body: string
  createdAt: string
  readAt: string | null
}

interface Inbox {
  items: Notice[]
  page: number
  size: number
  hasNext: boolean
  unreadCount: number
}

const options = [
  ['taskReminders', 'Task reminders'],
  ['invitations', 'Invitations'],
  ['messages', 'New messages'],
  ['exams', 'Upcoming exams'],
  ['revision', 'Flashcard revision'],
] as const

const preferencesPath = '/me/notification-preferences'
const loadPreferences = () => read<Preferences>(preferencesPath)
const noticeDate = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' })

export function NotificationPreferences() {
  const load = useResource(loadPreferences)
  const mutation = useMutation(load.reload)
  const data = load.data
  return <section className="workspace-panel">
    <h2>Notification preferences</h2>
    {!data ? <Pending {...load} /> : <>
      <form onSubmit={event => {
        event.preventDefault()
        const form = new FormData(event.currentTarget)
        const preferences = Object.fromEntries(options.map(([key]) => [key, form.has(key)]))
        void mutation.run(() => write(preferencesPath, 'PUT', preferences), 'Notification preferences saved.')
      }}>
        <fieldset className="engagement-preferences" disabled={mutation.busy}>
          <legend>In-app alerts</legend>
          {options.map(([key, name]) => <label key={key}>
            <input type="checkbox" name={key} defaultChecked={data[key]} /> {name}
          </label>)}
          <button type="submit">Save preferences</button>
        </fieldset>
      </form>
      <Feedback value={mutation.feedback} />
    </>}
  </section>
}

function NotificationInbox() {
  const [page, setPage] = useState(0)
  const loadPage = useCallback(() => read<Inbox>(`/notifications?page=${page}&size=20`), [page])
  const load = useResource(loadPage)
  const mutation = useMutation(load.reload)
  const data = load.data
  return <section className="workspace-panel">
    <h2>Inbox</h2>
    {!data ? <Pending {...load} /> : <>
      <p className="personal-status">{data.unreadCount} unread</p>
      {data.items.length ? <ul className="engagement-list notification-list">{data.items.map(notice => <li key={notice.id} data-unread={!notice.readAt}>
        <header><h3>{notice.title}</h3><time dateTime={notice.createdAt}>{noticeDate.format(new Date(notice.createdAt))}</time></header>
        <p>{notice.body}</p>
        {notice.readAt ? <p>Read</p> : <p><button className="button-secondary" disabled={mutation.busy} onClick={() => {
          void mutation.run(() => write(`/notifications/${notice.id}/read`, 'PUT'), 'Notification marked as read.')
        }}>Mark as read<span className="engagement-sr-only">: {notice.title}</span></button></p>}
      </li>)}</ul> : <p className="empty-state">No notifications on this page.</p>}
      <div className="engagement-pagination">
        {page > 0 ? <button className="button-secondary" disabled={mutation.busy} onClick={() => setPage(page - 1)}>Previous page</button> : null}
        {page > 0 || data.hasNext ? <span>Page {page + 1}</span> : null}
        {data.hasNext ? <button className="button-secondary" disabled={mutation.busy} onClick={() => setPage(page + 1)}>Next page</button> : null}
        <button className="button-secondary" disabled={mutation.busy} onClick={load.retry}>Refresh inbox</button>
      </div>
      <Feedback value={mutation.feedback} />
    </>}
  </section>
}

export function NotificationsPage() {
  return <><FeatureHeader title="Notifications" description="Catch up on updates and choose what you want to receive." />
    <div className="feature-columns notification-layout">
      <NotificationInbox />
      <NotificationPreferences />
    </div>
  </>
}
