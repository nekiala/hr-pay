import { useEffect, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { ApiError, api, days, fileToBase64, type LeaveBalance, type LeaveDocument, type LeaveRequest, type LeaveType } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'
import { useUnitFilter } from '../components/UnitFilter'

const STATUS: Record<LeaveRequest['status'], string> = {
  PENDING: 'Waiting for approval', AWAITING_HR: 'Approved, HR to check the document', APPROVED: 'Approved',
  REJECTED: 'Rejected', CANCELLED: 'Cancelled',
}
const HALF = { AM: 'morning', PM: 'afternoon' }

const dates = (r: LeaveRequest) =>
  r.halfDay ? `${r.startDate} (${HALF[r.halfDay]})` : r.startDate === r.endDate ? r.startDate : `${r.startDate} to ${r.endDate}`

/** The supporting documents of one request: links to open them, and a file picker when more can be added. */
function Documents({ request, canAdd, onChange }: { request: LeaveRequest; canAdd: boolean; onChange: () => void }) {
  const [docs, setDocs] = useState<LeaveDocument[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const load = () => api<LeaveDocument[]>(`/api/leave/requests/${request.id}/documents`).then(setDocs).catch((e: Error) => setError(e.message))
  useEffect(() => { if (request.documents > 0) load() }, [request.id, request.documents]) // eslint-disable-line react-hooks/exhaustive-deps

  const upload = async (file: File | undefined) => {
    if (!file) return
    setError(null)
    if (file.size > 5 * 1024 * 1024) { setError('The file must be at most 5 MB.'); return }
    try {
      await api(`/api/leave/requests/${request.id}/documents`, {
        method: 'POST', body: { fileName: file.name, contentType: file.type || 'application/octet-stream', base64: await fileToBase64(file) },
      })
      await load()
      onChange()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not send the file.')
    }
  }

  return (
    <div className="documents">
      {docs?.map((d) => (
        <a key={d.id} href={`/api/leave/requests/${request.id}/documents/${d.id}`} target="_blank" rel="noreferrer">{d.fileName}</a>
      ))}
      {request.documents === 0 && request.requiresDocument && <span className="muted">No document yet.</span>}
      {canAdd && (
        <label className="file-button">
          {request.documents === 0 ? 'Add the document' : 'Add another'}
          <input type="file" accept="application/pdf,image/jpeg,image/png" onChange={(e) => { void upload(e.target.files?.[0]); e.target.value = '' }} />
        </label>
      )}
      <ErrorText error={error} />
    </div>
  )
}

export default function LeavePage() {
  const { can } = useAuth()
  const canRequest = can('leave.request')
  const isHr = can('leave.manage')
  const canDecide = isHr || can('leave.approve.team')
  const [types, setTypes] = useState<LeaveType[]>([])
  const [balances, setBalances] = useState<LeaveBalance[]>([])
  const [mine, setMine] = useState<LeaveRequest[]>([])
  const [pending, setPending] = useState<LeaveRequest[]>([])
  const [form, setForm] = useState({ leaveTypeId: '', startDate: '', endDate: '', halfDay: '', reason: '' })
  const [file, setFile] = useState<File | null>(null)
  const [preview, setPreview] = useState<number | null>(null)
  const [notes, setNotes] = useState<Record<number, string>>({})
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const units = useUnitFilter()

  const load = () => {
    api<LeaveType[]>('/api/leave/types').then((t) => setTypes(t.filter((x) => x.active))).catch(() => undefined)
    if (canRequest) api<{ balances: LeaveBalance[]; requests: LeaveRequest[] }>('/api/leave/mine')
      .then((m) => { setBalances(m.balances); setMine(m.requests) }).catch((e: Error) => setError(e.message))
    if (canDecide) api<LeaveRequest[]>('/api/leave/requests/to-decide').then(setPending).catch((e: Error) => setError(e.message))
  }
  useEffect(load, []) // eslint-disable-line react-hooks/exhaustive-deps

  const oneDay = form.startDate !== '' && form.startDate === form.endDate
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

  const selected = types.find((t) => t.id === Number(form.leaveTypeId))

  const submit = (e: FormEvent) => {
    e.preventDefault()
    void run(async () => {
      const created = await api<LeaveRequest>('/api/leave/requests', {
        method: 'POST',
        body: { ...form, leaveTypeId: Number(form.leaveTypeId), halfDay: oneDay && form.halfDay ? form.halfDay : null },
      })
      if (file) {
        await api(`/api/leave/requests/${created.id}/documents`, {
          method: 'POST', body: { fileName: file.name, contentType: file.type || 'application/octet-stream', base64: await fileToBase64(file) },
        })
      }
      setForm({ leaveTypeId: form.leaveTypeId, startDate: '', endDate: '', halfDay: '', reason: '' })
      setFile(null)
    }, selected?.requiresDocument && !file
      ? 'Leave requested. Add the supporting document below so HR can check it.'
      : 'Leave requested. Your manager will be asked to approve it.')
  }

  const decide = (r: LeaveRequest, approve: boolean) => {
    const path = r.status === 'AWAITING_HR' ? 'document-check' : 'decision'
    void run(() => api(`/api/leave/requests/${r.id}/${path}`, { method: 'POST', body: { approve, note: notes[r.id] } }),
      approve ? (r.status === 'AWAITING_HR' ? 'Document accepted; the leave counts as approved.' : 'Leave approved.') : 'Leave rejected.')
  }

  const shown = pending.filter((r) => units.keep(r.userId))
  const halfPreview = oneDay && form.halfDay && preview ? preview / 2 : preview

  return (
    <section>
      <div className="row space">
        <h2>Leave</h2>
        {isHr && <Link className="button secondary" to="/leave/settings">Leave settings</Link>}
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
          <div className="row space"><h3>To approve</h3>{units.select}</div>
          {shown.length === 0 && <p className="muted">Nothing waiting.</p>}
          {shown.map((r) => (
            <div key={r.id} className="mission">
              <p><strong>{r.employeeName}</strong> <span className="muted">{r.registrationNo} · {units.unitName(r.userId)}</span></p>
              <p>{r.leaveType}: {dates(r)} ({days(r.workingDays)})</p>
              {r.reason && <p className="muted">{r.reason}</p>}
              {r.status === 'AWAITING_HR' && <p className="hint">Approved by {r.decidedBy}. Check the document before the leave counts.</p>}
              {(r.requiresDocument || r.documents > 0) && <Documents request={r} canAdd={isHr} onChange={load} />}
              <div className="row">
                <input placeholder="Note (required to reject)" value={notes[r.id] ?? ''}
                  onChange={(e) => setNotes({ ...notes, [r.id]: e.target.value })} />
                <button onClick={() => decide(r, true)}>{r.status === 'AWAITING_HR' ? 'Accept document' : 'Approve'}</button>
                <button className="secondary" onClick={() => decide(r, false)}>{r.status === 'AWAITING_HR' ? 'Refuse document' : 'Reject'}</button>
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
            <label>First day<input type="date" value={form.startDate}
              onChange={(e) => setForm({ ...form, startDate: e.target.value, endDate: form.endDate && form.endDate >= e.target.value ? form.endDate : e.target.value })} required /></label>
            <label>Last day<input type="date" value={form.endDate} min={form.startDate} onChange={(e) => setForm({ ...form, endDate: e.target.value })} required /></label>
          </div>
          {oneDay && (
            <label>Part of the day
              <select value={form.halfDay} onChange={(e) => setForm({ ...form, halfDay: e.target.value })}>
                <option value="">The whole day</option>
                <option value="AM">The morning only</option>
                <option value="PM">The afternoon only</option>
              </select>
            </label>
          )}
          <label>Reason (optional)<input value={form.reason} maxLength={500} onChange={(e) => setForm({ ...form, reason: e.target.value })} /></label>
          {halfPreview !== null && <p className="hint">{days(halfPreview)} of work. Rest days and public holidays are not counted.</p>}
          {selected?.requiresDocument && (
            <label>Supporting document (for example a medical certificate; PDF, JPEG or PNG, at most 5 MB)
              <input type="file" accept="application/pdf,image/jpeg,image/png" onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
              <small className="muted">You can also add it later. HR checks it before the leave counts.</small>
            </label>
          )}
          {selected && selected.payPercent === 0 && <p className="hint">This leave is unpaid.</p>}
          {selected && selected.payPercent > 0 && selected.payPercent < 100 && (
            <p className="hint">This leave is paid at {Number(selected.payPercent).toLocaleString()}% of the usual pay.</p>
          )}
          <button type="submit">Send request</button>
        </form>
      )}

      {canRequest && mine.length > 0 && (
        <div className="table-wrap">
          <table>
            <thead><tr><th>Kind</th><th>Dates</th><th>Days</th><th>Status</th><th>Documents</th><th></th></tr></thead>
            <tbody>
              {mine.map((r) => (
                <tr key={r.id}>
                  <td>{r.leaveType}</td>
                  <td>{dates(r)}</td>
                  <td>{days(r.workingDays)}</td>
                  <td>{STATUS[r.status]}{r.decidedBy ? <><br /><span className="muted">by {r.decidedBy}{r.decisionNote ? `: ${r.decisionNote}` : ''}</span></> : null}</td>
                  <td>{(r.requiresDocument || r.documents > 0) && (
                    <Documents request={r} canAdd={['PENDING', 'AWAITING_HR', 'APPROVED'].includes(r.status)} onChange={load} />
                  )}</td>
                  <td>{(r.status === 'PENDING' || r.status === 'AWAITING_HR') && (
                    <button className="secondary" onClick={() => void run(() => api(`/api/leave/requests/${r.id}/cancel`, { method: 'POST', body: {} }), 'Request cancelled.')}>Cancel</button>
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
