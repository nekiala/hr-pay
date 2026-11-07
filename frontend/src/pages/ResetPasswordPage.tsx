import { useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { Banknote } from 'lucide-react'
import { api } from '../api'
import { ErrorText } from '../components/ErrorText'
import { Button, Form } from '../components/Button'
import { LanguageToggle } from '../components/Language'
import { t } from '../i18n'

/** "Forgot your password?": asks for a link by email, then, from the link, sets the new password. Works signed out. */
export default function ResetPasswordPage() {
  const [params] = useSearchParams()
  const token = params.get('token')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [again, setAgain] = useState('')
  const [sent, setSent] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const ask = async () => {
    setError(null)
    try {
      await api('/api/auth/password-reset', { method: 'POST', body: { email: email.trim() } })
      setSent(true)
    } catch (e) { setError((e as Error).message) }
  }
  const save = async () => {
    setError(null)
    if (password !== again) { setError(t('The two passwords are not the same.')); return }
    try {
      await api('/api/auth/password-reset/confirm', { method: 'POST', body: { token, password } })
      // Every browser was signed out, this one too: a fresh start on the sign-in page.
      window.location.assign('/login?reset=done')
    } catch (e) { setError((e as Error).message) }
  }

  const brand = <h1><span className="brand-mark"><Banknote size={18} aria-hidden /></span>{t('HR Pay')}</h1>

  if (token) {
    return (
      <div className="login">
        <Form className="card" onSubmit={save}>
          {brand}
          <p className="muted">{t('Choose a new password: at least 10 characters, with a letter and a digit.')}</p>
          <label>{t('New password')}<input type="password" autoComplete="new-password" minLength={10} maxLength={128} required
            value={password} onChange={(e) => setPassword(e.target.value)} autoFocus /></label>
          <label>{t('The same again')}<input type="password" autoComplete="new-password" minLength={10} maxLength={128} required
            value={again} onChange={(e) => setAgain(e.target.value)} /></label>
          <ErrorText error={error} />
          <Button type="submit">{t('Save the new password')}</Button>
          <p className="small"><Link to="/login">{t('Back to sign-in')}</Link></p>
        </Form>
      </div>
    )
  }

  return (
    <div className="login">
      <Form className="card" onSubmit={ask}>
        {brand}
        {sent ? (
          <>
            <p className="success">{t('If this address has an HR Pay account, a link to choose a new password is on its way. It works once, for 30 minutes.')}</p>
            <p className="small muted">{t('Nothing after a few minutes? Look in the spam folder, or ask your administrator to set a new password for you.')}</p>
          </>
        ) : (
          <>
            <p className="muted">{t('Enter the email address you sign in with. We send you a link to choose a new password.')}</p>
            <label>{t('Email')}<input type="email" autoComplete="username" required maxLength={320} value={email}
              onChange={(e) => setEmail(e.target.value)} autoFocus /></label>
            <ErrorText error={error} />
            <Button type="submit">{t('Send me a link')}</Button>
          </>
        )}
        <p className="small"><Link to="/login">{t('Back to sign-in')}</Link></p>
        <LanguageToggle />
      </Form>
    </div>
  )
}
