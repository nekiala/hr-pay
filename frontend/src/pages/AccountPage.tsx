import { useEffect, useState } from 'react'
import { api, CORRECTION_FIELDS, download, longDay, type DataCorrection, type PushSettings, type SignedInSession, type TwoStepStatus } from '../api'
import { currentSubscription, installed, isIos, pushSupported, turnOffPush, turnOnPush, useInstall } from '../pwa'
import { useAuth } from '../auth'
import { Button, Form } from '../components/Button'
import { useAsk } from '../components/Dialogs'
import { ErrorText } from '../components/ErrorText'
import { Modal } from '../components/Modal'
import { LanguageCard } from '../components/Language'
import { RecoveryCodes, TwoStepSetupFlow } from '../components/TwoStep'
import ChangePasswordPage from './ChangePasswordPage'
import { locale, plural, t } from '../i18n'

const when = (iso: string) => new Date(iso).toLocaleString(locale(), { dateStyle: 'medium', timeStyle: 'short' })

/** A browser's name from its user agent, enough to recognise it. */
function browser(agent: string | null) {
  if (!agent) return t('Unknown browser')
  const name = /Edg\//.test(agent) ? t('Edge') : /OPR\//.test(agent) ? t('Opera') : /Firefox\//.test(agent) ? t('Firefox')
    : /Chrome\//.test(agent) ? t('Chrome') : /Safari\//.test(agent) ? t('Safari') : t('Browser')
  const os = /Windows/.test(agent) ? t('Windows') : /Android/.test(agent) ? t('Android') : /iPhone|iPad/.test(agent) ? t('iPhone or iPad')
    : /Mac OS X/.test(agent) ? t('Mac') : /Linux/.test(agent) ? t('Linux') : ''
  return os ? t('{a} on {b}', { a: name, b: os }) : name
}

const METHODS: Record<string, string> = { password: 'Password', google: 'Google', microsoft: 'Microsoft' }
const method = (m: string) => m.split('+').map((p) => METHODS[p] ?? (p === 'totp' ? t('authenticator app') : p === 'code' ? t('emailed code') : p)).join(' + ')

export default function AccountPage() {
  const { me } = useAuth()
  if (!me) return null
  return (
    <section>
      <h2>{t('My account')}</h2>
      <p className="muted">{me.firstName} {me.lastName} · {me.email} · {me.roles.join(', ')}</p>
      {me.support
        ? <p className="notice">{t('You are on a support visit: your own account settings are in your own company.')}</p>
        : (
          <>
            <div className="cards2">
              <ChangePasswordPage />
              <TwoStepCard />
            </div>
            <LanguageCard me={me} />
            <PhoneCard />
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
    title: t('New recovery codes'), confirmLabel: t('Make new codes'),
    message: t('The codes you have now stop working.'),
    fields: [{ label: t('Your password'), type: 'password', required: true }],
    onConfirm: async ([password]) => {
      const r = await api<{ recoveryCodes: string[] }>('/api/account/two-step/recovery-codes', { method: 'POST', body: { password } })
      setCodes(r.recoveryCodes)
      await load()
    },
  })

  const turnOff = () => ask({
    title: t('Turn off the second step?'), confirmLabel: t('Turn off'), danger: true,
    message: t('Your password alone will then open your account.'),
    fields: [{ label: t('Your password'), type: 'password', required: true }],
    onConfirm: async ([password]) => {
      await api('/api/account/two-step/off', { method: 'POST', body: { password } })
      await load()
    },
  })

  if (!status) return <div className="card"><h3>{t('Second step')}</h3><ErrorText error={error} /></div>
  const label = status.method === 'TOTP' ? t('Authenticator app') : t('Code by email')

  return (
    <div className="card">
      <div className="row between">
        <h3>{t('Second step at sign-in')}</h3>
        {status.enabled ? <span className="badge ok">{t('On')}</span> : <span className={`badge ${status.required ? 'warn' : ''}`}>{t('Off')}</span>}
      </div>
      {status.enabled ? (
        <>
          <p>{label}{t(', since')}{' '}{longDay(status.confirmedAt!.slice(0, 10))}.</p>
          <p className={status.recoveryCodesLeft <= 2 ? 'notice' : 'muted'}>
            {plural('{n} recovery code left.', '{n} recovery codes left.', status.recoveryCodesLeft)}</p>
          {status.required && <p className="hint">{t('Your role requires it: you can change the method but not turn it off.')}</p>}
          <div className="row">
            <Button className="secondary" onClick={() => setSetting(true)}>{t('Change method or phone')}</Button>
            <Button className="secondary" onClick={renew}>{t('New recovery codes')}</Button>
            {!status.required && <Button className="danger" onClick={turnOff}>{t('Turn off')}</Button>}
          </div>
        </>
      ) : (
        <>
          <p className="muted">{t('After your password, ask for a six-digit code from your phone. Someone who learns your password still cannot sign in.')}</p>
          <Button onClick={() => setSetting(true)}>{t('Turn on')}</Button>
        </>
      )}
      <ErrorText error={error} />
      {setting && (
        <Modal title={t('Second step')} size="sm" onClose={() => setSetting(false)}>
          <TwoStepSetupFlow codesAvailable={status.codesAvailable}
            onDone={(c) => { setSetting(false); setCodes(c); void load() }} onCancel={() => setSetting(false)} />
        </Modal>
      )}
      {codes && (
        <Modal title={t('Recovery codes')} size="sm" onClose={() => setCodes(null)}>
          <RecoveryCodes codes={codes} onContinue={() => setCodes(null)} continueLabel={t('Done')} />
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
        <h3>{t('Where you are signed in')}</h3>
        {others.length > 0 && (
          <Button className="secondary" onClick={() => ask({
            title: t('Sign out everywhere else?'), confirmLabel: t('Sign out the others'),
            message: plural('{n} other browser will have to sign in again.', '{n} other browsers will have to sign in again.', others.length),
            onConfirm: async () => { await api('/api/account/sessions/end-others', { method: 'POST' }); await load() },
          })}>{t('Sign out everywhere else')}</Button>
        )}
      </div>
      <ul className="session-list">
        {sessions.map((s) => (
          <li key={s.id}>
            <div>
              <strong>{browser(s.userAgent)}</strong>{s.current && <span className="badge ok" style={{ marginLeft: 8 }}>{t('This browser')}</span>}
              <div className="muted small">
                {method(s.method)}{' '}{t('· signed in')}{' '}{when(s.signedInAt)}{' '}{t('· last active')}{' '}{when(s.lastSeenAt)}{s.ip ? ` · ${s.ip}` : ''}
              </div>
            </div>
            {!s.current && (
              <Button className="secondary" onClick={async () => {
                setError(null)
                try { await api(`/api/account/sessions/${s.id}`, { method: 'DELETE' }); await load() } catch (e) { setError((e as Error).message) }
              }}>{t('Sign out')}</Button>
            )}
          </li>
        ))}
      </ul>
      {sessions.length === 0 && <p className="muted">{t('Only this browser.')}</p>}
      <p className="hint">{t('Not you? Sign the browser out, then change your password.')}</p>
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
        <h3>{t('Your data')}</h3>
        <div className="row">
          <Button className="secondary" onClick={async () => {
            setError(null)
            try { await download('/api/account/data-export', 'my-data.json') } catch (e) { setError((e as Error).message) }
          }} pendingText={t('Preparing…')}>{t('Download my data')}</Button>
          <Button className="secondary" onClick={() => setAsking(true)}>{t('Ask for a correction')}</Button>
        </div>
      </div>
      <p className="muted">{t('Everything HR Pay keeps about you, in one download. Ask HR to correct anything wrong.')}</p>
      {requests.length > 0 && (
        <table>
          <thead><tr><th>{t('Asked')}</th><th>{t('What')}</th><th>{t('Correct value')}</th><th>{t('Status')}</th><th>{t('Answer')}</th></tr></thead>
          <tbody>
            {requests.map((r) => (
              <tr key={r.id}>
                <td>{longDay(r.createdAt.slice(0, 10))}</td>
                <td>{CORRECTION_FIELDS[r.field] ?? r.field}</td>
                <td>{r.requested}</td>
                <td><span className={`badge ${r.status === 'DONE' ? 'ok' : r.status === 'REFUSED' ? 'off' : 'warn'}`}>
                  {r.status === 'OPEN' ? t('Waiting for HR') : r.status === 'DONE' ? t('Corrected') : t('Refused')}</span></td>
                <td>{r.answer ?? ''}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <ErrorText error={error} />
      {asking && (
        <Modal title={t('Ask HR for a correction')} size="sm" onClose={() => setAsking(false)}>
          <Form onSubmit={submit}>
            <label>{t('What is wrong')}<select value={field} onChange={(e) => setField(e.target.value)}>
                {Object.entries(CORRECTION_FIELDS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
              </select>
            </label>
            <label>{t('What HR Pay shows now')}<input value={current} onChange={(e) => setCurrent(e.target.value)} maxLength={500} /></label>
            <label>{t('The correct value')}<input value={requested} onChange={(e) => setRequested(e.target.value)} required maxLength={500} /></label>
            <label>{t('Why (optional)')}<textarea rows={2} value={reason} onChange={(e) => setReason(e.target.value)} maxLength={1000} /></label>
            <p className="hint">{t('HR may ask for a document (ID card, marriage or birth certificate).')}</p>
            <ErrorText error={askError} />
            <div className="modal-foot">
              <Button className="secondary" onClick={() => setAsking(false)}>{t('Cancel')}</Button>
              <Button type="submit">{t('Send to HR')}</Button>
            </div>
          </Form>
        </Modal>
      )}
    </div>
  )
}

/** The app on the phone: install it, and get notifications on it. */
function PhoneCard() {
  const { canInstall, install } = useInstall()
  const [settings, setSettings] = useState<PushSettings | null>(null)
  const [here, setHere] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const load = async () => {
    const s = await api<PushSettings>('/api/notifications/push').catch(() => null)
    setSettings(s)
    const sub = await currentSubscription().catch(() => null)
    setHere(!!sub && !!s?.phones.length)
  }
  useEffect(() => { void load() }, [])
  const denied = pushSupported() && Notification.permission === 'denied'

  return (
    <div className="card">
      <h3>{t('The app on your phone')}</h3>
      <p className="small muted">{t('HR Pay installs like an app: check in (even without a connection), ask for leave, read your payslips and documents, and approve requests.')}</p>
      {installed() ? <p className="small success">{t('Installed on this device.')}</p>
        : canInstall ? <Button onClick={install}>{t('Install the app')}</Button>
          : isIos() ? <p className="small">{t('On an iPhone: open HR Pay in Safari, tap Share, then Add to Home Screen.')}</p>
            : <p className="small muted">{t('To install: open the browser menu and choose Install app or Add to Home screen.')}</p>}

      <h4>{t('Notifications on this device')}</h4>
      {!settings ? null : !settings.enabled ? <p className="small muted">{t('Phone notifications are not set up on this server yet. You still see them under the bell.')}</p>
        : !pushSupported() ? <p className="small muted">{isIos() ? t('On an iPhone, install the app first (iOS 16.4 or later), then turn notifications on from the installed app.') : t('This browser cannot receive notifications.')}</p>
          : denied ? <p className="small">{t('Notifications are blocked for HR Pay in this browser\'s settings.')}</p>
            : here ? (
              <div className="row">
                <span className="badge ok">{t('On')}</span>
                <Button className="secondary" onClick={async () => { setError(null); await turnOffPush(); await load() }}>{t('Turn off')}</Button>
              </div>
            ) : (
              <Button onClick={async () => {
                setError(null)
                try { await turnOnPush(settings); await load() } catch (e) {
                  setError((e as Error).message === 'denied' ? t('Notifications were not allowed.') : (e as Error).message)
                }
              }}>{t('Turn on notifications')}</Button>
            )}
      {settings && settings.phones.length > 0 && <p className="small muted">{plural('{a} device receives your notifications.', '{a} devices receive your notifications.', settings.phones.length, { a: settings.phones.length })}</p>}
      <ErrorText error={error} />
    </div>
  )
}
