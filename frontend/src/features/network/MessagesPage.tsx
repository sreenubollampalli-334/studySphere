import { FeatureHeader } from '../../components/FeatureHeader'
import { useCallback, useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { authenticatedRequest, messageFor } from '../auth/authApi'
import { all, read, useResource, write } from '../productivity/common'
import { useConversationSocket } from './useConversationSocket'

interface Conversation { id: string; peerId: string; peerName: string | null; unreadCount: number }
interface Message {
  id: string
  senderId: string
  body: string | null
  createdAt: string
  read: boolean
  attachmentName: string | null
  deleted: boolean
}
interface History { items: Message[]; hasNext: boolean }

const loadConversations = () => all<Conversation>('/conversations')
const historyPath = (id: string) => '/conversations/' + id + '/messages'

export function MessagesPage({ preferredId }: { preferredId: string | null }) {
  const conversations = useResource(loadConversations)
  const [selection, setSelection] = useState(preferredId)
  const selected = conversations.data?.find(conversation => conversation.id === selection) ?? conversations.data?.[0]

  return (
    <section className="network-page" aria-labelledby="messages-heading">
      <FeatureHeader title="Messages" description="Pick a conversation to continue where you left off." headingId="messages-heading" />
      {conversations.error && <p role="alert">{conversations.error} <button onClick={conversations.retry}>Retry</button></p>}
      {!conversations.data && !conversations.error && <p role="status">Loading conversations…</p>}
      {conversations.data?.length === 0 && <p className="empty-state">No conversations yet. Start a message from Study partners.</p>}
      {selected && <div className="conversation-layout">
      <nav className="network-actions conversation-list" aria-label="Conversations">
        {conversations.data?.map(conversation => <button key={conversation.id} type="button"
          className={selected?.id === conversation.id ? '' : 'button-secondary'}
          aria-current={selected?.id === conversation.id ? 'true' : undefined}
          onClick={() => setSelection(conversation.id)}>
          {conversation.peerName ?? 'StudySphere member'}{conversation.unreadCount > 0 ? ' (' + conversation.unreadCount + ' unread)' : ''}
        </button>)}
      </nav>
      <div className="conversation-thread">
        <ConversationThread key={selected.id} conversation={selected} onRead={conversations.reload} />
      </div>
      </div>}
    </section>
  )
}

function mergeMessages(existing: Message[], updated: Message[]) {
  const byId = new Map(existing.map(message => [message.id, message]))
  for (const message of updated) byId.set(message.id, message)
  return Array.from(byId.values()).sort((a, b) => BigInt(a.id) < BigInt(b.id) ? -1 : BigInt(a.id) > BigInt(b.id) ? 1 : 0)
}

function ConversationThread({ conversation, onRead }: { conversation: Conversation; onRead: () => Promise<void> }) {
  const { id, peerId } = conversation
  const [messages, setMessages] = useState<Message[]>([])
  const [loaded, setLoaded] = useState(false)
  const [hasOlder, setHasOlder] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [busy, setBusy] = useState(false)
  const [typing, setTyping] = useState(false)
  const [online, setOnline] = useState<boolean>()
  const [reportId, setReportId] = useState<string>()
  const lastKnown = useRef<string | undefined>(undefined)
  const lastRead = useRef<string | undefined>(undefined)
  const loading = useRef(false)
  const pending = useRef(false)
  const typingTimer = useRef<number | undefined>(undefined)
  const path = historyPath(id)

  const refresh = useCallback(async () => {
    if (loading.current) {
      pending.current = true
      return
    }
    loading.current = true
    try {
      do {
        pending.current = false
        if (lastKnown.current) {
          let cursor = lastKnown.current
          for (;;) {
            const next = await read<History>(path + '?afterId=' + cursor + '&size=50')
            if (next.items.length === 0) break
            setMessages(existing => mergeMessages(existing, next.items))
            cursor = next.items.at(-1)!.id
            lastKnown.current = cursor
            if (!next.hasNext) break
          }
        }
        const latest = await read<History>(path + '?size=50')
        setMessages(existing => mergeMessages(existing, latest.items))
        const newest = latest.items.at(-1)?.id
        if (newest && (!lastKnown.current || BigInt(newest) > BigInt(lastKnown.current))) lastKnown.current = newest
        const firstLoad = !lastRead.current
        if (firstLoad) setHasOlder(latest.hasNext)
        setLoaded(true)
        setError('')
        const incoming = latest.items.slice().reverse().find(message => message.senderId === peerId && !message.read && !message.deleted)
        if (incoming && lastRead.current !== incoming.id) {
          await write('/conversations/' + id + '/read', 'POST', { messageId: incoming.id })
          lastRead.current = incoming.id
          await onRead()
        } else if (!lastRead.current) {
          lastRead.current = '0'
        }
      } while (pending.current)
    } catch (failure) {
      setError(messageFor(failure, 'Could not load the conversation. Try again.'))
    } finally {
      loading.current = false
    }
  }, [id, onRead, path, peerId])

  const { connected, sendTyping } = useConversationSocket(id, event => {
    if (event.type === 'TYPING' && event.userId === peerId) {
      window.clearTimeout(typingTimer.current)
      setTyping(true)
      typingTimer.current = window.setTimeout(() => setTyping(false), 3750)
    } else if (event.type === 'PRESENCE' && event.userId === peerId) {
      setOnline(event.online)
    } else if (event.type !== 'TYPING' && event.type !== 'PRESENCE') {
      void refresh()
    }
  }, () => void refresh())

  useEffect(() => {
    void refresh()
    const timer = window.setInterval(() => { if (!connected) void refresh() }, 6000)
    return () => window.clearInterval(timer)
  }, [connected, refresh])

  useEffect(() => () => window.clearTimeout(typingTimer.current), [])

  async function older() {
    const before = messages[0]?.id
    if (!before) return
    await mutate(async () => {
      const result = await read<History>(path + '?beforeId=' + before + '&size=50')
      setMessages(existing => mergeMessages(existing, result.items))
      setHasOlder(result.hasNext)
    })
  }

  async function mutate(change: () => Promise<unknown>) {
    setBusy(true)
    setNotice('')
    setError('')
    try {
      await change()
      return true
    } catch (failure) {
      setError(messageFor(failure, 'Could not complete the message action.'))
      return false
    } finally {
      setBusy(false)
    }
  }

  async function send(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const form = event.currentTarget
    const formData = new FormData(form)
    const body = String(formData.get('body') ?? '').trim()
    const file = formData.get('file')
    if (!body && !(file instanceof File && file.size > 0)) {
      setError('Write a message or choose an attachment.')
      return
    }
    if (file instanceof File && file.size > 0 && (!file.name.endsWith('.txt') || file.size > 5 * 1024 * 1024)) {
      setError('Choose a UTF-8 text file up to 5 MiB.')
      return
    }
    if (await mutate(() => file instanceof File && file.size > 0
      ? authenticatedRequest(path, { method: 'POST', body: formData })
      : write(path, 'POST', { body }))) {
      form.reset()
      await refresh()
    }
  }

  async function download(message: Message) {
    await mutate(async () => {
      const response = await authenticatedRequest(path + '/' + message.id + '/attachment')
      const url = URL.createObjectURL(await response.blob())
      const anchor = document.createElement('a')
      anchor.href = url
      anchor.download = message.attachmentName ?? 'attachment'
      document.body.append(anchor)
      anchor.click()
      anchor.remove()
      window.setTimeout(() => URL.revokeObjectURL(url), 1000)
    })
  }

  async function report(event: FormEvent<HTMLFormElement>, messageId: string) {
    event.preventDefault()
    const reason = String(new FormData(event.currentTarget).get('reason') ?? '').trim()
    if (await mutate(() => write(path + '/' + messageId + '/report', 'POST', { reason }))) {
      setReportId(undefined)
      setNotice('Message reported.')
    }
  }

  return (
    <section aria-label="Conversation">
      <h2>{conversation.peerName ?? 'StudySphere member'}</h2>
      <p role="status">
        {connected ? online === undefined ? 'Connected' : online ? 'Online' : 'Offline'
          : 'Reconnecting. Message history is still available.'}
      </p>
      {typing && <p role="status">{conversation.peerName ?? 'Your partner'} is typing…</p>}
      {notice && <p role="status">{notice}</p>}
      {error && <p role="alert">{error} <button onClick={() => void refresh()}>Retry</button></p>}
      {!loaded && !error && <p role="status">Loading message history…</p>}
      {loaded && messages.length === 0 && <p className="empty-state">No messages yet.</p>}
      {hasOlder && <button className="button-secondary" disabled={busy} onClick={() => void older()}>Load earlier messages</button>}
      <ol className="network-list">
        {messages.map(message => <li key={message.id}>
          <p><strong>{message.senderId === peerId ? conversation.peerName ?? 'StudySphere member' : 'You'}</strong>
            {' · '}<time dateTime={message.createdAt}>{new Date(message.createdAt).toLocaleString()}</time></p>
          {message.deleted ? <p>Message deleted.</p> : <>
            {message.body && <p className="network-message">{message.body}</p>}
            {message.attachmentName && <button className="button-secondary" disabled={busy}
              onClick={() => void download(message)}>Download {message.attachmentName}</button>}
            {message.senderId === peerId
              ? <button className="button-secondary" disabled={busy}
                onClick={() => setReportId(reportId === message.id ? undefined : message.id)}>Report message</button>
              : <div className="network-actions">
                <span>{message.read ? 'Read' : 'Sent'}</span>
                <button className="button-secondary" disabled={busy}
                  onClick={() => void mutate(() => write(path + '/' + message.id, 'DELETE'))
                    .then(saved => { if (saved) return refresh() })}>Delete message</button>
              </div>}
            {reportId === message.id && <form onSubmit={event => void report(event, message.id)}>
              <label>Report reason <textarea name="reason" required maxLength={500} /></label>
              <button disabled={busy}>Submit report</button>
            </form>}
          </>}
        </li>)}
      </ol>
      <form onSubmit={event => void send(event)}>
        <label>Message <textarea name="body" maxLength={4000} onChange={sendTyping} /></label>
        <label>Text attachment, up to 5 MiB <input name="file" type="file" accept=".txt,text/plain" /></label>
        <button disabled={busy || !loaded}>Send message</button>
      </form>
    </section>
  )
}
