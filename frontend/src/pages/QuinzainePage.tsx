import { Fragment, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import {
  ApiError, api, longDay, money, type QuinzaineEmployee, type QuinzaineExclusion, type QuinzaineRun,
  type QuinzaineSettings,
} from '../api'
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

const thisMonth = () => new Date().toISOString().slice(0, 7)
const monthName = (m: string) => new Date(m.slice(0, 7) + '-01T00:00:00').toLocaleDateString(locale(), { month: 'long', year: 'numeric' })

/**
 * The quinzaine: an optional advance on the month's pay, paid around the 15th. The company turns it on and sets a rate
 * per grade category; HR works it out, Finance approves it, HR closes it, the Payments page pays it, and the month's
 * payslip takes it back in full.
 */
export default function QuinzainePage() {
  const { can } = useAuth()
  const ask = useAsk()
  const editable = can('masterdata.manage')
  const [settings, setSettings] = useState<QuinzaineSettings | null>(null)
  const [runs, setRuns] = useState<QuinzaineRun[]>([])
  const [people, setPeople] = useState<QuinzaineEmployee[]>([])
  const [exclusions, setExclusions] = useState<QuinzaineExclusion[]>([])
  const [filter, setFilter] = useState('')
  const [month, setMonth] = useState(thisMonth())
  const [payDate, setPayDate] = useState<string | null>(null)
  const [open, setOpen] = useState<number | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)

  const load = async () => {
    const o = await api<{ settings: QuinzaineSettings; runs: QuinzaineRun[] }>('/api/quinzaine')
    setSettings(o.settings)
    setRuns(o.runs)
    setPeople(await api<QuinzaineEmployee[]>('/api/quinzaine/employees'))
  }
  useEffect(() => { load().catch((e: Error) => setError(e.message)) }, [])
  useEffect(() => {
    api<string>(`/api/quinzaine/pay-date?month=${month}`).then(setPayDate).catch(() => setPayDate(null))
    api<QuinzaineExclusion[]>(`/api/quinzaine/exclusions?month=${month}`).then(setExclusions).catch(() => setExclusions([]))
  }, [month])

  const run = async (action: () => Promise<unknown>, done: string) => {
    setError(null)
    setMessage(null)
    try {
      await action()
      setMessage(done)
      await load()
      setExclusions(await api<QuinzaineExclusion[]>(`/api/quinzaine/exclusions?month=${month}`))
    } catch (e) {
      setError(e instanceof ApiError ? [e.message, ...Object.values(e.fieldErrors)].join(' ') : t('Something went wrong.'))
    }
  }

  const calculate = () => run(async () => {
    const r = await api<QuinzaineRun>('/api/quinzaine/calculate', { method: 'POST', body: { month } })
    setOpen(r.id)
  }, t('Quinzaine worked out. Finance approves it next.'))

  const shown = people.filter((p) => !filter || `${p.employeeName} ${p.registrationNo}`.toLowerCase().includes(filter.toLowerCase()))

  return (
    <section>
      <PageTitle title={t('Quinzaine')}><SettingsLink to="/payroll/quinzaine/settings" /></PageTitle>
      <p className="muted">{t('An advance on the month\'s pay, taken back in full from the month\'s payslip.')}</p>
      <ErrorText error={error} />
      {message && <p className="success">{message}</p>}

      {settings && !settings.enabled && (
        <p className="notice">{t('The company does not pay a quinzaine.')}{' '}
          <Link to="/payroll/quinzaine/settings">{editable ? t('Turn it on in the settings') : t('Settings')}</Link></p>
      )}

      {settings?.enabled && can('payroll.run') && (
        <div className="card">
          <h3>{t('Work out a quinzaine')}</h3>
          <div className="row grant">
            <label>{t('Month')}<input type="month" value={month} onChange={(e) => e.target.value && setMonth(e.target.value)} /></label>
            {payDate && <span>{t('Paid on {a}', { a: longDay(payDate) })}</span>}
            <Button onClick={calculate}>{t('Calculate the quinzaine')}</Button>
          </div>
          <p className="hint">{t('For everyone under contract on the pay day, never above the net already earned. Calculating again replaces it until Finance approves.')}</p>
          {exclusions.length > 0 && (
            <ul>{exclusions.map((x) => (
              <li key={x.id}>{t('{a} left out: {b}', { a: x.employeeName, b: x.reason })}{' '}
                <Button className="secondary" onClick={() => run(() => api(`/api/quinzaine/exclusions?month=${month}&userId=${x.userId}`, { method: 'DELETE' }),
                  t('{a} is back in the quinzaine.', { a: x.employeeName }))}>{t('Put back')}</Button></li>
            ))}</ul>
          )}
        </div>
      )}

      <div className="card">
        <h3>{t('Quinzaines')}</h3>
        {runs.length === 0 && <p className="muted">{t('None yet.')}</p>}
        {runs.length > 0 && (
          <div className="table-wrap">
            <table>
              <thead><tr><th>{t('Month')}</th><th>{t('Pay date')}</th><th>{t('Employees')}</th><th>{t('Total')}</th><th>{t('Status')}</th><th></th></tr></thead>
              <tbody>
                {runs.map((r) => (
                  <Fragment key={r.id}>
                    <tr>
                      <td>{monthName(r.month)}</td>
                      <td>{longDay(r.payDate)}</td>
                      <td>{r.lines.length}{r.skipped.length > 0 ? <span className="muted">{' '}{t('({a} left out)', { a: r.skipped.length })}</span> : null}</td>
                      <td>{r.totals.map((x) => money(x.amount, x.currency)).join(' · ') || '—'}</td>
                      <td>{STATUS[r.status]}{r.cancelNote && <><br /><span className="muted">{r.cancelNote}</span></>}</td>
                      <td className="row">
                        <Button className="secondary" onClick={() => setOpen(open === r.id ? null : r.id)}>{open === r.id ? t('Hide') : t('Details')}</Button>
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
                            onConfirm: ([note]) => run(() => api(`/api/quinzaine/${r.id}/cancel`, { method: 'POST', body: { note: note || null } }), t('Quinzaine cancelled.')),
                          })}>{t('Cancel')}</Button>
                        )}
                      </td>
                    </tr>
                    {open === r.id && (
                      <tr>
                        <td colSpan={6}>
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
                                          onConfirm: ([reason]) => run(() => api('/api/quinzaine/exclusions', { method: 'POST', body: { month: r.month.slice(0, 7), userId: l.userId, reason } }),
                                            t('{a} left out of this quinzaine.', { a: l.employeeName })),
                                        })}>{t('Leave out')}</Button></>
                                      )}
                                    </td>
                                  </tr>
                                ))}
                              </tbody>
                            </table>
                          </div>
                          {r.skipped.length > 0 && (
                            <>
                              <h4>{t('Left out')}</h4>
                              <ul className="warnings">{r.skipped.map((s) => <li key={s}>{s}</li>)}</ul>
                            </>
                          )}
                          <p className="muted">{t('Worked out by {a}', { a: r.calculatedBy })}{r.approvedBy ? ` · ${t('approved by {a}', { a: r.approvedBy })}` : ''}{r.closedBy ? ` · ${t('closed by {a}', { a: r.closedBy })}` : ''}</p>
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

      <div className="card">
        <div className="row space">
          <h3>{t('Requests not to receive it')}</h3>
          <input type="search" placeholder={t('Search')} value={filter} onChange={(e) => setFilter(e.target.value)} />
        </div>
        <p className="muted">{t('{n} employees asked not to receive it, from their payslips page.', { n: people.length })}</p>
        {people.length === 0 ? <p className="muted">{t('Everyone receives it.')}</p> : (
          <div className="table-wrap">
            <table>
              <thead><tr><th>{t('Employee')}</th><th>{t('Category')}</th><th>{t('Reason')}</th><th>{t('Asked on')}</th></tr></thead>
              <tbody>
                {shown.map((p) => (
                  <tr key={p.userId}>
                    <td>{p.employeeName} <span className="muted">{p.registrationNo}</span></td>
                    <td>{p.category ?? '—'}{p.category == null ? '' : p.percent != null ? ` · ${Number(p.percent)}%` : ` · ${t('no rate')}`}</td>
                    <td>{p.reason ?? '—'}</td>
                    <td>{p.recordedAt ? longDay(p.recordedAt) : ''}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </section>
  )
}
