import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { ApiError, api, money, type PayrollRun, type RunView } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'
import { ThirteenthMonthCard } from '../components/ThirteenthMonthCard'

const lastMonth = () => { const d = new Date(); d.setDate(1); d.setMonth(d.getMonth() - 1); return d.toISOString().slice(0, 7) }
const STATUS = { CALCULATED: 'Calculated, waiting for approval', APPROVED: 'Approved, ready to close', CLOSED: 'Closed' }
type Raise = { id: number; userId: number; userName: string; anniversaryDate: string; years: number; effectiveFrom: string; gradeCode: string; percent: number; raiseAmount: number; oldBasic: number; newBasic: number; currency: string; notifiedAt: string | null }
const when = (iso: string | null) => (iso ? new Date(iso).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' }) : '')

type HolidayAdjustment = { id: number; userId: number; userName: string; payslipId: number; closedMonth: string; amount: number; currency: string; targetMonth: string; note: string; createdBy: string }

export default function PayrollRunsPage() {
  const { can } = useAuth()
  const [runs, setRuns] = useState<PayrollRun[]>([])
  const [month, setMonth] = useState(lastMonth())
  const [view, setView] = useState<RunView | null>(null)
  const [busy, setBusy] = useState(false)
  const [raises, setRaises] = useState<Raise[]>([])
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const [direction, setDirection] = useState('')
  const [adjustments, setAdjustments] = useState<HolidayAdjustment[]>([])
  useEffect(() => { api<HolidayAdjustment[]>('/api/payroll/holiday-adjustments').then(setAdjustments).catch(() => undefined) }, [])

  const loadRuns = () => api<PayrollRun[]>('/api/payroll/runs').then(setRuns).catch((e: Error) => setError(e.message))
  const loadView = (m: string) =>
    api<RunView>(`/api/payroll/runs/${m}`).then(setView).catch((e) => { setView(null); if (!(e instanceof ApiError && e.status === 404)) setError(e.message) })
  const loadRaises = () => (can('salary.read') ? api<Raise[]>('/api/raises').then(setRaises).catch(() => undefined) : Promise.resolve())
  useEffect(() => { void loadRuns(); void loadRaises() }, []) // eslint-disable-line react-hooks/exhaustive-deps
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
  // Subtotals per Direction and currency, from the unit each payslip was made for.
  const byDirection = new Map<string, { name: string; currency: string; payslips: number; gross: number; deductions: number; net: number; employer: number }>()
  for (const p of view?.payslips ?? []) {
    const key = `${p.directionId ?? 0}|${p.currency}`
    const t = byDirection.get(key) ?? { name: p.directionName ?? 'No unit', currency: p.currency, payslips: 0, gross: 0, deductions: 0, net: 0, employer: 0 }
    t.payslips++; t.gross += p.gross; t.deductions += p.employeeDeductions; t.net += p.net; t.employer += p.employerCharges
    byDirection.set(key, t)
  }
  const directions = [...new Map((view?.payslips ?? []).map((p) => [String(p.directionId ?? 0), p.directionName ?? 'No unit'])).entries()]
    .sort((a, b) => a[1].localeCompare(b[1]))
  const shown = (view?.payslips ?? []).filter((p) => !direction || String(p.directionId ?? 0) === direction)
  const warnings = view?.payslips.reduce((n, p) => n + p.warningCount, 0) ?? 0

  const pending = adjustments.filter((a) => a.targetMonth.slice(0, 7) >= month)
  return (
    <section>
      <h2>Payroll</h2>
      <ErrorText error={error} />
      {message && <p className="success">{message}</p>}
      {pending.length > 0 && (
        <details className="card">
          <summary>Holiday changes carried into a later payroll ({pending.length})</summary>
          <p className="hint">A holiday moved inside a closed month changes that month's basic pay. The difference is added to the payroll shown; calculate it again to include it.</p>
          <div className="table-wrap">
            <table>
              <thead><tr><th>Employee</th><th>Closed month</th><th>Amount</th><th>Into</th><th>Why</th></tr></thead>
              <tbody>
                {pending.map((a) => (
                  <tr key={a.id}><td>{a.userName}</td><td>{a.closedMonth}</td><td>{money(a.amount, a.currency)}</td>
                    <td>{a.targetMonth.slice(0, 7)}</td><td className="small">{a.note}</td></tr>
                ))}
              </tbody>
            </table>
          </div>
        </details>
      )}

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

          {byDirection.size > 0 && (
            <div className="card">
              <h3>By Direction</h3>
              <div className="table-wrap">
                <table>
                  <thead><tr><th>Direction</th><th>Payslips</th><th>Gross</th><th>Employee deductions</th><th>Net to pay</th><th>Employer charges</th></tr></thead>
                  <tbody>
                    {[...byDirection.values()].sort((a, b) => a.name.localeCompare(b.name) || a.currency.localeCompare(b.currency)).map((t) => (
                      <tr key={t.name + t.currency}><td>{t.name}</td><td>{t.payslips}</td><td>{money(t.gross, t.currency)}</td><td>{money(t.deductions, t.currency)}</td><td>{money(t.net, t.currency)}</td><td>{money(t.employer, t.currency)}</td></tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <p className="hint">Each payslip counts in the Direction of the unit the employee was in at the end of the month.</p>
            </div>
          )}

          <div className="card">
            <h3>Payslips</h3>
            {directions.length > 1 && (
              <label className="inline-filter">Direction
                <select value={direction} onChange={(e) => setDirection(e.target.value)}>
                  <option value="">All</option>
                  {directions.map(([id, name]) => <option key={id} value={id}>{name}</option>)}
                </select>
              </label>
            )}
            {warnings > 0 && <p className="notice">{warnings} warning(s) to check before approval: open the payslips marked ⚠.</p>}
            <div className="table-wrap">
              <table>
                <thead><tr><th>Employee</th><th>Unit</th><th>No.</th><th>Gross</th><th>Deductions</th><th>Net</th><th>Employer</th><th></th></tr></thead>
                <tbody>
                  {shown.map((p) => (
                    <tr key={p.id}>
                      <td>{p.userName} <span className="muted">{p.registrationNo}</span></td>
                      <td>{p.unitName ?? ''}</td>
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

      {can('salary.read') && (
        <div className="card">
          <h3>Anniversary raises</h3>
          <p className="hint">Applied automatically each morning for permanent staff; the rate and start month are set per grade category under Grades. HR gets one email on the 1st of each month.</p>
          {raises.length === 0 ? <p className="muted">No raises applied yet.</p> : (
            <div className="table-wrap">
              <table>
                <thead><tr><th>Employee</th><th>Anniversary</th><th>Raise</th><th>Basic</th><th>From</th><th>HR told</th></tr></thead>
                <tbody>
                  {raises.map((r) => (
                    <tr key={r.id}>
                      <td>{r.userName}</td>
                      <td>{r.anniversaryDate} ({r.years} yr)</td>
                      <td>{money(r.raiseAmount, r.currency)} <span className="muted">({r.percent}% of {r.gradeCode})</span></td>
                      <td>{money(r.oldBasic, r.currency)} → <strong>{money(r.newBasic, r.currency)}</strong></td>
                      <td>{r.effectiveFrom}</td>
                      <td>{r.notifiedAt ? 'Yes' : 'Not yet'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {can('salary.write') && (
            <div className="row">
              <button className="secondary" disabled={busy} onClick={async () => {
                setError(null); setMessage(null)
                try {
                  const o = await api<{ applied: Raise[]; skipped: string[] }>('/api/raises/apply', { method: 'POST' })
                  setMessage(`${o.applied.length} raise(s) applied.${o.skipped.length ? ' To do by hand: ' + o.skipped.join(' ') : ''}`)
                  await loadRaises()
                } catch (e) { setError((e as Error).message) }
              }}>Check today's anniversaries now</button>
              <button className="secondary" disabled={busy} onClick={async () => {
                setError(null); setMessage(null)
                try {
                  const r = await api<{ listed: number }>('/api/raises/notify', { method: 'POST' })
                  setMessage(r.listed ? `Email sent to HR listing ${r.listed} raise(s).` : 'No new raise to report.')
                  await loadRaises()
                } catch (e) { setError((e as Error).message) }
              }}>Email HR the list now</button>
            </div>
          )}
        </div>
      )}
      {can('salary.read') && <ThirteenthMonthCard />}
    </section>
  )
}
