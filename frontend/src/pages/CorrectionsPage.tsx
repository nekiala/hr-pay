import { useEffect, useState, type FormEvent } from 'react'
import { ApiError, api, time } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'
import { useUnitFilter } from '../components/UnitFilter'

type Request = {
  id: number; userId: number; employeeName: string; registrationNo: string; workDate: string; action: 'ADD' | 'VOID'
  eventType: 'IN' | 'OUT' | null; localTime: string | null; eventId: number | null; eventAt: string | null; reason: string
  status: 'PENDING' | 'PROPOSED' | 'APPLIED' | 'REJECTED' | 'CANCELLED'; requestedBy: string; requestedAt: string
  proposedBy: string | null; proposedAt: string | null; decidedBy: string | null; decidedAt: string | null; decisionNote: string | null
}
type Entry = { id: number; type: 'IN' | 'OUT'; occurredAt: string; source: string }

const STATUS: Record<Request['status'], string> = {
  PENDING: 'Waiting for the head', PROPOSED: 'Proposed, waiting for HR', APPLIED: 'Applied', REJECTED: 'Rejected', CANCELLED: 'Cancelled',
}
const KIND = { IN: 'check-in', OUT: 'check-out' }

const what = (r: Request) => r.action === 'ADD'
  ? `Add the missing ${KIND[r.eventType ?? 'IN']} at ${(r.localTime ?? '').slice(0, 5)}`
  : `Remove the ${r.eventAt ? `entry of ${time(r.eventAt)}` : 'entry'}`

const today = () => new Date().toISOString().slice(0, 10)
const blank = { userId: '', workDate: '', action: 'ADD' as 'ADD' | 'VOID', type: 'IN' as 'IN' | 'OUT', time: '', eventId: '', reason: '' }

/**
 * Attendance corrections: the employee asks for a missing entry to be added or a wrong one removed, a head of their
 * branch proposes it, and HR applies it. A head can also propose one directly for someone in their branch.
 */
export default function CorrectionsPage() {
  const { can } = useAuth()
  const canAsk = can('attendance.correct.request')
  const isHead = can('attendance.correct.propose')
  const isHr = can('attendance.correct')
  const units = useUnitFilter()
  const [mine, setMine] = useState<Request[]>([])
  const [toDecide, setToDecide] = useState<Request[]>([])
  const [form, setForm] = useState(blank)
  const [entries, setEntries] = useState<Entry[]>([])
  const [notes, setNotes] = useState<Record<number, string>>({})
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)

  const load = () => {
    api<Request[]>('/api/corrections/mine').then(setMine).catch((e: Error) => setError(e.message))
    if (isHead || isHr) api<Request[]>('/api/corrections/to-decide').then(setToDecide).catch((e: Error) => setError(e.message))
  }
  useEffect(load, []) // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    setEntries([])
    if (form.action !== 'VOID' || !form.workDate) return
    const who = form.userId ? `userId=${form.userId}&` : ''
    api<Entry[]>(`/api/corrections/entries?${who}date=${form.workDate}`).then(setEntries).catch(() => undefined)
  }, [form.action, form.workDate, form.userId])

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

  const submit = (e: FormEvent) => {
    e.preventDefault()
    const forOther = form.userId !== ''
    void run(async () => {
      await api('/api/corrections', {
        method: 'POST',
        body: {
          userId: forOther ? Number(form.userId) : null, workDate: form.workDate, action: form.action, reason: form.reason,
          type: form.action === 'ADD' ? form.type : null, time: form.action === 'ADD' ? form.time : null,
          eventId: form.action === 'VOID' && form.eventId ? Number(form.eventId) : null,
        },
      })
      setForm({ ...blank, userId: form.userId })
    }, forOther ? 'Correction proposed. HR will apply it.' : 'Request sent. A head of your unit will look at it, then HR applies it.')
  }

  const act = (r: Request, step: 'propose' | 'apply' | 'reject', done: string) =>
    void run(() => api(`/api/corrections/${r.id}/${step}`, { method: 'POST', body: { note: notes[r.id] } }), done)

  const shown = toDecide.filter((r) => units.keep(r.userId))
  const team = isHead && !isHr ? units.people : []

  return (
    <section>
      <h2>Attendance corrections</h2>
      <p className="muted">
        Ask for a forgotten check-in or check-out to be added, or a wrong one removed, up to two months back.
        A head of the unit proposes it and HR applies it; the change then shows on the day with its reason.
      </p>
      <ErrorText error={error} />
      {message && <p className="success">{message}</p>}

      {(isHead || isHr) && (
        <div className="card">
          <div className="row space"><h3>To decide</h3>{units.select}</div>
          {shown.length === 0 && <p className="muted">Nothing waiting.</p>}
          {shown.map((r) => (
            <div key={r.id} className="mission">
              <p><strong>{r.employeeName}</strong> <span className="muted">{r.registrationNo} · {units.unitName(r.userId)}</span></p>
              <p>{r.workDate}: {what(r)}</p>
              <p className="muted">{r.reason}</p>
              <p className="muted">
                {STATUS[r.status]}. Asked by {r.requestedBy}
                {r.proposedBy && `, proposed by ${r.proposedBy}`}{r.decisionNote && ` (${r.decisionNote})`}.
              </p>
              <div className="row">
                <input placeholder="Note (required to reject)" value={notes[r.id] ?? ''} maxLength={300}
                  onChange={(e) => setNotes({ ...notes, [r.id]: e.target.value })} />
                {isHr
                  ? <button onClick={() => act(r, 'apply', 'Correction applied.')}>Apply</button>
                  : <button onClick={() => act(r, 'propose', 'Proposed to HR.')}>Propose to HR</button>}
                <button className="secondary" onClick={() => act(r, 'reject', 'Request rejected.')}>Reject</button>
              </div>
            </div>
          ))}
        </div>
      )}

      {(canAsk || team.length > 0) && (
        <form className="card" onSubmit={submit}>
          <h3>{team.length > 0 ? 'Ask or propose a correction' : 'Ask for a correction'}</h3>
          <div className="grid3">
            {team.length > 0 && (
              <label>For
                <select value={form.userId} onChange={(e) => setForm({ ...form, userId: e.target.value, eventId: '' })}>
                  {canAsk && <option value="">Myself</option>}
                  {!canAsk && <option value="">Choose a person</option>}
                  {team.map((p) => <option key={p.userId} value={p.userId}>{p.name} ({p.registrationNo})</option>)}
                </select>
              </label>
            )}
            <label>Day<input type="date" value={form.workDate} max={today()} required
              onChange={(e) => setForm({ ...form, workDate: e.target.value, eventId: '' })} /></label>
            <label>What
              <select value={form.action} onChange={(e) => setForm({ ...form, action: e.target.value as 'ADD' | 'VOID' })}>
                <option value="ADD">Add a missing entry</option>
                <option value="VOID">Remove a wrong entry</option>
              </select>
            </label>
          </div>
          {form.action === 'ADD' ? (
            <div className="grid3">
              <label>Entry
                <select value={form.type} onChange={(e) => setForm({ ...form, type: e.target.value as 'IN' | 'OUT' })}>
                  <option value="IN">Check-in</option>
                  <option value="OUT">Check-out</option>
                </select>
              </label>
              <label>Time<input type="time" value={form.time} required onChange={(e) => setForm({ ...form, time: e.target.value })} /></label>
            </div>
          ) : (
            <label>Entry to remove
              <select value={form.eventId} required onChange={(e) => setForm({ ...form, eventId: e.target.value })}>
                <option value="">{form.workDate ? (entries.length ? 'Choose' : 'No entries that day') : 'Choose the day first'}</option>
                {entries.map((x) => <option key={x.id} value={x.id}>{x.type === 'IN' ? 'Check-in' : 'Check-out'} at {time(x.occurredAt)}</option>)}
              </select>
            </label>
          )}
          <label>Why<input value={form.reason} required maxLength={500} placeholder="For example: the badge reader was down"
            onChange={(e) => setForm({ ...form, reason: e.target.value })} /></label>
          <button type="submit" disabled={!canAsk && form.userId === ''}>{form.userId ? 'Propose to HR' : 'Send request'}</button>
        </form>
      )}

      {mine.length > 0 && (
        <div className="table-wrap">
          <h3>My requests</h3>
          <table>
            <thead><tr><th>Day</th><th>Correction</th><th>Why</th><th>Status</th><th></th></tr></thead>
            <tbody>
              {mine.map((r) => (
                <tr key={r.id}>
                  <td>{r.workDate}</td>
                  <td>{what(r)}</td>
                  <td>{r.reason}</td>
                  <td>{STATUS[r.status]}{r.decidedBy && r.status !== 'CANCELLED'
                    ? <><br /><span className="muted">by {r.decidedBy}{r.decisionNote ? `: ${r.decisionNote}` : ''}</span></> : null}</td>
                  <td>{r.status === 'PENDING' && (
                    <button className="secondary" onClick={() => void run(() => api(`/api/corrections/${r.id}/cancel`, { method: 'POST' }), 'Request cancelled.')}>Cancel</button>
                  )}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}
