import { useEffect, useState, type FormEvent } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ApiError, api, download, days, money, type GradeCategory, type LeaveBalance, type LeaveLedgerEntry, type LeaveType, type Role, type Salary, type Schedule, type ScheduleAssignment, type Site, type User } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'
import { OrgCard, UnitSelect } from '../components/OrgCard'
import type { OrgUnit } from '../org'
import { Button, Form } from '../components/Button'
import { useAsk } from '../components/Dialogs'
import { locale, plural, t } from '../i18n'

type Form = { email: string; registrationNo: string; phone: string; firstName: string; lastName: string; siteId: string }
const empty: Form = { email: '', registrationNo: '', phone: '+243', firstName: '', lastName: '', siteId: '' }

export default function UserFormPage() {
  const { id } = useParams()
  const isNew = !id
  const navigate = useNavigate()
  const { can, me } = useAuth()
  const ask = useAsk()
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
      return true
    } catch (err) {
      if (err instanceof ApiError) {
        setError(err.message)
        setFieldErrors(Object.fromEntries(Object.entries(err.fieldErrors).map(([k, v]) => [k.replace(/^user\./, ''), v])))
      } else setError(t('Something went wrong.'))
      return false
    }
  }

  const payload = () => ({ ...form, siteId: form.siteId ? Number(form.siteId) : null })

  const save = (e: FormEvent) => {
    e.preventDefault()
    return handle(async () => {
      if (isNew) {
        const created = await api<User>('/api/users', {
          method: 'POST',
          body: { user: payload(), roles: selectedRoles, initialPassword: initialPassword || null, orgUnitId: orgUnitId ? Number(orgUnitId) : null },
        })
        navigate(`/users/${created.id}`, { replace: true })
      } else {
        setUser(await api<User>(`/api/users/${id}`, { method: 'PUT', body: payload() }))
        setMessage(t('Saved.'))
      }
    })
  }

  const saveRoles = () => handle(async () => {
    setUser(await api<User>(`/api/users/${id}/roles`, { method: 'PUT', body: { roles: selectedRoles } }))
    setMessage(t('Roles updated.'))
  })

  const toggleStatus = () => handle(async () => {
    const action = user?.status === 'ACTIVE' ? 'disable' : 'enable'
    setUser(await api<User>(`/api/users/${id}/${action}`, { method: 'POST' }))
  })

  const doReset = () => handle(async () => {
    await api(`/api/users/${id}/password`, { method: 'POST', body: { password: resetPassword } })
    setResetPassword('')
    setMessage(t('Temporary password set. The user must change it at next sign-in.'))
  })

  const resetTwoStep = () => ask({
    title: t('Reset the second step of {a} {b}?', { a: user?.firstName, b: user?.lastName }), confirmLabel: t('Reset'), danger: true,
    message: t('Their authenticator and recovery codes stop working, they are signed out everywhere, and they set the second step up again at their next sign-in.'),
    onConfirm: () => handle(async () => {
      await api(`/api/users/${id}/two-step/reset`, { method: 'POST' })
      setMessage(t('Second step reset. The person sets it up again at their next sign-in.'))
    }),
  })

  const signOutEverywhere = () => handle(async () => {
    const r = await api<{ count: number }>(`/api/users/${id}/sessions/end`, { method: 'POST' })
    setMessage(r.count === 0 ? t('The person was not signed in anywhere.') : plural('Signed out of {n} browser.', 'Signed out of {n} browsers.', r.count))
  })

  const assignSchedule = () => handle(async () => {
    await api(`/api/users/${id}/schedules`, { method: 'POST', body: { scheduleId: Number(newSchedule), effectiveFrom: scheduleFrom } })
    setAssignments(await api<ScheduleAssignment[]>(`/api/users/${id}/schedules`))
    setMessage(t('Schedule assigned.'))
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
      <h2>{isNew ? t('New user') : `${form.firstName} ${form.lastName}`}</h2>
      {user && (
        <p className="muted">
          {user.status === 'ACTIVE' ? t('Active') : t('Disabled')} · {user.hasPassword ? t('Password set') : t('Google sign-in only')}
          {user.lastLoginAt ? t(' · Last sign-in {a}', { a: new Date(user.lastLoginAt).toLocaleString(locale()) }) : t(' · Never signed in')}
        </p>
      )}
      <Form className="card" onSubmit={save}>
        <div className="grid2">
          {field('firstName', t('First name'))}
          {field('lastName', t('Last name'))}
        </div>
        {field('email', t('Email'), 'email')}
        <div className="grid2">
          {field('registrationNo', t('Registration number'))}
          {field('phone', t('Phone (WhatsApp)'), 'tel', t('International format, for example +243 81 234 5678'))}
        </div>
        <label>{t('Site')}<select value={form.siteId} onChange={set('siteId')} disabled={!canEdit}>
            <option value="">{t('No site')}</option>
            {sites.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
          </select>
        </label>
        {isNew && (
          <>
            <label>{t('Unit')}<UnitSelect units={units} value={orgUnitId} required onChange={setOrgUnitId} />
              <span className="hint">{t('Where this person works: Direction, Service, Équipe and so on. It can be changed later, with history.')}</span>
              {fieldErrors.orgUnitId && <span className="error">{fieldErrors.orgUnitId}</span>}
            </label>
            <fieldset>
              <legend>{t('Roles')}</legend>
              {roles.filter((r) => canAssign || r.code === 'EMPLOYEE').map((r) => (
                <label key={r.code} className="check">
                  <input type="checkbox" checked={selectedRoles.includes(r.code)} onChange={() => toggleRole(r.code)} />
                  {r.name}
                </label>
              ))}
            </fieldset>
            <label>{t('Temporary password (optional)')}<input type="password" value={initialPassword} onChange={(e) => setInitialPassword(e.target.value)} />
              <span className="hint">{t('Leave empty if the user will sign in with Google.')}</span>
            </label>
          </>
        )}
        <ErrorText error={error} />
        {message && <p className="success">{message}</p>}
        {canEdit && <Button type="submit">{isNew ? t('Create user') : t('Save changes')}</Button>}
      </Form>

      {!isNew && user && (
        <div className="card">
          <h3>{t('Work schedule')}</h3>
          {assignments.length === 0 && <p className="muted">{t('No schedule yet. Without one, no hours are expected.')}</p>}
          {assignments.map((a) => (
            <p key={a.id}>{a.scheduleName} <span className="muted">{t('from')}{' '}{a.effectiveFrom}</span></p>
          ))}
          {can('schedule.manage') && (
            <div className="row">
              <select value={newSchedule} onChange={(e) => setNewSchedule(e.target.value)}>
                <option value="">{t('Choose a schedule')}</option>
                {schedules.filter((s) => s.active).map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
              </select>
              <input type="date" value={scheduleFrom} onChange={(e) => setScheduleFrom(e.target.value)} />
              <Button className="secondary" disabled={!newSchedule} onClick={() => assignSchedule()}>{t('Assign')}</Button>
            </div>
          )}
        </div>
      )}

      {!isNew && user && can('attendance.read.all') && (
        <p className="row">
          <Link className="button secondary" to={`/users/${id}/attendance`}>{t('View attendance and earnings')}</Link>
          {can('salary.read') && <Link className="button secondary" to={`/users/${id}/file`}>{t('Contract and family')}</Link>}
        </p>
      )}

      {!isNew && user && can('salary.read') && <SalaryCard userId={Number(id)} />}

      {!isNew && user && <OrgCard userId={user.id} />}

      {!isNew && user && <ManagerCard user={user} editable={can('user.update')} onChange={setUser} />}

      {!isNew && user && <EmploymentCard user={user} editable={can('user.update')} onChange={setUser} />}

      {!isNew && user && can('leave.manage') && <LeaveCard userId={user.id} />}

      {!isNew && user && canAssign && (
        <div className="card">
          <h3>{t('Roles')}</h3>
          {roles.map((r) => (
            <label key={r.code} className="check">
              <input type="checkbox" checked={selectedRoles.includes(r.code)} onChange={() => toggleRole(r.code)} />
              {r.name}
            </label>
          ))}
          <Button onClick={() => saveRoles()}>{t('Save roles')}</Button>
        </div>
      )}

      {!isNew && user && (can('user.disable') || can('user.update')) && user.id !== me?.id && (
        <div className="card">
          <h3>{t('Access')}</h3>
          {can('user.update') && (
            <div className="row">
              <input type="password" placeholder={t('New temporary password')} value={resetPassword}
                onChange={(e) => setResetPassword(e.target.value)} />
              <Button className="secondary" disabled={!resetPassword} onClick={() => doReset()}>{t('Set password')}</Button>
            </div>
          )}
          <div className="row">
            {can('user.disable') && (
              <Button className={user.status === 'ACTIVE' ? 'danger' : ''} onClick={() => toggleStatus()}>
                {user.status === 'ACTIVE' ? t('Disable account') : t('Enable account')}
              </Button>
            )}
            {can('user.disable') && <Button className="secondary" onClick={() => signOutEverywhere()}>{t('Sign out everywhere')}</Button>}
            {can('role.assign') && <Button className="secondary" onClick={() => resetTwoStep()}>{t('Reset second step')}</Button>}
          </div>
          {can('role.assign') && <p className="hint">{t('Reset the second step only for someone who lost their phone and their recovery codes, after checking who is asking.')}</p>}
        </div>
      )}
      {!isNew && user && can('privacy.manage') && (
        <div className="card">
          <h3>{t('Personal data')}</h3>
          <p className="muted">{t('Everything HR Pay keeps about this person, for a request under data protection law.')}</p>
          <Button className="secondary" onClick={() => handle(() => download(`/api/users/${id}/data-export`, `data-${id}.json`))}>{t('Download their data')}</Button>
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
      setMessage(t('Salary saved.'))
      await load()
    } catch (err) {
      setError(err instanceof ApiError ? [err.message, ...Object.values(err.fieldErrors)].join(' ') : t('Could not save.'))
    }
  }

  return (
    <div className="card">
      <h3>{t('Gross monthly salary')}</h3>
      {history.length === 0 && <p className="muted">{t('No salary yet.')}</p>}
      {history.map((s) => (
        <p key={s.id}>{money(s.grossMonthly, s.currency)} <span className="muted">{t('from')}{' '}{s.effectiveFrom}{s.note ? ` · ${s.note}` : ''}{' '}{t('· set by')}{' '}{s.createdBy}</span></p>
      ))}
      {can('salary.write') && (
        <Form className="row" onSubmit={add}>
          <input type="date" value={form.effectiveFrom} onChange={(e) => setForm({ ...form, effectiveFrom: e.target.value })} required />
          <input type="number" min="0.01" step="0.01" placeholder={t('Gross per month')} value={form.grossMonthly}
            onChange={(e) => setForm({ ...form, grossMonthly: e.target.value })} required />
          <select value={form.currency} onChange={(e) => setForm({ ...form, currency: e.target.value })}>
            <option>{t('USD')}</option><option>{t('CDF')}</option>
          </select>
          <input placeholder={t('Reason (optional)')} value={form.note} onChange={(e) => setForm({ ...form, note: e.target.value })} />
          <Button type="submit">{t('Add')}</Button>
        </Form>
      )}
      <p className="hint">{t('A new amount applies from its date; earlier amounts stay in the history.')}</p>
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
    const tItem = setTimeout(() => {
      api<{ items: User[] }>(`/api/users?q=${encodeURIComponent(query.trim())}&size=8`)
        .then((p) => setResults(p.items.filter((u) => u.id !== user.id))).catch(() => setResults([]))
    }, 250)
    return () => clearTimeout(tItem)
  }, [query, user.id])

  const save = async (managerId: number | null) => {
    setError(null)
    try {
      onChange(await api<User>(`/api/users/${user.id}/manager`, { method: 'PUT', body: { managerId } }))
      setQuery('')
      setResults([])
    } catch (e) {
      setError(e instanceof Error ? e.message : t('Could not save.'))
    }
  }

  return (
    <div className="card">
      <h3>{t('Manager set by hand')}</h3>
      <p>{manager ? `${manager.firstName} ${manager.lastName}` : <span className="muted">{t('None: the head of the unit is used')}</span>}
        {editable && manager && <> <Button className="secondary void" onClick={() => save(null)}>{t('Remove')}</Button></>}</p>
      <p className="hint">{t('Normally people report to the head of their unit (or the nearest head above), who approves their missions, overtime and leave and scores their evaluations. Set a manager here only for an exception; heads above still see this person in their branch.')}</p>
      {editable && (
        <>
          <input placeholder={t('Search by name or registration number')} value={query} onChange={(e) => setQuery(e.target.value)} />
          {results.map((u) => (
            <p key={u.id}><Button className="secondary void" onClick={() => save(u.id)}>{t('Choose')}</Button> {u.firstName} {u.lastName} <span className="muted">{u.registrationNo}</span></p>
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
      setMessage(t('Saved.'))
    } catch (e) {
      setError(e instanceof Error ? e.message : t('Could not save.'))
    }
  }

  return (
    <div className="card">
      <h3>{t('Employment')}</h3>
      <div className="grid2">
        <label>{t('Grade category')}<select value={categoryId} disabled={!editable} onChange={(e) => setCategoryId(e.target.value)}>
            <option value="">{t('None')}</option>
            {categories.map((c) => <option key={c.id} value={c.id}>{c.name} ({days(c.annualLeaveDays)}{' '}{t('leave)')}</option>)}
          </select>
        </label>
        <label>{t('Hire date')}<input type="date" value={hireDate} disabled={!editable} onChange={(e) => setHireDate(e.target.value)} /></label>
      </div>
      <p className="hint">{t('The grade category sets the yearly annual leave. Grades and pay scales come with payroll.')}</p>
      {editable && <Button className="secondary" onClick={() => save()}>{t('Save employment')}</Button>}
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
    api<LeaveType[]>('/api/leave/types').then((tItem) => setTypes(tItem.filter((x) => x.usesBalance))).catch(() => undefined)
  }, [userId]) // eslint-disable-line react-hooks/exhaustive-deps

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setError(null)
    try {
      await api(`/api/leave/users/${userId}/adjustments`, { method: 'POST', body: { leaveTypeId: Number(adjust.leaveTypeId), days: Number(adjust.days), note: adjust.note } })
      setAdjust({ ...adjust, days: '', note: '' })
      await load()
    } catch (err) {
      setError(err instanceof ApiError ? [err.message, ...Object.values(err.fieldErrors)].join(' ') : t('Could not save.'))
    }
  }

  return (
    <div className="card">
      <h3>{t('Leave balance')}</h3>
      {data?.balances.map((b) => (
        <p key={b.leaveTypeId}><strong>{b.leaveType}: {days(b.available)}</strong> <span className="muted">({days(b.granted)}{' '}{t('granted,')}{' '}{days(b.taken)}{' '}{t('taken')}{b.pending ? t(", {a} waiting", { a: days(b.pending) }) : ''})</span></p>
      ))}
      <Form className="row" onSubmit={submit}>
        <select value={adjust.leaveTypeId} onChange={(e) => setAdjust({ ...adjust, leaveTypeId: e.target.value })} required>
          <option value="">{t('Kind')}</option>
          {types.map((tItem) => <option key={tItem.id} value={tItem.id}>{tItem.name}</option>)}
        </select>
        <input type="number" step="0.5" placeholder={t('Days (+ or -)')} className="narrow-input" value={adjust.days} onChange={(e) => setAdjust({ ...adjust, days: e.target.value })} required />
        <input placeholder={t('Reason, for example days carried from the old system')} value={adjust.note} onChange={(e) => setAdjust({ ...adjust, note: e.target.value })} required />
        <Button type="submit" className="secondary">{t('Adjust')}</Button>
      </Form>
      <ErrorText error={error} />
      {data && data.ledger.length > 0 && (
        <details>
          <summary>{t('History')}</summary>
          {data.ledger.map((l) => (
            <p key={l.id} className="muted">{l.entryDate} · {l.leaveType} · {l.days > 0 ? '+' : ''}{days(l.days)} · {l.kind.toLowerCase()}{l.note ? ` · ${l.note}` : ''} · {l.createdBy}</p>
          ))}
        </details>
      )}
    </div>
  )
}
