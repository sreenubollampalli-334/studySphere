import { FeatureHeader } from '../../components/FeatureHeader'
import { useCallback, useState } from 'react'
import type { FormEvent } from 'react'
import { authenticatedRequest } from '../auth/authApi'
import { indexResource } from '../documents/DocumentsPage'
import { all, Feedback, Field, field, Pending, read, useMutation, useResource, write } from '../productivity/common'

interface Resource {
  id: string
  title: string
  fileName: string
  sizeBytes: number
  folderId: string | null
  tags: string[]
  owned: boolean
}
interface Named { id: string; name: string }
interface Permission { userId: string; email: string }
interface Page { items: Resource[]; hasNext: boolean }

const defaultFilter = { q: '', tag: '', folderId: '', collectionId: '' }

export function ResourcesPage({ navigate }: { navigate?: (path: string) => void }) {
  const [view, setView] = useState('library')
  const [filter, setFilter] = useState(defaultFilter)
  const [page, setPage] = useState(0)
  const [selected, setSelected] = useState<Resource>()
  const load = useCallback(async () => {
    const params = new URLSearchParams({ page: String(page), size: '20' })
    if (view === 'library') {
      for (const [key, value] of Object.entries(filter)) if (value) params.set(key, value)
    }
    const path = view === 'bookmarks' ? '/bookmarks' : view === 'recent' ? '/resources/recent' : '/resources'
    const [resources, folders, collections, bookmarks] = await Promise.all([
      read<Page>(path + '?' + params),
      all<Named>('/folders'),
      all<Named>('/collections'),
      view === 'bookmarks' ? Promise.resolve([]) : all<Resource>('/bookmarks'),
    ])
    return {
      resources, folders, collections,
      bookmarked: new Set((view === 'bookmarks' ? resources.items : bookmarks).map(item => item.id)),
    }
  }, [filter, page, view])
  const library = useResource(load)
  const data = library.data
  const mutation = useMutation(library.reload)

  function filterLibrary(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const fields = new FormData(event.currentTarget)
    setFilter({
      q: field(fields, 'q'), tag: field(fields, 'tag'),
      folderId: field(fields, 'folderId'), collectionId: field(fields, 'collectionId'),
    })
    setPage(0)
    setSelected(undefined)
  }

  async function upload(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const form = event.currentTarget
    const fields = new FormData(form)
    for (const key of ['folderId', 'title', 'tags']) if (!field(fields, key)) fields.delete(key)
    if (await mutation.run(() => authenticatedRequest('/resources', { method: 'POST', body: fields }), 'File uploaded.')) {
      form.reset()
    }
  }

  return <>
    <FeatureHeader title="Resources" description="Search, organize, and share your course materials.">
      {data && <a className="feature-action" href="#resource-upload">Upload a resource</a>}
    </FeatureHeader>
    <Feedback value={mutation.feedback} />
    <div className="workspace-actions feature-tabs" role="group" aria-label="Resource views">
      {[['library', 'Library'], ['bookmarks', 'Bookmarks'], ['recent', 'Recently opened']].map(([key, label]) =>
        <button key={key} type="button" aria-pressed={view === key} className="button-secondary"
          onClick={() => { setView(key); setPage(0); setSelected(undefined) }}>
          {label}
        </button>)}
    </div>
    {!data ? <Pending error={library.error} retry={library.retry} /> : <>
      {view === 'library' && <form className="workspace-form" onSubmit={filterLibrary}>
        <div className="workspace-grid">
          <Field label="Search resources"><input name="q" defaultValue={filter.q} /></Field>
          <Field label="Filter by tag"><input name="tag" defaultValue={filter.tag} /></Field>
          <Field label="Filter by folder"><NamedOptions name="folderId" choices={data.folders}
            blank="All folders" initial={filter.folderId} /></Field>
          <Field label="Filter by collection"><NamedOptions name="collectionId" choices={data.collections}
            blank="All collections" initial={filter.collectionId} /></Field>
        </div>
        <button>Search</button>
      </form>}
      {data.resources.items.length === 0 ? <p className="empty-state">No resources in this view.</p> :
        <ul className="workspace-list feature-card-grid">{data.resources.items.map(item => <li key={item.id}>
          <strong>{item.title}</strong>
          <p>{item.fileName} · {new Intl.NumberFormat(undefined).format(item.sizeBytes)} bytes
            {!item.owned && ' · Shared with you'}</p>
          {item.tags.length > 0 && <p>{item.tags.join(', ')}</p>}
          <div className="workspace-actions">
            <button className="button-secondary" disabled={mutation.busy}
              onClick={() => void mutation.run(() => download(item), 'Download started.')}>Download {item.title}</button>
            <button className="button-secondary" disabled={mutation.busy}
              onClick={() => void mutation.run(() => write('/bookmarks/' + item.id,
                data.bookmarked.has(item.id) ? 'DELETE' : 'PUT'))}>
              {data.bookmarked.has(item.id) ? 'Remove bookmark' : 'Bookmark'} {item.title}
            </button>
            <button className="button-secondary" onClick={() => setSelected(item)}>Manage {item.title}</button>
            {navigate && item.owned && /\.(pdf|txt)$/i.test(item.fileName) && <button className="button-secondary"
              disabled={mutation.busy} onClick={() => {
                let id: string | undefined
                void mutation.run(async () => { id = (await indexResource(item.id)).id }, 'Study document opened.')
                  .then(saved => { if (saved && id) navigate('/documents/' + id) })
              }}>Study {item.title}</button>}
          </div>
        </li>)}</ul>}
      <div className="workspace-actions">
        {page > 0 && <button className="button-secondary" onClick={() => setPage(page - 1)}>Previous page</button>}
        {data.resources.hasNext && <button className="button-secondary" onClick={() => setPage(page + 1)}>Next page</button>}
      </div>
      {selected && <ResourceDetails key={selected.id} item={data.resources.items.find(item => item.id === selected.id) ?? selected}
        folders={data.folders} collections={data.collections}
        reload={library.reload} close={() => setSelected(undefined)} />}
      <form className="workspace-form" id="resource-upload" tabIndex={-1} onSubmit={upload}>
        <h2>Upload a resource</h2>
        <p>PDF, TXT, PNG, or JPEG files up to 20 MiB.</p>
        <fieldset disabled={mutation.busy}>
          <Field label="File"><input name="file" type="file" accept=".pdf,.txt,.png,.jpg,.jpeg" required /></Field>
          <Field label="Title"><input name="title" /></Field>
          <Field label="Tags, separated by commas"><input name="tags" /></Field>
          <Field label="Folder"><NamedOptions name="folderId" choices={data.folders} blank="No folder" /></Field>
          <button>Upload</button>
        </fieldset>
      </form>
      <div className="workspace-grid">
        <GroupManager endpoint="/folders" label="Folder" items={data.folders} reload={library.reload} />
        <GroupManager endpoint="/collections" label="Collection" items={data.collections} reload={library.reload} />
      </div>
    </>}
  </>
}

function NamedOptions({ name, choices, blank, initial }: { name: string; choices: Named[]; blank: string; initial?: string }) {
  return <select name={name} defaultValue={initial ?? ''}>
    <option value="">{blank}</option>
    {choices.map(choice => <option key={choice.id} value={choice.id}>{choice.name}</option>)}
  </select>
}

function GroupManager({ endpoint, label, items, reload }: {
  endpoint: string; label: string; items: Named[]; reload: () => Promise<void>
}) {
  const mutation = useMutation(reload)
  return <section>
    <form className="workspace-form" onSubmit={async event => {
      event.preventDefault()
      const form = event.currentTarget
      const name = field(new FormData(form), 'name')
      if (await mutation.run(() => write(endpoint, 'POST', { name }), label + ' created.')) form.reset()
    }}>
      <h2>{label}s</h2>
      <fieldset disabled={mutation.busy}>
        <Field label={label + ' name'}><input name="name" maxLength={100} required /></Field>
        <button>Create {label.toLowerCase()}</button>
      </fieldset>
    </form>
    <Feedback value={mutation.feedback} />
    {items.length === 0 ? <p className="empty-state">No {label.toLowerCase()}s yet.</p> : <ul className="workspace-list">
      {items.map(item => <li key={item.id}>{item.name} <button className="button-secondary" disabled={mutation.busy}
        onClick={() => { if (window.confirm('Delete ' + item.name + '?')) void mutation.run(() => write(endpoint + '/' + item.id, 'DELETE')) }}>
        Delete {item.name}
      </button></li>)}
    </ul>}
  </section>
}

function ResourceDetails({ item, folders, collections, reload, close }: {
  item: Resource; folders: Named[]; collections: Named[]; reload: () => Promise<void>; close: () => void
}) {
  const endpoint = '/resources/' + item.id
  const loadPermissions = useCallback(() => item.owned ? read<Permission[]>(endpoint + '/permissions') : Promise.resolve([]),
    [endpoint, item.owned])
  const permissions = useResource(loadPermissions)
  const mutation = useMutation(async () => { await Promise.all([reload(), permissions.reload()]) })
  return <section className="workspace-panel">
    <h2>Manage {item.title}</h2>
    <button className="button-secondary" onClick={close}>Close management</button>
    <Feedback value={mutation.feedback} />
    {item.owned && <>
      <form className="workspace-form" onSubmit={event => {
        event.preventDefault()
        const fields = new FormData(event.currentTarget)
        void mutation.run(() => write(endpoint, 'PATCH', {
          title: field(fields, 'title'), folderId: field(fields, 'folderId'),
          tags: field(fields, 'tags').split(',').map(tag => tag.trim()).filter(Boolean),
        }))
      }}>
        <fieldset disabled={mutation.busy}>
          <Field label="Resource title"><input name="title" defaultValue={item.title} required /></Field>
          <Field label="Resource tags"><input name="tags" defaultValue={item.tags.join(', ')} /></Field>
          <Field label="Move to folder"><NamedOptions name="folderId" choices={folders} blank="No folder"
            initial={item.folderId ?? ''} /></Field>
          <button>Save resource</button>
        </fieldset>
      </form>
      <h3>Sharing</h3>
      {!permissions.data ? <Pending error={permissions.error} retry={permissions.retry} /> :
        permissions.data.length === 0 ? <p className="empty-state">No one else has access.</p> :
          <ul className="workspace-list">{permissions.data.map(entry => <li key={entry.userId}>{entry.email}{' '}
            <button className="button-secondary" disabled={mutation.busy}
              onClick={() => void mutation.run(() => write(endpoint + '/permissions/' + entry.userId, 'DELETE'))}>
              Revoke {entry.email}
            </button></li>)}</ul>}
      <form className="workspace-form" onSubmit={async event => {
        event.preventDefault()
        const form = event.currentTarget
        const email = field(new FormData(form), 'email')
        if (await mutation.run(() => write(endpoint + '/permissions', 'POST', { email }), 'Access granted.')) form.reset()
      }}>
        <fieldset disabled={mutation.busy}>
          <Field label="Share with email"><input type="email" name="email" required /></Field>
          <button>Grant access</button>
        </fieldset>
      </form>
      <button className="button-secondary" disabled={mutation.busy} onClick={async () => {
        if (window.confirm('Delete ' + item.title + ' and its file?') &&
          await mutation.run(() => write(endpoint, 'DELETE'), 'Resource deleted.')) close()
      }}>Delete resource</button>
    </>}
    {collections.length > 0 && <form className="workspace-form" onSubmit={event => {
      event.preventDefault()
      const fields = new FormData(event.currentTarget)
      const collectionId = field(fields, 'collectionId')
      if (collectionId) void mutation.run(() => write('/collections/' + collectionId + '/resources/' + item.id,
        field(fields, 'operation')))
    }}>
      <fieldset disabled={mutation.busy}>
        <Field label="Collection"><NamedOptions name="collectionId" choices={collections} blank="Select a collection" /></Field>
        <Field label="Action"><select name="operation"><option value="PUT">Add resource</option>
          <option value="DELETE">Remove resource</option></select></Field>
        <button>Update collection</button>
      </fieldset>
    </form>}
  </section>
}

async function download(item: Resource) {
  const response = await authenticatedRequest('/resources/' + item.id + '/download')
  const url = URL.createObjectURL(await response.blob())
  const link = document.createElement('a')
  link.href = url
  link.download = item.fileName
  document.body.append(link)
  link.click()
  link.remove()
  window.setTimeout(() => URL.revokeObjectURL(url), 1000)
}
