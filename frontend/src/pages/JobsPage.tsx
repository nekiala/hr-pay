import { useEffect, useState } from 'react'
import { api, download, fileSize, jobFinished, type Job, type JobStatus } from '../api'
import { Button } from '../components/Button'
import { ErrorText } from '../components/ErrorText'
import { locale, t } from '../i18n'

const STATUS: Record<JobStatus, [string, string]> = {
  WAITING: [t('Waiting'), ''], RUNNING: [t('Running'), 'warn'], DONE: [t('Done'), 'ok'], FAILED: [t('Failed'), 'off'],
  CANCELLED: [t('Cancelled'), ''],
}
const when = (iso: string | null) => (iso ? new Date(iso).toLocaleString(locale(), { dateStyle: 'short', timeStyle: 'short' }) : '—')
const took = (j: Job) => {
  if (!j.startedAt || !j.finishedAt) return '—'
  const s = Math.max(0, Math.round((new Date(j.finishedAt).getTime() - new Date(j.startedAt).getTime()) / 1000))
  return s < 60 ? `${s} s` : `${Math.floor(s / 60)} min ${s % 60} s`
}

/** My jobs: the long tasks the person started, their progress, and the files they made (kept 7 days). */
export default function JobsPage() {
  const [jobs, setJobs] = useState<Job[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const load = () => api<Job[]>('/api/jobs').then(setJobs).catch((e: Error) => setError(e.message))
  useEffect(() => { void load() }, [])
  // While one runs, follow it.
  const busy = jobs?.some((j) => !jobFinished(j)) ?? false
  useEffect(() => {
    if (!busy) return
    const timer = window.setInterval(() => { void load() }, 2000)
    return () => window.clearInterval(timer)
  }, [busy])

  const act = async (path: string) => {
    setError(null)
    try { await api(path, { method: 'POST' }); await load() } catch (e) { setError((e as Error).message) }
  }

  return (
    <section>
      <h2>{t('My jobs')}</h2>
      <p className="hint">{t('Long tasks (a payroll of many people, closing a month, imports, files of every payslip) run in the background. You can leave their page: the bell tells you when one ends. Files are kept 7 days.')}</p>
      <ErrorText error={error} />
      {jobs === null ? (!error && <p className="muted">{t('Loading…')}</p>) : jobs.length === 0 ? <p className="muted">{t('No job in the last 30 days.')}</p> : (
        <div className="card">
          <div className="table-wrap">
            <table>
              <thead><tr><th>{t('Job')}</th><th>{t('Started')}</th><th>{t('Status')}</th><th>{t('Took')}</th><th /></tr></thead>
              <tbody>
                {jobs.map((j) => (
                  <tr key={j.id}>
                    <td>{j.title}{j.attempts > 1 && <small className="muted">{' '}· {t('try {a}', { a: j.attempts })}</small>}
                      {j.status === 'FAILED' && j.error && <div className="error small">{j.error}</div>}
                    </td>
                    <td>{when(j.createdAt)}</td>
                    <td>
                      <span className={`badge ${STATUS[j.status][1]}`}>{STATUS[j.status][0]}</span>
                      {j.status === 'RUNNING' && (
                        <>
                          <div className="progress"><span style={{ width: `${Math.max(3, j.progress)}%` }} /></div>
                          <small className="muted">{j.progress}%{j.step ? ` · ${j.step}` : ''}</small>
                        </>
                      )}
                    </td>
                    <td>{took(j)}</td>
                    <td className="row">
                      {j.status === 'DONE' && j.fileName && (
                        <Button className="secondary" onClick={() => download(`/api/jobs/${j.id}/file`, j.fileName!).catch((e: Error) => setError(e.message))}>
                          {t('Download')}{j.fileSize ? ` (${fileSize(j.fileSize)})` : ''}
                        </Button>
                      )}
                      {j.status === 'WAITING' && <Button className="secondary" onClick={() => act(`/api/jobs/${j.id}/cancel`)}>{t('Cancel')}</Button>}
                      {(j.status === 'FAILED' || j.status === 'CANCELLED') && <Button className="secondary" onClick={() => act(`/api/jobs/${j.id}/retry`)}>{t('Start again')}</Button>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </section>
  )
}
