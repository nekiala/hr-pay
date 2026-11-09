import { useEffect, useState } from 'react'
import { ApiError, api, longDay, money } from '../api'
import { Button, Form } from './Button'
import { useAsk } from './Dialogs'
import { ErrorText } from './ErrorText'
import { t } from '../i18n'

type Terms = { commissionPercent: number; discountPercent: number }
type Commission = { id: number; firmName: string; clientName: string; invoiceNumber: string; amount: number; currency: string; createdAt: string; paidOutAt: string | null; paidOutRef: string | null }

const fail = (e: unknown) => e instanceof ApiError ? [e.message, ...Object.values(e.fieldErrors)].join(' ') : t('Something went wrong.')

/** The provider's terms for accounting firms, and the commissions to pay them. */
export function FirmTermsCard() {
  const ask = useAsk()
  const [terms, setTerms] = useState<Terms | null>(null)
  const [rows, setRows] = useState<Commission[]>([])
  const [error, setError] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)
  const load = () => {
    api<Terms>('/api/platform/firms/terms').then(setTerms).catch((e) => setError(fail(e)))
    api<Commission[]>('/api/platform/firms/commissions').then(setRows).catch((e) => setError(fail(e)))
  }
  useEffect(load, [])

  return (
    <div className="card">
      <h3>{t('Accounting firms')}</h3>
      <ErrorText error={error} />
      {terms && (
        <Form onSubmit={async () => {
          setError(null); setSaved(false)
          try { setTerms(await api<Terms>('/api/platform/firms/terms', { method: 'PUT', body: terms })); setSaved(true) } catch (e) { setError(fail(e)) }
        }}>
          <div className="row grant">
            <label>{t('Commission on clients that pay (%)')}<input type="number" min={0} max={50} step={0.5} className="narrow-input" value={terms.commissionPercent}
              onChange={(e) => setTerms({ ...terms, commissionPercent: Number(e.target.value) })} /></label>
            <label>{t('Discount when the firm pays (%)')}<input type="number" min={0} max={50} step={0.5} className="narrow-input" value={terms.discountPercent}
              onChange={(e) => setTerms({ ...terms, discountPercent: Number(e.target.value) })} /></label>
            <Button type="submit" className="secondary">{t('Save')}</Button>
          </div>
          {saved && <p className="success">{t('Saved. They apply to the next invoices.')}</p>}
        </Form>
      )}
      {rows.length === 0 ? <p className="muted">{t('No commission yet.')}</p> : (
        <div className="table-wrap">
          <table>
            <thead><tr><th>{t('Date')}</th><th>{t('Firm')}</th><th>{t('Client')}</th><th>{t('Invoice')}</th><th>{t('Amount')}</th><th>{t('Paid out')}</th></tr></thead>
            <tbody>
              {rows.map((m) => (
                <tr key={m.id}>
                  <td>{longDay(m.createdAt)}</td><td>{m.firmName}</td><td>{m.clientName}</td><td>{m.invoiceNumber}</td>
                  <td>{money(m.amount, m.currency)}</td>
                  <td>{m.paidOutAt ? `${longDay(m.paidOutAt)} · ${m.paidOutRef ?? ''}` : (
                    <Button className="link" onClick={() => ask({
                      title: t('Commission paid to {a}', { a: m.firmName }), confirmLabel: t('Save'),
                      fields: [{ label: t('Transfer reference'), required: true }],
                      onConfirm: async ([reference]) => {
                        try { await api(`/api/platform/firms/commissions/${m.id}/paid`, { method: 'POST', body: { reference } }); load() } catch (e) { setError(fail(e)) }
                      },
                    })}>{t('Mark as paid')}</Button>
                  )}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
