import { useEffect, useMemo, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api, money, type LayoutTarget } from '../api'
import { ErrorText } from '../components/ErrorText'
import { METHOD, type PayMethodKind } from './PaymentsPage'
import { Button } from '../components/Button'
import { t } from '../i18n'
import { useAuth } from '../auth'
import { LayoutDownload } from './ExportLayoutsPage'
import { LINE_STATUS, NtpayPanel, type NtpayBatch } from './NtpayPage'

type Line = { payslipId: number; userId: number; beneficiary: string; registrationNo: string | null; account: string | null; amount: number; currency: string
  unitId: number | null; directionName: string | null; unitName: string | null
  ntpayStatus: string | null; ntpayPayoutReference: string | null; ntpayError: string | null }
type View = {
  month: string; kind: 'PAYROLL' | 'QUINZAINE'
  batch: NtpayBatch & { id: number; method: PayMethodKind; provider: string; currency: string; total: number; status: string; paidOn: string | null; reference: string | null }
  lines: Line[]
  warning?: string | null
}
type Employer = { name: string; address: string | null; bankName: string | null; bankAccount: string | null }

/** A payment file on screen; for cash, the list people sign when they collect their pay. */
export default function PaymentFilePage() {
  const { id } = useParams()
  const { can } = useAuth()
  const [view, setView] = useState<View | null>(null)
  const [employer, setEmployer] = useState<Employer | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [direction, setDirection] = useState('')
  const reload = () => api<View>(`/api/payment-batches/${id}`).then(setView).catch((e: Error) => setError(e.message))
  useEffect(() => {
    void reload()
    api<Employer>('/api/company').then(setEmployer).catch(() => undefined)
  }, [id])

  const directions = useMemo(() => {
    const m = new Map<string, { count: number; total: number }>()
    view?.lines.forEach((l) => {
      const k = l.directionName ?? t('No unit')
      const g = m.get(k) ?? { count: 0, total: 0 }
      m.set(k, { count: g.count + 1, total: g.total + l.amount })
    })
    return [...m.entries()].sort((a, b) => a[0].localeCompare(b[0]))
  }, [view])

  if (!view) return <section><ErrorText error={error} /></section>
  const b = view.batch
  const lines = direction ? view.lines.filter((l) => (l.directionName ?? t('No unit')) === direction) : view.lines
  const shownTotal = lines.reduce((s, l) => s + l.amount, 0)
  const cash = b.method === 'CASH'
  return (
    <section>
      <p className="no-print"><Link to={`/payroll/payments?month=${view.month}${view.kind === 'QUINZAINE' ? '&kind=QUINZAINE' : ''}`}>{t('← Payments')}{' '}{view.month}</Link>
        {' · '}<Button className="link" onClick={() => window.print()}>{t('Print or save as PDF')}</Button>
        {can('payroll.pay') && <>{' '}<LayoutDownload target={view.batch.method as LayoutTarget} path={(l) => `/api/payment-batches/${view.batch.id}/layout/${l}`} /></>}
        {directions.length > 1 && <>{' · '}
          <select value={direction} onChange={(e) => setDirection(e.target.value)}>
            <option value="">{t('Every Direction')}</option>
            {directions.map(([k, g]) => <option key={k} value={k}>{k} ({g.count})</option>)}
          </select></>}
      </p>
      <div className="card payslip">
        <h2>{cash ? t('Cash pay list') : t('Payment file')}{' '}{view.kind === 'QUINZAINE' ? t('· quinzaine') : t('· salaries')}{' '}{view.month}</h2>
        <p>{employer?.name}{employer?.address && <> · {employer.address}</>}</p>
        {direction && <p><strong>{direction}</strong>{' '}{t('only:')}{' '}{lines.length}{' '}{t('of')}{' '}{view.lines.length}{' '}{t('people. The file sent to the provider holds everyone.')}</p>}
        <p className="muted">{t('File #')}{b.id} · {METHOD[b.method]}{!cash && <> · {b.provider}</>} · {b.status === 'PAID' ? t('paid on {a}{b}', { a: b.paidOn, b: b.reference ? `, ref. ${b.reference}` : '' }) : b.status.toLowerCase()}</p>
        {view.warning && <p className="notice no-print">{view.warning}</p>}
        <NtpayPanel batch={b} onChange={reload} />
        {!cash && b.method === 'BANK' && employer?.bankAccount && <p className="small">{t('Debit account:')}{' '}{employer.bankName} {employer.bankAccount}</p>}
        <table>
          <thead><tr><th>{t('No')}</th><th>{t('Employee')}</th><th>{cash ? t('Reg. no') : b.method === 'BANK' ? t('Account') : t('Number')}</th><th>{t('Amount')}</th>{cash && <th className="signature">{t('Signature')}</th>}{b.ntpayStatus && <th className="no-print">{t('NTPay')}</th>}</tr></thead>
          <tbody>
            {lines.map((l, i) => (
              <tr key={l.payslipId}>
                <td>{i + 1}</td><td>{l.beneficiary}</td><td>{cash ? l.registrationNo : l.account}</td><td>{money(l.amount, l.currency)}</td>
                {cash && <td className="signature"></td>}
                {b.ntpayStatus && <td className="no-print">{l.ntpayStatus && <span className={`badge ${LINE_STATUS[l.ntpayStatus]?.[1] ?? ''}`}>{LINE_STATUS[l.ntpayStatus]?.[0] ?? l.ntpayStatus}</span>}
                  {l.ntpayPayoutReference && <div className="small muted">{l.ntpayPayoutReference}</div>}
                  {l.ntpayError && <div className="small warn-text">{l.ntpayError}</div>}</td>}
              </tr>
            ))}
          </tbody>
          <tfoot><tr><td></td><td><strong>{t('Total (')}{lines.length})</strong></td><td></td><td><strong>{money(direction ? shownTotal : b.total, b.currency)}</strong></td>{cash && <td></td>}{b.ntpayStatus && <td className="no-print"></td>}</tr></tfoot>
        </table>
        {!direction && directions.length > 1 && (
          <table className="no-print">
            <thead><tr><th>{t('Direction')}</th><th>{t('People')}</th><th>{t('Amount')}</th></tr></thead>
            <tbody>{directions.map(([k, g]) => <tr key={k}><td>{k}</td><td>{g.count}</td><td>{money(g.total, b.currency)}</td></tr>)}</tbody>
          </table>
        )}
        {cash && <p className="small">{t('Paid by (name and signature): ____________________ \u00a0 Date: ____________')}</p>}
      </div>
    </section>
  )
}
