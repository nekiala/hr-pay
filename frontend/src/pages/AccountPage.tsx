import { useEffect, useState } from 'react'
import { api, CORRECTION_FIELDS, download, longDay, type DataCorrection, type SignedInSession, type TwoStepStatus } from '../api'
import { useAuth } from '../auth'
import { Button, Form } from '../components/Button'
import { useAsk } from '../components/Dialogs'
import { ErrorText } from '../components/ErrorText'
import { Modal } from '../components/Modal'
import { RecoveryCodes, TwoStepSetupFlow } from '../components/TwoStep'
import ChangePasswordPage from './ChangePasswordPage'

const when = (iso: string) => new Date(iso).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })

/** A browser's name from its user agent, enough to recognise it. */
function browser(agent: string | null) {
  if (!agent) return 'Unknown browser'
  const name = /Edg\//.test(agent) ? 'Edge' : /OPR\//.test(agent) ? 'Opera' : /Firefox\//.test(agent) ? 'Firefox'
    : /Chrome\//.test(agent) ? 'Chrome' : /Safari\//.test(agent) ? 'Safari' : 'Browser'
  const os = /Windows/.test(agent) ? 'Windows' : /Android/.test(agent) ? 'Android' : /iPhone|iPad/.test(agent) ? 'iPhone or iPad'
    : /Mac OS X/.test(agent) ? 'Mac' : /Linux/.test(agent) ? 'Linux' : ''
  return os ? `${name} on ${os}` : name
}

const METHODS: Record<string, string> = { password: 'Password', google: 'Google', microsoft: 'Microsoft' }
const method = (m: string) => m.split('+').map((p) => METHODS[p] ?? (p === 'totp' ? 'authenticator app' : p === 'code' ? 'emailed code' : p)).join(' + ')

export default function AccountPage() {
  const { me } = useAuth()
  if (!me) return null
  return (
    <section>
      <h2>My account</h2>
      <p className="muted">{me.firstName} {me.lastName} · {me.email} · {me.roles.join(', ')}</p>
      {me.support
        ? <p className="notice">You are on a support visit: your own account settings are in your own company.</p>
        : (
          <>
            <div className="cards2">
              <ChangePasswordPage />
              <TwoStepCard />
            </div>
            <SessionsCard />
            <MyDataCard />
          </>
        )}
    </section>
  )
}

function TwoStepCard() {
  const ask = useAsk()
  const [status, setStatus] = useState<TwoStepStatus | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [setting, setSetting] = useState(false)
  const [codes, setCodes] = useState<string[] | null>(null)

  const load = () => api<TwoStepStatus>('/api/account/two-step').then(setStatus).catch((e: Error) => setError(e.message))
  useEffect(() => { void load() }, [])

  const renew = () => ask({
    title: 'New recovery codes', confirmLabel: 'Make new codes',
    message: 'The codes you have now stop working.',
    fields: [{ label: 'Your password', type: 'password', required: true }],
    onConfirm: async ([password]) => {
      const r = await api<{ recoveryCodes: string[] }>('/api/account/two-step/recovery-codes', { method: 'POST', body: { password } })
      setCodes(r.recoveryCodes)
      await load()
    },
  })

  const turnOff = () => ask({
    title: 'Turn off the second step?', confirmLabel: 'Turn off', danger: true,
    message: 'Your password alone will then open your account.',
    fields: [{ label: 'Your password', type: 'password', required: true }],
    onConfirm: async ([password]) => {
      await api('/api/account/two-step/off', { method: 'POST', body: { password } })
      await load()
    },
  })

  if (!status) return <div className="card"><h3>Second step</h3><ErrorText error={error} /></div>
  const label = status.method === 'TOTP' ? 'Authenticator app' : 'Code by email'

  return (
    <div className="card">
      <div className="row between">
        <h3>Second step at sign-in</h3>
        {status.enabled ? <span className="badge ok">On</span> : <span className={`badge ${status.required ? 'warn' : ''}`}>Off</span>}
      </div>
      {status.enabled ? (
        <>
          <p>{label}, since {longDay(status.confirmedAt!.slice(0, 10))}.</p>
          <p className={status.recoveryCodesLeft <= 2 ? 'notice' : 'muted'}>
            {status.recoveryCodesLeft} recovery code{status.recoveryCodesLeft === 1 ? '' : 's'} left.
          </p>
          {status.required && <p className="hint">Your role requires it: you can change the method but not turn it off.</p>}
          <div className="row">
            <Button className="secondary" onClick={() => setSetting(true)}>Change method or phone</Button>
            <Button className="secondary" onClick={renew}>New recovery codes</Button>
            {!status.required && <Button className="danger" onClick={turnOff}>Turn off</Button>}
          </div>
        </>
      ) : (
        <>
          <p className="muted">After your password, ask for a six-digit code from your phone. Someone who learns your password
            still cannot sign in.</p>
          <Button onClick={() => setSetting(true)}>Turn on</Button>
        </>
      )}
      <ErrorText error={error} />
      {setting && (
        <Modal title="Second step" size="sm" onClose={() => setSetting(false)}>
          <TwoStepSetupFlow codesAvailable={status.codesAvailable}
            onDone={(c) => { setSetting(false); setCodes(c); void load() }} onCancel={() => setSetting(false)} />
        </Modal>
      )}
      {codes && (
        <Modal title="Recovery codes" size="sm" onClose={() => setCodes(null)}>
          <RecoveryCodes codes={codes} onContinue={() => setCodes(null)} continueLabel="Done" />
        </Modal>
      )}
    </div>
  )
}

function SessionsCard() {
  const ask = useAsk()
  const [sessions, setSessions] = useState<SignedInSession[]>([])
  const [error, setError] = useState<string | null>(null)
  const load = () => api<SignedInSession[]>('/api/account/sessions').then(setSessions).catch((e: Error) => setError(e.message))
  useEffect(() => { void load() }, [])
  const others = sessions.filter((s) => !s.current)

  return (
    <div className="card">
      <div className="row between">
        <h3>Where you are signed in</h3>
        {others.length > 0 && (
          <Button className="secondary" onClick={() => ask({
            title: 'Sign out everywhere else?', confirmLabel: 'Sign out the others',
            message: `${others.length} other browser${others.length === 1 ? '' : 's'} will have to sign in again.`,
            onConfirm: async () => { await api('/api/account/sessions/end-others', { method: 'POST' }); await load() },
          })}>Sign out everywhere else</Button>
        )}
      </div>
      <ul className="session-list">
        {sessions.map((s) => (
          <li key={s.id}>
            <div>
              <strong>{browser(s.userAgent)}</strong>{s.current && <span className="badge ok" style={{ marginLeft: 8 }}>This browser</span>}
              <div className="muted small">
                {method(s.method)} · signed in {when(s.signedInAt)} · last active {when(s.lastSeenAt)}{s.ip ? ` · ${s.ip}` : ''}
              </div>
            </div>
            {!s.current && (
              <Button className="secondary" onClick={async () => {
                setError(null)
                try { await api(`/api/account/sessions/${s.id}`, { method: 'DELETE' }); await load() } catch (e) { setError((e as Error).message) }
              }}>Sign out</Button>
            )}
          </li>
        ))}
      </ul>
      {sessions.length === 0 && <p className="muted">Only this browser.</p>}
      <p className="hint">Not you? Sign the browser out, then change your password.</p>
      <ErrorText error={error} />
    </div>
  )
}

function MyDataCard() {
  const [requests, setRequests] = useState<DataCorrection[]>([])
  const [error, setError] = useState<string | null>(null)
  const [asking, setAsking] = useState(false)
  const [field, setField] = useState('BIRTH_DATE')
  const [current, setCurrent] = useState('')
  const [requested, setRequested] = useState('')
  const [reason, setReason] = useState('')
  const load = () => api<DataCorrection[]>('/api/account/corrections').then(setRequests).catch((e: Error) => setError(e.message))
  useEffect(() => { void load() }, [])

  const [askError, setAskError] = useState<string | null>(null)
  const submit = async () => {
    setAskError(null)
    try {
      await api('/api/account/corrections', { method: 'POST', body: { field, current, requested, reason } })
      setAsking(false)
      setCurrent(''); setRequested(''); setReason('')
      await load()
    } catch (e) {
      setAskError((e as Error).message)
    }
  }

  return (
    <div className="card">
      <div className="row between">
        <h3>Your data</h3>
        <div className="row">
          <Button className="secondary" onClick={async () => {
            setError(null)
            try { await download('/api/account/data-export', 'my-data.json') } catch (e) { setError((e as Error).message) }
          }} pendingText="Preparing…">Download my data</Button>
          <Button className="secondary" onClick={() => setAsking(true)}>Ask for a correction</Button>
        </div>
      </div>
      <p className="muted">The download holds everything HR Pay keeps about you: your file, contracts, salaries, attendance, leave,
        payslips, evaluations and the audit entries about you. Ask HR to correct anything wrong. For a check-in, use Corrections.</p>
      {requests.length > 0 && (
        <table>
          <thead><tr><th>Asked</th><th>What</th><th>Correct value</th><th>Status</th><th>Answer</th></tr></thead>
          <tbody>
            {requests.map((r) => (
              <tr key={r.id}>
                <td>{longDay(r.createdAt.slice(0, 10))}</td>
                <td>{CORRECTION_FIELDS[r.field] ?? r.field}</td>
                <td>{r.requested}</td>
                <td><span className={`badge ${r.status === 'DONE' ? 'ok' : r.status === 'REFUSED' ? 'off' : 'warn'}`}>
                  {r.status === 'OPEN' ? 'Waiting for HR' : r.status === 'DONE' ? 'Corrected' : 'Refused'}</span></td>
                <td>{r.answer ?? ''}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <ErrorText error={error} />
      {asking && (
        <Modal title="Ask HR for a correction" size="sm" onClose={() => setAsking(false)}>
          <Form onSubmit={submit}>
            <label>What is wrong
              <select value={field} onChange={(e) => setField(e.target.value)}>
                {Object.entries(CORRECTION_FIELDS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
              </select>
            </label>
            <label>What HR Pay shows now<input value={current} onChange={(e) => setCurrent(e.target.value)} maxLength={500} /></label>
            <label>The correct value<input value={requested} onChange={(e) => setRequested(e.target.value)} required maxLength={500} /></label>
            <label>Why (optional)<textarea rows={2} value={reason} onChange={(e) => setReason(e.target.value)} maxLength={1000} /></label>
            <p className="hint">HR may ask for a document (ID card, marriage or birth certificate).</p>
            <ErrorText error={askError} />
            <div className="modal-foot">
              <Button className="secondary" onClick={() => setAsking(false)}>Cancel</Button>
              <Button type="submit">Send to HR</Button>
            </div>
          </Form>
        </Modal>
      )}
    </div>
  )
}
