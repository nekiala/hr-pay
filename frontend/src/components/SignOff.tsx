import { useEffect, useState, type ReactNode } from 'react'
import { api, longDay, type CertificationStatus } from '../api'
import { useAuth } from '../auth'
import { Button } from './Button'
import { useAsk } from './Dialogs'
import { plural, t } from '../i18n'

/**
 * Whether a payroll expert signed off the rates and formulas: the workbook to send them, the sign-off to record,
 * and the rates changed since. Before any sign-off, the page's own notice about sample values shows.
 */
export function SignOff({ notice }: { notice: ReactNode }) {
  const { can } = useAuth()
  const ask = useAsk()
  const [status, setStatus] = useState<CertificationStatus | null>(null)
  useEffect(() => { api<CertificationStatus>('/api/payroll/certification').then(setStatus).catch(() => undefined) }, [])
  const last = status?.last
  const changed = (status?.ratesChangedSince ?? 0) + (status?.bracketsChangedSince ?? 0)

  const record = () => ask({
    title: t('Record the sign-off'),
    message: t('Record it once the expert has checked the workbook and signed it. Keep the signed copy.'),
    confirmLabel: t('Record'),
    fields: [
      { label: t('Signed off by (name and title)'), required: true, defaultValue: last?.certifiedBy ?? '' },
      { label: t('Date of the sign-off'), required: true, type: 'date', defaultValue: new Date().toISOString().slice(0, 10) },
      { label: t('Note (optional)'), multiline: true },
    ],
    onConfirm: async ([by, on, note]) => {
      setStatus(await api<CertificationStatus>('/api/payroll/certification', { method: 'POST', body: { certifiedBy: by, certifiedOn: on, note } }))
    },
  })

  return (
    <div className={last && changed === 0 ? 'success' : 'notice'}>
      {last
        ? <>{t('Rates and formulas signed off by {a} on {b}.', { a: last.certifiedBy, b: longDay(last.certifiedOn) })}
            {changed > 0 && <>{' '}<strong>{plural('{n} change since then.', '{n} changes since then.', changed)}</strong></>}</>
        : notice}
      <div className="row">
        <a className="button secondary" href="/api/payroll/certification.xlsx">{t('Workbook to check (Excel)')}</a>
        {can('masterdata.manage') && <Button className="secondary" onClick={record}>{t('Record the sign-off')}</Button>}
      </div>
    </div>
  )
}
