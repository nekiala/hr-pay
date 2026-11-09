import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api, type Page, type User , personName } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'
import { UnitSelect } from '../components/OrgCard'
import type { OrgUnit } from '../org'
import { Button } from '../components/Button'
import { SettingsLink } from '../components/SettingsLink'
import { t } from '../i18n'

export default function UsersPage() {
  const { can } = useAuth()
  const [query, setQuery] = useState('')
  const [unit, setUnit] = useState('')
  const [noContract, setNoContract] = useState(false)
  const [units, setUnits] = useState<OrgUnit[]>([])
  const [page, setPage] = useState(0)
  const [data, setData] = useState<Page<User> | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => { api<OrgUnit[]>('/api/org/units').then(setUnits).catch(() => undefined) }, [])

  useEffect(() => {
    const handle = setTimeout(() => {
      const params = new URLSearchParams({ page: String(page), size: '25' })
      if (query.trim()) params.set('q', query.trim())
      if (unit) params.set('unitId', unit)
      if (noContract) params.set('noContract', 'true')
      api<Page<User>>(`/api/users?${params}`).then(setData).catch((e: Error) => setError(e.message))
    }, 250)
    return () => clearTimeout(handle)
  }, [query, unit, noContract, page])

  const pages = data ? Math.max(1, Math.ceil(data.total / data.size)) : 1

  return (
    <section>
      <div className="row between">
        <h2>{t('Users')}</h2>
        <div className="row">
          {can('matricule.manage') && <SettingsLink to="/users/matricule" label={t('Employee ID')} />}
          {can('user.create') && <Link className="button" to="/users/new">{t('New user')}</Link>}
        </div>
      </div>
      <input className="search" placeholder={t('Search by name, email or registration number')} value={query}
        onChange={(e) => { setQuery(e.target.value); setPage(0) }} />
      <label className="inline-filter">{t('Unit (with the units below it)')}<UnitSelect units={units} value={unit} placeholder={t('All units')} onChange={(v) => { setUnit(v); setPage(0) }} />
      </label>
      <label className="check inline-filter"><input type="checkbox" checked={noContract} onChange={(e) => { setNoContract(e.target.checked); setPage(0) }} />
        {t('Only employees with no contract today')}</label>
      <ErrorText error={error} />
      <div className="table-wrap">
        <table>
          <thead>
            <tr><th>{t('Name')}</th><th>{t('Registration no.')}</th><th>{t('Email')}</th><th>{t('Phone')}</th><th>{t('Roles')}</th><th>{t('Unit')}</th><th>{t('Site')}</th><th>{t('Status')}</th></tr>
          </thead>
          <tbody>
            {data?.items.map((u) => (
              <tr key={u.id}>
                <td><Link to={`/users/${u.id}`}>{personName(u)}</Link></td>
                <td>{u.registrationNo}</td>
                <td>{u.email}</td>
                <td>{u.phone}</td>
                <td>{u.roles.join(', ')}</td>
                <td>{u.unitId ? <Link to={`/org/units/${u.unitId}`}>{u.unitName}</Link> : '—'}</td>
                <td>{u.siteName ?? '—'}</td>
                <td><span className={`badge ${u.status === 'ACTIVE' ? 'ok' : 'off'}`}>{u.status === 'ACTIVE' ? t('Active') : t('Disabled')}</span>
                  {u.status === 'ACTIVE' && !u.underContract && !u.notEmployee && <>{' '}<span className="badge warn">{t('No contract')}</span></>}
                  {u.notEmployee && <>{' '}<span className="badge off">{t('Not an employee')}</span></>}</td>
              </tr>
            ))}
            {data && data.items.length === 0 && <tr><td colSpan={8} className="muted">{t('No users found.')}</td></tr>}
          </tbody>
        </table>
      </div>
      {data && (
        <div className="row pager">
          <Button className="secondary" disabled={page === 0} onClick={() => setPage(page - 1)}>{t('Previous')}</Button>
          <span>{t('Page')}{' '}{page + 1}{' '}{t('of')}{' '}{pages} · {data.total}{' '}{t('users')}</span>
          <Button className="secondary" disabled={page + 1 >= pages} onClick={() => setPage(page + 1)}>{t('Next')}</Button>
        </div>
      )}
    </section>
  )
}
