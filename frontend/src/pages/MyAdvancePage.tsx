import { useEffect, useState } from 'react'
import { ApiError, api, longDay, money, type AdvanceCodeSent, type MyAdvances, type SalaryAdvance , personName } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'
import { Button, Form } from '../components/Button'
import { t } from '../i18n'

export const ADVANCE_STATUS: Record<SalaryAdvance['status'], string> = {
  REQUESTED: t('Waiting for HR'), APPROVED: t('Approved, to pay'), PAID: t('Paid'), REJECTED: t('Refused'), CANCELLED: t('Withdrawn'),
}

/**
 * Salary advance on demand: the person sees what they may take today and why, asks for an amount, confirms the receipt
 * with the one-time code and their name, then follows their advances.
 */
export default function MyAdvancePage() {
  const { me } = useAuth()
  const [data, setData] = useState<MyAdvances | null>(null)
  const [amount, setAmount] = useState('')
  const [sent, setSent] = useState<AdvanceCodeSent | null>(null)
  const [code, setCode] = useState('')
  const [name, setName] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)

  const load = () => api<MyAdvances>('/api/advances/mine').then(setData).catch((e: Error) => setError(e.message))
  useEffect(() => { void load() }, [])

  const fail = (e: unknown) => setError(e instanceof ApiError ? [e.message, ...Object.values(e.fieldErrors)].join(' ') : t('Something went wrong.'))

  const askCode = async () => {
    setError(null)
    setMessage(null)
    try {
      setSent(await api<AdvanceCodeSent>('/api/advances/mine/code', { method: 'POST', body: { amount: Number(amount) } }))
    } catch (e) { fail(e) }
  }

  const confirm = async () => {
    if (!sent) return
    setError(null)
    try {
      const a = await api<SalaryAdvance>('/api/advances/mine', {
        method: 'POST', body: { challengeId: sent.challengeId, code, amount: Number(amount), signedName: name },
      })
      setSent(null)
      setAmount('')
      setCode('')
      setName('')
      setMessage(a.status === 'APPROVED'
        ? t('Approved: advance no. {a}. It is paid by your usual payment method.', { a: a.number ?? '' })
        : t('Request sent. HR decides and you are told.'))
      await load()
    } catch (e) { fail(e) }
  }

  const cancel = async (id: number) => {
    setError(null)
    try {
      await api(`/api/advances/mine/${id}/cancel`, { method: 'POST' })
      setMessage(t('Request withdrawn.'))
      await load()
    } catch (e) { fail(e) }
  }

  const a = data?.availability
  const fullName = me ? personName(me) : ''

  return (
    <section>
      <h2>{t('My salary advance')}</h2>
      <ErrorText error={error} />
      {message && <p className="success">{message}</p>}

      {data && !data.settings.enabled && <p className="notice">{t('Your company does not offer salary advances on demand.')}</p>}

      {data && data.settings.enabled && a && (
        <div className="card">
          <h3>{t('Available today')}</h3>
          {a.currency
            ? <p className="big-figure"><strong>{money(a.available, a.currency)}</strong></p>
            : null}
          {a.reason && <p className="notice">{a.reason}</p>}
          {a.explanation.length > 0 && (
            <details>
              <summary>{t('How it is worked out')}</summary>
              <ul className="small">{a.explanation.map((line) => <li key={line}>{line}</li>)}</ul>
            </details>
          )}
          <p className="hint">{t('An advance on the pay you have already earned this month. No interest and no fees: the full amount is taken back on this month\'s payslip, before loans.')}</p>

          {a.open && a.currency && !sent && (
            <Form onSubmit={(e) => { e.preventDefault(); return askCode() }}>
              <div className="row grant">
                <label>{t('Amount')} ({a.currency})<input type="number" min={1} max={a.available} step={1} required className="narrow-input"
                  value={amount} onChange={(e) => setAmount(e.target.value)} /></label>
                <Button type="submit" disabled={!amount || Number(amount) <= 0}>{t('Continue')}</Button>
              </div>
              <p className="hint">{t('{a} request(s) left this month.', { a: a.requestsLeft })}</p>
            </Form>
          )}

          {sent && (
            <Form onSubmit={(e) => { e.preventDefault(); return confirm() }}>
              <h3>{t('Confirm the receipt')}</h3>
              <blockquote className="receipt">{sent.receipt}</blockquote>
              <p className="small muted">{sent.channels.length > 0
                ? t('A code was sent by {a}. It expires at {b}.', { a: sent.channels.join(', ').toLowerCase(), b: new Date(sent.expiresAt).toLocaleTimeString() })
                : t('The code could not be sent. Ask for a new one later.')}</p>
              <div className="row grant">
                <label>{t('Code')}<input inputMode="numeric" autoComplete="one-time-code" maxLength={6} required className="narrow-input"
                  value={code} onChange={(e) => setCode(e.target.value)} /></label>
                <label>{t('Your name, as a signature')}<input required placeholder={fullName} value={name} onChange={(e) => setName(e.target.value)} /></label>
              </div>
              <div className="row">
                <Button className="secondary" onClick={() => { setSent(null); setCode('') }}>{t('Back')}</Button>
                <Button type="submit" disabled={code.length < 6 || !name.trim()}>{t('Sign and ask')}</Button>
              </div>
            </Form>
          )}
        </div>
      )}

      {data && data.advances.length > 0 && (
        <div className="card">
          <h3>{t('My advances')}</h3>
          <div className="table-wrap">
            <table>
              <thead><tr><th>{t('Date')}</th><th>{t('Number')}</th><th>{t('Amount')}</th><th>{t('Taken back on')}</th><th>{t('Status')}</th><th></th></tr></thead>
              <tbody>
                {data.advances.map((x) => (
                  <tr key={x.id}>
                    <td>{longDay(x.createdAt)}</td>
                    <td>{x.number ?? '—'}</td>
                    <td>{money(x.amount, x.currency)}</td>
                    <td>{x.month.slice(0, 7)}</td>
                    <td>{ADVANCE_STATUS[x.status]}{x.decisionNote && <div className="hint">{x.decisionNote}</div>}</td>
                    <td>{x.status === 'REQUESTED' && <Button className="secondary" onClick={() => cancel(x.id)}>{t('Withdraw')}</Button>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </section>
  )
}
