import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { ApiError, api, longDay } from '../api'
import { Button, Form } from '../components/Button'
import { useAsk } from '../components/Dialogs'
import { ErrorText } from '../components/ErrorText'
import { Modal } from '../components/Modal'
import { PageTitle, SettingsLink } from '../components/SettingsLink'
import { t } from '../i18n'

type Status = 'UPCOMING' | 'TO_FILE' | 'FILED'
type Filed = { filedOn: string; reference: string | null; note: string | null; filedBy: string; filedAt: string }
type Movement = {
  kind: 'HIRE' | 'DEPARTURE'; userId: number; registrationNo: string; name: string; eventDate: string; detail: string
  due: string; remindFrom: string; status: Status; late: boolean; daysLeft: number; filed: Filed | null
}
type Annual = {
  kind: 'WORKFORCE' | 'BILAN_SOCIAL' | 'TAX_ANNUAL'; year: number; due: string; status: Status; late: boolean; daysLeft: number
  filed: Filed | null; authority: string; legalBasis: string; verified: boolean
}
type Rule = { kind: string; authority: string; dueDays: number | null; remindAfterDays: number | null; dueMonth: number | null; dueDay: number | null; yearOffset: number; legalBasis: string; verified: boolean }
export type FilingSettings = { country: string; filingsFrom: string; personnelRegister: boolean; registerByDefault: boolean; movements: boolean; annual: boolean }
type Page = { settings: FilingSettings; movements: Movement[]; annual: Annual[]; rules: Rule[] }
type Visit = { id: number; visitedOn: string; inspector: string; observations: string | null; enteredBy: string; enteredAt: string }
type Filing = { title: string; path: string }

const fail = (e: unknown) => e instanceof ApiError ? [e.message, ...Object.values(e.fieldErrors)].join(' ') : t('Something went wrong.')
const today = () => new Date().toISOString().slice(0, 10)
const yearStart = () => `${new Date().getFullYear()}-01-01`

const KIND_LABEL: Record<string, string> = {
  HIRE: t('Hiring'), DEPARTURE: t('Departure'),
  WORKFORCE: t('Annual declaration of the workforce'), BILAN_SOCIAL: t('Bilan social'), TAX_ANNUAL: t('Annual tax summary'),
}
/** The annual tax summary as each country calls it. */
const kindLabel = (kind: string, country?: string) =>
  kind === 'TAX_ANNUAL' && country === 'CD' ? t('Annual IPR summary') : kind === 'TAX_ANNUAL' && country === 'CG' ? t('Annual statement of salaries (DAS)') : KIND_LABEL[kind] ?? kind

function StatusBadge({ status, late }: { status: Status; late: boolean }) {
  if (status === 'FILED') return <span className={`badge ${late ? 'warn' : 'on'}`}>{late ? t('Filed late') : t('Filed')}</span>
  if (status === 'UPCOMING') return <span className="badge">{t('Upcoming')}</span>
  return <span className={`badge ${late ? 'off' : 'warn'}`}>{late ? t('Late') : t('To file')}</span>
}

function DaysLeft({ status, daysLeft }: { status: Status; daysLeft: number }) {
  if (status === 'FILED') return null
  return <div className={`small ${daysLeft < 0 ? 'error' : 'muted'}`}>
    {daysLeft < 0 ? t('{a} days late', { a: -daysLeft }) : daysLeft === 0 ? t('today') : t('in {a} days', { a: daysLeft })}</div>
}

function Downloads({ path }: { path: string }) {
  const sep = path.includes('?') ? '&' : '?'
  return <><a className="button link" href={`${path}${sep}format=pdf`}>PDF</a>{' '}<a className="button link" href={`${path}${sep}format=xlsx`}>Excel</a></>
}

function FiledModal({ filing, onClose, onSave }: { filing: Filing; onClose: () => void; onSave: (b: { filedOn: string; reference: string | null; note: string | null }) => Promise<unknown> }) {
  const [form, setForm] = useState({ filedOn: today(), reference: '', note: '' })
  return (
    <Modal title={t('Filed: {a}', { a: filing.title })} size="sm" onClose={onClose}>
      <Form onSubmit={() => onSave({ filedOn: form.filedOn, reference: form.reference || null, note: form.note || null })}>
        <label>{t('Filed on')}<input type="date" required max={today()} value={form.filedOn} onChange={(e) => setForm({ ...form, filedOn: e.target.value })} /></label>
        <label>{t('Receipt or reference')}<input maxLength={100} value={form.reference} onChange={(e) => setForm({ ...form, reference: e.target.value })} /></label>
        <label>{t('Note')}<input maxLength={300} value={form.note} onChange={(e) => setForm({ ...form, note: e.target.value })} /></label>
        <div className="modal-foot"><Button className="secondary" onClick={onClose}>{t('Cancel')}</Button><Button type="submit">{t('Save')}</Button></div>
      </Form>
    </Modal>
  )
}

/**
 * The legal registers and annual filings: in the DRC each hiring and departure, the yearly workforce declaration and
 * the bilan social; the personnel register where the company keeps one (Republic of the Congo).
 */
export default function LegalFilingsPage() {
  const ask = useAsk()
  const [page, setPage] = useState<Page | null>(null)
  const [visits, setVisits] = useState<Visit[]>([])
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const [filing, setFiling] = useState<Filing | null>(null)
  const [period, setPeriod] = useState({ from: yearStart(), to: today() })
  const [register, setRegister] = useState({ from: yearStart(), to: today() })
  const [year, setYear] = useState(new Date().getFullYear())
  const [visit, setVisit] = useState({ visitedOn: today(), inspector: '', observations: '' })
  const [showFiled, setShowFiled] = useState(false)

  const load = async () => {
    const p = await api<Page>('/api/legal-filings')
    setPage(p)
    if (p.settings.personnelRegister) setVisits(await api<Visit[]>('/api/legal-filings/register/visits'))
  }
  useEffect(() => { load().catch((e) => setError(fail(e))) }, [])

  const run = async (work: () => Promise<unknown>, done: string) => {
    setError(null)
    setMessage(null)
    try { await work(); setMessage(done); await load() } catch (e) { setError(fail(e)) }
  }

  const s = page?.settings
  const open = [...(page?.movements ?? []), ...(page?.annual ?? [])].filter((x) => x.status === 'TO_FILE')
  const late = open.filter((x) => x.late)
  const next = [...open].sort((a, b) => a.due.localeCompare(b.due))[0]
  const movements = (page?.movements ?? []).filter((m) => showFiled || m.status !== 'FILED')
  const movementPath = (m: Movement) => `/api/legal-filings/movements/${m.kind}/${m.userId}/${m.eventDate}`
  const has = (kind: string) => (page?.rules ?? []).some((r) => r.kind === kind)
  const nothing = s && !s.movements && !s.annual && !s.personnelRegister

  return (
    <section>
      <p className="small muted"><Link to="/payroll/declarations">{t('Declarations')}</Link> · <Link to="/payroll/declarations/calendar">{t('Declarations calendar')}</Link></p>
      <PageTitle title={t('Legal registers and annual filings')}><SettingsLink to="/payroll/legal-filings/settings" /></PageTitle>
      <ErrorText error={error} />
      {message && <p className="success">{message}</p>}

      {page && (s?.movements || s?.annual) && (
        <div className="tiles">
          <div className="tile"><span>{t('Next deadline')}</span><strong>{next ? longDay(next.due) : '—'}</strong>
            <small>{next ? ('name' in next ? `${KIND_LABEL[next.kind]} · ${next.name}` : `${kindLabel(next.kind, s?.country)} ${next.year}`) : t('Nothing open')}</small></div>
          <div className="tile"><span>{t('To file')}</span><strong>{open.length}</strong><small>{t('hirings, departures and yearly filings')}</small></div>
          <div className={`tile${late.length ? ' warn' : ''}`}><span>{t('Late')}</span><strong>{late.length}</strong><small>{t('past their due date')}</small></div>
        </div>
      )}

      {nothing && <p className="muted">{t('No legal filing is set for this country yet. If you keep a personnel register, turn it on in the settings.')}</p>}

      {s?.movements && (
        <div className="card">
          <h3>{t('Hirings and departures')}</h3>
          <p className="hint">{t('Each hiring and each departure is declared to the labour inspectorate and the ONEM. Listed from {a}.', { a: longDay(s.filingsFrom) })}</p>
          {movements.length === 0 ? <p className="muted">{t('Nothing to declare.')}</p> : (
            <div className="table-wrap">
              <table>
                <thead><tr><th>{t('Event')}</th><th>{t('Person')}</th><th>{t('Date')}</th><th>{t('Due')}</th><th>{t('Status')}</th><th>{t('Filed')}</th><th></th></tr></thead>
                <tbody>
                  {movements.map((m) => (
                    <tr key={`${m.kind}-${m.userId}-${m.eventDate}`}>
                      <td><strong>{KIND_LABEL[m.kind]}</strong><div className="small muted">{m.detail}</div></td>
                      <td>{m.name}<div className="small muted">{m.registrationNo}</div></td>
                      <td>{longDay(m.eventDate)}</td>
                      <td>{longDay(m.due)}<DaysLeft status={m.status} daysLeft={m.daysLeft} /></td>
                      <td><StatusBadge status={m.status} late={m.late} /></td>
                      <td className="small">{m.filed ? <>{longDay(m.filed.filedOn)}{m.filed.reference && <div className="muted">{m.filed.reference}</div>}</> : '—'}</td>
                      <td className="nowrap">
                        <Downloads path={movementPath(m)} />
                        {m.status === 'TO_FILE' && <>{' '}<Button className="link" onClick={() => setFiling({ title: `${KIND_LABEL[m.kind]} · ${m.name}`, path: movementPath(m) })}>{t('Record as filed')}</Button></>}
                        {m.status === 'FILED' && <>{' '}<Button className="link" onClick={() => ask({
                          title: t('Remove the filing record?'), confirmLabel: t('Remove'), danger: true,
                          onConfirm: () => run(() => api(`${movementPath(m)}/filed`, { method: 'DELETE' }), t('Filing record removed.')),
                        })}>{t('Undo')}</Button></>}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          <label className="check"><input type="checkbox" checked={showFiled} onChange={(e) => setShowFiled(e.target.checked)} />{t('Show those already filed')}</label>
          <div className="row">
            <label>{t('From')}<input type="date" value={period.from} onChange={(e) => setPeriod({ ...period, from: e.target.value })} /></label>
            <label>{t('To')}<input type="date" value={period.to} onChange={(e) => setPeriod({ ...period, to: e.target.value })} /></label>
            <span>{t('List of the period')}: <Downloads path={`/api/legal-filings/movements?from=${period.from}&to=${period.to}`} /></span>
          </div>
        </div>
      )}

      {s?.annual && (
        <div className="card">
          <h3>{t('Yearly filings')}</h3>
          <p className="hint">{has('WORKFORCE')
            ? t('The workforce employed on 1 January, national and foreign, and the bilan social of the past year, by 31 January.')
            : t('The yearly summary of the salaries paid and the tax withheld, in January.')}{has('TAX_ANNUAL') && <> {t('The tax summary counts closed months only: close December first.')}</>}</p>
          {(page?.annual ?? []).length > 0 && (
            <div className="table-wrap">
              <table>
                <thead><tr><th>{t('Filing')}</th><th>{t('Due')}</th><th>{t('Status')}</th><th>{t('Filed')}</th><th></th></tr></thead>
                <tbody>
                  {page!.annual.map((a) => {
                    const path = `/api/legal-filings/annual/${a.kind}/${a.year}`
                    return (
                      <tr key={`${a.kind}-${a.year}`}>
                        <td><strong>{kindLabel(a.kind, s?.country)} {a.year}</strong><div className="small muted">{a.authority}</div></td>
                        <td>{longDay(a.due)}<DaysLeft status={a.status} daysLeft={a.daysLeft} /></td>
                        <td><StatusBadge status={a.status} late={a.late} /></td>
                        <td className="small">{a.filed ? <>{longDay(a.filed.filedOn)}{a.filed.reference && <div className="muted">{a.filed.reference}</div>}</> : '—'}</td>
                        <td className="nowrap">
                          <Downloads path={path} />
                          {a.kind === 'TAX_ANNUAL' && <div className="small">{t('Individual sheets')}: <Downloads path={`${path}/sheets`} /></div>}
                          {a.status === 'TO_FILE' && <>{' '}<Button className="link" onClick={() => setFiling({ title: `${kindLabel(a.kind, s?.country)} ${a.year}`, path })}>{t('Record as filed')}</Button></>}
                          {a.status === 'FILED' && <>{' '}<Button className="link" onClick={() => ask({
                            title: t('Remove the filing record?'), confirmLabel: t('Remove'), danger: true,
                            onConfirm: () => run(() => api(`${path}/filed`, { method: 'DELETE' }), t('Filing record removed.')),
                          })}>{t('Undo')}</Button></>}
                        </td>
                      </tr>
                    )
                  })}
                </tbody>
              </table>
            </div>
          )}
          <div className="row">
            <label>{t('Year')}<input type="number" min={2000} max={new Date().getFullYear() + 1} value={year} onChange={(e) => setYear(Number(e.target.value))} /></label>
            {has('WORKFORCE') && <span>{t('Workforce on 1 January')}: <Downloads path={`/api/legal-filings/annual/WORKFORCE/${year}`} /></span>}
            {has('BILAN_SOCIAL') && <span>{t('Bilan social')}: <Downloads path={`/api/legal-filings/annual/BILAN_SOCIAL/${year}`} /></span>}
            {has('TAX_ANNUAL') && <span>{kindLabel('TAX_ANNUAL', s?.country)}: <Downloads path={`/api/legal-filings/annual/TAX_ANNUAL/${year}`} /></span>}
            {has('TAX_ANNUAL') && <span>{t('Individual sheets')}: <Downloads path={`/api/legal-filings/annual/TAX_ANNUAL/${year}/sheets`} /></span>}
          </div>
        </div>
      )}

      {s?.personnelRegister && (
        <div className="card">
          <h3>{t('Personnel register')}</h3>
          <p className="hint">{t('Part 1: the workers and their contracts, numbered in the order they were hired. Part 2: each month\'s days worked, leave and pay. Part 3: the labour inspector\'s visits. Keep it at the workplace for the inspector.')}</p>
          <div className="row">
            <label>{t('From')}<input type="date" value={register.from} onChange={(e) => setRegister({ ...register, from: e.target.value })} /></label>
            <label>{t('To')}<input type="date" value={register.to} onChange={(e) => setRegister({ ...register, to: e.target.value })} /></label>
            <span>{t('Register')}: <Downloads path={`/api/legal-filings/register?from=${register.from}&to=${register.to}`} /></span>
          </div>
          <h4>{t('Visits of the labour inspector')}</h4>
          {visits.length === 0 ? <p className="muted">{t('No visit recorded.')}</p> : (
            <div className="table-wrap">
              <table>
                <thead><tr><th>{t('Date')}</th><th>{t('Inspector')}</th><th>{t('Observations and formal notices')}</th><th>{t('Entered by')}</th></tr></thead>
                <tbody>{visits.map((v) => <tr key={v.id}><td>{longDay(v.visitedOn)}</td><td>{v.inspector}</td><td className="small">{v.observations ?? '—'}</td><td className="small muted">{v.enteredBy}</td></tr>)}</tbody>
              </table>
            </div>
          )}
          <Form onSubmit={() => run(async () => {
            await api('/api/legal-filings/register/visits', { method: 'POST', body: { visitedOn: visit.visitedOn, inspector: visit.inspector, observations: visit.observations || null } })
            setVisit({ visitedOn: today(), inspector: '', observations: '' })
          }, t('Visit recorded.'))}>
            <div className="grid2">
              <label>{t('Date of the visit')}<input type="date" required max={today()} value={visit.visitedOn} onChange={(e) => setVisit({ ...visit, visitedOn: e.target.value })} /></label>
              <label>{t('Inspector')}<input required maxLength={150} value={visit.inspector} onChange={(e) => setVisit({ ...visit, inspector: e.target.value })} /></label>
            </div>
            <label>{t('Observations and formal notices')}<textarea maxLength={2000} rows={3} value={visit.observations} onChange={(e) => setVisit({ ...visit, observations: e.target.value })} /></label>
            <Button type="submit">{t('Record the visit')}</Button>
          </Form>
        </div>
      )}

      {page && page.rules.length > 0 && (
        <div className="card">
          <h3>{t('Due dates and legal basis')}</h3>
          <div className="table-wrap">
            <table>
              <thead><tr><th>{t('Filing')}</th><th>{t('Due')}</th><th>{t('Legal basis')}</th></tr></thead>
              <tbody>
                {page.rules.map((r) => (
                  <tr key={r.kind}>
                    <td><strong>{kindLabel(r.kind, s?.country)}</strong> <span className="muted small">{r.authority}</span></td>
                    <td className="small">{r.dueDays !== null
                      ? <>{t('{a} days after the event', { a: r.dueDays })}{r.remindAfterDays ? <div className="muted">{t('reminder after {a} days', { a: r.remindAfterDays })}</div> : null}</>
                      : (r.yearOffset ? t('By {a}/{b} of the next year', { a: r.dueDay ?? '', b: r.dueMonth ?? '' }) : t('By {a}/{b} of the year', { a: r.dueDay ?? '', b: r.dueMonth ?? '' }))}</td>
                    <td className="small">{r.legalBasis}{!r.verified && <>{' '}<span className="badge warn">{t('To confirm')}</span></>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <p className="hint">{t('No official form was found for these filings: the documents carry what the texts ask for, in HR Pay\'s layout. A reminder goes out to those who manage the declarations.')}</p>
        </div>
      )}

      {filing && <FiledModal filing={filing} onClose={() => setFiling(null)}
        onSave={(body) => run(async () => { await api(`${filing.path}/filed`, { method: 'POST', body }); setFiling(null) }, t('Recorded as filed.'))} />}
    </section>
  )
}
