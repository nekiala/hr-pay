import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { ApiError, api, day, download } from '../api'
import { ErrorText } from '../components/ErrorText'
import { Button } from '../components/Button'
import { useAsk } from '../components/Dialogs'
import { dayTime, locale, t } from '../i18n'

type Decade = {
  number: number; from: string; to: string; status: 'OPEN' | 'CLOSED'; ended: boolean; closedBy: string | null; closedAt: string | null
  reopenedBy: string | null; reopenedAt: string | null; reopenReason: string | null; correctionsPending: number; leavePending: number
}
type Person = {
  userId: number; name: string; registrationNo: string | null; unitId: number | null; unit: string | null; scheduledDays: number
  workedDays: number; absentDays: number; leaveDays: number; missionDays: number; lateDays: number; lateMinutes: number
  missingCheckOutDays: number; overtimeMinutes: number; overtimePendingMinutes: number; correctionsPending: number; leavePending: number
}
type Unit = { unitId: number | null; unit: string | null; people: number; absentDays: number; overtimePendingMinutes: number; correctionsPending: number; leavePending: number }
type Detail = { decade: Decade; units: Unit[]; people: Person[] }

const thisMonth = () => { const d = new Date(); return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}` }
const hours = (m: number) => `${Math.floor(m / 60)} h ${String(m % 60).padStart(2, '0')}`
const monthName = (m: string) => new Date(`${m}-01T00:00:00Z`).toLocaleDateString(locale(), { month: 'long', year: 'numeric', timeZone: 'UTC' })
const shift = (m: string, n: number) => { const [y, mo] = m.split('-').map(Number); const d = new Date(Date.UTC(y, mo - 1 + n, 1)); return `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, '0')}` }

/**
 * The attendance decades (1 to 10, 11 to 20, 21 to the end of the month). HR checks what still waits in a decade,
 * closes it (its days are then locked), prints the decade sheet for each unit head to sign, and can reopen it with a
 * reason.
 */
export default function DecadesPage() {
  const ask = useAsk()
  const [month, setMonth] = useState(thisMonth())
  const [decades, setDecades] = useState<Decade[]>([])
  const [open, setOpen] = useState<string | null>(null)
  const [detail, setDetail] = useState<Detail | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)

  const load = () => api<Decade[]>(`/api/attendance/decades?month=${month}`).then(setDecades).catch((e: Error) => setError(e.message))
  useEffect(() => { void load() }, [month])
  useEffect(() => {
    setDetail(null)
    if (open) api<Detail>(`/api/attendance/decades/${open}`).then(setDetail).catch((e: Error) => setError(e.message))
  }, [open])

  const run = async (action: () => Promise<unknown>, done: string) => {
    setError(null)
    setMessage(null)
    try {
      await action()
      setMessage(done)
      await load()
      if (open) setDetail(await api<Detail>(`/api/attendance/decades/${open}`))
    } catch (e) {
      setError(e instanceof ApiError ? [e.message, ...Object.values(e.fieldErrors)].join(' ') : t('Something went wrong.'))
      throw e
    }
  }

  return (
    <section>
      <h2>{t('Attendance decades')}</h2>
      <p className="muted">{t('Close each decade once its days are checked: its clock entries, corrections, overtime decisions and leave approvals are then locked. Categories paid on decades take their absences and overtime from them.')}{' '}
        <Link to="/help/pay-periods">{t('More')}</Link></p>
      <ErrorText error={error} />
      {message && <p className="success">{message}</p>}

      <div className="toolbar">
        <Button className="secondary" onClick={() => setMonth(shift(month, -1))}>←</Button>
        <strong>{monthName(month)}</strong>
        <Button className="secondary" onClick={() => setMonth(shift(month, 1))}>→</Button>
      </div>

      <div className="table-wrap">
        <table>
          <thead><tr><th>{t('Decade')}</th><th>{t('Days')}</th><th>{t('Status')}</th><th>{t('Waiting')}</th><th></th></tr></thead>
          <tbody>
            {decades.map((d) => (
              <tr key={d.from} className={open === d.from ? 'selected' : undefined}>
                <td>{t('Decade {a}', { a: d.number })}</td>
                <td>{t('{a} to {b}', { a: day(d.from), b: day(d.to) })}</td>
                <td>{d.status === 'CLOSED'
                  ? <span className="badge ok" title={d.closedAt ? dayTime(d.closedAt) : undefined}>{t('Closed by {a}', { a: d.closedBy ?? '' })}</span>
                  : d.ended ? <span className="badge warn">{t('To close')}</span> : <span className="badge">{t('In progress')}</span>}
                  {d.reopenReason && d.status === 'OPEN' && <div className="small muted">{t('Reopened: {a}', { a: d.reopenReason })}</div>}
                </td>
                <td>{d.correctionsPending + d.leavePending === 0 ? '—'
                  : t('{a} correction(s), {b} leave request(s)', { a: d.correctionsPending, b: d.leavePending })}</td>
                <td className="nowrap">
                  <Button className="secondary" onClick={() => setOpen(open === d.from ? null : d.from)}>{open === d.from ? t('Hide') : t('Details')}</Button>{' '}
                  {d.status === 'OPEN' && d.ended && <Button onClick={() => ask({
                    title: t('Close decade {a}?', { a: t('{a} to {b}', { a: day(d.from), b: day(d.to) }) }),
                    message: t('Its clock entries, corrections, overtime decisions and leave approvals will be locked until it is reopened.'),
                    confirmLabel: t('Close the decade'),
                    onConfirm: () => run(() => api(`/api/attendance/decades/${d.from}/close`, { method: 'POST' }), t('Decade closed.')),
                  })}>{t('Close decade')}</Button>}
                  {d.status === 'CLOSED' && <Button className="secondary" onClick={() => ask({
                    title: t('Reopen decade {a}?', { a: t('{a} to {b}', { a: day(d.from), b: day(d.to) }) }),
                    message: t('A payroll already approved for these days stays as it is; a change will go on a later payslip.'),
                    confirmLabel: t('Reopen'), fields: [{ label: t('Reason'), required: true, multiline: true }],
                    onConfirm: ([reason]) => run(() => api(`/api/attendance/decades/${d.from}/reopen`, { method: 'POST', body: { reason } }), t('Decade reopened.')),
                  })}>{t('Reopen')}</Button>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {open && !detail && <p className="muted">{t('Loading…')}</p>}
      {detail && (
        <div className="card">
          <h3>{t('Decade {a}', { a: detail.decade.number })} · {t('{a} to {b}', { a: day(detail.decade.from), b: day(detail.decade.to) })}</h3>
          <p>
            <Button className="secondary" onClick={() => download(`/api/attendance/decades/${detail.decade.from}/sheet?format=pdf`, `fiche-decade-${detail.decade.from}.pdf`)}>{t('Decade sheet (PDF)')}</Button>{' '}
            <Button className="secondary" onClick={() => download(`/api/attendance/decades/${detail.decade.from}/sheet?format=xlsx`, `fiche-decade-${detail.decade.from}.xlsx`)}>{t('Excel')}</Button>
          </p>
          <h4>{t('By unit')}</h4>
          <div className="table-wrap">
            <table>
              <thead><tr><th>{t('Unit')}</th><th className="num">{t('People')}</th><th className="num">{t('Absences')}</th><th className="num">{t('Overtime waiting')}</th><th className="num">{t('Corrections waiting')}</th><th className="num">{t('Leave waiting')}</th><th></th></tr></thead>
              <tbody>
                {detail.units.map((u) => (
                  <tr key={u.unitId ?? 0}>
                    <td>{u.unit ?? t('No unit')}</td>
                    <td className="num">{u.people}</td>
                    <td className="num">{u.absentDays}</td>
                    <td className="num">{u.overtimePendingMinutes ? hours(u.overtimePendingMinutes) : '—'}</td>
                    <td className="num">{u.correctionsPending || '—'}</td>
                    <td className="num">{u.leavePending || '—'}</td>
                    <td>{u.unitId != null && <button type="button" className="link" onClick={() => void download(`/api/attendance/decades/${detail.decade.from}/sheet?format=pdf&unitId=${u.unitId}`, `fiche-decade-${detail.decade.from}-${u.unitId}.pdf`)}>{t('Sheet')}</button>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <h4>{t('People')}</h4>
          <div className="table-wrap">
            <table>
              <thead><tr><th>{t('Name')}</th><th>{t('Unit')}</th><th className="num">{t('Scheduled days')}</th><th className="num">{t('Days worked')}</th><th className="num">{t('Absences')}</th><th className="num">{t('Leave')}</th><th className="num">{t('Late arrivals')}</th><th className="num">{t('Overtime')}</th></tr></thead>
              <tbody>
                {detail.people.map((p) => (
                  <tr key={p.userId}>
                    <td>{p.name}{p.registrationNo ? <span className="small muted"> · {p.registrationNo}</span> : null}</td>
                    <td>{p.unit ?? '—'}</td>
                    <td className="num">{p.scheduledDays}</td>
                    <td className="num">{p.workedDays}</td>
                    <td className="num">{p.absentDays || '—'}</td>
                    <td className="num">{p.leaveDays || '—'}</td>
                    <td className="num">{p.lateDays || '—'}</td>
                    <td className="num">{p.overtimeMinutes ? hours(p.overtimeMinutes) : '—'}{p.overtimePendingMinutes > 0 && <span className="badge warn"> {t('{a} waiting', { a: hours(p.overtimePendingMinutes) })}</span>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </section>
  )
}
