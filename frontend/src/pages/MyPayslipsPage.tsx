import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { ApiError, api, longDay, money, type LeaveAllowance, type MyQuinzaine, type PayslipSummary } from '../api'
import { Button } from '../components/Button'
import { useAsk } from '../components/Dialogs'
import { ErrorText } from '../components/ErrorText'
import { t } from '../i18n'

export default function MyPayslipsPage() {
  const [slips, setSlips] = useState<PayslipSummary[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [leave, setLeave] = useState<LeaveAllowance[]>([])
  const [quinzaine, setQuinzaine] = useState<MyQuinzaine | null>(null)
  const ask = useAsk()
  const setRefused = (refused: boolean, reason?: string) =>
    api<MyQuinzaine>('/api/quinzaine/mine', { method: 'PUT', body: { refused, reason: reason || null } }).then(setQuinzaine)
      .catch((e) => setError(e instanceof ApiError ? e.message : t('Something went wrong.')))
  useEffect(() => {
    api<MyQuinzaine>('/api/quinzaine/mine').then(setQuinzaine).catch(() => undefined)
    api<PayslipSummary[]>('/api/my/payslips').then(setSlips).catch((e: Error) => setError(e.message))
    api<LeaveAllowance[]>('/api/leave-pay/mine').then((l) => setLeave(l.filter((a) => a.number))).catch(() => undefined)
  }, [])

  return (
    <section>
      <h2>{t('My payslips')}</h2>
      <p><Link to="/my/year">{t('Annual pay statement')}</Link></p>
      <ErrorText error={error} />
      {slips?.length === 0 && <p className="muted">{t('No payslips yet. They appear here once HR closes the month.')}</p>}
      {slips && slips.length > 0 && (
        <div className="card">
          <table>
            <thead><tr><th>{t('Payslip')}</th><th>{t('Gross')}</th><th>{t('Net')}</th><th></th></tr></thead>
            <tbody>
              {slips.map((s) => (
                <tr key={s.id}>
                  <td>{s.number}</td><td>{money(s.gross, s.currency)}</td><td><strong>{money(s.net, s.currency)}</strong></td>
                  <td><Link to={`/payslips/${s.id}`}>{t('Open')}</Link></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {quinzaine && (quinzaine.enabled || quinzaine.payments.length > 0) && (
        <div className="card">
          <h3>{t('Quinzaine')}</h3>
          <p>{quinzaine.refused
            ? t('You asked not to receive the quinzaine: your pay comes once, at the end of the month.')
            : t('Part of your pay is advanced around day {a} of the month (quinzaine); your payslip takes it back from the net at the end of the month.', { a: quinzaine.day })}</p>
          {quinzaine.enabled && (quinzaine.refused
            ? <Button className="secondary" onClick={() => void setRefused(false)}>{t('Receive the quinzaine again')}</Button>
            : <Button className="secondary" onClick={() => ask({
              title: t('Ask not to receive the quinzaine?'), confirmLabel: t('Send the request'),
              message: t('It applies from the next quinzaine not yet approved: you will then be paid once, at the end of the month. A quinzaine already approved or paid stays.'),
              fields: [{ label: t('Reason (optional)') }],
              onConfirm: ([reason]) => setRefused(true, reason),
            })}>{t('Ask not to receive the quinzaine')}</Button>)}
          {quinzaine.payments.length > 0 && (
            <table>
              <thead><tr><th>{t('Month')}</th><th>{t('Pay date')}</th><th>{t('Amount')}</th><th></th></tr></thead>
              <tbody>
                {quinzaine.payments.map((q) => (
                  <tr key={q.id}><td>{q.month.slice(0, 7)}</td><td>{longDay(q.payDate)}</td><td>{money(q.amount, q.currency)}</td>
                    <td>{q.number && <a href={`/api/quinzaine/receipts/${q.id}.pdf`} target="_blank" rel="noreferrer">{q.number}</a>}</td></tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      )}
      {leave.length > 0 && (
        <div className="card">
          <h3>{t('Leave allowance payslips')}</h3>
          <table>
            <thead><tr><th>{t('Payslip')}</th><th>{t('Leave')}</th><th>{t('Gross')}</th><th>{t('Net')}</th><th></th></tr></thead>
            <tbody>
              {leave.map((a) => (
                <tr key={a.id}>
                  <td>{a.number}</td><td>{t('{a} to {b}', { a: longDay(a.startDate), b: longDay(a.endDate) })}</td>
                  <td>{money(a.gross, a.currency)}</td><td><strong>{money(a.net ?? a.advance, a.currency)}</strong></td>
                  <td><a href={`/api/leave-pay/${a.id}.pdf`} target="_blank" rel="noreferrer">{t('Download PDF')}</a></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}
