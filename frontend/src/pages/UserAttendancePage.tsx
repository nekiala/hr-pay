import { useEffect, useState, type FormEvent } from 'react'
import { Link, useParams } from 'react-router-dom'
import { ApiError, api, type User , personName } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'
import { useAsk } from '../components/Dialogs'
import MonthView from '../components/MonthView'
import { Button, Form } from '../components/Button'
import { Modal } from '../components/Modal'
import { t } from '../i18n'

export default function UserAttendancePage() {
  const [adding, setAdding] = useState(false)
  const ask = useAsk()
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
      return true
    } catch (e) {
      setError(e instanceof ApiError ? [e.message, ...Object.values(e.fieldErrors)].join(' ') : t('Something went wrong.'))
      return false
    }
  }

  const add = (e: FormEvent) => {
    e.preventDefault()
    return run(async () => {
      await api(`/api/attendance/users/${id}/events`, {
        method: 'POST',
        body: { type: entry.type, at: `${entry.date}T${entry.time}:00`, workDate: entry.date, note: entry.note },
      })
      setEntry({ ...entry, note: '' })
    }, t('Entry added.'))
  }

  const voidEntry = (entryId: number) => ask({
    title: t('Void this entry'), message: t('It stays in the history, marked as voided.'), confirmLabel: t('Void'), danger: true,
    fields: [{ label: t('Why is it wrong?'), required: true, multiline: true }],
    onConfirm: ([reason]) => run(() => api(`/api/attendance/events/${entryId}/void`, { method: 'POST', body: { reason } }), t('Entry voided.')),
  })

  return (
    <section>
      <p><Link to={`/users/${id}`}>{t('← Back to employee')}</Link></p>
      <div className="row between">
        <h2>{t('Attendance')}{user ? `: ${personName(user)}` : ''}</h2>
        {canCorrect && <Button onClick={() => setAdding(true)}>{t('Add a missing entry')}</Button>}
      </div>
      {canCorrect && adding && (
        <Modal title={t('Add a missing entry')} size="md" onClose={() => setAdding(false)}>
          <Form onSubmit={async (e) => { if (await add(e)) setAdding(false) }}>
            <p className="hint">{t('Entries are never edited. Void a wrong one with a reason, then add the right one. Both are kept in the audit log.')}</p>
            <div className="row">
              <input type="date" value={entry.date} onChange={(e) => setEntry({ ...entry, date: e.target.value })} required />
              <input type="time" value={entry.time} onChange={(e) => setEntry({ ...entry, time: e.target.value })} required />
              <select value={entry.type} onChange={(e) => setEntry({ ...entry, type: e.target.value })}>
                <option value="IN">{t('Arrival')}</option>
                <option value="OUT">{t('End of day')}</option>
              </select>
              <input placeholder={t('Reason, for example phone was off')} value={entry.note}
                onChange={(e) => setEntry({ ...entry, note: e.target.value })} required maxLength={500} />
              <ErrorText error={error} />
            <div className="modal-foot"><Button className="secondary" onClick={() => setAdding(false)}>{t('Cancel')}</Button><Button type="submit">{t('Add')}</Button></div>
            </div>
          </Form>
        </Modal>
      )}
      <ErrorText error={error} />
      {message && <p className="success">{message}</p>}
      <MonthView source={`/api/attendance/users/${id}/month`} onVoid={canCorrect ? voidEntry : undefined} version={version} />
    </section>
  )
}
