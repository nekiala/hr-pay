import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api, type Site } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'

export default function SitesPage() {
  const { can } = useAuth()
  const [sites, setSites] = useState<Site[]>([])
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    api<Site[]>('/api/sites').then(setSites).catch((e: Error) => setError(e.message))
  }, [])

  return (
    <section>
      <div className="row between">
        <h2>Sites</h2>
        {can('site.manage') && <Link className="button" to="/sites/new">New site</Link>}
      </div>
      <ErrorText error={error} />
      <div className="table-wrap">
        <table>
          <thead><tr><th>Name</th><th>Code</th><th>Time zone</th><th>Allowed networks</th><th>GPS area</th><th>Status</th></tr></thead>
          <tbody>
            {sites.map((s) => (
              <tr key={s.id}>
                <td><Link to={`/sites/${s.id}`}>{s.name}</Link></td>
                <td>{s.code}</td>
                <td>{s.timeZone}</td>
                <td>{s.ipRanges.map((r) => r.cidr).join(', ') || '—'}</td>
                <td>{s.radiusM ? `${s.radiusM} m${s.gpsRequired ? ', required' : ''}` : '—'}</td>
                <td><span className={`badge ${s.active ? 'ok' : 'off'}`}>{s.active ? 'Active' : 'Inactive'}</span></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  )
}
