import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { ApiError, api, money, type ThirteenthRun } from '../api'
import { useAuth } from '../auth'
import { Button } from '../components/Button'
import { useAsk } from '../components/Dialogs'
import { ErrorText } from '../components/ErrorText'
import { PageTitle, SettingsLink } from '../components/SettingsLink'
import { locale, t } from '../i18n'

const STATUS: Record<ThirteenthRun['status'], string> = {
  CALCULATED: t('Calculated, waiting for Finance'), APPROVED: t('Approved, paid with the payroll of {a}'), PAID: t('Paid with the payroll of {a}'),
  CANCELLED: t('Cancelled'),
}
const monthName = (m: string) => new Date(m.slice(0, 7) + '-01T00:00:00').toLocaleDateString(locale(), { month: 'long', year: 'numeric' })

/**
 * One 13th month run: HR works it out for a year, Finance approves it, and the month-end payroll of its month puts
 * each amount on the payslip, with CNSS and income tax. Closing that payroll marks it paid.
 */
export default function ThirteenthRunPage() {
  const { id } = useParams()
  const { can } = useAuth()
  const ask = useAsk()
  const [r, setRun] = useState<ThirteenthRun | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const load = () => api<ThirteenthRun>(`/api/thirteenth-runs/${id}`).then(setRun).catch((e: Error) => setError(e.message))
  useEffect(() => { void load() }, [id]) // eslint-disable-line react-hooks/exhaustive-deps

  const act = async (action: () => Promise<ThirteenthRun>, done: string) => {
    setError(null)
    setMessage(null)
    try { setRun(await action()); setMessage(done) } catch (e) {
      setError(e instanceof ApiError ? [e.message, ...Object.values(e.fieldErrors)].join(' ') : t('Something went wrong.'))
    }
  }

  const month = r?.month.slice(0, 7) ?? ''
  return (
    <section>
      <PageTitle title={r ? t('13th month {a}', { a: r.year }) : t('13th month')}><SettingsLink to="/payroll/thirteenth-month/settings" /></PageTitle>
      <p className="muted small"><Link to={month ? `/payroll?month=${month}` : '/payroll'}>{t('Pay runs')}</Link></p>
      <ErrorText error={error} />
      {message && <p className="success">{message}</p>}
      {r && (
        <>
          <div className="card">
            <p><strong>{STATUS[r.status].replace('{a}', monthName(r.month))}</strong> · {r.lines.length}{' '}{t('employees')}
              {r.totals.length > 0 && <> · {r.totals.map((x) => money(x.amount, x.currency)).join(' · ')}</>}</p>
            <p className="hint">{t('Base: {a}; fallback score {b}%{c}.', {
              a: r.base === 'LAST_BASIC' ? t('last basic of the year') : t('average basic over the year'), b: r.fallbackPercent,
              c: r.prorate ? t(', prorated by months worked') : '',
            })}{' '}{t('Worked out by {a}', { a: r.calculatedBy })}{r.approvedBy ? ` · ${t('approved by {a}', { a: r.approvedBy })}` : ''}</p>
            {r.cancelNote && <p className="muted">{r.cancelNote}</p>}
            {r.status === 'APPROVED' && <p className="hint">{t('Calculate the payroll of {a} (again if it was already calculated) to put it on the payslips.', { a: monthName(r.month) })}{' '}
              <Link to={`/payroll/runs/monthly/${month}`}>{t('Open the payroll')}</Link></p>}
            <div className="row">
              {r.status === 'CALCULATED' && can('payroll.run') && (
                <Button className="secondary" onClick={() => act(() => api<ThirteenthRun>('/api/thirteenth-runs/calculate', { method: 'POST', body: { year: r.year, month } }),
                  t('13th month worked out again.'))}>{t('Calculate again')}</Button>
              )}
              {r.status === 'CALCULATED' && can('payroll.approve') && (
                <Button onClick={() => ask({
                  title: t('Approve the 13th month {a}?', { a: r.year }), confirmLabel: t('Approve'),
                  message: t('The payroll of {a} then puts it on each payslip.', { a: monthName(r.month) }),
                  onConfirm: () => act(() => api<ThirteenthRun>(`/api/thirteenth-runs/${r.id}/approve`, { method: 'POST' }), t('13th month approved.')),
                })}>{t('Approve')}</Button>
              )}
              {(r.status === 'CALCULATED' || r.status === 'APPROVED') && (can('payroll.run') || can('payroll.approve')) && (
                <Button className="secondary" onClick={() => ask({
                  title: t('Cancel this 13th month?'), confirmLabel: t('Cancel the 13th month'), danger: true,
                  message: t('It can be worked out again afterwards. Calculate the payroll of its month again if it was.'),
                  fields: [{ label: t('Reason (optional)') }],
                  onConfirm: ([note]) => act(() => api<ThirteenthRun>(`/api/thirteenth-runs/${r.id}/cancel`, { method: 'POST', body: { note: note || null } }), t('13th month cancelled.')),
                })}>{t('Cancel')}</Button>
              )}
            </div>
          </div>

          {(r.skipped.length > 0 || r.excluded.length > 0) && (
            <div className="card">
              <h3>{t('Left out')}</h3>
              <ul className="warnings">{r.skipped.map((s) => <li key={s}>{s}</li>)}</ul>
              {r.status === 'CALCULATED' && can('payroll.run') && r.excluded.length > 0 && (
                <ul>{r.excluded.map((x) => (
                  <li key={x.userId}>{x.name}{' '}<Button className="secondary" onClick={() => act(async () => {
                    await api(`/api/thirteenth-runs/${r.id}/exclusions/${x.userId}`, { method: 'DELETE' })
                    return api<ThirteenthRun>('/api/thirteenth-runs/calculate', { method: 'POST', body: { year: r.year, month } })
                  }, t('{a} is back in the 13th month.', { a: x.name }))}>{t('Put back')}</Button></li>
                ))}</ul>
              )}
            </div>
          )}

          <div className="card">
            <h3>{t('Employees')}</h3>
            {r.lines.length === 0 ? <p className="muted">{t('Nobody to pay.')}</p> : (
              <div className="table-wrap">
                <table>
                  <thead><tr><th>{t('Employee')}</th><th>{t('Score')}</th><th className="num">{t('Base')}</th><th>{t('Months')}</th><th className="num">{t('13th month')}</th><th /></tr></thead>
                  <tbody>
                    {r.lines.map((l) => (
                      <tr key={l.id}>
                        <td>{l.employeeName} <span className="muted">{l.registrationNo}</span></td>
                        <td>{Number(l.scorePercent)}% <span className="muted small">{l.scoreSource === 'EVALUATIONS' ? t('{n} evaluation(s)', { n: l.evaluations }) : t('fallback')}</span></td>
                        <td className="num">{money(l.base, l.currency)}</td>
                        <td>{l.monthsWorked}/12</td>
                        <td className="num"><strong>{money(l.amount, l.currency)}</strong></td>
                        <td>{r.status === 'CALCULATED' && can('payroll.run') && (
                          <Button className="secondary" onClick={() => ask({
                            title: t('Leave {a} out of this 13th month?', { a: l.employeeName }), confirmLabel: t('Leave out'),
                            fields: [{ label: t('Reason'), required: true }],
                            onConfirm: ([reason]) => act(() => api<ThirteenthRun>(`/api/thirteenth-runs/${r.id}/exclusions`, { method: 'POST', body: { userId: l.userId, reason } }),
                              t('{a} left out of this 13th month.', { a: l.employeeName })),
                          })}>{t('Leave out')}</Button>
                        )}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </>
      )}
    </section>
  )
}
