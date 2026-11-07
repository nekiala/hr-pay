import { useEffect, useState, type FormEvent } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { ApiError, api, type Role, type Site, type User } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'

type Form = { email: string; registrationNo: string; phone: string; firstName: string; lastName: string; siteId: string }
const empty: Form = { email: '', registrationNo: '', phone: '+243', firstName: '', lastName: '', siteId: '' }

export default function UserFormPage() {
  const { id } = useParams()
  const isNew = !id
  const navigate = useNavigate()
  const { can, me } = useAuth()
  const [form, setForm] = useState<Form>(empty)
  const [user, setUser] = useState<User | null>(null)
  const [roles, setRoles] = useState<Role[]>([])
  const [sites, setSites] = useState<Site[]>([])
  const [selectedRoles, setSelectedRoles] = useState<string[]>(['EMPLOYEE'])
  const [initialPassword, setInitialPassword] = useState('')
  const [resetPassword, setResetPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [message, setMessage] = useState<string | null>(null)

  const canAssign = can('role.assign')
  const canEdit = isNew ? can('user.create') : can('user.update')

  useEffect(() => {
    api<Role[]>('/api/roles').then(setRoles).catch(() => undefined)
    if (can('site.read') || can('site.manage')) api<Site[]>('/api/sites').then(setSites).catch(() => undefined)
    if (id) {
      api<User>(`/api/users/${id}`).then((u) => {
        setUser(u)
        setSelectedRoles(u.roles)
        setForm({ email: u.email, registrationNo: u.registrationNo, phone: u.phone, firstName: u.firstName,
          lastName: u.lastName, siteId: u.siteId ? String(u.siteId) : '' })
      }).catch((e: Error) => setError(e.message))
    }
  }, [id, can])

  const set = (key: keyof Form) => (e: { target: { value: string } }) => setForm({ ...form, [key]: e.target.value })

  const handle = async (action: () => Promise<void>) => {
    setError(null); setFieldErrors({}); setMessage(null)
    try {
      await action()
    } catch (err) {
      if (err instanceof ApiError) {
        setError(err.message)
        setFieldErrors(Object.fromEntries(Object.entries(err.fieldErrors).map(([k, v]) => [k.replace(/^user\./, ''), v])))
      } else setError('Something went wrong.')
    }
  }

  const payload = () => ({ ...form, siteId: form.siteId ? Number(form.siteId) : null })

  const save = (e: FormEvent) => {
    e.preventDefault()
    void handle(async () => {
      if (isNew) {
        const created = await api<User>('/api/users', {
          method: 'POST',
          body: { user: payload(), roles: selectedRoles, initialPassword: initialPassword || null },
        })
        navigate(`/users/${created.id}`, { replace: true })
      } else {
        setUser(await api<User>(`/api/users/${id}`, { method: 'PUT', body: payload() }))
        setMessage('Saved.')
      }
    })
  }

  const saveRoles = () => handle(async () => {
    setUser(await api<User>(`/api/users/${id}/roles`, { method: 'PUT', body: { roles: selectedRoles } }))
    setMessage('Roles updated.')
  })

  const toggleStatus = () => handle(async () => {
    const action = user?.status === 'ACTIVE' ? 'disable' : 'enable'
    setUser(await api<User>(`/api/users/${id}/${action}`, { method: 'POST' }))
  })

  const doReset = () => handle(async () => {
    await api(`/api/users/${id}/password`, { method: 'POST', body: { password: resetPassword } })
    setResetPassword('')
    setMessage('Temporary password set. The user must change it at next sign-in.')
  })

  const toggleRole = (code: string) =>
    setSelectedRoles(selectedRoles.includes(code) ? selectedRoles.filter((r) => r !== code) : [...selectedRoles, code])

  const field = (key: keyof Form, label: string, type = 'text', hint?: string) => (
    <label>
      {label}
      <input type={type} value={form[key]} onChange={set(key)} required disabled={!canEdit} />
      {hint && <span className="hint">{hint}</span>}
      {fieldErrors[key] && <span className="error">{fieldErrors[key]}</span>}
    </label>
  )

  return (
    <section className="narrow">
      <h2>{isNew ? 'New user' : `${form.firstName} ${form.lastName}`}</h2>
      {user && (
        <p className="muted">
          {user.status === 'ACTIVE' ? 'Active' : 'Disabled'} · {user.hasPassword ? 'Password set' : 'Google sign-in only'}
          {user.lastLoginAt ? ` · Last sign-in ${new Date(user.lastLoginAt).toLocaleString()}` : ' · Never signed in'}
        </p>
      )}
      <form className="card" onSubmit={save}>
        <div className="grid2">
          {field('firstName', 'First name')}
          {field('lastName', 'Last name')}
        </div>
        {field('email', 'Email', 'email')}
        <div className="grid2">
          {field('registrationNo', 'Registration number')}
          {field('phone', 'Phone (WhatsApp)', 'tel', 'International format, for example +243 81 234 5678')}
        </div>
        <label>
          Site
          <select value={form.siteId} onChange={set('siteId')} disabled={!canEdit}>
            <option value="">No site</option>
            {sites.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
          </select>
        </label>
        {isNew && (
          <>
            <fieldset>
              <legend>Roles</legend>
              {roles.filter((r) => canAssign || r.code === 'EMPLOYEE').map((r) => (
                <label key={r.code} className="check">
                  <input type="checkbox" checked={selectedRoles.includes(r.code)} onChange={() => toggleRole(r.code)} />
                  {r.name}
                </label>
              ))}
            </fieldset>
            <label>
              Temporary password (optional)
              <input type="password" value={initialPassword} onChange={(e) => setInitialPassword(e.target.value)} />
              <span className="hint">Leave empty if the user will sign in with Google.</span>
            </label>
          </>
        )}
        <ErrorText error={error} />
        {message && <p className="success">{message}</p>}
        {canEdit && <button type="submit">{isNew ? 'Create user' : 'Save changes'}</button>}
      </form>

      {!isNew && user && canAssign && (
        <div className="card">
          <h3>Roles</h3>
          {roles.map((r) => (
            <label key={r.code} className="check">
              <input type="checkbox" checked={selectedRoles.includes(r.code)} onChange={() => toggleRole(r.code)} />
              {r.name}
            </label>
          ))}
          <button onClick={() => void saveRoles()}>Save roles</button>
        </div>
      )}

      {!isNew && user && (can('user.disable') || can('user.update')) && user.id !== me?.id && (
        <div className="card">
          <h3>Access</h3>
          {can('user.update') && (
            <div className="row">
              <input type="password" placeholder="New temporary password" value={resetPassword}
                onChange={(e) => setResetPassword(e.target.value)} />
              <button className="secondary" disabled={!resetPassword} onClick={() => void doReset()}>Set password</button>
            </div>
          )}
          {can('user.disable') && (
            <button className={user.status === 'ACTIVE' ? 'danger' : ''} onClick={() => void toggleStatus()}>
              {user.status === 'ACTIVE' ? 'Disable account' : 'Enable account'}
            </button>
          )}
        </div>
      )}
    </section>
  )
}
