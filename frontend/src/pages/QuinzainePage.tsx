import { Fragment, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import {
  ApiError, api, longDay, money, type QuinzaineCategory, type QuinzaineEmployee, type QuinzaineExclusion, type QuinzaineRun,
  type QuinzaineSettings,
} from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'
import { useAsk } from '../components/Dialogs'
import { Button } from '../components/Button'
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
  const [form, setForm] = useState<QuinzaineSettings>({ enabled: false, base: 'NET', day: 15, maxDebtMonths: 3 })
  const [categories, setCategories] = useState<QuinzaineCategory[]>([])
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
    const o = await api<{ settings: QuinzaineSettings; categories: QuinzaineCategory[]; runs: QuinzaineRun[] }>('/api/quinzaine')
    setSettings(o.settings)
    setForm({ ...o.settings, maxDebtMonths: Number(o.settings.maxDebtMonths) })
    setCategories(o.categories)
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

  const save = () => run(async () => {
    const put = (key: string, value: string) => api(`/api/statutory/settings/${key}`, { method: 'PUT', body: { value } })
    await put('QUINZAINE_BASE', form.base)
    await put('QUINZAINE_DAY', String(form.day))
    await put('QUINZAINE_MAX_DEBT_MONTHS', String(form.maxDebtMonths))
    await put('QUINZAINE_ENABLED', String(form.enabled))
  }, form.enabled ? t('Quinzaine turned on.') : t('Quinzaine settings saved.'))

  const setRate = (c: QuinzaineCategory, value: string) => setCategories(categories.map((x) => (x.id === c.id
    ? { ...x, quinzainePercent: value === '' ? null : Number(value) } : x)))

  const calculate = () => run(async () => {
    const r = await api<QuinzaineRun>('/api/quinzaine/calculate', { method: 'POST', body: { month } })
    setOpen(r.id)
  }, t('Quinzaine worked out. Finance approves it next.'))

  const shown = people.filter((p) => !filter || `${p.employeeName} ${p.registrationNo}`.toLowerCase().includes(filter.toLowerCase()))
  const requests = people.filter((p) => p.refused).length

  return (
    <section>
      <h2>{t('Quinzaine')}</h2>
      <p className="muted">{t('An advance on the month\'s pay (acompte de quinzaine), paid around the middle of the month. It is worked out like the month-end payroll, paid through the payment files, and the payslip of the month takes it back in full from the net pay.')}</p>
      <ErrorText error={error} />
      {message && <p className="success">{message}</p>}

      {settings && (
        <div className="card">
          <h3>{t('Company setting')}</h3>
          <label className="check"><input type="checkbox" checked={form.enabled} disabled={!editable}
            onChange={(e) => setForm({ ...form, enabled: e.target.checked })} />{t('The company pays a quinzaine')}</label>
          <div className="row grant">
            <label>{t('Rate applies to')}<select className="auto-width" value={form.base} disabled={!editable}
              onChange={(e) => setForm({ ...form, base: e.target.value as QuinzaineSettings['base'] })}>
              <option value="NET">{t('the net pay of a normal month')}</option>
              <option value="BASIC">{t('the basic salary')}</option>
            </select></label>
            <label>{t('Paid on day')}<input type="number" min={1} max={28} className="narrow-input" value={form.day}
              disabled={!editable} onChange={(e) => setForm({ ...form, day: Number(e.target.value) })} /></label>
            <label>{t('No quinzaine above a debt of (months of net pay)')}<input type="number" min={0} max={99} step={0.5} className="narrow-input"
              value={form.maxDebtMonths} disabled={!editable} onChange={(e) => setForm({ ...form, maxDebtMonths: Number(e.target.value) })} /></label>
            {editable && <Button className="secondary" onClick={save}>{t('Save')}</Button>}
          </div>
          <p className="hint">{t('When the day is a weekend or a public holiday, it is paid on the working day before. DRC (Labour Code art. 99): pay at least once a month, within six days after it; advances may be taken back on the pay (art. 112). Republic of the Congo (art. 88 and 89): pay within eight days after the month; where day or hour workers are paid monthly, the acompte each fortnight must be at least a third of the previous month\'s pay. To confirm with your payroll expert.')}</p>

          <h3>{t('Rate by grade category')}</h3>
          <div className="table-wrap">
            <table>
              <thead><tr><th>{t('Category')}</th><th>{t('Rate (%)')}</th><th></th></tr></thead>
              <tbody>
                {categories.map((c) => (
                  <tr key={c.id}>
                    <td>{c.name} <span className="muted">{c.code}</span></td>
                    <td><input type="number" min={10} max={50} step={0.5} className="narrow-input" placeholder={t('none')} disabled={!editable}
                      value={c.quinzainePercent ?? ''} onChange={(e) => setRate(c, e.target.value)} /></td>
                    <td>{editable && <Button className="secondary" onClick={() => run(() => api(`/api/quinzaine/categories/${c.id}`,
                      { method: 'PUT', body: { percent: c.quinzainePercent } }), t('{a} saved.', { a: c.code }))}>{t('Save')}</Button>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <p className="hint">{t('From 10% to 50%. Leave it empty for a category that does not get the quinzaine.')}</p>
        </div>
      )}

      {settings?.enabled && can('payroll.run') && (
        <div className="card">
          <h3>{t('Work out a quinzaine')}</h3>
          <div className="row grant">
            <label>{t('Month')}<input type="month" value={month} onChange={(e) => e.target.value && setMonth(e.target.value)} /></label>
            {payDate && <span>{t('Paid on {a}', { a: longDay(payDate) })}</span>}
            <Button onClick={calculate}>{t('Calculate the quinzaine')}</Button>
          </div>
          <p className="hint">{t('For everyone under contract on the pay day. Left out: employees who asked not to receive it, those you leave out below, a category without a rate, unpaid leave on the pay day, a departure in the month, and debts above the limit. The amount is never more than the net already earned on the pay day, so the month\'s payslip can always take it back. While Finance has not approved it, calculating again replaces it.')}</p>
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
        <p className="muted">{t('{a} of {b} employees asked not to receive the quinzaine. Employees make or withdraw the request themselves on their payslips page; it applies from the next quinzaine not yet approved. A quinzaine already approved or paid stays.', { a: requests, b: people.length })}</p>
        <div className="table-wrap">
          <table>
            <thead><tr><th>{t('Employee')}</th><th>{t('Category')}</th><th>{t('Quinzaine')}</th><th>{t('Reason')}</th></tr></thead>
            <tbody>
              {shown.map((p) => (
                <tr key={p.userId}>
                  <td>{p.employeeName} <span className="muted">{p.registrationNo}</span></td>
                  <td>{p.category}{p.percent != null ? ` · ${Number(p.percent)}%` : ` · ${t('no rate')}`}</td>
                  <td>{p.refused ? t('Asked not to receive it') : t('Takes it')}</td>
                  <td>{p.refused ? <>{p.reason ?? '—'}<br /><span className="muted">{p.recordedAt ? longDay(p.recordedAt) : ''}</span></> : null}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </section>
  )
}
