import { useEffect, useState, type FormEvent } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { api, longDay, amount, PAYMENT_METHODS, type Invoice, type InvoicePayment, type Subscription, type SupportGrant, type SupportRequest } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'
import { InvoiceTable } from '../components/Invoices'
import { Button, Form } from '../components/Button'

type Detail = {
  id: number
  name: string
  status: 'ACTIVE' | 'SUSPENDED'
  suspendedReason: 'UNPAID' | 'MANUAL' | null
  planName: string
  usage: { activeEmployees: number; signedIn30Days: number; lastSignIn: string | null; checkIns30Days: number; lastPayrollMonth: string | null; lastPayrollStatus: string | null }
  subscription: Subscription | null
  invoices: Invoice[]
  payments: InvoicePayment[]
  support: SupportGrant[]
  supportRequests: SupportRequest[]
}

/** One company for the provider: how much it uses the app, its subscription and invoices, and support access. */
export default function PlatformCompanyPage() {
  const { id } = useParams()
  const navigate = useNavigate()
  const { refresh } = useAuth()
  const [d, setD] = useState<Detail | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [form, setForm] = useState({ cycle: 'MONTHLY', currency: 'USD', trialEnds: '', nextInvoiceOn: '', autoSuspend: true })
  const [saved, setSaved] = useState<string | null>(null)

  const load = async () => {
    try {
      const v = await api<Detail>(`/api/platform/companies/${id}/billing`)
      setD(v)
      const s = v.subscription
      if (s) setForm({ cycle: s.cycle, currency: s.currency, trialEnds: s.trialEnds ?? '', nextInvoiceOn: s.nextInvoiceOn ?? '', autoSuspend: s.autoSuspend })
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not load the company.')
    }
  }
  useEffect(() => { void load() }, [id])

  const change = async (body: object, done: string) => {
    setError(null); setSaved(null)
    try {
      await api(`/api/platform/companies/${id}/subscription`, { method: 'PUT', body })
      setSaved(done)
      await load()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not save.')
    }
  }

  const save = (e: FormEvent) => { e.preventDefault(); return change({
    cycle: form.cycle, currency: form.currency, trialEnds: form.trialEnds || null, nextInvoiceOn: form.nextInvoiceOn || null, autoSuspend: form.autoSuspend,
  }, 'The subscription is saved.') }

  const issue = async () => {
    setError(null); setSaved(null)
    try {
      const i = await api<Invoice>(`/api/platform/companies/${id}/invoices`, { method: 'POST' })
      setSaved(`Invoice ${i.number} is issued and sent to the company's administrators.`)
      await load()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not issue the invoice.')
    }
  }

  const visit = async () => {
    setError(null)
    try {
      await api(`/api/platform/companies/${id}/support`, { method: 'POST' })
      await refresh()
      navigate('/')
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not open the company.')
    }
  }

  if (!d) return <section><ErrorText error={error} /></section>
  const s = d.subscription
  const grant = d.support.find((g) => g.active)
  const u = d.usage
  return (
    <section>
      <p className="muted small"><Link to="/platform/companies">Companies</Link></p>
      <div className="row between">
        <h2>{d.name}</h2>
        {d.status === 'SUSPENDED'
          ? <span className="badge off">Read-only{d.suspendedReason === 'UNPAID' ? ': unpaid invoice' : ': suspended'}</span>
          : <span className="badge ok">Active</span>}
      </div>
      <ErrorText error={error} />
      {saved && <p className="success">{saved}</p>}
      <div className="cards2">
        <div className="card">
          <h3>Use</h3>
          <dl className="facts">
            <dt>Plan</dt><dd>{d.planName}</dd>
            <dt>Active employees</dt><dd>{u.activeEmployees}</dd>
            <dt>Signed in, last 30 days</dt><dd>{u.signedIn30Days}</dd>
            <dt>Last sign-in</dt><dd>{u.lastSignIn ? new Date(u.lastSignIn).toLocaleString() : 'Never'}</dd>
            <dt>Check-ins, last 30 days</dt><dd>{u.checkIns30Days}</dd>
            <dt>Last payroll</dt><dd>{u.lastPayrollMonth ? `${new Date(u.lastPayrollMonth + 'T00:00:00').toLocaleDateString(undefined, { month: 'long', year: 'numeric' })} (${u.lastPayrollStatus?.toLowerCase()})` : 'None yet'}</dd>
          </dl>
        </div>
        <div className="card">
          <div className="row between">
            <h3>Support access</h3>
            {grant ? <span className="badge warn">Open until {longDay(grant.expiresAt)}</span> : <span className="badge">Closed</span>}
          </div>
          <p className="hint">The company's administrator opens it from their Billing page. You then see the company as they do, read-only; every page you open is shown to them.</p>
          <Button disabled={!grant} onClick={visit}>Open the company as support</Button>
          {d.supportRequests.length > 0 && <p className="muted small">{d.supportRequests.length} recent request(s), the last on {new Date(d.supportRequests[0].at).toLocaleString()}.</p>}
        </div>
      </div>

      {s ? (
        <div className="card">
          <div className="row between">
            <h3>Subscription</h3>
            <div className="row">
              {s.status !== 'CANCELLED' && <Button className="secondary" onClick={issue}>Issue the next invoice now</Button>}
              {s.status === 'CANCELLED'
                ? <Button className="secondary" onClick={() => change({ cancelled: false, nextInvoiceOn: new Date().toISOString().slice(0, 10) }, 'The subscription is resumed.')}>Resume</Button>
                : <Button className="secondary" onClick={() => change({ cancelled: true }, 'The subscription is cancelled: no more invoices.')}>Cancel subscription</Button>}
            </div>
          </div>
          <p className="hint">{s.status === 'TRIAL' ? `Free trial until ${longDay(s.trialEnds)}; the first invoice is issued on its last day.` : s.status === 'CANCELLED' ? 'Cancelled: no more invoices.' : 'Invoices are issued in advance on the next invoice date.'}</p>
          <Form onSubmit={save}>
            <div className="grid3">
              <label>Invoiced
                <select value={form.cycle} onChange={(e) => setForm({ ...form, cycle: e.target.value })}>
                  <option value="MONTHLY">Every month</option>
                  <option value="YEARLY">Every year</option>
                </select>
              </label>
              <label>Currency
                <select value={form.currency} onChange={(e) => setForm({ ...form, currency: e.target.value })}>
                  <option value="USD">USD</option>
                  <option value="CDF">CDF at the BCC rate</option>
                </select>
              </label>
              {s.status === 'TRIAL' && <label>Trial ends<input type="date" value={form.trialEnds} onChange={(e) => setForm({ ...form, trialEnds: e.target.value, nextInvoiceOn: e.target.value })} /></label>}
              {s.status !== 'CANCELLED' && <label>Next invoice<input type="date" value={form.nextInvoiceOn} onChange={(e) => setForm({ ...form, nextInvoiceOn: e.target.value })} /></label>}
            </div>
            <label className="check"><input type="checkbox" checked={form.autoSuspend} onChange={(e) => setForm({ ...form, autoSuspend: e.target.checked })} /> Make the company read-only when an invoice is unpaid past the grace days</label>
            <Button type="submit">Save</Button>
          </Form>
        </div>
      ) : <div className="card"><p className="muted">This company is not billed.</p></div>}

      <div className="card">
        <h3>Invoices</h3>
        <InvoiceTable invoices={d.invoices} manage pdf={(i) => `/api/platform/invoices/${i.id}.pdf`} onChange={load} />
        {d.payments.length > 0 && (
          <>
            <h4>Payments</h4>
            <div className="table-wrap">
              <table>
                <thead><tr><th>Date</th><th>Invoice</th><th>Paid by</th><th>Reference</th><th className="num">Amount</th><th>Recorded by</th></tr></thead>
                <tbody>
                  {d.payments.map((p) => (
                    <tr key={p.id}><td>{longDay(p.paidOn)}</td><td className="mono">{p.invoiceNumber}</td><td>{PAYMENT_METHODS[p.method]}</td>
                      <td className="mono">{p.reference}</td><td className="num">{amount(p.amount)} {p.currency}</td><td className="small">{p.recordedBy}</td></tr>
                  ))}
                </tbody>
              </table>
            </div>
          </>
        )}
      </div>
    </section>
  )
}
