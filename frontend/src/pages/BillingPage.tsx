import { useEffect, useState, type FormEvent } from 'react'
import { api, longDay, amount, PAYMENT_METHODS, type Invoice, type InvoicePayment, type Subscription, type SupportGrant, type SupportRequest } from '../api'
import { useAuth } from '../auth'
import { useAsk } from '../components/Dialogs'
import { ErrorText } from '../components/ErrorText'
import { InvoiceTable } from '../components/Invoices'
import { Button, Form } from '../components/Button'
import { t, locale } from '../i18n'

type View = {
  billed: boolean
  mode: 'SAAS' | 'ON_PREMISE'
  companyName: string
  planName: string
  subscription: Subscription | null
  price: { monthlyPrice: number; yearlyPrice: number } | null
  howToPay: { bankDetails: string | null; mobileMoney: string | null; taxLabel: string; taxRate: number; paymentDays: number; suspendAfterDays: number }
  invoices: Invoice[]
  payments: InvoicePayment[]
  support: SupportGrant[]
  supportRequests: SupportRequest[]
}

const STATUS = { TRIAL: [t('Free trial'), 'warn'], ACTIVE: [t('Active'), 'ok'], CANCELLED: [t('Cancelled'), 'off'] } as const

/** The company's subscription, invoices and payments, and the provider's support access it controls. */
export default function BillingPage() {
  const { me, refresh } = useAuth()
  const ask = useAsk()
  const [view, setView] = useState<View | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [cycle, setCycle] = useState<'MONTHLY' | 'YEARLY'>('MONTHLY')
  const [currency, setCurrency] = useState<'USD' | 'CDF'>('USD')
  const [message, setMessage] = useState<string | null>(null)

  const load = async () => {
    try {
      const v = await api<View>('/api/billing')
      setView(v)
      if (v.subscription) { setCycle(v.subscription.cycle); setCurrency(v.subscription.currency) }
    } catch (e) {
      setError(e instanceof Error ? e.message : t('Could not load the billing.'))
    }
  }
  useEffect(() => { void load() }, [])

  const choose = async (e: FormEvent) => {
    e.preventDefault()
    setError(null); setMessage(null)
    try {
      await api('/api/billing/subscription', { method: 'PUT', body: { cycle, currency } })
      setMessage(t('Saved: the next invoice follows this choice.'))
      await load()
    } catch (err) {
      setError(err instanceof Error ? err.message : t('Could not save.'))
    }
  }

  const s = view?.subscription
  // An invoice issued in advance does not end the trial early.
  const status = s && s.status !== 'CANCELLED' && s.trialEnds && s.trialEnds >= new Date().toISOString().slice(0, 10) ? 'TRIAL' : s?.status
  const active = view?.support.find((g) => g.active)
  const price = view?.price
  return (
    <section>
      <h2>{t('Billing')}</h2>
      <ErrorText error={error} />
      {message && <p className="success">{message}</p>}
      {me?.company?.readOnly && <p className="error">{me.company.readOnly}</p>}
      {view && !view.billed && (
        <p className="muted">{view.mode === 'ON_PREMISE'
          ? t('This installation runs under a licence from your provider: there are no invoices here.')
          : t('Your company is not billed on this installation.')}</p>
      )}
      {view && s && (
        <div className="cards2">
          <div className="card">
            <div className="row between">
              <h3>{view.planName}</h3>
              <span className={`badge ${STATUS[status ?? s.status][1]}`}>{STATUS[status ?? s.status][0]}</span>
            </div>
            <dl className="facts">
              {status === 'TRIAL' && <><dt>{t('Free trial until')}</dt><dd>{longDay(s.trialEnds)}</dd></>}
              <dt>{t('Next invoice')}</dt><dd>{s.nextInvoiceOn ? longDay(s.nextInvoiceOn) : t('None')}</dd>
              {price && <><dt>{t('Price')}</dt><dd>{t('USD')}{' '}{amount(price.monthlyPrice)}{' '}{t('a month, or USD')}{' '}{amount(price.yearlyPrice)}{' '}{t('a year, before')}{' '}{view.howToPay.taxLabel} ({view.howToPay.taxRate}%)</dd></>}
            </dl>
            {s.status !== 'CANCELLED' && (
              <Form onSubmit={choose}>
                <div className="row">
                  <label>{t('Invoiced')}<select value={cycle} onChange={(e) => setCycle(e.target.value as 'MONTHLY' | 'YEARLY')}>
                      <option value="MONTHLY">{t('Every month')}</option>
                      <option value="YEARLY">{t('Every year')}</option>
                    </select>
                  </label>
                  <label>{t('In')}<select value={currency} onChange={(e) => setCurrency(e.target.value as 'USD' | 'CDF')}>
                      <option value="USD">{t('US dollars')}</option>
                      <option value="CDF">{t('Congolese francs (BCC rate of the day)')}</option>
                    </select>
                  </label>
                  <Button type="submit" className="secondary" disabled={cycle === s.cycle && currency === s.currency}>{t('Save')}</Button>
                </div>
                <p className="hint">{t('Applies from the next invoice. The price in US dollars stays the same; in francs it is converted at the BCC rate of the invoice\'s day.')}</p>
              </Form>
            )}
          </div>
          <div className="card">
            <h3>{t('How to pay')}</h3>
            <p className="hint">{t('Give the invoice number as the reference. Each invoice is due')}{' '}{view.howToPay.paymentDays}{' '}{t('days after it is issued.')}{' '}{view.howToPay.suspendAfterDays}{' '}{t('days after that, an unpaid invoice makes HR Pay read-only until it is paid: nothing is lost, and everyone can still read and export.')}</p>
            {view.howToPay.bankDetails && <><h4>{t('Bank transfer')}</h4><p className="pre">{view.howToPay.bankDetails}</p></>}
            {view.howToPay.mobileMoney && <><h4>{t('Mobile money')}</h4><p className="pre">{view.howToPay.mobileMoney}</p></>}
            {!view.howToPay.bankDetails && !view.howToPay.mobileMoney && <p className="muted">{t('Your provider has not given payment details yet.')}</p>}
          </div>
        </div>
      )}
      {view?.billed && (
        <div className="card">
          <h3>{t('Invoices')}</h3>
          <InvoiceTable invoices={view.invoices} pdf={(i) => `/api/billing/invoices/${i.id}.pdf`} />
          {view.payments.length > 0 && (
            <>
              <h4>{t('Payments received')}</h4>
              <div className="table-wrap">
                <table>
                  <thead><tr><th>{t('Date')}</th><th>{t('Invoice')}</th><th>{t('Paid by')}</th><th>{t('Reference')}</th><th className="num">{t('Amount')}</th></tr></thead>
                  <tbody>
                    {view.payments.map((p) => (
                      <tr key={p.id}><td>{longDay(p.paidOn)}</td><td className="mono">{p.invoiceNumber}</td><td>{PAYMENT_METHODS[p.method]}</td>
                        <td className="mono">{p.reference}</td><td className="num">{amount(p.amount)} {p.currency}</td></tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </>
          )}
        </div>
      )}
      {view && (
        <div className="card">
          <div className="row between">
            <h3>{t('Support access')}</h3>
            {active ? <span className="badge warn">{t('Open until')}{' '}{longDay(active.expiresAt)}</span> : <span className="badge">{t('Closed')}</span>}
          </div>
          <p className="hint">{t('When you need help, let your provider\'s support team look at your company\'s data, read-only, for a few days. They cannot change anything. Every page they open is listed here, and you can close the access at any time.')}</p>
          {active ? (
            <Button className="secondary" onClick={() => ask({
              title: t('Close support access?'), message: t('The provider is signed out of your company at once.'), confirmLabel: t('Close access'), danger: true,
              onConfirm: async () => { await api(`/api/billing/support/${active.id}`, { method: 'DELETE' }); await load() },
            })}>{t('Close access')}</Button>
          ) : (
            <Button onClick={() => ask({
              title: t('Open support access'), confirmLabel: t('Grant access'),
              message: t('The provider can look at everything in your company, read-only, until the access ends or you close it.'),
              fields: [{ label: t('Days (1 to 14)'), type: 'number', defaultValue: '3', required: true }, { label: t('What it is about'), placeholder: t('Optional') }],
              onConfirm: async ([days, reason]) => { await api('/api/billing/support', { method: 'POST', body: { days: Number(days), reason } }); await load(); await refresh() },
            })}>{t('Open support access')}</Button>
          )}
          {view.supportRequests.length > 0 && (
            <>
              <h4>{t('What support opened')}</h4>
              <div className="table-wrap">
                <table>
                  <thead><tr><th>{t('When')}</th><th>{t('Who')}</th><th>{t('Page or action')}</th></tr></thead>
                  <tbody>
                    {view.supportRequests.map((r, n) => (
                      <tr key={n}><td>{new Date(r.at).toLocaleString(locale())}</td><td>{r.visitor}</td><td className="mono small">{r.method} {r.path}</td></tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </>
          )}
        </div>
      )}
    </section>
  )
}
