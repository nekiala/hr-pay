import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { ApiError, api, money, type PayrollRun, type RunView } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'

const lastMonth = () => { const d = new Date(); d.setDate(1); d.setMonth(d.getMonth() - 1); return d.toISOString().slice(0, 7) }
const STATUS = { CALCULATED: 'Calculated, waiting for approval', APPROVED: 'Approved, ready to close', CLOSED: 'Closed' }
const when = (iso: string | null) => (iso ? new Date(iso).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' }) : '')

export default function PayrollRunsPage() {
  const { can } = useAuth()
  const [runs, setRuns] = useState<PayrollRun[]>([])
  const [month, setMonth] = useState(lastMonth())
  const [view, setView] = useState<RunView | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)

  const loadRuns = () => api<PayrollRun[]>('/api/payroll/runs').then(setRuns).catch((e: Error) => setError(e.message))
  const loadView = (m: string) =>
    api<RunView>(`/api/payroll/runs/${m}`).then(setView).catch((e) => { setView(null); if (!(e instanceof ApiError && e.status === 404)) setError(e.message) })
  useEffect(() => { void loadRuns() }, [])
  useEffect(() => { void loadView(month) }, [month])

  const act = async (path: string, done: string, body?: unknown) => {
    setError(null)
    setMessage(null)
    setBusy(true)
    try {
      setView(await api<RunView>(`/api/payroll/runs/${month}/${path}`, { method: 'POST', body }))
      setMessage(done)
      await loadRuns()
    } catch (e) {
      setError(e instanceof ApiError ? [e.message, ...Object.values(e.fieldErrors)].join(' ') : 'Something went wrong.')
    } finally {
      setBusy(false)
    }
  }

  const run = view?.run
  const warnings = view?.payslips.reduce((n, p) => n + p.warningCount, 0) ?? 0

  return (
    <section>
      <h2>Payroll</h2>
      <ErrorText error={error} />
      {message && <p className="success">{message}</p>}

      <div className="card">
        <div className="row">
          <label className="month-pick">Month <input type="month" value={month} onChange={(e) => e.target.value && setMonth(e.target.value)} /></label>
          {runs.length > 0 && (
            <span className="muted">Recent: {runs.slice(0, 6).map((r) => (
              <button key={r.id} className="link" onClick={() => setMonth(r.month.slice(0, 7))}>{r.month.slice(0, 7)} ({r.status.toLowerCase()})</button>
            ))}</span>
          )}
        </div>

        {!run && <p className="muted">No payroll for {month} yet.</p>}
        {run && (
          <>
            <p><strong>{STATUS[run.status]}</strong> · {run.employeeCount} payslips · $1 = {run.usdCdf} CDF (BCC, {run.rateDate})</p>
            <p className="hint">
              Calculated by {run.calculatedBy}, {when(run.calculatedAt)}
              {run.approvedBy && ` · approved by ${run.approvedBy}, ${when(run.approvedAt)}`}
              {run.closedBy && ` · closed by ${run.closedBy}, ${when(run.closedAt)}`}
            </p>
            {run.projected && <p className="notice">Calculated before the end of the month: the remaining working days are counted as worked. Calculate again once the month is over; it cannot be approved before.</p>}
          </>
        )}

        <div className="row">
          {can('payroll.run') && (!run || run.status === 'CALCULATED') && (
            <button disabled={busy} onClick={() => void act('calculate', 'Payroll calculated.')}>{run ? 'Calculate again' : `Calculate ${month}`}</button>
          )}
          {can('payroll.approve') && run?.status === 'CALCULATED' && (
            <button disabled={busy} onClick={() => { if (confirm(`Approve the ${month} payroll?`)) void act('approve', 'Payroll approved.') }}>Approve</button>
          )}
          {(can('payroll.approve') || can('payroll.run')) && run?.status === 'APPROVED' && (
            <button className="secondary" disabled={busy} onClick={() => {
              const reason = prompt('Why is it sent back?')
              if (reason) void act('send-back', 'Sent back for changes.', { reason })
            }}>Send back</button>
          )}
          {can('payroll.close') && run?.status === 'APPROVED' && (
            <button disabled={busy} onClick={() => { if (confirm(`Close ${month}? Payslips are numbered and locked, and loan repayments are recorded. This cannot be undone.`)) void act('close', 'Month closed. Employees can now see their payslips.') }}>Close the month</button>
          )}
        </div>
        {busy && <p className="muted">Working…</p>}
      </div>

      {view && view.skipped.length > 0 && (
        <div className="card">
          <h3>Not included</h3>
          <ul>{view.skipped.map((s) => <li key={s}>{s}</li>)}</ul>
        </div>
      )}

      {view && (
        <>
          <div className="card">
            <h3>Totals</h3>
            <div className="table-wrap">
              <table>
                <thead><tr><th>Currency</th><th>Payslips</th><th>Gross</th><th>Employee deductions</th><th>Net to pay</th><th>Employer charges</th></tr></thead>
                <tbody>
                  {view.totals.map((t) => (
                    <tr key={t.currency}><td>{t.currency}</td><td>{t.payslips}</td><td>{money(t.gross, t.currency)}</td><td>{money(t.employeeDeductions, t.currency)}</td><td><strong>{money(t.net, t.currency)}</strong></td><td>{money(t.employerCharges, t.currency)}</td></tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>

          <div className="card">
            <h3>Payslips</h3>
            {warnings > 0 && <p className="notice">{warnings} warning(s) to check before approval: open the payslips marked ⚠.</p>}
            <div className="table-wrap">
              <table>
                <thead><tr><th>Employee</th><th>No.</th><th>Gross</th><th>Deductions</th><th>Net</th><th>Employer</th><th></th></tr></thead>
                <tbody>
                  {view.payslips.map((p) => (
                    <tr key={p.id}>
                      <td>{p.userName} <span className="muted">{p.registrationNo}</span></td>
                      <td>{p.number ?? ''}</td>
                      <td>{money(p.gross, p.currency)}</td>
                      <td>{money(p.employeeDeductions, p.currency)}</td>
                      <td><strong>{money(p.net, p.currency)}</strong></td>
                      <td>{money(p.employerCharges, p.currency)}</td>
                      <td><Link to={`/payslips/${p.id}`}>{p.warningCount > 0 ? '⚠ Open' : 'Open'}</Link></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        </>
      )}
    </section>
  )
}
