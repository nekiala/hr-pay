import { useEffect, useState, type FormEvent } from 'react'
import { api, type BillingSettings, type Invoice, type PlanPrice } from '../api'
import { ErrorText } from '../components/ErrorText'
import { InvoiceTable } from '../components/Invoices'
import { Button, Form } from '../components/Button'

type View = { mode: 'SAAS' | 'ON_PREMISE'; settings: BillingSettings; prices: PlanPrice[] }

const FILTERS = [['OVERDUE', 'Overdue'], ['OPEN', 'To pay'], ['PAID', 'Paid'], ['VOID', 'Cancelled'], ['', 'All']] as const

/** The provider's invoices across companies, prices per plan, and billing rules. */
export default function PlatformBillingPage() {
  const [view, setView] = useState<View | null>(null)
  const [settings, setSettings] = useState<BillingSettings | null>(null)
  const [prices, setPrices] = useState<PlanPrice[]>([])
  const [invoices, setInvoices] = useState<Invoice[]>([])
  const [filter, setFilter] = useState<string>('OPEN')
  const [error, setError] = useState<string | null>(null)
  const [saved, setSaved] = useState<string | null>(null)

  const loadInvoices = () => api<Invoice[]>(`/api/platform/invoices${filter ? `?status=${filter}` : ''}`).then(setInvoices)
    .catch((e: Error) => setError(e.message))
  useEffect(() => {
    api<View>('/api/platform/billing').then((v) => { setView(v); setSettings(v.settings); setPrices(v.prices) }).catch((e: Error) => setError(e.message))
  }, [])
  useEffect(() => { void loadInvoices() }, [filter])

  const saveSettings = async (e: FormEvent) => {
    e.preventDefault()
    setError(null); setSaved(null)
    try {
      const v = await api<View>('/api/platform/billing', { method: 'PUT', body: settings })
      setSettings(v.settings)
      setSaved('The billing rules are saved.')
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not save.')
    }
  }

  const savePrices = async (e: FormEvent) => {
    e.preventDefault()
    setError(null); setSaved(null)
    try {
      for (const p of prices) {
        await api(`/api/platform/plans/${p.code}/price`, { method: 'PUT', body: { monthlyPrice: p.monthlyPrice, yearlyPrice: p.yearlyPrice } })
      }
      setSaved('The prices are saved. They apply from each company\'s next invoice.')
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not save the prices.')
    }
  }

  const set = <K extends keyof BillingSettings>(k: K, v: BillingSettings[K]) => settings && setSettings({ ...settings, [k]: v })
  const price = (code: string, k: 'monthlyPrice' | 'yearlyPrice', v: string) =>
    setPrices(prices.map((p) => p.code === code ? { ...p, [k]: Number(v) } : p))

  return (
    <section>
      <h2>Billing</h2>
      <ErrorText error={error} />
      {saved && <p className="success">{saved}</p>}
      {view?.mode === 'ON_PREMISE' && <p className="notice">This installation runs on premise: its use is covered by the licence, and no invoice is issued here.</p>}

      <div className="card">
        <div className="row between">
          <h3>Invoices</h3>
          <div className="segmented" role="group" aria-label="Show">
            {FILTERS.map(([code, label]) => (
              <button key={code} type="button" className={filter === code ? 'on' : ''} onClick={() => setFilter(code)}>{label}</button>
            ))}
          </div>
        </div>
        <InvoiceTable invoices={invoices} manage showCompany pdf={(i) => `/api/platform/invoices/${i.id}.pdf`} onChange={loadInvoices} />
      </div>

      <div className="cards2">
        <div className="card">
          <h3>Prices</h3>
          <p className="hint">In US dollars, before tax, for all the plan's modules. A company invoiced in francs pays the same at the BCC rate of the invoice's day.</p>
          <Form onSubmit={savePrices}>
            <div className="table-wrap">
              <table>
                <thead><tr><th>Plan</th><th className="num">A month</th><th className="num">A year</th></tr></thead>
                <tbody>
                  {prices.map((p) => (
                    <tr key={p.code}>
                      <td>{p.name}</td>
                      <td className="num"><input aria-label={`${p.name}, a month`} className="num-input" type="number" min="0" step="0.01" value={p.monthlyPrice} onChange={(e) => price(p.code, 'monthlyPrice', e.target.value)} /></td>
                      <td className="num"><input aria-label={`${p.name}, a year`} className="num-input" type="number" min="0" step="0.01" value={p.yearlyPrice} onChange={(e) => price(p.code, 'yearlyPrice', e.target.value)} /></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Button type="submit">Save prices</Button>
          </Form>
        </div>

        {settings && (
          <div className="card">
            <h3>Rules and payment details</h3>
            <Form onSubmit={saveSettings}>
              <div className="grid3">
                <label>Free trial (days)<input type="number" min="0" max="365" required value={settings.trialDays} onChange={(e) => set('trialDays', Number(e.target.value))} /></label>
                <label>To pay within (days)<input type="number" min="0" max="120" required value={settings.paymentDays} onChange={(e) => set('paymentDays', Number(e.target.value))} /></label>
                <label>Read-only after (days late)<input type="number" min="0" max="365" required value={settings.suspendAfterDays} onChange={(e) => set('suspendAfterDays', Number(e.target.value))} /></label>
                <label>Tax name<input required maxLength={20} value={settings.taxLabel} onChange={(e) => set('taxLabel', e.target.value)} /></label>
                <label>Tax rate (%)<input type="number" min="0" max="99.99" step="0.01" required value={settings.taxRate} onChange={(e) => set('taxRate', Number(e.target.value))} /></label>
                <label>Invoice prefix<input required maxLength={10} value={settings.invoicePrefix} onChange={(e) => set('invoicePrefix', e.target.value)} /></label>
              </div>
              <label>Bank transfer details<textarea rows={3} value={settings.bankDetails ?? ''} onChange={(e) => set('bankDetails', e.target.value)} placeholder="Bank, account holder, account number, SWIFT" /></label>
              <label>Mobile money numbers<textarea rows={3} value={settings.mobileMoney ?? ''} onChange={(e) => set('mobileMoney', e.target.value)} placeholder="M-Pesa: +243 …&#10;Orange Money: +243 …&#10;Airtel Money: +243 …" /></label>
              <label>Note at the foot of invoices<textarea rows={2} value={settings.invoiceNote ?? ''} onChange={(e) => set('invoiceNote', e.target.value)} /></label>
              <p className="hint">The invoices carry your company's name, logo and legal details from the Company page.</p>
              <Button type="submit">Save</Button>
            </Form>
          </div>
        )}
      </div>
    </section>
  )
}
