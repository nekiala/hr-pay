import { useEffect, useState, type FormEvent } from 'react'
import { useSearchParams } from 'react-router-dom'
import { Banknote } from 'lucide-react'
import { api } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'
import { Button, Form } from '../components/Button'

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
      <Form className="card" onSubmit={submit}>
        <h1><span className="brand-mark"><Banknote size={18} aria-hidden /></span>HR Pay</h1>
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
        <Button type="submit" pending={busy} pendingText="Signing in…">Sign in</Button>
        {google && (
          <a className="button secondary" href="/oauth2/authorization/google">Sign in with Google</a>
        )}
      </Form>
    </div>
  )
}
