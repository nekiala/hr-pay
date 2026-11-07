import { useEffect, useState } from 'react'
import QRCode from 'qrcode'
import { api, type Me, type TwoStepNeeded, type TwoStepSetup } from '../api'
import { Button, Form } from './Button'
import { ErrorText } from './ErrorText'
import { t } from '../i18n'

const message = (e: unknown, fallback: string) => (e instanceof Error ? e.message : fallback)

/** The second step of a sign-in: the app's code, the code sent, or a recovery code. */
export function TwoStepCode({ needed, onDone, onCancel }: { needed: TwoStepNeeded; onDone: (me: Me) => void; onCancel: () => void }) {
  const [code, setCode] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [sent, setSent] = useState<string | null>(null)
  const [recovery, setRecovery] = useState(false)

  const submit = async () => {
    setError(null)
    try {
      onDone(await api<Me>('/api/auth/two-step', { method: 'POST', body: { code } }))
    } catch (e) {
      setError(message(e, t('The code is not correct.')))
      setCode('')
    }
  }

  const resend = async () => {
    setError(null)
    try {
      const r = await api<TwoStepNeeded>('/api/auth/two-step/resend', { method: 'POST' })
      setSent(t('A new code was sent to {a}.', { a: r.sentTo.join(', ') }))
    } catch (e) {
      setError(message(e, t('Could not send a new code.')))
    }
  }

  return (
    <Form onSubmit={submit}>
      <h2>{t('Second step')}</h2>
      {recovery
        ? <p className="muted">{t('Enter one of the recovery codes you saved when you set up the second step. Each one works once.')}</p>
        : needed.method === 'CODE'
          ? <p className="muted">{t('Enter the six-digit code sent to')}{' '}{needed.sentTo.join(', ')}.</p>
          : <p className="muted">{t('Open your authenticator app and enter the six-digit code shown for HR Pay.')}</p>}
      <label>
        {recovery ? t('Recovery code') : t('Code')}
        <input className="code-input" value={code} onChange={(e) => setCode(e.target.value)} required autoFocus
          autoComplete="one-time-code" inputMode={recovery ? 'text' : 'numeric'} maxLength={recovery ? 12 : 6}
          placeholder={recovery ? t('XXXXX-XXXXX') : '123456'} />
      </label>
      <ErrorText error={error} />
      {sent && <p className="success">{sent}</p>}
      <Button type="submit" pendingText={t('Checking…')}>{t('Continue')}</Button>
      <div className="row between small">
        <button type="button" className="link" onClick={() => { setRecovery(!recovery); setCode(''); setError(null) }}>
          {recovery ? t('Use a code instead') : t('Use a recovery code')}
        </button>
        {needed.method === 'CODE' && !recovery && <button type="button" className="link" onClick={() => void resend()}>{t('Send a new code')}</button>}
      </div>
      <Button className="secondary" onClick={onCancel}>{t('Back to sign-in')}</Button>
    </Form>
  )
}

/**
 * Setting up the second step: an authenticator app (scan a QR code, enter its first code) or codes by email. Used
 * at a first sign-in that requires it, and from My account.
 */
export function TwoStepSetupFlow({ codesAvailable, intro, onDone, onCancel, cancelLabel = t('Cancel') }: {
  codesAvailable: boolean
  intro?: string
  onDone: (recoveryCodes: string[], me: Me) => void
  onCancel: () => void
  cancelLabel?: string
}) {
  const [setup, setSetup] = useState<TwoStepSetup | null>(null)
  const [qr, setQr] = useState<string | null>(null)
  const [code, setCode] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [showSecret, setShowSecret] = useState(false)

  const start = async (method: 'TOTP' | 'CODE') => {
    setError(null)
    try {
      const s = await api<TwoStepSetup>('/api/auth/two-step/setup', { method: 'POST', body: { method } })
      setSetup(s)
      setCode('')
      setQr(s.uri ? await QRCode.toDataURL(s.uri, { margin: 1, width: 200 }) : null)
    } catch (e) {
      setError(message(e, t('Could not start the set-up.')))
    }
  }

  // With no email server, the app is the only choice: go straight to it.
  useEffect(() => {
    if (!codesAvailable) void start('TOTP')
  }, [codesAvailable])

  const confirm = async () => {
    if (!setup) return
    setError(null)
    try {
      const r = await api<{ recoveryCodes: string[]; me: Me }>('/api/auth/two-step/confirm', {
        method: 'POST', body: { method: setup.method, code, challenge: setup.method === 'CODE' ? setup.secret : null },
      })
      onDone(r.recoveryCodes, r.me)
    } catch (e) {
      setError(message(e, t('The code is not correct.')))
    }
  }

  if (!setup) {
    return (
      <div className="stack">
        <h2>{t('Set up the second step')}</h2>
        <p className="muted">{intro ?? t('After your password, HR Pay will ask for a six-digit code. Choose where it comes from.')}</p>
        <Button onClick={() => start('TOTP')}>{t('Use an authenticator app')}</Button>
        {codesAvailable && <Button className="secondary" onClick={() => start('CODE')}>{t('Receive codes by email')}</Button>}
        <p className="hint">{t('Authenticator apps: Google Authenticator, Microsoft Authenticator, Aegis, 1Password… They work without a network.')}</p>
        <ErrorText error={error} />
        <Button className="secondary" onClick={onCancel}>{cancelLabel}</Button>
      </div>
    )
  }

  return (
    <Form onSubmit={confirm}>
      <h2>{setup.method === 'TOTP' ? t('Scan the code with your app') : t('Check your email')}</h2>
      {setup.method === 'TOTP' ? (
        <>
          <p className="muted">{t('In your authenticator app, add an account and scan this code. Then enter the six digits it shows.')}</p>
          {qr && <img className="qr" src={qr} alt={t('QR code to add HR Pay to an authenticator app')} width={200} height={200} />}
          {showSecret
            ? <p className="small">{t('Key to type in by hand:')}{' '}<code className="secret">{setup.secret.replace(/(.{4})/g, '$1 ').trim()}</code></p>
            : <button type="button" className="link small" onClick={() => setShowSecret(true)}>{t('Can\'t scan? Type a key instead')}</button>}
        </>
      ) : (
        <p className="muted">{t('We sent a six-digit code to your email address. Enter it to turn the second step on.')}</p>
      )}
      <label>{t('Code')}<input className="code-input" value={code} onChange={(e) => setCode(e.target.value)} required autoFocus
          autoComplete="one-time-code" inputMode="numeric" maxLength={6} placeholder="123456" />
      </label>
      <ErrorText error={error} />
      <Button type="submit" pendingText={t('Checking…')}>{t('Turn on')}</Button>
      <Button className="secondary" onClick={() => { setSetup(null); setError(null) }}>{t('Back')}</Button>
    </Form>
  )
}

/** The ten recovery codes, shown once. */
export function RecoveryCodes({ codes, onContinue, continueLabel = t('I saved them, continue') }: {
  codes: string[]
  onContinue: () => void
  continueLabel?: string
}) {
  const [copied, setCopied] = useState(false)
  const text = codes.join('\n')
  const save = () => {
    const url = URL.createObjectURL(new Blob([t('HR Pay recovery codes\nEach code works once.\n\n{a}\n', { a: text })], { type: 'text/plain' }))
    const a = document.createElement('a')
    a.href = url
    a.download = 'hr-pay-recovery-codes.txt'
    a.click()
    URL.revokeObjectURL(url)
  }
  return (
    <div className="stack">
      <h2>{t('Save your recovery codes')}</h2>
      <p className="muted">{t('If you lose your phone, each of these codes lets you sign in once. Keep them somewhere safe, away from your phone. They are shown only now.')}</p>
      <ol className="recovery-codes">{codes.map((c) => <li key={c}><code>{c}</code></li>)}</ol>
      <div className="row">
        <Button className="secondary" onClick={() => { void navigator.clipboard?.writeText(text).then(() => setCopied(true)) }}>
          {copied ? t('Copied') : t('Copy')}
        </Button>
        <Button className="secondary" onClick={save}>{t('Download')}</Button>
      </div>
      <Button onClick={onContinue}>{continueLabel}</Button>
    </div>
  )
}
