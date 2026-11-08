import { useEffect, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { ApiError, api, days, fileToBase64, longDay, type AnnualLeave, type LeaveBalance, type LeaveDocument, type LeaveRequest, type LeaveType } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'
import { useUnitFilter } from '../components/UnitFilter'
import { Button, Form } from '../components/Button'
import { Modal } from '../components/Modal'
import { SettingsLink } from '../components/SettingsLink'
import { AnnualLeavePlanning } from '../components/AnnualLeavePlanning'
import { t, locale } from '../i18n'

const STATUS: Record<LeaveRequest['status'], string> = {
  PENDING: t('Waiting for approval'), AWAITING_HR: t('Approved, HR to check the document'), APPROVED: t('Approved'),
  REJECTED: t('Rejected'), CANCELLED: t('Cancelled'),
}
const HALF = { AM: 'morning', PM: 'afternoon' }

const dates = (r: LeaveRequest) =>
  r.halfDay ? `${r.startDate} (${HALF[r.halfDay]})` : r.startDate === r.endDate ? r.startDate : t('{a} to {b}', { a: r.startDate, b: r.endDate })

/** The supporting documents of one request: links to open them, and a file picker when more can be added. */
function Documents({ request, canAdd, onChange }: { request: LeaveRequest; canAdd: boolean; onChange: () => void }) {
  const [docs, setDocs] = useState<LeaveDocument[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const load = () => api<LeaveDocument[]>(`/api/leave/requests/${request.id}/documents`).then(setDocs).catch((e: Error) => setError(e.message))
  useEffect(() => { if (request.documents > 0) load() }, [request.id, request.documents]) // eslint-disable-line react-hooks/exhaustive-deps

  const upload = async (file: File | undefined) => {
    if (!file) return
    setError(null)
    if (file.size > 5 * 1024 * 1024) { setError(t('The file must be at most 5 MB.')); return }
    try {
      await api(`/api/leave/requests/${request.id}/documents`, {
        method: 'POST', body: { fileName: file.name, contentType: file.type || 'application/octet-stream', base64: await fileToBase64(file) },
      })
      await load()
      onChange()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : t('Could not send the file.'))
    }
  }

  return (
    <div className="documents">
      {docs?.map((d) => (
        <a key={d.id} href={`/api/leave/requests/${request.id}/documents/${d.id}`} target="_blank" rel="noreferrer">{d.fileName}</a>
      ))}
      {request.documents === 0 && request.requiresDocument && <span className="muted">{t('No document yet.')}</span>}
      {canAdd && (
        <label className="file-button">
          {request.documents === 0 ? t('Add the document') : t('Add another')}
          <input type="file" accept="application/pdf,image/jpeg,image/png" onChange={(e) => { void upload(e.target.files?.[0]); e.target.value = '' }} />
        </label>
      )}
      <ErrorText error={error} />
    </div>
  )
}

export default function LeavePage() {
  const [requesting, setRequesting] = useState(false)
  const { can } = useAuth()
  const canRequest = can('leave.request')
  const isHr = can('leave.manage')
  const canDecide = isHr || can('leave.approve.team')
  const [types, setTypes] = useState<LeaveType[]>([])
  const [balances, setBalances] = useState<LeaveBalance[]>([])
  const [annual, setAnnual] = useState<AnnualLeave | null>(null)
  const [leavePayDue, setLeavePayDue] = useState(0)
  const leavePay = can('payroll.run') || can('payroll.approve')
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
    api<LeaveType[]>('/api/leave/types').then((tItem) => setTypes(tItem.filter((x) => x.active))).catch(() => undefined)
    if (canRequest) api<{ balances: LeaveBalance[]; requests: LeaveRequest[]; annual: AnnualLeave }>('/api/leave/mine')
      .then((m) => { setBalances(m.balances); setMine(m.requests); setAnnual(m.annual) }).catch((e: Error) => setError(e.message))
    if (leavePay) api<{ due: unknown[] }>('/api/leave-pay').then((o) => setLeavePayDue(o.due.length)).catch(() => undefined)
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
      return true
    } catch (e) {
      setError(e instanceof ApiError ? [e.message, ...Object.values(e.fieldErrors)].join(' ') : t('Something went wrong.'))
      return false
    }
  }

  const selected = types.find((tItem) => tItem.id === Number(form.leaveTypeId))

  const submit = (e: FormEvent) => {
    e.preventDefault()
    return run(async () => {
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
      ? t('Leave requested. Add the supporting document below so HR can check it.')
      : t('Leave requested. Your manager will be asked to approve it.'))
  }

  const decide = (r: LeaveRequest, approve: boolean) => {
    const path = r.status === 'AWAITING_HR' ? 'document-check' : 'decision'
    return run(() => api(`/api/leave/requests/${r.id}/${path}`, { method: 'POST', body: { approve, note: notes[r.id] } }),
      approve ? (r.status === 'AWAITING_HR' ? t('Document accepted; the leave counts as approved.') : t('Leave approved.')) : t('Leave rejected.'))
  }

  const shown = pending.filter((r) => units.keep(r.userId))
  const halfPreview = oneDay && form.halfDay && preview ? preview / 2 : preview

  return (
    <section>
      <div className="row space">
        <div className="row between">
          <h2>{t('Leave')}</h2>
          {canRequest && <Button onClick={() => setRequesting(true)}>{t('Request leave')}</Button>}
        </div>
        <div className="row">
          {leavePay && <Link className="button secondary" to="/payroll/leave-pay">{t('Leave pay')}{leavePayDue > 0 ? ` (${leavePayDue})` : ''}</Link>}
          {isHr && <SettingsLink to="/leave/settings" />}
        </div>
      </div>
      {leavePay && leavePayDue > 0 && (
        <p className="notice">{t('{a} approved annual leave(s) wait for their leave pay.', { a: leavePayDue })}{' '}<Link to="/payroll/leave-pay">{t('Work it out')}</Link></p>
      )}
      <ErrorText error={error} />
      {message && <p className="success">{message}</p>}

      {canRequest && balances.length > 0 && (
        <div className="tiles">
          {balances.map((b) => (
            <div key={b.leaveTypeId} className="tile">
              <span>{b.leaveType}</span>
              <strong>{days(b.available)}</strong>
              <small>{days(b.granted)}{' '}{t('granted ·')}{' '}{days(b.taken)}{' '}{t('taken')}{b.pending ? t(" · {a} waiting", { a: days(b.pending) }) : ''}</small>
            </div>
          ))}
        </div>
      )}

      {canRequest && annual && (annual.grantedThisYear > 0 || annual.available !== 0) && (
        <div className="card">
          <h3>{t('My annual leave')}</h3>
          <div className="tiles">
            <div className="tile"><span>{t('Earned so far this year')}</span><strong>{days(annual.earnedToDate)}</strong>
              <small>{t('of {a} for the year', { a: days(annual.grantedThisYear) })}</small></div>
            <div className="tile"><span>{t('Carried from earlier years')}</span><strong>{days(annual.carriedOver)}</strong></div>
            <div className="tile"><span>{t('Taken this year')}</span><strong>{days(annual.takenThisYear)}</strong></div>
            <div className="tile"><span>{t('Left to take')}</span><strong>{days(annual.available)}</strong>
              {annual.pending > 0 && <small>{t('{a} waiting for approval', { a: days(annual.pending) })}</small>}</div>
          </div>
          {annual.rightOpensOn && <p className="hint">{t('Your right to annual leave opens after a year of service, on {a}.', { a: longDay(annual.rightOpensOn) })}</p>}
          {annual.years.filter((y) => y.lapsing > 0).map((y) => (
            <p key={y.year} className="hint">{t('{a} of your {b} leave must be taken by {c}, or they lapse.', { a: days(y.lapsing), b: y.year, c: longDay(y.lapsesOn) })}</p>
          ))}
          <p className="muted">{annual.paid
            ? t('Annual leave is paid: your leave allowance is paid before you leave, and your payslip settles it.')
            : t('Annual leave is not paid in this company.')}{' '}
            {t('Up to {a}% of a year\'s leave can be kept for {b} more year(s).', { a: annual.carryPercent, b: annual.carryYears })}</p>
        </div>
      )}

      {canDecide && (
        <div className="card">
          <div className="row space"><h3>{t('To approve')}</h3>{units.select}</div>
          {shown.length === 0 && <p className="muted">{t('Nothing waiting.')}</p>}
          {shown.map((r) => (
            <div key={r.id} className="mission">
              <p><strong>{r.employeeName}</strong> <span className="muted">{r.registrationNo} · {units.unitName(r.userId)}</span></p>
              <p>{r.leaveType}: {dates(r)} ({days(r.workingDays)})</p>
              {r.reason && <p className="muted">{r.reason}</p>}
              {r.status === 'AWAITING_HR' && <p className="hint">{t('Approved by')}{' '}{r.decidedBy}{t('. Check the document before the leave counts.')}</p>}
              {(r.requiresDocument || r.documents > 0) && <Documents request={r} canAdd={isHr} onChange={load} />}
              <div className="row">
                <input placeholder={t('Note (required to reject)')} value={notes[r.id] ?? ''}
                  onChange={(e) => setNotes({ ...notes, [r.id]: e.target.value })} />
                <Button onClick={() => decide(r, true)}>{r.status === 'AWAITING_HR' ? t('Accept document') : t('Approve')}</Button>
                <Button className="secondary" onClick={() => decide(r, false)}>{r.status === 'AWAITING_HR' ? t('Refuse document') : t('Reject')}</Button>
              </div>
            </div>
          ))}
        </div>
      )}

      {canRequest && requesting && (
        <Modal title={t('Request leave')} size="md" onClose={() => setRequesting(false)}>
          <Form onSubmit={async (e) => { if (await submit(e)) setRequesting(false) }}>
            <div className="grid3">
              <label>{t('Kind')}<select value={form.leaveTypeId} onChange={(e) => setForm({ ...form, leaveTypeId: e.target.value })} required>
                  <option value="">{t('Choose')}</option>
                  {types.map((tItem) => <option key={tItem.id} value={tItem.id}>{tItem.name}</option>)}
                </select>
              </label>
              <label>{t('First day')}<input type="date" value={form.startDate}
                onChange={(e) => setForm({ ...form, startDate: e.target.value, endDate: form.endDate && form.endDate >= e.target.value ? form.endDate : e.target.value })} required /></label>
              <label>{t('Last day')}<input type="date" value={form.endDate} min={form.startDate} onChange={(e) => setForm({ ...form, endDate: e.target.value })} required /></label>
            </div>
            {oneDay && (
              <label>{t('Part of the day')}<select value={form.halfDay} onChange={(e) => setForm({ ...form, halfDay: e.target.value })}>
                  <option value="">{t('The whole day')}</option>
                  <option value="AM">{t('The morning only')}</option>
                  <option value="PM">{t('The afternoon only')}</option>
                </select>
              </label>
            )}
            <label>{t('Reason (optional)')}<input value={form.reason} maxLength={500} onChange={(e) => setForm({ ...form, reason: e.target.value })} /></label>
            {halfPreview !== null && <p className="hint">{days(halfPreview)}{' '}{t('of work. Rest days and public holidays are not counted.')}</p>}
            {selected?.requiresDocument && (
              <label>{t('Supporting document (for example a medical certificate; PDF, JPEG or PNG, at most 5 MB)')}<input type="file" accept="application/pdf,image/jpeg,image/png" onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
                <small className="muted">{t('You can also add it later. HR checks it before the leave counts.')}</small>
              </label>
            )}
            {selected && (selected.payPercent === 0 || (selected.code === 'ANNUAL' && annual && !annual.paid)) && <p className="hint">{t('This leave is unpaid.')}</p>}
            {selected && selected.payPercent > 0 && selected.payPercent < 100 && (
              <p className="hint">{t('This leave is paid at')}{' '}{Number(selected.payPercent).toLocaleString(locale())}{t('% of the usual pay.')}</p>
            )}
            <ErrorText error={error} />
          <div className="modal-foot"><Button className="secondary" onClick={() => setRequesting(false)}>{t('Cancel')}</Button><Button type="submit">{t('Send request')}</Button></div>
          </Form>
        </Modal>
      )}

      {canRequest && mine.length > 0 && (
        <div className="table-wrap">
          <table>
            <thead><tr><th>{t('Kind')}</th><th>{t('Dates')}</th><th>{t('Days')}</th><th>{t('Status')}</th><th>{t('Documents')}</th><th></th></tr></thead>
            <tbody>
              {mine.map((r) => (
                <tr key={r.id}>
                  <td>{r.leaveType}</td>
                  <td>{dates(r)}</td>
                  <td>{days(r.workingDays)}</td>
                  <td>{STATUS[r.status]}{r.decidedBy ? <><br /><span className="muted">{t('by')}{' '}{r.decidedBy}{r.decisionNote ? `: ${r.decisionNote}` : ''}</span></> : null}</td>
                  <td>{(r.requiresDocument || r.documents > 0) && (
                    <Documents request={r} canAdd={['PENDING', 'AWAITING_HR', 'APPROVED'].includes(r.status)} onChange={load} />
                  )}</td>
                  <td>{(r.status === 'PENDING' || r.status === 'AWAITING_HR') && (
                    <Button className="secondary" onClick={() => run(() => api(`/api/leave/requests/${r.id}/cancel`, { method: 'POST', body: {} }), t('Request cancelled.'))}>{t('Cancel')}</Button>
                  )}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {(canDecide || leavePay) && <AnnualLeavePlanning showPay={leavePay} />}
    </section>
  )
}
