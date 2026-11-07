import { useEffect, useState } from 'react'
import { ApiError, api } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from './ErrorText'
import { Button } from './Button'
import { t } from '../i18n'

/** What the app records about people, why, who sees it and for how long. */
export function PrivacyText() {
  const [days, setDays] = useState<number | null>(null)
  useEffect(() => { api<{ gpsRetentionDays: number }>('/api/privacy/settings').then((s) => setDays(s.gpsRetentionDays)).catch(() => undefined) }, [])
  return (
    <div className="privacy-text">
      <h3>{t('What we record')}</h3>
      <ul>
        <li>{t('Your identity and contact details, your unit, contract, grade and salary.')}</li>
        <li>{t('Each check-in and check-out: the time, the site, the network address, the device and, when your phone shares it, your GPS position.')}</li>
        <li>{t('Leave, missions, overtime, corrections and evaluations, with who decided on them.')}</li>
        <li>{t('Your payslips, the payments made to you, and the amounts declared to CNSS, DGI, INPP and ONEM.')}</li>
      </ul>
      <h3>{t('Why')}</h3>
      <p>{t('To count working time, pay you correctly, and meet the employer\'s legal duties (social security, income tax, labour inspection).')}</p>
      <h3>{t('Who sees it')}</h3>
      <p>{t('You see your own data. The head of your unit (and the heads above) see your attendance and requests. HR sees everyone\'s file. Finance sees pay and payments. Every export and change is recorded in an audit log.')}</p>
      <h3>{t('How long')}</h3>
      <p>
        {days === null ? t('GPS positions are erased after the period HR sets.')
          : days === 0 ? t('GPS positions are kept for now; HR can set a period after which they are erased.')
            : t('GPS positions are erased after {a} days; the check-in itself (time and site) stays.', { a: days })}
        {' '}{t('Attendance and pay records are kept as long as the law requires. After you leave, your personal details are erased once the period your employer keeps them for has passed; the payroll amounts stay.')}</p>
      <h3>{t('Your rights')}</h3>
      <p>{t('Download a copy of all your data, and ask HR to correct anything wrong, from My account. For attendance, use Corrections. Bank accounts and ID numbers are stored encrypted.')}</p>
    </div>
  )
}

/** Shown once, at the first sign-in, until the person confirms they have read it. */
export function PrivacyGate() {
  const { me, refresh } = useAuth()
  const [error, setError] = useState<string | null>(null)
  if (!me || me.privacyAccepted) return null
  const accept = async () => {
    try {
      await api('/api/privacy/accept', { method: 'POST' })
      await refresh()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : t('Could not save.'))
    }
  }
  return (
    <div className="modal-backdrop" role="dialog" aria-modal="true" aria-labelledby="privacy-title">
      <div className="modal card">
        <h2 id="privacy-title">{t('How your data is used')}</h2>
        <PrivacyText />
        <ErrorText error={error} />
        <Button onClick={() => accept()}>{t('I have read this')}</Button>
      </div>
    </div>
  )
}

export default function PrivacyPage() {
  return (
    <section className="narrow">
      <h2>{t('Privacy')}</h2>
      <div className="card"><PrivacyText /></div>
    </section>
  )
}
