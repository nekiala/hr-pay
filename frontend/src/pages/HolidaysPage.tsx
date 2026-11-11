import { useEffect, useState, type FormEvent } from 'react'
import { api, type HolidayOccurrence } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'
import { Modal } from '../components/Modal'
import { Button, Form } from '../components/Button'
import { t, locale } from '../i18n'
import { DateInput } from '../components/DateFields'

const fmt = (d: string) => new Date(d + 'T00:00:00').toLocaleDateString(locale(), { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' })

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
  const [adding, setAdding] = useState(false)

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
      setError(err instanceof Error ? err.message : t('Could not save.'))
    }
  }

  const addHoliday = async (e: FormEvent) => {
    e.preventDefault()
    const [, m, d] = newDate.split('-').map(Number)
    try {
      await api('/api/holidays/definitions', { method: 'POST', body: { name: newName, usualMonth: m, usualDay: d, paid: true, active: true } })
      setNewName('')
      setNewDate('')
      setAdding(false)
      await load()
    } catch (err) {
      setError(err instanceof Error ? err.message : t('Could not add the holiday.'))
    }
  }

  return (
    <section>
      <div className="row between">
        <h2>{t('Public holidays')}</h2>
        <div className="row">
          {editable && <Button onClick={() => { setError(null); setAdding(true) }}>{t('Add holiday')}</Button>}
          <Button className="secondary" onClick={() => setYear(year - 1)}>{year - 1}</Button>
          <strong>{year}</strong>
          <Button className="secondary" onClick={() => setYear(year + 1)}>{year + 1}</Button>
        </div>
      </div>
      <p className="muted">{t('When the government moves a holiday, set its observed date. Attendance and payroll use the observed date only.')}</p>
      <ErrorText error={error} />
      <div className="table-wrap">
        <table>
          <thead><tr><th>{t('Holiday')}</th><th>{t('Official date')}</th><th>{t('Observed date')}</th><th>{t('Announcement')}</th>{editable && <th />}</tr></thead>
          <tbody>
            {items.map((h) => (
              <tr key={h.id}>
                <td>{h.name}</td>
                <td>{fmt(h.officialDate)}</td>
                <td>{h.moved ? <strong>{fmt(h.observedDate)}</strong> : fmt(h.observedDate)}</td>
                <td className="small">{h.announcementRef ?? '—'}{h.setBy ? <span className="muted"> · {h.setBy}</span> : null}</td>
                {editable && <td><Button className="link" onClick={() => startEdit(h)}>{t('Change date')}</Button></td>}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {editing && (
        <Modal title={`${editing.name} ${editing.year}`} onClose={() => setEditing(null)}>
          <Form onSubmit={saveMove}>
            <label>{t('Observed date')}<DateInput value={observed} onChange={(e) => setObserved(e.target.value)} required /></label>
            <label>{t('Government announcement (reference)')}<input value={announcement} onChange={(e) => setAnnouncement(e.target.value)} placeholder={t('e.g. Communiqué du Ministère, 25 June')} /></label>
            <p className="hint">{t('A change in a closed month is settled on the next payroll not yet approved.')}</p>
            <ErrorText error={error} />
            <div className="modal-foot"><Button type="button" className="secondary" onClick={() => setEditing(null)}>{t('Cancel')}</Button><Button type="submit">{t('Save')}</Button></div>
          </Form>
        </Modal>
      )}
      {adding && (
        <Modal title={t('Add a recurring holiday')} size="sm" onClose={() => setAdding(false)}>
          <Form onSubmit={addHoliday}>
            <label>{t('Name')}<input value={newName} onChange={(e) => setNewName(e.target.value)} required /></label>
            <label>{t('Usual date (any year)')}<DateInput value={newDate} onChange={(e) => setNewDate(e.target.value)} required /></label>
            <ErrorText error={error} />
            <div className="modal-foot"><Button type="button" className="secondary" onClick={() => setAdding(false)}>{t('Cancel')}</Button><Button type="submit">{t('Add holiday')}</Button></div>
          </Form>
        </Modal>
      )}
    </section>
  )
}
