import { useEffect, useState } from 'react'
import { api, hours, money, time, type DayResult, type DayStatus, type MonthSummary, type OvertimeDay } from '../api'
import { ErrorText } from './ErrorText'

const STATUS: Record<DayStatus, string> = {
  FUTURE: '',
  NOT_STARTED: 'Not started',
  IN_PROGRESS: 'In progress',
  PRESENT: 'Present',
  ABSENT: 'Absent',
  HOLIDAY: 'Public holiday',
  REST_DAY: 'Rest day',
  NO_SCHEDULE: 'No schedule',
  LEAVE: 'On leave',
}

function currentMonth() {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
}

function shiftMonth(month: string, delta: number) {
  const [y, m] = month.split('-').map(Number)
  const d = new Date(y, m - 1 + delta, 1)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
}

const OT_STATUS: Record<OvertimeDay['status'], string> = {
  PENDING: 'awaiting approval', APPROVED: 'approved', REJECTED: 'rejected', AUTOMATIC: 'paid',
}

function notes(d: DayResult, ot?: OvertimeDay) {
  const list: string[] = []
  if (d.lateMinutes > 0) list.push(`Late ${hours(d.lateMinutes)}${d.lateDeductedMinutes ? ' (deducted)' : ' (within grace)'}`)
  if (d.earlyMinutes > 0) list.push(`Left early ${hours(d.earlyMinutes)}${d.earlyDeductedMinutes ? ' (deducted)' : ' (within grace)'}`)
  if (d.missingCheckOut) list.push('No end of day recorded')
  if (d.autoClosed) list.push('Closed automatically, to confirm')
  if (d.mission) list.push('Mission')
  if (d.corrected) list.push('Corrected by HR')
  if (d.overtimeMinutes > 0) {
    const status = ot ? OT_STATUS[ot.status] : 'awaiting approval'
    const partial = ot && ot.status === 'APPROVED' && ot.approvedMinutes < ot.minutes ? ` (${hours(ot.approvedMinutes)} approved)` : ''
    list.push(`Overtime ${hours(d.overtimeMinutes)}, ${status}${partial}`)
  }
  return list.join(' · ')
}

/** Month to date for one employee: summary tiles, earnings (when allowed) and one row per day. */
const SOURCE: Record<string, string> = { SELF: 'self', HR_MANUAL: 'HR entry', AUTO_CLOSE: 'automatic', MISSION: 'mission' }

/**
 * Month to date for one employee. With {@code onVoid}, each day lists its entries with a Void button (HR view);
 * change {@code version} to reload.
 */
export default function MonthView({ source, onVoid, version = 0 }: {
  source: string
  onVoid?: (entryId: number) => void
  version?: number
}) {
  const [month, setMonth] = useState(currentMonth())
  const [data, setData] = useState<MonthSummary | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    setError(null)
    api<MonthSummary>(`${source}?month=${month}`).then(setData).catch((e: Error) => setError(e.message))
  }, [source, month, version])

  const label = new Date(`${month}-01T12:00:00`).toLocaleDateString(undefined, { month: 'long', year: 'numeric' })
  const days = data?.days.filter((d) => d.date <= data.asOf) ?? []

  return (
    <div className="month">
      <div className="row month-nav">
        <button className="secondary" onClick={() => setMonth(shiftMonth(month, -1))}>Previous</button>
        <strong>{label}</strong>
        <button className="secondary" onClick={() => setMonth(shiftMonth(month, 1))} disabled={month >= currentMonth()}>Next</button>
      </div>
      <ErrorText error={error} />
      {data && (
        <>
          <div className="tiles">
            {data.earnings && (
              <div className="tile accent">
                <span>Earned so far</span>
                <strong>{money(data.earnings.earnedToDate, data.earnings.currency)}</strong>
                <small>of {money(data.earnings.grossMonthly, data.earnings.currency)} gross per month</small>
              </div>
            )}
            <div className="tile">
              <span>Paid time</span>
              <strong>{hours(data.payableMinutesToDate)}</strong>
              <small>of {hours(data.scheduledMinutesToDate)} expected to date, {hours(data.scheduledMinutesMonth)} this month</small>
            </div>
            <div className="tile">
              <span>Overtime approved</span>
              <strong>{hours(data.overtimeApprovedMinutes)}</strong>
              <small>
                {data.earnings ? `${money(data.earnings.overtimeEarned, data.earnings.currency)} · ` : ''}
                {data.overtimePendingMinutes ? `${hours(data.overtimePendingMinutes)} awaiting approval` : 'nothing waiting'}
              </small>
            </div>
            <div className="tile">
              <span>Late days</span>
              <strong>{data.lateDays}</strong>
              <small>{data.absentDays} absent{data.missingCheckOutDays ? `, ${data.missingCheckOutDays} without end of day` : ''}</small>
            </div>
          </div>
          {data.earnings && (
            <p className="hint">
              Gross earned = monthly gross ÷ {hours(data.scheduledMinutesMonth)} scheduled this month × paid time. Before
              taxes and deductions. Approved overtime is paid at the overtime rates on top.
            </p>
          )}
          <div className="table-wrap">
            <table>
              <thead>
                <tr><th>Date</th><th>Status</th><th>Arrived</th><th>Left</th><th>Worked</th><th>Paid</th><th>Notes</th></tr>
              </thead>
              <tbody>
                {days.length === 0 && <tr><td colSpan={7} className="muted">Nothing yet this month.</td></tr>}
                {days.map((d) => (
                  <tr key={d.date} className={d.status === 'ABSENT' ? 'absent' : undefined}>
                    <td>{new Date(`${d.date}T12:00:00`).toLocaleDateString(undefined, { weekday: 'short', day: 'numeric' })}</td>
                    <td>{d.status === 'LEAVE' && d.leaveType ? d.leaveType : STATUS[d.status]}</td>
                    <td>{d.firstIn ? time(d.firstIn, data.timeZone) : ''}</td>
                    <td>{d.lastOut ? time(d.lastOut, data.timeZone) : ''}</td>
                    <td>{d.workedMinutes ? hours(d.workedMinutes) : ''}</td>
                    <td>{d.scheduledMinutes ? hours(d.payableMinutes) : ''}</td>
                    <td className="muted">
                      {notes(d, data.overtime.find((o) => o.date === d.date))}
                      {onVoid && d.entries.length > 0 && (
                        <ul className="entries">
                          {d.entries.map((e) => (
                            <li key={e.id}>
                              {e.type === 'IN' ? 'In' : 'Out'} {time(e.at, data.timeZone)} ({SOURCE[e.source]}){e.note ? ` · ${e.note}` : ''}
                              <button className="secondary void" onClick={() => onVoid(e.id)}>Void</button>
                            </li>
                          ))}
                        </ul>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}
    </div>
  )
}
