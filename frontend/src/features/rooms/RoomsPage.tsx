import { FeatureHeader } from '../../components/FeatureHeader'
import { lazy, Suspense, useCallback, useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { authenticatedRequest, messageFor } from '../auth/authApi'
import { all, Feedback, Field, field, Pending, read, useMutation, useResource, write } from '../productivity/common'
import { useRoomSocket } from './useRoomSocket'

type Role = 'OWNER' | 'MODERATOR' | 'MEMBER'
interface Room {
  id: string; name: string; description: string | null; visibility: 'PUBLIC' | 'PRIVATE'
  capacity: number; memberCount: number; role: Role | null
}
interface Member { userId: string; displayName: string | null; role: Role }
interface Invitation { userId: string; displayName: string | null }
interface AgendaItem { id: string; text: string }
interface RoomResource { id: string; title: string; fileName: string }
interface LibraryResource extends RoomResource { owned: boolean }
interface Message { id: string; body: string; senderName: string | null; createdAt: string; pinned: boolean }

const loadRooms = () => Promise.all([all<Room>('/rooms'), read<Room[]>('/room-invitations')])
const RoomAssistant = lazy(() => import('./RoomAssistant').then(module => ({ default: module.RoomAssistant })))

function formAction(action: (form: FormData) => Promise<boolean>, reset = true) {
  return async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const form = event.currentTarget
    if (await action(new FormData(form)) && reset) form.reset()
  }
}

function roomFields(form: FormData) {
  return {
    name: field(form, 'name'), description: field(form, 'description'),
    visibility: field(form, 'visibility'), capacity: Number(field(form, 'capacity')),
  }
}

function RoomFields({ room }: { room?: Room }) {
  return <>
    <Field label="Room name"><input name="name" required maxLength={120} defaultValue={room?.name} /></Field>
    <Field label="Description"><textarea name="description" maxLength={600} defaultValue={room?.description ?? ''} /></Field>
    <Field label="Visibility"><select name="visibility" defaultValue={room?.visibility ?? 'PUBLIC'}>
      <option value="PUBLIC">Public</option><option value="PRIVATE">Private</option>
    </select></Field>
    <Field label="Capacity"><input type="number" name="capacity" min={2} max={100} required defaultValue={room?.capacity ?? 10} /></Field>
  </>
}

export function RoomsPage({ navigate }: { navigate: (path: string) => void }) {
  const rooms = useResource(loadRooms)
  const mutation = useMutation(rooms.reload)
  const [showCreate, setShowCreate] = useState(false)
  async function create(form: FormData) {
    let id = ''
    const success = await mutation.run(async () => {
      id = ((await (await write('/rooms', 'POST', roomFields(form))).json()) as Room).id
    }, 'Room created.')
    if (success) navigate('/rooms/' + id)
    return success
  }
  return <>
    <FeatureHeader title="Study rooms" description="Find a room or bring your study group together.">
      <button onClick={() => setShowCreate(value => !value)} aria-expanded={showCreate}>Create room</button>
    </FeatureHeader>
    <Feedback value={mutation.feedback} />
    {showCreate && <form onSubmit={formAction(create)}><fieldset disabled={mutation.busy}>
      <RoomFields /><button>Create study room</button>
    </fieldset></form>}
    {!rooms.data ? <Pending error={rooms.error} retry={rooms.retry} /> : <>
      {rooms.data[1].length > 0 && <section><h2>Invitations</h2>
        <RoomLinks rooms={rooms.data[1]} navigate={navigate} />
      </section>}
      <section><h2>Discover rooms</h2>
        {rooms.data[0].length ? <RoomLinks rooms={rooms.data[0]} navigate={navigate} /> : <p className="empty-state">No rooms are available yet.</p>}
      </section>
    </>}
  </>
}

function RoomLinks({ rooms, navigate }: { rooms: Room[]; navigate: (path: string) => void }) {
  return <ul className="workspace-list feature-card-grid">{rooms.map(room => <li key={room.id}>
    <button onClick={() => navigate('/rooms/' + room.id)}>{room.name}</button>
    <p>{room.visibility === 'PRIVATE' ? 'Private' : 'Public'} · {room.memberCount} of {room.capacity} members
      {room.role ? ' · ' + room.role.toLowerCase() : ''}</p>
    {room.description && <p>{room.description}</p>}
  </li>)}</ul>
}

export function RoomPage({ roomId, navigate }: { roomId: string; navigate: (path: string) => void }) {
  const path = '/rooms/' + roomId
  const loadRoom = useCallback(() => read<Room>(path), [path])
  const room = useResource(loadRoom)
  const mutation = useMutation(room.reload)
  const [showAssistant, setShowAssistant] = useState(false)
  if (!room.data) return <><h1 tabIndex={-1}>Study room</h1><Pending error={room.error} retry={room.retry} /></>
  const data = room.data
  return <>
    <FeatureHeader title={data.name} description={data.description ?? undefined}>
      <button className="button-secondary" onClick={() => navigate('/rooms')}>All study rooms</button>
    </FeatureHeader>
    <p>{data.visibility === 'PRIVATE' ? 'Private room' : 'Public room'} · {data.memberCount} of {data.capacity} members</p>
    <Feedback value={mutation.feedback} />
    {data.role ? <><p>Your role is {data.role.toLowerCase()}.</p>
      {data.role !== 'OWNER' && <button disabled={mutation.busy} className="button-secondary"
        onClick={() => void mutation.run(() => write(path + '/members/me', 'DELETE'), 'You left the room.')}>Leave room</button>}
      <RoomMemberTools room={data} path={path} reloadRoom={room.reload} navigate={navigate} />
      <RoomChat path={path} roomId={roomId} moderate={data.role !== 'MEMBER'} />
      <section className="workspace-panel"><h2>Room assistant and notes</h2>
        <button className="button-secondary" aria-expanded={showAssistant} onClick={() => setShowAssistant(value => !value)}>
          {showAssistant ? 'Hide room assistant' : 'Open room assistant'}
        </button>
        {showAssistant && <Suspense fallback={<p role="status">Loading room assistant…</p>}>
          <RoomAssistant key={roomId} path={path} moderate={data.role !== 'MEMBER'} />
        </Suspense>}
      </section>
    </> : <>
      {data.memberCount >= data.capacity && <p>This room is full.</p>}
      <button disabled={mutation.busy || data.memberCount >= data.capacity}
        onClick={() => void mutation.run(() => write(path + '/members/me', 'PUT'), 'You joined the room.')}>Join room</button>
    </>}
  </>
}

function RoomMemberTools({ room, path, reloadRoom, navigate }: {
  room: Room; path: string; reloadRoom: () => Promise<void>; navigate: (path: string) => void
}) {
  const moderate = room.role !== 'MEMBER'
  const own = room.role === 'OWNER'
  const loadTools = useCallback(() => Promise.all([
    read<Member[]>(path + '/members'), read<AgendaItem[]>(path + '/agenda'),
    read<RoomResource[]>(path + '/resources'), moderate ? read<Invitation[]>(path + '/invitations') : Promise.resolve([]),
    all<LibraryResource>('/resources'),
  ]), [path, moderate])
  const tools = useResource(loadTools)
  const mutation = useMutation(async () => { await Promise.all([tools.reload(), reloadRoom()]) })
  const [deleteConfirm, setDeleteConfirm] = useState(false)
  const [downloadError, setDownloadError] = useState('')
  if (!tools.data) return <Pending error={tools.error} retry={tools.retry} />
  const [members, agenda, shared, invitations, resources] = tools.data
  const ownedResources = resources.filter(resource => resource.owned)
  const shareable = ownedResources.filter(resource => !shared.some(item => item.id === resource.id))
  const alter = (suffix: string, method: string, payload?: unknown) =>
    mutation.run(() => write(path + suffix, method, payload))
  async function download(resource: RoomResource) {
    setDownloadError('')
    try {
      const result = await authenticatedRequest(path + '/resources/' + resource.id + '/download')
      const url = URL.createObjectURL(await result.blob())
      const link = document.createElement('a')
      link.href = url
      link.download = resource.fileName
      link.click()
      window.setTimeout(() => URL.revokeObjectURL(url), 1000)
    } catch (error) {
      setDownloadError(messageFor(error, 'This resource could not be downloaded.'))
    }
  }
  return <>
    <Feedback value={mutation.feedback} />
    <section className="workspace-panel"><h2>Members</h2>
      <ul className="workspace-list">{members.map(member => <li key={member.userId}>
        {member.displayName ?? 'StudySphere member'} · {member.role.toLowerCase()}
        {own && member.role !== 'OWNER' && <button className="button-secondary" disabled={mutation.busy}
          onClick={() => void alter('/members/' + member.userId, 'PATCH', { role: member.role === 'MEMBER' ? 'MODERATOR' : 'MEMBER' })}>
          {member.role === 'MEMBER' ? 'Make moderator' : 'Remove moderator role'}</button>}
        {moderate && (member.role === 'MEMBER' || (own && member.role === 'MODERATOR')) &&
          <button className="button-secondary" disabled={mutation.busy}
            onClick={() => void alter('/members/' + member.userId, 'DELETE')}>Remove member</button>}
      </li>)}</ul>
    </section>
    {moderate && <section className="workspace-panel"><h2>Invite members</h2>
      <form onSubmit={formAction(form => alter('/invitations', 'POST', { email: field(form, 'email') }))}>
        <fieldset disabled={mutation.busy}><Field label="Invitee email"><input type="email" name="email" required /></Field>
          <button>Send invitation</button></fieldset>
      </form>
      {invitations.length > 0 && <ul className="workspace-list">{invitations.map(invitation => <li key={invitation.userId}>
        {invitation.displayName ?? 'StudySphere member'}
        <button className="button-secondary" disabled={mutation.busy}
          onClick={() => void alter('/invitations/' + invitation.userId, 'DELETE')}>Revoke invitation</button>
      </li>)}</ul>}
    </section>}
    <section className="workspace-panel"><h2>Agenda</h2>
      {agenda.length ? <ol className="workspace-list">{agenda.map(item => <li key={item.id}>{item.text}
        {moderate && <button className="button-secondary" disabled={mutation.busy}
          onClick={() => void alter('/agenda/' + item.id, 'DELETE')}>Remove agenda item</button>}
      </li>)}</ol> : <p className="empty-state">No agenda items yet.</p>}
      {moderate && <form onSubmit={formAction(form => alter('/agenda', 'POST', { text: field(form, 'text') }))}>
        <fieldset disabled={mutation.busy}><Field label="Agenda item"><input name="text" required maxLength={1000} /></Field>
          <button>Add agenda item</button></fieldset>
      </form>}
    </section>
    <section className="workspace-panel"><h2>Shared resources</h2>
      {downloadError && <Feedback value={{ text: downloadError, error: true }} />}
      {shared.length ? <ul className="workspace-list">{shared.map(resource => <li key={resource.id}>
        <button className="button-secondary" onClick={() => void download(resource)}>Download {resource.title}</button>
        {(moderate || ownedResources.some(owned => owned.id === resource.id)) && <button className="button-secondary" disabled={mutation.busy}
          onClick={() => void alter('/resources/' + resource.id, 'DELETE')}>Remove shared resource</button>}
      </li>)}</ul> : <p className="empty-state">No resources have been shared.</p>}
      <form onSubmit={formAction(form => alter('/resources', 'POST', { resourceId: field(form, 'resourceId') }))}>
        <fieldset disabled={mutation.busy || shareable.length === 0}><Field label="Resource to share">
          <select name="resourceId" required><option value="">Select a resource</option>
            {shareable.map(resource => <option key={resource.id} value={resource.id}>{resource.title}</option>)}
          </select></Field><button>Share resource</button></fieldset>
      </form>
    </section>
    {own && <section className="workspace-panel"><h2>Room settings</h2>
      <form onSubmit={formAction(form => alter('', 'PATCH', roomFields(form)), false)}>
        <fieldset disabled={mutation.busy}><RoomFields room={room} /><button>Save room settings</button></fieldset>
      </form>
      {!deleteConfirm ? <button className="button-secondary" onClick={() => setDeleteConfirm(true)}>Delete room</button> :
        <><p>Deleting the room removes its membership and chat history.</p>
          <button className="button-secondary" onClick={() => setDeleteConfirm(false)}>Keep room</button>
          <button disabled={mutation.busy} onClick={async () => {
            try { await write(path, 'DELETE'); navigate('/rooms') }
            catch { await mutation.run(() => Promise.reject(new Error('Delete failed'))) }
          }}>Confirm room deletion</button></>}
    </section>}
  </>
}

function RoomChat({ roomId, path, moderate }: { roomId: string; path: string; moderate: boolean }) {
  const load = useCallback(() => all<Message>(path + '/messages'), [path])
  const messages = useResource(load)
  const reload = messages.reload
  const [connectedOnce, setConnectedOnce] = useState(false)
  const connected = useRoomSocket(roomId, () => {
    setConnectedOnce(true)
    void messages.reload().catch(() => messages.retry())
  })
  const mutation = useMutation(messages.reload)
  useEffect(() => {
    if (connected) return
    const id = window.setInterval(() => { void reload().catch(() => undefined) }, 15000)
    return () => window.clearInterval(id)
  }, [connected, reload])

  return <section className="workspace-panel"><h2>Room chat</h2>
    <p role="status">{connected ? 'Chat connected.' : connectedOnce ? 'Reconnecting to chat…' : 'Connecting to chat…'}</p>
    <Feedback value={mutation.feedback} />
    {!messages.data ? <Pending error={messages.error} retry={messages.retry} /> :
      messages.data.length === 0 ? <p className="empty-state">No messages yet.</p> :
        <ul className="workspace-list" aria-label="Room messages">{[...messages.data].sort((left, right) =>
          left.id.length - right.id.length || left.id.localeCompare(right.id)).map(message =>
          <li key={message.id}>
            <p><strong>{message.senderName ?? 'StudySphere member'}</strong> · <time dateTime={message.createdAt}>
              {new Date(message.createdAt).toLocaleString()}</time> {message.pinned && ' · Pinned'}</p>
            <p>{message.body}</p>
            {moderate && <button className="button-secondary" disabled={mutation.busy}
              onClick={() => void mutation.run(() => write(path + '/messages/' + message.id + '/pin',
                message.pinned ? 'DELETE' : 'PUT'))}>{message.pinned ? 'Unpin' : 'Pin'} message</button>}
          </li>)}</ul>}
    <form onSubmit={formAction(form => mutation.run(() => write(path + '/messages', 'POST',
      { body: field(form, 'body') }), 'Message sent.'))}>
      <fieldset disabled={mutation.busy}><Field label="Message"><textarea name="body" required maxLength={2000} /></Field>
        <button>Send message</button></fieldset>
    </form>
  </section>
}
