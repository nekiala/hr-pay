import { useEffect, useState, type ChangeEvent, type FormEvent } from 'react'
import { api, MODULE_NAMES, type LicenceStatus } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'
import { Button, Form } from '../components/Button'

const STATES: Record<LicenceStatus['state'], { label: string; tone: string }> = {
  VALID: { label: 'Valid', tone: 'ok' },
  EXPIRING: { label: 'Ends soon', tone: 'warn' },
  GRACE: { label: 'Grace period', tone: 'warn' },
  EVALUATION: { label: 'Evaluation', tone: 'warn' },
  LOCKED: { label: 'Changes stopped', tone: 'off' },
}

const day = (d: string | null) => d ? new Date(d + 'T00:00:00').toLocaleDateString(undefined, { day: 'numeric', month: 'long', year: 'numeric' }) : '—'

/** On-premise installations: the licence from the provider, and where to install its renewal. */
export default function LicencePage() {
  const { refresh } = useAuth()
  const [status, setStatus] = useState<LicenceStatus | null>(null)
  const [content, setContent] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)

  useEffect(() => { api<LicenceStatus>('/api/platform/licence').then(setStatus).catch((e: Error) => setError(e.message)) }, [])

  const readFile = async (e: ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (file) setContent(await file.text())
  }

  const install = async (e: FormEvent) => {
    e.preventDefault()
    setError(null); setMessage(null)
    try {
      setStatus(await api<LicenceStatus>('/api/platform/licence', { method: 'POST', body: { content } }))
      setContent('')
      setMessage('The licence is installed.')
      await refresh()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not install the licence.')
    }
  }

  const l = status?.licence
  const state = status ? STATES[status.state] : null
  return (
    <section className="narrow">
      <h2>Licence</h2>
      <ErrorText error={error} />
      {message && <p className="success">{message}</p>}
      {status && state && (
        <div className="card">
          <div className="row between">
            <h3>{l ? l.licensee : 'No licence installed'}</h3>
            <span className={`badge ${state.tone}`}>{state.label}</span>
          </div>
          {status.message && <p className={status.state === 'LOCKED' ? 'error' : 'notice'}>{status.message}</p>}
          {l ? (
            <dl className="facts">
              <dt>Licence</dt><dd className="mono">{l.id}</dd>
              <dt>Valid until</dt><dd>{day(l.expires)}</dd>
              <dt>Changes stop on</dt><dd>{day(status.locksOn)} (after {l.graceDays ?? 30} days of grace)</dd>
              <dt>Modules</dt><dd>{l.modules.map((m) => MODULE_NAMES[m]).join(', ')}</dd>
              <dt>Employees</dt><dd>{l.maxEmployees === null ? 'No limit' : `Up to ${l.maxEmployees}`}</dd>
              <dt>Companies</dt><dd>{status.companies} of {l.maxCompanies ?? 1}</dd>
            </dl>
          ) : (
            <p className="muted">This installation runs as an evaluation copy until {day(status.evaluationEnds)}: every module, up to 50 employees and one company.</p>
          )}
        </div>
      )}
      <div className="card">
        <h3>Install a licence</h3>
        <p className="hint">Your provider sends a .licence file. It is checked here, without an internet connection, and replaces the current licence.</p>
        <Form onSubmit={install}>
          <label>Licence file<input type="file" accept=".licence,.txt" onChange={readFile} /></label>
          <label>Or paste its content<textarea rows={5} className="mono" value={content} onChange={(e) => setContent(e.target.value)} /></label>
          <Button type="submit" disabled={!content.trim()}>Install licence</Button>
        </Form>
      </div>
    </section>
  )
}
