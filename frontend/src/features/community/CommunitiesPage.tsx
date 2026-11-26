import { FeatureHeader } from '../../components/FeatureHeader'
import { useCallback, useState } from 'react'
import type { FormEvent } from 'react'
import { authenticatedRequest, messageFor } from '../auth/authApi'
import { all, Feedback, Field, field, Pending, read, SubjectOptions, useMutation, useResource, write } from '../productivity/common'
import type { Subject } from '../productivity/common'

type Role = 'OWNER' | 'MODERATOR' | 'MEMBER'
interface Community { id: string; subjectId: string; name: string; description: string | null; role: Role | null }
interface Post { id: string; authorId: string; authorName: string | null; title: string; body: string; score: number; myVote: number | null; createdAt: string }
interface Comment { id: string; authorId: string; authorName: string | null; body: string }
interface Member { userId: string; displayName: string | null; role: Role }
interface SharedFile { id: string; title: string; fileName: string; owned?: boolean }
type Navigate = (path: string) => void

const loadDiscovery = () => Promise.all([all<Community>('/communities'), all<Subject>('/subjects')])

function submit(action: (form: FormData) => Promise<boolean>) {
  return async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const form = event.currentTarget
    if (await action(new FormData(form))) form.reset()
  }
}

function ReportContent({ path, label }: { path: string; label: string }) {
  const [open, setOpen] = useState(false)
  const mutation = useMutation(async () => setOpen(false))
  return <>
    <button type="button" className="button-secondary" aria-expanded={open}
      onClick={() => setOpen(current => !current)}>Report {label}</button>
    {open && <form className="auth-form" onSubmit={submit(form => mutation.run(
      () => write(path + '/report', 'POST', { reason: field(form, 'reason') }), 'Report submitted.',
    ))}>
      <fieldset disabled={mutation.busy}>
        <Field label="Report reason"><textarea name="reason" required maxLength={500} rows={2} /></Field>
        <button>Submit report</button>
      </fieldset>
    </form>}
    <Feedback value={mutation.feedback} />
  </>
}

export function CommunitiesPage({ navigate }: { navigate: Navigate }) {
  const discovery = useResource(loadDiscovery)
  const mutation = useMutation(discovery.reload)
  const [creating, setCreating] = useState(false)
  const data = discovery.data
  return <>
    <FeatureHeader title="Course communities" description="Find course discussions, shared resources, and classmates.">
      <button onClick={() => setCreating(value => !value)} aria-expanded={creating}>Create community</button>
    </FeatureHeader>
    <Feedback value={mutation.feedback} />
    {!data ? <Pending error={discovery.error} retry={discovery.retry} /> : <>
      {creating && <form onSubmit={submit(form => mutation.run(async () => {
        const response = await write('/communities', 'POST', {
          subjectId: field(form, 'subjectId'), name: field(form, 'name'), description: field(form, 'description'),
        })
        navigate('/communities/' + (await response.json() as Community).id)
      }, 'Community created.'))}><fieldset disabled={mutation.busy}>
        <Field label="Subject"><select name="subjectId" required><SubjectOptions subjects={data[1]} required /></select></Field>
        <Field label="Community name"><input name="name" maxLength={120} required /></Field>
        <Field label="Description"><textarea name="description" maxLength={600} /></Field>
        <button>Create course community</button>
      </fieldset></form>}
      {data[0].length === 0 ? <p className="empty-state">No course communities yet.</p> :
        <ul className="workspace-list feature-card-grid">{data[0].map(community => <li key={community.id}>
          <a href={'/communities/' + community.id} onClick={event => {
            if (event.button === 0 && !event.metaKey && !event.ctrlKey && !event.altKey && !event.shiftKey) {
              event.preventDefault()
              navigate('/communities/' + community.id)
            }
          }}>{community.name}</a>
          {community.description && <p>{community.description}</p>}
          <p>{data[1].find(subject => subject.id === community.subjectId)?.name ?? 'Course community'}
            {community.role ? ' · ' + community.role.toLowerCase() : ''}</p>
        </li>)}</ul>}
    </>}
  </>
}

export function CommunityPage({ id, navigate }: { id: string; navigate: Navigate }) {
  const path = '/communities/' + id
  const load = useCallback(() => read<Community>(path), [path])
  const detail = useResource(load)
  const mutation = useMutation(detail.reload)
  const community = detail.data
  return <>
    <FeatureHeader title={community?.name ?? 'Course community'} description={community?.description ?? undefined}>
      <button className="button-secondary" onClick={() => navigate('/communities')}>All communities</button>
    </FeatureHeader>
    <Feedback value={mutation.feedback} />
    {!community ? <Pending error={detail.error} retry={detail.retry} /> : community.role ? <>
      {community.role !== 'OWNER' && <button disabled={mutation.busy} className="button-secondary"
        onClick={() => void mutation.run(() => write(path + '/members/me', 'DELETE'), 'You left the community.')}>Leave community</button>}
      <CommunityMemberArea community={community} path={path} />
    </> : <>
      <p>Join to participate in discussions and view course resources.</p>
      <button disabled={mutation.busy}
        onClick={() => void mutation.run(() => write(path + '/members/me', 'PUT'), 'You joined the community.')}>Join community</button>
    </>}
  </>
}

function CommunityMemberArea({ community, path }: { community: Community; path: string }) {
  const load = useCallback(() => Promise.all([
    all<Post>('/posts?communityId=' + community.id), read<{ id: string }>('/me'),
    all<Member>(path + '/members'), all<SharedFile>(path + '/resources'), all<SharedFile>('/resources'),
  ]), [community.id, path])
  const data = useResource(load)
  const mutation = useMutation(data.reload)
  const [openPost, setOpenPost] = useState<string>()
  if (!data.data) return <Pending error={data.error} retry={data.retry} />

  const [posts, account, members, resources, library] = data.data
  const moderate = community.role === 'OWNER' || community.role === 'MODERATOR'
  const shareable = library.filter(resource => resource.owned && !resources.some(shared => shared.id === resource.id))
  const change = (endpoint: string, method: string, body?: unknown, success?: string) =>
    mutation.run(() => write(endpoint, method, body), success)

  return <>
    <Feedback value={mutation.feedback} />
    <section className="workspace-panel"><h2>Discussion</h2>
      <form onSubmit={submit(form => change('/posts', 'POST', { communityId: community.id,
        title: field(form, 'title'), body: field(form, 'body'), allowAiSummary: form.has('allowAiSummary') }, 'Question posted.'))}>
        <fieldset disabled={mutation.busy}>
          <Field label="Question title"><input name="title" required maxLength={160} /></Field>
          <Field label="Question details"><textarea name="body" required maxLength={8000} /></Field>
          <Field label="Include this post in AI summaries"><input type="checkbox" name="allowAiSummary" /></Field>
          <button>Post question</button>
        </fieldset>
      </form>
      {posts.length === 0 ? <p className="empty-state">No questions have been posted.</p> : <ul className="workspace-list">{posts.map(post => <li key={post.id}>
        <h3>{post.title}</h3><p>{post.body}</p><p>{post.authorName || 'StudySphere member'} · {post.score} votes</p>
        <div className="workspace-actions">
          <button disabled={mutation.busy} aria-pressed={post.myVote === 1} aria-label={'Upvote ' + post.title}
            onClick={() => void change('/posts/' + post.id + '/vote', post.myVote === 1 ? 'DELETE' : 'PUT',
              post.myVote === 1 ? undefined : { value: 1 })}>Upvote</button>
          <button disabled={mutation.busy} aria-pressed={post.myVote === -1} aria-label={'Downvote ' + post.title}
            onClick={() => void change('/posts/' + post.id + '/vote', post.myVote === -1 ? 'DELETE' : 'PUT',
              post.myVote === -1 ? undefined : { value: -1 })}>Downvote</button>
          <button className="button-secondary" aria-expanded={openPost === post.id}
            onClick={() => setOpenPost(current => current === post.id ? undefined : post.id)}>Replies and related questions</button>
          {(moderate || post.authorId === account.id) && <button className="button-secondary" disabled={mutation.busy}
            onClick={() => void change('/posts/' + post.id, 'DELETE', undefined, 'Question removed.')}>Remove question</button>}
        </div>
        {post.authorId !== account.id && <ReportContent path={'/posts/' + post.id} label="question" />}
        {openPost === post.id && <PostReplies key={post.id} postId={post.id} userId={account.id} moderate={moderate} />}
      </li>)}</ul>}
    </section>
    <CommunitySummary path={path} />
    <CommunityResources path={path} resources={resources} shareable={shareable} library={library}
      moderate={moderate} busy={mutation.busy} change={change} />
    <section className="workspace-panel"><h2>Members</h2>
      <ul className="workspace-list">{members.map(member => <li key={member.userId}>
        {member.displayName || 'StudySphere member'} · {member.role.toLowerCase()}
        {community.role === 'OWNER' && member.role !== 'OWNER' && <button className="button-secondary" disabled={mutation.busy}
          onClick={() => void change(path + '/members/' + member.userId, 'PATCH',
            { role: member.role === 'MODERATOR' ? 'MEMBER' : 'MODERATOR' })}>
          {member.role === 'MODERATOR' ? 'Remove moderator role' : 'Make moderator'}</button>}
      </li>)}</ul>
    </section>
  </>
}

function PostReplies({ postId, userId, moderate }: { postId: string; userId: string; moderate: boolean }) {
  const path = '/posts/' + postId
  const load = useCallback(() => Promise.all([all<Comment>(path + '/comments'), all<Post>(path + '/related')]), [path])
  const content = useResource(load)
  const mutation = useMutation(content.reload)
  return <section><h4>Replies</h4>
    <Feedback value={mutation.feedback} />
    {!content.data ? <Pending error={content.error} retry={content.retry} /> : <>
      {content.data[0].length === 0 ? <p className="empty-state">No replies yet.</p> : <ul className="workspace-list">{content.data[0].map(comment => <li key={comment.id}>
        <p>{comment.authorName || 'StudySphere member'}</p><p>{comment.body}</p>
        {(moderate || comment.authorId === userId) && <button className="button-secondary" disabled={mutation.busy}
          onClick={() => void mutation.run(() => write(path + '/comments/' + comment.id, 'DELETE'), 'Reply removed.')}>Remove reply</button>}
        {comment.authorId !== userId && <ReportContent path={path + '/comments/' + comment.id} label="reply" />}
      </li>)}</ul>}
      <form onSubmit={submit(form => mutation.run(() => write(path + '/comments', 'POST', { body: field(form, 'body') }), 'Reply posted.'))}>
        <fieldset disabled={mutation.busy}><Field label="Reply"><textarea name="body" required maxLength={4000} /></Field>
          <button>Post reply</button></fieldset>
      </form>
      <h4>Related questions</h4>
      {content.data[1].length === 0 ? <p className="empty-state">No related questions found.</p> :
        <ul className="workspace-list">{content.data[1].map(post => <li key={post.id}><strong>{post.title}</strong><p>{post.body}</p></li>)}</ul>}
    </>}
  </section>
}

function CommunitySummary({ path }: { path: string }) {
  const [summary, setSummary] = useState('')
  const [allowed, setAllowed] = useState(false)
  const mutation = useMutation(async () => {})
  return <section><h2>Discussion summary</h2>
    <p>Recent visible posts whose authors opted in can be sent to the configured AI provider for a summary.</p>
    <Field label="I agree to send the opted-in posts"><input type="checkbox" checked={allowed}
      onChange={event => setAllowed(event.target.checked)} /></Field>
    <button disabled={!allowed || mutation.busy} onClick={() => void mutation.run(async () => {
      setSummary('')
      const response = await write(path + '/summaries', 'POST', { consent: true })
      setSummary((await response.json() as { summary: string }).summary)
    }, 'Summary generated.')}>{mutation.busy ? 'Generating summary…' : 'Generate summary'}</button>
    <Feedback value={mutation.feedback} />{summary && <p className="workspace-text">{summary}</p>}
  </section>
}

function CommunityResources({ path, resources, shareable, library, moderate, busy, change }: {
  path: string; resources: SharedFile[]; shareable: SharedFile[]; library: SharedFile[]
  moderate: boolean; busy: boolean
  change: (path: string, method: string, body?: unknown, success?: string) => Promise<boolean>
}) {
  const [error, setError] = useState('')
  async function download(resource: SharedFile) {
    setError('')
    try {
      const response = await authenticatedRequest(path + '/resources/' + resource.id + '/download')
      const url = URL.createObjectURL(await response.blob())
      const link = document.createElement('a')
      link.href = url
      link.download = resource.fileName
      link.click()
      window.setTimeout(() => URL.revokeObjectURL(url), 1000)
    } catch (failure) {
      setError(messageFor(failure, 'Could not download this community resource.'))
    }
  }

  return <section className="workspace-panel"><h2>Course resources</h2>
    {error && <Feedback value={{ text: error, error: true }} />}
    {resources.length === 0 ? <p className="empty-state">No course resources yet.</p> : <ul className="workspace-list">{resources.map(resource => <li key={resource.id}>
      <button className="button-secondary" onClick={() => void download(resource)}>Download {resource.title}</button>
      {(moderate || library.some(file => file.id === resource.id && file.owned)) && <button className="button-secondary" disabled={busy}
        onClick={() => void change(path + '/resources/' + resource.id, 'DELETE', undefined, 'Resource removed.')}>Remove resource</button>}
    </li>)}</ul>}
    {shareable.length > 0 && <form onSubmit={submit(form => change(path + '/resources/' + field(form, 'resourceId'),
      'PUT', undefined, 'Resource added.'))}><fieldset disabled={busy}>
      <Field label="Resource to add"><select name="resourceId" required><option value="">Select a resource</option>
        {shareable.map(resource => <option key={resource.id} value={resource.id}>{resource.title}</option>)}
      </select></Field><button>Add resource</button>
    </fieldset></form>}
  </section>
}
