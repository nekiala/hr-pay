import { useEffect, useState, type FormEvent } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { api, longDay, amount, PAYMENT_METHODS, type Invoice, type InvoicePayment, type Subscription, type SupportGrant, type SupportRequest } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'
import { InvoiceTable } from '../components/Invoices'
import { Button, Form } from '../components/Button'
import { t, locale } from '../i18n'

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
      setError(e instanceof Error ? e.message : t('Could not load the company.'))
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
      setError(err instanceof Error ? err.message : t('Could not save.'))
    }
  }

  const save = (e: FormEvent) => { e.preventDefault(); return change({
    cycle: form.cycle, currency: form.currency, trialEnds: form.trialEnds || null, nextInvoiceOn: form.nextInvoiceOn || null, autoSuspend: form.autoSuspend,
  }, t('The subscription is saved.')) }

  const issue = async () => {
    setError(null); setSaved(null)
    try {
      const i = await api<Invoice>(`/api/platform/companies/${id}/invoices`, { method: 'POST' })
      setSaved(t('Invoice {a} is issued and sent to the company\'s administrators.', { a: i.number }))
      await load()
    } catch (err) {
      setError(err instanceof Error ? err.message : t('Could not issue the invoice.'))
    }
  }

  const visit = async () => {
    setError(null)
    try {
      await api(`/api/platform/companies/${id}/support`, { method: 'POST' })
      await refresh()
      navigate('/')
    } catch (err) {
      setError(err instanceof Error ? err.message : t('Could not open the company.'))
    }
  }

  if (!d) return <section><ErrorText error={error} /></section>
  const s = d.subscription
  const grant = d.support.find((g) => g.active)
  const u = d.usage
  return (
    <section>
      <p className="muted small"><Link to="/platform/companies">{t('Companies')}</Link></p>
      <div className="row between">
        <h2>{d.name}</h2>
        {d.status === 'SUSPENDED'
          ? <span className="badge off">{t('Read-only')}{d.suspendedReason === 'UNPAID' ? t(': unpaid invoice') : t(': suspended')}</span>
          : <span className="badge ok">{t('Active')}</span>}
      </div>
      <ErrorText error={error} />
      {saved && <p className="success">{saved}</p>}
      <div className="cards2">
        <div className="card">
          <h3>{t('Use')}</h3>
          <dl className="facts">
            <dt>{t('Plan')}</dt><dd>{d.planName}</dd>
            <dt>{t('Active employees')}</dt><dd>{u.activeEmployees}</dd>
            <dt>{t('Signed in, last 30 days')}</dt><dd>{u.signedIn30Days}</dd>
            <dt>{t('Last sign-in')}</dt><dd>{u.lastSignIn ? new Date(u.lastSignIn).toLocaleString(locale()) : t('Never')}</dd>
            <dt>{t('Check-ins, last 30 days')}</dt><dd>{u.checkIns30Days}</dd>
            <dt>{t('Last payroll')}</dt><dd>{u.lastPayrollMonth ? `${new Date(u.lastPayrollMonth + 'T00:00:00').toLocaleDateString(locale(), { month: 'long', year: 'numeric' })} (${u.lastPayrollStatus?.toLowerCase()})` : t('None yet')}</dd>
          </dl>
        </div>
        <div className="card">
          <div className="row between">
            <h3>{t('Support access')}</h3>
            {grant ? <span className="badge warn">{t('Open until')}{' '}{longDay(grant.expiresAt)}</span> : <span className="badge">{t('Closed')}</span>}
          </div>
          <p className="hint">{t('The company\'s administrator opens it from their Billing page. You then see the company as they do, read-only; every page you open is shown to them.')}</p>
          <Button disabled={!grant} onClick={visit}>{t('Open the company as support')}</Button>
          {d.supportRequests.length > 0 && <p className="muted small">{d.supportRequests.length}{' '}{t('recent request(s), the last on')}{' '}{new Date(d.supportRequests[0].at).toLocaleString(locale())}.</p>}
        </div>
      </div>

      {s ? (
        <div className="card">
          <div className="row between">
            <h3>{t('Subscription')}</h3>
            <div className="row">
              {s.status !== 'CANCELLED' && <Button className="secondary" onClick={issue}>{t('Issue the next invoice now')}</Button>}
              {s.status === 'CANCELLED'
                ? <Button className="secondary" onClick={() => change({ cancelled: false, nextInvoiceOn: new Date().toISOString().slice(0, 10) }, t('The subscription is resumed.'))}>{t('Resume')}</Button>
                : <Button className="secondary" onClick={() => change({ cancelled: true }, t('The subscription is cancelled: no more invoices.'))}>{t('Cancel subscription')}</Button>}
            </div>
          </div>
          <p className="hint">{s.status === 'TRIAL' ? t('Free trial until {a}; the first invoice is issued on its last day.', { a: longDay(s.trialEnds) }) : s.status === 'CANCELLED' ? t('Cancelled: no more invoices.') : t('Invoices are issued in advance on the next invoice date.')}</p>
          <Form onSubmit={save}>
            <div className="grid3">
              <label>{t('Invoiced')}<select value={form.cycle} onChange={(e) => setForm({ ...form, cycle: e.target.value })}>
                  <option value="MONTHLY">{t('Every month')}</option>
                  <option value="YEARLY">{t('Every year')}</option>
                </select>
              </label>
              <label>{t('Currency')}<select value={form.currency} onChange={(e) => setForm({ ...form, currency: e.target.value })}>
                  <option value="USD">{t('USD')}</option>
                  <option value="CDF">{t('CDF at the BCC rate')}</option>
                </select>
              </label>
              {s.status === 'TRIAL' && <label>{t('Trial ends')}<input type="date" value={form.trialEnds} onChange={(e) => setForm({ ...form, trialEnds: e.target.value, nextInvoiceOn: e.target.value })} /></label>}
              {s.status !== 'CANCELLED' && <label>{t('Next invoice')}<input type="date" value={form.nextInvoiceOn} onChange={(e) => setForm({ ...form, nextInvoiceOn: e.target.value })} /></label>}
            </div>
            <label className="check"><input type="checkbox" checked={form.autoSuspend} onChange={(e) => setForm({ ...form, autoSuspend: e.target.checked })} />{' '}{t('Make the company read-only when an invoice is unpaid past the grace days')}</label>
            <Button type="submit">{t('Save')}</Button>
          </Form>
        </div>
      ) : <div className="card"><p className="muted">{t('This company is not billed.')}</p></div>}

      <div className="card">
        <h3>{t('Invoices')}</h3>
        <InvoiceTable invoices={d.invoices} manage pdf={(i) => `/api/platform/invoices/${i.id}.pdf`} onChange={load} />
        {d.payments.length > 0 && (
          <>
            <h4>{t('Payments')}</h4>
            <div className="table-wrap">
              <table>
                <thead><tr><th>{t('Date')}</th><th>{t('Invoice')}</th><th>{t('Paid by')}</th><th>{t('Reference')}</th><th className="num">{t('Amount')}</th><th>{t('Recorded by')}</th></tr></thead>
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
