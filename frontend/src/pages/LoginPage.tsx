import { useEffect, useState, type FormEvent } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { Banknote } from 'lucide-react'
import { api, type Me, type TwoStepNeeded } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'
import { Button, Form } from '../components/Button'
import { LanguageToggle } from '../components/Language'
import { RecoveryCodes, TwoStepCode, TwoStepSetupFlow } from '../components/TwoStep'
import { serverVersion } from './HelpPage'
import { PublicLinks } from './PublicPages'
import { t } from '../i18n'
import { GoogleLogo, MicrosoftLogo } from '../components/ProviderLogos'

/** What a refused Google or Microsoft sign-in means, by the reason the server gave. */
const PROVIDER_ERRORS: Record<string, string> = {
  google: t('This Google account is not registered or is disabled. Contact HR.'),
  not_registered: t('This account is not registered in HR Pay. Sign in with the email address HR registered, or contact HR.'),
  disabled: t('This account is disabled. Contact HR.'),
  email_not_verified: t('Your provider did not confirm this email address.'),
  personal_account: t('Personal Google accounts cannot sign in. Use your company\'s professional Google account.'),
  domain_not_allowed: t('Your company has not registered this Google domain. Ask your administrator to add it on the Roles page.'),
  wrong_tenant: t('Your company has not allowed this Microsoft directory. Ask your administrator to set it on the Company page.'),
  provider: t('The sign-in with your provider did not succeed. Try again.'),
}

export default function LoginPage() {
  const { signIn, signedIn } = useAuth()
  const [params, setParams] = useSearchParams()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [providers, setProviders] = useState({ google: false, microsoft: false })
  const [step, setStep] = useState<TwoStepNeeded | null>(null)
  const [codes, setCodes] = useState<{ codes: string[]; me: Me } | null>(null)
  const [demo, setDemo] = useState(false)
  const errorCode = params.get('error')
  const [error, setError] = useState<string | null>(errorCode ? PROVIDER_ERRORS[errorCode] ?? PROVIDER_ERRORS.provider : null)

  useEffect(() => {
    api<{ google: boolean; microsoft: boolean }>('/api/auth/providers').then(setProviders).catch(() => undefined)
    void serverVersion().then((v) => setDemo(v.demo))
    // Back from Google or Microsoft with a second step to do.
    if (params.get('step') === 'two') {
      api<TwoStepNeeded>('/api/auth/two-step').then(setStep).catch((e: Error) => setError(e.message))
    }
  }, [])

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      setStep(await signIn(email, password))
      setPassword('')
    } catch (err) {
      setError(err instanceof Error ? err.message : t('Sign-in failed.'))
    } finally {
      setBusy(false)
    }
  }

  const restart = () => {
    setStep(null)
    setCodes(null)
    setError(null)
    if (params.has('step')) setParams({}, { replace: true })
  }

  const brand = <h1><span className="brand-mark"><Banknote size={18} aria-hidden /></span>{t('HR Pay')}</h1>

  if (codes) {
    return (
      <div className="login"><div className="card">
        {brand}
        <RecoveryCodes codes={codes.codes} onContinue={() => signedIn(codes.me)} />
      </div></div>
    )
  }

  if (step) {
    return (
      <div className="login"><div className="card">
        {brand}
        {step.setup
          ? <TwoStepSetupFlow codesAvailable={step.codesAvailable} cancelLabel={t('Back to sign-in')}
              intro={t('Your role asks for a second step at each sign-in, to protect pay and personal data. Set it up now; it takes a minute.')}
              onDone={(c, me) => setCodes({ codes: c, me })} onCancel={restart} />
          : <TwoStepCode needed={step} onDone={signedIn} onCancel={restart} />}
      </div></div>
    )
  }

  return (
    <div className="login">
      <Form className="card" onSubmit={submit}>
        {brand}
        {params.get('reset') === 'done'
          ? <p className="success">{t('Your new password is saved. Sign in with it.')}</p>
          : <p className="muted">{t('Sign in with the email address registered by HR.')}</p>}
        <label>{t('Email')}<input type="email" autoComplete="username" value={email} onChange={(e) => setEmail(e.target.value)} required />
        </label>
        <label>{t('Password')}<input type="password" autoComplete="current-password" value={password}
            onChange={(e) => setPassword(e.target.value)} required />
        </label>
        <p className="small"><Link to="/reset-password">{t('Forgot your password?')}</Link></p>
        <ErrorText error={error} />
        <Button type="submit" pending={busy} pendingText={t('Signing in…')}>{t('Sign in')}</Button>
        {providers.microsoft && (
          <a className="button secondary" href="/oauth2/authorization/microsoft"><MicrosoftLogo />{t('Sign in with Microsoft')}</a>
        )}
        {providers.google && (
          <a className="button secondary" href="/oauth2/authorization/google"><GoogleLogo />{t('Sign in with Google')}</a>
        )}
        {demo && (
          <div className="notice small">
            <strong>{t('Demo')}</strong>{' '}{t('Sign in as one of these people, password Demo@2026!. Everything is erased every night.')}
            <div className="row">
              {[['admin', t('Administrator')], ['hr', t('HR')], ['finance', t('Finance')], ['employee1', t('Employee')]].map(([u, label]) => (
                <button key={u} type="button" className="secondary" onClick={() => { setEmail(`${u}@demo.hrpay.cd`); setPassword('Demo@2026!') }}>{label}</button>
              ))}
            </div>
          </div>
        )}
        <LanguageToggle />
        <p className="small" style={{ textAlign: "center", margin: 0 }}><Link to="/trial">{t('Try HR Pay free for 30 days')}</Link></p>
      </Form>
      <PublicLinks />
    </div>
  )
}
