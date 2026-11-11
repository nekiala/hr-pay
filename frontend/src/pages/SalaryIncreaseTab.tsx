import { useEffect, useState } from 'react'
import { ApiError, api, downloadJob, money, type Grade } from '../api'
import { useAuth } from '../auth'
import { Button } from '../components/Button'
import { useAsk } from '../components/Dialogs'
import { ErrorText } from '../components/ErrorText'
import { locale, t } from '../i18n'

type GradeLine = { gradeId: number; code: string; name: string; categoryName: string; currency: string; oldBase: number; newBase: number; raise: number; legalMinimum: number | null; people: number }
type PersonLine = {
  userId: number; name: string; registrationNo: string | null; gradeCode: string; currency: string
  oldBasic: number | null; newBasic: number | null; oldGross: number | null; newGross: number | null; oldNet: number | null; newNet: number | null
  oldCost: number | null; newCost: number | null; arrears: number | null; arrearsMonths: number; problem: string | null
}
type Total = {
  currency: string; people: number; payrollCost: number; gross: number; net: number; employerCharges: number; cost: number
  percentOfCost: number | null; costToYearEnd: number; monthsToYearEnd: number; costTwelveMonths: number; arrears: number
}
type Result = {
  scenarioId: number | null; name: string; percent: number; startMonth: string; referenceMonth: string; arrearsMonth: string | null
  paidMonths: string[]; grades: GradeLine[]; people: PersonLine[]; totals: Total[]; warnings: string[]; notes: string[]
}
type Status = 'DRAFT' | 'SUBMITTED' | 'APPROVED' | 'REJECTED' | 'UNDONE'
type Scenario = {
  id: number; name: string; percent: number; minimumAmount: number | null; minimumCurrency: string | null; rounding: number | null
  startMonth: string; categoryIds: number[]; gradeIds: number[]; status: Status; summary: Total[] | null; arrearsMonth: string | null
  createdBy: string; submittedBy: string | null; decidedBy: string | null; decisionNote: string | null; canUndo: boolean
}
type Form = { name: string; percent: string; minimumAmount: string; minimumCurrency: string; rounding: string; startMonth: string; categoryIds: number[]; gradeIds: number[] }

const STATUS: Record<Status, string> = {
  DRAFT: t('Draft'), SUBMITTED: t('Waiting for approval'), APPROVED: t('Approved'), REJECTED: t('Rejected'), UNDONE: t('Undone'),
}
const nextMonth = () => { const d = new Date(); d.setMonth(d.getMonth() + 1); return d.toISOString().slice(0, 7) }
const monthName = (m: string) => new Date(m.slice(0, 7) + '-01T00:00:00').toLocaleDateString(locale(), { month: 'long', year: 'numeric' })
const cash = (n: number | null | undefined, cur: string | null) => (n === null || n === undefined || !cur ? '' : money(n, cur))
const pct = (n: number | null) => (n === null ? '' : new Intl.NumberFormat(locale(), { maximumFractionDigits: 2 }).format(n) + ' %')
const failure = (e: unknown) => (e instanceof ApiError ? [e.message, ...Object.values(e.fieldErrors)].join(' ') : t('Something went wrong.'))
const decimal = (s: string) => (s.trim() === '' ? null : Number(s.replace(',', '.')))
const blank = (): Form => ({ name: '', percent: '5', minimumAmount: '', minimumCurrency: 'USD', rounding: '', startMonth: nextMonth(), categoryIds: [], gradeIds: [] })

/** A general increase of the grade scale: simulated (nothing saved), saved to compare, approved by someone else. */
export function SalaryIncreaseTab() {
  const { can } = useAuth()
  const prepare = can('payroll.increase')
  const approve = can('payroll.increase.approve')
  const ask = useAsk()
  const [grades, setGrades] = useState<Grade[]>([])
  const [form, setForm] = useState<Form>(blank)
  const [editing, setEditing] = useState<Scenario | null>(null)
  const [result, setResult] = useState<Result | null>(null)
  const [list, setList] = useState<Scenario[]>([])
  const [compare, setCompare] = useState<number[]>([])
  const [error, setError] = useState<string | null>(null)
  const [done, setDone] = useState<string | null>(null)

  const load = () => api<Scenario[]>('/api/payroll/increases').then(setList).catch((e) => setError(failure(e)))
  useEffect(() => {
    load()
    if (prepare) api<Grade[]>('/api/grades').then((g) => setGrades(g.filter((x) => x.active))).catch(() => undefined)
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

  const categories = [...new Map(grades.map((g) => [g.categoryId, g.categoryName])).entries()]
  const body = () => ({
    name: form.name.trim() || t('General increase'), percent: decimal(form.percent), startMonth: form.startMonth,
    minimumAmount: decimal(form.minimumAmount), minimumCurrency: form.minimumAmount.trim() ? form.minimumCurrency : null,
    rounding: decimal(form.rounding), categoryIds: form.categoryIds, gradeIds: form.gradeIds,
  })
  const act = async (work: () => Promise<unknown>, message?: string) => {
    setError(null); setDone(null)
    try { await work(); if (message) setDone(message) } catch (e) { setError(failure(e)) }
  }
  const simulate = () => act(async () => setResult(await api<Result>('/api/payroll/increases/simulate', { method: 'POST', body: body() })))
  const save = () => act(async () => {
    const s = editing
      ? await api<Scenario>(`/api/payroll/increases/${editing.id}`, { method: 'PUT', body: body() })
      : await api<Scenario>('/api/payroll/increases', { method: 'POST', body: body() })
    setEditing(s)
    setResult(await api<Result>(`/api/payroll/increases/${s.id}/preview`, { method: 'POST' }))
    await load()
  }, t('Saved. Nobody\'s pay changes until it is approved.'))
  const open = (s: Scenario) => act(async () => {
    if (prepare && (s.status === 'DRAFT' || s.status === 'REJECTED')) {
      setEditing(s)
      setForm({
        name: s.name, percent: String(s.percent), minimumAmount: s.minimumAmount === null ? '' : String(s.minimumAmount),
        minimumCurrency: s.minimumCurrency ?? 'USD', rounding: s.rounding === null ? '' : String(s.rounding),
        startMonth: s.startMonth.slice(0, 7), categoryIds: s.categoryIds, gradeIds: s.gradeIds,
      })
    }
    setResult(await api<Result>(`/api/payroll/increases/${s.id}/preview`, { method: 'POST' }))
    await load()
  })
  const file = (format: 'xlsx' | 'pdf') => act(() => (result?.scenarioId
    ? downloadJob(`/api/payroll/increases/${result.scenarioId}/preview?format=${format}`)
    : downloadJob(`/api/payroll/increases/simulate?format=${format}`, body())))
  const post = (s: Scenario, what: string, message: string, extra?: unknown) =>
    act(async () => { await api(`/api/payroll/increases/${s.id}/${what}`, { method: 'POST', body: extra }); await load() }, message)
  const toggle = (list: number[], id: number) => (list.includes(id) ? list.filter((x) => x !== id) : [...list, id])
  const chosen = list.filter((s) => compare.includes(s.id) && s.summary)

  return (
    <>
      {prepare && (
        <div className="card">
          <h3>{editing ? t('Increase: {a}', { a: editing.name }) : t('General salary increase')}</h3>
          <p className="hint">{t('The grade bases go up by the percentage from the month chosen, and everyone on those grades gets the same amount on their basic salary (Code du travail art. 86). Their salary complement does not change. Nothing changes until another person approves it.')}</p>
          <div className="row grant">
            <label>{t('Name')}<input value={form.name} maxLength={120} placeholder={t('e.g. 5% from January')} onChange={(e) => setForm({ ...form, name: e.target.value })} /></label>
            <label>{t('Increase (%)')}<input inputMode="decimal" className="narrow-input" value={form.percent} onChange={(e) => setForm({ ...form, percent: e.target.value })} /></label>
            <label>{t('From')}<input type="month" value={form.startMonth} onChange={(e) => setForm({ ...form, startMonth: e.target.value })} /></label>
          </div>
          <div className="row grant">
            <label>{t('At least (optional)')}<input inputMode="decimal" className="narrow-input" value={form.minimumAmount} onChange={(e) => setForm({ ...form, minimumAmount: e.target.value })} /></label>
            <label>{t('Currency')}<select value={form.minimumCurrency} onChange={(e) => setForm({ ...form, minimumCurrency: e.target.value })}>
              {['USD', 'CDF', 'XAF'].map((c) => <option key={c}>{c}</option>)}
            </select></label>
            <label>{t('Round the new bases up to')}<select value={form.rounding} onChange={(e) => setForm({ ...form, rounding: e.target.value })}>
              <option value="">{t('No rounding')}</option>
              {['1', '5', '10', '100', '1000'].map((r) => <option key={r} value={r}>{r}</option>)}
            </select></label>
          </div>
          <fieldset className="choices">
            <legend>{t('Grades (none ticked: the whole scale)')}</legend>
            {categories.map(([id, name]) => (
              <div key={id} className="choice-group">
                <label className="check"><input type="checkbox" checked={form.categoryIds.includes(id)}
                  onChange={() => setForm({ ...form, categoryIds: toggle(form.categoryIds, id) })} /> <strong>{name}</strong></label>
                <div className="grade-checks">
                  {grades.filter((g) => g.categoryId === id).map((g) => (
                    <label key={g.id} className="check">
                      <input type="checkbox" checked={form.categoryIds.includes(id) || form.gradeIds.includes(g.id)} disabled={form.categoryIds.includes(id)}
                        onChange={() => setForm({ ...form, gradeIds: toggle(form.gradeIds, g.id) })} /> {g.code} <span className="muted">{money(g.baseSalary, g.currency)}</span>
                    </label>
                  ))}
                </div>
              </div>
            ))}
          </fieldset>
          <ErrorText error={error} />
          {done && <p className="success">{done}</p>}
          <p className="row">
            <Button onClick={simulate}>{t('Simulate')}</Button>
            <Button className="secondary" onClick={save}>{editing ? t('Save changes') : t('Save to compare')}</Button>
            {result && <><Button className="secondary" onClick={() => file('xlsx')}>{t('Excel')}</Button><Button className="secondary" onClick={() => file('pdf')}>{t('PDF')}</Button></>}
            {editing && <Button className="secondary" onClick={() => { setEditing(null); setForm(blank()); setResult(null) }}>{t('New increase')}</Button>}
          </p>
        </div>
      )}
      {!prepare && <><ErrorText error={error} />{done && <p className="success">{done}</p>}</>}
      {result && <IncreaseResult r={result} />}
      <div className="card">
        <h3>{t('Saved increases')}</h3>
        {list.length === 0 ? <p className="muted">{t('None yet: simulate one, then save it to compare it with others.')}</p> : (
          <div className="table-wrap">
            <table className="report">
              <thead><tr>
                <th style={{ width: '2.5rem', minWidth: 0 }}></th><th>{t('Name')}</th><th className="num">{t('Increase')}</th><th>{t('From')}</th><th>{t('Status')}</th>
                <th className="num">{t('People')}</th><th className="num">{t('Cost per month')}</th><th className="num">{t('Cost to 31 December')}</th>
                <th className="num">{t('Back pay')}</th>
              </tr></thead>
              <tbody>
                {list.map((s) => (
                  <tr key={s.id}>
                    <td style={{ minWidth: 0 }}><input type="checkbox" aria-label={t('Compare')} disabled={!s.summary} checked={compare.includes(s.id)} onChange={() => setCompare(toggle(compare, s.id))} /></td>
                    <td style={{ minWidth: '16rem' }}><button type="button" className="link" onClick={() => open(s)}>{s.name}</button>
                      {s.decisionNote && <div className="small muted">{s.decisionNote}</div>}
                      <div className="row" style={{ margin: '6px 0 0' }}>
                      {prepare && s.status === 'DRAFT' && <Button className="secondary small" onClick={() => ask({
                        title: t('Send "{a}" for approval?', { a: s.name }), confirmLabel: t('Send for approval'),
                        message: t('Finance or an administrator approves it. Pay changes only then.'),
                        onConfirm: () => post(s, 'submit', t('Sent for approval.')),
                      })}>{t('Send for approval')}</Button>}
                      {approve && s.status === 'SUBMITTED' && <>
                        <Button className="small" onClick={() => ask({
                          title: t('Approve "{a}"?', { a: s.name }), confirmLabel: t('Approve'),
                          message: t('The grade bases and the basic salaries change from {a}; months already approved get back pay on the next payroll. It can be undone until a later payroll is approved.', { a: monthName(s.startMonth) }),
                          onConfirm: () => post(s, 'approve', t('Approved: the new salaries are recorded.')),
                        })}>{t('Approve')}</Button>
                        <Button className="secondary small" onClick={() => ask({
                          title: t('Reject "{a}"?', { a: s.name }), confirmLabel: t('Reject'), danger: true,
                          fields: [{ label: t('Reason'), multiline: true }],
                          onConfirm: ([note]) => post(s, 'reject', t('Rejected.'), { note }),
                        })}>{t('Reject')}</Button>
                      </>}
                      {approve && s.status === 'APPROVED' && s.canUndo && <Button className="secondary small" onClick={() => ask({
                        title: t('Undo "{a}"?', { a: s.name }), confirmLabel: t('Undo'), danger: true,
                        message: t('The grade bases, the salaries and the back pay go back to what they were.'),
                        onConfirm: () => post(s, 'undo', t('Undone.')),
                      })}>{t('Undo')}</Button>}
                      {prepare && (s.status === 'DRAFT' || s.status === 'REJECTED') && <Button className="secondary small" onClick={() => ask({
                        title: t('Delete "{a}"?', { a: s.name }), confirmLabel: t('Delete'), danger: true,
                        onConfirm: () => act(async () => { await api(`/api/payroll/increases/${s.id}`, { method: 'DELETE' }); if (editing?.id === s.id) { setEditing(null); setForm(blank()) } await load() }),
                      })}>{t('Delete')}</Button>}
                      </div>
                    </td>
                    <td className="num">{pct(s.percent)}</td><td style={{ whiteSpace: 'nowrap' }}>{monthName(s.startMonth)}</td>
                    <td>{STATUS[s.status]}{s.decidedBy && s.status !== 'SUBMITTED' ? <div className="small muted">{s.decidedBy}</div> : null}</td>
                    <td className="num">{s.summary?.map((x) => <div key={x.currency}>{x.people}</div>)}</td>
                    <td className="num">{s.summary?.map((x) => <div key={x.currency}>{money(x.cost, x.currency)}</div>)}</td>
                    <td className="num">{s.summary?.map((x) => <div key={x.currency}>{money(x.costToYearEnd, x.currency)}</div>)}</td>
                    <td className="num">{s.summary?.map((x) => <div key={x.currency}>{x.arrears ? money(x.arrears, x.currency) : ''}</div>)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {chosen.length > 1 && <Compare scenarios={chosen} />}
        {list.some((s) => !s.summary) && <p className="hint">{t('Open an increase to work out its figures; then tick it to compare.')}</p>}
      </div>
    </>
  )
}

function Compare({ scenarios }: { scenarios: Scenario[] }) {
  const currencies = [...new Set(scenarios.flatMap((s) => s.summary!.map((x) => x.currency)))]
  const rows: [string, (x: Total) => string][] = [
    [t('People'), (x) => String(x.people)],
    [t('Gross per month'), (x) => money(x.gross, x.currency)],
    [t('Net per month'), (x) => money(x.net, x.currency)],
    [t('Cost per month'), (x) => money(x.cost, x.currency)],
    [t('% of the cost'), (x) => pct(x.percentOfCost)],
    [t('Cost to 31 December'), (x) => money(x.costToYearEnd, x.currency)],
    [t('Cost over 12 months'), (x) => money(x.costTwelveMonths, x.currency)],
    [t('Back pay'), (x) => (x.arrears ? money(x.arrears, x.currency) : '')],
  ]
  return (
    <>
      <h4>{t('Side by side')}</h4>
      <div className="table-wrap">
        <table className="report">
          <thead><tr><th></th>{scenarios.map((s) => <th key={s.id} className="num">{s.name}<div className="small muted">{pct(s.percent)} · {monthName(s.startMonth)}</div></th>)}</tr></thead>
          <tbody>
            {currencies.flatMap((c) => rows.map(([label, f]) => (
              <tr key={c + label}>
                <td>{label}{currencies.length > 1 ? ` · ${c}` : ''}</td>
                {scenarios.map((s) => { const x = s.summary!.find((y) => y.currency === c); return <td key={s.id} className="num">{x ? f(x) : ''}</td> })}
              </tr>
            )))}
          </tbody>
        </table>
      </div>
    </>
  )
}

function IncreaseResult({ r }: { r: Result }) {
  const problems = r.people.filter((p) => p.problem)
  return (
    <div className="card">
      <h3>{t('{a}: {b} from {c}', { a: r.name, b: pct(r.percent), c: monthName(r.startMonth) })}</h3>
      {r.warnings.map((w) => <p key={w} className="notice">{w}</p>)}
      <div className="table-wrap">
        <table className="report">
          <thead><tr><th></th>{r.totals.map((x) => <th key={x.currency} className="num">{x.currency}</th>)}</tr></thead>
          <tbody>
            {([
              [t('People'), (x: Total) => String(x.people)],
              [t('Monthly cost now'), (x: Total) => money(x.payrollCost, x.currency)],
              [t('Gross per month'), (x: Total) => '+' + money(x.gross, x.currency)],
              [t('Net per month'), (x: Total) => '+' + money(x.net, x.currency)],
              [t('Employer charges per month'), (x: Total) => '+' + money(x.employerCharges, x.currency)],
              [t('Cost per month'), (x: Total) => '+' + money(x.cost, x.currency)],
              [t('% of the cost'), (x: Total) => pct(x.percentOfCost)],
              [t('Cost to 31 December'), (x: Total) => money(x.costToYearEnd, x.currency) + ' (' + t('{n} months', { n: x.monthsToYearEnd }) + ')'],
              [t('Cost over 12 months'), (x: Total) => money(x.costTwelveMonths, x.currency)],
              [t('Back pay'), (x: Total) => (x.arrears ? money(x.arrears, x.currency) : '')],
            ] as [string, (x: Total) => string][]).map(([label, f], i) => (
              <tr key={label} className={i === 5 ? 'total' : undefined}>
                <td>{label}</td>{r.totals.map((x) => <td key={x.currency} className="num">{f(x)}</td>)}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <h4>{t('Grades')}</h4>
      <div className="table-wrap">
        <table className="report">
          <thead><tr>
            <th>{t('Grade')}</th><th>{t('Category')}</th><th className="num">{t('Base now')}</th><th className="num">{t('New base')}</th>
            <th className="num">{t('Increase')}</th><th className="num">{t('Legal minimum')}</th><th className="num">{t('People')}</th>
          </tr></thead>
          <tbody>
            {r.grades.map((g) => (
              <tr key={g.gradeId}>
                <td>{g.code} <span className="muted">{g.name}</span></td><td>{g.categoryName}</td>
                <td className="num">{money(g.oldBase, g.currency)}</td><td className="num">{money(g.newBase, g.currency)}</td>
                <td className="num">+{money(g.raise, g.currency)}</td>
                <td className={g.legalMinimum !== null && g.newBase < g.legalMinimum ? 'num danger-text' : 'num'}>{cash(g.legalMinimum, g.currency)}</td>
                <td className="num">{g.people}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <details>
        <summary>{t('By employee ({n})', { n: r.people.length })}{problems.length ? ` · ${t('{n} to check', { n: problems.length })}` : ''}</summary>
        <div className="table-wrap">
          <table className="report">
            <thead><tr>
              <th>{t('Employee')}</th><th>{t('Grade')}</th><th className="num">{t('Basic now')}</th><th className="num">{t('New basic')}</th>
              <th className="num">{t('Gross now')}</th><th className="num">{t('New gross')}</th><th className="num">{t('Net now')}</th><th className="num">{t('New net')}</th>
              <th className="num">{t('Cost now')}</th><th className="num">{t('New cost')}</th><th className="num">{t('Back pay')}</th>
            </tr></thead>
            <tbody>
              {r.people.map((p) => (
                <tr key={p.userId}>
                  <td>{p.name} <span className="muted">{p.registrationNo ?? ''}</span></td><td>{p.gradeCode}</td>
                  {p.problem ? <td colSpan={9} className="small danger-text">{p.problem}</td> : <>
                    <td className="num">{cash(p.oldBasic, p.currency)}</td><td className="num">{cash(p.newBasic, p.currency)}</td>
                    <td className="num">{cash(p.oldGross, p.currency)}</td><td className="num">{cash(p.newGross, p.currency)}</td>
                    <td className="num">{cash(p.oldNet, p.currency)}</td><td className="num">{cash(p.newNet, p.currency)}</td>
                    <td className="num">{cash(p.oldCost, p.currency)}</td><td className="num">{cash(p.newCost, p.currency)}</td>
                    <td className="num">{p.arrears ? cash(p.arrears, p.currency) : ''}</td>
                  </>}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </details>
      <ul className="hint">{r.notes.map((n) => <li key={n}>{n}</li>)}</ul>
      <p className="small muted">{t('A simulation: nothing was saved.')}</p>
    </div>
  )
}
