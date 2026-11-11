import { useEffect, useState } from 'react'
import { useParams } from 'react-router-dom'
import { api, type MissionCheck, day } from '../api'
import { t } from '../i18n'

/** The page the QR code of an ordre de mission opens, for anyone (border officer, host): is this order genuine? */
export default function MissionCheckPage() {
  const { token } = useParams()
  const [check, setCheck] = useState<MissionCheck | null>(null)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => {
    api<MissionCheck>(`/api/public/mission-check/${token}`).then(setCheck).catch(() => setError(t('This ordre de mission is not known. It may be forged: contact the company.')))
  }, [token])
  const label = check?.status === 'VALID' ? t('Valid ordre de mission / Valid mission order')
    : check?.status === 'ENDED' ? t('Mission ended / Mission completed') : t('Cancelled: not valid / Cancelled: not valid')
  return (
    <main className="public-page">
      <div className="card" style={{ maxWidth: 560, margin: '2rem auto' }}>
        <h2>{t('Ordre de mission check')}</h2>
        {error && <p className="error">{error}</p>}
        {check && (
          <>
            <p className={check.status === 'VALID' ? 'success' : 'error'}><strong>{label}</strong></p>
            <dl className="facts">
              <dt>{t('Number')}</dt><dd>{check.number}{check.amendment > 0 ? ` · ${t('amendment {a}', { a: check.amendment })}` : ''}</dd>
              <dt>{t('Employer')}</dt><dd>{check.company}</dd>
              <dt>{t('Bearer')}</dt><dd>{check.employeeName}</dd>
              <dt>{t('Destination')}</dt><dd>{check.destination}</dd>
              <dt>{t('Dates')}</dt><dd>{day(check.startDate)} → {day(check.endDate)}</dd>
            </dl>
            <p className="muted small">{t('Checked with HR Pay. The printed order must show the same number, name, destination and dates.')}</p>
          </>
        )}
      </div>
    </main>
  )
}
