import { useEffect, useState } from 'react'
import { api, type AuditEntry, type Page } from '../api'
import { ErrorText } from '../components/ErrorText'
import { Button } from '../components/Button'
import { t, locale } from '../i18n'

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
      <h2>{t('Audit log')}</h2>
      <p className="muted">{t('Every sign-in and every change to users, roles and sites. Entries cannot be edited or deleted.')}</p>
      <ErrorText error={error} />
      <div className="table-wrap">
        <table>
          <thead><tr><th>{t('When')}</th><th>{t('Who')}</th><th>{t('Action')}</th><th>{t('On')}</th><th>{t('Details')}</th><th>{t('IP')}</th></tr></thead>
          <tbody>
            {data?.items.map((a) => (
              <tr key={a.id}>
                <td className="nowrap">{new Date(a.occurredAt).toLocaleString(locale())}</td>
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
          <Button className="secondary" disabled={page === 0} onClick={() => setPage(page - 1)}>{t('Newer')}</Button>
          <span>{t('Page')}{' '}{page + 1}{' '}{t('of')}{' '}{pages}</span>
          <Button className="secondary" disabled={page + 1 >= pages} onClick={() => setPage(page + 1)}>{t('Older')}</Button>
        </div>
      )}
    </section>
  )
}
