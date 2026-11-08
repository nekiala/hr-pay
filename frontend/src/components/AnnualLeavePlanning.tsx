import { useEffect, useState } from 'react'
import { api, days, longDay } from '../api'
import { ErrorText } from './ErrorText'
import { t } from '../i18n'

type Planned = {
  id: number; userId: number; employeeName: string; registrationNo: string; startDate: string; endDate: string
  workingDays: number; status: 'APPROVED' | 'PENDING' | 'AWAITING_HR'; allowanceStatus: 'CALCULATED' | 'APPROVED' | 'PAID' | null
  allowanceNumber: string | null
}

const iso = (d: Date) => d.toISOString().slice(0, 10)
const STATUS: Record<Planned['status'], string> = {
  APPROVED: t('Approved'), PENDING: t('Waiting for approval'), AWAITING_HR: t('Approved, HR to check the document'),
}
const PAY: Record<NonNullable<Planned['allowanceStatus']>, string> = {
  CALCULATED: t('Calculated, waiting for Finance'), APPROVED: t('Approved, to pay'), PAID: t('Paid'),
}

/** Who takes annual leave and when: for HR and payroll (everyone) and managers (their branch). */
export function AnnualLeavePlanning({ showPay }: { showPay: boolean }) {
  const today = new Date()
  const [from, setFrom] = useState(iso(today))
  const [to, setTo] = useState(iso(new Date(today.getFullYear(), today.getMonth() + 3, today.getDate())))
  const [rows, setRows] = useState<Planned[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!from || !to || to < from) return
    setError(null)
    api<Planned[]>(`/api/leave/annual-planning?from=${from}&to=${to}`).then(setRows).catch((e: Error) => setError(e.message))
  }, [from, to])

  const now = iso(today)
  return (
    <div className="card">
      <div className="row space">
        <h3>{t('Annual leave planning')}</h3>
        <div className="row">
          <label>{t('From')} <input type="date" value={from} onChange={(e) => setFrom(e.target.value)} /></label>
          <label>{t('To')} <input type="date" value={to} onChange={(e) => setTo(e.target.value)} /></label>
        </div>
      </div>
      <ErrorText error={error} />
      {rows?.length === 0 && <p className="muted">{t('No annual leave in this period.')}</p>}
      {rows && rows.length > 0 && (
        <div className="table-wrap">
          <table>
            <thead><tr><th>{t('Employee')}</th><th>{t('From')}</th><th>{t('To')}</th><th>{t('Days')}</th><th>{t('Status')}</th>{showPay && <th>{t('Leave pay')}</th>}</tr></thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.id}>
                  <td>{r.employeeName} <span className="muted">{r.registrationNo}</span>
                    {r.startDate <= now && r.endDate >= now && <> <span className="badge">{t('on leave now')}</span></>}</td>
                  <td>{longDay(r.startDate)}</td><td>{longDay(r.endDate)}</td><td>{days(r.workingDays)}</td>
                  <td>{STATUS[r.status]}</td>
                  {showPay && <td>{r.allowanceStatus ? `${PAY[r.allowanceStatus]}${r.allowanceNumber ? ` · ${r.allowanceNumber}` : ''}` : r.status === 'APPROVED' ? t('Not worked out yet') : '—'}</td>}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
