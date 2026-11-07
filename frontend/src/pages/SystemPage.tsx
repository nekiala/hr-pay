import { useEffect, useState } from 'react'
import { api } from '../api'
import { ErrorText } from '../components/ErrorText'

type Backup = { id: number; startedAt: string; finishedAt: string | null; fileName: string | null; sizeBytes: number | null; status: 'RUNNING' | 'OK' | 'FAILED'; offsite: boolean; message: string | null }
type Job = { name: string; lockedAt: string | null; lockedBy: string | null; lastOkAt: string | null; lastError: string | null; lastErrorAt: string | null }
type Alert = { id: number; kind: string; summary: string; detail: string | null; firstAt: string; lastAt: string; occurrences: number; notifiedAt: string | null }
type Status = {
  version: string; builtAt: string | null; javaVersion: string; startedAt: string; uptimeSeconds: number
  databaseBytes: number; activeSessions: number; employees: number; backupsExpected: boolean
  backups: Backup[]; jobs: Job[]; alerts: Alert[]
}

const JOBS: Record<string, string> = {
  'auto-close': 'Close days left open', 'anniversary-raises': 'Anniversary raises', 'exchange-rates': 'BCC exchange rates',
  'gps-erase': 'Erase old GPS positions', 'backup-watch': 'Check the backups',
}
const KINDS: Record<string, string> = { ERROR: 'Error', JOB_FAILED: 'Job failed', BACKUP_FAILED: 'Backup failed', BACKUP_MISSING: 'No recent backup' }
const when = (iso: string | null) => (iso ? new Date(iso).toLocaleString() : '—')
const size = (b: number | null) => (b === null ? '—' : b > 1e9 ? `${(b / 1e9).toFixed(1)} GB` : b > 1e6 ? `${(b / 1e6).toFixed(1)} MB` : `${Math.round(b / 1e3)} KB`)
const duration = (s: number) => (s >= 86400 ? `${Math.floor(s / 86400)} d ${Math.floor((s % 86400) / 3600)} h` : `${Math.floor(s / 3600)} h ${Math.floor((s % 3600) / 60)} min`)

/** For administrators: is the system healthy, are the backups running, what went wrong lately. */
export default function SystemPage() {
  const [s, setS] = useState<Status | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [open, setOpen] = useState<number | null>(null)
  useEffect(() => { api<Status>('/api/system/status').then(setS).catch((e: Error) => setError(e.message)) }, [])

  if (!s) return <section><h2>System</h2><ErrorText error={error} />{!error && <p className="muted">Loading…</p>}</section>
  const backups = s.backups.filter((b) => !b.fileName?.startsWith('restore-test:') && !b.fileName?.startsWith('restore:'))
  const tests = s.backups.filter((b) => b.fileName?.startsWith('restore-test:'))
  const lastOk = backups.find((b) => b.status === 'OK')
  const stale = !lastOk || Date.now() - new Date(lastOk.finishedAt ?? lastOk.startedAt).getTime() > 26 * 3600 * 1000

  return (
    <section>
      <h2>System</h2>
      <div className="tiles">
        <div className="tile"><span>Version</span><strong>{s.version}</strong><small>built {when(s.builtAt)} · Java {s.javaVersion}</small></div>
        <div className="tile"><span>Running for</span><strong>{duration(s.uptimeSeconds)}</strong><small>since {when(s.startedAt)}</small></div>
        <div className="tile"><span>Database</span><strong>{size(s.databaseBytes)}</strong><small>{s.employees} active people · {s.activeSessions} signed in</small></div>
        <div className={`tile${s.backupsExpected && stale ? ' warn' : ''}`}>
          <span>Last backup</span>
          <strong>{lastOk ? when(lastOk.finishedAt) : 'None yet'}</strong>
          <small>{!s.backupsExpected ? 'No backup job on this server (development)' : lastOk ? `${size(lastOk.sizeBytes)}${lastOk.offsite ? ' · copied off-site' : ' · on this server only'}` : 'The backup job has not run yet'}</small>
        </div>
      </div>

      <div className="card">
        <h3>Alerts</h3>
        <p className="hint">Unexpected errors, failed jobs and backup problems. People with the "system status" permission get an email, at most one an hour for the same problem.</p>
        {s.alerts.length === 0 ? <p className="muted">No alerts.</p> : (
          <div className="table-wrap">
            <table>
              <thead><tr><th>Last seen</th><th>Kind</th><th>Problem</th><th className="num">Times</th><th>First seen</th></tr></thead>
              <tbody>
                {s.alerts.map((a) => (
                  <tr key={a.id} onClick={() => setOpen(open === a.id ? null : a.id)} className="clickable">
                    <td>{when(a.lastAt)}</td><td>{KINDS[a.kind] ?? a.kind}</td>
                    <td>{a.summary}{open === a.id && a.detail && <pre className="detail">{a.detail}</pre>}</td>
                    <td className="num">{a.occurrences}</td><td>{when(a.firstAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      <div className="card">
        <h3>Backups</h3>
        {backups.length === 0 ? <p className="muted">No backup recorded.</p> : (
          <div className="table-wrap">
            <table>
              <thead><tr><th>Started</th><th>File</th><th className="num">Size</th><th>Result</th><th>Off-site</th></tr></thead>
              <tbody>
                {backups.map((b) => (
                  <tr key={b.id}>
                    <td>{when(b.startedAt)}</td><td>{b.fileName}</td><td className="num">{size(b.sizeBytes)}</td>
                    <td>{b.status === 'OK' ? 'Done' : b.status === 'RUNNING' ? 'Running' : <span className="error">Failed{b.message ? `: ${b.message}` : ''}</span>}</td>
                    <td>{b.offsite ? 'Yes' : 'No'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {tests.length > 0 && <p className="hint">Last restore test: {when(tests[0].finishedAt)}, {tests[0].status === 'OK' ? 'passed' : 'failed'}. {tests[0].message}</p>}
      </div>

      <div className="card">
        <h3>Scheduled jobs</h3>
        <div className="table-wrap">
          <table>
            <thead><tr><th>Job</th><th>Last run</th><th>Last success</th><th>Last error</th></tr></thead>
            <tbody>
              {s.jobs.map((j) => (
                <tr key={j.name}>
                  <td>{JOBS[j.name] ?? j.name}</td><td>{when(j.lockedAt)}</td><td>{when(j.lastOkAt)}</td>
                  <td>{j.lastError ? <span className="error">{when(j.lastErrorAt)}: {j.lastError}</span> : '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </section>
  )
}
