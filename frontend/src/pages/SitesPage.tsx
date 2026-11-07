import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { ApiError, api, type Site } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'
import { Button } from '../components/Button'
import { plural, t } from '../i18n'

export default function SitesPage() {
  const { can } = useAuth()
  const [sites, setSites] = useState<Site[]>([])
  const [error, setError] = useState<string | null>(null)

  const [retention, setRetention] = useState<number | null>(null)
  const [message, setMessage] = useState<string | null>(null)

  useEffect(() => {
    api<Site[]>('/api/sites').then(setSites).catch((e: Error) => setError(e.message))
    api<{ gpsRetentionDays: number }>('/api/privacy/settings').then((s) => setRetention(s.gpsRetentionDays)).catch(() => undefined)
  }, [])

  const saveRetention = async () => {
    setError(null)
    setMessage(null)
    try {
      const r = await api<{ erased: number }>('/api/privacy/settings', { method: 'PUT', body: { gpsRetentionDays: retention } })
      setMessage(plural('Saved. {n} older GPS position erased.', 'Saved. {n} older GPS positions erased.', r.erased))
    } catch (e) {
      setError(e instanceof ApiError ? e.message : t('Could not save.'))
    }
  }

  return (
    <section>
      <div className="row between">
        <h2>{t('Sites')}</h2>
        {can('site.manage') && <Link className="button" to="/sites/new">{t('New site')}</Link>}
      </div>
      <ErrorText error={error} />
      <div className="table-wrap">
        <table>
          <thead><tr><th>{t('Name')}</th><th>{t('Code')}</th><th>{t('Time zone')}</th><th>{t('Allowed networks')}</th><th>{t('GPS area')}</th><th>{t('Status')}</th></tr></thead>
          <tbody>
            {sites.map((s) => (
              <tr key={s.id}>
                <td><Link to={`/sites/${s.id}`}>{s.name}</Link></td>
                <td>{s.code}</td>
                <td>{s.timeZone}</td>
                <td>{s.ipRanges.map((r) => r.cidr).join(', ') || '—'}</td>
                <td>{s.radiusM ? `${s.radiusM} m${s.gpsRequired ? t(', required') : ''}` : '—'}</td>
                <td><span className={`badge ${s.active ? 'ok' : 'off'}`}>{s.active ? t('Active') : t('Inactive')}</span></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {retention !== null && (
        <div className="card narrow" style={{ marginTop: 16 }}>
          <h3>{t('GPS positions')}</h3>
          <p className="hint">{t('The position a phone shares at check-in is erased after this many days; the check-in itself (time, site, method) stays. 0 keeps positions. Everyone sees this period in the')}{' '}<Link to="/privacy">{t('privacy notice')}</Link>.</p>
          {message && <p className="success">{message}</p>}
          <div className="row">
            <label>{t('Keep for (days)')}<input type="number" min={0} max={3650} className="narrow-input" value={retention} disabled={!can('site.manage')}
              onChange={(e) => setRetention(Number(e.target.value))} /></label>
            {can('site.manage') && <Button className="secondary" onClick={() => saveRetention()}>{t('Save')}</Button>}
          </div>
        </div>
      )}
    </section>
  )
}
