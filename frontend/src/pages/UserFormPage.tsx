import { useEffect, useState, type FormEvent } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ApiError, api, days, money, type GradeCategory, type LeaveBalance, type LeaveLedgerEntry, type LeaveType, type Role, type Salary, type Schedule, type ScheduleAssignment, type Site, type User } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'
import { OrgCard, UnitSelect } from '../components/OrgCard'
import type { OrgUnit } from '../org'

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
  const [schedules, setSchedules] = useState<Schedule[]>([])
  const [assignments, setAssignments] = useState<ScheduleAssignment[]>([])
  const [newSchedule, setNewSchedule] = useState('')
  const [scheduleFrom, setScheduleFrom] = useState(new Date().toISOString().slice(0, 10))
  const [units, setUnits] = useState<OrgUnit[]>([])
  const [orgUnitId, setOrgUnitId] = useState('')

  const canAssign = can('role.assign')
  const canEdit = isNew ? can('user.create') : can('user.update')

  useEffect(() => {
    api<Role[]>('/api/roles').then(setRoles).catch(() => undefined)
    if (can('site.read') || can('site.manage')) api<Site[]>('/api/sites').then(setSites).catch(() => undefined)
    if (!id) api<OrgUnit[]>('/api/org/units').then(setUnits).catch(() => undefined)
    if (id) {
      api<Schedule[]>('/api/schedules').then(setSchedules).catch(() => undefined)
      api<ScheduleAssignment[]>(`/api/users/${id}/schedules`).then(setAssignments).catch(() => undefined)
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
          body: { user: payload(), roles: selectedRoles, initialPassword: initialPassword || null, orgUnitId: orgUnitId ? Number(orgUnitId) : null },
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

  const assignSchedule = () => handle(async () => {
    await api(`/api/users/${id}/schedules`, { method: 'POST', body: { scheduleId: Number(newSchedule), effectiveFrom: scheduleFrom } })
    setAssignments(await api<ScheduleAssignment[]>(`/api/users/${id}/schedules`))
    setMessage('Schedule assigned.')
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
            <label>
              Unit
              <UnitSelect units={units} value={orgUnitId} required onChange={setOrgUnitId} />
              <span className="hint">Where this person works: Direction, Service, Équipe and so on. It can be changed later, with history.</span>
              {fieldErrors.orgUnitId && <span className="error">{fieldErrors.orgUnitId}</span>}
            </label>
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

      {!isNew && user && (
        <div className="card">
          <h3>Work schedule</h3>
          {assignments.length === 0 && <p className="muted">No schedule yet. Without one, no hours are expected.</p>}
          {assignments.map((a) => (
            <p key={a.id}>{a.scheduleName} <span className="muted">from {a.effectiveFrom}</span></p>
          ))}
          {can('schedule.manage') && (
            <div className="row">
              <select value={newSchedule} onChange={(e) => setNewSchedule(e.target.value)}>
                <option value="">Choose a schedule</option>
                {schedules.filter((s) => s.active).map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
              </select>
              <input type="date" value={scheduleFrom} onChange={(e) => setScheduleFrom(e.target.value)} />
              <button className="secondary" disabled={!newSchedule} onClick={() => void assignSchedule()}>Assign</button>
            </div>
          )}
        </div>
      )}

      {!isNew && user && can('attendance.read.all') && (
        <p className="row">
          <Link className="button secondary" to={`/users/${id}/attendance`}>View attendance and earnings</Link>
          {can('salary.read') && <Link className="button secondary" to={`/users/${id}/file`}>Contract and family</Link>}
        </p>
      )}

      {!isNew && user && can('salary.read') && <SalaryCard userId={Number(id)} />}

      {!isNew && user && <OrgCard userId={user.id} />}

      {!isNew && user && <ManagerCard user={user} editable={can('user.update')} onChange={setUser} />}

      {!isNew && user && <EmploymentCard user={user} editable={can('user.update')} onChange={setUser} />}

      {!isNew && user && can('leave.manage') && <LeaveCard userId={user.id} />}

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

function SalaryCard({ userId }: { userId: number }) {
  const { can } = useAuth()
  const [history, setHistory] = useState<Salary[]>([])
  const [form, setForm] = useState({ effectiveFrom: new Date().toISOString().slice(0, 8) + '01', grossMonthly: '', currency: 'USD', note: '' })
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)

  const load = () => api<Salary[]>(`/api/users/${userId}/salaries`).then(setHistory).catch((e: Error) => setError(e.message))
  useEffect(() => { load() }, [userId]) // eslint-disable-line react-hooks/exhaustive-deps

  const add = async (e: FormEvent) => {
    e.preventDefault()
    setError(null)
    setMessage(null)
    try {
      await api(`/api/users/${userId}/salaries`, { method: 'POST', body: { ...form, grossMonthly: Number(form.grossMonthly) } })
      setForm({ ...form, grossMonthly: '', note: '' })
      setMessage('Salary saved.')
      await load()
    } catch (err) {
      setError(err instanceof ApiError ? [err.message, ...Object.values(err.fieldErrors)].join(' ') : 'Could not save.')
    }
  }

  return (
    <div className="card">
      <h3>Gross monthly salary</h3>
      {history.length === 0 && <p className="muted">No salary yet.</p>}
      {history.map((s) => (
        <p key={s.id}>{money(s.grossMonthly, s.currency)} <span className="muted">from {s.effectiveFrom}{s.note ? ` · ${s.note}` : ''} · set by {s.createdBy}</span></p>
      ))}
      {can('salary.write') && (
        <form className="row" onSubmit={add}>
          <input type="date" value={form.effectiveFrom} onChange={(e) => setForm({ ...form, effectiveFrom: e.target.value })} required />
          <input type="number" min="0.01" step="0.01" placeholder="Gross per month" value={form.grossMonthly}
            onChange={(e) => setForm({ ...form, grossMonthly: e.target.value })} required />
          <select value={form.currency} onChange={(e) => setForm({ ...form, currency: e.target.value })}>
            <option>USD</option><option>CDF</option>
          </select>
          <input placeholder="Reason (optional)" value={form.note} onChange={(e) => setForm({ ...form, note: e.target.value })} />
          <button type="submit">Add</button>
        </form>
      )}
      <p className="hint">A new amount applies from its date; earlier amounts stay in the history.</p>
      <ErrorText error={error} />
      {message && <p className="success">{message}</p>}
    </div>
  )
}

function ManagerCard({ user, editable, onChange }: { user: User; editable: boolean; onChange: (u: User) => void }) {
  const [manager, setManager] = useState<User | null>(null)
  const [query, setQuery] = useState('')
  const [results, setResults] = useState<User[]>([])
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (user.managerId) api<User>(`/api/users/${user.managerId}`).then(setManager).catch(() => setManager(null))
    else setManager(null)
  }, [user.managerId])

  useEffect(() => {
    if (query.trim().length < 2) { setResults([]); return }
    const t = setTimeout(() => {
      api<{ items: User[] }>(`/api/users?q=${encodeURIComponent(query.trim())}&size=8`)
        .then((p) => setResults(p.items.filter((u) => u.id !== user.id))).catch(() => setResults([]))
    }, 250)
    return () => clearTimeout(t)
  }, [query, user.id])

  const save = async (managerId: number | null) => {
    setError(null)
    try {
      onChange(await api<User>(`/api/users/${user.id}/manager`, { method: 'PUT', body: { managerId } }))
      setQuery('')
      setResults([])
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not save.')
    }
  }

  return (
    <div className="card">
      <h3>Manager set by hand</h3>
      <p>{manager ? `${manager.firstName} ${manager.lastName}` : <span className="muted">None: the head of the unit is used</span>}
        {editable && manager && <> <button className="secondary void" onClick={() => void save(null)}>Remove</button></>}</p>
      <p className="hint">Normally people report to the head of their unit (or the nearest head above), who approves their missions, overtime and leave and scores their evaluations. Set a manager here only for an exception; heads above still see this person in their branch.</p>
      {editable && (
        <>
          <input placeholder="Search by name or registration number" value={query} onChange={(e) => setQuery(e.target.value)} />
          {results.map((u) => (
            <p key={u.id}><button className="secondary void" onClick={() => void save(u.id)}>Choose</button> {u.firstName} {u.lastName} <span className="muted">{u.registrationNo}</span></p>
          ))}
        </>
      )}
      <ErrorText error={error} />
    </div>
  )
}

function EmploymentCard({ user, editable, onChange }: { user: User; editable: boolean; onChange: (u: User) => void }) {
  const [categories, setCategories] = useState<GradeCategory[]>([])
  const [categoryId, setCategoryId] = useState(user.gradeCategoryId ? String(user.gradeCategoryId) : '')
  const [hireDate, setHireDate] = useState(user.hireDate ?? '')
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  useEffect(() => { api<GradeCategory[]>('/api/leave/categories').then(setCategories).catch(() => undefined) }, [])

  const save = async () => {
    setError(null)
    setMessage(null)
    try {
      onChange(await api<User>(`/api/users/${user.id}/employment`, {
        method: 'PUT', body: { gradeCategoryId: categoryId ? Number(categoryId) : null, hireDate: hireDate || null },
      }))
      setMessage('Saved.')
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not save.')
    }
  }

  return (
    <div className="card">
      <h3>Employment</h3>
      <div className="grid2">
        <label>Grade category
          <select value={categoryId} disabled={!editable} onChange={(e) => setCategoryId(e.target.value)}>
            <option value="">None</option>
            {categories.map((c) => <option key={c.id} value={c.id}>{c.name} ({days(c.annualLeaveDays)} leave)</option>)}
          </select>
        </label>
        <label>Hire date<input type="date" value={hireDate} disabled={!editable} onChange={(e) => setHireDate(e.target.value)} /></label>
      </div>
      <p className="hint">The grade category sets the yearly annual leave. Grades and pay scales come with payroll.</p>
      {editable && <button className="secondary" onClick={() => void save()}>Save employment</button>}
      <ErrorText error={error} />
      {message && <p className="success">{message}</p>}
    </div>
  )
}

function LeaveCard({ userId }: { userId: number }) {
  const [data, setData] = useState<{ balances: LeaveBalance[]; ledger: LeaveLedgerEntry[] } | null>(null)
  const [types, setTypes] = useState<LeaveType[]>([])
  const [adjust, setAdjust] = useState({ leaveTypeId: '', days: '', note: '' })
  const [error, setError] = useState<string | null>(null)

  const load = () => api<{ balances: LeaveBalance[]; ledger: LeaveLedgerEntry[] }>(`/api/leave/users/${userId}`).then(setData).catch((e: Error) => setError(e.message))
  useEffect(() => {
    load()
    api<LeaveType[]>('/api/leave/types').then((t) => setTypes(t.filter((x) => x.usesBalance))).catch(() => undefined)
  }, [userId]) // eslint-disable-line react-hooks/exhaustive-deps

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setError(null)
    try {
      await api(`/api/leave/users/${userId}/adjustments`, { method: 'POST', body: { leaveTypeId: Number(adjust.leaveTypeId), days: Number(adjust.days), note: adjust.note } })
      setAdjust({ ...adjust, days: '', note: '' })
      await load()
    } catch (err) {
      setError(err instanceof ApiError ? [err.message, ...Object.values(err.fieldErrors)].join(' ') : 'Could not save.')
    }
  }

  return (
    <div className="card">
      <h3>Leave balance</h3>
      {data?.balances.map((b) => (
        <p key={b.leaveTypeId}><strong>{b.leaveType}: {days(b.available)}</strong> <span className="muted">({days(b.granted)} granted, {days(b.taken)} taken{b.pending ? `, ${days(b.pending)} waiting` : ''})</span></p>
      ))}
      <form className="row" onSubmit={submit}>
        <select value={adjust.leaveTypeId} onChange={(e) => setAdjust({ ...adjust, leaveTypeId: e.target.value })} required>
          <option value="">Kind</option>
          {types.map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}
        </select>
        <input type="number" step="0.5" placeholder="Days (+ or -)" className="narrow-input" value={adjust.days} onChange={(e) => setAdjust({ ...adjust, days: e.target.value })} required />
        <input placeholder="Reason, for example days carried from the old system" value={adjust.note} onChange={(e) => setAdjust({ ...adjust, note: e.target.value })} required />
        <button type="submit" className="secondary">Adjust</button>
      </form>
      <ErrorText error={error} />
      {data && data.ledger.length > 0 && (
        <details>
          <summary>History</summary>
          {data.ledger.map((l) => (
            <p key={l.id} className="muted">{l.entryDate} · {l.leaveType} · {l.days > 0 ? '+' : ''}{days(l.days)} · {l.kind.toLowerCase()}{l.note ? ` · ${l.note}` : ''} · {l.createdBy}</p>
          ))}
        </details>
      )}
    </div>
  )
}
