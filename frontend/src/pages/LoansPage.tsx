import { useEffect, useState, type FormEvent } from 'react'
import { ApiError, api, money, type Loan, type Page, type User } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'
import { useAsk } from '../components/Dialogs'
import { Modal } from '../components/Modal'
import { Button, Form } from '../components/Button'

const thisMonth = () => new Date().toISOString().slice(0, 7)
const STATUS = { ACTIVE: 'Being repaid', REPAID: 'Repaid', CANCELLED: 'Cancelled' }

export default function LoansPage() {
  const [granting, setGranting] = useState(false)
  const ask = useAsk()
  const { can } = useAuth()
  const manage = can('loan.manage')
  const [status, setStatus] = useState('ACTIVE')
  const [loans, setLoans] = useState<Loan[]>([])
  const [form, setForm] = useState({ userQuery: '', userId: '', kind: 'LOAN', principal: '', currency: 'USD', installment: '', startMonth: thisMonth(), grantedOn: new Date().toISOString().slice(0, 10), note: '' })
  const [people, setPeople] = useState<User[]>([])
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)

  const load = () => api<Loan[]>(`/api/loans${status ? `?status=${status}` : ''}`).then(setLoans).catch((e: Error) => setError(e.message))
  useEffect(() => { void load() }, [status]) // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => {
    if (!manage || form.userQuery.trim().length < 2) return
    const t = setTimeout(() => {
      api<Page<User>>(`/api/users?${new URLSearchParams({ q: form.userQuery.trim(), size: '10' })}`).then((p) => setPeople(p.items)).catch(() => undefined)
    }, 250)
    return () => clearTimeout(t)
  }, [form.userQuery, manage])

  const run = async (action: () => Promise<unknown>, done: string) => {
    setError(null)
    setMessage(null)
    try {
      await action()
      setMessage(done)
      await load()
      return true
    } catch (e) {
      setError(e instanceof ApiError ? [e.message, ...Object.values(e.fieldErrors)].join(' ') : 'Something went wrong.')
      return false
    }
  }

  const grant = (e: FormEvent) => {
    e.preventDefault()
    return run(async () => {
      await api('/api/loans', {
        method: 'POST',
        body: {
          userId: Number(form.userId), kind: form.kind, principal: Number(form.principal), currency: form.currency,
          installment: form.kind === 'ADVANCE' ? null : Number(form.installment), startMonth: form.startMonth, grantedOn: form.grantedOn, note: form.note || null,
        },
      })
      setForm({ ...form, userQuery: '', userId: '', principal: '', installment: '', note: '' })
      setPeople([])
    }, 'Saved. Payroll takes the installment from the first month.')
  }

  const label = (p: User) => `${p.firstName} ${p.lastName} (${p.registrationNo ?? p.email})`
  const months = form.kind === 'LOAN' && Number(form.principal) > 0 && Number(form.installment) > 0 ? Math.ceil(Number(form.principal) / Number(form.installment)) : null

  return (
    <section>
      <div className="row between">
        <h2>Loans and advances</h2>
        {manage && <Button onClick={() => setGranting(true)}>New loan or advance</Button>}
      </div>
      <ErrorText error={error} />
      {message && <p className="success">{message}</p>}

      <div className="card">
        <label className="month-pick">Show
          <select value={status} onChange={(e) => setStatus(e.target.value)}>
            <option value="ACTIVE">Being repaid</option><option value="REPAID">Repaid</option><option value="CANCELLED">Cancelled</option><option value="">All</option>
          </select>
        </label>
        {loans.length === 0 ? <p className="muted">None.</p> : (
          <div className="table-wrap">
            <table>
              <thead><tr><th>#</th><th>Employee</th><th>Type</th><th>Amount</th><th>Per month</th><th>From</th><th>Repaid</th><th>Left</th><th>Status</th><th></th></tr></thead>
              <tbody>
                {loans.map((l) => (
                  <tr key={l.id}>
                    <td>{l.id}</td>
                    <td>{l.userName}{l.note && <div className="hint">{l.note}</div>}</td>
                    <td>{l.kind === 'LOAN' ? 'Loan' : 'Advance'}</td>
                    <td>{money(l.principal, l.currency)}</td>
                    <td>{money(l.installment, l.currency)}</td>
                    <td>{l.startMonth.slice(0, 7)}</td>
                    <td>{money(l.repaid, l.currency)}</td>
                    <td><strong>{money(l.balance, l.currency)}</strong></td>
                    <td>{STATUS[l.status]}</td>
                    <td className="nowrap">
                      {manage && l.status === 'ACTIVE' && (
                        <>
                          <Button className="secondary" onClick={() => ask({
                            title: `Pause loan #${l.id} for a month`, confirmLabel: 'Pause',
                            fields: [{ label: 'Skip the installment of', type: 'month', required: true, defaultValue: thisMonth() }, { label: 'Reason', required: true }],
                            onConfirm: ([month, reason]) => run(() => api(`/api/loans/${l.id}/pauses`, { method: 'POST', body: { month, reason } }), `Installment skipped for ${month}.`),
                          })}>Pause a month</Button>{' '}
                          <Button className="secondary" onClick={() => ask({
                            title: `Cancel loan #${l.id}?`, message: `The remaining ${money(l.balance, l.currency)} will not be deducted.`, confirmLabel: 'Cancel the loan', danger: true,
                            fields: [{ label: 'Reason', required: true, multiline: true }],
                            onConfirm: ([reason]) => run(() => api(`/api/loans/${l.id}/cancel`, { method: 'POST', body: { reason } }), 'Cancelled.'),
                          })}>Cancel</Button>
                        </>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <p className="hint">Each month the installments together take at most the share of net pay set under Rates; the rest waits for the next month.</p>
      </div>

      {manage && granting && (
        <Modal title="New loan or advance" size="lg" onClose={() => setGranting(false)}>
          <Form onSubmit={async (e) => { if (await grant(e)) setGranting(false) }}>
            <div className="grid3">
              <label>Employee
                <input list="loan-people" placeholder="Type a name or number" value={form.userQuery} required
                  onChange={(e) => {
                    const match = people.find((p) => label(p) === e.target.value)
                    setForm({ ...form, userQuery: e.target.value, userId: match ? String(match.id) : '' })
                  }} />
                <datalist id="loan-people">{people.map((p) => <option key={p.id} value={label(p)} />)}</datalist>
              </label>
              <label>Type
                <select value={form.kind} onChange={(e) => setForm({ ...form, kind: e.target.value })}>
                  <option value="LOAN">Loan, repaid in installments</option><option value="ADVANCE">Salary advance, taken back in one month</option>
                </select>
              </label>
              <label>Granted on<input type="date" value={form.grantedOn} required onChange={(e) => setForm({ ...form, grantedOn: e.target.value })} /></label>
              <label>Amount<input type="number" min={0.01} step={0.01} value={form.principal} required onChange={(e) => setForm({ ...form, principal: e.target.value })} /></label>
              <label>Currency<select value={form.currency} onChange={(e) => setForm({ ...form, currency: e.target.value })}><option>USD</option><option>CDF</option></select></label>
              {form.kind === 'LOAN' && (
                <label>Per month<input type="number" min={0.01} step={0.01} value={form.installment} required onChange={(e) => setForm({ ...form, installment: e.target.value })} /></label>
              )}
              <label>First month deducted<input type="month" value={form.startMonth} required onChange={(e) => setForm({ ...form, startMonth: e.target.value })} /></label>
              <label>Note<input value={form.note} maxLength={300} onChange={(e) => setForm({ ...form, note: e.target.value })} /></label>
            </div>
            {months && <p className="hint">About {months} month(s) to repay.</p>}
            <ErrorText error={error} />
            <div className="modal-foot"><Button className="secondary" onClick={() => setGranting(false)}>Cancel</Button><Button type="submit" disabled={!form.userId}>Save</Button></div>
          </Form>
        </Modal>
      )}
    </section>
  )
}
