import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { ApiError, api, money, type PayrollRun, type RunView } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'
import { ThirteenthMonthCard } from '../components/ThirteenthMonthCard'
import { useAsk } from '../components/Dialogs'
import { Button } from '../components/Button'
import { t, locale } from '../i18n'

const lastMonth = () => { const d = new Date(); d.setDate(1); d.setMonth(d.getMonth() - 1); return d.toISOString().slice(0, 7) }
const STATUS = { CALCULATED: t('Calculated, waiting for approval'), APPROVED: t('Approved, ready to close'), CLOSED: t('Month closed') }
type Raise = { id: number; userId: number; userName: string; anniversaryDate: string; years: number; effectiveFrom: string; gradeCode: string; percent: number; raiseAmount: number; oldBasic: number; newBasic: number; currency: string; notifiedAt: string | null }
const when = (iso: string | null) => (iso ? new Date(iso).toLocaleString(locale(), { dateStyle: 'medium', timeStyle: 'short' }) : '')

type HolidayAdjustment = { id: number; userId: number; userName: string; payslipId: number; closedMonth: string; amount: number; currency: string; targetMonth: string; note: string; createdBy: string }

export default function PayrollRunsPage() {
  const ask = useAsk()
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
      return true
    } catch (e) {
      setError(e instanceof ApiError ? [e.message, ...Object.values(e.fieldErrors)].join(' ') : t('Something went wrong.'))
      return false
    } finally {
      setBusy(false)
    }
  }

  const run = view?.run
  // Subtotals per Direction and currency, from the unit each payslip was made for.
  const byDirection = new Map<string, { name: string; currency: string; payslips: number; gross: number; deductions: number; net: number; employer: number }>()
  for (const p of view?.payslips ?? []) {
    const key = `${p.directionId ?? 0}|${p.currency}`
    const tItem = byDirection.get(key) ?? { name: p.directionName ?? t('No unit'), currency: p.currency, payslips: 0, gross: 0, deductions: 0, net: 0, employer: 0 }
    tItem.payslips++; tItem.gross += p.gross; tItem.deductions += p.employeeDeductions; tItem.net += p.net; tItem.employer += p.employerCharges
    byDirection.set(key, tItem)
  }
  const directions = [...new Map((view?.payslips ?? []).map((p) => [String(p.directionId ?? 0), p.directionName ?? t('No unit')])).entries()]
    .sort((a, b) => a[1].localeCompare(b[1]))
  const shown = (view?.payslips ?? []).filter((p) => !direction || String(p.directionId ?? 0) === direction)
  const warnings = view?.payslips.reduce((n, p) => n + p.warningCount, 0) ?? 0

  const pending = adjustments.filter((a) => a.targetMonth.slice(0, 7) >= month)
  return (
    <section>
      <h2>{t('Payroll')}</h2>
      <ErrorText error={error} />
      {message && <p className="success">{message}</p>}
      {pending.length > 0 && (
        <details className="card">
          <summary>{t('Holiday changes carried into a later payroll (')}{pending.length})</summary>
          <p className="hint">{t('A holiday moved inside a closed month changes that month\'s basic pay. The difference is added to the payroll shown; calculate it again to include it.')}</p>
          <div className="table-wrap">
            <table>
              <thead><tr><th>{t('Employee')}</th><th>{t('Closed month')}</th><th>{t('Amount')}</th><th>{t('Into')}</th><th>{t('Why')}</th></tr></thead>
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
          <label className="month-pick">{t('Month')}{' '}<input type="month" value={month} onChange={(e) => e.target.value && setMonth(e.target.value)} /></label>
          {runs.length > 0 && (
            <span className="muted">{t('Recent:')}{' '}{runs.slice(0, 6).map((r) => (
              <Button key={r.id} className="link" onClick={() => setMonth(r.month.slice(0, 7))}>{r.month.slice(0, 7)} ({(STATUS[r.status as keyof typeof STATUS] ?? r.status).split(',')[0].toLowerCase()})</Button>
            ))}</span>
          )}
        </div>

        {!run && <p className="muted">{t('No payroll for')}{' '}{month}{' '}{t('yet.')}</p>}
        {run && (
          <>
            <p><strong>{STATUS[run.status]}</strong> · {run.employeeCount}{' '}{t('payslips · $1 =')}{' '}{run.usdCdf}{' '}{t('CDF (BCC,')}{' '}{run.rateDate})</p>
            <p className="hint">{t('Calculated by')}{' '}{run.calculatedBy}, {when(run.calculatedAt)}
              {run.approvedBy && t(' · approved by {a}, {b}', { a: run.approvedBy, b: when(run.approvedAt) })}
              {run.closedBy && t(' · closed by {a}, {b}', { a: run.closedBy, b: when(run.closedAt) })}
            </p>
            {run.projected && <p className="notice">{t('Calculated before the end of the month: the remaining working days are counted as worked. Calculate again once the month is over; it cannot be approved before.')}</p>}
          </>
        )}

        <div className="row">
          {can('payroll.run') && (!run || run.status === 'CALCULATED') && (
            <Button disabled={busy} onClick={() => act('calculate', t('Payroll calculated.'))}>{run ? t('Calculate again') : t('Calculate {a}', { a: month })}</Button>
          )}
          {can('payroll.approve') && run?.status === 'CALCULATED' && (
            <Button disabled={busy} onClick={() => ask({ title: t('Approve the {a} payroll?', { a: month }), message: t('HR can then close the month.'), confirmLabel: t('Approve'), onConfirm: () => act('approve', t('Payroll approved.')) })}>{t('Approve')}</Button>
          )}
          {(can('payroll.approve') || can('payroll.run')) && run?.status === 'APPROVED' && (
            <Button className="secondary" disabled={busy} onClick={() => ask({
              title: t('Send the {a} payroll back', { a: month }), confirmLabel: t('Send back'), fields: [{ label: t('What needs changing?'), required: true, multiline: true }],
              onConfirm: ([reason]) => act('send-back', t('Sent back for changes.'), { reason }),
            })}>{t('Send back')}</Button>
          )}
          {can('payroll.close') && run?.status === 'APPROVED' && (
            <Button disabled={busy} onClick={() => ask({ title: t('Close {a}?', { a: month }), message: t('Payslips are numbered and locked, and loan repayments are recorded. This cannot be undone.'), confirmLabel: t('Close the month'), danger: true, onConfirm: () => act('close', t('Month closed. Employees can now see their payslips.')) })}>{t('Close the month')}</Button>
          )}
        </div>
      </div>

      {view && view.skipped.length > 0 && (
        <div className="card">
          <h3>{t('Not included')}</h3>
          <ul>{view.skipped.map((s) => <li key={s}>{s}</li>)}</ul>
        </div>
      )}

      {view && (
        <>
          <div className="card">
            <h3>{t('Totals')}</h3>
            <div className="table-wrap">
              <table>
                <thead><tr><th>{t('Currency')}</th><th>{t('Payslips')}</th><th>{t('Gross')}</th><th>{t('Employee deductions')}</th><th>{t('Net to pay')}</th><th>{t('Employer charges')}</th></tr></thead>
                <tbody>
                  {view.totals.map((tItem) => (
                    <tr key={tItem.currency}><td>{tItem.currency}</td><td>{tItem.payslips}</td><td>{money(tItem.gross, tItem.currency)}</td><td>{money(tItem.employeeDeductions, tItem.currency)}</td><td><strong>{money(tItem.net, tItem.currency)}</strong></td><td>{money(tItem.employerCharges, tItem.currency)}</td></tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>

          {byDirection.size > 0 && (
            <div className="card">
              <h3>{t('By Direction')}</h3>
              <div className="table-wrap">
                <table>
                  <thead><tr><th>{t('Direction')}</th><th>{t('Payslips')}</th><th>{t('Gross')}</th><th>{t('Employee deductions')}</th><th>{t('Net to pay')}</th><th>{t('Employer charges')}</th></tr></thead>
                  <tbody>
                    {[...byDirection.values()].sort((a, b) => a.name.localeCompare(b.name) || a.currency.localeCompare(b.currency)).map((tItem) => (
                      <tr key={tItem.name + tItem.currency}><td>{tItem.name}</td><td>{tItem.payslips}</td><td>{money(tItem.gross, tItem.currency)}</td><td>{money(tItem.deductions, tItem.currency)}</td><td>{money(tItem.net, tItem.currency)}</td><td>{money(tItem.employer, tItem.currency)}</td></tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <p className="hint">{t('Each payslip counts in the Direction of the unit the employee was in at the end of the month.')}</p>
            </div>
          )}

          <div className="card">
            <h3>{t('Payslips')}</h3>
            {directions.length > 1 && (
              <label className="inline-filter">{t('Direction')}<select value={direction} onChange={(e) => setDirection(e.target.value)}>
                  <option value="">{t('All')}</option>
                  {directions.map(([id, name]) => <option key={id} value={id}>{name}</option>)}
                </select>
              </label>
            )}
            {warnings > 0 && <p className="notice">{warnings}{' '}{t('warning(s) to check before approval: open the payslips marked ⚠.')}</p>}
            <div className="table-wrap">
              <table>
                <thead><tr><th>{t('Employee')}</th><th>{t('Unit')}</th><th>{t('No.')}</th><th>{t('Gross')}</th><th>{t('Deductions')}</th><th>{t('Net')}</th><th>{t('Employer')}</th><th></th></tr></thead>
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
                      <td><Link to={`/payslips/${p.id}`}>{p.warningCount > 0 ? t('⚠ Open') : t('Open')}</Link></td>
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
          <h3>{t('Anniversary raises')}</h3>
          <p className="hint">{t('Applied automatically each morning for permanent staff; the rate and start month are set per grade category under Grades. HR gets one email on the 1st of each month.')}</p>
          {raises.length === 0 ? <p className="muted">{t('No raises applied yet.')}</p> : (
            <div className="table-wrap">
              <table>
                <thead><tr><th>{t('Employee')}</th><th>{t('Anniversary')}</th><th>{t('Raise')}</th><th>{t('Basic')}</th><th>{t('From')}</th><th>{t('HR told')}</th></tr></thead>
                <tbody>
                  {raises.map((r) => (
                    <tr key={r.id}>
                      <td>{r.userName}</td>
                      <td>{r.anniversaryDate} ({r.years}{' '}{t('yr)')}</td>
                      <td>{money(r.raiseAmount, r.currency)} <span className="muted">({r.percent}{t('% of')}{' '}{r.gradeCode})</span></td>
                      <td>{money(r.oldBasic, r.currency)} → <strong>{money(r.newBasic, r.currency)}</strong></td>
                      <td>{r.effectiveFrom}</td>
                      <td>{r.notifiedAt ? t('Yes') : t('Not yet')}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {can('salary.write') && (
            <div className="row">
              <Button className="secondary" disabled={busy} onClick={async () => {
                setError(null); setMessage(null)
                try {
                  const o = await api<{ applied: Raise[]; skipped: string[] }>('/api/raises/apply', { method: 'POST' })
                  setMessage(t('{a} raise(s) applied.{b}', { a: o.applied.length, b: o.skipped.length ? ' To do by hand: ' + o.skipped.join(' ') : '' }))
                  await loadRaises()
                } catch (e) { setError((e as Error).message) }
              }}>{t('Check today\'s anniversaries now')}</Button>
              <Button className="secondary" disabled={busy} onClick={async () => {
                setError(null); setMessage(null)
                try {
                  const r = await api<{ listed: number }>('/api/raises/notify', { method: 'POST' })
                  setMessage(r.listed ? t('Email sent to HR listing {a} raise(s).', { a: r.listed }) : t('No new raise to report.'))
                  await loadRaises()
                } catch (e) { setError((e as Error).message) }
              }}>{t('Email HR the list now')}</Button>
            </div>
          )}
        </div>
      )}
      {can('salary.read') && <ThirteenthMonthCard />}
    </section>
  )
}
