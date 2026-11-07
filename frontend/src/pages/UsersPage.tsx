import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api, type Page, type User } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'
import { UnitSelect } from '../components/OrgCard'
import type { OrgUnit } from '../org'

export default function UsersPage() {
  const { can } = useAuth()
  const [query, setQuery] = useState('')
  const [unit, setUnit] = useState('')
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
      api<Page<User>>(`/api/users?${params}`).then(setData).catch((e: Error) => setError(e.message))
    }, 250)
    return () => clearTimeout(handle)
  }, [query, unit, page])

  const pages = data ? Math.max(1, Math.ceil(data.total / data.size)) : 1

  return (
    <section>
      <div className="row between">
        <h2>Users</h2>
        {can('user.create') && <Link className="button" to="/users/new">New user</Link>}
      </div>
      <input className="search" placeholder="Search by name, email or registration number" value={query}
        onChange={(e) => { setQuery(e.target.value); setPage(0) }} />
      <label className="inline-filter">Unit (with the units below it)
        <UnitSelect units={units} value={unit} placeholder="All units" onChange={(v) => { setUnit(v); setPage(0) }} />
      </label>
      <ErrorText error={error} />
      <div className="table-wrap">
        <table>
          <thead>
            <tr><th>Name</th><th>Registration no.</th><th>Email</th><th>Phone</th><th>Roles</th><th>Unit</th><th>Site</th><th>Status</th></tr>
          </thead>
          <tbody>
            {data?.items.map((u) => (
              <tr key={u.id}>
                <td><Link to={`/users/${u.id}`}>{u.lastName} {u.firstName}</Link></td>
                <td>{u.registrationNo}</td>
                <td>{u.email}</td>
                <td>{u.phone}</td>
                <td>{u.roles.join(', ')}</td>
                <td>{u.unitId ? <Link to={`/org/units/${u.unitId}`}>{u.unitName}</Link> : '—'}</td>
                <td>{u.siteName ?? '—'}</td>
                <td><span className={`badge ${u.status === 'ACTIVE' ? 'ok' : 'off'}`}>{u.status === 'ACTIVE' ? 'Active' : 'Disabled'}</span></td>
              </tr>
            ))}
            {data && data.items.length === 0 && <tr><td colSpan={8} className="muted">No users found.</td></tr>}
          </tbody>
        </table>
      </div>
      {data && (
        <div className="row pager">
          <button className="secondary" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</button>
          <span>Page {page + 1} of {pages} · {data.total} users</span>
          <button className="secondary" disabled={page + 1 >= pages} onClick={() => setPage(page + 1)}>Next</button>
        </div>
      )}
    </section>
  )
}
