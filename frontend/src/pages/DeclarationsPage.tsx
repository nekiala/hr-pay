import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { ApiError, api, longDay, money, day } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'
import { useAsk } from '../components/Dialogs'
import { Modal } from '../components/Modal'
import { Button, Form } from '../components/Button'
import { localCurrency, t } from '../i18n'
import { PayDeclarationModal, STATUS_BADGE, statusLabel, type CalendarItem } from '../components/DeclarationStatus'
import { DateInput } from '../components/DateFields'

type Kind = 'CNSS' | 'IPR' | 'INPP' | 'ONEM' | 'IERE' | 'TUS' | 'CNAMGS' | 'CAC' | 'CFC' | 'FNE' | 'RAV' | 'TDL' | 'TCS' | 'FNH' | 'CFP'
type Filed = { filedOn: string; reference: string | null; note: string | null; filedBy: string; filedAt: string }
type Summary = { kind: Kind; employees: number; totalCdf: number; filed: Filed | null }
type Employer = { name: string; address: string; cnssNumber: string; taxNumber: string; nationalId: string; rccm: string; inppNumber: string; onemNumber: string; bankName: string; bankAccount: string }
type Row = { userId: number; registrationNo: string; name: string; idNumber: string | null; currency: string; base: number; baseCdf: number; employeeCdf: number; employerCdf: number; totalCdf: number; directionName: string | null; unitName: string | null }
type Declaration = {
  month: string; kind: Kind; runStatus: string; usdCdf: number; rateDate: string; rows: Row[]
  baseCdf: number; employeeCdf: number; employerCdf: number; totalCdf: number; warnings: string[]; filed: Filed | null
}

const KIND: Record<Kind, { name: string; to: string }> = {
  CNSS: { name: t('CNSS (social security)'), to: 'CNSS' },
  IPR: { name: t('IPR withheld'), to: 'DGI' },
  INPP: { name: t('INPP (training levy)'), to: 'INPP' },
  ONEM: { name: t('ONEM (employment office)'), to: 'ONEM' },
  IERE: { name: t('IERE (expatriates)'), to: 'DGI' },
  TUS: { name: t('TUS (single tax on salaries)'), to: t('Tax office') },
  CNAMGS: { name: t('CNAMGS (health insurance)'), to: 'CNAMGS' },
  CAC: { name: t('CAC (additional council tax)'), to: 'DGI' },
  CFC: { name: t('Crédit foncier (CFC)'), to: 'DGI' },
  FNE: { name: t('FNE (national employment fund)'), to: 'DGI' },
  RAV: { name: t('RAV (audiovisual licence fee)'), to: 'DGI' },
  TDL: { name: t('TDL (local development tax)'), to: 'DGI' },
  TCS: { name: t('TCS (additional tax on salaries)'), to: 'DGI' },
  FNH: { name: t('FNH (national housing fund)'), to: 'DGI' },
  CFP: { name: t('Vocational training contribution'), to: 'DGI' },
}
const lastMonth = () => { const d = new Date(); d.setDate(1); d.setMonth(d.getMonth() - 1); return d.toISOString().slice(0, 7) }
const cdf = (n: number) => money(n, localCurrency())

/** Subtotals by Direction, for the cost owners; the file sent stays company-wide. */
function ByDirection({ rows }: { rows: Row[] }) {
  const groups = new Map<string, { people: number; base: number; employee: number; employer: number; total: number }>()
  rows.forEach((r) => {
    const k = r.directionName ?? t('No unit')
    const g = groups.get(k) ?? { people: 0, base: 0, employee: 0, employer: 0, total: 0 }
    groups.set(k, { people: g.people + 1, base: g.base + r.baseCdf, employee: g.employee + r.employeeCdf, employer: g.employer + r.employerCdf, total: g.total + r.totalCdf })
  })
  if (groups.size < 2) return null
  return (
    <div className="table-wrap no-print">
      <h4>{t('By Direction')}</h4>
      <table>
        <thead><tr><th>{t('Direction')}</th><th>{t('People')}</th><th>{t('Base')}</th><th>{t('Employee')}</th><th>{t('Employer')}</th><th>{t('Total')}</th></tr></thead>
        <tbody>
          {[...groups.entries()].sort((a, b) => a[0].localeCompare(b[0])).map(([k, g]) => (
            <tr key={k}><td>{k}</td><td>{g.people}</td><td>{cdf(g.base)}</td><td>{cdf(g.employee)}</td><td>{cdf(g.employer)}</td><td>{cdf(g.total)}</td></tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

export default function DeclarationsPage() {
  const ask = useAsk()
  const { can, me } = useAuth()
  const [params, setParams] = useSearchParams()
  const month = params.get('month') ?? lastMonth()
  const kind = (params.get('kind') ?? 'CNSS') as Kind
  const [summary, setSummary] = useState<Summary[] | null>(null)
  const [employer, setEmployer] = useState<Employer | null>(null)
  const [decl, setDecl] = useState<Declaration | null>(null)
  const [calendar, setCalendar] = useState<CalendarItem[]>([])
  const [paying, setPaying] = useState<CalendarItem | null>(null)
  const [filing, setFiling] = useState<{ filedOn: string; reference: string; note: string } | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)

  const load = () => {
    setError(null)
    api<{ company: Employer; declarations: Summary[]; calendar: CalendarItem[] }>(`/api/declarations/${month}`)
      .then((r) => { setSummary(r.declarations); setEmployer(r.company); setCalendar(r.calendar ?? []) })
      .catch((e) => { setSummary(null); setDecl(null); setCalendar([]); setError(e instanceof ApiError && e.status === 404 ? t('No payroll for {a} yet.', { a: month }) : (e as Error).message) })
    api<Declaration>(`/api/declarations/${month}/${kind}`).then(setDecl).catch(() => setDecl(null))
  }
  useEffect(load, [month, kind]) // eslint-disable-line react-hooks/exhaustive-deps

  const run = async (action: () => Promise<unknown>, done: string) => {
    setError(null)
    setMessage(null)
    try {
      await action()
      setMessage(done)
      load()
      return true
    } catch (e) {
      setError(e instanceof ApiError ? [e.message, ...Object.values(e.fieldErrors)].join(' ') : t('Something went wrong.'))
      return false
    }
  }

  const employeeCol = kind === 'CNSS' || kind === 'IPR'
  const item = (k: string) => calendar.find((c) => c.kind === k)
  const current = item(kind)
  const employerCol = kind !== 'IPR'

  return (
    <section>
      <div className="row space no-print">
        <h2>{t('Declarations')}</h2>
        <div className="row">
          <Link className="button secondary" to="/payroll/declarations/calendar">{t('Calendar')}</Link>
          {can('company.manage') && <Link className="button secondary" to="/company">{t('Employer details')}</Link>}
        </div>
      </div>
      <label className="month-pick no-print">{t('Month')}{' '}<input type="month" value={month} onChange={(e) => e.target.value && setParams({ month: e.target.value, kind })} /></label>
      <ErrorText error={error} />
      {message && <p className="success no-print">{message}</p>}

      {summary && (
        <div className="card no-print">
          <div className="table-wrap">
            <table>
              <thead><tr><th>{t('Declaration')}</th><th>{t('To')}</th><th>{t('Employees')}</th><th>{t('Total')}</th><th>{t('Due')}</th><th>{t('Status')}</th><th></th></tr></thead>
              <tbody>
                {summary.map((s) => (
                  <tr key={s.kind} className={s.kind === kind ? 'selected' : undefined}>
                    <td>{KIND[s.kind].name}</td><td>{KIND[s.kind].to}</td><td>{s.employees}</td><td>{cdf(s.totalCdf)}</td>
                    <td className="small">{item(s.kind) ? longDay(item(s.kind)!.nextDue) : '—'}</td>
                    <td>{item(s.kind) ? <span className={`badge ${item(s.kind)!.late && item(s.kind)!.status !== 'PAID' ? 'off' : STATUS_BADGE[item(s.kind)!.status]}`}>{statusLabel(item(s.kind)!)}</span>
                      : s.filed ? `${day(s.filed.filedOn)}${s.filed.reference ? ` · ${s.filed.reference}` : ''}` : <span className="muted">{t('Not yet')}</span>}</td>
                    <td><Button className="link" onClick={() => setParams({ month, kind: s.kind })}>{t('Open')}</Button></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {decl && (
        <div className="card payslip">
          <h3>{KIND[decl.kind].name} · {decl.month}</h3>
          <p>{employer?.name} · {decl.kind === 'CNSS' ? t('CNSS no. {a}', { a: employer?.cnssNumber ?? '—' }) : decl.kind === 'INPP' ? t('INPP no. {a}', { a: employer?.inppNumber ?? '—' }) : decl.kind === 'ONEM' ? t('ONEM no. {a}', { a: employer?.onemNumber ?? '—' }) : t('NIF {a}', { a: employer?.taxNumber ?? '—' })}</p>
          <p className="muted small">{t('Amounts in CDF at the payroll\'s rate: 1 USD =')}{' '}{decl.usdCdf}{' '}{t('CDF (')}{day(decl.rateDate)}).</p>
          {decl.warnings.length > 0 && <div className="notice no-print"><ul>{decl.warnings.map((w) => <li key={w}>{w}</li>)}</ul></div>}
          {decl.rows.length === 0 ? <p className="muted">{t('Nobody is concerned this month.')}</p> : (
            <div className="table-wrap">
              <table>
                <thead><tr><th>{t('No')}</th><th>{t('Employee')}</th>{decl.kind === 'CNSS' && <th>{t('CNSS no.')}</th>}{decl.kind === 'IPR' && <th>{t('NIF')}</th>}<th>{t('Base')}</th>
                  {employeeCol && <th>{decl.kind === 'IPR' ? 'IPR' : t('Employee')}</th>}{employerCol && <th>{t('Employer')}</th>}<th>{t('Total')}</th></tr></thead>
                <tbody>
                  {decl.rows.map((r, i) => (
                    <tr key={r.userId}>
                      <td>{i + 1}</td><td>{r.name} <span className="muted">{r.registrationNo}</span>{r.unitName && <div className="hint no-print">{r.unitName}</div>}</td>
                      {(decl.kind === 'CNSS' || decl.kind === 'IPR') && <td>{r.idNumber ?? <span className="error">{t('missing')}</span>}</td>}
                      <td>{cdf(r.baseCdf)}{r.currency !== localCurrency() && <div className="hint">{money(r.base, r.currency)}</div>}</td>
                      {employeeCol && <td>{cdf(r.employeeCdf)}</td>}{employerCol && <td>{cdf(r.employerCdf)}</td>}<td>{cdf(r.totalCdf)}</td>
                    </tr>
                  ))}
                </tbody>
                <tfoot><tr><td></td><td><strong>{t('Total')}</strong></td>{(decl.kind === 'CNSS' || decl.kind === 'IPR') && <td></td>}<td>{cdf(decl.baseCdf)}</td>
                  {employeeCol && <td>{cdf(decl.employeeCdf)}</td>}{employerCol && <td>{cdf(decl.employerCdf)}</td>}<td><strong>{cdf(decl.totalCdf)}</strong></td></tr></tfoot>
              </table>
            </div>
          )}
          <ByDirection rows={decl.rows} />
          {current?.paid && <p>{t('Paid on {a}: {b}', { a: longDay(current.paid.paidOn), b: money(current.paid.amount, localCurrency()) })}{current.paid.reference && t(', reference {a}', { a: current.paid.reference })}.</p>}
          {current?.penalty && current.penalty.total > 0 && <p className="notice small no-print">{t('Penalty estimate: {a}.', { a: money(current.penalty.total, localCurrency()) })}{' '}{current.penalty.how.join(' ')}</p>}
          {current?.annual && <p className="notice small no-print">{current.annual}</p>}
          {decl.filed && <p>{t('Filed on')}{' '}{day(decl.filed.filedOn)}{decl.filed.reference && t(', reference {a}', { a: decl.filed.reference })}{decl.filed.note && ` (${decl.filed.note})`}{t('. Recorded by')}{' '}{decl.filed.filedBy}.</p>}
          <div className="row no-print">
            <a className="button secondary" href={`/api/declarations/${month}/${kind}/file.csv`}>{t('Download CSV')}</a>
            <Button className="secondary" onClick={() => window.print()}>{t('Print or save as PDF')}</Button>
            {kind === 'CNSS' && me?.company?.country === 'CD' && <>
              <a className="button secondary" href={`/api/declarations/${month}/CNSS/mod-dc.pdf`} target="_blank" rel="noreferrer">{t('MOD. DC form')}</a>
              {decl.filed && <a className="button secondary" href={`/api/declarations/${month}/CNSS/mod-dcc.pdf`} target="_blank" rel="noreferrer">{t('MOD. DCC form')}</a>}
            </>}
            {current?.status === 'DECLARED' && <Button onClick={() => setPaying(current)}>{t('Record payment')}</Button>}
            {decl.runStatus === 'CLOSED' && !decl.filed && !filing && <Button onClick={() => setFiling({ filedOn: new Date().toISOString().slice(0, 10), reference: '', note: '' })}>{t('Record as filed')}</Button>}
            {decl.filed && !current?.paid && <Button className="link" onClick={() => ask({ title: t('Remove the filing record?'), confirmLabel: t('Remove'), danger: true, onConfirm: () => run(() => api(`/api/declarations/${month}/${kind}/filed`, { method: 'DELETE' }), t('Filing record removed.')) })}>{t('Undo filing')}</Button>}
          </div>
          {paying && <PayDeclarationModal item={paying} currency={localCurrency()} onClose={() => setPaying(null)}
            onSave={(body) => run(async () => { await api(`/api/declarations/${paying.month}/${paying.kind}/paid`, { method: 'POST', body }); setPaying(null) }, t('Payment recorded.'))} />}
          {filing && (
            <Modal title={t('Record {a} for {b} as filed', { a: kind, b: month })} size="sm" onClose={() => setFiling(null)}>
              <Form onSubmit={() => run(async () => { await api(`/api/declarations/${month}/${kind}/filed`, { method: 'POST', body: { filedOn: filing.filedOn, reference: filing.reference || null, note: filing.note || null } }); setFiling(null) }, t('{a} for {b} recorded as filed.', { a: kind, b: month }))}>
                <label>{t('Filed on')}<DateInput required value={filing.filedOn} onChange={(e) => setFiling({ ...filing, filedOn: e.target.value })} /></label>
                <label>{t('Receipt or reference')}<input maxLength={100} value={filing.reference} onChange={(e) => setFiling({ ...filing, reference: e.target.value })} /></label>
                <label>{t('Note')}<input maxLength={300} value={filing.note} onChange={(e) => setFiling({ ...filing, note: e.target.value })} /></label>
                <div className="modal-foot"><Button className="secondary" onClick={() => setFiling(null)}>{t('Cancel')}</Button><Button type="submit">{t('Save')}</Button></div>
              </Form>
            </Modal>
          )}
        </div>
      )}

    </section>
  )
}
