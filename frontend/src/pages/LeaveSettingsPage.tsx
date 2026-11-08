import { useEffect, useState } from 'react'
import { ApiError, api, days, type GradeCategory, type LeaveLapse, type LeaveType } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'
import { Button } from '../components/Button'
import { useAsk } from '../components/Dialogs'
import { t } from '../i18n'

export default function LeaveSettingsPage() {
  const { can } = useAuth()
  const [categories, setCategories] = useState<GradeCategory[]>([])
  const [types, setTypes] = useState<LeaveType[]>([])
  const [added, setAdded] = useState({ code: '', name: '', annualLeaveDays: 20, active: true })
  const [seniority, setSeniority] = useState({ seniorityYears: 5, seniorityDays: 1 })
  const [pay, setPay] = useState({ annualPaid: true, carryPercent: 50, carryYears: 1 })
  const [closing, setClosing] = useState(new Date().getFullYear() - 1)
  const [lapses, setLapses] = useState<LeaveLapse[] | null>(null)
  const ask = useAsk()
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)

  const load = () => {
    api<GradeCategory[]>('/api/leave/categories').then(setCategories).catch((e: Error) => setError(e.message))
    api<LeaveType[]>('/api/leave/types').then(setTypes).catch((e: Error) => setError(e.message))
    api<Record<string, string>>('/api/leave/settings').then((s) => {
      setSeniority({ seniorityYears: Number(s.LEAVE_SENIORITY_YEARS ?? 0), seniorityDays: Number(s.LEAVE_SENIORITY_DAYS ?? 0) })
      setPay({ annualPaid: s.LEAVE_ANNUAL_PAID !== 'false', carryPercent: Number(s.LEAVE_CARRY_PERCENT ?? 50), carryYears: Number(s.LEAVE_CARRY_YEARS ?? 1) })
    }).catch(() => undefined)
  }
  useEffect(load, [])

  const run = async (action: () => Promise<unknown>, done: string | ((r: unknown) => string)) => {
    setError(null)
    setMessage(null)
    try {
      const r = await action()
      setMessage(typeof done === 'string' ? done : done(r))
      load()
      return true
    } catch (e) {
      setError(e instanceof ApiError ? [e.message, ...Object.values(e.fieldErrors)].join(' ') : t('Something went wrong.'))
      return false
    }
  }

  const setCategory = (id: number, patch: Partial<GradeCategory>) =>
    setCategories(categories.map((c) => (c.id === id ? { ...c, ...patch } : c)))
  const setType = (id: number, patch: Partial<LeaveType>) => setTypes(types.map((tItem) => (tItem.id === id ? { ...tItem, ...patch } : tItem)))
  const masterdata = can('masterdata.manage')

  return (
    <section>
      <h2>{t('Leave settings')}</h2>
      <p className="muted">{t('Sample values to start with. Check them against the Labour Code and your collective agreement.')}</p>
      <ErrorText error={error} />
      {message && <p className="success">{message}</p>}

      <div className="card">
        <h3>{t('Annual leave by grade category')}</h3>
        <div className="table-wrap">
          <table>
            <thead><tr><th>{t('Code')}</th><th>{t('Name')}</th><th>{t('Days per year')}</th><th>{t('Active')}</th><th></th></tr></thead>
            <tbody>
              {categories.map((c) => (
                <tr key={c.id}>
                  <td>{c.code}</td>
                  <td><input value={c.name} disabled={!masterdata} onChange={(e) => setCategory(c.id, { name: e.target.value })} /></td>
                  <td><input type="number" min={0} step={0.5} className="narrow-input" value={c.annualLeaveDays} disabled={!masterdata}
                    onChange={(e) => setCategory(c.id, { annualLeaveDays: Number(e.target.value) })} /></td>
                  <td><input type="checkbox" checked={c.active} disabled={!masterdata} onChange={(e) => setCategory(c.id, { active: e.target.checked })} /></td>
                  <td>{masterdata && <Button className="secondary" onClick={() => run(() => api(`/api/leave/categories/${c.id}`, { method: 'PUT', body: c }), t("{a} saved.", { a: c.code }))}>{t('Save')}</Button>}</td>
                </tr>
              ))}
              {masterdata && (
                <tr>
                  <td><input placeholder="CAT4" className="narrow-input" value={added.code} onChange={(e) => setAdded({ ...added, code: e.target.value.toUpperCase() })} /></td>
                  <td><input placeholder={t('Name')} value={added.name} onChange={(e) => setAdded({ ...added, name: e.target.value })} /></td>
                  <td><input type="number" min={0} step={0.5} className="narrow-input" value={added.annualLeaveDays} onChange={(e) => setAdded({ ...added, annualLeaveDays: Number(e.target.value) })} /></td>
                  <td></td>
                  <td><Button onClick={() => run(async () => {
                    await api('/api/leave/categories', { method: 'POST', body: added })
                    setAdded({ code: '', name: '', annualLeaveDays: 20, active: true })
                  }, t('Category added.'))}>{t('Add')}</Button></td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
        <p className="hint">{t('Annual leave is earned month by month: each closed monthly payroll credits the days of the person\'s category ÷ 12, never below the legal floor (DRC art. 141: 1 working day per full month, 1.5 under 18), plus the seniority days below, for the share of the month in service (unpaid leave and unjustified absence do not count). The payslip shows the days earned and the balance. Unused days carry over within the limits below.')}</p>
      </div>

      <div className="card">
        <h3>{t('Seniority')}</h3>
        <div className="row grant">
          <label>{t('Extra days')}<input type="number" min={0} step={0.5} className="narrow-input" value={seniority.seniorityDays}
            onChange={(e) => setSeniority({ ...seniority, seniorityDays: Number(e.target.value) })} /></label>
          <label>{t('for every')}<input type="number" min={0} max={40} className="narrow-input" value={seniority.seniorityYears}
            onChange={(e) => setSeniority({ ...seniority, seniorityYears: Number(e.target.value) })} /></label>
          <span>{t('full years of service on 1 January')}</span>
          <Button className="secondary" onClick={() => run(() => api('/api/leave/settings', { method: 'PUT', body: { ...seniority, ...pay } }), t('Seniority rule saved. It applies to the next grant.'))}>{t('Save')}</Button>
        </div>
        <p className="hint">{t('Counted from the hire date. Set the years to 0 to give no seniority days. Sample rule to check against the Labour Code and your collective agreement.')}</p>
      </div>

      <div className="card">
        <h3>{t('Annual leave pay and carry-over')}</h3>
        <label className="check"><input type="checkbox" checked={pay.annualPaid} onChange={(e) => setPay({ ...pay, annualPaid: e.target.checked })} />
          {t('The company pays annual leave')}</label>
        {pay.annualPaid
          ? <p className="hint">{t('Leave days are paid on the payslip, the leave allowance is worked out on the Leave pay page and paid before the leave, and unused days are paid out at departure.')}</p>
          : <p className="notice">{t('The DRC Labour Code (art. 142) and the Congo Labour Code (art. 122) both require paid annual leave. Turn this off only if your situation allows it, after advice. Annual leave days are then unpaid and not paid out at departure.')}</p>}
        <div className="row grant">
          <label>{t('Share of a year\'s leave that can be carried (%)')}<input type="number" min={0} max={100} className="narrow-input" value={pay.carryPercent}
            onChange={(e) => setPay({ ...pay, carryPercent: Number(e.target.value) })} /></label>
          <label>{t('Years it can still be taken')}<input type="number" min={0} max={10} className="narrow-input" value={pay.carryYears}
            onChange={(e) => setPay({ ...pay, carryYears: Number(e.target.value) })} /></label>
          <Button className="secondary" onClick={() => run(() => api('/api/leave/settings', { method: 'PUT', body: { ...seniority, ...pay } }), t('Annual leave settings saved.'))}>{t('Save')}</Button>
        </div>
        <p className="hint">{t('DRC (art. 140): only half of the leave can be carried, over two years: 50% for 1 year. Republic of the Congo (art. 120): the right lapses after 3 years: 100% for 3 years. To confirm with your payroll expert.')}</p>
        <div className="row grant">
          <label>{t('Leave year')}<input type="number" className="narrow-input" value={closing} onChange={(e) => { setClosing(Number(e.target.value)); setLapses(null) }} /></label>
          <Button className="secondary" onClick={() => run(async () => setLapses(await api<LeaveLapse[]>(`/api/leave/close-year?year=${closing}`)), t('Here is what would lapse.'))}>{t('Show what lapses')}</Button>
          {lapses && lapses.length > 0 && (
            <Button onClick={() => ask({
              title: t('Close the leave year {a}?', { a: closing }), confirmLabel: t('Close the year'), danger: true,
              message: t('{a} days lapse for {b} people. The employee cannot give up their leave (DRC art. 140): plan their leave before closing if you can.', {
                a: lapses.reduce((n, l) => n + Number(l.lapsing), 0), b: new Set(lapses.map((l) => l.userId)).size,
              }),
              onConfirm: () => run(async () => { await api(`/api/leave/close-year?year=${closing}`, { method: 'POST' }); setLapses(null) }, t('Leave year closed.')),
            })}>{t('Close the leave year')}</Button>
          )}
        </div>
        {lapses && lapses.length === 0 && <p className="muted">{t('Nothing lapses.')}</p>}
        {lapses && lapses.length > 0 && (
          <div className="table-wrap">
            <table>
              <thead><tr><th>{t('Employee')}</th><th>{t('Leave year')}</th><th>{t('Left')}</th><th>{t('Lapses')}</th><th>{t('Kept')}</th></tr></thead>
              <tbody>
                {lapses.map((l) => (
                  <tr key={`${l.userId}-${l.leaveYear}`}>
                    <td>{l.employeeName} <span className="muted">{l.registrationNo}</span></td>
                    <td>{l.leaveYear}</td><td>{days(l.left)}</td><td>{days(l.lapsing)}</td><td>{days(l.kept)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      <div className="card">
        <h3>{t('Kinds of leave')}</h3>
        <div className="table-wrap">
          <table>
            <thead><tr><th>{t('Name')}</th><th>{t('Share of pay (%)')}</th><th>{t('Uses balance')}</th><th>{t('Needs document')}</th><th>{t('Max days')}</th><th>{t('Active')}</th><th></th></tr></thead>
            <tbody>
              {types.map((tItem) => (
                <tr key={tItem.id}>
                  <td><input value={tItem.name} onChange={(e) => setType(tItem.id, { name: e.target.value })} /></td>
                  <td><input type="number" min={0} max={100} step={0.01} className="narrow-input" value={tItem.payPercent}
                    onChange={(e) => setType(tItem.id, { payPercent: Number(e.target.value) })} /></td>
                  <td><input type="checkbox" checked={tItem.usesBalance} onChange={(e) => setType(tItem.id, { usesBalance: e.target.checked })} /></td>
                  <td><input type="checkbox" checked={tItem.requiresDocument} onChange={(e) => setType(tItem.id, { requiresDocument: e.target.checked })} /></td>
                  <td><input type="number" min={1} className="narrow-input" value={tItem.maxDays ?? ''} placeholder="none"
                    onChange={(e) => setType(tItem.id, { maxDays: e.target.value === '' ? null : Number(e.target.value) })} /></td>
                  <td><input type="checkbox" checked={tItem.active} onChange={(e) => setType(tItem.id, { active: e.target.checked })} /></td>
                  <td><Button className="secondary" onClick={() => run(() => api(`/api/leave/types/${tItem.id}`, { method: 'PUT', body: { ...tItem, paid: undefined } }), t("{a} saved.", { a: tItem.name }))}>{t('Save')}</Button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <p className="hint">{t('100% keeps the full day\'s pay, 0% makes the leave unpaid. A kind that needs a document is approved by the manager, then counts only once HR has checked the document.')}</p>
      </div>
    </section>
  )
}
