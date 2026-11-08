import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { ApiError, api, money } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'
import { useAsk } from '../components/Dialogs'
import { Modal } from '../components/Modal'
import { Button, Form } from '../components/Button'
import { t } from '../i18n'

export type PayMethodKind = 'BANK' | 'MOBILE_MONEY' | 'CASH'
type Group = { method: PayMethodKind; provider: string; currency: string; count: number; total: number }
type Payee = { payslipId: number; userId: number; name: string; method: PayMethodKind; currency: string; amount: number; problem: string }
type Batch = {
  id: number; method: PayMethodKind; provider: string; currency: string; lineCount: number; total: number
  status: 'PREPARED' | 'PAID' | 'CANCELLED'; createdBy: string; createdAt: string; paidOn: string | null; reference: string | null
  paidBy: string | null; cancelReason: string | null
}
type Overview = { month: string; runStatus: string; toPay: Group[]; problems: Payee[]; batches: Batch[]; payslips: number; paid: number }

export const METHOD: Record<PayMethodKind, string> = { BANK: t('Bank transfer'), MOBILE_MONEY: t('Mobile money'), CASH: t('Cash') }
const lastMonth = () => { const d = new Date(); d.setDate(1); d.setMonth(d.getMonth() - 1); return d.toISOString().slice(0, 7) }
const today = () => new Date().toISOString().slice(0, 10)

export default function PaymentsPage() {
  const ask = useAsk()
  const { can } = useAuth()
  const canPay = can('payroll.pay')
  const [params, setParams] = useSearchParams()
  const month = params.get('month') ?? lastMonth()
  const kind = params.get('kind') === 'QUINZAINE' ? 'QUINZAINE' : 'PAYROLL'
  const quinzaine = kind === 'QUINZAINE'
  const [data, setData] = useState<Overview | null>(null)
  const [paying, setPaying] = useState<{ id: number; paidOn: string; reference: string } | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)

  const load = () => api<Overview>(`/api/payments/${month}?kind=${kind}`).then(setData).catch((e) => {
    setData(null)
    setError(e instanceof ApiError && e.status === 404 ? (quinzaine ? t('No quinzaine for {a} yet.', { a: month }) : t('No payroll for {a} yet.', { a: month })) : (e as Error).message)
  })
  useEffect(() => { setError(null); void load() }, [month, kind]) // eslint-disable-line react-hooks/exhaustive-deps

  const run = async (action: () => Promise<unknown>, done: string) => {
    setError(null)
    setMessage(null)
    try {
      await action()
      setMessage(done)
      await load()
      return true
    } catch (e) {
      setError(e instanceof ApiError ? [e.message, ...Object.values(e.fieldErrors)].join(' ') : t('Something went wrong.'))
      return false
    }
  }

  const closed = data?.runStatus === 'CLOSED'

  return (
    <section>
      <h2>{t('Payments')}</h2>
      <div className="row">
        <label className="month-pick">{t('Month')}{' '}<input type="month" value={month} onChange={(e) => e.target.value && setParams({ month: e.target.value, kind })} /></label>
        <label className="month-pick">{t('Pay')}{' '}<select className="auto-width" value={kind} onChange={(e) => setParams({ month, kind: e.target.value })}>
          <option value="PAYROLL">{t('Month-end pay')}</option>
          <option value="QUINZAINE">{t('Quinzaine')}</option>
        </select></label>
      </div>
      <ErrorText error={error} />
      {message && <p className="success">{message}</p>}

      {data && (
        <>
          <p className="muted">{data.paid}{' '}{t('of')}{' '}{data.payslips}{' '}{t('net pays recorded as paid.')}</p>
          {!closed && !quinzaine && <p className="notice">{t('The')}{' '}{month}{' '}{t('payroll is not closed yet. Payment files can be made once HR closes it.')}</p>}
          {!closed && quinzaine && data.runStatus !== 'PAID' && <p className="notice">{t('The quinzaine of {a} is not closed yet. Payment files can be made once HR closes it.', { a: month })}</p>}

          <div className="card">
            <h3>{t('To pay')}</h3>
            <p className="hint">{t('One file per bank, mobile money operator or cash, and currency. Payment details come from each employee file when the file is made.')}</p>
            {data.toPay.length === 0 ? <p className="muted">{t('Nothing left to pay.')}</p> : (
              <div className="table-wrap">
                <table>
                  <thead><tr><th>{t('How')}</th><th>{t('Bank or operator')}</th><th>{t('People')}</th><th>{t('Total')}</th><th></th></tr></thead>
                  <tbody>
                    {data.toPay.map((g) => (
                      <tr key={`${g.method}|${g.provider}|${g.currency}`}>
                        <td>{METHOD[g.method]}</td><td>{g.provider}</td><td>{g.count}</td><td><strong>{money(g.total, g.currency)}</strong></td>
                        <td>{canPay && closed && (
                          <Button className="secondary" onClick={() => run(() => api(`/api/payments/${month}/batches?kind=${kind}`, { method: 'POST', body: { method: g.method, provider: g.provider, currency: g.currency } }),
                            t('File made for {a}. Download it below, send it, then record the payment.', { a: g.provider }))}>{t('Make file')}</Button>
                        )}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
            {data.problems.length > 0 && (
              <div className="notice">
                <strong>{t('Cannot be paid until the employee file is completed')}</strong>
                <ul>{data.problems.map((p) => (
                  <li key={p.payslipId}>{p.name} ({money(p.amount, p.currency)}): {p.problem} {can('salary.read') && <Link to={`/users/${p.userId}/file`}>{t('Open file')}</Link>}</li>
                ))}</ul>
              </div>
            )}
          </div>

          <div className="card">
            <h3>{t('Payment files')}</h3>
            {data.batches.length === 0 ? <p className="muted">{t('None yet.')}</p> : (
              <div className="table-wrap">
                <table>
                  <thead><tr><th>#</th><th>{t('How')}</th><th>{t('People')}</th><th>{t('Total')}</th><th>{t('Status')}</th><th></th></tr></thead>
                  <tbody>
                    {data.batches.map((b) => (
                      <tr key={b.id}>
                        <td>{b.id}</td>
                        <td>{METHOD[b.method]} · {b.provider}</td>
                        <td>{b.lineCount}</td>
                        <td>{money(b.total, b.currency)}</td>
                        <td>
                          {b.status === 'PREPARED' && t('Made, not paid yet')}
                          {b.status === 'PAID' && <>{t('Paid on')}{' '}{b.paidOn}{b.reference && <span className="muted">{' '}{t('· ref.')}{' '}{b.reference}</span>}</>}
                          {b.status === 'CANCELLED' && <span className="muted">{t('Cancelled:')}{' '}{b.cancelReason}</span>}
                        </td>
                        <td><span className="row">
                          {b.status !== 'CANCELLED' && <Link to={`/payroll/payments/files/${b.id}`}>{b.method === 'CASH' ? t('Cash list') : t('View')}</Link>}
                          {canPay && b.status !== 'CANCELLED' && <a href={`/api/payment-batches/${b.id}/file.csv`}>{t('Download')}</a>}
                          {canPay && b.status === 'PREPARED' && (
                            <>
                              <Button className="secondary" onClick={() => setPaying({ id: b.id, paidOn: today(), reference: '' })}>{t('Record payment')}</Button>
                              <Button className="link" onClick={() => ask({
                                title: t('Cancel payment file #{a}', { a: b.id }), message: t('Its people can then be put in a new file.'), confirmLabel: t('Cancel the file'), danger: true,
                                fields: [{ label: t('Why?'), required: true, multiline: true }],
                                onConfirm: ([reason]) => run(() => api(`/api/payment-batches/${b.id}/cancel`, { method: 'POST', body: { reason } }), t('File cancelled.')),
                              })}>{t('Cancel')}</Button>
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
              <Modal title={t('Record payment of file #{a}', { a: paying.id })} size="sm" onClose={() => setPaying(null)}>
                <Form onSubmit={() => run(async () => { await api(`/api/payment-batches/${paying.id}/paid`, { method: 'POST', body: { paidOn: paying.paidOn, reference: paying.reference || null } }); setPaying(null) }, t('Payment recorded.'))}>
                  <label>{t('Paid on')}<input type="date" required max={today()} value={paying.paidOn} onChange={(e) => setPaying({ ...paying, paidOn: e.target.value })} /></label>
                  <label>{t('Bank or operator reference')}<input maxLength={100} value={paying.reference} onChange={(e) => setPaying({ ...paying, reference: e.target.value })} /></label>
                  <ErrorText error={error} />
                  <div className="modal-foot">
                    <Button className="secondary" onClick={() => setPaying(null)}>{t('Cancel')}</Button>
                    <Button type="submit">{t('Save')}</Button>
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
