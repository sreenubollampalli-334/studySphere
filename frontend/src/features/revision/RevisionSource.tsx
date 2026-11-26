import { useCallback, useState } from 'react'
import { all, Field, Pending, useResource } from '../productivity/common'

interface Choice { id: string; name: string }
interface Document { id: string; title: string; status: string }

const loadSubjects = () => all<Choice>('/subjects')
const loadDocuments = () => all<Document>('/documents')

function TopicSource() {
  const subjects = useResource(loadSubjects)
  const [subject, setSubject] = useState('')
  const loadTopics = useCallback(() => subject ? all<Choice>('/subjects/' + subject + '/topics') : Promise.resolve([]), [subject])
  const topics = useResource(loadTopics)

  return <>
    <Field label="Subject"><select value={subject} required onChange={event => setSubject(event.target.value)}>
      <option value="">Select a subject</option>
      {subjects.data?.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}
    </select></Field>
    {!subjects.data && <Pending error={subjects.error} retry={subjects.retry} />}
    {subjects.data?.length === 0 && <p className="empty-state">No subjects are available yet.</p>}
    <Field label="Topic"><select key={subject} required name="topicId">
      <option value="">{subject ? 'Select a topic' : 'Choose a subject first'}</option>
      {topics.data?.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}
    </select></Field>
    {subject && !topics.data && <Pending error={topics.error} retry={topics.retry} />}
    {subject && topics.data?.length === 0 && <p className="empty-state">This subject has no topics yet.</p>}
  </>
}

function DocumentSource() {
  const documents = useResource(loadDocuments)
  const ready = documents.data?.filter(item => item.status === 'READY')

  return <>
    <Field label="Document"><select required name="documentId">
      <option value="">Select an indexed document</option>
      {ready?.map(item => <option key={item.id} value={item.id}>{item.title}</option>)}
    </select></Field>
    {!documents.data && <Pending error={documents.error} retry={documents.retry} />}
    {ready?.length === 0 && <p className="empty-state">No indexed documents are ready. Index a PDF or text file from Resources.</p>}
  </>
}

export function RevisionSource() {
  const [kind, setKind] = useState('topic')

  return <div className="study-source">
    <Field label="Source type"><select value={kind} onChange={event => setKind(event.target.value)}>
      <option value="topic">Topic</option><option value="document">Indexed document</option>
    </select></Field>
    {kind === 'topic' ? <TopicSource /> : <DocumentSource />}
  </div>
}
