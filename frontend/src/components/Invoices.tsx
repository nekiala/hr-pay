import { useState, type FormEvent } from 'react'
import { api, longDay, amount, PAYMENT_METHODS, type Invoice, type PaymentMethod } from '../api'
import { useAsk } from './Dialogs'
import { ErrorText } from './ErrorText'
import { Modal } from './Modal'
import { Button, Form } from './Button'
import { t } from '../i18n'
import { DateInput } from './DateFields'

export function InvoiceStatus({ i }: { i: Invoice }) {
  if (i.status === 'PAID') return <span className="badge ok">{t('Paid')}</span>
  if (i.status === 'VOID') return <span className="badge off">{t('Cancelled')}</span>
  if (i.overdue) return <span className="badge off">{t('Overdue')}</span>
  return <span className="badge warn">{i.paid > 0 ? t('Part paid') : t('To pay')}</span>
}

type Props = {
  invoices: Invoice[]
  /** Where each invoice's PDF is served. */
  pdf: (i: Invoice) => string
  /** The provider records payments and cancels invoices; a company only reads its own. */
  manage?: boolean
  showCompany?: boolean
  onChange?: () => unknown
}

/** A list of invoices, with their PDF and, for the provider, recording a payment or cancelling. */
export function InvoiceTable({ invoices, pdf, manage, showCompany, onChange }: Props) {
  const ask = useAsk()
  const [paying, setPaying] = useState<Invoice | null>(null)
  if (invoices.length === 0) return <p className="muted">{t('No invoice yet.')}</p>
  return (
    <>
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>{t('Invoice')}</th>{showCompany && <th>{t('Company')}</th>}<th>{t('Period')}</th><th>{t('Due')}</th>
              <th className="num">{t('Total')}</th><th className="num">{t('Left to pay')}</th><th>{t('Status')}</th><th />
            </tr>
          </thead>
          <tbody>
            {invoices.map((i) => (
              <tr key={i.id}>
                <td><a href={pdf(i)} target="_blank" rel="noreferrer" className="mono">{i.number}</a></td>
                {showCompany && <td>{i.customerName}</td>}
                <td>{longDay(i.periodFrom)}{' '}{t('to')}{' '}{longDay(i.periodTo)}<div className="muted small">{i.planName}, {i.cycle === 'YEARLY' ? 'yearly' : 'monthly'}</div></td>
                <td>{longDay(i.dueOn)}</td>
                <td className="num">{amount(i.total)} {i.currency}</td>
                <td className="num">{i.status === 'OPEN' ? `${amount(i.total - i.paid)} ${i.currency}` : '—'}</td>
                <td><InvoiceStatus i={i} /></td>
                <td className="actions">
                  {manage && i.status === 'OPEN' && <Button className="secondary" onClick={() => setPaying(i)}>{t('Record payment')}</Button>}
                  {manage && i.status === 'OPEN' && i.paid === 0 && (
                    <Button className="secondary" onClick={() => ask({
                      title: t('Cancel invoice {a}?', { a: i.number }),
                      message: t('Its period can then be invoiced again. The cancelled invoice stays in the list.'),
                      confirmLabel: t('Cancel the invoice'), danger: true,
                      fields: [{ label: t('Why'), required: true }],
                      onConfirm: async ([reason]) => {
                        await api(`/api/platform/invoices/${i.id}/void`, { method: 'POST', body: { reason } })
                        await onChange?.()
                      },
                    })}>{t('Cancel')}</Button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {paying && <RecordPayment invoice={paying} onClose={() => setPaying(null)} onDone={async () => { setPaying(null); await onChange?.() }} />}
    </>
  )
}

function RecordPayment({ invoice, onClose, onDone }: { invoice: Invoice; onClose: () => void; onDone: () => unknown }) {
  const left = Math.round((invoice.total - invoice.paid) * 100) / 100
  const [method, setMethod] = useState<PaymentMethod>('BANK_TRANSFER')
  const [reference, setReference] = useState('')
  const [sum, setSum] = useState(String(left))
  const [paidOn, setPaidOn] = useState(new Date().toISOString().slice(0, 10))
  const [note, setNote] = useState('')
  const [error, setError] = useState<string | null>(null)

  const save = async (e: FormEvent) => {
    e.preventDefault()
    setError(null)
    try {
      await api(`/api/platform/invoices/${invoice.id}/payments`, { method: 'POST', body: { method, reference, amount: Number(sum), paidOn, note } })
      await onDone()
    } catch (err) {
      setError(err instanceof Error ? err.message : t('Could not record the payment.'))
    }
  }

  return (
    <Modal title={t('Payment of {a}', { a: invoice.number })} onClose={onClose}>
      <Form onSubmit={save}>
        <ErrorText error={error} />
        <p className="hint">{invoice.customerName}: {amount(left)} {invoice.currency}{' '}{t('left to pay. The invoice closes once it is paid in full, and a company made read-only for it gets its changes back.')}</p>
        <div className="grid2">
          <label>{t('Paid by')}<select value={method} onChange={(e) => setMethod(e.target.value as PaymentMethod)}>
              {Object.entries(PAYMENT_METHODS).map(([code, label]) => <option key={code} value={code}>{label}</option>)}
            </select>
          </label>
          <label>{t('Transaction reference')}<input required maxLength={100} value={reference} onChange={(e) => setReference(e.target.value)} placeholder={t('From the bank or the operator')} /></label>
          <label>{t('Amount (')}{invoice.currency})<input required type="number" min="0.01" step="0.01" max={left} value={sum} onChange={(e) => setSum(e.target.value)} /></label>
          <label>{t('Paid on')}<DateInput required value={paidOn} onChange={(e) => setPaidOn(e.target.value)} /></label>
        </div>
        <label>{t('Note')}<input value={note} onChange={(e) => setNote(e.target.value)} /></label>
        <footer className="modal-foot">
          <Button className="secondary" onClick={onClose}>{t('Cancel')}</Button>
          <Button type="submit">{t('Record payment')}</Button>
        </footer>
      </Form>
    </Modal>
  )
}
