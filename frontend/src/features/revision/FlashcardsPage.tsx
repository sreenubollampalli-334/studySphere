import { FeatureHeader } from '../../components/FeatureHeader'
import { useCallback, useState } from 'react'
import { all, Feedback, Field, field, Pending, read, useMutation, useResource, write } from '../productivity/common'
import { RevisionSource } from './RevisionSource'

interface Card { id: string; deckId: string; front: string; back: string; dueAt: string; intervalDays: number }
interface Deck { id: string; title: string; cardCount: number; cards?: Card[] }
interface Review { id: string; rating: string; reviewedAt: string; dueAt: string; intervalDays: number }
const ratings = ['AGAIN', 'HARD', 'GOOD', 'EASY'] as const
type Rating = (typeof ratings)[number]
const loadLibrary = () => Promise.all([all<Deck>('/flashcard-decks'), all<Card>('/flashcards/due')])
const formattedDate = (value: string) => new Date(value).toLocaleString()

export function FlashcardsPage() {
  const library = useResource(loadLibrary)
  const [selected, setSelected] = useState('')
  const [generate, setGenerate] = useState(false)
  const creation = useMutation(library.reload)

  return <>
    <FeatureHeader title="Flashcards" description="Review due cards and organize your revision into decks." />
    <form className="workspace-form study-tool-form" aria-busy={creation.busy} onSubmit={event => {
      event.preventDefault()
      const form = new FormData(event.currentTarget)
      void creation.run(async () => {
        const title = field(form, 'title')
        const topicId = field(form, 'topicId')
        const documentId = field(form, 'documentId')
        const response = generate
          ? await write('/flashcard-decks/generate', 'POST', { title, count: Number(field(form, 'count')),
            ...(topicId && { topicId }), ...(documentId && { documentId }) })
          : await write('/flashcard-decks', 'POST', {
            title, cards: [{ front: field(form, 'front'), back: field(form, 'back') }],
          })
        setSelected((await response.json() as Deck).id)
      }, 'Deck created.')
    }}>
      <h2>Create a deck</h2>
      <fieldset disabled={creation.busy}>
        <Field label="Deck title"><input name="title" required maxLength={120} /></Field>
        <Field label="How to create cards"><select value={generate ? 'generate' : 'manual'}
          onChange={event => setGenerate(event.target.value === 'generate')}>
          <option value="manual">Write my own</option><option value="generate">Generate from a source</option>
        </select></Field>
        {generate ? <>
          <RevisionSource />
          <Field label="Number of cards"><input name="count" type="number" min={1} max={10} defaultValue={5} required /></Field>
        </> : <>
          <Field label="Front"><textarea name="front" required maxLength={500} /></Field>
          <Field label="Back"><textarea name="back" required maxLength={1200} /></Field>
        </>}
        <button>{creation.busy ? 'Creating…' : 'Create deck'}</button>
      </fieldset>
    </form>
    {creation.busy && <p role="status">{generate ? 'Generating flashcards…' : 'Saving your deck…'}</p>}
    <Feedback value={creation.feedback} />
    {library.data ? <>
      <section className="workspace-panel">
        <h2>Due for review</h2>
        {library.data[1].length
          ? <DueCard key={library.data[1][0].id} card={library.data[1][0]} reload={library.reload} />
          : <p className="empty-state">No cards are due.</p>}
      </section>
      <section className="workspace-panel">
        <h2>Your decks</h2>
        {library.data[0].length ? <ul className="workspace-list">{library.data[0].map(item => <li key={item.id}>
          <button type="button" aria-pressed={selected === item.id} onClick={() => setSelected(item.id)}>
            {item.title} · {item.cardCount} cards
          </button>
        </li>)}</ul> : <p className="empty-state">No decks yet.</p>}
      </section>
    </> : <Pending error={library.error} retry={library.retry} />}
    {selected && <DeckDetail key={selected} id={selected} refreshLibrary={library.reload} onDelete={() => setSelected('')} />}
  </>
}

function DueCard({ card, reload }: { card: Card; reload: () => Promise<void> }) {
  const [revealed, setRevealed] = useState(false)
  const [submission, setSubmission] = useState<{ rating: Rating; requestId: string }>()
  const operation = useMutation(reload)

  function review(rating: Rating) {
    // Preserve the same ID and rating if the response was lost and the user retries.
    const request = submission ?? { rating, requestId: crypto.randomUUID() }
    setSubmission(request)
    void operation.run(() => write('/flashcards/' + card.id + '/reviews', 'POST', request), 'Review saved.')
  }

  return <article>
    <h3>{card.front}</h3>
    {revealed ? <>
      <p>{card.back}</p>
      <fieldset disabled={operation.busy}>
        <legend>How well did you remember?</legend>
        {ratings.map(rating => <button key={rating} type="button" disabled={!!submission && submission.rating !== rating}
          onClick={() => review(rating)}>{rating.charAt(0) + rating.slice(1).toLowerCase()}</button>)}
      </fieldset>
    </> : <button type="button" onClick={() => setRevealed(true)}>Reveal answer</button>}
    <Feedback value={operation.feedback} />
  </article>
}

function DeckDetail({ id, refreshLibrary, onDelete }: {
  id: string; refreshLibrary: () => Promise<void>; onDelete: () => void
}) {
  const path = '/flashcard-decks/' + id
  const load = useCallback(() => read<Deck>(path), [path])
  const deck = useResource(load)
  const change = useMutation(async () => { await Promise.all([refreshLibrary(), deck.reload()]) })
  const removal = useMutation(refreshLibrary)
  const [cardHistory, setCardHistory] = useState('')
  const [confirming, setConfirming] = useState(false)
  if (!deck.data) return <Pending error={deck.error} retry={deck.retry} />
  return <section className="workspace-panel">
    <h2>{deck.data.title}</h2>
    <form className="workspace-form" onSubmit={event => {
      event.preventDefault()
      const form = new FormData(event.currentTarget)
      void change.run(() => write(path + '/cards', 'POST', {
        front: field(form, 'front'), back: field(form, 'back'),
      }), 'Card added.')
    }}>
      <fieldset disabled={change.busy}>
        <legend>Add a card</legend>
        <Field label="Card front"><textarea name="front" required maxLength={500} /></Field>
        <Field label="Card back"><textarea name="back" required maxLength={1200} /></Field>
        <button>Add card</button>
      </fieldset>
    </form>
    <Feedback value={change.feedback} />
    <h3>Cards</h3>
    {deck.data.cards?.length ? <ul className="workspace-list">{deck.data.cards.map(item => <li key={item.id}>
      <strong>{item.front}</strong>
      <p>Due {formattedDate(item.dueAt)}</p>
      <button type="button" onClick={() => setCardHistory(value => value === item.id ? '' : item.id)}
        aria-expanded={cardHistory === item.id}>Review history</button>
      {cardHistory === item.id && <ReviewHistory id={item.id} />}
    </li>)}</ul> : <p className="empty-state">No cards in this deck.</p>}
    {confirming ? <>
      <p>Delete this deck and its cards?</p>
      <button type="button" disabled={removal.busy} onClick={() => {
        void removal.run(() => write(path, 'DELETE'), 'Deck deleted.').then(deleted => { if (deleted) onDelete() })
      }}>Confirm delete deck</button>
      <button type="button" disabled={removal.busy} onClick={() => setConfirming(false)}>Cancel</button>
    </> : <button type="button" onClick={() => setConfirming(true)}>Delete deck</button>}
    <Feedback value={removal.feedback} />
  </section>
}

function ReviewHistory({ id }: { id: string }) {
  const load = useCallback(() => all<Review>('/flashcards/' + id + '/reviews'), [id])
  const history = useResource(load)
  return history.data ? history.data.length ? <ul>{history.data.map(entry => <li key={entry.id}>
    {entry.rating} · {formattedDate(entry.reviewedAt)} · next due {formattedDate(entry.dueAt)}
  </li>)}</ul> : <p className="empty-state">No recorded reviews.</p> : <Pending error={history.error} retry={history.retry} />
}
