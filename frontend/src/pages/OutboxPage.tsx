import { useEffect, useState } from 'react'
import { api } from '../api'
import { ErrorText } from '../components/ErrorText'
import { Button } from '../components/Button'
import { Link } from 'react-router-dom'
import { useAuth } from '../auth'
import { t, locale } from '../i18n'
import { levelText, type MailLevel } from './MailServerPage'

type Message = { id: number; createdAt: string; channel: string; recipient: string; subject: string | null; body: string; status: string; error: string | null; via: MailLevel | null }

const viaText: Record<MailLevel, string> = {
  COMPANY: t('Company'), PLATFORM: t('HR Pay'), ENV: '.env', NONE: t('None'),
}

export default function OutboxPage() {
  const [items, setItems] = useState<Message[]>([])
  const [server, setServer] = useState<MailLevel | null>(null)
  const [error, setError] = useState<string | null>(null)
  const { can } = useAuth()
  const load = () => Promise.all([
    api<Message[]>('/api/outbox').then(setItems),
    api<{ inUse: MailLevel }>('/api/outbox/server').then((s) => setServer(s.inUse)),
  ]).catch((e: Error) => setError(e.message))
  useEffect(() => { void load() }, [])
  return (
    <section>
      <div className="row between"><h2>{t('Sent messages')}</h2><Button className="secondary" onClick={() => load()}>{t('Refresh')}</Button></div>
      <p className="muted">{t('Codes and notifications sent by email and WhatsApp, newest first.')}</p>
      {server === 'NONE' && (
        <p className="notice">{t('No email server is set: emails are not sent, only kept here ("LOGGED"). Codes can be read here while testing.')}
          {can('company.manage') && <>{' '}<Link to="/company/email">{t('Set up an email server')}</Link></>}</p>
      )}
      {server && server !== 'NONE' && <p className="small muted">{t('Emails leave through')}: {levelText(server)}</p>}
      <ErrorText error={error} />
      <div className="table-wrap">
        <table>
          <thead><tr><th>{t('When')}</th><th>{t('Channel')}</th><th>{t('To')}</th><th>{t('Message')}</th><th>{t('Status')}</th><th>{t('Server')}</th></tr></thead>
          <tbody>
            {items.map((m) => (
              <tr key={m.id}>
                <td className="nowrap">{new Date(m.createdAt).toLocaleString(locale())}</td>
                <td>{m.channel}</td>
                <td>{m.recipient}</td>
                <td>{m.body}</td>
                <td>{m.status}{m.error ? <span className="error small"> {m.error}</span> : null}</td>
                <td className="small">{m.via ? viaText[m.via] : ''}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  )
}
