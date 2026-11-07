import { useEffect, useState, type FormEvent } from 'react'
import { ApiError, PAY_KINDS, PAY_METHODS, api, money, type Page, type PayElement, type PayEntry, type PayMethod, type User } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'

const thisMonth = () => new Date().toISOString().slice(0, 7)

const describe = (e: PayElement) => {
  switch (e.method) {
    case 'FIXED': return `${money(e.defaultAmount ?? 0, e.defaultCurrency ?? 'USD')} a month`
    case 'PERCENT_OF_BASIC': return `${e.percent}% of basic`
    case 'PER_DAY_WORKED': return `${money(e.defaultAmount ?? 0, e.defaultCurrency ?? 'USD')} a day worked`
    case 'PER_CHILD': return `${money(e.defaultAmount ?? 0, e.defaultCurrency ?? 'USD')} a child`
    default: return PAY_METHODS[e.method]
  }
}

const blankElement = {
  code: '', name: '', kind: 'EARNING', method: 'FIXED' as PayMethod, defaultAmount: '', defaultCurrency: 'USD', percent: '',
  subjectToCnss: false, taxableIpr: false, iprExemptCapPercent: '', prorated: false, defaultForAll: false, displayOrder: 100,
}

export default function PayElementsPage() {
  const { can } = useAuth()
  const manage = can('masterdata.manage')
  const writePay = can('salary.write')
  const readPay = can('salary.read')
  const [elements, setElements] = useState<PayElement[]>([])
  const [month, setMonth] = useState(thisMonth())
  const [entries, setEntries] = useState<PayEntry[]>([])
  const [edit, setEdit] = useState<typeof blankElement & { id?: number; system?: boolean; active?: boolean } | null>(null)
  const [entry, setEntry] = useState({ userQuery: '', userId: '', elementId: '', amount: '', currency: 'USD', note: '' })
  const [people, setPeople] = useState<User[]>([])
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)

  const loadElements = () => api<PayElement[]>('/api/pay-elements').then(setElements).catch((e: Error) => setError(e.message))
  const loadEntries = () => readPay ? api<PayEntry[]>(`/api/pay-entries?month=${month}`).then(setEntries).catch((e: Error) => setError(e.message)) : Promise.resolve()
  useEffect(() => { void loadElements() }, [])
  useEffect(() => { void loadEntries() }, [month]) // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (!writePay || entry.userQuery.trim().length < 2) return
    const handle = setTimeout(() => {
      api<Page<User>>(`/api/users?${new URLSearchParams({ q: entry.userQuery.trim(), size: '10' })}`).then((p) => setPeople(p.items)).catch(() => undefined)
    }, 250)
    return () => clearTimeout(handle)
  }, [entry.userQuery, writePay])

  const run = async (action: () => Promise<unknown>, done: string) => {
    setError(null)
    setMessage(null)
    try {
      await action()
      setMessage(done)
      await Promise.all([loadElements(), loadEntries()])
    } catch (e) {
      setError(e instanceof ApiError ? [e.message, ...Object.values(e.fieldErrors)].join(' ') : 'Something went wrong.')
    }
  }

  const saveElement = (ev: FormEvent) => {
    ev.preventDefault()
    if (!edit) return
    const num = (v: string | number) => (v === '' || v === null ? null : Number(v))
    const body = {
      ...edit, defaultAmount: num(edit.defaultAmount), percent: num(edit.percent), iprExemptCapPercent: num(edit.iprExemptCapPercent),
      defaultCurrency: edit.defaultAmount === '' ? null : edit.defaultCurrency, active: edit.active ?? true,
      kind: edit.system ? 'EARNING' : edit.kind, method: edit.system ? 'FIXED' : edit.method,
    }
    void run(async () => {
      await api(edit.id ? `/api/pay-elements/${edit.id}` : '/api/pay-elements', { method: edit.id ? 'PUT' : 'POST', body })
      setEdit(null)
    }, 'Pay element saved.')
  }

  const startEdit = (e: PayElement) => setEdit({
    id: e.id, system: e.system, active: e.active, code: e.code, name: e.name, kind: e.kind, method: e.method,
    defaultAmount: e.defaultAmount === null ? '' : String(e.defaultAmount), defaultCurrency: e.defaultCurrency ?? 'USD',
    percent: e.percent === null ? '' : String(e.percent), subjectToCnss: e.subjectToCnss, taxableIpr: e.taxableIpr,
    iprExemptCapPercent: e.iprExemptCapPercent === null ? '' : String(e.iprExemptCapPercent), prorated: e.prorated,
    defaultForAll: e.defaultForAll, displayOrder: e.displayOrder,
  })

  const addEntry = (ev: FormEvent) => {
    ev.preventDefault()
    void run(async () => {
      await api('/api/pay-entries', {
        method: 'POST',
        body: { userId: Number(entry.userId), elementId: Number(entry.elementId), month, amount: Number(entry.amount), currency: entry.currency, note: entry.note || null },
      })
      setEntry({ userQuery: '', userId: '', elementId: '', amount: '', currency: entry.currency, note: '' })
      setPeople([])
    }, 'Entry added.')
  }

  const manual = elements.filter((e) => e.active && e.method !== 'COMPUTED' && e.kind !== 'EMPLOYER_CHARGE')
  const needsAmount = edit && ['FIXED', 'PER_DAY_WORKED', 'PER_CHILD'].includes(edit.method)

  return (
    <section>
      <h2>Pay elements</h2>
      <ErrorText error={error} />
      {message && <p className="success">{message}</p>}

      {readPay && (
        <div className="card">
          <h3>Bonuses and deductions for a month</h3>
          <p className="hint">One-off lines for this month only, such as a performance bonus. Standing items such as a different transport amount are set on the employee's Contract and family page.</p>
          <label className="month-pick">Month <input type="month" value={month} onChange={(e) => setMonth(e.target.value)} /></label>
          {entries.length === 0 ? <p className="muted">Nothing entered for {month}.</p> : (
            <div className="table-wrap">
              <table>
                <thead><tr><th>Employee</th><th>Element</th><th>Amount</th><th>Note</th><th>By</th><th></th></tr></thead>
                <tbody>
                  {entries.map((x) => (
                    <tr key={x.id}>
                      <td>{x.userName}</td>
                      <td>{x.elementName}</td>
                      <td>{x.kind === 'DEDUCTION' ? '−' : ''}{money(x.amount, x.currency)}</td>
                      <td>{x.note}</td>
                      <td className="muted">{x.createdBy}</td>
                      <td>{writePay && <button className="secondary" onClick={() => { if (confirm(`Remove ${x.elementName} for ${x.userName}?`)) void run(() => api(`/api/pay-entries/${x.id}`, { method: 'DELETE' }), 'Removed.') }}>Remove</button>}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {writePay && (
            <form onSubmit={addEntry}>
              <div className="grid3">
                <label>Employee
                  <input list="pay-people" placeholder="Type a name or number" value={entry.userQuery} required
                    onChange={(e) => {
                      const match = people.find((p) => `${p.firstName} ${p.lastName} (${p.registrationNo ?? p.email})` === e.target.value)
                      setEntry({ ...entry, userQuery: e.target.value, userId: match ? String(match.id) : '' })
                    }} />
                  <datalist id="pay-people">
                    {people.map((p) => <option key={p.id} value={`${p.firstName} ${p.lastName} (${p.registrationNo ?? p.email})`} />)}
                  </datalist>
                </label>
                <label>Element
                  <select value={entry.elementId} required onChange={(e) => setEntry({ ...entry, elementId: e.target.value })}>
                    <option value="">Choose</option>
                    {manual.map((e) => <option key={e.id} value={e.id}>{e.name}{e.kind === 'DEDUCTION' ? ' (deduction)' : ''}</option>)}
                  </select>
                </label>
                <label>Amount<input type="number" min={0.01} step={0.01} value={entry.amount} required onChange={(e) => setEntry({ ...entry, amount: e.target.value })} /></label>
                <label>Currency<select value={entry.currency} onChange={(e) => setEntry({ ...entry, currency: e.target.value })}><option>USD</option><option>CDF</option></select></label>
                <label>Note<input value={entry.note} maxLength={300} onChange={(e) => setEntry({ ...entry, note: e.target.value })} /></label>
              </div>
              {entry.userQuery && !entry.userId && <p className="hint">Pick the employee from the list.</p>}
              <button type="submit" disabled={!entry.userId}>Add for {month}</button>
            </form>
          )}
        </div>
      )}

      <div className="card">
        <h3>Catalogue</h3>
        <p className="hint">Sample treatment and amounts: a DRC payroll specialist must confirm which elements are subject to CNSS and IPR before go-live. Elements "calculated by payroll" are built in; you can only rename them.</p>
        <div className="table-wrap">
          <table>
            <thead><tr><th>Code</th><th>Name</th><th>Type</th><th>Rule</th><th>CNSS</th><th>IPR</th><th>Everyone</th><th>Active</th><th></th></tr></thead>
            <tbody>
              {elements.map((e) => (
                <tr key={e.id} className={e.active ? '' : 'muted'}>
                  <td>{e.code}</td>
                  <td>{e.name}</td>
                  <td>{PAY_KINDS[e.kind]}</td>
                  <td>{describe(e)}{e.prorated ? ', prorated' : ''}{e.iprExemptCapPercent !== null ? `, IPR-free up to ${e.iprExemptCapPercent}% of basic` : ''}</td>
                  <td>{e.kind === 'EARNING' ? (e.subjectToCnss ? 'Yes' : 'No') : ''}</td>
                  <td>{e.kind === 'EARNING' ? (e.taxableIpr ? 'Yes' : 'No') : ''}</td>
                  <td>{e.defaultForAll ? 'Yes' : ''}</td>
                  <td>{e.active ? 'Yes' : 'No'}</td>
                  <td>{manage && <button className="secondary" onClick={() => startEdit(e)}>Edit</button>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {manage && !edit && <button className="secondary" onClick={() => setEdit({ ...blankElement })}>New element</button>}
        {manage && edit && (
          <form onSubmit={saveElement} className="sub-form">
            <h4>{edit.id ? `Edit ${edit.code}` : 'New pay element'}</h4>
            <div className="grid3">
              <label>Code<input value={edit.code} disabled={!!edit.id} required pattern="[A-Za-z0-9_-]{1,32}" onChange={(e) => setEdit({ ...edit, code: e.target.value.toUpperCase() })} /></label>
              <label>Name<input value={edit.name} required maxLength={150} onChange={(e) => setEdit({ ...edit, name: e.target.value })} /></label>
              <label>Order on payslip<input type="number" min={0} max={999} value={edit.displayOrder} onChange={(e) => setEdit({ ...edit, displayOrder: Number(e.target.value) })} /></label>
            </div>
            {!edit.system && (
              <>
                <div className="grid3">
                  <label>Type
                    <select value={edit.kind} onChange={(e) => setEdit({ ...edit, kind: e.target.value })}>
                      <option value="EARNING">Earning</option><option value="DEDUCTION">Deduction</option>
                    </select>
                  </label>
                  <label>Rule
                    <select value={edit.method} onChange={(e) => setEdit({ ...edit, method: e.target.value as PayMethod })}>
                      {(['FIXED', 'PERCENT_OF_BASIC', 'PER_DAY_WORKED', 'PER_CHILD', 'MANUAL'] as PayMethod[]).map((m) => <option key={m} value={m}>{PAY_METHODS[m]}</option>)}
                    </select>
                  </label>
                  {edit.method === 'PERCENT_OF_BASIC' && (
                    <label>Percentage<input type="number" min={0} max={100} step={0.001} value={edit.percent} required onChange={(e) => setEdit({ ...edit, percent: e.target.value })} /></label>
                  )}
                  {needsAmount && (
                    <>
                      <label>Default amount<input type="number" min={0} step={0.01} value={edit.defaultAmount} required onChange={(e) => setEdit({ ...edit, defaultAmount: e.target.value })} /></label>
                      <label>Currency<select value={edit.defaultCurrency} onChange={(e) => setEdit({ ...edit, defaultCurrency: e.target.value })}><option>USD</option><option>CDF</option></select></label>
                    </>
                  )}
                </div>
              </>
            )}
            {edit.kind === 'EARNING' && (
                  <div className="grid3">
                    <label className="check"><input type="checkbox" checked={edit.subjectToCnss} onChange={(e) => setEdit({ ...edit, subjectToCnss: e.target.checked })} /> Subject to CNSS</label>
                    <label className="check"><input type="checkbox" checked={edit.taxableIpr} onChange={(e) => setEdit({ ...edit, taxableIpr: e.target.checked })} /> Taxable for IPR</label>
                    {edit.taxableIpr && (
                      <label>IPR-free share, % of basic (optional)<input type="number" min={0} max={100} step={0.001} value={edit.iprExemptCapPercent} onChange={(e) => setEdit({ ...edit, iprExemptCapPercent: e.target.value })} /></label>
                    )}
                  </div>
            )}
            {!edit.system && (
              <>
                <div className="grid3">
                  <label className="check"><input type="checkbox" checked={edit.prorated} onChange={(e) => setEdit({ ...edit, prorated: e.target.checked })} /> Prorated by hours paid</label>
                  {edit.method !== 'MANUAL' && (
                    <label className="check"><input type="checkbox" checked={edit.defaultForAll} onChange={(e) => setEdit({ ...edit, defaultForAll: e.target.checked })} /> Applies to every employee</label>
                  )}
                  {edit.id && <label className="check"><input type="checkbox" checked={edit.active ?? true} onChange={(e) => setEdit({ ...edit, active: e.target.checked })} /> Active</label>}
                </div>
              </>
            )}
            <div className="row">
              <button type="submit">Save</button>
              <button type="button" className="secondary" onClick={() => setEdit(null)}>Cancel</button>
            </div>
          </form>
        )}
      </div>
    </section>
  )
}
