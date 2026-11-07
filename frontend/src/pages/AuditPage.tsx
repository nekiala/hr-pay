import { useEffect, useState } from 'react'
import { api, type AuditEntry, type Page } from '../api'
import { ErrorText } from '../components/ErrorText'

export default function AuditPage() {
  const [page, setPage] = useState(0)
  const [data, setData] = useState<Page<AuditEntry> | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    api<Page<AuditEntry>>(`/api/audit?page=${page}&size=50`).then(setData).catch((e: Error) => setError(e.message))
  }, [page])

  const pages = data ? Math.max(1, Math.ceil(data.total / data.size)) : 1

  return (
    <section>
      <h2>Audit log</h2>
      <p className="muted">Every sign-in and every change to users, roles and sites. Entries cannot be edited or deleted.</p>
      <ErrorText error={error} />
      <div className="table-wrap">
        <table>
          <thead><tr><th>When</th><th>Who</th><th>Action</th><th>On</th><th>Details</th><th>IP</th></tr></thead>
          <tbody>
            {data?.items.map((a) => (
              <tr key={a.id}>
                <td className="nowrap">{new Date(a.occurredAt).toLocaleString()}</td>
                <td>{a.actor}</td>
                <td><code>{a.action}</code></td>
                <td>{a.entityType ? `${a.entityType} ${a.entityId ?? ''}` : '—'}</td>
                <td className="details">{a.details}</td>
                <td>{a.ip}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {data && (
        <div className="row pager">
          <button className="secondary" disabled={page === 0} onClick={() => setPage(page - 1)}>Newer</button>
          <span>Page {page + 1} of {pages}</span>
          <button className="secondary" disabled={page + 1 >= pages} onClick={() => setPage(page + 1)}>Older</button>
        </div>
      )}
    </section>
  )
}
