import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import {
  ApiError, DEPARTURE_REASONS, api, download, downloadJob, longDay, money, openFile, personName, type Page, type User,
} from '../api'
import { Button } from '../components/Button'
import { ErrorText } from '../components/ErrorText'
import { UnitSelect } from '../components/OrgCard'
import type { OrgUnit } from '../org'
import { locale, t } from '../i18n'

type SimType = 'MONTH' | 'QUINZAINE' | 'LEAVE_PAY' | 'THIRTEENTH' | 'SETTLEMENT'
type Line = { elementCode: string; label: string; kind: 'EARNING' | 'DEDUCTION' | 'EMPLOYER_CHARGE'; base: number | null; rate: number | null; amount: number }
type Paid = { month: string; number: string | null; currency: string; gross: number; employeeDeductions: number; net: number; employerCharges: number }
type Result = {
  type: SimType; userId: number; name: string; registrationNo: string | null; period: string; currency: string | null
  lines: Line[]; gross: number; employeeDeductions: number; net: number; employerCharges: number; totalCost: number
  explanation: string[]; warnings: string[]; paid: Paid | null; usdRate: number | null; rateDate: string | null
  scheduledMinutes: number; paidMinutes: number; daysWorked: number; changed: boolean
}
type WhatIf = { basic: string; complement: string; overtimeHours: string; unpaidDays: string; bonus: string; children: string; dependants: string; usdRate: string }
type ProjMonth = {
  month: string; status: 'PAID' | 'PROJECTED' | 'NO_CONTRACT' | 'LEFT' | 'NOT_PAID'; currency: string | null; basic: number | null
  gross: number | null; cnss: number | null; tax: number | null; employeeDeductions: number | null; net: number | null
  employerCharges: number | null; cost: number | null; leaveEarned: number | null; events: string[]
}
type Total = { currency: string; gross: number; cnss: number; tax: number; employeeDeductions: number; net: number; employerCharges: number; cost: number }
type Projection = {
  userId: number; name: string; registrationNo: string | null; year: number; variable: string; usdRate: number; rateDate: string
  months: ProjMonth[]; totals: Total[]; raises: number; notes: string[]
  leave: { balanceNow: number; earnedToCome: number; requested: number; balanceYearEnd: number; dailyRate: number | null; value: number | null; currency: string | null } | null
}
type GroupMonth = { month: string; currency: string; people: number; gross: number; cnss: number; tax: number; net: number; employerCharges: number; cost: number }
type Group = {
  year: number; unitId: number | null; unitName: string | null; people: number; months: GroupMonth[]; totals: Total[]
  employees: { userId: number; name: string; registrationNo: string | null; currency: string; gross: number; net: number; cost: number; raises: number; leaveValue: number | null }[]
  skipped: string[]
}

const TYPES: Record<SimType, string> = {
  MONTH: t('Month-end payslip'),
  QUINZAINE: t('Quinzaine (mid-month advance)'),
  LEAVE_PAY: t('Allocation de congé'),
  THIRTEENTH: t('13th month'),
  SETTLEMENT: t('Final settlement'),
}
const STATUS: Record<ProjMonth['status'], string> = {
  PAID: t('Paid'), PROJECTED: t('Projected'), NO_CONTRACT: t('No contract'), LEFT: t('Left'), NOT_PAID: t('Not paid'),
}
const thisMonth = () => new Date().toISOString().slice(0, 7)
const monthName = (m: string) => new Date(m.slice(0, 7) + '-01T00:00:00').toLocaleDateString(locale(), { month: 'long', year: 'numeric' })
const num = (n: number | null, digits = 2) => (n === null || n === undefined ? '' : new Intl.NumberFormat(locale(), { maximumFractionDigits: digits }).format(n))
const cash = (n: number | null, cur: string | null) => (n === null || n === undefined || !cur ? '' : money(n, cur))
const failure = (e: unknown) => (e instanceof ApiError ? [e.message, ...Object.values(e.fieldErrors)].join(' ') : t('Something went wrong.'))
const decimal = (s: string) => (s.trim() === '' ? null : Number(s.replace(',', '.')))
const whole = (s: string) => (s.trim() === '' ? null : Math.round(Number(s)))
const EMPTY: WhatIf = { basic: '', complement: '', overtimeHours: '', unpaidDays: '', bonus: '', children: '', dependants: '', usdRate: '' }

type Tab = 'simulate' | 'projection' | 'company'

/** Simulate pay and year-end projection: the real payroll engine, nothing saved. */
export default function PaySimulationPage() {
  const [params, setParams] = useSearchParams()
  const tab = (params.get('tab') as Tab | null) ?? 'simulate'
  const tabs: Record<Tab, string> = { simulate: t('Simulate pay'), projection: t('Year projection'), company: t('Company projection') }
  const setTab = (k: Tab) => { const p = new URLSearchParams(params); p.set('tab', k); setParams(p) }
  const [person, setPerson] = useState<User | null>(null)
  const userId = params.get('userId')
  useEffect(() => {
    if (userId && (!person || String(person.id) !== userId)) api<User>(`/api/users/${userId}`).then(setPerson).catch(() => undefined)
  }, [userId]) // eslint-disable-line react-hooks/exhaustive-deps
  const choose = (u: User | null) => {
    setPerson(u)
    const p = new URLSearchParams(params)
    if (u) p.set('userId', String(u.id)); else p.delete('userId')
    setParams(p)
  }
  return (
    <section>
      <h2>{t('Simulate pay')}</h2>
      <p className="muted">{t('Works out pay with the real payroll engine from the data recorded today. Nothing is saved, nothing is paid, and the employee does not see it: it is not a payslip.')}</p>
      <div className="segmented" role="tablist">
        {(Object.keys(tabs) as Tab[]).map((k) => (
          <button key={k} type="button" role="tab" aria-selected={tab === k} className={tab === k ? 'on' : ''} onClick={() => setTab(k)}>{tabs[k]}</button>
        ))}
      </div>
      {tab !== 'company' && <PersonPicker person={person} onChange={choose} />}
      {tab === 'simulate' && person && <Simulate key={person.id} person={person} />}
      {tab === 'projection' && person && <PersonProjection key={person.id} person={person} />}
      {tab === 'company' && <CompanyProjection />}
    </section>
  )
}

function PersonPicker({ person, onChange }: { person: User | null; onChange: (u: User | null) => void }) {
  const [query, setQuery] = useState('')
  const [people, setPeople] = useState<User[]>([])
  useEffect(() => {
    if (person) return
    const handle = setTimeout(() => {
      const p = new URLSearchParams({ page: '0', size: '8' })
      if (query.trim()) p.set('q', query.trim())
      api<Page<User>>(`/api/users?${p}`).then((d) => setPeople(d.items)).catch(() => setPeople([]))
    }, 250)
    return () => clearTimeout(handle)
  }, [query, person])
  if (person) {
    return (
      <div className="card">
        <p><strong>{personName(person)}</strong>{' '}<span className="muted">{person.registrationNo ?? ''}</span>{' '}
          <Button className="link" onClick={() => onChange(null)}>{t('Change')}</Button>{' · '}
          <Link to={`/users/${person.id}/file`}>{t('Contract and family')}</Link></p>
      </div>
    )
  }
  return (
    <div className="card">
      <h3>{t('Employee')}</h3>
      <input type="search" placeholder={t('Search an employee')} value={query} onChange={(e) => setQuery(e.target.value)} />
      <ul>{people.map((u) => (
        <li key={u.id}><Button className="link" onClick={() => onChange(u)}>{personName(u)}</Button>{' '}<span className="muted">{u.registrationNo ?? ''}</span></li>
      ))}</ul>
    </div>
  )
}

function Simulate({ person }: { person: User }) {
  const [type, setType] = useState<SimType>('MONTH')
  const [month, setMonth] = useState(thisMonth())
  const [year, setYear] = useState(new Date().getFullYear())
  const [leaveFrom, setLeaveFrom] = useState('')
  const [leaveTo, setLeaveTo] = useState('')
  const [lastDay, setLastDay] = useState('')
  const [reason, setReason] = useState('RESIGNATION')
  const [w, setW] = useState<WhatIf>(EMPTY)
  const [result, setResult] = useState<Result | null>(null)
  const [error, setError] = useState<string | null>(null)
  const whatIfApplies = type === 'MONTH' || type === 'THIRTEENTH' || type === 'SETTLEMENT'

  const request = () => ({
    userId: person.id, type,
    month: type === 'MONTH' || type === 'QUINZAINE' ? month : null,
    year: type === 'THIRTEENTH' ? year : null,
    leaveFrom: type === 'LEAVE_PAY' ? leaveFrom || null : null,
    leaveTo: type === 'LEAVE_PAY' ? leaveTo || null : null,
    lastDay: type === 'SETTLEMENT' ? lastDay || null : null,
    reason: type === 'SETTLEMENT' ? reason : null,
    whatIf: whatIfApplies ? {
      basic: decimal(w.basic), complement: decimal(w.complement), overtimeHours: decimal(w.overtimeHours), unpaidDays: decimal(w.unpaidDays),
      bonus: decimal(w.bonus), children: whole(w.children), dependants: whole(w.dependants), usdRate: decimal(w.usdRate),
    } : null,
  })
  const run = async () => {
    setError(null)
    try { setResult(await api<Result>('/api/payroll/simulate', { method: 'POST', body: request() })) } catch (e) { setResult(null); setError(failure(e)) }
  }
  const pdf = async () => {
    const r = request()
    const p = new URLSearchParams()
    Object.entries(r).forEach(([k, v]) => { if (k !== 'whatIf' && v !== null && v !== undefined) p.set(k, String(v)) })
    Object.entries(r.whatIf ?? {}).forEach(([k, v]) => { if (v !== null && v !== undefined) p.set(k, String(v)) })
    try { await openFile(`/api/payroll/simulate.pdf?${p}`) } catch (e) { setError(failure(e)) }
  }
  const field = (k: keyof WhatIf, label: string, hint?: string) => (
    <label>{label}<input inputMode="decimal" value={w[k]} placeholder={hint ?? t('as recorded')} onChange={(e) => setW({ ...w, [k]: e.target.value })} /></label>
  )

  return (
    <>
      <div className="card">
        <h3>{t('What to simulate')}</h3>
        <div className="row grant">
          <label>{t('Pay type')}
            <select value={type} onChange={(e) => { setType(e.target.value as SimType); setResult(null) }}>
              {(Object.keys(TYPES) as SimType[]).map((k) => <option key={k} value={k}>{TYPES[k]}</option>)}
            </select>
          </label>
          {(type === 'MONTH' || type === 'QUINZAINE') && <label>{t('Month')}<input type="month" value={month} onChange={(e) => e.target.value && setMonth(e.target.value)} /></label>}
          {type === 'THIRTEENTH' && <label>{t('Year')}<input type="number" className="narrow-input" min={2000} max={2100} value={year}
            onChange={(e) => e.target.value.length === 4 && setYear(Number(e.target.value))} /></label>}
          {type === 'LEAVE_PAY' && <>
            <label>{t('First day of leave')}<input type="date" value={leaveFrom} onChange={(e) => setLeaveFrom(e.target.value)} /></label>
            <label>{t('Last day of leave')}<input type="date" value={leaveTo} min={leaveFrom} onChange={(e) => setLeaveTo(e.target.value)} /></label>
          </>}
          {type === 'SETTLEMENT' && <>
            <label>{t('Last day of work')}<input type="date" value={lastDay} onChange={(e) => setLastDay(e.target.value)} /></label>
            <label>{t('Reason')}<select value={reason} onChange={(e) => setReason(e.target.value)}>
              {Object.entries(DEPARTURE_REASONS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
            </select></label>
          </>}
        </div>
        {whatIfApplies && (
          <details>
            <summary>{t('What if (optional): change some of the recorded data')}</summary>
            <div className="row grant">
              {field('basic', t('Basic salary'))}
              {field('complement', t('Salary complement'))}
              {field('bonus', t('One-off bonus'), '0')}
              {field('overtimeHours', t('More overtime (hours)'), '0')}
              {field('unpaidDays', t('Days not paid'), '0')}
              {field('children', t('Children counted'))}
              {field('dependants', t('Dependants in charge'))}
              {field('usdRate', t('1 USD in local currency'))}
            </div>
            <p className="hint">{t('Amounts in the currency of the person\'s salary. Leave a field empty to keep what is recorded.')}</p>
            {Object.values(w).some((v) => v !== '') && <Button className="link" onClick={() => setW(EMPTY)}>{t('Clear the changes')}</Button>}
          </details>
        )}
        <ErrorText error={error} />
        <p className="row">
          <Button onClick={run}>{t('Simulate')}</Button>
          {result && type !== 'QUINZAINE' && <Button className="secondary" onClick={pdf}>{t('PDF marked SIMULATION')}</Button>}
        </p>
      </div>
      {result && <SimResult r={result} />}
    </>
  )
}

function SimResult({ r }: { r: Result }) {
  const cur = r.currency ?? 'USD'
  const of = (kind: Line['kind']) => r.lines.filter((l) => l.kind === kind)
  const rateCell = (l: Line) => (l.rate === null ? '' : l.kind === 'EARNING' && l.elementCode !== 'HOUSING' ? num(l.rate, 4) : `${num(l.rate, 3)}%`)
  const section = (title: string, lines: Line[], total: number, label: string) => lines.length === 0 ? null : (
    <>
      <tr className="group"><th colSpan={4}>{title}</th></tr>
      {lines.map((l, i) => (
        <tr key={title + i}><td>{l.label}</td><td className="num">{num(l.base)}</td><td className="num">{rateCell(l)}</td><td className="num">{money(l.amount, cur)}</td></tr>
      ))}
      <tr className="subtotal"><td colSpan={3}>{label}</td><td className="num">{money(total, cur)}</td></tr>
    </>
  )
  const period = r.type === 'LEAVE_PAY' ? r.period.split('/').map(longDay).join(' – ') : r.type === 'THIRTEENTH' ? monthName(r.period) : monthName(r.period)
  return (
    <div className="card">
      <h3>{TYPES[r.type]}{' · '}{period}{r.changed && <span className="badge">{' '}{t('what if')}</span>}</h3>
      <p className="notice">{t('Simulation: not a payslip, nothing saved.')}</p>
      {r.warnings.map((x, i) => <p key={i} className="hint">{x}</p>)}
      <div className="table-wrap">
        <table className="lines">
          <thead><tr><th>{t('Item')}</th><th className="num">{t('Base')}</th><th className="num">{t('Rate')}</th><th className="num">{t('Amount')}</th></tr></thead>
          <tbody>
            {section(t('Earnings'), of('EARNING'), r.gross, t('Gross pay'))}
            {section(t('Deductions'), of('DEDUCTION'), r.employeeDeductions, t('Total deductions'))}
            <tr className="net"><td colSpan={3}>{r.type === 'QUINZAINE' ? t('Paid mid-month') : t('Net pay')}</td><td className="num">{money(r.net, cur)}</td></tr>
            {section(t('Employer charges'), of('EMPLOYER_CHARGE'), r.employerCharges, t('Total employer charges'))}
            {r.type !== 'QUINZAINE' && <tr className="subtotal"><td colSpan={3}>{t('Total cost to the employer')}</td><td className="num">{money(r.totalCost, cur)}</td></tr>}
          </tbody>
        </table>
      </div>
      {r.paid && (r.type === 'MONTH' || r.type === 'THIRTEENTH' || r.type === 'SETTLEMENT') && (
        <>
          <h4>{t('Compared with the payslip paid for {a}', { a: monthName(r.paid.month) })}</h4>
          <div className="table-wrap">
            <table className="lines">
              <thead><tr><th></th><th className="num">{t('Simulation')}</th><th className="num">{t('Paid')}</th><th className="num">{t('Difference')}</th></tr></thead>
              <tbody>
                {([[t('Gross pay'), r.gross, r.paid.gross], [t('Deductions'), r.employeeDeductions, r.paid.employeeDeductions],
                  [t('Net pay'), r.net, r.paid.net], [t('Employer charges'), r.employerCharges, r.paid.employerCharges]] as [string, number, number][]).map(([label, a, b]) => (
                  <tr key={label}><td>{label}</td><td className="num">{money(a, cur)}</td><td className="num">{money(b, r.paid!.currency)}</td>
                    <td className="num">{r.paid!.currency === cur ? money(a - b, cur) : ''}</td></tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}
      {r.explanation.length > 0 && <>
        <h4>{t('How it was worked out')}</h4>
        <ul>{r.explanation.map((x, i) => <li key={i}>{x}</li>)}</ul>
      </>}
      {r.usdRate !== null && r.rateDate && <p className="hint">{t('1 USD = {a} ({b}).', { a: num(r.usdRate, 4), b: longDay(r.rateDate) })}</p>}
    </div>
  )
}

function PersonProjection({ person }: { person: User }) {
  const [year, setYear] = useState(new Date().getFullYear())
  const [variable, setVariable] = useState<'AVERAGE' | 'NONE'>('AVERAGE')
  const [usdRate, setUsdRate] = useState('')
  const [p, setP] = useState<Projection | null>(null)
  const [error, setError] = useState<string | null>(null)
  const query = (format: string) => {
    const q = new URLSearchParams({ userId: String(person.id), year: String(year), variable, format })
    if (decimal(usdRate)) q.set('usdRate', String(decimal(usdRate)))
    return `/api/payroll/projection?${q}`
  }
  const run = async () => {
    setError(null)
    try { setP(await api<Projection>(query('json'))) } catch (e) { setP(null); setError(failure(e)) }
  }
  const file = async (format: 'pdf' | 'xlsx') => {
    try { await download(query(format), `projection-${year}.${format}`) } catch (e) { setError(failure(e)) }
  }
  return (
    <>
      <div className="card">
        <h3>{t('Year projection')}</h3>
        <div className="row grant">
          <label>{t('Year')}<input type="number" className="narrow-input" min={2000} max={2100} value={year}
            onChange={(e) => e.target.value.length === 4 && setYear(Number(e.target.value))} /></label>
          <label>{t('Bonuses and overtime to come')}<select value={variable} onChange={(e) => setVariable(e.target.value as 'AVERAGE' | 'NONE')}>
            <option value="AVERAGE">{t('Average of the last 12 months')}</option>
            <option value="NONE">{t('None')}</option>
          </select></label>
          <label>{t('1 USD in local currency')}<input inputMode="decimal" value={usdRate} placeholder={t('today\'s rate')} onChange={(e) => setUsdRate(e.target.value)} /></label>
        </div>
        <p className="hint">{t('Closed months as paid; the others projected with what is known today: salary changes already recorded, anniversary raises, annual leave and its allocation, the 13th month, loans, the end of a fixed-term contract, children reaching the age limit and rates entered for later dates.')}</p>
        <ErrorText error={error} />
        <p className="row">
          <Button onClick={run}>{t('Project {a}', { a: year })}</Button>
          {p && <><Button className="secondary" onClick={() => file('xlsx')}>{t('Excel')}</Button><Button className="secondary" onClick={() => file('pdf')}>{t('PDF')}</Button></>}
        </p>
      </div>
      {p && (
        <div className="card">
          <h3>{t('Pay projection {a}', { a: p.year })}</h3>
          <div className="table-wrap">
            <table className="report">
              <thead><tr>
                <th>{t('Month')}</th><th>{t('Status')}</th><th className="num">{t('Basic salary')}</th><th className="num">{t('Gross')}</th>
                <th className="num">{t('CNSS (employee)')}</th><th className="num">{t('Income tax')}</th><th className="num">{t('Net')}</th>
                <th className="num">{t('Employer charges')}</th><th className="num">{t('Total cost')}</th><th>{t('What changes')}</th>
              </tr></thead>
              <tbody>
                {p.months.map((m) => (
                  <tr key={m.month} className={m.status === 'PAID' ? 'muted' : undefined}>
                    <td>{monthName(m.month)}</td><td style={{ whiteSpace: 'nowrap' }}>{STATUS[m.status]}</td>
                    {m.gross == null ? (
                      <td colSpan={8} className="small muted">{m.events.join(' · ')}</td>
                    ) : <>
                    <td className="num">{cash(m.basic, m.currency)}</td><td className="num">{cash(m.gross, m.currency)}</td>
                    <td className="num">{cash(m.cnss, m.currency)}</td><td className="num">{cash(m.tax, m.currency)}</td>
                    <td className="num">{cash(m.net, m.currency)}</td><td className="num">{cash(m.employerCharges, m.currency)}</td>
                    <td className="num">{cash(m.cost, m.currency)}</td>
                    <td className="small" style={{ minWidth: '16rem' }}>{m.events.map((e, i) => <div key={i}>{e}</div>)}</td>
                    </>}
                  </tr>
                ))}
                {p.totals.map((x) => (
                  <tr key={x.currency} className="total">
                    <td>{t('Total')}</td><td>{x.currency}</td><td></td><td className="num">{money(x.gross, x.currency)}</td>
                    <td className="num">{money(x.cnss, x.currency)}</td><td className="num">{money(x.tax, x.currency)}</td>
                    <td className="num">{money(x.net, x.currency)}</td><td className="num">{money(x.employerCharges, x.currency)}</td>
                    <td className="num">{money(x.cost, x.currency)}</td><td></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {p.leave && (
            <p>{t('Annual leave: {a} days now, {b} still to earn this year, {c} asked for and not approved yet: about {d} days left on 31 December.', {
              a: num(p.leave.balanceNow, 1), b: num(p.leave.earnedToCome, 1), c: num(p.leave.requested, 1), d: num(p.leave.balanceYearEnd, 1),
            })}{p.leave.value !== null && p.leave.currency && ' ' + t('Worth {a} at {b} a day, paid only when the contract ends.', {
              a: money(p.leave.value, p.leave.currency), b: money(p.leave.dailyRate ?? 0, p.leave.currency),
            })}</p>
          )}
          <ul className="hint">{p.notes.map((n, i) => <li key={i}>{n}</li>)}</ul>
        </div>
      )}
    </>
  )
}

function CompanyProjection() {
  const [year, setYear] = useState(new Date().getFullYear())
  const [variable, setVariable] = useState<'AVERAGE' | 'NONE'>('AVERAGE')
  const [unit, setUnit] = useState('')
  const [units, setUnits] = useState<OrgUnit[]>([])
  const [g, setG] = useState<Group | null>(null)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => { api<OrgUnit[]>('/api/org/units').then(setUnits).catch(() => undefined) }, [])
  const body = () => ({ year, variable, unitId: unit ? Number(unit) : null })
  const run = async () => {
    setError(null)
    try { setG(await api<Group>('/api/payroll/projection/group', { method: 'POST', body: body() })) } catch (e) { setG(null); setError(failure(e)) }
  }
  const excel = async () => {
    try { await downloadJob('/api/payroll/projection/group.xlsx', body()) } catch (e) { setError(failure(e)) }
  }
  return (
    <>
      <div className="card">
        <h3>{t('Company projection')}</h3>
        <div className="row grant">
          <label>{t('Year')}<input type="number" className="narrow-input" min={2000} max={2100} value={year}
            onChange={(e) => e.target.value.length === 4 && setYear(Number(e.target.value))} /></label>
          <label>{t('Unit')}<UnitSelect units={units} value={unit} onChange={setUnit} placeholder={t('Whole company')} /></label>
          <label>{t('Bonuses and overtime to come')}<select value={variable} onChange={(e) => setVariable(e.target.value as 'AVERAGE' | 'NONE')}>
            <option value="AVERAGE">{t('Average of the last 12 months')}</option>
            <option value="NONE">{t('None')}</option>
          </select></label>
        </div>
        <p className="hint">{t('Everyone under contract in the year, projected one by one like the year projection, then added up. A large company takes a few minutes: it runs in the background (My jobs).')}</p>
        <ErrorText error={error} />
        <p className="row">
          <Button onClick={run}>{t('Project {a}', { a: year })}</Button>
          <Button className="secondary" onClick={excel}>{t('Excel')}</Button>
        </p>
      </div>
      {g && (
        <div className="card">
          <h3>{t('Pay projection {a}: {b}', { a: g.year, b: g.unitName ?? t('whole company') })}</h3>
          <p className="muted">{t('{n} people', { n: g.people })}</p>
          <div className="table-wrap">
            <table className="report">
              <thead><tr>
                <th>{t('Month')}</th><th className="num">{t('People')}</th><th className="num">{t('Gross')}</th><th className="num">{t('CNSS (employee)')}</th>
                <th className="num">{t('Income tax')}</th><th className="num">{t('Net')}</th><th className="num">{t('Employer charges')}</th><th className="num">{t('Total cost')}</th>
              </tr></thead>
              <tbody>
                {g.months.map((m) => (
                  <tr key={m.month + m.currency}>
                    <td>{monthName(m.month)}{g.totals.length > 1 ? ` · ${m.currency}` : ''}</td><td className="num">{m.people}</td>
                    <td className="num">{money(m.gross, m.currency)}</td><td className="num">{money(m.cnss, m.currency)}</td>
                    <td className="num">{money(m.tax, m.currency)}</td><td className="num">{money(m.net, m.currency)}</td>
                    <td className="num">{money(m.employerCharges, m.currency)}</td><td className="num">{money(m.cost, m.currency)}</td>
                  </tr>
                ))}
                {g.totals.map((x) => (
                  <tr key={x.currency} className="total">
                    <td>{t('Total')} {x.currency}</td><td></td><td className="num">{money(x.gross, x.currency)}</td><td className="num">{money(x.cnss, x.currency)}</td>
                    <td className="num">{money(x.tax, x.currency)}</td><td className="num">{money(x.net, x.currency)}</td>
                    <td className="num">{money(x.employerCharges, x.currency)}</td><td className="num">{money(x.cost, x.currency)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <details>
            <summary>{t('By employee')}</summary>
            <div className="table-wrap">
              <table className="report">
                <thead><tr><th>{t('Employee')}</th><th className="num">{t('Gross')}</th><th className="num">{t('Net')}</th><th className="num">{t('Total cost')}</th>
                  <th className="num">{t('Raises')}</th><th className="num">{t('Leave left on 31 Dec. (value)')}</th></tr></thead>
                <tbody>{g.employees.map((e) => (
                  <tr key={e.userId + e.currency}>
                    <td><Link to={`/payroll/simulate?tab=projection&userId=${e.userId}`}>{e.name}</Link> <span className="muted">{e.registrationNo ?? ''}</span></td>
                    <td className="num">{money(e.gross, e.currency)}</td><td className="num">{money(e.net, e.currency)}</td><td className="num">{money(e.cost, e.currency)}</td>
                    <td className="num">{e.raises || ''}</td><td className="num">{e.leaveValue === null ? '' : money(e.leaveValue, e.currency)}</td>
                  </tr>
                ))}</tbody>
              </table>
            </div>
          </details>
          {g.skipped.length > 0 && <ul className="hint">{g.skipped.map((s, i) => <li key={i}>{s}</li>)}</ul>}
        </div>
      )}
    </>
  )
}
