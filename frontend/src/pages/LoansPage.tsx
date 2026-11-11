import { useEffect, useState, type FormEvent } from 'react'
import { ApiError, api, money, type Loan, type Page, type User , personName } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'
import { useAsk } from '../components/Dialogs'
import { Modal } from '../components/Modal'
import { Button, Form } from '../components/Button'
import { t } from '../i18n'
import { DateInput } from '../components/DateFields'

const thisMonth = () => new Date().toISOString().slice(0, 7)
const STATUS = { ACTIVE: t('Being repaid'), REPAID: t('Repaid'), CANCELLED: t('Cancelled') }

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
    const tItem = setTimeout(() => {
      api<Page<User>>(`/api/users?${new URLSearchParams({ q: form.userQuery.trim(), size: '10' })}`).then((p) => setPeople(p.items)).catch(() => undefined)
    }, 250)
    return () => clearTimeout(tItem)
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
      setError(e instanceof ApiError ? [e.message, ...Object.values(e.fieldErrors)].join(' ') : t('Something went wrong.'))
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
    }, t('Saved. Payroll takes the installment from the first month.'))
  }

  const label = (p: User) => `${personName(p)} (${p.registrationNo ?? p.email})`
  const months = form.kind === 'LOAN' && Number(form.principal) > 0 && Number(form.installment) > 0 ? Math.ceil(Number(form.principal) / Number(form.installment)) : null

  return (
    <section>
      <div className="row between">
        <h2>{t('Loans and advances')}</h2>
        {manage && <Button onClick={() => setGranting(true)}>{t('New loan or advance')}</Button>}
      </div>
      <ErrorText error={error} />
      {message && <p className="success">{message}</p>}

      <div className="card">
        <label className="month-pick">{t('Show')}<select value={status} onChange={(e) => setStatus(e.target.value)}>
            <option value="ACTIVE">{t('Being repaid')}</option><option value="REPAID">{t('Repaid')}</option><option value="CANCELLED">{t('Cancelled')}</option><option value="">{t('All')}</option>
          </select>
        </label>
        {loans.length === 0 ? <p className="muted">{t('None.')}</p> : (
          <div className="table-wrap">
            <table>
              <thead><tr><th>#</th><th>{t('Employee')}</th><th>{t('Type')}</th><th>{t('Amount')}</th><th>{t('Per month')}</th><th>{t('From')}</th><th>{t('Repaid')}</th><th>{t('Left')}</th><th>{t('Status')}</th><th></th></tr></thead>
              <tbody>
                {loans.map((l) => (
                  <tr key={l.id}>
                    <td>{l.id}</td>
                    <td>{l.userName}{l.note && <div className="hint">{l.note}</div>}</td>
                    <td>{l.kind === 'LOAN' ? t('Loan') : t('Advance')}</td>
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
                            title: t('Pause loan #{a} for a month', { a: l.id }), confirmLabel: t('Pause'),
                            fields: [{ label: t('Skip the installment of'), type: 'month', required: true, defaultValue: thisMonth() }, { label: t('Reason'), required: true }],
                            onConfirm: ([month, reason]) => run(() => api(`/api/loans/${l.id}/pauses`, { method: 'POST', body: { month, reason } }), t('Installment skipped for {a}.', { a: month })),
                          })}>{t('Pause a month')}</Button>{' '}
                          <Button className="secondary" onClick={() => ask({
                            title: t('Cancel loan #{a}?', { a: l.id }), message: t('The remaining {a} will not be deducted.', { a: money(l.balance, l.currency) }), confirmLabel: t('Cancel the loan'), danger: true,
                            fields: [{ label: t('Reason'), required: true, multiline: true }],
                            onConfirm: ([reason]) => run(() => api(`/api/loans/${l.id}/cancel`, { method: 'POST', body: { reason } }), t('Cancelled.')),
                          })}>{t('Cancel')}</Button>
                        </>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <p className="hint">{t('Each month the installments together take at most the share of net pay set under Rates; the rest waits for the next month.')}</p>
      </div>

      {manage && granting && (
        <Modal title={t('New loan or advance')} size="lg" onClose={() => setGranting(false)}>
          <Form onSubmit={async (e) => { if (await grant(e)) setGranting(false) }}>
            <div className="grid3">
              <label>{t('Employee')}<input list="loan-people" placeholder={t('Type a name or number')} value={form.userQuery} required
                  onChange={(e) => {
                    const match = people.find((p) => label(p) === e.target.value)
                    setForm({ ...form, userQuery: e.target.value, userId: match ? String(match.id) : '' })
                  }} />
                <datalist id="loan-people">{people.map((p) => <option key={p.id} value={label(p)} />)}</datalist>
              </label>
              <label>{t('Type')}<select value={form.kind} onChange={(e) => setForm({ ...form, kind: e.target.value })}>
                  <option value="LOAN">{t('Loan, repaid in installments')}</option><option value="ADVANCE">{t('Salary advance, taken back in one month')}</option>
                </select>
              </label>
              <label>{t('Granted on')}<DateInput value={form.grantedOn} required onChange={(e) => setForm({ ...form, grantedOn: e.target.value })} /></label>
              <label>{t('Amount')}<input type="number" min={0.01} step={0.01} value={form.principal} required onChange={(e) => setForm({ ...form, principal: e.target.value })} /></label>
              <label>{t('Currency')}<select value={form.currency} onChange={(e) => setForm({ ...form, currency: e.target.value })}><option>{t('USD')}</option><option>{t('CDF')}</option></select></label>
              {form.kind === 'LOAN' && (
                <label>{t('Per month')}<input type="number" min={0.01} step={0.01} value={form.installment} required onChange={(e) => setForm({ ...form, installment: e.target.value })} /></label>
              )}
              <label>{t('First month deducted')}<input type="month" value={form.startMonth} required onChange={(e) => setForm({ ...form, startMonth: e.target.value })} /></label>
              <label>{t('Note')}<input value={form.note} maxLength={300} onChange={(e) => setForm({ ...form, note: e.target.value })} /></label>
            </div>
            {months && <p className="hint">{t('About')}{' '}{months}{' '}{t('month(s) to repay.')}</p>}
            <ErrorText error={error} />
            <div className="modal-foot"><Button className="secondary" onClick={() => setGranting(false)}>{t('Cancel')}</Button><Button type="submit" disabled={!form.userId}>{t('Save')}</Button></div>
          </Form>
        </Modal>
      )}
    </section>
  )
}
