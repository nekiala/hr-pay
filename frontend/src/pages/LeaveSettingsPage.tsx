import { useEffect, useState } from 'react'
import { ApiError, api, type GradeCategory, type LeaveType } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'
import { Button } from '../components/Button'
import { t } from '../i18n'

export default function LeaveSettingsPage() {
  const { can } = useAuth()
  const [categories, setCategories] = useState<GradeCategory[]>([])
  const [types, setTypes] = useState<LeaveType[]>([])
  const [added, setAdded] = useState({ code: '', name: '', annualLeaveDays: 20, active: true })
  const [year, setYear] = useState(new Date().getFullYear())
  const [seniority, setSeniority] = useState({ seniorityYears: 5, seniorityDays: 1 })
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)

  const load = () => {
    api<GradeCategory[]>('/api/leave/categories').then(setCategories).catch((e: Error) => setError(e.message))
    api<LeaveType[]>('/api/leave/types').then(setTypes).catch((e: Error) => setError(e.message))
    api<Record<string, string>>('/api/leave/settings').then((s) => setSeniority({
      seniorityYears: Number(s.LEAVE_SENIORITY_YEARS ?? 0), seniorityDays: Number(s.LEAVE_SENIORITY_DAYS ?? 0),
    })).catch(() => undefined)
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
        <div className="row grant">
          <label>{t('Year')}<input type="number" className="narrow-input" value={year} onChange={(e) => setYear(Number(e.target.value))} /></label>
          <Button onClick={() => run(() => api<Record<string, number>>(`/api/leave/grant-annual?year=${year}`, { method: 'POST' }),
            (r) => {
              const x = r as Record<string, number>
              return t('Annual leave {a}: granted to {b}, already granted to {c}, {d} without a grade category.', { a: year, b: x.granted, c: x.alreadyGranted, d: x.withoutCategory })
            })}>{t('Grant annual leave for')}{' '}{year}</Button>
        </div>
        <p className="hint">{t('Gives each active employee the days of their category, plus the seniority days below, prorated for people hired during the year. Running it again skips those already granted. Unused days carry over.')}</p>
      </div>

      <div className="card">
        <h3>{t('Seniority')}</h3>
        <div className="row grant">
          <label>{t('Extra days')}<input type="number" min={0} step={0.5} className="narrow-input" value={seniority.seniorityDays}
            onChange={(e) => setSeniority({ ...seniority, seniorityDays: Number(e.target.value) })} /></label>
          <label>{t('for every')}<input type="number" min={0} max={40} className="narrow-input" value={seniority.seniorityYears}
            onChange={(e) => setSeniority({ ...seniority, seniorityYears: Number(e.target.value) })} /></label>
          <span>{t('full years of service on 1 January')}</span>
          <Button className="secondary" onClick={() => run(() => api('/api/leave/settings', { method: 'PUT', body: seniority }), t('Seniority rule saved. It applies to the next grant.'))}>{t('Save')}</Button>
        </div>
        <p className="hint">{t('Counted from the hire date. Set the years to 0 to give no seniority days. Sample rule to check against the Labour Code and your collective agreement.')}</p>
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
