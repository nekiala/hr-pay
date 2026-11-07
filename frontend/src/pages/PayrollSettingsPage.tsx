import { useEffect, useState } from 'react'
import { ApiError, api, type GradeCategory, type Grade, type Occupation } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'

type CategoryWithRaise = GradeCategory & { anniversaryRaisePercent?: number }

export default function PayrollSettingsPage() {
  const { can } = useAuth()
  const editable = can('masterdata.manage')
  const [categories, setCategories] = useState<CategoryWithRaise[]>([])
  const [grades, setGrades] = useState<Grade[]>([])
  const [occupations, setOccupations] = useState<Occupation[]>([])
  const [newGrade, setNewGrade] = useState({ categoryId: '', code: '', name: '', baseSalary: '', currency: 'USD' })
  const [newOccupation, setNewOccupation] = useState({ code: '', name: '' })
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)

  const load = () => {
    api<CategoryWithRaise[]>('/api/leave/categories').then(setCategories).catch((e: Error) => setError(e.message))
    api<Grade[]>('/api/grades').then(setGrades).catch((e: Error) => setError(e.message))
    api<Occupation[]>('/api/occupations').then(setOccupations).catch((e: Error) => setError(e.message))
  }
  useEffect(load, [])

  const run = async (action: () => Promise<unknown>, done: string) => {
    setError(null)
    setMessage(null)
    try {
      await action()
      setMessage(done)
      load()
    } catch (e) {
      setError(e instanceof ApiError ? [e.message, ...Object.values(e.fieldErrors)].join(' ') : 'Something went wrong.')
    }
  }
  const setGrade = (id: number, patch: Partial<Grade>) => setGrades(grades.map((g) => (g.id === id ? { ...g, ...patch } : g)))
  const setCategory = (id: number, patch: Partial<CategoryWithRaise>) => setCategories(categories.map((c) => (c.id === id ? { ...c, ...patch } : c)))
  const setOcc = (id: number, patch: Partial<Occupation>) => setOccupations(occupations.map((o) => (o.id === id ? { ...o, ...patch } : o)))

  return (
    <section>
      <h2>Grades and occupations</h2>
      <p className="muted">Sample values to start with: replace them with your collective agreement's grade table.</p>
      <ErrorText error={error} />
      {message && <p className="success">{message}</p>}

      <div className="card">
        <h3>Anniversary raise by grade category</h3>
        <p className="hint">Each year on a permanent employee's contract anniversary, the basic salary rises by this percentage of their grade's base salary.</p>
        <div className="table-wrap">
          <table>
            <thead><tr><th>Category</th><th>Raise (%)</th><th></th></tr></thead>
            <tbody>
              {categories.map((c) => (
                <tr key={c.id}>
                  <td>{c.name}</td>
                  <td><input type="number" min={0} max={100} step={0.25} className="narrow-input" disabled={!editable}
                    value={c.anniversaryRaisePercent ?? 0} onChange={(e) => setCategory(c.id, { anniversaryRaisePercent: Number(e.target.value) })} /></td>
                  <td>{editable && <button className="secondary" onClick={() => void run(() => api(`/api/grade-categories/${c.id}/raise`, { method: 'PUT', body: { percent: c.anniversaryRaisePercent ?? 0 } }), `${c.code} saved.`)}>Save</button>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      <div className="card">
        <h3>Grades</h3>
        <div className="table-wrap">
          <table>
            <thead><tr><th>Code</th><th>Name</th><th>Category</th><th>Base salary</th><th>Currency</th><th>Active</th><th></th></tr></thead>
            <tbody>
              {grades.map((g) => (
                <tr key={g.id}>
                  <td>{g.code}</td>
                  <td><input value={g.name} disabled={!editable} onChange={(e) => setGrade(g.id, { name: e.target.value })} /></td>
                  <td>
                    <select value={g.categoryId} disabled={!editable} onChange={(e) => setGrade(g.id, { categoryId: Number(e.target.value) })}>
                      {categories.map((c) => <option key={c.id} value={c.id}>{c.code}</option>)}
                    </select>
                  </td>
                  <td><input type="number" min={0} step={0.01} className="narrow-input" disabled={!editable} value={g.baseSalary} onChange={(e) => setGrade(g.id, { baseSalary: Number(e.target.value) })} /></td>
                  <td>
                    <select value={g.currency} disabled={!editable} onChange={(e) => setGrade(g.id, { currency: e.target.value as Grade['currency'] })}>
                      <option>USD</option><option>CDF</option>
                    </select>
                  </td>
                  <td><input type="checkbox" checked={g.active} disabled={!editable} onChange={(e) => setGrade(g.id, { active: e.target.checked })} /></td>
                  <td>{editable && <button className="secondary" onClick={() => void run(() => api(`/api/grades/${g.id}`, { method: 'PUT', body: g }), `${g.code} saved.`)}>Save</button>}</td>
                </tr>
              ))}
              {editable && (
                <tr>
                  <td><input className="narrow-input" placeholder="G4A" value={newGrade.code} onChange={(e) => setNewGrade({ ...newGrade, code: e.target.value.toUpperCase() })} /></td>
                  <td><input placeholder="Name" value={newGrade.name} onChange={(e) => setNewGrade({ ...newGrade, name: e.target.value })} /></td>
                  <td>
                    <select value={newGrade.categoryId} onChange={(e) => setNewGrade({ ...newGrade, categoryId: e.target.value })}>
                      <option value="">Category</option>
                      {categories.map((c) => <option key={c.id} value={c.id}>{c.code}</option>)}
                    </select>
                  </td>
                  <td><input type="number" min={0} step={0.01} className="narrow-input" value={newGrade.baseSalary} onChange={(e) => setNewGrade({ ...newGrade, baseSalary: e.target.value })} /></td>
                  <td>
                    <select value={newGrade.currency} onChange={(e) => setNewGrade({ ...newGrade, currency: e.target.value })}><option>USD</option><option>CDF</option></select>
                  </td>
                  <td></td>
                  <td><button onClick={() => void run(async () => {
                    await api('/api/grades', { method: 'POST', body: { ...newGrade, categoryId: Number(newGrade.categoryId), baseSalary: Number(newGrade.baseSalary), active: true } })
                    setNewGrade({ categoryId: '', code: '', name: '', baseSalary: '', currency: 'USD' })
                  }, 'Grade added.')}>Add</button></td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
        <p className="hint">Changing a grade's base salary does not change anyone's current basic; it is the default for new contracts and the base for anniversary raises.</p>
      </div>

      <div className="card">
        <h3>Occupations</h3>
        <div className="table-wrap">
          <table>
            <thead><tr><th>Code</th><th>Name</th><th>Active</th><th></th></tr></thead>
            <tbody>
              {occupations.map((o) => (
                <tr key={o.id}>
                  <td>{o.code}</td>
                  <td><input value={o.name} disabled={!editable} onChange={(e) => setOcc(o.id, { name: e.target.value })} /></td>
                  <td><input type="checkbox" checked={o.active} disabled={!editable} onChange={(e) => setOcc(o.id, { active: e.target.checked })} /></td>
                  <td>{editable && <button className="secondary" onClick={() => void run(() => api(`/api/occupations/${o.id}`, { method: 'PUT', body: o }), `${o.name} saved.`)}>Save</button>}</td>
                </tr>
              ))}
              {editable && (
                <tr>
                  <td><input className="narrow-input" placeholder="CODE" value={newOccupation.code} onChange={(e) => setNewOccupation({ ...newOccupation, code: e.target.value.toUpperCase() })} /></td>
                  <td><input placeholder="Name" value={newOccupation.name} onChange={(e) => setNewOccupation({ ...newOccupation, name: e.target.value })} /></td>
                  <td></td>
                  <td><button onClick={() => void run(async () => {
                    await api('/api/occupations', { method: 'POST', body: { ...newOccupation, active: true } })
                    setNewOccupation({ code: '', name: '' })
                  }, 'Occupation added.')}>Add</button></td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </div>
    </section>
  )
}
