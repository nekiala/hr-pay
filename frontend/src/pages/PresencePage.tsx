import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api, day } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'
import { useUnitFilter } from '../components/UnitFilter'
import { Button } from '../components/Button'
import { t } from '../i18n'
import { DateInput } from '../components/DateFields'
import { TeamFitnessCard } from '../components/HealthCards'

type Person = {
  userId: number; name: string; registrationNo: string; directionName: string | null; unitName: string | null; siteName: string | null
  status: string; firstIn: string | null; lastOut: string | null; lateMinutes: number; mission: boolean; leaveType: string | null; missingCheckOut: boolean
}
type Presence = { date: string; counts: Record<string, number>; people: Person[] }
type Issue = { userId: number; name: string; registrationNo: string; directionName: string | null; unitName: string | null; date: string; kind: string; minutes: number; detail: string }
type Exceptions = { from: string; to: string; counts: Record<string, number>; rows: Issue[] }

const today = () => new Date(Date.now() + 60 * 60 * 1000).toISOString().slice(0, 10)   // Kinshasa is UTC+1
const monthStart = () => today().slice(0, 8) + '01'
const hm = (tItem: string | null) => (tItem ? tItem.slice(0, 5) : '')

const STATUS: Record<string, [string, string]> = {
  PRESENT: [t('Came and left'), 'in'], IN_PROGRESS: [t('At work'), 'in'], NOT_STARTED: [t('Not in yet'), 'late'], ABSENT: [t('Absent'), 'absent'],
  LEAVE: [t('On leave'), 'away'], MISSION: [t('On mission'), 'away'], HOLIDAY: [t('Public holiday'), ''], REST_DAY: [t('Rest day'), ''],
  NO_SCHEDULE: [t('No schedule'), ''], FUTURE: ['', ''],
}
const KIND: Record<string, string> = {
  ABSENT: t('Absent'), LATE: t('Late'), LEFT_EARLY: t('Left early'), NO_CHECK_OUT: t('No check-out'), AUTO_CLOSED: t('Closed automatically'), CORRECTED: t('Corrected by HR'),
}
const TILES: [string, string][] = [
  ['expected', t('Expected')], ['in', t('In')], ['late', t('Late')], ['notYetIn', t('Not in yet')], ['absent', t('Absent')], ['leave', t('On leave')], ['mission', t('On mission')],
]

/** Who is in today, and the exceptions of a period, for HR (everyone) and heads (their branch). */
export default function PresencePage() {
  const { can } = useAuth()
  const units = useUnitFilter()
  const [view, setView] = useState<'day' | 'exceptions'>('day')
  const [date, setDate] = useState(today())
  const [from, setFrom] = useState(monthStart())
  const [to, setTo] = useState(today())
  const [kind, setKind] = useState('')
  const [presence, setPresence] = useState<Presence | null>(null)
  const [issues, setIssues] = useState<Exceptions | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    setError(null)
    const unit = units.unit ? `&unitId=${units.unit}` : ''
    if (view === 'day') {
      api<Presence>(`/api/reports/presence?date=${date}${unit}`).then(setPresence).catch((e: Error) => setError(e.message))
    } else if (from && to) {
      api<Exceptions>(`/api/reports/exceptions?from=${from}&to=${to}${unit}`).then(setIssues).catch((e: Error) => setError(e.message))
    }
  }, [view, date, from, to, units.unit])

  const q = `from=${from}&to=${to}${units.unit ? `&unitId=${units.unit}` : ''}`
  const rows = issues?.rows.filter((r) => !kind || r.kind === kind) ?? []

  return (
    <section>
      <div className="row space no-print">
        <h2>{t('Presence')}</h2>
        <div className="row">
          <select value={view} onChange={(e) => setView(e.target.value as 'day' | 'exceptions')}>
            <option value="day">{t('One day')}</option>
            <option value="exceptions">{t('Exceptions over a period')}</option>
          </select>
          {units.select}
        </div>
      </div>
      <ErrorText error={error} />

      {view === 'day' && (
        <>
          <div className="row no-print">
            <label>{t('Day')}<DateInput value={date} max={today()} onChange={(e) => setDate(e.target.value)} /></label>
            <Button className="secondary" onClick={() => window.print()}>{t('Print')}</Button>
          </div>
          {presence && (
            <>
              <div className="tiles">
                {TILES.map(([k, label]) => (
                  <div key={k} className="tile"><span>{label}</span><strong>{presence.counts[k] ?? 0}</strong></div>
                ))}
              </div>
              <div className="table-wrap">
                <table>
                  <thead><tr><th>{t('Employee')}</th><th>{t('Unit')}</th><th>{t('Status')}</th><th>{t('Arrived')}</th><th>{t('Left')}</th><th>{t('Late')}</th></tr></thead>
                  <tbody>
                    {presence.people.map((p) => {
                      const [label, tone] = STATUS[p.status] ?? [p.status, '']
                      return (
                        <tr key={p.userId}>
                          <td>{can('attendance.read.all') ? <Link to={`/users/${p.userId}/attendance`}>{p.name}</Link> : p.name}<br /><span className="muted">{p.registrationNo}</span></td>
                          <td>{p.unitName}{p.siteName && <><br /><span className="muted">{p.siteName}</span></>}</td>
                          <td><span className={`status-pill ${p.lateMinutes > 0 && tone === 'in' ? 'late' : tone}`}>{p.leaveType ?? label}</span>
                            {p.mission && p.status !== 'MISSION' && <span className="muted">{' '}{t('· mission')}</span>}</td>
                          <td>{hm(p.firstIn)}</td>
                          <td>{hm(p.lastOut)}{p.missingCheckOut && <span className="muted">{' '}{t('no check-out')}</span>}</td>
                          <td>{p.lateMinutes > 0 ? `${p.lateMinutes} min` : ''}</td>
                        </tr>
                      )
                    })}
                  </tbody>
                </table>
              </div>
            </>
          )}
        </>
      )}

      {view === 'exceptions' && (
        <>
          <div className="row no-print">
            <label>{t('From')}<DateInput value={from} max={to} onChange={(e) => setFrom(e.target.value)} /></label>
            <label>{t('To')}<DateInput value={to} min={from} onChange={(e) => setTo(e.target.value)} /></label>
            <label>{t('Kind')}<select value={kind} onChange={(e) => setKind(e.target.value)}>
                <option value="">{t('All')}</option>
                {Object.entries(KIND).map(([k, v]) => <option key={k} value={k}>{v} ({issues?.counts[k] ?? 0})</option>)}
              </select>
            </label>
            {can('report.attendance.export') && <>
              <a className="button secondary" href={`/api/reports/exceptions.xlsx?${q}`}>{t('Excel')}</a>
              <a className="button secondary" href={`/api/reports/exceptions.csv?${q}`}>{t('CSV')}</a>
            </>}
          </div>
          <p className="muted">{t('At most 92 days. Days on approved leave or mission are not counted as absences.')}</p>
          {issues && rows.length === 0 && <p className="muted">{t('No exceptions.')}</p>}
          {rows.length > 0 && (
            <div className="table-wrap">
              <table>
                <thead><tr><th>{t('Employee')}</th><th>{t('Unit')}</th><th>{t('Day')}</th><th>{t('Exception')}</th><th>{t('Detail')}</th></tr></thead>
                <tbody>
                  {rows.map((r, i) => (
                    <tr key={i}>
                      <td>{r.name}<br /><span className="muted">{r.registrationNo}</span></td>
                      <td>{r.unitName}</td>
                      <td>{day(r.date)}</td>
                      <td>{KIND[r.kind] ?? r.kind}{r.minutes > 0 && r.kind !== 'ABSENT' ? ` (${r.minutes} min)` : ''}</td>
                      <td className="small">{r.detail}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}
      <TeamFitnessCard />
    </section>
  )
}
