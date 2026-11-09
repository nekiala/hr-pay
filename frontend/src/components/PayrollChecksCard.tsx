import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { ApiError, api } from '../api'
import { useAuth } from '../auth'
import { Button } from './Button'
import { useAsk } from './Dialogs'
import { ErrorText } from './ErrorText'
import { SettingsLink } from './SettingsLink'
import { t } from '../i18n'

export type PayrollCheck = {
  code: string; level: 'LEGAL' | 'WARNING'; userId: number | null; name: string | null; registrationNo: string | null
  message: string; acceptedBy: string | null; reason: string | null; acceptedAt: string | null
}
export type ChecksReport = {
  month: string; status: string; legal: number; warnings: number; accepted: number; clear: boolean
  checks: PayrollCheck[]; settings: { netChangePercent: number; rateMaxDays: number }
}

/** What each check looks at, in the words HR uses. */
export const CHECK_TITLES: Record<string, string> = {
  NET_NEGATIVE: t('Net pay below zero'),
  BELOW_MINIMUM: t('Basic pay below the legal minimum'),
  AFTER_LEAVING: t('Paid after leaving'),
  MISSING_DETAILS: t('Missing CNSS, tax or payment details'),
  SHARED_ACCOUNT: t('Same account for several people'),
  NET_CHANGE: t('Large change in net pay'),
  OVERTIME_PENDING: t('Overtime waiting for a decision'),
  OVERTIME_LIMIT: t('Overtime above the weekly limit'),
  ABSENT_DAYS: t('Days without a check-in'),
  LEAVE_PENDING: t('Leave waiting for a decision'),
  CORRECTION_PENDING: t('Attendance corrections waiting'),
  RATE_OLD: t('Old exchange rate'),
}

const fail = (e: unknown) => e instanceof ApiError ? [e.message, ...Object.values(e.fieldErrors)].join(' ') : t('Something went wrong.')

/**
 * The checks of a month-end payroll before it is approved. Legal ones must be fixed; HR accepts the others with a
 * reason. {@code version} changes when the payroll is calculated again, so the list follows.
 */
export function PayrollChecksCard({ month, version, onChange }: { month: string; version: unknown; onChange?: (r: ChecksReport) => void }) {
  const { can } = useAuth()
  const ask = useAsk()
  const [report, setReport] = useState<ChecksReport | null>(null)
  const [error, setError] = useState<string | null>(null)

  const load = () => api<ChecksReport>(`/api/payroll/runs/${month}/checks`)
    .then((r) => { setReport(r); onChange?.(r) })
    .catch((e) => { setReport(null); if (!(e instanceof ApiError && e.status === 404)) setError(fail(e)) })
  useEffect(() => { setError(null); void load() }, [month, version]) // eslint-disable-line react-hooks/exhaustive-deps

  if (!report || report.status === 'CLOSED') return null
  const canAccept = can('payroll.run') && report.status === 'CALCULATED'

  const accept = (code: string, userIds: number[] | null, count: number) => ask({
    title: t('Accept: {a}', { a: CHECK_TITLES[code] ?? code }),
    message: userIds ? '' : t('This accepts the {a} open line(s) of this check. The reason is kept in the audit log.', { a: count }),
    confirmLabel: t('Accept'),
    fields: [{ label: t('Why is it right?'), required: true, multiline: true, placeholder: t('For example: married couple paid into one account.') }],
    onConfirm: async ([reason]) => {
      setError(null)
      try {
        await api(`/api/payroll/runs/${month}/checks/accept`, { method: 'POST', body: { code, userIds, reason } })
        await load()
      } catch (e) { setError(fail(e)) }
    },
  })

  const groups = new Map<string, PayrollCheck[]>()
  for (const c of report.checks) groups.set(c.code, [...(groups.get(c.code) ?? []), c])

  return (
    <div className="card">
      <div className="row space">
        <h3>{t('Checks before approval')}</h3>
        {can('payroll.run') && <SettingsLink to="/payroll/checks/settings" label={t('Thresholds')} />}
      </div>
      <ErrorText error={error} />
      {report.checks.length === 0 && <p className="success">{t('No problem found.')}</p>}
      {report.checks.length > 0 && (
        <p className={report.clear ? 'success' : 'notice'}>
          {report.legal > 0 && <strong>{t('{a} against the law, to fix and calculate again.', { a: report.legal })}{' '}</strong>}
          {t('{a} warning(s) open, {b} accepted.', { a: report.warnings, b: report.accepted })}
          {report.clear && <>{' '}{t('The payroll can be approved.')}</>}
        </p>
      )}
      {[...groups.entries()].map(([code, list]) => {
        const legal = list[0].level === 'LEGAL'
        const open = list.filter((c) => !c.acceptedBy)
        return (
          <details key={code} open={open.length > 0 && (legal || groups.size <= 3)}>
            <summary>
              <span className={`badge ${legal ? 'off' : open.length ? 'warn' : 'ok'}`}>{legal ? t('Law') : open.length ? t('To check') : t('Accepted')}</span>{' '}
              <strong>{CHECK_TITLES[code] ?? code}</strong>{' '}
              <span className="muted">({open.length}/{list.length})</span>
              {canAccept && !legal && open.length > 1 && (
                <>{' '}<Button className="link" onClick={(e) => { e.preventDefault(); accept(code, null, open.length) }}>{t('Accept all')}</Button></>
              )}
            </summary>
            <div className="table-wrap">
              <table>
                <tbody>
                  {list.map((c, i) => (
                    <tr key={`${c.userId ?? 'run'}-${i}`}>
                      <td>{c.name ?? t('The whole payroll')}{c.registrationNo && <>{' '}<span className="muted">{c.registrationNo}</span></>}</td>
                      <td className="small">{c.message}</td>
                      <td className="small">
                        {c.acceptedBy
                          ? <>{t('Accepted by {a}: {b}', { a: c.acceptedBy, b: c.reason ?? '' })}</>
                          : legal
                            ? (c.userId && <Link to={`/users/${c.userId}/file`}>{t('Open the file')}</Link>)
                            : canAccept && <Button className="link" onClick={() => accept(code, c.userId === null ? null : [c.userId], 1)}>{t('Accept')}</Button>}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </details>
        )
      })}
      {report.checks.length > 0 && <p className="hint">{t('A legal problem cannot be accepted. Every other check is fixed, or accepted with a reason; calculating again starts the acceptances over.')}</p>}
    </div>
  )
}
