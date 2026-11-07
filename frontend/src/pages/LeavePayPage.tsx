import { Fragment, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { ApiError, api, days, longDay, money, type LeaveAllowance, type LeavePayDue } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'
import { useAsk } from '../components/Dialogs'
import { Button } from '../components/Button'
import { t } from '../i18n'

const STATUS: Record<LeaveAllowance['status'], string> = {
  CALCULATED: t('Calculated, waiting for Finance'), APPROVED: t('Approved, to pay'), PAID: t('Paid'), CANCELLED: t('Cancelled'),
}

const period = (a: { startDate: string; endDate: string }) =>
  a.startDate === a.endDate ? longDay(a.startDate) : t('{a} to {b}', { a: longDay(a.startDate), b: longDay(a.endDate) })

/**
 * The leave pay run: the allowance (allocation de congé) of approved annual leave. HR works it out on request,
 * Finance approves it, and it is paid by the last working day before the leave; the payslip of the leave month
 * then takes the advance back and adds the average of bonuses and overtime.
 */
export default function LeavePayPage() {
  const { can, me } = useAuth()
  const ask = useAsk()
  const [paid, setPaid] = useState(true)
  const [due, setDue] = useState<LeavePayDue[]>([])
  const [allowances, setAllowances] = useState<LeaveAllowance[]>([])
  const [chosen, setChosen] = useState<number[]>([])
  const [open, setOpen] = useState<number | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const [skipped, setSkipped] = useState<string[]>([])

  const load = () => api<{ annualPaid: boolean; due: LeavePayDue[]; allowances: LeaveAllowance[] }>('/api/leave-pay')
    .then((o) => { setPaid(o.annualPaid); setDue(o.due); setAllowances(o.allowances) }).catch((e: Error) => setError(e.message))
  useEffect(() => { void load() }, [])

  const run = async (action: () => Promise<unknown>, done: string) => {
    setError(null)
    setMessage(null)
    try {
      await action()
      setMessage(done)
      await load()
    } catch (e) {
      setError(e instanceof ApiError ? [e.message, ...Object.values(e.fieldErrors)].join(' ') : t('Something went wrong.'))
    }
  }

  const calculate = () => run(async () => {
    const r = await api<{ calculated: LeaveAllowance[]; skipped: string[] }>('/api/leave-pay/calculate', {
      method: 'POST', body: { leaveRequestIds: chosen },
    })
    setChosen([])
    setSkipped(r.skipped)
  }, t('Leave pay worked out. Finance approves it next.'))

  const late = (d: string) => d < new Date().toISOString().slice(0, 10)

  return (
    <section>
      <h2>{t('Leave pay')}</h2>
      <p className="muted">{t('The leave allowance (allocation de congé) of approved annual leave: the salary for the leave days plus the average of bonuses, commissions and overtime of the 12 months before. The law asks for it to be paid by the last working day before the leave (DRC Labour Code art. 142 and 145). The payslip of the leave month takes back what was paid in advance.')}</p>
      <ErrorText error={error} />
      {message && <p className="success">{message}</p>}
      {skipped.length > 0 && <ul className="warnings">{skipped.map((s) => <li key={s}>{s}</li>)}</ul>}
      {!paid && (
        <p className="notice">{t('Annual leave is not paid in this company.')}{' '}<Link to="/leave/settings">{t('Leave settings')}</Link></p>
      )}

      {paid && (
        <div className="card">
          <div className="row space">
            <h3>{t('Approved annual leave waiting for its pay')}</h3>
            {can('payroll.run') && due.length > 0 && (
              <Button onClick={calculate}>{chosen.length === 0 ? t('Work out leave pay for all') : t('Work out leave pay for {a}', { a: chosen.length })}</Button>
            )}
          </div>
          {due.length === 0 && <p className="muted">{t('Nothing waiting.')}</p>}
          {due.length > 0 && (
            <div className="table-wrap">
              <table>
                <thead><tr>{can('payroll.run') && <th></th>}<th>{t('Employee')}</th><th>{t('Leave')}</th><th>{t('Days')}</th><th>{t('Pay by')}</th></tr></thead>
                <tbody>
                  {due.map((d) => (
                    <tr key={d.leaveRequestId}>
                      {can('payroll.run') && <td><input type="checkbox" checked={chosen.includes(d.leaveRequestId)} disabled={d.userId === me?.id}
                        onChange={(e) => setChosen(e.target.checked ? [...chosen, d.leaveRequestId] : chosen.filter((x) => x !== d.leaveRequestId))} /></td>}
                      <td>{d.employeeName} <span className="muted">{d.registrationNo}</span></td>
                      <td>{period(d)}</td>
                      <td>{days(d.workingDays)}</td>
                      <td className={late(d.payBy) ? 'danger-text' : undefined}>{longDay(d.payBy)}{late(d.payBy) ? ` · ${t('late')}` : ''}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}

      <div className="card">
        <h3>{t('Leave allowances')}</h3>
        {allowances.length === 0 && <p className="muted">{t('None yet.')}</p>}
        {allowances.length > 0 && (
          <div className="table-wrap">
            <table>
              <thead><tr><th>{t('Employee')}</th><th>{t('Leave')}</th><th>{t('Gross')}</th><th>{t('To pay now')}</th><th>{t('Pay by')}</th><th>{t('Status')}</th><th></th></tr></thead>
              <tbody>
                {allowances.map((a) => (
                  <Fragment key={a.id}>
                    <tr>
                      <td>{a.employeeName} <span className="muted">{a.registrationNo}</span></td>
                      <td>{period(a)} · {days(a.days)}</td>
                      <td>{money(a.gross, a.currency)}</td>
                      <td><strong>{money(a.advance, a.currency)}</strong></td>
                      <td>{longDay(a.payBy)}</td>
                      <td>{STATUS[a.status]}{a.paidOn ? <><br /><span className="muted">{longDay(a.paidOn)}{a.paymentReference ? ` · ${a.paymentReference}` : ''}</span></> : null}
                        {a.cancelNote && <><br /><span className="muted">{a.cancelNote}</span></>}</td>
                      <td className="row">
                        <Button className="secondary" onClick={() => setOpen(open === a.id ? null : a.id)}>{open === a.id ? t('Hide') : t('Details')}</Button>
                        {a.status === 'CALCULATED' && can('payroll.approve') && (
                          <Button onClick={() => run(() => api(`/api/leave-pay/${a.id}/approve`, { method: 'POST' }), t('Leave pay approved: it is now on the payslips.'))}>{t('Approve')}</Button>
                        )}
                        {a.status === 'APPROVED' && (can('payroll.pay') || can('payroll.approve')) && (
                          <Button onClick={() => ask({
                            title: t('Record the payment'), confirmLabel: t('Record as paid'),
                            message: t('{a} paid to {b}.', { a: money(a.advance, a.currency), b: a.employeeName }),
                            fields: [{ label: t('Paid on'), type: 'date', required: true, defaultValue: new Date().toISOString().slice(0, 10) },
                              { label: t('Reference (optional)') }],
                            onConfirm: ([on, ref]) => run(() => api(`/api/leave-pay/${a.id}/paid`, { method: 'POST', body: { paidOn: on, reference: ref || null } }), t('Payment recorded.')),
                          })}>{t('Record as paid')}</Button>
                        )}
                        {(a.status === 'CALCULATED' || a.status === 'APPROVED') && (can('payroll.run') || can('payroll.approve')) && (
                          <Button className="secondary" onClick={() => ask({
                            title: t('Cancel this leave pay?'), confirmLabel: t('Cancel the leave pay'), danger: true,
                            message: t('It can be worked out again afterwards.'),
                            fields: [{ label: t('Reason (optional)') }],
                            onConfirm: ([note]) => run(() => api(`/api/leave-pay/${a.id}/cancel`, { method: 'POST', body: { note: note || null } }), t('Leave pay cancelled.')),
                          })}>{t('Cancel')}</Button>
                        )}
                      </td>
                    </tr>
                    {open === a.id && (
                      <tr>
                        <td colSpan={7}>
                          <ul>{a.explanation.map((x) => <li key={x}>{x}</li>)}</ul>
                          <p className="muted">{t('On the payslips:')}{' '}{a.months.map((m) => t('{a}: {b} taken back, {c} added', {
                            a: m.month.slice(0, 7), b: money(m.advanceAmount, a.currency), c: money(m.variableAmount, a.currency),
                          })).join(' · ')}</p>
                          <p className="muted">{t('Worked out by {a}', { a: a.calculatedBy })}{a.approvedBy ? ` · ${t('approved by {a}', { a: a.approvedBy })}` : ''}{a.paidBy ? ` · ${t('paid, recorded by {a}', { a: a.paidBy })}` : ''}</p>
                        </td>
                      </tr>
                    )}
                  </Fragment>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </section>
  )
}
