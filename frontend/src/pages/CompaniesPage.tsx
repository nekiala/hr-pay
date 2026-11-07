import { useEffect, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { api, longDay, MODULE_NAMES, type CompanySummary, type Module, type Plan } from '../api'
import { ErrorText } from '../components/ErrorText'
import { Modal } from '../components/Modal'
import { Button, Form } from '../components/Button'

const ALL: Module[] = ['ATTENDANCE', 'PAYROLL', 'EVALUATIONS']

type Created = { company: CompanySummary; adminEmail: string; temporaryPassword: string }

function ModulePicker({ value, onChange }: { value: Module[]; onChange: (m: Module[]) => void }) {
  return (
    <fieldset>
      <legend>Modules</legend>
      <div className="module-list">
        {ALL.map((m) => (
          <label key={m} className="check">
            <input type="checkbox" checked={value.includes(m)}
              onChange={(e) => onChange(e.target.checked ? [...value, m] : value.filter((x) => x !== m))} />
            {MODULE_NAMES[m]}
          </label>
        ))}
      </div>
    </fieldset>
  )
}

function Billing({ c }: { c: CompanySummary }) {
  if (!c.subscriptionStatus) return <span className="muted">—</span>
  if (c.subscriptionStatus === 'CANCELLED') return <span className="muted">Cancelled</span>
  return (
    <>
      {c.subscriptionStatus === 'TRIAL' || (c.trialEnds && c.trialEnds >= new Date().toISOString().slice(0, 10)) ? `Trial until ${longDay(c.trialEnds)}` : `${c.cycle === 'YEARLY' ? 'Yearly' : 'Monthly'}, next ${longDay(c.nextInvoiceOn)}`}
      {c.overdueInvoices > 0
        ? <div><span className="badge off">{c.overdueInvoices} overdue</span></div>
        : c.openInvoices > 0 && <div className="muted small">{c.openInvoices} to pay</div>}
    </>
  )
}

/** The provider's list of companies on this installation, with their plan, modules and size. */
export default function CompaniesPage() {
  const [companies, setCompanies] = useState<CompanySummary[]>([])
  const [plans, setPlans] = useState<Plan[]>([])
  const [countries, setCountries] = useState<Record<string, string>>({})
  const [error, setError] = useState<string | null>(null)
  const [adding, setAdding] = useState(false)
  const [created, setCreated] = useState<Created | null>(null)
  const [editing, setEditing] = useState<CompanySummary | null>(null)

  const [name, setName] = useState('')
  const [country, setCountry] = useState('CD')
  const [plan, setPlan] = useState('UP_TO_50')
  const [modules, setModules] = useState<Module[]>(ALL)
  const [admin, setAdmin] = useState({ firstName: '', lastName: '', email: '', phone: '' })
  const [formError, setFormError] = useState<string | null>(null)

  const [editPlan, setEditPlan] = useState('')
  const [editModules, setEditModules] = useState<Module[]>([])
  const [editStatus, setEditStatus] = useState<'ACTIVE' | 'SUSPENDED'>('ACTIVE')

  const load = () => api<CompanySummary[]>('/api/platform/companies').then(setCompanies).catch((e: Error) => setError(e.message))
  useEffect(() => {
    void load()
    api<Plan[]>('/api/platform/plans').then(setPlans).catch(() => undefined)
    api<Record<string, string>>('/api/platform/countries').then(setCountries).catch(() => undefined)
  }, [])

  const openNew = () => {
    setName(''); setCountry('CD'); setPlan('UP_TO_50'); setModules(ALL)
    setAdmin({ firstName: '', lastName: '', email: '', phone: '' }); setFormError(null); setAdding(true)
  }

  const create = async (e: FormEvent) => {
    e.preventDefault()
    if (modules.length === 0) { setFormError('Choose at least one module.'); return }
    try {
      const result = await api<Created>('/api/platform/companies', { method: 'POST', body: { name, countryCode: country, planCode: plan, modules, admin } })
      setAdding(false)
      setCreated(result)
      await load()
    } catch (err) {
      setFormError(err instanceof Error ? err.message : 'Could not create the company.')
    }
  }

  const openEdit = (c: CompanySummary) => {
    setEditing(c); setEditPlan(c.planCode); setEditModules(c.modules); setEditStatus(c.status); setFormError(null)
  }

  const save = async (e: FormEvent) => {
    e.preventDefault()
    if (!editing) return
    if (editModules.length === 0) { setFormError('Choose at least one module.'); return }
    try {
      await api(`/api/platform/companies/${editing.id}`, { method: 'PUT', body: { planCode: editPlan, modules: editModules, status: editStatus } })
      setEditing(null)
      await load()
    } catch (err) {
      setFormError(err instanceof Error ? err.message : 'Could not save.')
    }
  }

  const planSelect = (value: string, onChange: (v: string) => void) => (
    <label>Plan
      <select value={value} onChange={(e) => onChange(e.target.value)}>
        {plans.map((p) => <option key={p.code} value={p.code}>{p.name}</option>)}
      </select>
    </label>
  )

  return (
    <section>
      <div className="row between">
        <h2>Companies</h2>
        <Button onClick={openNew}>New company</Button>
      </div>
      <p className="muted">Each company has its own people, structure, payroll and settings, and never sees another company's data. A new company starts with its country's grades, rates, holidays and roles.</p>
      <ErrorText error={error} />
      <div className="table-wrap">
        <table>
          <thead><tr><th>Company</th><th>Country</th><th>Plan</th><th className="num">Employees</th><th>Modules</th><th>Billing</th><th>Status</th><th /></tr></thead>
          <tbody>
            {companies.map((c) => {
              const over = c.maxEmployees !== null && c.employees > c.maxEmployees
              return (
                <tr key={c.id}>
                  <td>
                    {c.provider ? <strong>{c.name}</strong> : <Link to={`/platform/companies/${c.id}`}><strong>{c.name}</strong></Link>}
                    {c.provider && <span className="muted small"> · your company</span>}
                    {c.supportOpen && <span className="badge warn small">Support open</span>}
                    <div className="muted small">{c.lastSignIn ? `Last sign-in ${longDay(c.lastSignIn)}` : 'No sign-in yet'}</div>
                  </td>
                  <td>{countries[c.countryCode] ?? c.countryCode}</td>
                  <td>{c.planName}</td>
                  <td className="num">{over ? <span className="badge warn">{c.employees} / {c.maxEmployees}</span> : c.employees}</td>
                  <td>{c.modules.map((m) => MODULE_NAMES[m]).join(', ')}</td>
                  <td><Billing c={c} /></td>
                  <td><span className={`badge ${c.status === 'ACTIVE' ? 'ok' : 'off'}`}>{c.status === 'ACTIVE' ? 'Active' : c.suspendedReason === 'UNPAID' ? 'Read-only: unpaid' : 'Suspended'}</span></td>
                  <td className="actions">
                    <Button className="secondary" onClick={() => openEdit(c)}>Change plan</Button>
                    {!c.provider && <Link className="button secondary" to={`/platform/companies/${c.id}`}>Open</Link>}
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>

      {adding && (
        <Modal title="New company" size="lg" onClose={() => setAdding(false)}>
          <Form onSubmit={create}>
            <ErrorText error={formError} />
            <div className="grid2">
              <label>Company name<input required maxLength={150} value={name} onChange={(e) => setName(e.target.value)} /></label>
              <label>Country
                <select value={country} onChange={(e) => setCountry(e.target.value)}>
                  {Object.entries(countries).map(([code, label]) => <option key={code} value={code}>{label}</option>)}
                </select>
              </label>
            </div>
            {planSelect(plan, setPlan)}
            <ModulePicker value={modules} onChange={setModules} />
            <h4>First administrator</h4>
            <p className="hint">Gets the Administrator and HR roles, and a temporary password to change at the first sign-in.</p>
            <div className="grid2">
              <label>First name<input required value={admin.firstName} onChange={(e) => setAdmin({ ...admin, firstName: e.target.value })} /></label>
              <label>Last name<input required value={admin.lastName} onChange={(e) => setAdmin({ ...admin, lastName: e.target.value })} /></label>
              <label>Email<input required type="email" value={admin.email} onChange={(e) => setAdmin({ ...admin, email: e.target.value })} /></label>
              <label>Phone<input required placeholder="+243812345678" value={admin.phone} onChange={(e) => setAdmin({ ...admin, phone: e.target.value })} /></label>
            </div>
            <footer className="modal-foot">
              <Button className="secondary" onClick={() => setAdding(false)}>Cancel</Button>
              <Button type="submit">Create company</Button>
            </footer>
          </Form>
        </Modal>
      )}

      {created && (
        <Modal title={`${created.company.name} is ready`} size="sm" onClose={() => setCreated(null)}
          footer={<Button onClick={() => setCreated(null)}>Done</Button>}>
          <p>Send these to the administrator. The password is shown only now and must be changed at the first sign-in.</p>
          <dl className="facts">
            <dt>Email</dt><dd>{created.adminEmail}</dd>
            <dt>Temporary password</dt><dd className="mono">{created.temporaryPassword}</dd>
          </dl>
        </Modal>
      )}

      {editing && (
        <Modal title={`Plan of ${editing.name}`} onClose={() => setEditing(null)}>
          <Form onSubmit={save}>
            <ErrorText error={formError} />
            {planSelect(editPlan, setEditPlan)}
            <ModulePicker value={editModules} onChange={setEditModules} />
            {!editing.provider && (
              <label>Status
                <select value={editStatus} onChange={(e) => setEditStatus(e.target.value as 'ACTIVE' | 'SUSPENDED')}>
                  <option value="ACTIVE">Active</option>
                  <option value="SUSPENDED">Suspended: read-only, scheduled jobs stop</option>
                </select>
              </label>
            )}
            <p className="hint">Removing a module hides it and stops changes in it; its data stays and can still be read.</p>
            <footer className="modal-foot">
              <Button className="secondary" onClick={() => setEditing(null)}>Cancel</Button>
              <Button type="submit">Save</Button>
            </footer>
          </Form>
        </Modal>
      )}
    </section>
  )
}
