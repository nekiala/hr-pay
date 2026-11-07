import { useEffect, useState, type FormEvent } from 'react'
import { useSearchParams } from 'react-router-dom'
import { api } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'

export default function LoginPage() {
  const { signIn } = useAuth()
  const [params] = useSearchParams()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [google, setGoogle] = useState(false)
  const [error, setError] = useState<string | null>(
    params.get('error') === 'google' ? 'This Google account is not registered or is disabled. Contact HR.' : null,
  )

  useEffect(() => {
    api<{ google: boolean }>('/api/auth/providers').then((p) => setGoogle(p.google)).catch(() => undefined)
  }, [])

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await signIn(email, password)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Sign-in failed.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="login">
      <form className="card" onSubmit={submit}>
        <h1>HR Pay</h1>
        <p className="muted">Sign in with the email address registered by HR.</p>
        <label>
          Email
          <input type="email" autoComplete="username" value={email} onChange={(e) => setEmail(e.target.value)} required />
        </label>
        <label>
          Password
          <input type="password" autoComplete="current-password" value={password}
            onChange={(e) => setPassword(e.target.value)} required />
        </label>
        <ErrorText error={error} />
        <button type="submit" disabled={busy}>{busy ? 'Signing in…' : 'Sign in'}</button>
        {google && (
          <a className="button secondary" href="/oauth2/authorization/google">Sign in with Google</a>
        )}
      </form>
    </div>
  )
}
