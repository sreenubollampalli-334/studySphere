import { FeatureHeader } from '../../components/FeatureHeader'
import { all, read, useMutation, useResource, write } from '../productivity/common'

interface Peer { userId: string; displayName: string | null }
interface Partner extends Peer {
  score: number
  institution: string | null
  program: string | null
  reasons: string[]
}
interface ConnectionRequest extends Peer {
  id: string
  direction: 'INCOMING' | 'OUTGOING'
  status: string
}
interface Conversation { id: string }

async function loadPartners() {
  return Promise.all([
    all<Partner>('/partners'),
    read<ConnectionRequest[]>('/connections/requests'),
    read<Peer[]>('/connections'),
    read<Peer[]>('/blocks'),
  ])
}

function peerName(peer: Peer) {
  return peer.displayName ?? 'StudySphere member'
}

export function PartnersPage({ openConversation }: { openConversation: (id: string) => void }) {
  const partners = useResource(loadPartners)
  const mutation = useMutation(partners.reload)

  async function message(peer: Peer) {
    await mutation.run(async () => {
      const response = await write('/conversations', 'POST', { userId: peer.userId })
      const conversation = await response.json() as Conversation
      openConversation(conversation.id)
    })
  }

  const pending = partners.data?.[1].filter(request => request.status === 'PENDING') ?? []
  const pendingIds = new Set(pending.map(request => request.userId))

  return (
    <section className="network-page" aria-labelledby="partners-heading">
      <FeatureHeader title="Study partners" description="Find study partners and manage your connections." headingId="partners-heading" />
      {partners.error && <p role="alert">{partners.error} <button onClick={partners.retry}>Retry</button></p>}
      {!partners.data && !partners.error && <p role="status">Loading study partners…</p>}
      {mutation.feedback && <p role={mutation.feedback.error ? 'alert' : 'status'}>{mutation.feedback.text}</p>}
      {partners.data && <>
        <h2>Suggested partners</h2>
        {partners.data[0].length === 0 && <p className="empty-state">No partners are available yet. Add subjects and availability to your profile.</p>}
        <ul className="network-list">
          {partners.data[0].map(peer => <li key={peer.userId}>
            <div><strong>{peerName(peer)}</strong> <span>{peer.score}% match</span></div>
            {(peer.program || peer.institution) && <p>{[peer.program, peer.institution].filter(Boolean).join(' · ')}</p>}
            {peer.reasons.length > 0 && <p>{peer.reasons.join(' · ')}</p>}
            <div className="network-actions">
              {pendingIds.has(peer.userId) ? <span>Request pending</span> : <button disabled={mutation.busy}
                onClick={() => void mutation.run(() => write('/connections/requests', 'POST', { userId: peer.userId }), 'Request sent.')}>
                Connect with {peerName(peer)}
              </button>}
              <button disabled={mutation.busy} className="button-secondary"
                onClick={() => void mutation.run(() => write('/blocks/' + peer.userId, 'PUT'), 'Member blocked.')}>
                Block {peerName(peer)}
              </button>
            </div>
          </li>)}
        </ul>
        <h2>Connection requests</h2>
        {pending.length === 0 && <p className="empty-state">No pending requests.</p>}
        <ul className="network-list">
          {pending.map(request => <li key={request.id}>
            <strong>{peerName(request)}</strong>
            <p>{request.direction === 'INCOMING' ? 'Incoming request' : 'Outgoing request'}</p>
            <div className="network-actions">
              {(request.direction === 'INCOMING'
                ? [['ACCEPTED', 'Accept'], ['DECLINED', 'Decline']] : [['CANCELED', 'Cancel']])
                .map(([status, action]) => <button key={status} disabled={mutation.busy}
                  onClick={() => void mutation.run(() => write('/connections/requests/' + request.id, 'PATCH', { status }))}>
                  {action} request from {peerName(request)}
                </button>)}
            </div>
          </li>)}
        </ul>
        <h2>Connections</h2>
        {partners.data[2].length === 0 && <p className="empty-state">No connections yet.</p>}
        <ul className="network-list">
          {partners.data[2].map(peer => <li key={peer.userId}>
            <strong>{peerName(peer)}</strong>
            <div className="network-actions">
              <button disabled={mutation.busy} onClick={() => void message(peer)}>Message {peerName(peer)}</button>
              <button disabled={mutation.busy} className="button-secondary"
                onClick={() => void mutation.run(() => write('/connections/' + peer.userId, 'DELETE'), 'Connection removed.')}>
                Remove {peerName(peer)}
              </button>
              <button disabled={mutation.busy} className="button-secondary"
                onClick={() => void mutation.run(() => write('/blocks/' + peer.userId, 'PUT'), 'Member blocked.')}>
                Block {peerName(peer)}
              </button>
            </div>
          </li>)}
        </ul>
        <h2>Blocked members</h2>
        {partners.data[3].length === 0 && <p className="empty-state">No blocked members.</p>}
        <ul className="network-list">
          {partners.data[3].map(peer => <li key={peer.userId}>
            <strong>{peerName(peer)}</strong>
            <button disabled={mutation.busy} className="button-secondary"
              onClick={() => void mutation.run(() => write('/blocks/' + peer.userId, 'DELETE'), 'Member unblocked.')}>
              Unblock {peerName(peer)}
            </button>
          </li>)}
        </ul>
      </>}
    </section>
  )
}
