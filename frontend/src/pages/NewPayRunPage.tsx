import { useEffect, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import {
  ApiError, api, days, longDay, type LeaveAllowance, type LeavePayDue, type Page, type PayRunType, type PayRunTypeInfo,
  type QuinzaineEmployee, type QuinzaineExclusion, type QuinzaineRun, type ThirteenthRun, type ThirteenthSettings, type User,
} from '../api'
import { useAuth } from '../auth'
import { Button } from '../components/Button'
import { ErrorText } from '../components/ErrorText'
import { PAY_RUN_TYPES } from '../payRunTypes'
import { locale, t } from '../i18n'

const thisMonth = () => new Date().toISOString().slice(0, 7)
const lastMonth = () => { const d = new Date(); d.setDate(1); d.setMonth(d.getMonth() - 1); return d.toISOString().slice(0, 7) }
const monthName = (m: string) => new Date(m.slice(0, 7) + '-01T00:00:00').toLocaleDateString(locale(), { month: 'long', year: 'numeric' })
const failure = (e: unknown) => (e instanceof ApiError ? [e.message, ...Object.values(e.fieldErrors)].join(' ') : t('Something went wrong.'))

/** New pay run: choose the type, fill in its conditions, run it. The run then opens on its own page. */
export default function NewPayRunPage() {
  const [params, setParams] = useSearchParams()
  const type = params.get('type') as PayRunType | null
  const [types, setTypes] = useState<PayRunTypeInfo[]>([])
  const [error, setError] = useState<string | null>(null)
  useEffect(() => { api<PayRunTypeInfo[]>('/api/pay-runs/types').then(setTypes).catch((e: Error) => setError(e.message)) }, [])
  const available = (x: PayRunType) => types.find((i) => i.type === x)?.available ?? false
  const choose = (x: PayRunType) => { const p = new URLSearchParams(params); p.set('type', x); setParams(p) }

  return (
    <section>
      <h2>{t('New pay run')}</h2>
      <p className="muted small"><Link to="/payroll">{t('Pay runs')}</Link></p>
      <ErrorText error={error} />
      <div className="card">
        <h3>{t('1. Pay type')}</h3>
        <div className="type-pick">
          {PAY_RUN_TYPES.map((x) => available(x.type) ? (
            <button key={x.type} type="button" className={type === x.type ? 'chosen' : undefined} onClick={() => choose(x.type)}>
              <strong>{x.name}</strong><span>{x.description}</span>
            </button>
          ) : (
            <div key={x.type} className="off">
              <strong>{x.name}</strong><span>{t('Not paid in this company.')}{' '}{x.settings && <Link to={x.settings}>{t('Settings')}</Link>}</span>
            </div>
          ))}
        </div>
      </div>
      {type && available(type) && (
        <div className="card">
          <h3>{t('2. Conditions')}</h3>
          {type === 'MONTHLY' && <Monthly initial={params.get('month')} />}
          {type === 'QUINZAINE' && <Quinzaine initial={params.get('month')} />}
          {type === 'LEAVE_PAY' && <LeavePay />}
          {type === 'THIRTEENTH' && <Thirteenth />}
          {type === 'CORRECTION' && <Corrections />}
          {type === 'SETTLEMENT' && <Settlement />}
        </div>
      )}
    </section>
  )
}

function Monthly({ initial }: { initial: string | null }) {
  const navigate = useNavigate()
  const { can } = useAuth()
  const [month, setMonth] = useState(initial ?? lastMonth())
  const [status, setStatus] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => {
    api<{ run: { status: string } }>(`/api/payroll/runs/${month}`).then((v) => setStatus(v.run.status)).catch(() => setStatus(null))
  }, [month])
  const calculate = async () => {
    setError(null)
    try {
      await api(`/api/payroll/runs/${month}/calculate`, { method: 'POST' })
      navigate(`/payroll/runs/monthly/${month}`)
    } catch (e) { setError(failure(e)) }
  }
  const locked = status === 'APPROVED' || status === 'CLOSED'
  return (
    <>
      <ErrorText error={error} />
      <div className="row grant">
        <label>{t('Month')}<input type="month" value={month} onChange={(e) => e.target.value && setMonth(e.target.value)} /></label>
      </div>
      {status && <p className="hint">{locked ? t('This month is already approved or closed.') : t('Already calculated: calculating again replaces it.')}{' '}
        <Link to={`/payroll/runs/monthly/${month}`}>{t('Open')}</Link></p>}
      {month >= thisMonth() && <p className="notice">{t('Calculated before the end of the month: the remaining working days are counted as worked. Calculate again once the month is over; it cannot be approved before.')}</p>}
      {can('payroll.run') && !locked && <Button onClick={calculate}>{t('Calculate {a}', { a: month })}</Button>}
    </>
  )
}

function Quinzaine({ initial }: { initial: string | null }) {
  const navigate = useNavigate()
  const [month, setMonth] = useState(initial ?? thisMonth())
  const [payDate, setPayDate] = useState<string | null>(null)
  const [exclusions, setExclusions] = useState<QuinzaineExclusion[]>([])
  const [refused, setRefused] = useState<QuinzaineEmployee[]>([])
  const [error, setError] = useState<string | null>(null)
  const loadExclusions = () => api<QuinzaineExclusion[]>(`/api/quinzaine/exclusions?month=${month}`).then(setExclusions).catch(() => setExclusions([]))
  useEffect(() => { api<QuinzaineEmployee[]>('/api/quinzaine/employees').then(setRefused).catch(() => undefined) }, [])
  useEffect(() => {
    api<string>(`/api/quinzaine/pay-date?month=${month}`).then(setPayDate).catch(() => setPayDate(null))
    void loadExclusions()
  }, [month]) // eslint-disable-line react-hooks/exhaustive-deps
  const calculate = async () => {
    setError(null)
    try {
      const r = await api<QuinzaineRun>('/api/quinzaine/calculate', { method: 'POST', body: { month } })
      navigate(`/payroll/runs/quinzaine/${r.id}`)
    } catch (e) { setError(failure(e)) }
  }
  return (
    <>
      <ErrorText error={error} />
      <div className="row grant">
        <label>{t('Month')}<input type="month" value={month} onChange={(e) => e.target.value && setMonth(e.target.value)} /></label>
        {payDate && <span>{t('Paid on {a}', { a: longDay(payDate) })}</span>}
      </div>
      <p className="hint">{t('For everyone under contract on the pay day, never above the net already earned. Calculating again replaces it until Finance approves.')}</p>
      {exclusions.length > 0 && (
        <>
          <h4>{t('Left out by HR this month')}</h4>
          <ul>{exclusions.map((x) => (
            <li key={x.id}>{t('{a} left out: {b}', { a: x.employeeName, b: x.reason })}{' '}
              <Button className="secondary" onClick={async () => {
                try { await api(`/api/quinzaine/exclusions?month=${month}&userId=${x.userId}`, { method: 'DELETE' }); await loadExclusions() } catch (e) { setError(failure(e)) }
              }}>{t('Put back')}</Button></li>
          ))}</ul>
        </>
      )}
      {refused.length > 0 && (
        <details>
          <summary>{t('{n} employees asked not to receive it, from their payslips page.', { n: refused.length })}</summary>
          <ul>{refused.map((p) => <li key={p.userId}>{p.employeeName} <span className="muted">{p.registrationNo}</span>{p.reason ? ` · ${p.reason}` : ''}</li>)}</ul>
        </details>
      )}
      <Button onClick={calculate}>{t('Calculate the quinzaine')}</Button>
    </>
  )
}

function LeavePay() {
  const navigate = useNavigate()
  const { me } = useAuth()
  const [due, setDue] = useState<LeavePayDue[] | null>(null)
  const [chosen, setChosen] = useState<number[]>([])
  const [skipped, setSkipped] = useState<string[]>([])
  const [error, setError] = useState<string | null>(null)
  useEffect(() => {
    api<{ due: LeavePayDue[] }>('/api/leave-pay').then((o) => setDue(o.due)).catch((e: Error) => setError(e.message))
  }, [])
  const late = (d: string) => d < new Date().toISOString().slice(0, 10)
  const calculate = async () => {
    setError(null)
    try {
      const r = await api<{ calculated: LeaveAllowance[]; skipped: string[] }>('/api/leave-pay/calculate', { method: 'POST', body: { leaveRequestIds: chosen } })
      if (r.calculated.length > 0) navigate(`/payroll/runs/leave-pay/${r.calculated[0].payBy.slice(0, 7)}`)
      else setSkipped(r.skipped)
    } catch (e) { setError(failure(e)) }
  }
  if (!due) return <ErrorText error={error} />
  return (
    <>
      <ErrorText error={error} />
      {skipped.length > 0 && <ul className="warnings">{skipped.map((s) => <li key={s}>{s}</li>)}</ul>}
      <p className="hint">{t('Approved annual leave waiting for its pay. Tick some, or work out all of them.')}</p>
      {due.length === 0 ? <p className="muted">{t('Nothing waiting.')}</p> : (
        <>
          <div className="table-wrap">
            <table>
              <thead><tr><th /><th>{t('Employee')}</th><th>{t('Leave')}</th><th>{t('Days')}</th><th>{t('Pay by')}</th></tr></thead>
              <tbody>
                {due.map((d) => (
                  <tr key={d.leaveRequestId}>
                    <td><input type="checkbox" checked={chosen.includes(d.leaveRequestId)} disabled={d.userId === me?.id}
                      onChange={(e) => setChosen(e.target.checked ? [...chosen, d.leaveRequestId] : chosen.filter((x) => x !== d.leaveRequestId))} /></td>
                    <td>{d.employeeName} <span className="muted">{d.registrationNo}</span></td>
                    <td>{d.startDate === d.endDate ? longDay(d.startDate) : t('{a} to {b}', { a: longDay(d.startDate), b: longDay(d.endDate) })}</td>
                    <td>{days(d.workingDays)}</td>
                    <td className={late(d.payBy) ? 'danger-text' : undefined}>{longDay(d.payBy)}{late(d.payBy) ? ` · ${t('late')}` : ''}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <Button onClick={calculate}>{chosen.length === 0 ? t('Work out leave pay for all') : t('Work out leave pay for {a}', { a: chosen.length })}</Button>
        </>
      )}
    </>
  )
}

const MONTHS = [t('January'), t('February'), t('March'), t('April'), t('May'), t('June'), t('July'), t('August'), t('September'), t('October'), t('November'), t('December')]

function Thirteenth() {
  const navigate = useNavigate()
  const [settings, setSettings] = useState<ThirteenthSettings | null>(null)
  const [year, setYear] = useState(new Date().getFullYear())
  const [month, setMonth] = useState<string>('')
  const [error, setError] = useState<string | null>(null)
  useEffect(() => {
    api<{ settings: ThirteenthSettings; runs: ThirteenthRun[] }>('/api/thirteenth-runs').then((o) => setSettings(o.settings)).catch((e: Error) => setError(e.message))
  }, [])
  const payMonth = month || (settings ? `${year}-${String(settings.payMonth).padStart(2, '0')}` : '')
  const calculate = async () => {
    setError(null)
    try {
      const r = await api<ThirteenthRun>('/api/thirteenth-runs/calculate', { method: 'POST', body: { year, month: payMonth } })
      navigate(`/payroll/runs/thirteenth/${r.id}`)
    } catch (e) { setError(failure(e)) }
  }
  if (!settings) return <ErrorText error={error} />
  return (
    <>
      <ErrorText error={error} />
      <div className="row grant">
        <label>{t('Year')}<input type="number" className="narrow-input" min={2000} max={2100} value={year}
          onChange={(e) => e.target.value.length === 4 && setYear(Number(e.target.value))} /></label>
        <label>{t('Paid with the payroll of')}<input type="month" value={payMonth} min={`${year}-01`} max={`${year + 1}-12`}
          onChange={(e) => e.target.value && setMonth(e.target.value)} /></label>
      </div>
      <p className="hint">{t('Base: {a}; fallback score {b}%{c}. Default pay month: {d}.', {
        a: settings.base === 'LAST_BASIC' ? t('last basic of the year') : t('average basic over the year'), b: settings.fallbackPercent,
        c: settings.prorate ? t(', prorated by months worked') : '', d: MONTHS[settings.payMonth - 1],
      })}{' '}<Link to="/payroll/thirteenth-month/settings">{t('Settings')}</Link></p>
      <p className="hint">{t('Finance approves it, then the payroll of that month puts it on each payslip, with CNSS and income tax.')}</p>
      <Button onClick={calculate}>{t('Calculate the 13th month {a}', { a: year })}</Button>
      {payMonth && <span className="muted small">{' '}{monthName(payMonth)}</span>}
    </>
  )
}

function Corrections() {
  const [due, setDue] = useState<number | null>(null)
  useEffect(() => { api<unknown[]>('/api/payroll/back-pay').then((d) => setDue(d.length)).catch(() => setDue(null)) }, [])
  return (
    <>
      <p className="hint">{t('A closed payslip is never changed: open it and choose Correct to make a complementary payslip, carry the difference into the next payroll, or cancel it.')}</p>
      {due != null && <p>{due > 0 ? t('{n} closed payslip(s) have back pay due.', { n: due }) : t('No back pay is due.')}</p>}
      <Link className="button" to="/payroll/runs/corrections">{t('Open the corrections')}</Link>
    </>
  )
}

function Settlement() {
  const navigate = useNavigate()
  const [query, setQuery] = useState('')
  const [people, setPeople] = useState<User[]>([])
  useEffect(() => {
    const handle = setTimeout(() => {
      const p = new URLSearchParams({ page: '0', size: '10' })
      if (query.trim()) p.set('q', query.trim())
      api<Page<User>>(`/api/users?${p}`).then((d) => setPeople(d.items)).catch(() => setPeople([]))
    }, 250)
    return () => clearTimeout(handle)
  }, [query])
  return (
    <>
      <p className="hint">{t('The settlement goes on the last payslip, in the month of the last day.')}</p>
      <input type="search" placeholder={t('Search an employee')} value={query} onChange={(e) => setQuery(e.target.value)} />
      <ul>{people.map((u) => (
        <li key={u.id}><Button className="link" onClick={() => navigate(`/users/${u.id}/departure`)}>{u.lastName} {u.firstName}</Button>
          {' '}<span className="muted">{u.registrationNo ?? ''}</span></li>
      ))}</ul>
    </>
  )
}
