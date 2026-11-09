import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { ApiError, api, longDay, type Site } from '../api'
import { useAuth } from '../auth'
import { Button, Form } from '../components/Button'
import { useAsk } from '../components/Dialogs'
import { ErrorText } from '../components/ErrorText'
import type { OrgUnit } from '../org'
import { t } from '../i18n'

type Audience = 'ALL' | 'UNIT' | 'SITE'
type Announcement = { id: number; title: string; body: string; audience: Audience; audienceName: string | null; createdBy: string; createdAt: string }
type Summary = {
  id: number; title: string; audience: Audience; audienceName: string | null; whatsapp: boolean; recipients: number
  byWhatsapp: number; byEmail: number; createdBy: string; createdAt: string
}
type Preview = {
  recipients: number; byWhatsapp: number; byEmail: number; inAppOnly: number; whatsappAvailable: boolean
  templateApproved: boolean; estimatedCostUsd: number; pricePerMessageUsd: number
}

const audienceText = (a: Audience, name: string | null) =>
  a === 'ALL' ? t('Everyone') : a === 'UNIT' ? t('Unit: {a}', { a: name ?? '' }) : t('Site: {a}', { a: name ?? '' })

const fail = (e: unknown) => e instanceof ApiError ? [e.message, ...Object.values(e.fieldErrors)].join(' ') : t('Something went wrong.')

/** Announcements: what was addressed to me, and for HR, writing and sending one. */
export default function AnnouncementsPage() {
  const { me } = useAuth()
  const hr = !!me?.permissions.includes('announcement.send')
  const [mine, setMine] = useState<Announcement[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    api<Announcement[]>('/api/announcements/mine').then(setMine).catch((e: Error) => setError(e.message))
  }, [])

  return (
    <section>
      <h2>{t('Announcements')}</h2>
      <ErrorText error={error} />
      {hr && <SendAnnouncement />}
      <div className="card">
        <h3>{t('For me')}</h3>
        {mine && mine.length === 0 && <p className="muted">{t('No announcement yet.')}</p>}
        {mine && mine.length > 0 && (
          <table>
            <thead><tr><th>{t('Date')}</th><th>{t('Title')}</th><th>{t('From')}</th></tr></thead>
            <tbody>
              {mine.map((a) => (
                <tr key={a.id}>
                  <td>{longDay(a.createdAt)}</td>
                  <td><Link to={`/announcements/${a.id}`}>{a.title}</Link></td>
                  <td className="small">{a.createdBy}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </section>
  )
}

function SendAnnouncement() {
  const ask = useAsk()
  const [title, setTitle] = useState('')
  const [body, setBody] = useState('')
  const [audience, setAudience] = useState<Audience>('ALL')
  const [unitId, setUnitId] = useState('')
  const [siteId, setSiteId] = useState('')
  const [whatsapp, setWhatsapp] = useState(true)
  const [units, setUnits] = useState<OrgUnit[]>([])
  const [sites, setSites] = useState<Site[]>([])
  const [preview, setPreview] = useState<Preview | null>(null)
  const [sent, setSent] = useState<Summary[]>([])
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)

  const loadSent = () => api<Summary[]>('/api/announcements').then(setSent).catch((e: Error) => setError(e.message))
  useEffect(() => {
    void loadSent()
    api<OrgUnit[]>('/api/org/units').then(setUnits).catch(() => undefined)
    api<Site[]>('/api/sites').then(setSites).catch(() => undefined)
  }, [])

  const input = () => ({
    title, body, audience, whatsapp,
    unitId: audience === 'UNIT' && unitId ? Number(unitId) : null,
    siteId: audience === 'SITE' && siteId ? Number(siteId) : null,
  })

  // The audience and the cost follow the choices as they are made.
  useEffect(() => {
    if (audience === 'UNIT' && !unitId) { setPreview(null); return }
    if (audience === 'SITE' && !siteId) { setPreview(null); return }
    api<Preview>('/api/announcements/preview', { method: 'POST', body: input() }).then(setPreview).catch(() => setPreview(null))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [audience, unitId, siteId, whatsapp])

  const send = () => ask({
    title: t('Send this announcement?'), confirmLabel: t('Send'),
    message: preview ? t('{a} people will receive it. It cannot be withdrawn once sent.', { a: preview.recipients }) : '',
    onConfirm: async () => {
      setError(null)
      try {
        const s = await api<Summary>('/api/announcements', { method: 'POST', body: input() })
        setMessage(t('Announcement sent to {a} people.', { a: s.recipients }))
        setTitle('')
        setBody('')
        await loadSent()
      } catch (e) { setError(fail(e)) }
    },
  })

  return (
    <>
      <div className="card">
        <h3>{t('New announcement')}</h3>
        <Form onSubmit={send}>
          <label>{t('Title')}<input value={title} maxLength={120} required onChange={(e) => setTitle(e.target.value)} /></label>
          <label>{t('Message')}<textarea value={body} rows={6} maxLength={5000} required onChange={(e) => setBody(e.target.value)} /></label>
          <div className="row">
            <label>{t('Who receives it')}
              <select value={audience} onChange={(e) => setAudience(e.target.value as Audience)}>
                <option value="ALL">{t('Everyone')}</option>
                <option value="UNIT">{t('A unit and the units below it')}</option>
                <option value="SITE">{t('A site')}</option>
              </select>
            </label>
            {audience === 'UNIT' && (
              <label>{t('Unit')}
                <select value={unitId} required onChange={(e) => setUnitId(e.target.value)}>
                  <option value="">—</option>
                  {units.map((u) => <option key={u.id} value={u.id}>{u.name}</option>)}
                </select>
              </label>
            )}
            {audience === 'SITE' && (
              <label>{t('Site')}
                <select value={siteId} required onChange={(e) => setSiteId(e.target.value)}>
                  <option value="">—</option>
                  {sites.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
                </select>
              </label>
            )}
          </div>
          <label className="check">
            <input type="checkbox" checked={whatsapp} onChange={(e) => setWhatsapp(e.target.checked)} />
            {t('Also send a WhatsApp notice to those who agreed')}
          </label>
          {preview && (
            <div className="notice">
              <p>{t('{a} people: {b} by WhatsApp, {c} by email, {d} only in HR Pay. Everyone sees it in HR Pay.',
                { a: preview.recipients, b: preview.byWhatsapp, c: preview.byEmail, d: preview.inAppOnly })}</p>
              {whatsapp && preview.byWhatsapp > 0 && (
                <p className="small">{t('Estimated Meta cost: about {a} USD ({b} USD per message). The WhatsApp notice only says there is an announcement; it is read in HR Pay.',
                  { a: preview.estimatedCostUsd.toFixed(2), b: String(preview.pricePerMessageUsd) })}</p>
              )}
              {whatsapp && !preview.whatsappAvailable && <p className="small">{t('WhatsApp is not turned on for this company: emails go instead.')}</p>}
              {whatsapp && preview.whatsappAvailable && !preview.templateApproved && (
                <p className="small">{t('Meta has not approved the announcement template yet: emails go instead.')}</p>
              )}
            </div>
          )}
          <ErrorText error={error} />
          {message && <p className="success">{message}</p>}
          <Button type="submit" disabled={!preview || preview.recipients === 0}>{t('Send')}</Button>
        </Form>
      </div>
      {sent.length > 0 && (
        <div className="card">
          <h3>{t('Sent')}</h3>
          <table>
            <thead><tr><th>{t('Date')}</th><th>{t('Title')}</th><th>{t('Who receives it')}</th><th>{t('People')}</th><th>WhatsApp</th><th>{t('Email')}</th><th>{t('By')}</th></tr></thead>
            <tbody>
              {sent.map((s) => (
                <tr key={s.id}>
                  <td>{longDay(s.createdAt)}</td>
                  <td><Link to={`/announcements/${s.id}`}>{s.title}</Link></td>
                  <td>{audienceText(s.audience, s.audienceName)}</td>
                  <td>{s.recipients}</td>
                  <td>{s.whatsapp ? s.byWhatsapp : '—'}</td>
                  <td>{s.byEmail}</td>
                  <td className="small">{s.createdBy}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  )
}

/** One announcement, opened from the bell, WhatsApp or an email. */
export function AnnouncementPage() {
  const { id } = useParams()
  const [a, setA] = useState<Announcement | null>(null)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => {
    api<Announcement>(`/api/announcements/${id}`).then(setA).catch((e: Error) => setError(e.message))
  }, [id])
  return (
    <section>
      <p><Link to="/announcements">← {t('Announcements')}</Link></p>
      <ErrorText error={error} />
      {a && (
        <div className="card">
          <h2>{a.title}</h2>
          <p className="muted small">{longDay(a.createdAt)} · {a.createdBy} · {audienceText(a.audience, a.audienceName)}</p>
          <p style={{ whiteSpace: 'pre-wrap' }}>{a.body}</p>
        </div>
      )}
    </section>
  )
}
