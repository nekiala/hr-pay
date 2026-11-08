import { Fragment, useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { ApiError, api, days, longDay, money, type LeaveAllowance, type LeavePayDue } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'
import { useAsk } from '../components/Dialogs'
import { Button } from '../components/Button'
import { locale, t } from '../i18n'

const STATUS: Record<LeaveAllowance['status'], string> = {
  CALCULATED: t('Calculated, waiting for Finance'), APPROVED: t('Approved, to pay'), PAID: t('Paid'), CANCELLED: t('Cancelled'),
}

const period = (a: { startDate: string; endDate: string }) =>
  a.startDate === a.endDate ? longDay(a.startDate) : t('{a} to {b}', { a: longDay(a.startDate), b: longDay(a.endDate) })

/**
 * Leave pay (allocation de congé) due in one month: HR works it out from New pay run, Finance approves it, and it is
 * paid by the last working day before the leave; the payslip of the leave month then adds the allocation as an
 * earning and takes back the net paid in advance.
 */
export default function LeavePayRunPage() {
  const { month } = useParams()
  const { can } = useAuth()
  const ask = useAsk()
  const [due, setDue] = useState<LeavePayDue[]>([])
  const [all, setAll] = useState<LeaveAllowance[]>([])
  const [open, setOpen] = useState<number | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)

  const load = () => api<{ annualPaid: boolean; due: LeavePayDue[]; allowances: LeaveAllowance[] }>('/api/leave-pay')
    .then((o) => { setDue(o.due); setAll(o.allowances) }).catch((e: Error) => setError(e.message))
  useEffect(() => { void load() }, [])
  const allowances = all.filter((a) => a.payBy.slice(0, 7) === month)
  const title = month ? new Date(month + '-01T00:00:00').toLocaleDateString(locale(), { month: 'long', year: 'numeric' }) : ''

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

    return (
    <section>
      <h2>{t('Leave pay of {a}', { a: title })}</h2>
      <p className="muted small"><Link to={`/payroll?month=${month}`}>{t('Pay runs')}</Link></p>
      <ErrorText error={error} />
      {message && <p className="success">{message}</p>}
      {due.length > 0 && can('payroll.run') && (
        <p className="notice">{t('{a} approved annual leave(s) wait for their leave pay.', { a: due.length })}{' '}
          <Link to="/payroll/new?type=LEAVE_PAY">{t('Work it out')}</Link></p>
      )}

      <div className="card">
        <h3>{t('Leave allowances')}</h3>
        {allowances.length === 0 && <p className="muted">{t('None yet.')}</p>}
        {allowances.length > 0 && (
          <div className="table-wrap">
            <table>
              <thead><tr><th>{t('Employee')}</th><th>{t('Leave')}</th><th>{t('Payslip')}</th><th>{t('Gross')}</th><th>{t('CNSS and income tax')}</th><th>{t('Net to pay now')}</th><th>{t('Pay by')}</th><th>{t('Status')}</th><th></th></tr></thead>
              <tbody>
                {allowances.map((a) => (
                  <Fragment key={a.id}>
                    <tr>
                      <td>{a.employeeName} <span className="muted">{a.registrationNo}</span></td>
                      <td>{period(a)} · {days(a.days)}</td>
                      <td>{a.number ?? <span className="muted">{t('Draft')}</span>}</td>
                      <td>{money(a.gross, a.currency)}</td>
                      <td>{a.employeeDeductions === null ? '—' : money(a.employeeDeductions, a.currency)}</td>
                      <td><strong>{money(a.advance, a.currency)}</strong></td>
                      <td>{longDay(a.payBy)}</td>
                      <td>{STATUS[a.status]}{a.paidOn ? <><br /><span className="muted">{longDay(a.paidOn)}{a.paymentReference ? ` · ${a.paymentReference}` : ''}</span></> : null}
                        {a.cancelNote && <><br /><span className="muted">{a.cancelNote}</span></>}</td>
                      <td className="row">
                        <Button className="secondary" onClick={() => setOpen(open === a.id ? null : a.id)}>{open === a.id ? t('Hide') : t('Details')}</Button>
                        {a.status !== 'CANCELLED' && <a className="button secondary" href={`/api/leave-pay/${a.id}.pdf`} target="_blank" rel="noreferrer">{t('PDF')}</a>}
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
                        <td colSpan={9}>
                          <ul>{a.explanation.map((x) => <li key={x}>{x}</li>)}</ul>
                          <p className="muted">{t('On the payslips:')}{' '}{a.months.map((m) => t('{a}: {c} added as an earning, {b} taken back', {
                            a: m.month.slice(0, 7), b: money(m.advanceAmount, a.currency), c: money(m.grossAmount, a.currency),
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
