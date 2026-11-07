import { useEffect, useState } from 'react'
import { api, hours, money, time, type DayResult, type DayStatus, type MonthSummary, type OvertimeDay } from '../api'
import { ErrorText } from './ErrorText'
import { Button } from './Button'
import { t, locale } from '../i18n'

const STATUS: Record<DayStatus, string> = {
  FUTURE: '',
  NOT_STARTED: t('Not started'),
  IN_PROGRESS: t('In progress'),
  PRESENT: t('Present'),
  ABSENT: t('Absent'),
  HOLIDAY: t('Public holiday'),
  REST_DAY: t('Rest day'),
  NO_SCHEDULE: t('No schedule'),
  LEAVE: t('On leave'),
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
  PENDING: t('awaiting approval'), APPROVED: 'approved', REJECTED: 'rejected', AUTOMATIC: 'paid',
}

function notes(d: DayResult, ot?: OvertimeDay) {
  const list: string[] = []
  if (d.lateMinutes > 0) list.push(t('Late {a}{b}', { a: hours(d.lateMinutes), b: d.lateDeductedMinutes ? ' (deducted)' : ' (within grace)' }))
  if (d.earlyMinutes > 0) list.push(t('Left early {a}{b}', { a: hours(d.earlyMinutes), b: d.earlyDeductedMinutes ? ' (deducted)' : ' (within grace)' }))
  if (d.missingCheckOut) list.push(t('No end of day recorded'))
  if (d.autoClosed) list.push(t('Closed automatically, to confirm'))
  if (d.mission) list.push(t('Mission'))
  if (d.corrected) list.push(t('Corrected by HR'))
  if (d.overtimeMinutes > 0) {
    const status = ot ? OT_STATUS[ot.status] : t('awaiting approval')
    const partial = ot && ot.status === 'APPROVED' && ot.approvedMinutes < ot.minutes ? t(" ({a} approved)", { a: hours(ot.approvedMinutes) }) : ''
    list.push(t('Overtime {a}, {b}{c}', { a: hours(d.overtimeMinutes), b: status, c: partial }))
  }
  return list.join(' · ')
}

/** Month to date for one employee: summary tiles, earnings (when allowed) and one row per day. */
const SOURCE: Record<string, string> = { SELF: 'self', HR_MANUAL: t('HR entry'), AUTO_CLOSE: 'automatic', MISSION: 'mission' }

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

  const label = new Date(`${month}-01T12:00:00`).toLocaleDateString(locale(), { month: 'long', year: 'numeric' })
  const days = data?.days.filter((d) => d.date <= data.asOf) ?? []

  return (
    <div className="month">
      <div className="row month-nav">
        <Button className="secondary" onClick={() => setMonth(shiftMonth(month, -1))}>{t('Previous')}</Button>
        <strong>{label}</strong>
        <Button className="secondary" onClick={() => setMonth(shiftMonth(month, 1))} disabled={month >= currentMonth()}>{t('Next')}</Button>
      </div>
      <ErrorText error={error} />
      {data && (
        <>
          <div className="tiles">
            {data.earnings && (
              <div className="tile accent">
                <span>{t('Earned so far')}</span>
                <strong>{money(data.earnings.earnedToDate, data.earnings.currency)}</strong>
                <small>{t('of')}{' '}{money(data.earnings.grossMonthly, data.earnings.currency)}{' '}{t('gross per month')}</small>
              </div>
            )}
            <div className="tile">
              <span>{t('Paid time')}</span>
              <strong>{hours(data.payableMinutesToDate)}</strong>
              <small>{t('of')}{' '}{hours(data.scheduledMinutesToDate)}{' '}{t('expected to date,')}{' '}{hours(data.scheduledMinutesMonth)}{' '}{t('this month')}</small>
            </div>
            <div className="tile">
              <span>{t('Overtime approved')}</span>
              <strong>{hours(data.overtimeApprovedMinutes)}</strong>
              <small>
                {data.earnings ? `${money(data.earnings.overtimeEarned, data.earnings.currency)} · ` : ''}
                {data.overtimePendingMinutes ? t('{a} awaiting approval', { a: hours(data.overtimePendingMinutes) }) : t('nothing waiting')}
              </small>
            </div>
            <div className="tile">
              <span>{t('Late days')}</span>
              <strong>{data.lateDays}</strong>
              <small>{data.absentDays}{' '}{t('absent')}{data.missingCheckOutDays ? t(', {a} without end of day', { a: data.missingCheckOutDays }) : ''}</small>
            </div>
          </div>
          {data.earnings && (
            <p className="hint">{t('Gross earned = monthly gross ÷')}{' '}{hours(data.scheduledMinutesMonth)}{' '}{t('scheduled this month × paid time. Before taxes and deductions. Approved overtime is paid at the overtime rates on top.')}</p>
          )}
          <div className="table-wrap">
            <table>
              <thead>
                <tr><th>{t('Date')}</th><th>{t('Status')}</th><th>{t('Arrived')}</th><th>{t('Left')}</th><th>{t('Worked')}</th><th>{t('Paid')}</th><th>{t('Notes')}</th></tr>
              </thead>
              <tbody>
                {days.length === 0 && <tr><td colSpan={7} className="muted">{t('Nothing yet this month.')}</td></tr>}
                {days.map((d) => (
                  <tr key={d.date} className={d.status === 'ABSENT' ? 'absent' : undefined}>
                    <td>{new Date(`${d.date}T12:00:00`).toLocaleDateString(locale(), { weekday: 'short', day: 'numeric' })}</td>
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
                              {e.type === 'IN' ? t('In') : t('Out')} {time(e.at, data.timeZone)} ({SOURCE[e.source]}){e.note ? ` · ${e.note}` : ''}
                              <Button className="secondary void" onClick={() => onVoid(e.id)}>{t('Void')}</Button>
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
