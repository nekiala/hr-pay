import { useEffect, useState } from 'react'
import { ApiError, api } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from './ErrorText'
import { Button } from './Button'

/** What the app records about people, why, who sees it and for how long. */
export function PrivacyText() {
  const [days, setDays] = useState<number | null>(null)
  useEffect(() => { api<{ gpsRetentionDays: number }>('/api/privacy/settings').then((s) => setDays(s.gpsRetentionDays)).catch(() => undefined) }, [])
  return (
    <div className="privacy-text">
      <h3>What we record</h3>
      <ul>
        <li>Your identity and contact details, your unit, contract, grade and salary.</li>
        <li>Each check-in and check-out: the time, the site, the network address, the device and, when your phone shares it, your GPS position.</li>
        <li>Leave, missions, overtime, corrections and evaluations, with who decided on them.</li>
        <li>Your payslips, the payments made to you, and the amounts declared to CNSS, DGI, INPP and ONEM.</li>
      </ul>
      <h3>Why</h3>
      <p>To count working time, pay you correctly, and meet the employer's legal duties (social security, income tax, labour inspection).</p>
      <h3>Who sees it</h3>
      <p>You see your own data. The head of your unit (and the heads above) see your attendance and requests. HR sees everyone's file. Finance sees pay and payments. Every export and change is recorded in an audit log.</p>
      <h3>How long</h3>
      <p>
        {days === null ? 'GPS positions are erased after the period HR sets.'
          : days === 0 ? 'GPS positions are kept for now; HR can set a period after which they are erased.'
            : `GPS positions are erased after ${days} days; the check-in itself (time and site) stays.`}
        {' '}Attendance and pay records are kept as long as the law requires. After you leave, your personal details are
        erased once the period your employer keeps them for has passed; the payroll amounts stay.
      </p>
      <h3>Your rights</h3>
      <p>Download a copy of all your data, and ask HR to correct anything wrong, from My account. For attendance, use
        Corrections. Bank accounts and ID numbers are stored encrypted.</p>
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
      setError(e instanceof ApiError ? e.message : 'Could not save.')
    }
  }
  return (
    <div className="modal-backdrop" role="dialog" aria-modal="true" aria-labelledby="privacy-title">
      <div className="modal card">
        <h2 id="privacy-title">How your data is used</h2>
        <PrivacyText />
        <ErrorText error={error} />
        <Button onClick={() => accept()}>I have read this</Button>
      </div>
    </div>
  )
}

export default function PrivacyPage() {
  return (
    <section className="narrow">
      <h2>Privacy</h2>
      <div className="card"><PrivacyText /></div>
    </section>
  )
}
