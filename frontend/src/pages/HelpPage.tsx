import { useEffect, useMemo, useState } from 'react'
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom'
import { CirclePlay, LifeBuoy, Search } from 'lucide-react'
import { api, longDay } from '../api'
import { useAuth } from '../auth'
import { Button, Form } from '../components/Button'
import { ErrorText } from '../components/ErrorText'
import { Markdown } from '../components/Markdown'
import { Modal } from '../components/Modal'
import { ARTICLES, articleText, type Article } from '../help'
import { notes, RELEASES } from '../releases'
import { locale, t } from '../i18n'

export type Ticket = {
  id: number; number: string; companyId: number; companyName: string; userId: number; userName: string; userEmail: string
  subject: string; category: 'PROBLEM' | 'QUESTION' | 'IDEA'; status: 'OPEN' | 'ANSWERED' | 'CLOSED'; page: string | null
  appVersion: string | null; userAgent: string | null; createdAt: string; updatedAt: string; messages: number; lastFromProvider: boolean
}
export type TicketMessage = { id: number; fromProvider: boolean; authorName: string; body: string; createdAt: string }
export type TicketThread = { ticket: Ticket; messages: TicketMessage[] }

export const TICKET_STATUS: Record<Ticket['status'], [string, string]> = {
  OPEN: [t('Waiting for support'), 'badge warn'],
  ANSWERED: [t('Answered'), 'badge ok'],
  CLOSED: [t('Closed'), 'badge'],
}
export const TICKET_CATEGORY: Record<Ticket['category'], string> = {
  PROBLEM: t('Problem'), QUESTION: t('Question'), IDEA: t('Idea'),
}

const AUDIENCES: [Article['audience'], string][] = [
  ['everyone', t('For everyone')], ['managers', t('For managers')], ['hr', t('For HR')], ['payroll', t('For payroll and finance')], ['admin', t('For administrators')],
]

/** The version the server runs, shared by the menu and the report form. */
let versionCache: Promise<{ version: string; builtAt: string | null; demo: boolean }> | null = null
export const serverVersion = () => (versionCache ??= api<{ version: string; builtAt: string | null; demo: boolean }>('/api/version')
  .catch(() => ({ version: 'unknown', builtAt: null, demo: false })))

/** The help centre: articles, the person's requests to support, and what's new. */
export default function HelpPage() {
  const { can } = useAuth()
  const [query, setQuery] = useState('')
  const [tickets, setTickets] = useState<Ticket[] | null>(null)
  const [reporting, setReporting] = useState(false)
  const navigate = useNavigate()
  const load = () => api<Ticket[]>('/api/tickets').then(setTickets).catch(() => setTickets([]))
  useEffect(() => { void load() }, [])

  const found = useMemo(() => {
    const q = query.trim().toLowerCase()
    if (!q) return null
    return ARTICLES.filter((a) => { const x = articleText(a); return (x.title + ' ' + x.body).toLowerCase().includes(q) })
  }, [query])

  return (
    <section>
      <div className="row between">
        <h2>{t('Help')}</h2>
        <div className="row">
          {can('company.manage') && <Link className="button secondary" to="/setup">{t('Set-up guide')}</Link>}
          <Button onClick={() => setReporting(true)}><LifeBuoy size={16} aria-hidden />{' '}{t('Report a problem')}</Button>
        </div>
      </div>
      <label className="search-field"><Search size={16} aria-hidden />
        <input type="search" placeholder={t('Search the help, for example: leave, payslip, check-in')} value={query} onChange={(e) => setQuery(e.target.value)} aria-label={t('Search the help')} />
      </label>

      {found ? (
        <div className="card">
          <h3>{found.length === 0 ? t('Nothing found. Try another word, or report a problem.') : t('{n} article(s) found', { n: found.length })}</h3>
          <ul className="link-list">{found.map((a) => <li key={a.id}><Link to={`/help/${a.id}`}>{articleText(a).title}</Link></li>)}</ul>
        </div>
      ) : (
        <div className="cards2">
          {AUDIENCES.map(([key, label]) => {
            const list = ARTICLES.filter((a) => a.audience === key)
            return list.length === 0 ? null : (
              <div key={key} className="card">
                <h3>{label}</h3>
                <ul className="link-list">{list.map((a) => <li key={a.id}><Link to={`/help/${a.id}`}>{articleText(a).title}</Link>{a.video && <CirclePlay size={14} aria-label={t('Video')} />}</li>)}</ul>
              </div>
            )
          })}
        </div>
      )}

      <div className="card">
        <h3>{can('role.assign') ? t('Requests to support from the company') : t('My requests to support')}</h3>
        {tickets && tickets.length === 0 && <p className="muted">{t('No request yet.')}</p>}
        {tickets && tickets.length > 0 && <TicketTable rows={tickets} link={(tk) => `/support/${tk.id}`} />}
      </div>

      <div className="card">
        <div className="row between"><h3>{t('What’s new')}</h3><Link to="/whats-new">{t('All versions')}</Link></div>
        <p className="small muted">{t('Version {a}', { a: RELEASES[0].version })} · {longDay(RELEASES[0].date)}</p>
        <ul>{notes(RELEASES[0]).map((n) => <li key={n}>{n}</li>)}</ul>
      </div>

      {reporting && <ReportProblem onClose={() => setReporting(false)} onDone={(id) => { setReporting(false); navigate(`/support/${id}`) }} />}
    </section>
  )
}

export function TicketTable({ rows, link, company }: { rows: Ticket[]; link: (t: Ticket) => string; company?: boolean }) {
  return (
    <div className="table-wrap">
      <table>
        <thead><tr><th>{t('No.')}</th>{company && <th>{t('Company')}</th>}<th>{t('Subject')}</th><th>{t('From')}</th><th>{t('Last change')}</th><th>{t('Status')}</th></tr></thead>
        <tbody>
          {rows.map((tk) => (
            <tr key={tk.id}>
              <td className="mono"><Link to={link(tk)}>{tk.number}</Link></td>
              {company && <td>{tk.companyName}</td>}
              <td><Link to={link(tk)}>{tk.subject}</Link><div className="small muted">{TICKET_CATEGORY[tk.category]}</div></td>
              <td className="small">{tk.userName}</td>
              <td className="small">{new Date(tk.updatedAt).toLocaleString(locale())}</td>
              <td><span className={TICKET_STATUS[tk.status][1]}>{TICKET_STATUS[tk.status][0]}</span></td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

export function ArticlePage() {
  const { id } = useParams()
  const a = ARTICLES.find((x) => x.id === id)
  const [reporting, setReporting] = useState(false)
  const navigate = useNavigate()
  if (!a) return <section><p className="notice">{t('Page not found.')}</p><Link to="/help">{t('Help')}</Link></section>
  const x = articleText(a)
  return (
    <section>
      <p className="muted small"><Link to="/help">{t('Help')}</Link></p>
      <div className="card">
        <h2>{x.title}</h2>
        {a.video && <p><a className="button secondary" href={a.video} target="_blank" rel="noreferrer"><CirclePlay size={16} aria-hidden />{' '}{t('Watch the video')}</a></p>}
        <Markdown text={x.body} />
      </div>
      <p className="small muted">{t('Still stuck?')}{' '}<Button className="link" onClick={() => setReporting(true)}>{t('Report a problem')}</Button></p>
      {reporting && <ReportProblem onClose={() => setReporting(false)} onDone={(n) => { setReporting(false); navigate(`/support/${n}`) }} />}
    </section>
  )
}

/** Report a problem, ask a question or suggest an idea; the page and version are attached. */
export function ReportProblem({ onClose, onDone, page }: { onClose: () => void; onDone: (id: number) => void; page?: string }) {
  const location = useLocation()
  const [category, setCategory] = useState<Ticket['category']>('PROBLEM')
  const [subject, setSubject] = useState('')
  const [body, setBody] = useState('')
  const [error, setError] = useState<string | null>(null)
  const where = page ?? location.pathname + location.search
  const send = async () => {
    setError(null)
    try {
      const v = await serverVersion()
      const tk = await api<Ticket>('/api/tickets', { method: 'POST', body: { category, subject, body, page: where.slice(0, 300), appVersion: v.version, userAgent: navigator.userAgent.slice(0, 300) } })
      onDone(tk.id)
    } catch (e) { setError((e as Error).message) }
  }
  return (
    <Modal title={t('Report a problem')} onClose={onClose}>
      <Form onSubmit={send} className="stack">
        <div className="row">
          {(Object.keys(TICKET_CATEGORY) as Ticket['category'][]).map((c) => (
            <label key={c} className="check"><input type="radio" name="category" checked={category === c} onChange={() => setCategory(c)} />{' '}{TICKET_CATEGORY[c]}</label>
          ))}
        </div>
        <label>{t('Subject')}<input required maxLength={150} value={subject} onChange={(e) => setSubject(e.target.value)} placeholder={t('For example: the payslip PDF does not open')} /></label>
        <label>{category === 'PROBLEM' ? t('What happens, and what you expected') : t('Your message')}<textarea required maxLength={5000} rows={6} value={body} onChange={(e) => setBody(e.target.value)} /></label>
        <p className="small muted">{t('Sent with your request: the page {a}, the version of HR Pay and your browser. Never your password.', { a: where })}</p>
        <ErrorText error={error} />
        <div className="row">
          <Button type="submit" disabled={!subject.trim() || !body.trim()}>{t('Send to support')}</Button>
          <Button className="secondary" onClick={onClose}>{t('Cancel')}</Button>
        </div>
      </Form>
    </Modal>
  )
}

/** One request and its conversation, for the person (or the provider, with provider=true). */
export function TicketPage({ provider = false }: { provider?: boolean }) {
  const { id } = useParams()
  const base = provider ? `/api/platform/tickets/${id}` : `/api/tickets/${id}`
  const [thread, setThread] = useState<TicketThread | null>(null)
  const [reply, setReply] = useState('')
  const [error, setError] = useState<string | null>(null)
  const load = () => api<TicketThread>(base).then(setThread).catch((e: Error) => setError(e.message))
  useEffect(() => { void load() }, [id])
  if (!thread) return <section><ErrorText error={error} /></section>
  const tk = thread.ticket
  const send = async () => {
    setError(null)
    try { setThread(await api<TicketThread>(`${base}/messages`, { method: 'POST', body: { body: reply } })); setReply('') } catch (e) { setError((e as Error).message) }
  }
  const close = async () => {
    setError(null)
    try { await api(`${base}/close`, { method: 'POST' }); await load() } catch (e) { setError((e as Error).message) }
  }
  return (
    <section>
      <p className="muted small"><Link to={provider ? '/platform/support' : '/help'}>{provider ? t('Support desk') : t('Help')}</Link></p>
      <div className="row between">
        <h2>{tk.subject}</h2>
        <span className={TICKET_STATUS[tk.status][1]}>{TICKET_STATUS[tk.status][0]}</span>
      </div>
      <p className="small muted">
        <span className="mono">{tk.number}</span> · {TICKET_CATEGORY[tk.category]} · {provider && <>{tk.companyName} · </>}{tk.userName}{provider && <> &lt;{tk.userEmail}&gt;</>} · {new Date(tk.createdAt).toLocaleString(locale())}
      </p>
      {provider && <p className="small muted">{t('Page')}: <span className="mono">{tk.page ?? '—'}</span> · {t('Version')}: {tk.appVersion ?? '—'}<br />{tk.userAgent}</p>}
      <div className="thread">
        {thread.messages.map((m) => (
          <div key={m.id} className={`card message${m.fromProvider ? ' from-support' : ''}`}>
            <p className="small muted"><strong>{m.fromProvider ? t('Support') + ' · ' + m.authorName : m.authorName}</strong> · {new Date(m.createdAt).toLocaleString(locale())}</p>
            <p className="pre-wrap">{m.body}</p>
          </div>
        ))}
      </div>
      <Form onSubmit={send} className="card stack">
        <label>{tk.status === 'CLOSED' ? t('Write again to reopen the request') : t('Your answer')}<textarea required maxLength={5000} rows={4} value={reply} onChange={(e) => setReply(e.target.value)} /></label>
        <ErrorText error={error} />
        <div className="row">
          <Button type="submit" disabled={!reply.trim()}>{t('Send')}</Button>
          {tk.status !== 'CLOSED' && <Button className="secondary" onClick={close}>{provider ? t('Close the request') : t('My problem is solved')}</Button>}
        </div>
      </Form>
    </section>
  )
}

export function WhatsNewPage() {
  useEffect(() => { try { localStorage.setItem('hrpay.seenVersion', RELEASES[0].version) } catch { /* private window */ } }, [])
  return (
    <section>
      <p className="muted small"><Link to="/help">{t('Help')}</Link></p>
      <h2>{t('What’s new')}</h2>
      {RELEASES.map((r) => (
        <div key={r.version} className="card">
          <h3>{t('Version {a}', { a: r.version })} <span className="small muted">· {longDay(r.date)}</span></h3>
          <ul>{notes(r).map((n) => <li key={n}>{n}</li>)}</ul>
        </div>
      ))}
    </section>
  )
}

/** Whether this browser has not seen the latest release notes yet. */
export const hasNews = () => { try { return localStorage.getItem('hrpay.seenVersion') !== RELEASES[0].version } catch { return false } }
