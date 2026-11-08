import { useEffect, useState, type FormEvent } from 'react'
import { ApiError, api, type Mission } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'
import { useUnitFilter } from '../components/UnitFilter'
import { Button, Form } from '../components/Button'
import { Modal } from '../components/Modal'
import { t } from '../i18n'

const STATUS: Record<Mission['status'], string> = {
  PENDING: t('Waiting for approval'), APPROVED: t('Approved'), REJECTED: t('Rejected'), CANCELLED: t('Cancelled'),
}

const today = () => new Date().toISOString().slice(0, 10)
const hm = (tItem: string) => tItem.slice(0, 5)

export default function MissionsPage() {
  const [requesting, setRequesting] = useState(false)
  const { can } = useAuth()
  const canRequest = can('mission.request')
  const canDecide = can('mission.approve.all') || can('mission.approve.team')
  const [mine, setMine] = useState<Mission[]>([])
  const [pending, setPending] = useState<Mission[]>([])
  const units = useUnitFilter()
  const [form, setForm] = useState({ workDate: today(), startTime: '14:00', endTime: '17:00', location: '', purpose: '' })
  const [notes, setNotes] = useState<Record<number, string>>({})
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)

  const load = () => {
    if (canRequest) api<Mission[]>('/api/missions/mine').then(setMine).catch((e: Error) => setError(e.message))
    if (canDecide) api<Mission[]>('/api/missions/to-decide').then(setPending).catch((e: Error) => setError(e.message))
  }
  useEffect(load, []) // eslint-disable-line react-hooks/exhaustive-deps

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
    return run(async () => {
      await api('/api/missions', { method: 'POST', body: form })
      setForm({ ...form, location: '', purpose: '' })
    }, t('Mission requested. Your manager will be asked to approve it.'))
  }

  return (
    <section>
      <div className="row between">
        <h2>{t('Missions')}</h2>
        {canRequest && <Button onClick={() => setRequesting(true)}>{t('Request a mission')}</Button>}
      </div>
      <p className="muted">{t('Work away from the office. Once approved, you can end your day where the mission is.')}</p>
      <ErrorText error={error} />
      {message && <p className="success">{message}</p>}

      {canDecide && (
        <div className="card">
          <div className="row space"><h3>{t('To approve')}</h3>{units.select}</div>
          {pending.filter((m) => units.keep(m.userId)).length === 0 && <p className="muted">{t('Nothing waiting.')}</p>}
          {pending.filter((m) => units.keep(m.userId)).map((m) => (
            <div key={m.id} className="mission">
              <p><strong>{m.employeeName}</strong> <span className="muted">{m.registrationNo} · {units.unitName(m.userId)}</span></p>
              <p>{m.workDate}, {hm(m.startTime)}–{hm(m.endTime)} · {m.location}</p>
              <p className="muted">{m.purpose}</p>
              <div className="row">
                <input placeholder={t('Note (required to reject)')} value={notes[m.id] ?? ''}
                  onChange={(e) => setNotes({ ...notes, [m.id]: e.target.value })} />
                <Button onClick={() => run(() => api(`/api/missions/${m.id}/decision`, { method: 'POST', body: { approve: true, note: notes[m.id] } }), t('Mission approved.'))}>{t('Approve')}</Button>
                <Button className="secondary" onClick={() => run(() => api(`/api/missions/${m.id}/decision`, { method: 'POST', body: { approve: false, note: notes[m.id] } }), t('Mission rejected.'))}>{t('Reject')}</Button>
              </div>
            </div>
          ))}
        </div>
      )}

      {canRequest && requesting && (
        <Modal title={t('Request a mission')} size="md" onClose={() => setRequesting(false)}>
          <Form onSubmit={async (e) => { if (await submit(e)) setRequesting(false) }}>
            <div className="grid3">
              <label>{t('Date')}<input type="date" value={form.workDate} onChange={(e) => setForm({ ...form, workDate: e.target.value })} required /></label>
              <label>{t('From')}<input type="time" value={form.startTime} onChange={(e) => setForm({ ...form, startTime: e.target.value })} required /></label>
              <label>{t('To')}<input type="time" value={form.endTime} onChange={(e) => setForm({ ...form, endTime: e.target.value })} required /></label>
            </div>
            <label>{t('Where')}<input value={form.location} maxLength={200} onChange={(e) => setForm({ ...form, location: e.target.value })} placeholder={t('For example Ministry of Labour, Gombe')} required /></label>
            <label>{t('Why')}<input value={form.purpose} maxLength={500} onChange={(e) => setForm({ ...form, purpose: e.target.value })} placeholder={t('For example submit the CNSS declaration')} required /></label>
            <ErrorText error={error} />
          <div className="modal-foot"><Button className="secondary" onClick={() => setRequesting(false)}>{t('Cancel')}</Button><Button type="submit">{t('Send request')}</Button></div>
          </Form>
        </Modal>
      )}

      {canRequest && mine.length > 0 && (
        <div className="table-wrap">
          <table>
            <thead><tr><th>{t('Date')}</th><th>{t('Time')}</th><th>{t('Where')}</th><th>{t('Status')}</th><th></th></tr></thead>
            <tbody>
              {mine.map((m) => (
                <tr key={m.id}>
                  <td>{m.workDate}</td>
                  <td>{hm(m.startTime)}–{hm(m.endTime)}</td>
                  <td>{m.location}<br /><span className="muted">{m.purpose}</span></td>
                  <td>{STATUS[m.status]}{m.decidedBy ? <><br /><span className="muted">{t('by')}{' '}{m.decidedBy}{m.decisionNote ? `: ${m.decisionNote}` : ''}</span></> : null}</td>
                  <td>{m.status === 'PENDING' && <Button className="secondary" onClick={() => run(() => api(`/api/missions/${m.id}/cancel`, { method: 'POST' }), t('Mission cancelled.'))}>{t('Cancel')}</Button>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}
