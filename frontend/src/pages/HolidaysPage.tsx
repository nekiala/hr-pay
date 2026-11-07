import { useEffect, useState, type FormEvent } from 'react'
import { api, type HolidayOccurrence } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'

const fmt = (d: string) => new Date(d + 'T00:00:00').toLocaleDateString(undefined, { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' })

export default function HolidaysPage() {
  const { can } = useAuth()
  const editable = can('holiday.manage')
  const [year, setYear] = useState(new Date().getFullYear())
  const [items, setItems] = useState<HolidayOccurrence[]>([])
  const [editing, setEditing] = useState<HolidayOccurrence | null>(null)
  const [observed, setObserved] = useState('')
  const [announcement, setAnnouncement] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [newName, setNewName] = useState('')
  const [newDate, setNewDate] = useState('')

  const load = () => api<HolidayOccurrence[]>(`/api/holidays?year=${year}`).then(setItems).catch((e: Error) => setError(e.message))
  useEffect(() => { void load() }, [year])

  const startEdit = (h: HolidayOccurrence) => {
    setEditing(h)
    setObserved(h.observedDate)
    setAnnouncement(h.announcementRef ?? '')
    setError(null)
  }

  const saveMove = async (e: FormEvent) => {
    e.preventDefault()
    if (!editing) return
    try {
      await api(`/api/holidays/occurrences/${editing.id}`, { method: 'PUT', body: { observedDate: observed, announcementRef: announcement || null } })
      setEditing(null)
      await load()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not save.')
    }
  }

  const addHoliday = async (e: FormEvent) => {
    e.preventDefault()
    const [, m, d] = newDate.split('-').map(Number)
    try {
      await api('/api/holidays/definitions', { method: 'POST', body: { name: newName, usualMonth: m, usualDay: d, paid: true, active: true } })
      setNewName('')
      setNewDate('')
      await load()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not add the holiday.')
    }
  }

  return (
    <section>
      <div className="row between">
        <h2>Public holidays</h2>
        <div className="row">
          <button className="secondary" onClick={() => setYear(year - 1)}>{year - 1}</button>
          <strong>{year}</strong>
          <button className="secondary" onClick={() => setYear(year + 1)}>{year + 1}</button>
        </div>
      </div>
      <p className="muted">When the government moves a holiday, set its observed date. Attendance and payroll use the observed date only.</p>
      <ErrorText error={error} />
      <div className="table-wrap">
        <table>
          <thead><tr><th>Holiday</th><th>Official date</th><th>Observed date</th><th>Announcement</th>{editable && <th />}</tr></thead>
          <tbody>
            {items.map((h) => (
              <tr key={h.id}>
                <td>{h.name}</td>
                <td>{fmt(h.officialDate)}</td>
                <td>{h.moved ? <strong>{fmt(h.observedDate)}</strong> : fmt(h.observedDate)}</td>
                <td className="small">{h.announcementRef ?? '—'}{h.setBy ? <span className="muted"> · {h.setBy}</span> : null}</td>
                {editable && <td><button className="link" onClick={() => startEdit(h)}>Change date</button></td>}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {editing && (
        <form className="card narrow" style={{ marginTop: 16 }} onSubmit={saveMove}>
          <h3>{editing.name} {editing.year}</h3>
          <label>Observed date<input type="date" value={observed} onChange={(e) => setObserved(e.target.value)} required /></label>
          <label>Government announcement (reference)<input value={announcement} onChange={(e) => setAnnouncement(e.target.value)} placeholder="e.g. Communiqué du Ministère, 25 June" /></label>
          <p className="hint">If the old or new date falls in a month whose payroll is closed, the pay difference is worked out and added to the next payroll that is not approved yet (Payroll › Holiday changes).</p>
          <div className="row"><button type="submit">Save</button><button type="button" className="secondary" onClick={() => setEditing(null)}>Cancel</button></div>
        </form>
      )}
      {editable && (
        <form className="card narrow" style={{ marginTop: 16 }} onSubmit={addHoliday}>
          <h3>Add a recurring holiday</h3>
          <div className="grid2">
            <label>Name<input value={newName} onChange={(e) => setNewName(e.target.value)} required /></label>
            <label>Usual date (any year)<input type="date" value={newDate} onChange={(e) => setNewDate(e.target.value)} required /></label>
          </div>
          <button type="submit" className="secondary">Add holiday</button>
        </form>
      )}
    </section>
  )
}
