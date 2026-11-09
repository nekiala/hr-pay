import { useEffect, useState, type ReactNode } from 'react'
import { Link, useParams } from 'react-router-dom'
import { Banknote, CircleAlert, CircleCheck, Wrench } from 'lucide-react'
import { api, longDay } from '../api'
import { useAuth } from '../auth'
import { Button, Form } from '../components/Button'
import { ErrorText } from '../components/ErrorText'
import { LanguageToggle } from '../components/Language'
import { Markdown } from '../components/Markdown'
import { language, locale, t } from '../i18n'

export type LegalDoc = { kind: 'TERMS' | 'DPA' | 'PRIVACY' | 'SLA'; version: number; language: string; title: string; body: string; mustAccept: boolean; publishedAt: string }

export const LEGAL_PATHS: Record<LegalDoc['kind'], string> = { TERMS: 'terms', DPA: 'dpa', PRIVACY: 'privacy-policy', SLA: 'sla' }
const KIND_OF: Record<string, LegalDoc['kind']> = { terms: 'TERMS', dpa: 'DPA', 'privacy-policy': 'PRIVACY', sla: 'SLA' }
const PHONE: Record<string, string> = { CD: '+243', CG: '+242', CM: '+237', GA: '+241' }

/** The frame of the pages anyone can open: brand, content, the public links. */
export function Public({ children, wide = false }: { children: ReactNode; wide?: boolean }) {
  return (
    <div className="public">
      <header className="public-head">
        <Link to="/" className="public-brand"><span className="brand-mark"><Banknote size={18} aria-hidden /></span>{t('HR Pay')}</Link>
        <LanguageToggle />
      </header>
      <main className={wide ? 'public-main wide' : 'public-main'}>{children}</main>
      <PublicLinks />
    </div>
  )
}

export function PublicLinks() {
  return (
    <footer className="public-links small">
      <Link to="/changes">{t('Law and rate changes')}</Link>
      <Link to="/status">{t('Service status')}</Link>
      <Link to="/legal/terms">{t('Terms of service')}</Link>
      <Link to="/legal/privacy-policy">{t('Privacy policy')}</Link>
      <Link to="/legal/dpa">{t('Data processing')}</Link>
      <Link to="/legal/sla">{t('Service levels')}</Link>
    </footer>
  )
}

export function LegalPage() {
  const { kind } = useParams()
  const [doc, setDoc] = useState<LegalDoc | null>(null)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => {
    const k = KIND_OF[kind ?? '']
    if (!k) { setError(t('Page not found.')); return }
    api<LegalDoc>(`/api/legal/documents/${k}?language=${language()}`).then(setDoc).catch((e: Error) => setError(e.message))
  }, [kind])
  return (
    <Public>
      <ErrorText error={error} />
      {doc && (
        <article className="card">
          <h1>{doc.title}</h1>
          <p className="small muted">{t('Version {a}', { a: doc.version })} · {longDay(doc.publishedAt)}</p>
          <Markdown text={doc.body} />
        </article>
      )}
    </Public>
  )
}

type Incident = { id: number; title: string; impact: 'MINOR' | 'MAJOR' | 'OUTAGE' | 'PLANNED'; startedAt: string; resolvedAt: string | null; updates: { id: number; body: string; createdAt: string }[] }
export type ServiceStatus = { overall: 'OPERATIONAL' | 'MAINTENANCE' | 'DEGRADED' | 'OUTAGE'; version: { version: string }; checks: { key: string; ok: boolean }[]; active: Incident[]; recent: Incident[]; checkedAt: string }

export const OVERALL: Record<ServiceStatus['overall'], [string, string]> = {
  OPERATIONAL: [t('All systems are working'), 'ok'],
  MAINTENANCE: [t('Maintenance in progress'), 'warn'],
  DEGRADED: [t('Some functions are slowed or disturbed'), 'warn'],
  OUTAGE: [t('HR Pay is unavailable'), 'off'],
}
export const IMPACT: Record<Incident['impact'], string> = {
  MINOR: t('Minor'), MAJOR: t('Major'), OUTAGE: t('Outage'), PLANNED: t('Planned maintenance'),
}

export function IncidentCard({ i }: { i: Incident }) {
  return (
    <div className="card incident">
      <div className="row between"><strong>{i.title}</strong><span className={i.resolvedAt ? 'badge' : i.impact === 'PLANNED' ? 'badge' : 'badge warn'}>{IMPACT[i.impact]}</span></div>
      <p className="small muted">{new Date(i.startedAt).toLocaleString(locale())}{i.resolvedAt && <> → {new Date(i.resolvedAt).toLocaleString(locale())} · {t('Resolved')}</>}</p>
      <ul className="updates">{i.updates.map((u) => <li key={u.id}><span className="small muted">{new Date(u.createdAt).toLocaleString(locale())}</span> {u.body}</li>)}</ul>
    </div>
  )
}

export function StatusPage() {
  const [s, setS] = useState<ServiceStatus | null>(null)
  const [down, setDown] = useState(false)
  useEffect(() => {
    const load = () => api<ServiceStatus>('/api/status').then((v) => { setS(v); setDown(false) }).catch(() => setDown(true))
    void load()
    const timer = window.setInterval(load, 60_000)
    return () => window.clearInterval(timer)
  }, [])
  const overall = down ? OVERALL.OUTAGE : s ? OVERALL[s.overall] : null
  const names: Record<string, string> = { app: t('Application'), database: t('Database') }
  return (
    <Public>
      <h1>{t('Service status')}</h1>
      {overall && (
        <div className={`status-banner ${overall[1]}`}>
          {overall[1] === 'ok' ? <CircleCheck size={22} aria-hidden /> : overall[1] === 'off' ? <CircleAlert size={22} aria-hidden /> : <Wrench size={22} aria-hidden />}
          <strong>{down ? t('HR Pay does not answer') : overall[0]}</strong>
        </div>
      )}
      {s && (
        <>
          <div className="card">
            <ul className="kv-list">{s.checks.map((c) => <li key={c.key}><span>{names[c.key] ?? c.key}</span><span className={c.ok ? 'badge ok' : 'badge off'}>{c.ok ? t('Working') : t('Not working')}</span></li>)}</ul>
            <p className="small muted">{t('Checked at {a}', { a: new Date(s.checkedAt).toLocaleTimeString(locale()) })} · {t('Version {a}', { a: s.version.version })}</p>
          </div>
          {s.active.length > 0 && <><h2>{t('Now')}</h2>{s.active.map((i) => <IncidentCard key={i.id} i={i} />)}</>}
          <h2>{t('Last 30 days')}</h2>
          {s.recent.length === 0 ? <p className="muted">{t('No incident in the last 30 days.')}</p> : s.recent.map((i) => <IncidentCard key={i.id} i={i} />)}
        </>
      )}
    </Public>
  )
}

/** The website's trial request: the provider reads it and creates the company. */
export function TrialPage() {
  // The website's calculator opens the form with the visitor's country (?country=CG).
  const params = new URLSearchParams(window.location.search)
  const asked = params.get('country') ?? 'CD'
  const startCountry = ['CD', 'CG', 'CM', 'GA'].includes(asked) ? asked : 'CD'
  // ?firm=1: an accounting firm asking for the accountant portal.
  const [form, setForm] = useState({ companyName: '', countryCode: startCountry, employees: '51-300', firstName: '', lastName: '', email: '', phone: PHONE[startCountry], message: '', website: '', kind: params.get('firm') ? 'FIRM' : 'COMPANY', firmRegistration: '' })
  const firm = form.kind === 'FIRM'
  const [sent, setSent] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const set = (k: keyof typeof form) => (e: { target: { value: string } }) => setForm({ ...form, [k]: e.target.value })
  const send = async () => {
    setError(null)
    try { await api('/api/trials', { method: 'POST', body: { ...form, language: language() } }); setSent(true) } catch (e) { setError((e as Error).message) }
  }
  return (
    <Public>
      <h1>{t('Try HR Pay free for 30 days')}</h1>
      {sent ? (
        <div className="card"><p className="success">{t('Thank you. We read your request and write to you within one working day to open your trial.')}</p></div>
      ) : (
        <Form onSubmit={send} className="card stack">
          <p className="muted">{t('Attendance, leave and payroll for the DRC and the Republic of the Congo, in French and English. Tell us about your company; we open your trial and help you set it up.')}</p>
          <label className="check"><input type="checkbox" checked={firm} onChange={(e) => setForm({ ...form, kind: e.target.checked ? 'FIRM' : 'COMPANY' })} />
            {t('We are an accounting firm and run the payroll of client companies')}</label>
          <label>{firm ? t('Firm name') : t('Company name')}<input required maxLength={150} value={form.companyName} onChange={set('companyName')} /></label>
          {firm && <label>{t('ONEC or ONECCA number (optional)')}<input maxLength={60} value={form.firmRegistration} onChange={set('firmRegistration')} /></label>}
          <div className="grid2">
            <label>{t('Country')}<select value={form.countryCode} onChange={(e) => setForm({ ...form, countryCode: e.target.value, phone: PHONE[e.target.value] ?? '+243' })}>
              <option value="CD">{t('DR Congo')}</option><option value="CG">{t('Republic of the Congo')}</option>
              <option value="CM">{t('Cameroon')}</option><option value="GA">{t('Gabon')}</option></select></label>
            <label>{t('Employees')}<select value={form.employees} onChange={set('employees')}>
              <option value="1-50">1–50</option><option value="51-300">51–300</option><option value="301-1000">301–1 000</option><option value="1000+">{t('More than 1,000')}</option></select></label>
            <label>{t('First name')}<input required maxLength={100} value={form.firstName} onChange={set('firstName')} /></label>
            <label>{t('Last name')}<input required maxLength={100} value={form.lastName} onChange={set('lastName')} /></label>
            <label>{t('Work email')}<input required type="email" maxLength={320} value={form.email} onChange={set('email')} /></label>
            <label>{t('Phone')}<input required maxLength={40} value={form.phone} onChange={set('phone')} /></label>
          </div>
          <label>{t('Anything we should know (optional)')}<textarea maxLength={1000} value={form.message} onChange={set('message')} placeholder={t('For example: number of sites, the payroll software you use today')} /></label>
          {/* Left empty by people; robots fill it. */}
          <input className="hp" tabIndex={-1} autoComplete="off" aria-hidden value={form.website} onChange={set('website')} name="website" />
          <p className="small muted">{t('We use these details only to answer you and open your trial.')}{' '}<Link to="/legal/privacy-policy">{t('Privacy policy')}</Link></p>
          <ErrorText error={error} />
          <Button type="submit">{t('Ask for a trial')}</Button>
        </Form>
      )}
    </Public>
  )
}

type Pending = { kind: LegalDoc['kind']; version: number; title: string }

/** A company's administrator accepts each new version of the terms before going on. */
export function TermsGate() {
  const { can, me } = useAuth()
  const [pending, setPending] = useState<Pending[]>([])
  const [checked, setChecked] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const ask = can('role.assign') && !me?.support
  useEffect(() => {
    if (!ask) return
    api<Pending[]>(`/api/legal/pending?language=${language()}`).then(setPending).catch(() => setPending([]))
  }, [ask])
  if (!ask || pending.length === 0) return null
  const accept = async () => {
    setError(null)
    try { await api('/api/legal/accept', { method: 'POST', body: { documents: pending } }); setPending([]) } catch (e) { setError((e as Error).message) }
  }
  return (
    <div className="modal-backdrop" role="dialog" aria-modal="true" aria-labelledby="terms-title">
      <div className="modal card">
        <h2 id="terms-title">{t('Terms to accept')}</h2>
        <p>{t('As an administrator of {a}, read and accept these documents to go on. They bind your company.', { a: me?.company?.name ?? '' })}</p>
        <ul>{pending.map((p) => <li key={p.kind}><a href={`/legal/${LEGAL_PATHS[p.kind]}`} target="_blank" rel="noreferrer">{p.title}</a> <span className="small muted">({t('version {a}', { a: p.version })})</span></li>)}</ul>
        <label className="check"><input type="checkbox" checked={checked} onChange={(e) => setChecked(e.target.checked)} />{' '}{t('I have read these documents and accept them on behalf of my company.')}</label>
        <ErrorText error={error} />
        <Button disabled={!checked} onClick={accept}>{t('Accept')}</Button>
      </div>
    </div>
  )
}
