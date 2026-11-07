import { useEffect, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { ApiError, api, days, type LeaveBalance, type LeaveRequest, type LeaveType } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'

const STATUS: Record<LeaveRequest['status'], string> = {
  PENDING: 'Waiting for approval', APPROVED: 'Approved', REJECTED: 'Rejected', CANCELLED: 'Cancelled',
}

export default function LeavePage() {
  const { can } = useAuth()
  const canRequest = can('leave.request')
  const canDecide = can('leave.manage') || can('leave.approve.team')
  const [types, setTypes] = useState<LeaveType[]>([])
  const [balances, setBalances] = useState<LeaveBalance[]>([])
  const [mine, setMine] = useState<LeaveRequest[]>([])
  const [pending, setPending] = useState<LeaveRequest[]>([])
  const [form, setForm] = useState({ leaveTypeId: '', startDate: '', endDate: '', reason: '' })
  const [preview, setPreview] = useState<number | null>(null)
  const [notes, setNotes] = useState<Record<number, string>>({})
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)

  const load = () => {
    api<LeaveType[]>('/api/leave/types').then((t) => setTypes(t.filter((x) => x.active))).catch(() => undefined)
    if (canRequest) api<{ balances: LeaveBalance[]; requests: LeaveRequest[] }>('/api/leave/mine')
      .then((m) => { setBalances(m.balances); setMine(m.requests) }).catch((e: Error) => setError(e.message))
    if (canDecide) api<LeaveRequest[]>('/api/leave/requests/to-decide').then(setPending).catch((e: Error) => setError(e.message))
  }
  useEffect(load, []) // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (!form.startDate || !form.endDate || form.endDate < form.startDate) { setPreview(null); return }
    api<{ days: number }>(`/api/leave/working-days?from=${form.startDate}&to=${form.endDate}`)
      .then((r) => setPreview(r.days)).catch(() => setPreview(null))
  }, [form.startDate, form.endDate])

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
    void run(async () => {
      await api('/api/leave/requests', { method: 'POST', body: { ...form, leaveTypeId: Number(form.leaveTypeId) } })
      setForm({ leaveTypeId: form.leaveTypeId, startDate: '', endDate: '', reason: '' })
    }, 'Leave requested. Your manager will be asked to approve it.')
  }

  const selected = types.find((t) => t.id === Number(form.leaveTypeId))

  return (
    <section>
      <div className="row space">
        <h2>Leave</h2>
        {can('leave.manage') && <Link className="button secondary" to="/leave/settings">Leave settings</Link>}
      </div>
      <ErrorText error={error} />
      {message && <p className="success">{message}</p>}

      {canRequest && balances.length > 0 && (
        <div className="tiles">
          {balances.map((b) => (
            <div key={b.leaveTypeId} className="tile">
              <span>{b.leaveType}</span>
              <strong>{days(b.available)}</strong>
              <small>{days(b.granted)} granted · {days(b.taken)} taken{b.pending ? ` · ${days(b.pending)} waiting` : ''}</small>
            </div>
          ))}
        </div>
      )}

      {canDecide && (
        <div className="card">
          <h3>To approve</h3>
          {pending.length === 0 && <p className="muted">Nothing waiting.</p>}
          {pending.map((r) => (
            <div key={r.id} className="mission">
              <p><strong>{r.employeeName}</strong> <span className="muted">{r.registrationNo}</span></p>
              <p>{r.leaveType}: {r.startDate} to {r.endDate} ({days(r.workingDays)})</p>
              {r.reason && <p className="muted">{r.reason}</p>}
              <div className="row">
                <input placeholder="Note (required to reject)" value={notes[r.id] ?? ''}
                  onChange={(e) => setNotes({ ...notes, [r.id]: e.target.value })} />
                <button onClick={() => void run(() => api(`/api/leave/requests/${r.id}/decision`, { method: 'POST', body: { approve: true, note: notes[r.id] } }), 'Leave approved.')}>Approve</button>
                <button className="secondary" onClick={() => void run(() => api(`/api/leave/requests/${r.id}/decision`, { method: 'POST', body: { approve: false, note: notes[r.id] } }), 'Leave rejected.')}>Reject</button>
              </div>
            </div>
          ))}
        </div>
      )}

      {canRequest && (
        <form className="card" onSubmit={submit}>
          <h3>Request leave</h3>
          <div className="grid3">
            <label>Kind
              <select value={form.leaveTypeId} onChange={(e) => setForm({ ...form, leaveTypeId: e.target.value })} required>
                <option value="">Choose</option>
                {types.map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}
              </select>
            </label>
            <label>First day<input type="date" value={form.startDate} onChange={(e) => setForm({ ...form, startDate: e.target.value })} required /></label>
            <label>Last day<input type="date" value={form.endDate} min={form.startDate} onChange={(e) => setForm({ ...form, endDate: e.target.value })} required /></label>
          </div>
          <label>Reason (optional)<input value={form.reason} maxLength={500} onChange={(e) => setForm({ ...form, reason: e.target.value })} /></label>
          {preview !== null && <p className="hint">{days(preview)} of work. Rest days and public holidays are not counted.</p>}
          {selected?.requiresDocument && <p className="hint">Give HR the supporting document (for example a medical certificate).</p>}
          {selected && !selected.paid && <p className="hint">This leave is unpaid.</p>}
          <button type="submit">Send request</button>
        </form>
      )}

      {canRequest && mine.length > 0 && (
        <div className="table-wrap">
          <table>
            <thead><tr><th>Kind</th><th>Dates</th><th>Days</th><th>Status</th><th></th></tr></thead>
            <tbody>
              {mine.map((r) => (
                <tr key={r.id}>
                  <td>{r.leaveType}</td>
                  <td>{r.startDate} to {r.endDate}</td>
                  <td>{days(r.workingDays)}</td>
                  <td>{STATUS[r.status]}{r.decidedBy ? <><br /><span className="muted">by {r.decidedBy}{r.decisionNote ? `: ${r.decisionNote}` : ''}</span></> : null}</td>
                  <td>{r.status === 'PENDING' && <button className="secondary" onClick={() => void run(() => api(`/api/leave/requests/${r.id}/cancel`, { method: 'POST', body: {} }), 'Request cancelled.')}>Cancel</button>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}
