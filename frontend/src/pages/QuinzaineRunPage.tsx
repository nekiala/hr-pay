import { useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ApiError, api, longDay, money, type QuinzaineRun } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'
import { useAsk } from '../components/Dialogs'
import { Button } from '../components/Button'
import { PageTitle, SettingsLink } from '../components/SettingsLink'
import { locale, t } from '../i18n'

const STATUS: Record<QuinzaineRun['status'], string> = {
  CALCULATED: t('Calculated, waiting for Finance'), APPROVED: t('Approved, waiting to be closed'), CLOSED: t('Closed, to pay'),
  PAID: t('Paid'), CANCELLED: t('Cancelled'),
}

const monthName = (m: string) => new Date(m.slice(0, 7) + '-01T00:00:00').toLocaleDateString(locale(), { month: 'long', year: 'numeric' })

/**
 * One quinzaine (mid-month advance on pay): HR works it out, Finance approves it, HR closes it, the Payments page pays
 * it, and the month's payslip takes it back in full.
 */
export default function QuinzaineRunPage() {
  const { id } = useParams()
  const navigate = useNavigate()
  const { can } = useAuth()
  const ask = useAsk()
  const [r, setRun] = useState<QuinzaineRun | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)

  const load = async () => {
    const o = await api<{ runs: QuinzaineRun[] }>('/api/quinzaine')
    const found = o.runs.find((x) => String(x.id) === id)
    if (!found) throw new Error(t('Quinzaine not found.'))
    setRun(found)
  }
  useEffect(() => { load().catch((e: Error) => setError(e.message)) }, [id]) // eslint-disable-line react-hooks/exhaustive-deps

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
      <PageTitle title={r ? t('Quinzaine of {a}', { a: monthName(r.month) }) : t('Quinzaine')}><SettingsLink to="/payroll/quinzaine/settings" /></PageTitle>
      <p className="muted small"><Link to={r ? `/payroll?month=${r.month.slice(0, 7)}` : '/payroll'}>{t('Pay runs')}</Link></p>
      <ErrorText error={error} />
      {message && <p className="success">{message}</p>}
      {r && (
        <>
          <div className="card">
            <p><strong>{STATUS[r.status]}</strong> · {t('Paid on {a}', { a: longDay(r.payDate) })} · {r.lines.length}{' '}{t('employees')}
              {r.totals.length > 0 && <> · {r.totals.map((x) => money(x.amount, x.currency)).join(' · ')}</>}</p>
            {r.cancelNote && <p className="muted">{r.cancelNote}</p>}
            <p className="hint">{t('Worked out by {a}', { a: r.calculatedBy })}{r.approvedBy ? ` · ${t('approved by {a}', { a: r.approvedBy })}` : ''}{r.closedBy ? ` · ${t('closed by {a}', { a: r.closedBy })}` : ''}</p>
            <div className="row">
              {r.status === 'CALCULATED' && can('payroll.run') && (
                <Button className="secondary" onClick={() => run(() => api('/api/quinzaine/calculate', { method: 'POST', body: { month: r.month.slice(0, 7) } }),
                  t('Quinzaine worked out. Finance approves it next.'))}>{t('Calculate again')}</Button>
              )}
              {r.status === 'CALCULATED' && can('payroll.approve') && (
                <Button onClick={() => run(() => api(`/api/quinzaine/${r.id}/approve`, { method: 'POST' }), t('Quinzaine approved: the payslips of the month now take it back.'))}>{t('Approve')}</Button>
              )}
              {r.status === 'APPROVED' && can('payroll.close') && (
                <Button onClick={() => ask({
                  title: t('Close this quinzaine?'), confirmLabel: t('Close the quinzaine'),
                  message: t('Receipts get their numbers and the payment files can be made. It cannot be cancelled afterwards.'),
                  onConfirm: () => run(() => api(`/api/quinzaine/${r.id}/close`, { method: 'POST' }), t('Quinzaine closed: make the payment files on the Payments page.')),
                })}>{t('Close')}</Button>
              )}
              {(r.status === 'CLOSED' || r.status === 'PAID') && (
                <Link className="button secondary" to={`/payroll/payments?month=${r.month.slice(0, 7)}&kind=QUINZAINE`}>{t('Payments')}</Link>
              )}
              {(r.status === 'CALCULATED' || r.status === 'APPROVED') && (can('payroll.run') || can('payroll.approve')) && (
                <Button className="secondary" onClick={() => ask({
                  title: t('Cancel this quinzaine?'), confirmLabel: t('Cancel the quinzaine'), danger: true,
                  message: t('The payslips of the month no longer take it back. It can be worked out again afterwards.'),
                  fields: [{ label: t('Reason (optional)') }],
                  onConfirm: ([note]) => run(async () => { await api(`/api/quinzaine/${r.id}/cancel`, { method: 'POST', body: { note: note || null } }); navigate('/payroll') }, t('Quinzaine cancelled.')),
                })}>{t('Cancel')}</Button>
              )}
            </div>
          </div>

          {r.skipped.length > 0 && (
            <div className="card">
              <h3>{t('Left out')}</h3>
              <ul className="warnings">{r.skipped.map((s) => <li key={s}>{s}</li>)}</ul>
            </div>
          )}

          <div className="card">
            <h3>{t('Employees')}</h3>
            <div className="table-wrap">
              <table>
                <thead><tr><th>{t('Employee')}</th><th>{t('Category')}</th><th>{r.base === 'BASIC' ? t('Basic salary') : t('Net pay of a normal month')}</th><th>{t('Paid time to the pay day')}</th><th>{t('Earned on the pay day')}</th><th>{t('Quinzaine')}</th><th></th></tr></thead>
                <tbody>
                  {r.lines.map((l) => (
                    <tr key={l.id} title={l.explanation.join('\n')}>
                      <td>{l.employeeName} <span className="muted">{l.registrationNo}</span></td>
                      <td>{l.category} · {Number(l.percent)}%</td>
                      <td>{money(l.baseAmount, l.currency)}</td>
                      <td>{Math.round(Number(l.workedShare) * 100)}%</td>
                      <td>{money(l.earnedNet, l.currency)}</td>
                      <td><strong>{money(l.amount, l.currency)}</strong>{l.paid && <span className="muted">{' · '}{t('Paid')}</span>}</td>
                      <td>
                        <a href={`/api/quinzaine/receipts/${l.id}.pdf`} target="_blank" rel="noreferrer">{l.number ?? t('Receipt')}</a>
                        {r.status === 'CALCULATED' && can('payroll.run') && (
                          <>{' '}<Button className="secondary" onClick={() => ask({
                            title: t('Leave {a} out of this quinzaine?', { a: l.employeeName }), confirmLabel: t('Leave out'),
                            fields: [{ label: t('Reason'), required: true }],
                            onConfirm: ([reason]) => run(async () => {
                              await api('/api/quinzaine/exclusions', { method: 'POST', body: { month: r.month.slice(0, 7), userId: l.userId, reason } })
                              await api('/api/quinzaine/calculate', { method: 'POST', body: { month: r.month.slice(0, 7) } })
                            }, t('{a} left out of this quinzaine.', { a: l.employeeName })),
                          })}>{t('Leave out')}</Button></>
                        )}
                      </td>
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
