import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { ApiError, api, money } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'
import { useAsk } from '../components/Dialogs'
import { Modal } from '../components/Modal'
import { Button, Form } from '../components/Button'

export type PayMethodKind = 'BANK' | 'MOBILE_MONEY' | 'CASH'
type Group = { method: PayMethodKind; provider: string; currency: string; count: number; total: number }
type Payee = { payslipId: number; userId: number; name: string; method: PayMethodKind; currency: string; amount: number; problem: string }
type Batch = {
  id: number; method: PayMethodKind; provider: string; currency: string; lineCount: number; total: number
  status: 'PREPARED' | 'PAID' | 'CANCELLED'; createdBy: string; createdAt: string; paidOn: string | null; reference: string | null
  paidBy: string | null; cancelReason: string | null
}
type Overview = { month: string; runStatus: string; toPay: Group[]; problems: Payee[]; batches: Batch[]; payslips: number; paid: number }

export const METHOD: Record<PayMethodKind, string> = { BANK: 'Bank transfer', MOBILE_MONEY: 'Mobile money', CASH: 'Cash' }
const lastMonth = () => { const d = new Date(); d.setDate(1); d.setMonth(d.getMonth() - 1); return d.toISOString().slice(0, 7) }
const today = () => new Date().toISOString().slice(0, 10)

export default function PaymentsPage() {
  const ask = useAsk()
  const { can } = useAuth()
  const canPay = can('payroll.pay')
  const [params, setParams] = useSearchParams()
  const month = params.get('month') ?? lastMonth()
  const [data, setData] = useState<Overview | null>(null)
  const [paying, setPaying] = useState<{ id: number; paidOn: string; reference: string } | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)

  const load = () => api<Overview>(`/api/payments/${month}`).then(setData).catch((e) => {
    setData(null)
    setError(e instanceof ApiError && e.status === 404 ? `No payroll for ${month} yet.` : (e as Error).message)
  })
  useEffect(() => { setError(null); void load() }, [month]) // eslint-disable-line react-hooks/exhaustive-deps

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

  const closed = data?.runStatus === 'CLOSED'

  return (
    <section>
      <h2>Payments</h2>
      <label className="month-pick">Month <input type="month" value={month} onChange={(e) => e.target.value && setParams({ month: e.target.value })} /></label>
      <ErrorText error={error} />
      {message && <p className="success">{message}</p>}

      {data && (
        <>
          <p className="muted">{data.paid} of {data.payslips} net pays recorded as paid.</p>
          {!closed && <p className="notice">The {month} payroll is not closed yet. Payment files can be made once HR closes it.</p>}

          <div className="card">
            <h3>To pay</h3>
            <p className="hint">One file per bank, mobile money operator or cash, and currency. Payment details come from each employee file when the file is made.</p>
            {data.toPay.length === 0 ? <p className="muted">Nothing left to pay.</p> : (
              <div className="table-wrap">
                <table>
                  <thead><tr><th>How</th><th>Bank or operator</th><th>People</th><th>Total</th><th></th></tr></thead>
                  <tbody>
                    {data.toPay.map((g) => (
                      <tr key={`${g.method}|${g.provider}|${g.currency}`}>
                        <td>{METHOD[g.method]}</td><td>{g.provider}</td><td>{g.count}</td><td><strong>{money(g.total, g.currency)}</strong></td>
                        <td>{canPay && closed && (
                          <Button className="secondary" onClick={() => run(() => api(`/api/payments/${month}/batches`, { method: 'POST', body: { method: g.method, provider: g.provider, currency: g.currency } }),
                            `File made for ${g.provider}. Download it below, send it, then record the payment.`)}>Make file</Button>
                        )}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
            {data.problems.length > 0 && (
              <div className="notice">
                <strong>Cannot be paid until the employee file is completed</strong>
                <ul>{data.problems.map((p) => (
                  <li key={p.payslipId}>{p.name} ({money(p.amount, p.currency)}): {p.problem} {can('salary.read') && <Link to={`/users/${p.userId}/file`}>Open file</Link>}</li>
                ))}</ul>
              </div>
            )}
          </div>

          <div className="card">
            <h3>Payment files</h3>
            {data.batches.length === 0 ? <p className="muted">None yet.</p> : (
              <div className="table-wrap">
                <table>
                  <thead><tr><th>#</th><th>How</th><th>People</th><th>Total</th><th>Status</th><th></th></tr></thead>
                  <tbody>
                    {data.batches.map((b) => (
                      <tr key={b.id}>
                        <td>{b.id}</td>
                        <td>{METHOD[b.method]} · {b.provider}</td>
                        <td>{b.lineCount}</td>
                        <td>{money(b.total, b.currency)}</td>
                        <td>
                          {b.status === 'PREPARED' && 'Made, not paid yet'}
                          {b.status === 'PAID' && <>Paid on {b.paidOn}{b.reference && <span className="muted"> · ref. {b.reference}</span>}</>}
                          {b.status === 'CANCELLED' && <span className="muted">Cancelled: {b.cancelReason}</span>}
                        </td>
                        <td><span className="row">
                          {b.status !== 'CANCELLED' && <Link to={`/payroll/payments/files/${b.id}`}>{b.method === 'CASH' ? 'Cash list' : 'View'}</Link>}
                          {canPay && b.status !== 'CANCELLED' && <a href={`/api/payment-batches/${b.id}/file.csv`}>Download</a>}
                          {canPay && b.status === 'PREPARED' && (
                            <>
                              <Button className="secondary" onClick={() => setPaying({ id: b.id, paidOn: today(), reference: '' })}>Record payment</Button>
                              <Button className="link" onClick={() => ask({
                                title: `Cancel payment file #${b.id}`, message: 'Its people can then be put in a new file.', confirmLabel: 'Cancel the file', danger: true,
                                fields: [{ label: 'Why?', required: true, multiline: true }],
                                onConfirm: ([reason]) => run(() => api(`/api/payment-batches/${b.id}/cancel`, { method: 'POST', body: { reason } }), 'File cancelled.'),
                              })}>Cancel</Button>
                            </>
                          )}
                        </span></td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
            {paying && (
              <Modal title={`Record payment of file #${paying.id}`} size="sm" onClose={() => setPaying(null)}>
                <Form onSubmit={() => run(async () => { await api(`/api/payment-batches/${paying.id}/paid`, { method: 'POST', body: { paidOn: paying.paidOn, reference: paying.reference || null } }); setPaying(null) }, 'Payment recorded.')}>
                  <label>Paid on<input type="date" required max={today()} value={paying.paidOn} onChange={(e) => setPaying({ ...paying, paidOn: e.target.value })} /></label>
                  <label>Bank or operator reference<input maxLength={100} value={paying.reference} onChange={(e) => setPaying({ ...paying, reference: e.target.value })} /></label>
                  <ErrorText error={error} />
                  <div className="modal-foot">
                    <Button className="secondary" onClick={() => setPaying(null)}>Cancel</Button>
                    <Button type="submit">Save</Button>
                  </div>
                </Form>
              </Modal>
            )}
          </div>
        </>
      )}
    </section>
  )
}
