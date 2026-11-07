import { useEffect, useState, type FormEvent } from 'react'
import { ApiError, api, time } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'
import { useUnitFilter } from '../components/UnitFilter'
import { Button, Form } from '../components/Button'
import { Modal } from '../components/Modal'
import { t } from '../i18n'

type Request = {
  id: number; userId: number; employeeName: string; registrationNo: string; workDate: string; action: 'ADD' | 'VOID'
  eventType: 'IN' | 'OUT' | null; localTime: string | null; eventId: number | null; eventAt: string | null; reason: string
  status: 'PENDING' | 'PROPOSED' | 'APPLIED' | 'REJECTED' | 'CANCELLED'; requestedBy: string; requestedAt: string
  proposedBy: string | null; proposedAt: string | null; decidedBy: string | null; decidedAt: string | null; decisionNote: string | null
}
type Entry = { id: number; type: 'IN' | 'OUT'; occurredAt: string; source: string }

const STATUS: Record<Request['status'], string> = {
  PENDING: t('Waiting for the head'), PROPOSED: t('Proposed, waiting for HR'), APPLIED: t('Applied'), REJECTED: t('Rejected'), CANCELLED: t('Cancelled'),
}
const KIND = { IN: 'check-in', OUT: 'check-out' }

const what = (r: Request) => r.action === 'ADD'
  ? t('Add the missing {a} at {b}', { a: KIND[r.eventType ?? 'IN'], b: (r.localTime ?? '').slice(0, 5) })
  : t('Remove the {a}', { a: r.eventAt ? t("entry of {a}", { a: time(r.eventAt) }) : 'entry' })

const today = () => new Date().toISOString().slice(0, 10)
const blank = { userId: '', workDate: '', action: 'ADD' as 'ADD' | 'VOID', type: 'IN' as 'IN' | 'OUT', time: '', eventId: '', reason: '' }

/**
 * Attendance corrections: the employee asks for a missing entry to be added or a wrong one removed, a head of their
 * branch proposes it, and HR applies it. A head can also propose one directly for someone in their branch.
 */
export default function CorrectionsPage() {
  const [requesting, setRequesting] = useState(false)
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
      return true
    } catch (e) {
      setError(e instanceof ApiError ? [e.message, ...Object.values(e.fieldErrors)].join(' ') : t('Something went wrong.'))
      return false
    }
  }

  const submit = (e: FormEvent) => {
    e.preventDefault()
    const forOther = form.userId !== ''
    return run(async () => {
      await api('/api/corrections', {
        method: 'POST',
        body: {
          userId: forOther ? Number(form.userId) : null, workDate: form.workDate, action: form.action, reason: form.reason,
          type: form.action === 'ADD' ? form.type : null, time: form.action === 'ADD' ? form.time : null,
          eventId: form.action === 'VOID' && form.eventId ? Number(form.eventId) : null,
        },
      })
      setForm({ ...blank, userId: form.userId })
    }, forOther ? t('Correction proposed. HR will apply it.') : t('Request sent. A head of your unit will look at it, then HR applies it.'))
  }

  const act = (r: Request, step: 'propose' | 'apply' | 'reject', done: string) =>
    run(() => api(`/api/corrections/${r.id}/${step}`, { method: 'POST', body: { note: notes[r.id] } }), done)

  const shown = toDecide.filter((r) => units.keep(r.userId))
  const team = isHead && !isHr ? units.people : []

  return (
    <section>
      <div className="row between">
        <h2>{t('Attendance corrections')}</h2>
        {(canAsk || team.length > 0) && <Button onClick={() => setRequesting(true)}>{team.length > 0 ? t('Ask or propose a correction') : t('Ask for a correction')}</Button>}
      </div>
      <p className="muted">{t('Ask for a forgotten check-in or check-out to be added, or a wrong one removed, up to two months back. A head of the unit proposes it and HR applies it; the change then shows on the day with its reason.')}</p>
      <ErrorText error={error} />
      {message && <p className="success">{message}</p>}

      {(isHead || isHr) && (
        <div className="card">
          <div className="row space"><h3>{t('To decide')}</h3>{units.select}</div>
          {shown.length === 0 && <p className="muted">{t('Nothing waiting.')}</p>}
          {shown.map((r) => (
            <div key={r.id} className="mission">
              <p><strong>{r.employeeName}</strong> <span className="muted">{r.registrationNo} · {units.unitName(r.userId)}</span></p>
              <p>{r.workDate}: {what(r)}</p>
              <p className="muted">{r.reason}</p>
              <p className="muted">
                {STATUS[r.status]}{t('. Asked by')}{' '}{r.requestedBy}
                {r.proposedBy && t(', proposed by {a}', { a: r.proposedBy })}{r.decisionNote && ` (${r.decisionNote})`}.
              </p>
              <div className="row">
                <input placeholder={t('Note (required to reject)')} value={notes[r.id] ?? ''} maxLength={300}
                  onChange={(e) => setNotes({ ...notes, [r.id]: e.target.value })} />
                {isHr
                  ? <Button onClick={() => act(r, 'apply', t('Correction applied.'))}>{t('Apply')}</Button>
                  : <Button onClick={() => act(r, 'propose', t('Proposed to HR.'))}>{t('Propose to HR')}</Button>}
                <Button className="secondary" onClick={() => act(r, 'reject', t('Request rejected.'))}>{t('Reject')}</Button>
              </div>
            </div>
          ))}
        </div>
      )}

      {(canAsk || team.length > 0) && requesting && (
        <Modal title={team.length > 0 ? t('Ask or propose a correction') : t('Ask for a correction')} size="md" onClose={() => setRequesting(false)}>
          <Form onSubmit={async (e) => { if (await submit(e)) setRequesting(false) }}>
            <div className="grid3">
              {team.length > 0 && (
                <label>{t('For')}<select value={form.userId} onChange={(e) => setForm({ ...form, userId: e.target.value, eventId: '' })}>
                    {canAsk && <option value="">{t('Myself')}</option>}
                    {!canAsk && <option value="">{t('Choose a person')}</option>}
                    {team.map((p) => <option key={p.userId} value={p.userId}>{p.name} ({p.registrationNo})</option>)}
                  </select>
                </label>
              )}
              <label>{t('Day')}<input type="date" value={form.workDate} max={today()} required
                onChange={(e) => setForm({ ...form, workDate: e.target.value, eventId: '' })} /></label>
              <label>{t('What')}<select value={form.action} onChange={(e) => setForm({ ...form, action: e.target.value as 'ADD' | 'VOID' })}>
                  <option value="ADD">{t('Add a missing entry')}</option>
                  <option value="VOID">{t('Remove a wrong entry')}</option>
                </select>
              </label>
            </div>
            {form.action === 'ADD' ? (
              <div className="grid3">
                <label>{t('Entry')}<select value={form.type} onChange={(e) => setForm({ ...form, type: e.target.value as 'IN' | 'OUT' })}>
                    <option value="IN">{t('Check-in')}</option>
                    <option value="OUT">{t('Check-out')}</option>
                  </select>
                </label>
                <label>{t('Time')}<input type="time" value={form.time} required onChange={(e) => setForm({ ...form, time: e.target.value })} /></label>
              </div>
            ) : (
              <label>{t('Entry to remove')}<select value={form.eventId} required onChange={(e) => setForm({ ...form, eventId: e.target.value })}>
                  <option value="">{form.workDate ? (entries.length ? t('Choose') : t('No entries that day')) : t('Choose the day first')}</option>
                  {entries.map((x) => <option key={x.id} value={x.id}>{x.type === 'IN' ? t('Check-in') : t('Check-out')}{' '}{t('at')}{' '}{time(x.occurredAt)}</option>)}
                </select>
              </label>
            )}
            <label>{t('Why')}<input value={form.reason} required maxLength={500} placeholder={t('For example: the badge reader was down')}
              onChange={(e) => setForm({ ...form, reason: e.target.value })} /></label>
            <ErrorText error={error} />
          <div className="modal-foot"><Button className="secondary" onClick={() => setRequesting(false)}>{t('Cancel')}</Button><Button type="submit" disabled={!canAsk && form.userId === ''}>{form.userId ? t('Propose to HR') : t('Send request')}</Button></div>
          </Form>
        </Modal>
      )}

      {mine.length > 0 && (
        <div className="table-wrap">
          <h3>{t('My requests')}</h3>
          <table>
            <thead><tr><th>{t('Day')}</th><th>{t('Correction')}</th><th>{t('Why')}</th><th>{t('Status')}</th><th></th></tr></thead>
            <tbody>
              {mine.map((r) => (
                <tr key={r.id}>
                  <td>{r.workDate}</td>
                  <td>{what(r)}</td>
                  <td>{r.reason}</td>
                  <td>{STATUS[r.status]}{r.decidedBy && r.status !== 'CANCELLED'
                    ? <><br /><span className="muted">{t('by')}{' '}{r.decidedBy}{r.decisionNote ? `: ${r.decisionNote}` : ''}</span></> : null}</td>
                  <td>{r.status === 'PENDING' && (
                    <Button className="secondary" onClick={() => run(() => api(`/api/corrections/${r.id}/cancel`, { method: 'POST' }), t('Request cancelled.'))}>{t('Cancel')}</Button>
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
