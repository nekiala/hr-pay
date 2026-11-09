import { useEffect, useState, type ReactNode } from 'react'
import { Link, useParams } from 'react-router-dom'
import { CONTRACT_TYPES, api, days, money, personName, type EmployeeFileView, type Grade, type LeaveBalance, type Role, type ScheduleAssignment, type User } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'
import { locale, t } from '../i18n'

/** A person's record, read-only: who they are, where they work, their contract, leave and family. Edit opens the form. */
export default function UserViewPage() {
  const { id } = useParams()
  const { can } = useAuth()
  const [user, setUser] = useState<User | null>(null)
  const [manager, setManager] = useState<User | null>(null)
  const [roles, setRoles] = useState<Role[]>([])
  const [file, setFile] = useState<EmployeeFileView | null>(null)
  const [grades, setGrades] = useState<Grade[]>([])
  const [leave, setLeave] = useState<LeaveBalance[] | null>(null)
  const [schedules, setSchedules] = useState<ScheduleAssignment[]>([])
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    setManager(null)
    api<User>(`/api/users/${id}`).then((u) => {
      setUser(u)
      if (u.managerId) api<User>(`/api/users/${u.managerId}`).then(setManager).catch(() => undefined)
    }).catch((e: Error) => setError(e.message))
    api<Role[]>('/api/roles').then(setRoles).catch(() => undefined)
    api<ScheduleAssignment[]>(`/api/users/${id}/schedules`).then(setSchedules).catch(() => undefined)
    if (can('salary.read')) {
      api<EmployeeFileView>(`/api/users/${id}/file`).then(setFile).catch(() => undefined)
      api<Grade[]>('/api/grades').then(setGrades).catch(() => undefined)
    }
    if (can('leave.manage')) {
      api<{ balances: LeaveBalance[] }>(`/api/leave/users/${id}`).then((d) => setLeave(d.balances)).catch(() => undefined)
    }
  }, [id, can])

  if (!user) return <section><ErrorText error={error} /></section>

  const today = new Date().toISOString().slice(0, 10)
  const contract = file?.contracts.find((c) => c.startDate <= today && (!c.endDate || c.endDate >= today)) ?? file?.contracts[0] ?? null
  const grade = contract ? grades.find((g) => g.id === contract.gradeId) : undefined
  const schedule = [...schedules].filter((s) => s.effectiveFrom <= today).sort((a, b) => b.effectiveFrom.localeCompare(a.effectiveFrom))[0]
  const counted = file?.dependants.filter((d) => d.counts) ?? []
  const children = counted.filter((d) => d.relationship === 'CHILD').length
  const spouse = counted.some((d) => d.relationship === 'SPOUSE')
  const roleName = (code: string) => roles.find((r) => r.code === code)?.name ?? code
  const empty = <span className="muted">{t('Not given')}</span>
  const fact = (label: string, value: ReactNode) => <div><dt>{label}</dt><dd>{value ?? empty}</dd></div>

  return (
    <section>
      <p><Link to="/users">{t('← All users')}</Link></p>
      <div className="row" style={{ justifyContent: 'space-between', alignItems: 'flex-start' }}>
        <div>
          <h2 style={{ marginBottom: 4 }}>{personName(user)}</h2>
          <p className="muted">
            {user.registrationNo} · <span className={`badge ${user.status === 'ACTIVE' ? 'ok' : 'off'}`}>{user.status === 'ACTIVE' ? t('Active') : t('Disabled')}</span>
            {' · '}{user.roles.map(roleName).join(', ')}
          </p>
        </div>
        {can('user.update') && <Link className="button" to={`/users/${id}/edit`}>{t('Edit')}</Link>}
      </div>

      <div className="tiles">
        {can('salary.read') && (
          <div className="tile">
            <span>{t('Grade')}</span>
            <strong>{contract ? contract.gradeCode : '—'}</strong>
            <small>{contract ? `${contract.gradeName}${grade ? ` · ${money(grade.baseSalary, grade.currency)}` : ''}` : t('No contract yet')}</small>
          </div>
        )}
        {can('salary.read') && (
          <div className="tile">
            <span>{t('Contract')}</span>
            <strong>{contract ? CONTRACT_TYPES[contract.contractType] : '—'}</strong>
            <small>{contract ? `${t('from')} ${contract.startDate}${contract.endDate ? t(' to {a}', { a: contract.endDate }) : ''}` : ''}</small>
          </div>
        )}
        {leave && leave.length > 0 && (
          <div className="tile">
            <span>{leave[0].leaveType}</span>
            <strong>{days(leave[0].available)}</strong>
            <small>{t('available')}</small>
          </div>
        )}
        {file && (
          <div className="tile">
            <span>{t('Family for allowances')}</span>
            <strong>{children}</strong>
            <small>{children === 1 ? t('child') : t('children')}{spouse ? t(' and the spouse') : ''}</small>
          </div>
        )}
      </div>

      <div className="card">
        <h3>{t('Identity and contact')}</h3>
        <dl className="facts">
          {fact(t('Last name'), user.lastName)}
          {fact(t('Postnom'), user.middleName || null)}
          {fact(t('First name'), user.firstName)}
          {fact(t('Registration number'), user.registrationNo)}
          {fact(t('Email'), <a href={`mailto:${user.email}`}>{user.email}</a>)}
          {fact(t('Phone (WhatsApp)'), user.phone)}
        </dl>
      </div>

      <div className="card">
        <h3>{t('Work')}</h3>
        <dl className="facts">
          {fact(t('Unit'), user.unitId ? <Link to={`/org/units/${user.unitId}`}>{user.unitName}</Link> : null)}
          {fact(t('Site'), user.siteName)}
          {fact(t('Manager'), manager ? <Link to={`/users/${manager.id}`}>{personName(manager)}</Link> : <span className="muted">{t('The head of the unit')}</span>)}
          {fact(t('Hire date'), user.hireDate ?? <span className="muted">{user.notEmployee ? t('Not an employee') : t('No contract')}</span>)}
          {fact(t('Work schedule'), schedule ? `${schedule.scheduleName} (${t('from')} ${schedule.effectiveFrom})` : null)}
          {can('salary.read') && fact(t('Occupation'), contract?.occupationName ?? null)}
          {fact(t('Last sign-in'), user.lastLoginAt ? new Date(user.lastLoginAt).toLocaleString(locale()) : <span className="muted">{t('Never signed in')}</span>)}
        </dl>
      </div>

      <p className="row">
        {can('attendance.read.all') && <Link className="button secondary" to={`/users/${id}/attendance`}>{t('View attendance and earnings')}</Link>}
        {can('salary.read') && <Link className="button secondary" to={`/users/${id}/file`}>{t('Contract and family')}</Link>}
        {(can('payroll.run') || can('payroll.approve')) && <Link className="button secondary" to={`/users/${id}/departure`}>{t('Departure')}</Link>}
        {can('lifecycle.manage') && <Link className="button secondary" to={`/users/${id}/lifecycle`}>{t('Documents and checklists')}</Link>}
      </p>
    </section>
  )
}
