import { useEffect, useState } from 'react'
import { api } from '../api'
import { ErrorText } from '../components/ErrorText'
import { Button } from '../components/Button'
import { t, locale } from '../i18n'

type Message = { id: number; createdAt: string; channel: string; recipient: string; subject: string | null; body: string; status: string; error: string | null }

export default function OutboxPage() {
  const [items, setItems] = useState<Message[]>([])
  const [error, setError] = useState<string | null>(null)
  const load = () => api<Message[]>('/api/outbox').then(setItems).catch((e: Error) => setError(e.message))
  useEffect(() => { void load() }, [])
  return (
    <section>
      <div className="row between"><h2>{t('Sent messages')}</h2><Button className="secondary" onClick={() => load()}>{t('Refresh')}</Button></div>
      <p className="muted">{t('Codes and notifications. Without an email server configured, emails are only kept here ("LOGGED"), which is how to read codes while testing.')}</p>
      <ErrorText error={error} />
      <div className="table-wrap">
        <table>
          <thead><tr><th>{t('When')}</th><th>{t('Channel')}</th><th>{t('To')}</th><th>{t('Message')}</th><th>{t('Status')}</th></tr></thead>
          <tbody>
            {items.map((m) => (
              <tr key={m.id}>
                <td className="nowrap">{new Date(m.createdAt).toLocaleString(locale())}</td>
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
