import { useEffect, useState } from 'react'
import { ApiError, api } from '../api'
import { useAuth } from '../auth'
import { Button, Form } from './Button'
import { ErrorText } from './ErrorText'
import { t } from '../i18n'

type SignIn = { microsoftTenant: string | null; microsoftAvailable: boolean; googleAvailable: boolean }

/** Which Microsoft 365 directory the company's people may sign in from. */
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
      <h3>{t('Sign in with Microsoft 365')}</h3>
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
