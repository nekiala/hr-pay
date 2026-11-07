import { useEffect, useState, type FormEvent } from 'react'
import { Link, useParams } from 'react-router-dom'
import { ApiError, CONTRACT_TYPES, api, money, type EmployeeFileView, type Grade, type Occupation, type Profile, type User } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'

const age = (birth: string | null) => {
  if (!birth) return ''
  const b = new Date(birth)
  const n = new Date()
  return String(n.getFullYear() - b.getFullYear() - (n < new Date(n.getFullYear(), b.getMonth(), b.getDate()) ? 1 : 0))
}

export default function EmployeeFilePage() {
  const { id } = useParams()
  const { can } = useAuth()
  const editable = can('salary.write')
  const [user, setUser] = useState<User | null>(null)
  const [file, setFile] = useState<EmployeeFileView | null>(null)
  const [grades, setGrades] = useState<Grade[]>([])
  const [occupations, setOccupations] = useState<Occupation[]>([])
  const [profile, setProfile] = useState<Profile | null>(null)
  const [contract, setContract] = useState({ contractType: 'PERMANENT', startDate: '', endDate: '', gradeId: '', occupationId: '', expatriate: false, negotiatedBasic: '', note: '' })
  const [dependant, setDependant] = useState({ fullName: '', relationship: 'CHILD', birthDate: '', inCharge: true, note: '' })
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)

  const load = () => api<EmployeeFileView>(`/api/users/${id}/file`).then((f) => { setFile(f); setProfile(f.profile) }).catch((e: Error) => setError(e.message))
  useEffect(() => {
    load()
    api<User>(`/api/users/${id}`).then(setUser).catch(() => undefined)
    api<Grade[]>('/api/grades').then(setGrades).catch(() => undefined)
    api<Occupation[]>('/api/occupations').then(setOccupations).catch(() => undefined)
  }, [id]) // eslint-disable-line react-hooks/exhaustive-deps

  const run = async (action: () => Promise<unknown>, done: string) => {
    setError(null)
    setMessage(null)
    try {
      await action()
      setMessage(done)
      await load()
    } catch (e) {
      setError(e instanceof ApiError ? [e.message, ...Object.values(e.fieldErrors)].join(' ') : 'Something went wrong.')
    }
  }

  const grade = grades.find((g) => g.id === Number(contract.gradeId))

  const startContract = (e: FormEvent) => {
    e.preventDefault()
    void run(() => api(`/api/users/${id}/contracts`, {
      method: 'POST',
      body: {
        ...contract, gradeId: Number(contract.gradeId), occupationId: contract.occupationId ? Number(contract.occupationId) : null,
        endDate: contract.endDate || null, negotiatedBasic: contract.negotiatedBasic ? Number(contract.negotiatedBasic) : null,
      },
    }), 'Contract saved.')
  }

  const saveProfile = (e: FormEvent) => {
    e.preventDefault()
    void run(() => api(`/api/users/${id}/profile`, { method: 'PUT', body: profile }), 'Details saved.')
  }

  const addDependant = (e: FormEvent) => {
    e.preventDefault()
    void run(async () => {
      await api(`/api/users/${id}/dependants`, { method: 'POST', body: { ...dependant, birthDate: dependant.birthDate || null } })
      setDependant({ fullName: '', relationship: 'CHILD', birthDate: '', inCharge: true, note: '' })
    }, 'Added.')
  }

  const p = profile
  const setP = (patch: Partial<Profile>) => p && setProfile({ ...p, ...patch })

  return (
    <section className="narrow">
      <p><Link to={`/users/${id}`}>← Back to employee</Link></p>
      <h2>Contract and family{user ? `: ${user.firstName} ${user.lastName}` : ''}</h2>
      <ErrorText error={error} />
      {message && <p className="success">{message}</p>}

      <div className="card">
        <h3>Contracts</h3>
        {file?.contracts.length === 0 && <p className="muted">No contract yet.</p>}
        {file?.contracts.map((c) => (
          <p key={c.id}>
            <strong>{CONTRACT_TYPES[c.contractType]}</strong> · {c.gradeName} ({c.gradeCode}){c.occupationName ? ` · ${c.occupationName}` : ''}{c.expatriate ? ' · expatriate' : ''}
            <br /><span className="muted">from {c.startDate}{c.endDate ? ` to ${c.endDate}` : ''}{c.note ? ` · ${c.note}` : ''}</span>
          </p>
        ))}
        {editable && (
          <form onSubmit={startContract}>
            <h3>New contract or change</h3>
            <div className="grid3">
              <label>Type
                <select value={contract.contractType} onChange={(e) => setContract({ ...contract, contractType: e.target.value })}>
                  {Object.entries(CONTRACT_TYPES).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
                </select>
              </label>
              <label>Starts<input type="date" value={contract.startDate} onChange={(e) => setContract({ ...contract, startDate: e.target.value })} required /></label>
              <label>Ends<input type="date" value={contract.endDate} onChange={(e) => setContract({ ...contract, endDate: e.target.value })} required={contract.contractType === 'FIXED_TERM'} /></label>
            </div>
            <div className="grid2">
              <label>Grade
                <select value={contract.gradeId} onChange={(e) => setContract({ ...contract, gradeId: e.target.value })} required>
                  <option value="">Choose</option>
                  {grades.filter((g) => g.active).map((g) => <option key={g.id} value={g.id}>{g.name} · {money(g.baseSalary, g.currency)}</option>)}
                </select>
              </label>
              <label>Occupation
                <select value={contract.occupationId} onChange={(e) => setContract({ ...contract, occupationId: e.target.value })}>
                  <option value="">None</option>
                  {occupations.filter((o) => o.active).map((o) => <option key={o.id} value={o.id}>{o.name}</option>)}
                </select>
              </label>
            </div>
            <div className="grid2">
              <label>Negotiated basic (optional)
                <input type="number" min="0.01" step="0.01" value={contract.negotiatedBasic} placeholder={grade ? `Grade basic: ${money(grade.baseSalary, grade.currency)}` : ''}
                  onChange={(e) => setContract({ ...contract, negotiatedBasic: e.target.value })} />
              </label>
              <label>Note<input value={contract.note} maxLength={300} onChange={(e) => setContract({ ...contract, note: e.target.value })} /></label>
            </div>
            <label className="check"><input type="checkbox" checked={contract.expatriate} onChange={(e) => setContract({ ...contract, expatriate: e.target.checked })} />Expatriate (employer pays IERE)</label>
            <p className="hint">The previous contract ends the day before. The basic salary from that date is the negotiated amount, or the grade's.</p>
            <button type="submit">Save contract</button>
          </form>
        )}
      </div>

      {p && (
        <form className="card" onSubmit={saveProfile}>
          <h3>Personal and payment details</h3>
          <fieldset disabled={!editable}>
            <div className="grid3">
              <label>Date of birth<input type="date" value={p.birthDate ?? ''} onChange={(e) => setP({ birthDate: e.target.value || null })} /></label>
              <label>Gender
                <select value={p.gender ?? ''} onChange={(e) => setP({ gender: (e.target.value || null) as Profile['gender'] })}>
                  <option value="">Not given</option><option value="FEMALE">Female</option><option value="MALE">Male</option>
                </select>
              </label>
              <label>Nationality<input value={p.nationality ?? ''} onChange={(e) => setP({ nationality: e.target.value })} /></label>
            </div>
            <div className="grid3">
              <label>Marital status
                <select value={p.maritalStatus} onChange={(e) => setP({ maritalStatus: e.target.value as Profile['maritalStatus'] })}>
                  <option value="SINGLE">Single</option><option value="MARRIED">Married</option><option value="DIVORCED">Divorced</option><option value="WIDOWED">Widowed</option>
                </select>
              </label>
              <label>CNSS number<input value={p.cnssNumber ?? ''} onChange={(e) => setP({ cnssNumber: e.target.value })} /></label>
              <label>Tax number (NIF)<input value={p.taxNumber ?? ''} onChange={(e) => setP({ taxNumber: e.target.value })} /></label>
            </div>
            <label>Paid by
              <select value={p.paymentMethod} onChange={(e) => setP({ paymentMethod: e.target.value as Profile['paymentMethod'] })}>
                <option value="BANK">Bank transfer</option><option value="MOBILE_MONEY">Mobile money</option><option value="CASH">Cash</option>
              </select>
            </label>
            {p.paymentMethod === 'BANK' && (
              <div className="grid2">
                <label>Bank<input value={p.bankName ?? ''} onChange={(e) => setP({ bankName: e.target.value })} required /></label>
                <label>Account number<input value={p.bankAccount ?? ''} onChange={(e) => setP({ bankAccount: e.target.value })} required /></label>
              </div>
            )}
            {p.paymentMethod === 'MOBILE_MONEY' && (
              <div className="grid2">
                <label>Operator<input value={p.mobileMoneyOperator ?? ''} placeholder="M-Pesa, Orange Money, Airtel Money" onChange={(e) => setP({ mobileMoneyOperator: e.target.value })} required /></label>
                <label>Number<input value={p.mobileMoneyNumber ?? ''} placeholder="+243…" onChange={(e) => setP({ mobileMoneyNumber: e.target.value })} required /></label>
              </div>
            )}
          </fieldset>
          {editable && <button type="submit">Save details</button>}
        </form>
      )}

      <div className="card">
        <h3>Spouse and children</h3>
        <p className="hint">Dependants in the employee's charge count for family allowances and the tax reduction.</p>
        {file?.dependants.length === 0 && <p className="muted">None recorded.</p>}
        {file?.dependants.map((d) => (
          <div key={d.id} className="row">
            <span>{d.relationship === 'SPOUSE' ? 'Spouse' : 'Child'}: <strong>{d.fullName}</strong>{d.birthDate ? ` · ${age(d.birthDate)} years` : ''}{d.inCharge ? '' : ' · not in charge'}</span>
            {editable && (
              <>
                <button className="secondary void" onClick={() => void run(() => api(`/api/users/${id}/dependants/${d.id}`, { method: 'PUT', body: { inCharge: !d.inCharge, note: d.note } }), 'Updated.')}>
                  {d.inCharge ? 'Mark not in charge' : 'Mark in charge'}
                </button>
                <button className="secondary void" onClick={() => { if (confirm(`Remove ${d.fullName}?`)) void run(() => api(`/api/users/${id}/dependants/${d.id}`, { method: 'DELETE' }), 'Removed.') }}>Remove</button>
              </>
            )}
          </div>
        ))}
        {editable && (
          <form className="row" onSubmit={addDependant}>
            <select value={dependant.relationship} onChange={(e) => setDependant({ ...dependant, relationship: e.target.value })}>
              <option value="CHILD">Child</option><option value="SPOUSE">Spouse</option>
            </select>
            <input placeholder="Full name" value={dependant.fullName} onChange={(e) => setDependant({ ...dependant, fullName: e.target.value })} required />
            <input type="date" value={dependant.birthDate} onChange={(e) => setDependant({ ...dependant, birthDate: e.target.value })} required={dependant.relationship === 'CHILD'} />
            <button type="submit" className="secondary">Add</button>
          </form>
        )}
      </div>
    </section>
  )
}
