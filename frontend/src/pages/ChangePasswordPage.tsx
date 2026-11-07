import { useState, type FormEvent } from 'react'
import { api } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'
import { Button, Form } from '../components/Button'

export default function ChangePasswordPage({ forced = false }: { forced?: boolean }) {
  const { me, refresh, signOut } = useAuth()
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [confirm, setConfirm] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [done, setDone] = useState(false)

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setError(null)
    if (next !== confirm) {
      setError('The two new passwords do not match.')
      return
    }
    try {
      await api('/api/auth/password', { method: 'POST', body: { currentPassword: current, newPassword: next } })
      setDone(true)
      setCurrent(''); setNext(''); setConfirm('')
      await refresh()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not change the password.')
    }
  }

  return (
    <div className={forced ? 'login' : ''}>
      <Form className="card" onSubmit={submit}>
        <h2>{forced ? 'Choose a new password' : 'My account'}</h2>
        {!forced && me && (
          <p className="muted">{me.email} · {me.roles.join(', ')}</p>
        )}
        {forced && <p className="muted">Your password was set by an administrator. Choose your own to continue.</p>}
        <label>Current password<input type="password" value={current} onChange={(e) => setCurrent(e.target.value)} required /></label>
        <label>New password<input type="password" value={next} onChange={(e) => setNext(e.target.value)} required minLength={10} /></label>
        <label>Repeat new password<input type="password" value={confirm} onChange={(e) => setConfirm(e.target.value)} required /></label>
        <p className="hint">At least 10 characters, with a letter and a digit.</p>
        <ErrorText error={error} />
        {done && <p className="success">Password changed.</p>}
        <Button type="submit">Change password</Button>
        {forced && <Button type="button" className="secondary" onClick={() => signOut()}>Sign out</Button>}
      </Form>
    </div>
  )
}
