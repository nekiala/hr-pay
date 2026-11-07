import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api, DEPARTURE_REASONS, longDay, money, type BackPayDue, type Correction, type DepartureSummary } from '../api'
import { useAuth } from '../auth'
import { Button } from '../components/Button'
import { useAsk } from '../components/Dialogs'
import { ErrorText } from '../components/ErrorText'
import { plural, t } from '../i18n'

const MODE: Record<Correction['mode'], string> = {
  COMPLEMENTARY: t('Complementary payslip'), NEXT_PAYROLL: t('In the next payroll'), REVERSAL: t('Payslip cancelled'),
}
const STATUS: Record<Correction['status'], string> = {
  WAITING: t('Waiting for Finance'), APPROVED: t('Approved'), CARRIED: t('Carried'),
}

/** Corrections of closed months: what waits for Finance, what was carried, and back pay still due. */
export default function PayrollCorrectionsPage() {
  const { can, me } = useAuth()
  const ask = useAsk()
  const [list, setList] = useState<Correction[]>([])
  const [due, setDue] = useState<BackPayDue[]>([])
  const [error, setError] = useState<string | null>(null)
  const [done, setDone] = useState<string | null>(null)

  const load = () => {
    api<Correction[]>('/api/payroll/corrections').then(setList).catch((e: Error) => setError(e.message))
    api<BackPayDue[]>('/api/payroll/back-pay').then(setDue).catch((e: Error) => setError(e.message))
  }
  useEffect(load, [])

  const approve = (c: Correction) => ask({
    title: t('Approve the complementary payslip of {a}?', { a: c.name }),
    message: (c.net ?? 0) < 0 ? t('Its net is negative: what the person owes becomes a salary advance, taken from the next payrolls.') : t('It gets its number and can be paid with the month\'s payment files.'),
    confirmLabel: t('Approve'),
    onConfirm: async () => { await api(`/api/payroll/corrections/${c.id}/approve`, { method: 'POST' }); load() },
  })
  const discard = (c: Correction) => ask({
    title: t('Discard this correction?'), danger: true, confirmLabel: t('Discard'),
    onConfirm: async () => { await api(`/api/payroll/corrections/${c.id}`, { method: 'DELETE' }); load() },
  })
  const payAll = () => ask({
    title: t('Pay the back pay in the next payroll?'),
    message: t('Each payslip is worked out again with the salary on file; the difference goes into the next open payroll as back pay. Calculate that payroll again afterwards.'),
    confirmLabel: t('Pay the back pay'),
    onConfirm: async () => {
      const r = await api<Correction[]>('/api/payroll/back-pay', { method: 'POST', body: {} })
      setDone(plural('{n} correction recorded.', '{n} corrections recorded.', r.length))
      load()
    },
  })

  const waiting = list.filter((c) => c.status === 'WAITING')
  return (
    <section>
      <h2>{t('Corrections and back pay')}</h2>
      <p className="muted">{t('A closed payslip is never changed: open it and choose Correct to make a complementary payslip, carry the difference into the next payroll, or cancel it.')}</p>
      <ErrorText error={error} />
      {done && <p className="success">{done}</p>}

      {waiting.length > 0 && (
        <div className="card">
          <h3>{t('Waiting for Finance')}</h3>
          <div className="table-wrap">
            <table>
              <thead><tr><th>{t('Person')}</th><th>{t('Month')}</th><th>{t('Kind')}</th><th className="num">{t('Net')}</th><th>{t('Reason')}</th><th>{t('By')}</th><th /></tr></thead>
              <tbody>
                {waiting.map((c) => (
                  <tr key={c.id}>
                    <td>{c.name}</td><td>{c.month.slice(0, 7)}</td><td>{MODE[c.mode]}</td>
                    <td className="num"><Link to={`/payslips/${c.complementaryId}`}>{money(c.net ?? 0, c.currency)}</Link></td>
                    <td>{c.reason}</td><td className="small">{c.createdBy}</td>
                    <td><div className="row">
                      {can('payroll.approve') && c.createdBy !== me?.email && <Button onClick={() => approve(c)}>{t('Approve')}</Button>}
                      <Button className="secondary" onClick={() => discard(c)}>{t('Discard')}</Button>
                    </div></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      <div className="card">
        <div className="row between">
          <h3>{t('Back pay due')}</h3>
          {due.length > 0 && can('payroll.run') && <Button onClick={payAll}>{t('Pay the back pay')}</Button>}
        </div>
        <p className="muted small">{t('Closed payslips of the last twelve months whose basic salary is now different on file: a raise or a grade change dated in the past.')}</p>
        {due.length === 0 ? <p className="muted">{t('No back pay is due.')}</p> : (
          <div className="table-wrap">
            <table>
              <thead><tr><th>{t('Person')}</th><th>{t('Month')}</th><th className="num">{t('Basic paid')}</th><th className="num">{t('Basic on file')}</th><th className="num">{t('About')}</th><th /></tr></thead>
              <tbody>
                {due.map((d) => (
                  <tr key={d.payslipId}>
                    <td>{d.name}</td><td>{d.month.slice(0, 7)}</td><td className="num">{money(d.basicPaid, d.currency)}</td>
                    <td className="num">{money(d.basicNow, d.currency)}</td><td className="num">{money(d.estimate, d.currency)}</td>
                    <td>{can('payroll.run') && <Link to={`/payslips/${d.payslipId}/correct`}>{t('Correct')}</Link>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      <div className="card">
        <h3>{t('All corrections')}</h3>
        {list.length === 0 ? <p className="muted">{t('No correction yet.')}</p> : (
          <div className="table-wrap">
            <table>
              <thead><tr><th>{t('Date')}</th><th>{t('Person')}</th><th>{t('Month')}</th><th>{t('Kind')}</th><th className="num">{t('Gross change')}</th><th>{t('Status')}</th><th>{t('Reason')}</th><th /></tr></thead>
              <tbody>
                {list.map((c) => (
                  <tr key={c.id}>
                    <td>{longDay(c.createdAt)}</td><td>{c.name}</td>
                    <td><Link to={`/payslips/${c.payslipId}`}>{c.month.slice(0, 7)}</Link></td>
                    <td>{MODE[c.mode]}{c.targetMonth ? ` → ${c.targetMonth.slice(0, 7)}` : ''}</td>
                    <td className="num">{money(c.grossChange, c.currency)}</td>
                    <td>{c.complementaryId ? <Link to={`/payslips/${c.complementaryId}`}>{c.complementaryNumber ?? STATUS[c.status]}</Link> : STATUS[c.status]}</td>
                    <td>{c.reason}</td>
                    <td>{c.status === 'CARRIED' && can('payroll.run') && <Button className="link" onClick={() => discard(c)}>{t('Discard')}</Button>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </section>
  )
}

/** Departures of the company and where each settlement stands. */
export function DeparturesPage() {
  const [list, setList] = useState<DepartureSummary[]>([])
  const [error, setError] = useState<string | null>(null)
  useEffect(() => { api<DepartureSummary[]>('/api/departures').then(setList).catch((e: Error) => setError(e.message)) }, [])
  return (
    <section>
      <h2>{t('Departures')}</h2>
      <p className="muted">{t('To record a departure, open the person on the Users page and choose Departure.')}</p>
      <ErrorText error={error} />
      <div className="card">
        {list.length === 0 ? <p className="muted">{t('No departure yet.')}</p> : (
          <div className="table-wrap">
            <table>
              <thead><tr><th>{t('Person')}</th><th>{t('Registration no.')}</th><th>{t('Last day')}</th><th>{t('Reason')}</th><th className="num">{t('Settlement')}</th><th>{t('Status')}</th></tr></thead>
              <tbody>
                {list.map((d) => (
                  <tr key={d.id}>
                    <td><Link to={`/users/${d.userId}/departure`}>{d.name}</Link></td><td>{d.registrationNo}</td><td>{longDay(d.lastDay)}</td>
                    <td>{DEPARTURE_REASONS[d.reason]}</td><td className="num">{money(d.total, d.currency)}</td>
                    <td>{d.status === 'CONFIRMED' ? <span className="badge ok">{t('Confirmed')}</span> : <span className="badge">{t('Draft')}</span>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </section>
  )
}
