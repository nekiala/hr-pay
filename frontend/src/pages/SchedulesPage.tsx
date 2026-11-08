import { useEffect, useState } from 'react'
import { ApiError, api, hours, WEEKDAYS, type Schedule, type ScheduleDay } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'
import { Button } from '../components/Button'
import { t } from '../i18n'

type Draft = Omit<Schedule, 'id' | 'weeklyMinutes' | 'warnings'> & { id?: number }

const blank = (): Draft => ({
  code: '', name: '', graceMinutes: 10, deductLateArrival: true, deductEarlyDeparture: true, active: true,
  days: WEEKDAYS.map((_, i) => ({
    weekday: i + 1, workday: i < 5, startTime: i < 5 ? '08:00' : null, endTime: i < 5 ? '17:00' : null,
    lunchEnabled: i < 5, lunchStart: i < 5 ? '12:00' : null, lunchMinutes: i < 5 ? 60 : null, lunchPaid: false,
  })),
})

const hhmm = (tItem: string | null) => (tItem ? tItem.slice(0, 5) : '')

export default function SchedulesPage() {
  const { can } = useAuth()
  const editable = can('schedule.manage')
  const [schedules, setSchedules] = useState<Schedule[]>([])
  const [draft, setDraft] = useState<Draft | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)

  const load = () => api<Schedule[]>('/api/schedules').then(setSchedules).catch((e: Error) => setError(e.message))
  useEffect(() => { void load() }, [])

  const setDay = (i: number, patch: Partial<ScheduleDay>) =>
    draft && setDraft({ ...draft, days: draft.days.map((d, j) => (i === j ? { ...d, ...patch } : d)) })

  const save = async () => {
    if (!draft) return
    setError(null)
    setMessage(null)
    try {
      const body = { ...draft, days: draft.days.map((d) => ({ ...d, startTime: d.workday ? hhmm(d.startTime) : null,
        endTime: d.workday ? hhmm(d.endTime) : null, lunchStart: d.lunchEnabled ? hhmm(d.lunchStart) : null })) }
      const saved = await api<Schedule>(draft.id ? `/api/schedules/${draft.id}` : '/api/schedules',
        { method: draft.id ? 'PUT' : 'POST', body })
      setDraft(saved)
      setMessage([t('Saved. {a} per week.', { a: hours(saved.weeklyMinutes) }), ...(saved.warnings ?? [])].join(' '))
      await load()
    } catch (e) {
      setError(e instanceof ApiError ? [e.message, ...Object.values(e.fieldErrors)].join(' ') : t('Could not save.'))
    }
  }

  return (
    <section>
      <div className="row between">
        <h2>{t('Work schedules')}</h2>
        {editable && <Button onClick={() => { setDraft(blank()); setMessage(null) }}>{t('New schedule')}</Button>}
      </div>
      <div className="table-wrap">
        <table>
          <thead><tr><th>{t('Name')}</th><th>{t('Code')}</th><th>{t('Hours per week')}</th><th>{t('Grace')}</th><th>{t('Status')}</th></tr></thead>
          <tbody>
            {schedules.map((s) => (
              <tr key={s.id}>
                <td><Button className="link" onClick={() => { setDraft(s); setMessage(null) }}>{s.name}</Button></td>
                <td>{s.code}</td>
                <td>{hours(s.weeklyMinutes)}</td>
                <td>{s.graceMinutes}{' '}{t('min')}</td>
                <td><span className={`badge ${s.active ? 'ok' : 'off'}`}>{s.active ? t('Active') : t('Inactive')}</span></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {draft && (
        <div className="card" style={{ marginTop: 16 }}>
          <fieldset disabled={!editable}>
            <div className="grid3">
              <label>{t('Code')}<input value={draft.code} disabled={!!draft.id} onChange={(e) => setDraft({ ...draft, code: e.target.value.toUpperCase() })} /></label>
              <label>{t('Name')}<input value={draft.name} onChange={(e) => setDraft({ ...draft, name: e.target.value })} /></label>
              <label>{t('Grace period (min)')}<input type="number" min={0} max={120} value={draft.graceMinutes}
                onChange={(e) => setDraft({ ...draft, graceMinutes: Number(e.target.value) })} /></label>
            </div>
            <label className="check"><input type="checkbox" checked={draft.deductLateArrival}
              onChange={(e) => setDraft({ ...draft, deductLateArrival: e.target.checked })} />{t('Deduct late arrival beyond the grace period')}</label>
            <label className="check"><input type="checkbox" checked={draft.deductEarlyDeparture}
              onChange={(e) => setDraft({ ...draft, deductEarlyDeparture: e.target.checked })} />{t('Deduct early departure')}</label>
            <label className="check"><input type="checkbox" checked={draft.active}
              onChange={(e) => setDraft({ ...draft, active: e.target.checked })} />{t('Active')}</label>
            <div className="table-wrap">
              <table>
                <thead><tr><th>{t('Day')}</th><th>{t('Workday')}</th><th>{t('Start')}</th><th>{t('End')}</th><th>{t('Lunch')}</th><th>{t('Lunch start')}</th><th>{t('Minutes')}</th><th>{t('Paid lunch')}</th></tr></thead>
                <tbody>
                  {draft.days.map((d, i) => (
                    <tr key={d.weekday}>
                      <td>{WEEKDAYS[d.weekday - 1]}</td>
                      <td><input type="checkbox" checked={d.workday} onChange={(e) => setDay(i, e.target.checked
                        ? { workday: true, startTime: '08:00', endTime: '17:00' }
                        : { workday: false, lunchEnabled: false })} /></td>
                      <td><input type="time" disabled={!d.workday} value={hhmm(d.startTime)} onChange={(e) => setDay(i, { startTime: e.target.value })} /></td>
                      <td><input type="time" disabled={!d.workday} value={hhmm(d.endTime)} onChange={(e) => setDay(i, { endTime: e.target.value })} /></td>
                      <td><input type="checkbox" disabled={!d.workday} checked={d.lunchEnabled} onChange={(e) => setDay(i, e.target.checked
                        ? { lunchEnabled: true, lunchStart: '12:00', lunchMinutes: 60 } : { lunchEnabled: false })} /></td>
                      <td><input type="time" disabled={!d.lunchEnabled} value={hhmm(d.lunchStart)} onChange={(e) => setDay(i, { lunchStart: e.target.value })} /></td>
                      <td><input type="number" disabled={!d.lunchEnabled} value={d.lunchMinutes ?? ''} onChange={(e) => setDay(i, { lunchMinutes: Number(e.target.value) })} /></td>
                      <td><input type="checkbox" disabled={!d.lunchEnabled} checked={d.lunchPaid} onChange={(e) => setDay(i, { lunchPaid: e.target.checked })} /></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </fieldset>
          <ErrorText error={error} />
          {message && <p className="success">{message}</p>}
          {editable && <div className="row" style={{ marginTop: 12 }}><Button onClick={() => save()}>{t('Save schedule')}</Button>
            <Button className="secondary" onClick={() => setDraft(null)}>{t('Close')}</Button></div>}
        </div>
      )}
    </section>
  )
}
