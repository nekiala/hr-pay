import { useEffect, useState } from 'react'
import { ApiError, api, type GradeCategory, type LeaveType } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'

export default function LeaveSettingsPage() {
  const { can } = useAuth()
  const [categories, setCategories] = useState<GradeCategory[]>([])
  const [types, setTypes] = useState<LeaveType[]>([])
  const [added, setAdded] = useState({ code: '', name: '', annualLeaveDays: 20, active: true })
  const [year, setYear] = useState(new Date().getFullYear())
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)

  const load = () => {
    api<GradeCategory[]>('/api/leave/categories').then(setCategories).catch((e: Error) => setError(e.message))
    api<LeaveType[]>('/api/leave/types').then(setTypes).catch((e: Error) => setError(e.message))
  }
  useEffect(load, [])

  const run = async (action: () => Promise<unknown>, done: string | ((r: unknown) => string)) => {
    setError(null)
    setMessage(null)
    try {
      const r = await action()
      setMessage(typeof done === 'string' ? done : done(r))
      load()
    } catch (e) {
      setError(e instanceof ApiError ? [e.message, ...Object.values(e.fieldErrors)].join(' ') : 'Something went wrong.')
    }
  }

  const setCategory = (id: number, patch: Partial<GradeCategory>) =>
    setCategories(categories.map((c) => (c.id === id ? { ...c, ...patch } : c)))
  const setType = (id: number, patch: Partial<LeaveType>) => setTypes(types.map((t) => (t.id === id ? { ...t, ...patch } : t)))
  const masterdata = can('masterdata.manage')

  return (
    <section>
      <h2>Leave settings</h2>
      <p className="muted">Sample values to start with. Check them against the Labour Code and your collective agreement.</p>
      <ErrorText error={error} />
      {message && <p className="success">{message}</p>}

      <div className="card">
        <h3>Annual leave by grade category</h3>
        <div className="table-wrap">
          <table>
            <thead><tr><th>Code</th><th>Name</th><th>Days per year</th><th>Active</th><th></th></tr></thead>
            <tbody>
              {categories.map((c) => (
                <tr key={c.id}>
                  <td>{c.code}</td>
                  <td><input value={c.name} disabled={!masterdata} onChange={(e) => setCategory(c.id, { name: e.target.value })} /></td>
                  <td><input type="number" min={0} step={0.5} className="narrow-input" value={c.annualLeaveDays} disabled={!masterdata}
                    onChange={(e) => setCategory(c.id, { annualLeaveDays: Number(e.target.value) })} /></td>
                  <td><input type="checkbox" checked={c.active} disabled={!masterdata} onChange={(e) => setCategory(c.id, { active: e.target.checked })} /></td>
                  <td>{masterdata && <button className="secondary" onClick={() => void run(() => api(`/api/leave/categories/${c.id}`, { method: 'PUT', body: c }), `${c.code} saved.`)}>Save</button>}</td>
                </tr>
              ))}
              {masterdata && (
                <tr>
                  <td><input placeholder="CAT4" className="narrow-input" value={added.code} onChange={(e) => setAdded({ ...added, code: e.target.value.toUpperCase() })} /></td>
                  <td><input placeholder="Name" value={added.name} onChange={(e) => setAdded({ ...added, name: e.target.value })} /></td>
                  <td><input type="number" min={0} step={0.5} className="narrow-input" value={added.annualLeaveDays} onChange={(e) => setAdded({ ...added, annualLeaveDays: Number(e.target.value) })} /></td>
                  <td></td>
                  <td><button onClick={() => void run(async () => {
                    await api('/api/leave/categories', { method: 'POST', body: added })
                    setAdded({ code: '', name: '', annualLeaveDays: 20, active: true })
                  }, 'Category added.')}>Add</button></td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
        <div className="row grant">
          <label>Year<input type="number" className="narrow-input" value={year} onChange={(e) => setYear(Number(e.target.value))} /></label>
          <button onClick={() => void run(() => api<Record<string, number>>(`/api/leave/grant-annual?year=${year}`, { method: 'POST' }),
            (r) => {
              const x = r as Record<string, number>
              return `Annual leave ${year}: granted to ${x.granted}, already granted to ${x.alreadyGranted}, ${x.withoutCategory} without a grade category.`
            })}>Grant annual leave for {year}</button>
        </div>
        <p className="hint">Gives each active employee the days of their category, prorated for people hired during the year. Running it again skips those already granted. Unused days carry over.</p>
      </div>

      <div className="card">
        <h3>Kinds of leave</h3>
        <div className="table-wrap">
          <table>
            <thead><tr><th>Name</th><th>Paid</th><th>Uses balance</th><th>Needs document</th><th>Max days</th><th>Active</th><th></th></tr></thead>
            <tbody>
              {types.map((t) => (
                <tr key={t.id}>
                  <td><input value={t.name} onChange={(e) => setType(t.id, { name: e.target.value })} /></td>
                  <td><input type="checkbox" checked={t.paid} onChange={(e) => setType(t.id, { paid: e.target.checked })} /></td>
                  <td><input type="checkbox" checked={t.usesBalance} onChange={(e) => setType(t.id, { usesBalance: e.target.checked })} /></td>
                  <td><input type="checkbox" checked={t.requiresDocument} onChange={(e) => setType(t.id, { requiresDocument: e.target.checked })} /></td>
                  <td><input type="number" min={1} className="narrow-input" value={t.maxDays ?? ''} placeholder="none"
                    onChange={(e) => setType(t.id, { maxDays: e.target.value === '' ? null : Number(e.target.value) })} /></td>
                  <td><input type="checkbox" checked={t.active} onChange={(e) => setType(t.id, { active: e.target.checked })} /></td>
                  <td><button className="secondary" onClick={() => void run(() => api(`/api/leave/types/${t.id}`, { method: 'PUT', body: t }), `${t.name} saved.`)}>Save</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </section>
  )
}
