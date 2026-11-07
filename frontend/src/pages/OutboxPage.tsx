import { useEffect, useState } from 'react'
import { api } from '../api'
import { ErrorText } from '../components/ErrorText'

type Message = { id: number; createdAt: string; channel: string; recipient: string; subject: string | null; body: string; status: string; error: string | null }

export default function OutboxPage() {
  const [items, setItems] = useState<Message[]>([])
  const [error, setError] = useState<string | null>(null)
  const load = () => api<Message[]>('/api/outbox').then(setItems).catch((e: Error) => setError(e.message))
  useEffect(() => { void load() }, [])
  return (
    <section>
      <div className="row between"><h2>Sent messages</h2><button className="secondary" onClick={() => void load()}>Refresh</button></div>
      <p className="muted">Codes and notifications. Without an email server configured, emails are only kept here ("LOGGED"), which is how to read codes while testing.</p>
      <ErrorText error={error} />
      <div className="table-wrap">
        <table>
          <thead><tr><th>When</th><th>Channel</th><th>To</th><th>Message</th><th>Status</th></tr></thead>
          <tbody>
            {items.map((m) => (
              <tr key={m.id}>
                <td className="nowrap">{new Date(m.createdAt).toLocaleString()}</td>
                <td>{m.channel}</td>
                <td>{m.recipient}</td>
                <td>{m.body}</td>
                <td>{m.status}{m.error ? <span className="error small"> {m.error}</span> : null}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  )
}
