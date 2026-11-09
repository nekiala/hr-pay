import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { ApiError, api, longDay, money } from '../api'
import { Button } from '../components/Button'
import { useAsk } from '../components/Dialogs'
import { ErrorText } from '../components/ErrorText'
import { PayDeclarationModal, STATUS_BADGE, statusLabel, type CalendarItem } from '../components/DeclarationStatus'
import { localCurrency, t } from '../i18n'

type Rule = {
  kind: string; authority: string; declareDay: number; payDay: number; quarterlyUpToStaff: number | null
  lateDeclarationPercent: number | null; lateDeclarationPercentLater: number | null; laterAfterMonths: number | null
  latePaymentPercentMonth: number | null; latePaymentPercentDay: number | null; dailyFromDay: number | null
  legalBasis: string | null; verified: boolean
}
type Page = { items: CalendarItem[]; currency: string; rules: Rule[] }

const fail = (e: unknown) => e instanceof ApiError ? [e.message, ...Object.values(e.fieldErrors)].join(' ') : t('Something went wrong.')

/** Every declaration of the last twelve months: when it is due, declared, paid, and what lateness would cost. */
export default function DeclarationCalendarPage() {
  const ask = useAsk()
  const [page, setPage] = useState<Page | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const [paying, setPaying] = useState<CalendarItem | null>(null)
  const [showAll, setShowAll] = useState(false)

  const load = () => api<Page>('/api/declarations/calendar?months=12').then(setPage).catch((e) => setError(fail(e)))
  useEffect(() => { void load() }, [])

  const run = async (work: () => Promise<unknown>, done: string) => {
    setError(null)
    setMessage(null)
    try { await work(); setMessage(done); await load() } catch (e) { setError(fail(e)) }
  }

  const cur = page?.currency ?? localCurrency()
  const items = (page?.items ?? []).filter((i) => showAll || i.status !== 'NIL')
  const months = [...new Set(items.map((i) => i.month))].sort().reverse()
  const open = (page?.items ?? []).filter((i) => i.status !== 'PAID' && i.status !== 'NIL')
  const late = open.filter((i) => i.late)
  const penalties = (page?.items ?? []).reduce((s, i) => s + (i.status !== 'PAID' ? i.penalty?.total ?? 0 : 0), 0)
  const next = [...open].sort((a, b) => a.nextDue.localeCompare(b.nextDue))[0]

  return (
    <section>
      <p className="small muted"><Link to="/payroll/declarations">{t('Declarations')}</Link></p>
      <h2>{t('Declarations calendar')}</h2>
      <ErrorText error={error} />
      {message && <p className="success">{message}</p>}

      {page && (
        <div className="tiles">
          <div className="tile"><span>{t('Next deadline')}</span><strong>{next ? longDay(next.nextDue) : '—'}</strong>
            <small>{next ? `${next.kind} · ${next.month}` : t('Nothing open')}</small></div>
          <div className="tile"><span>{t('Open')}</span><strong>{open.length}</strong><small>{t('to declare or to pay')}</small></div>
          <div className={`tile${late.length ? ' warn' : ''}`}><span>{t('Late')}</span><strong>{late.length}</strong><small>{t('past their due date')}</small></div>
          <div className={`tile${penalties > 0 ? ' warn' : ''}`}><span>{t('Penalties if paid today')}</span><strong>{money(penalties, cur)}</strong><small>{t('estimate')}</small></div>
        </div>
      )}

      {page && page.items.length === 0 && <p className="muted">{t('No payroll yet: the calendar starts with the first month paid.')}</p>}

      {months.map((m) => (
        <div className="card" key={m}>
          <h3>{m}</h3>
          <div className="table-wrap">
            <table>
              <thead><tr><th>{t('Declaration')}</th><th>{t('To')}</th><th>{t('Due')}</th><th>{t('Status')}</th><th>{t('Amount')}</th><th>{t('Declared')}</th><th>{t('Paid')}</th><th>{t('Penalty (estimate)')}</th><th></th></tr></thead>
              <tbody>
                {items.filter((i) => i.month === m).map((i) => (
                  <tr key={i.kind}>
                    <td><strong>{i.kind}</strong>{i.quarterly && <div className="small muted">{t('Quarterly')}</div>}
                      {i.annual && <div className="small notice">{i.annual}</div>}</td>
                    <td className="small">{i.authority}</td>
                    <td>{longDay(i.declareDue)}{i.payDue !== i.declareDue && <div className="small muted">{t('pay by {a}', { a: longDay(i.payDue) })}</div>}
                      {i.status !== 'PAID' && i.status !== 'NIL' && <div className={`small ${i.daysLeft < 0 ? 'error' : 'muted'}`}>
                        {i.daysLeft < 0 ? t('{a} days late', { a: -i.daysLeft }) : i.daysLeft === 0 ? t('today') : t('in {a} days', { a: i.daysLeft })}</div>}</td>
                    <td><span className={`badge ${i.late && i.status !== 'PAID' ? 'off' : STATUS_BADGE[i.status]}`}>{statusLabel(i)}</span></td>
                    <td>{i.amount === null ? '—' : money(i.amount, cur)}</td>
                    <td className="small">{i.filed ? <>{longDay(i.filed.filedOn)}{i.filed.reference && <div className="muted">{i.filed.reference}</div>}</> : '—'}</td>
                    <td className="small">{i.paid ? <>{longDay(i.paid.paidOn)} · {money(i.paid.amount, cur)}{i.paid.reference && <div className="muted">{i.paid.reference}</div>}</> : '—'}</td>
                    <td className="small">{i.penalty && i.penalty.total > 0
                      ? <span title={i.penalty.how.join(' ')}>{money(i.penalty.total, cur)}<div className="muted">{i.penalty.how.join(' ')}</div></span> : '—'}</td>
                    <td>
                      <Link className="button link" to={`/payroll/declarations?month=${i.month}&kind=${i.kind}`}>{t('Open')}</Link>
                      {i.status === 'DECLARED' && <>{' '}<Button className="link" onClick={() => setPaying(i)}>{t('Record payment')}</Button></>}
                      {i.status === 'PAID' && <>{' '}<Button className="link" onClick={() => ask({
                        title: t('Remove the payment record?'), confirmLabel: t('Remove'), danger: true,
                        onConfirm: () => run(() => api(`/api/declarations/${i.month}/${i.kind}/paid`, { method: 'DELETE' }), t('Payment record removed.')),
                      })}>{t('Undo payment')}</Button></>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      ))}
      {page && page.items.some((i) => i.status === 'NIL') && (
        <label className="check"><input type="checkbox" checked={showAll} onChange={(e) => setShowAll(e.target.checked)} />{t('Show the declarations with nobody concerned')}</label>
      )}

      {page && page.rules.length > 0 && (
        <div className="card">
          <h3>{t('Due dates and penalties')}</h3>
          <div className="table-wrap">
            <table>
              <thead><tr><th>{t('Declaration')}</th><th>{t('Due')}</th><th>{t('Late declaration')}</th><th>{t('Late payment')}</th><th>{t('Legal basis')}</th></tr></thead>
              <tbody>
                {page.rules.map((r) => (
                  <tr key={r.kind}>
                    <td><strong>{r.kind}</strong> <span className="muted small">{r.authority}</span></td>
                    <td className="small">{t('Day {a} of the next month', { a: r.declareDay })}
                      {r.quarterlyUpToStaff && <div className="muted">{t('Quarterly up to {a} workers', { a: r.quarterlyUpToStaff })}</div>}</td>
                    <td className="small">{r.lateDeclarationPercent === null ? '—' : `${r.lateDeclarationPercent} %`}
                      {r.lateDeclarationPercentLater !== null && <div className="muted">{t('{a} % after {b} months', { a: r.lateDeclarationPercentLater, b: r.laterAfterMonths ?? '' })}</div>}</td>
                    <td className="small">{[r.latePaymentPercentMonth !== null ? t('{a} % a month started', { a: r.latePaymentPercentMonth }) : null,
                      r.latePaymentPercentDay !== null ? (r.dailyFromDay ? t('{a} % a day from the {b}th', { a: r.latePaymentPercentDay, b: r.dailyFromDay }) : t('{a} % a day', { a: r.latePaymentPercentDay })) : null]
                      .filter(Boolean).join(' · ') || '—'}</td>
                    <td className="small">{r.legalBasis}{!r.verified && <>{' '}<span className="badge warn">{t('To confirm')}</span></>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <p className="hint">{t('Penalties are estimates at these rates, to check with your tax adviser. A reminder goes out 5 days and 1 day before each due date, then weekly while late, to those who manage the declarations and to your accounting firm.')}</p>
        </div>
      )}

      {paying && <PayDeclarationModal item={paying} currency={cur} onClose={() => setPaying(null)}
        onSave={(body) => run(async () => { await api(`/api/declarations/${paying.month}/${paying.kind}/paid`, { method: 'POST', body }); setPaying(null) }, t('Payment recorded.'))} />}
    </section>
  )
}
