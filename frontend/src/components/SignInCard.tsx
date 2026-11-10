import { useEffect, useState } from 'react'
import { ApiError, api } from '../api'
import { useAuth } from '../auth'
import { Button, Form } from './Button'
import { ErrorText } from './ErrorText'
import { t } from '../i18n'
import { GoogleLogo, MicrosoftLogo } from './ProviderLogos'

type SignIn = { microsoftTenant: string | null; microsoftAvailable: boolean; googleAvailable: boolean; googleDomains: string[]; googleOnly: boolean }

/** Which Microsoft 365 directory and Google Workspace domains the company's people may sign in from. */
export function SignInCard() {
  const { can } = useAuth()
  const [s, setS] = useState<SignIn | null>(null)
  const [tenant, setTenant] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)
  const allowed = can('role.manage')
  useEffect(() => {
    if (allowed) api<SignIn>('/api/company/sign-in').then((r) => { setS(r); setTenant(r.microsoftTenant ?? '') }).catch((e: Error) => setError(e.message))
  }, [allowed])
  if (!allowed || !s) return null
  return (
    <>
      <GoogleCard s={s} setS={setS} />
      <MicrosoftCard s={s} setS={setS} tenant={tenant} setTenant={setTenant} error={error} setError={setError} saved={saved} setSaved={setSaved} />
    </>
  )
}

type MicrosoftProps = {
  s: SignIn; setS: (s: SignIn) => void; tenant: string; setTenant: (v: string) => void
  error: string | null; setError: (e: string | null) => void; saved: boolean; setSaved: (v: boolean) => void
}

function MicrosoftCard({ s, setS, tenant, setTenant, error, setError, saved, setSaved }: MicrosoftProps) {
  const save = async () => {
    setError(null)
    setSaved(false)
    try {
      const r = await api<SignIn>('/api/company/sign-in', { method: 'PUT', body: { microsoftTenant: tenant.trim() } })
      setS(r)
      setTenant(r.microsoftTenant ?? '')
      setSaved(true)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : t('Could not save.'))
    }
  }
  return (
    <Form className="card" onSubmit={save}>
      <h3 className="row"><MicrosoftLogo />{t('Sign in with Microsoft 365')}</h3>
      {!s.microsoftAvailable && <p className="notice">{t('Microsoft sign-in is not turned on for this installation (MICROSOFT_CLIENT_ID). You can still set the tenant now.')}</p>}
      <p className="muted">{t('Your people can sign in with their work account, but only from your organization\'s own Microsoft directory. Find its tenant ID in the Microsoft Entra admin centre, under Overview. Their email in HR Pay must be their Microsoft sign-in name.')}</p>
      <label>{t('Tenant ID')}<input value={tenant} onChange={(e) => setTenant(e.target.value)} placeholder="00000000-0000-0000-0000-000000000000"
          pattern="[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}" />
      </label>
      <p className="hint">{t('Leave empty to refuse Microsoft sign-in for your company.')}</p>
      <ErrorText error={error} />
      {saved && <p className="success">{t('Saved.')}</p>}
      <Button type="submit">{t('Save')}</Button>
    </Form>
  )
}

/** The company's Google Workspace domains: only professional accounts on them may sign in with Google. */
function GoogleCard({ s, setS }: { s: SignIn; setS: (s: SignIn) => void }) {
  const [domains, setDomains] = useState(s.googleDomains.join('\n'))
  const [only, setOnly] = useState(s.googleOnly)
  const [error, setError] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)
  const save = async () => {
    setError(null)
    setSaved(false)
    try {
      const list = domains.split(/[\s,;]+/).map((d) => d.trim()).filter(Boolean)
      const r = await api<SignIn>('/api/company/sign-in/google', { method: 'PUT', body: { domains: list, googleOnly: only } })
      setS(r)
      setDomains(r.googleDomains.join('\n'))
      setOnly(r.googleOnly)
      setSaved(true)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : t('Could not save.'))
    }
  }
  return (
    <Form className="card" onSubmit={save}>
      <h3 className="row"><GoogleLogo />{t('Sign in with Google')}</h3>
      {!s.googleAvailable && <p className="notice">{t('Google sign-in is not turned on for this installation (GOOGLE_CLIENT_ID). You can still list your domains now.')}</p>}
      <p className="muted">{t('Your people can sign in with their professional Google account (Google Workspace), only on the domains listed here. Personal Gmail accounts are always refused. Their email in HR Pay must be their Google account. The second step is still asked.')}</p>
      <label>{t('Company domains')}<textarea rows={3} value={domains} onChange={(e) => setDomains(e.target.value)} placeholder="mycompany.com" />
        <span className="hint">{t('One per line. Leave empty to refuse Google sign-in for your company.')}</span>
      </label>
      <label className="check">
        <input type="checkbox" checked={only} disabled={!s.googleAvailable && !only} onChange={(e) => setOnly(e.target.checked)} />
        {t('Google only: people on these domains cannot sign in with a password')}
      </label>
      <p className="hint">{t('Their access then stops as soon as your IT closes their Google account. Others keep their password.')}</p>
      <ErrorText error={error} />
      {saved && <p className="success">{t('Saved.')}</p>}
      <Button type="submit">{t('Save')}</Button>
    </Form>
  )
}
