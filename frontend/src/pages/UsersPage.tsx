import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api, type Page, type User } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'

export default function UsersPage() {
  const { can } = useAuth()
  const [query, setQuery] = useState('')
  const [page, setPage] = useState(0)
  const [data, setData] = useState<Page<User> | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    const handle = setTimeout(() => {
      const params = new URLSearchParams({ page: String(page), size: '25' })
      if (query.trim()) params.set('q', query.trim())
      api<Page<User>>(`/api/users?${params}`).then(setData).catch((e: Error) => setError(e.message))
    }, 250)
    return () => clearTimeout(handle)
  }, [query, page])

  const pages = data ? Math.max(1, Math.ceil(data.total / data.size)) : 1

  return (
    <section>
      <div className="row between">
        <h2>Users</h2>
        {can('user.create') && <Link className="button" to="/users/new">New user</Link>}
      </div>
      <input className="search" placeholder="Search by name, email or registration number" value={query}
        onChange={(e) => { setQuery(e.target.value); setPage(0) }} />
      <ErrorText error={error} />
      <div className="table-wrap">
        <table>
          <thead>
            <tr><th>Name</th><th>Registration no.</th><th>Email</th><th>Phone</th><th>Roles</th><th>Site</th><th>Status</th></tr>
          </thead>
          <tbody>
            {data?.items.map((u) => (
              <tr key={u.id}>
                <td><Link to={`/users/${u.id}`}>{u.lastName} {u.firstName}</Link></td>
                <td>{u.registrationNo}</td>
                <td>{u.email}</td>
                <td>{u.phone}</td>
                <td>{u.roles.join(', ')}</td>
                <td>{u.siteName ?? '—'}</td>
                <td><span className={`badge ${u.status === 'ACTIVE' ? 'ok' : 'off'}`}>{u.status === 'ACTIVE' ? 'Active' : 'Disabled'}</span></td>
              </tr>
            ))}
            {data && data.items.length === 0 && <tr><td colSpan={7} className="muted">No users found.</td></tr>}
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
