import { useEffect, useState } from 'react'
import { ApiError, api, hours, time, type PendingOvertime } from '../api'
import { ErrorText } from '../components/ErrorText'
import { useUnitFilter } from '../components/UnitFilter'
import { Button } from '../components/Button'
import { t } from '../i18n'

const KIND = { WEEKDAY: t('Workday'), REST_DAY: t('Rest day'), HOLIDAY: t('Public holiday') }

export default function OvertimeApprovalsPage() {
  const [items, setItems] = useState<PendingOvertime[] | null>(null)
  const [edits, setEdits] = useState<Record<string, { minutes?: number; note?: string }>>({})
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const units = useUnitFilter()
  const shown = items?.filter((p) => units.keep(p.userId)) ?? null

  const load = () => api<PendingOvertime[]>('/api/overtime/pending').then(setItems).catch((e: Error) => setError(e.message))
  useEffect(() => { load() }, [])

  const key = (p: PendingOvertime) => `${p.userId}/${p.date}`
  const decide = async (p: PendingOvertime, approve: boolean) => {
    setError(null)
    setMessage(null)
    const edit = edits[key(p)] ?? {}
    try {
      await api('/api/overtime/decisions', {
        method: 'POST',
        body: { userId: p.userId, workDate: p.date, approve, minutes: approve ? edit.minutes ?? p.minutes : null, note: edit.note },
      })
      setMessage(`${approve ? t('Approved') : t('Rejected')}: ${p.employeeName}, ${p.date}.`)
      await load()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : t('Could not save.'))
    }
  }

  return (
    <section>
      <h2>{t('Overtime to approve')}</h2>
      <p className="muted">{t('Overtime of the last 31 days. Only approved overtime is paid; you can approve fewer minutes.')}</p>
      {units.select && <div className="row inline-filter">{units.select}</div>}
      <ErrorText error={error} />
      {message && <p className="success">{message}</p>}
      {shown && shown.length === 0 && <p className="muted">{t('Nothing waiting.')}</p>}
      {shown && shown.length > 0 && (
        <div className="table-wrap">
          <table>
            <thead><tr><th>{t('Employee')}</th><th>{t('Day')}</th><th>{t('Arrived – left')}</th><th>{t('Overtime')}</th><th>{t('Approve (min)')}</th><th>{t('Note')}</th><th></th></tr></thead>
            <tbody>
              {shown.map((p) => {
                const edit = edits[key(p)] ?? {}
                return (
                  <tr key={key(p)}>
                    <td>{p.employeeName}<br /><span className="muted">{p.registrationNo} · {units.unitName(p.userId)}</span></td>
                    <td>{p.date}<br /><span className="muted">{KIND[p.kind]}</span></td>
                    <td>{p.firstIn ? time(p.firstIn) : ''} – {p.lastOut ? time(p.lastOut) : ''}</td>
                    <td>{hours(p.minutes)}</td>
                    <td><input type="number" min={0} max={p.minutes} step={15} value={edit.minutes ?? p.minutes} className="narrow-input"
                      onChange={(e) => setEdits({ ...edits, [key(p)]: { ...edit, minutes: Number(e.target.value) } })} /></td>
                    <td><input placeholder={t('Required to reject')} value={edit.note ?? ''}
                      onChange={(e) => setEdits({ ...edits, [key(p)]: { ...edit, note: e.target.value } })} /></td>
                    <td className="actions">
                      <Button onClick={() => decide(p, true)}>{t('Approve')}</Button>
                      <Button className="secondary" onClick={() => decide(p, false)}>{t('Reject')}</Button>
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}
