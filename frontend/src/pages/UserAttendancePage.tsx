import { useEffect, useState, type FormEvent } from 'react'
import { Link, useParams } from 'react-router-dom'
import { ApiError, api, type User } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'
import MonthView from '../components/MonthView'

export default function UserAttendancePage() {
  const { id } = useParams()
  const { can, me } = useAuth()
  const [user, setUser] = useState<User | null>(null)
  const [version, setVersion] = useState(0)
  const [entry, setEntry] = useState({ date: new Date().toISOString().slice(0, 10), time: '08:00', type: 'IN', note: '' })
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  useEffect(() => { api<User>(`/api/users/${id}`).then(setUser).catch(() => undefined) }, [id])

  const canCorrect = can('attendance.correct') && me?.id !== Number(id)

  const run = async (action: () => Promise<unknown>, done: string) => {
    setError(null)
    setMessage(null)
    try {
      await action()
      setMessage(done)
      setVersion((v) => v + 1)
    } catch (e) {
      setError(e instanceof ApiError ? [e.message, ...Object.values(e.fieldErrors)].join(' ') : 'Something went wrong.')
    }
  }

  const add = (e: FormEvent) => {
    e.preventDefault()
    void run(async () => {
      await api(`/api/attendance/users/${id}/events`, {
        method: 'POST',
        body: { type: entry.type, at: `${entry.date}T${entry.time}:00`, workDate: entry.date, note: entry.note },
      })
      setEntry({ ...entry, note: '' })
    }, 'Entry added.')
  }

  const voidEntry = (entryId: number) => {
    const reason = prompt('Why is this entry wrong?')
    if (!reason?.trim()) return
    void run(() => api(`/api/attendance/events/${entryId}/void`, { method: 'POST', body: { reason } }), 'Entry voided.')
  }

  return (
    <section>
      <p><Link to={`/users/${id}`}>← Back to employee</Link></p>
      <h2>Attendance{user ? `: ${user.firstName} ${user.lastName}` : ''}</h2>
      {canCorrect && (
        <form className="card" onSubmit={add}>
          <h3>Add a missing entry</h3>
          <p className="hint">Entries are never edited. Void a wrong one with a reason, then add the right one. Both are kept in the audit log.</p>
          <div className="row">
            <input type="date" value={entry.date} onChange={(e) => setEntry({ ...entry, date: e.target.value })} required />
            <input type="time" value={entry.time} onChange={(e) => setEntry({ ...entry, time: e.target.value })} required />
            <select value={entry.type} onChange={(e) => setEntry({ ...entry, type: e.target.value })}>
              <option value="IN">Arrival</option>
              <option value="OUT">End of day</option>
            </select>
            <input placeholder="Reason, for example phone was off" value={entry.note}
              onChange={(e) => setEntry({ ...entry, note: e.target.value })} required maxLength={500} />
            <button type="submit">Add</button>
          </div>
        </form>
      )}
      <ErrorText error={error} />
      {message && <p className="success">{message}</p>}
      <MonthView source={`/api/attendance/users/${id}/month`} onVoid={canCorrect ? voidEntry : undefined} version={version} />
    </section>
  )
}
