import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { ApiError, api, money } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'
import { useAsk } from '../components/Dialogs'
import { Modal } from '../components/Modal'
import { Button, Form } from '../components/Button'

type Kind = 'CNSS' | 'IPR' | 'INPP' | 'ONEM' | 'IERE'
type Filed = { filedOn: string; reference: string | null; note: string | null; filedBy: string; filedAt: string }
type Summary = { kind: Kind; employees: number; totalCdf: number; filed: Filed | null }
type Employer = { name: string; address: string; cnssNumber: string; taxNumber: string; nationalId: string; rccm: string; inppNumber: string; onemNumber: string; bankName: string; bankAccount: string }
type Row = { userId: number; registrationNo: string; name: string; idNumber: string | null; currency: string; base: number; baseCdf: number; employeeCdf: number; employerCdf: number; totalCdf: number; directionName: string | null; unitName: string | null }
type Declaration = {
  month: string; kind: Kind; runStatus: string; usdCdf: number; rateDate: string; rows: Row[]
  baseCdf: number; employeeCdf: number; employerCdf: number; totalCdf: number; warnings: string[]; filed: Filed | null
}

const KIND: Record<Kind, { name: string; to: string }> = {
  CNSS: { name: 'CNSS (social security)', to: 'CNSS' },
  IPR: { name: 'IPR withheld', to: 'DGI' },
  INPP: { name: 'INPP (training levy)', to: 'INPP' },
  ONEM: { name: 'ONEM (employment office)', to: 'ONEM' },
  IERE: { name: 'IERE (expatriates)', to: 'DGI' },
}
const EMPLOYER_FIELDS: [keyof Employer, string][] = [
  ['name', 'Name'], ['address', 'Address'], ['cnssNumber', 'CNSS employer number'], ['taxNumber', 'NIF'], ['nationalId', 'Id. Nat.'],
  ['rccm', 'RCCM'], ['inppNumber', 'INPP number'], ['onemNumber', 'ONEM number'], ['bankName', 'Bank'], ['bankAccount', 'Account salaries are paid from'],
]
const lastMonth = () => { const d = new Date(); d.setDate(1); d.setMonth(d.getMonth() - 1); return d.toISOString().slice(0, 7) }
const cdf = (n: number) => money(n, 'CDF')

/** Subtotals by Direction, for the cost owners; the file sent stays company-wide. */
function ByDirection({ rows }: { rows: Row[] }) {
  const groups = new Map<string, { people: number; base: number; employee: number; employer: number; total: number }>()
  rows.forEach((r) => {
    const k = r.directionName ?? 'No unit'
    const g = groups.get(k) ?? { people: 0, base: 0, employee: 0, employer: 0, total: 0 }
    groups.set(k, { people: g.people + 1, base: g.base + r.baseCdf, employee: g.employee + r.employeeCdf, employer: g.employer + r.employerCdf, total: g.total + r.totalCdf })
  })
  if (groups.size < 2) return null
  return (
    <div className="table-wrap no-print">
      <h4>By Direction</h4>
      <table>
        <thead><tr><th>Direction</th><th>People</th><th>Base</th><th>Employee</th><th>Employer</th><th>Total</th></tr></thead>
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
  const { can } = useAuth()
  const [params, setParams] = useSearchParams()
  const month = params.get('month') ?? lastMonth()
  const kind = (params.get('kind') ?? 'CNSS') as Kind
  const [summary, setSummary] = useState<Summary[] | null>(null)
  const [employer, setEmployer] = useState<Employer | null>(null)
  const [editEmployer, setEditEmployer] = useState<Employer | null>(null)
  const [decl, setDecl] = useState<Declaration | null>(null)
  const [filing, setFiling] = useState<{ filedOn: string; reference: string; note: string } | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)

  const load = () => {
    setError(null)
    api<{ company: Employer; declarations: Summary[] }>(`/api/declarations/${month}`)
      .then((r) => { setSummary(r.declarations); setEmployer(r.company) })
      .catch((e) => { setSummary(null); setDecl(null); setError(e instanceof ApiError && e.status === 404 ? `No payroll for ${month} yet.` : (e as Error).message) })
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
      setError(e instanceof ApiError ? [e.message, ...Object.values(e.fieldErrors)].join(' ') : 'Something went wrong.')
      return false
    }
  }

  const employeeCol = kind === 'CNSS' || kind === 'IPR'
  const employerCol = kind !== 'IPR'

  return (
    <section>
      <h2 className="no-print">Declarations</h2>
      <label className="month-pick no-print">Month <input type="month" value={month} onChange={(e) => e.target.value && setParams({ month: e.target.value, kind })} /></label>
      <ErrorText error={error} />
      {message && <p className="success no-print">{message}</p>}

      {summary && (
        <div className="card no-print">
          <div className="table-wrap">
            <table>
              <thead><tr><th>Declaration</th><th>To</th><th>Employees</th><th>Total</th><th>Filed</th><th></th></tr></thead>
              <tbody>
                {summary.map((s) => (
                  <tr key={s.kind} className={s.kind === kind ? 'selected' : undefined}>
                    <td>{KIND[s.kind].name}</td><td>{KIND[s.kind].to}</td><td>{s.employees}</td><td>{cdf(s.totalCdf)}</td>
                    <td>{s.filed ? `${s.filed.filedOn}${s.filed.reference ? ` · ${s.filed.reference}` : ''}` : <span className="muted">Not yet</span>}</td>
                    <td><Button className="link" onClick={() => setParams({ month, kind: s.kind })}>Open</Button></td>
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
          <p>{employer?.name} · {decl.kind === 'CNSS' ? `CNSS no. ${employer?.cnssNumber ?? '—'}` : decl.kind === 'INPP' ? `INPP no. ${employer?.inppNumber ?? '—'}` : decl.kind === 'ONEM' ? `ONEM no. ${employer?.onemNumber ?? '—'}` : `NIF ${employer?.taxNumber ?? '—'}`}</p>
          <p className="muted small">Amounts in CDF at the payroll's rate: 1 USD = {decl.usdCdf} CDF ({decl.rateDate}).</p>
          {decl.warnings.length > 0 && <div className="notice no-print"><ul>{decl.warnings.map((w) => <li key={w}>{w}</li>)}</ul></div>}
          {decl.rows.length === 0 ? <p className="muted">Nobody is concerned this month.</p> : (
            <div className="table-wrap">
              <table>
                <thead><tr><th>No</th><th>Employee</th>{decl.kind === 'CNSS' && <th>CNSS no.</th>}{decl.kind === 'IPR' && <th>NIF</th>}<th>Base</th>
                  {employeeCol && <th>{decl.kind === 'IPR' ? 'IPR' : 'Employee'}</th>}{employerCol && <th>Employer</th>}<th>Total</th></tr></thead>
                <tbody>
                  {decl.rows.map((r, i) => (
                    <tr key={r.userId}>
                      <td>{i + 1}</td><td>{r.name} <span className="muted">{r.registrationNo}</span>{r.unitName && <div className="hint no-print">{r.unitName}</div>}</td>
                      {(decl.kind === 'CNSS' || decl.kind === 'IPR') && <td>{r.idNumber ?? <span className="error">missing</span>}</td>}
                      <td>{cdf(r.baseCdf)}{r.currency !== 'CDF' && <div className="hint">{money(r.base, r.currency)}</div>}</td>
                      {employeeCol && <td>{cdf(r.employeeCdf)}</td>}{employerCol && <td>{cdf(r.employerCdf)}</td>}<td>{cdf(r.totalCdf)}</td>
                    </tr>
                  ))}
                </tbody>
                <tfoot><tr><td></td><td><strong>Total</strong></td>{(decl.kind === 'CNSS' || decl.kind === 'IPR') && <td></td>}<td>{cdf(decl.baseCdf)}</td>
                  {employeeCol && <td>{cdf(decl.employeeCdf)}</td>}{employerCol && <td>{cdf(decl.employerCdf)}</td>}<td><strong>{cdf(decl.totalCdf)}</strong></td></tr></tfoot>
              </table>
            </div>
          )}
          <ByDirection rows={decl.rows} />
          {decl.filed && <p>Filed on {decl.filed.filedOn}{decl.filed.reference && `, reference ${decl.filed.reference}`}{decl.filed.note && ` (${decl.filed.note})`}. Recorded by {decl.filed.filedBy}.</p>}
          <div className="row no-print">
            <a className="button secondary" href={`/api/declarations/${month}/${kind}/file.csv`}>Download CSV</a>
            <Button className="secondary" onClick={() => window.print()}>Print or save as PDF</Button>
            {decl.runStatus === 'CLOSED' && !decl.filed && !filing && <Button onClick={() => setFiling({ filedOn: new Date().toISOString().slice(0, 10), reference: '', note: '' })}>Record as filed</Button>}
            {decl.filed && <Button className="link" onClick={() => ask({ title: 'Remove the filing record?', confirmLabel: 'Remove', danger: true, onConfirm: () => run(() => api(`/api/declarations/${month}/${kind}/filed`, { method: 'DELETE' }), 'Filing record removed.') })}>Undo filing</Button>}
          </div>
          {filing && (
            <Modal title={`Record ${kind} for ${month} as filed`} size="sm" onClose={() => setFiling(null)}>
              <Form onSubmit={() => run(async () => { await api(`/api/declarations/${month}/${kind}/filed`, { method: 'POST', body: { filedOn: filing.filedOn, reference: filing.reference || null, note: filing.note || null } }); setFiling(null) }, `${kind} for ${month} recorded as filed.`)}>
                <label>Filed on<input type="date" required value={filing.filedOn} onChange={(e) => setFiling({ ...filing, filedOn: e.target.value })} /></label>
                <label>Receipt or reference<input maxLength={100} value={filing.reference} onChange={(e) => setFiling({ ...filing, reference: e.target.value })} /></label>
                <label>Note<input maxLength={300} value={filing.note} onChange={(e) => setFiling({ ...filing, note: e.target.value })} /></label>
                <div className="modal-foot"><Button className="secondary" onClick={() => setFiling(null)}>Cancel</Button><Button type="submit">Save</Button></div>
              </Form>
            </Modal>
          )}
        </div>
      )}

      {employer && (
        <div className="card no-print">
          <h3>Employer details</h3>
          <p className="hint">Printed on declarations and payment files.</p>
          <dl className="facts">{EMPLOYER_FIELDS.map(([k, label]) => <div key={k}><dt>{label}</dt><dd>{employer[k] || '—'}</dd></div>)}</dl>
          {can('company.manage') && <Button className="secondary" onClick={() => setEditEmployer(employer)}>Edit</Button>}
          {editEmployer && (
            <Modal title="Employer details" size="lg" onClose={() => setEditEmployer(null)}>
              <Form onSubmit={() => run(async () => { await api('/api/company', { method: 'PUT', body: editEmployer }); setEditEmployer(null) }, 'Employer details saved.')}>
                <div className="grid2">
                  {EMPLOYER_FIELDS.map(([k, label]) => (
                    <label key={k}>{label}<input value={editEmployer[k] ?? ''} required={k === 'name'} onChange={(e) => setEditEmployer({ ...editEmployer, [k]: e.target.value })} /></label>
                  ))}
                </div>
                <div className="modal-foot"><Button className="secondary" onClick={() => setEditEmployer(null)}>Cancel</Button><Button type="submit">Save</Button></div>
              </Form>
            </Modal>
          )}
        </div>
      )}
    </section>
  )
}
