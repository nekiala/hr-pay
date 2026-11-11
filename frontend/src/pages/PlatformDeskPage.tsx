import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api, longDay } from '../api'
import { Button, Form } from '../components/Button'
import { ErrorText } from '../components/ErrorText'
import { Markdown } from '../components/Markdown'
import { Modal } from '../components/Modal'
import { locale, t } from '../i18n'
import { TicketTable, type Ticket } from './HelpPage'
import { IMPACT, IncidentCard, LEGAL_PATHS, OVERALL, type LegalDoc, type ServiceStatus } from './PublicPages'
import { DateTimeInput } from '../components/DateFields'

/** The provider's support desk: every company's requests, the open ones first. */
export function SupportDeskPage() {
  const [status, setStatus] = useState('')
  const [rows, setRows] = useState<Ticket[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => { api<Ticket[]>(`/api/platform/tickets${status ? `?status=${status}` : ''}`).then(setRows).catch((e: Error) => setError(e.message)) }, [status])
  return (
    <section>
      <div className="row between">
        <h2>{t('Support desk')}</h2>
        <select value={status} onChange={(e) => setStatus(e.target.value)} aria-label={t('Status')}>
          <option value="">{t('All requests')}</option><option value="OPEN">{t('Waiting for support')}</option><option value="ANSWERED">{t('Answered')}</option><option value="CLOSED">{t('Closed')}</option>
        </select>
      </div>
      <ErrorText error={error} />
      <div className="card">
        {rows && rows.length === 0 ? <p className="muted">{t('No request.')}</p> : rows && <TicketTable rows={rows} company link={(tk) => `/platform/support/${tk.id}`} />}
      </div>
    </section>
  )
}

type Trial = { id: number; companyName: string; countryCode: string; employees: string; firstName: string; lastName: string; email: string; phone: string; message: string | null; language: string; status: 'NEW' | 'APPROVED' | 'DECLINED'; companyId: number | null; decidedBy: string | null; decidedAt: string | null; createdAt: string; kind: 'COMPANY' | 'FIRM'; firmRegistration: string | null }
type Plan = { code: string; name: string; maxEmployees: number | null }
const MODULES: [string, string][] = [['ATTENDANCE', t('Attendance')], ['PAYROLL', t('Payroll')], ['EVALUATIONS', t('Evaluations')]]
const BAND_SIZE: Record<string, number> = { '1-50': 50, '51-300': 300, '301-1000': 1000, '1000+': 100000 }

/** Trial requests from the website; approving one creates the company and emails its administrator. */
export function TrialsPage() {
  const [rows, setRows] = useState<Trial[] | null>(null)
  const [plans, setPlans] = useState<Plan[]>([])
  const [approving, setApproving] = useState<Trial | null>(null)
  const [plan, setPlan] = useState('')
  const [modules, setModules] = useState<string[]>(['ATTENDANCE', 'PAYROLL'])
  const [error, setError] = useState<string | null>(null)
  const [info, setInfo] = useState<string | null>(null)
  const load = () => api<Trial[]>('/api/platform/trials').then(setRows).catch((e: Error) => setError(e.message))
  useEffect(() => { void load(); void api<Plan[]>('/api/platform/plans').then(setPlans).catch(() => undefined) }, [])

  const open = (r: Trial) => {
    const size = BAND_SIZE[r.employees] ?? 300
    const fit = plans.find((p) => (p.maxEmployees ?? Infinity) >= size) ?? plans[plans.length - 1]
    setPlan(fit?.code ?? ''); setModules(['ATTENDANCE', 'PAYROLL']); setApproving(r); setError(null)
  }
  const approve = async () => {
    try {
      await api(`/api/platform/trials/${approving!.id}/approve`, { method: 'POST', body: { planCode: plan, modules } })
      setInfo(t('{a} is created; {b} received a link to choose a password.', { a: approving!.companyName, b: approving!.email }))
      setApproving(null); await load()
    } catch (e) { setError((e as Error).message) }
  }
  const decline = async (r: Trial) => {
    setError(null)
    try { await api(`/api/platform/trials/${r.id}/decline`, { method: 'POST' }); await load() } catch (e) { setError((e as Error).message) }
  }
  const STATUS: Record<Trial['status'], [string, string]> = { NEW: [t('New'), 'badge warn'], APPROVED: [t('Approved'), 'badge ok'], DECLINED: [t('Declined'), 'badge'] }
  return (
    <section>
      <h2>{t('Trial requests')}</h2>
      <p className="hint">{t('From the website’s trial form ({a}). Nothing is created until you approve: the company then starts its free trial and its administrator gets a link to choose a password.', { a: `${window.location.origin}/trial` })}</p>
      {info && <p className="success">{info}</p>}
      <ErrorText error={approving ? null : error} />
      <div className="card">
        {rows && rows.length === 0 && <p className="muted">{t('No request yet.')}</p>}
        {rows && rows.length > 0 && (
          <div className="table-wrap">
            <table>
              <thead><tr><th>{t('Received')}</th><th>{t('Company')}</th><th>{t('Contact')}</th><th>{t('Employees')}</th><th>{t('Status')}</th><th></th></tr></thead>
              <tbody>
                {rows.map((r) => (
                  <tr key={r.id}>
                    <td className="small">{new Date(r.createdAt).toLocaleString(locale())}</td>
                    <td>{r.companyName} <span className="small muted">{r.countryCode}</span>
                      {r.kind === 'FIRM' && <>{' '}<span className="badge info">{t('Accounting firm')}</span>{r.firmRegistration && <span className="small muted"> {r.firmRegistration}</span>}</>}
                      {r.message && <div className="small muted">{r.message}</div>}</td>
                    <td className="small">{r.firstName} {r.lastName}<br /><a href={`mailto:${r.email}`}>{r.email}</a> · {r.phone}</td>
                    <td>{r.employees}</td>
                    <td><span className={STATUS[r.status][1]}>{STATUS[r.status][0]}</span>{r.companyId && <div><Link className="small" to={`/platform/companies/${r.companyId}`}>{t('Open the company')}</Link></div>}</td>
                    <td className="num">{r.status === 'NEW' && <div className="row"><Button onClick={() => open(r)}>{t('Approve')}</Button><Button className="secondary" onClick={() => decline(r)}>{t('Decline')}</Button></div>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
      {approving && (
        <Modal title={t('Open a trial for {a}', { a: approving.companyName })} onClose={() => setApproving(null)}>
          <Form onSubmit={approve} className="stack">
            <label>{t('Plan')}<select value={plan} onChange={(e) => setPlan(e.target.value)}>{plans.map((p) => <option key={p.code} value={p.code}>{p.name}</option>)}</select></label>
            <fieldset><legend>{t('Modules')}</legend>
              {MODULES.map(([code, name]) => <label key={code} className="check"><input type="checkbox" checked={modules.includes(code)} onChange={(e) => setModules(e.target.checked ? [...modules, code] : modules.filter((m) => m !== code))} />{' '}{name}</label>)}
            </fieldset>
            <p className="small muted">{t('{a} {b} becomes the administrator; the link to choose a password works for 7 days.', { a: approving.firstName, b: approving.lastName })}</p>
            <ErrorText error={error} />
            <div className="row"><Button type="submit" disabled={!plan || modules.length === 0}>{t('Create the company')}</Button><Button className="secondary" onClick={() => setApproving(null)}>{t('Cancel')}</Button></div>
          </Form>
        </Modal>
      )}
      <CalculatorLeads />
    </section>
  )
}

type Lead = { id: number; email: string; companyName: string | null; phone: string | null; countryCode: string; employees: string | null; language: string; summary: string; status: 'NEW' | 'CONTACTED'; contactedBy: string | null; contactedAt: string | null; createdAt: string }

/** Visitors of the website's salary calculator who asked for their result by email. */
function CalculatorLeads() {
  const [rows, setRows] = useState<Lead[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [open, setOpen] = useState<Lead | null>(null)
  const load = () => api<Lead[]>('/api/platform/calculator-leads').then(setRows).catch((e: Error) => setError(e.message))
  useEffect(() => { void load() }, [])
  const contacted = async (r: Lead) => {
    setError(null)
    try { await api(`/api/platform/calculator-leads/${r.id}/contacted`, { method: 'POST' }); await load() } catch (e) { setError((e as Error).message) }
  }
  return (
    <>
      <h2>{t('Salary calculator leads')}</h2>
      <p className="hint">{t('Visitors of the website’s salary calculator who asked for their result by email. They received it; contact them to offer a trial.')}</p>
      <ErrorText error={error} />
      <div className="card">
        {rows && rows.length === 0 && <p className="muted">{t('No lead yet.')}</p>}
        {rows && rows.length > 0 && (
          <div className="table-wrap">
            <table>
              <thead><tr><th>{t('Received')}</th><th>{t('Contact')}</th><th>{t('Country')}</th><th>{t('Employees')}</th><th>{t('Status')}</th><th></th></tr></thead>
              <tbody>
                {rows.map((r) => (
                  <tr key={r.id}>
                    <td className="small">{new Date(r.createdAt).toLocaleString(locale())}</td>
                    <td className="small">{r.companyName && <>{r.companyName}<br /></>}<a href={`mailto:${r.email}`}>{r.email}</a>{r.phone && <> · {r.phone}</>}</td>
                    <td>{r.countryCode}</td>
                    <td>{r.employees ?? '—'}</td>
                    <td>{r.status === 'NEW' ? <span className="badge warn">{t('New')}</span> : <span className="badge ok" title={r.contactedBy ?? ''}>{t('Contacted')}</span>}</td>
                    <td className="num"><div className="row"><Button className="secondary" onClick={() => setOpen(r)}>{t('See the calculation')}</Button>{r.status === 'NEW' && <Button onClick={() => contacted(r)}>{t('Mark contacted')}</Button>}</div></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
      {open && (
        <Modal title={open.companyName ?? open.email} onClose={() => setOpen(null)}>
          <pre className="small" style={{ whiteSpace: 'pre-wrap' }}>{open.summary}</pre>
        </Modal>
      )}
    </>
  )
}

const KINDS: [LegalDoc['kind'], string][] = [['TERMS', t('Terms of service')], ['DPA', t('Data processing agreement')], ['PRIVACY', t('Privacy policy')], ['SLA', t('Service levels')]]

/** The provider's legal documents: read them, publish a new version in both languages. */
export function LegalAdminPage() {
  const [docs, setDocs] = useState<LegalDoc[] | null>(null)
  const [editing, setEditing] = useState<LegalDoc['kind'] | null>(null)
  const [draft, setDraft] = useState({ frTitle: '', frBody: '', enTitle: '', enBody: '', mustAccept: false })
  const [viewing, setViewing] = useState<LegalDoc | null>(null)
  const [error, setError] = useState<string | null>(null)
  const load = () => api<LegalDoc[]>('/api/platform/legal').then(setDocs).catch((e: Error) => setError(e.message))
  useEffect(() => { void load() }, [])
  const latest = (k: string, l: string) => docs?.find((d) => d.kind === k && d.language === l)
  const edit = (k: LegalDoc['kind']) => {
    const fr = latest(k, 'fr'); const en = latest(k, 'en')
    setDraft({ frTitle: fr?.title ?? '', frBody: fr?.body ?? '', enTitle: en?.title ?? '', enBody: en?.body ?? '', mustAccept: fr?.mustAccept ?? false })
    setEditing(k); setError(null)
  }
  const publish = async () => {
    try {
      await api('/api/platform/legal', { method: 'POST', body: { kind: editing, mustAccept: draft.mustAccept, fr: { title: draft.frTitle, body: draft.frBody }, en: { title: draft.enTitle, body: draft.enBody } } })
      setEditing(null); await load()
    } catch (e) { setError((e as Error).message) }
  }
  return (
    <section>
      <h2>{t('Terms and policies')}</h2>
      <p className="hint">{t('The starting texts are drafts: have them reviewed by a lawyer, then publish your version. A new version of a document that must be accepted is shown to every company’s administrator at their next sign-in.')}</p>
      <ErrorText error={editing ? null : error} />
      <div className="card">
        <div className="table-wrap">
          <table>
            <thead><tr><th>{t('Document')}</th><th>{t('Version')}</th><th>{t('Published')}</th><th>{t('Accepted by companies')}</th><th></th></tr></thead>
            <tbody>
              {KINDS.map(([k, name]) => {
                const d = latest(k, 'fr')
                return (
                  <tr key={k}>
                    <td><a href={`/legal/${LEGAL_PATHS[k]}`} target="_blank" rel="noreferrer">{name}</a></td>
                    <td>{d?.version ?? '—'}</td><td className="small">{d ? longDay(d.publishedAt) : '—'}</td>
                    <td>{d?.mustAccept ? t('Required') : t('No, for information')}</td>
                    <td className="num"><div className="row">{d && <Button className="secondary" onClick={() => setViewing(d)}>{t('Read')}</Button>}<Button className="secondary" onClick={() => edit(k)}>{t('New version')}</Button></div></td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      </div>
      {viewing && <Modal title={viewing.title} size="lg" onClose={() => setViewing(null)}><Markdown text={viewing.body} /></Modal>}
      {editing && (
        <Modal title={t('New version: {a}', { a: KINDS.find(([k]) => k === editing)![1] })} size="lg" onClose={() => setEditing(null)}>
          <Form onSubmit={publish} className="stack">
            <p className="small muted">{t('Headings start with ##, lists with -, bold is **text**.')}</p>
            <label>{t('Title in French')}<input required maxLength={150} value={draft.frTitle} onChange={(e) => setDraft({ ...draft, frTitle: e.target.value })} /></label>
            <label>{t('Text in French')}<textarea required rows={10} value={draft.frBody} onChange={(e) => setDraft({ ...draft, frBody: e.target.value })} /></label>
            <label>{t('Title in English')}<input required maxLength={150} value={draft.enTitle} onChange={(e) => setDraft({ ...draft, enTitle: e.target.value })} /></label>
            <label>{t('Text in English')}<textarea required rows={10} value={draft.enBody} onChange={(e) => setDraft({ ...draft, enBody: e.target.value })} /></label>
            <label className="check"><input type="checkbox" checked={draft.mustAccept} onChange={(e) => setDraft({ ...draft, mustAccept: e.target.checked })} />{' '}{t('Every company’s administrator must accept this version')}</label>
            <ErrorText error={error} />
            <div className="row"><Button type="submit">{t('Publish')}</Button><Button className="secondary" onClick={() => setEditing(null)}>{t('Cancel')}</Button></div>
          </Form>
        </Modal>
      )}
    </section>
  )
}

/** Incidents and planned maintenance on the public status page. */
export function IncidentsPage() {
  const [s, setS] = useState<ServiceStatus | null>(null)
  const [form, setForm] = useState({ title: '', impact: 'MINOR', body: '', startedAt: '' })
  const [updates, setUpdates] = useState<Record<number, string>>({})
  const [error, setError] = useState<string | null>(null)
  const load = () => api<ServiceStatus>('/api/status').then(setS).catch((e: Error) => setError(e.message))
  useEffect(() => { void load() }, [])
  const post = async () => {
    setError(null)
    try {
      setS(await api<ServiceStatus>('/api/platform/incidents', { method: 'POST', body: { ...form, startedAt: form.startedAt ? new Date(form.startedAt).toISOString() : null } }))
      setForm({ title: '', impact: 'MINOR', body: '', startedAt: '' })
    } catch (e) { setError((e as Error).message) }
  }
  const update = async (id: number, resolve: boolean) => {
    setError(null)
    try { setS(await api<ServiceStatus>(`/api/platform/incidents/${id}/updates`, { method: 'POST', body: { body: updates[id] || (resolve ? t('Resolved.') : ''), resolve } })); setUpdates({ ...updates, [id]: '' }) } catch (e) { setError((e as Error).message) }
  }
  return (
    <section>
      <div className="row between"><h2>{t('Service status')}</h2><a href="/status" target="_blank" rel="noreferrer">{t('Open the public page')}</a></div>
      {s && <p><span className={`badge ${OVERALL[s.overall][1]}`}>{OVERALL[s.overall][0]}</span></p>}
      <ErrorText error={error} />
      {s?.active.map((i) => (
        <div key={i.id}>
          <IncidentCard i={i} />
          <div className="row">
            <input style={{ flex: 1 }} maxLength={1000} placeholder={t('New update for customers')} value={updates[i.id] ?? ''} onChange={(e) => setUpdates({ ...updates, [i.id]: e.target.value })} />
            <Button className="secondary" disabled={!updates[i.id]?.trim()} onClick={() => update(i.id, false)}>{t('Post the update')}</Button>
            <Button onClick={() => update(i.id, true)}>{t('Resolved')}</Button>
          </div>
        </div>
      ))}
      <Form onSubmit={post} className="card stack">
        <h3>{t('Post an incident or a planned maintenance')}</h3>
        <div className="grid3">
          <label>{t('Title')}<input required maxLength={150} value={form.title} onChange={(e) => setForm({ ...form, title: e.target.value })} /></label>
          <label>{t('Impact')}<select value={form.impact} onChange={(e) => setForm({ ...form, impact: e.target.value })}>{Object.entries(IMPACT).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select></label>
          <label>{t('Starts (empty: now)')}<DateTimeInput value={form.startedAt} onChange={(e) => setForm({ ...form, startedAt: e.target.value })} /></label>
        </div>
        <label>{t('What customers read')}<textarea required maxLength={1000} value={form.body} onChange={(e) => setForm({ ...form, body: e.target.value })} /></label>
        <Button type="submit">{t('Publish on the status page')}</Button>
      </Form>
      {s && s.recent.length > 0 && <><h3>{t('Last 30 days')}</h3>{s.recent.map((i) => <IncidentCard key={i.id} i={i} />)}</>}
    </section>
  )
}
