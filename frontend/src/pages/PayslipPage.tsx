import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api, money, type Employer, type Payslip, type PayslipLine, type PayslipSettings } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'
import { Button } from '../components/Button'

const monthName = (iso: string) => new Date(iso + 'T00:00:00').toLocaleDateString(undefined, { month: 'long', year: 'numeric' })
const hours = (min: number) => `${Math.floor(min / 60)} h ${String(min % 60).padStart(2, '0')}`
const num = (n: number | null, digits = 2) => (n === null ? '' : new Intl.NumberFormat(undefined, { maximumFractionDigits: digits }).format(n))
type YearToDate = { year: number; months: number; currency: string; gross: number; cnss: number; ipr: number; net: number; otherCurrencies: boolean }
const MARITAL: Record<string, string> = { S: 'single', M: 'married', D: 'divorced', W: 'widowed' }
const PAYMENT: Record<string, string> = { BANK: 'Bank transfer', MOBILE_MONEY: 'Mobile money', CASH: 'Cash' }

export default function PayslipPage() {
  const { id } = useParams()
  const { can } = useAuth()
  const [slip, setSlip] = useState<Payslip | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [ytd, setYtd] = useState<YearToDate | null>(null)
  const [employer, setEmployer] = useState<Employer | null>(null)
  const [brand, setBrand] = useState<PayslipSettings | null>(null)
  useEffect(() => {
    api<Employer>('/api/company').then(setEmployer).catch(() => undefined)
    api<PayslipSettings>('/api/company/payslip-settings').then(setBrand).catch(() => undefined)
  }, [])
  useEffect(() => {
    api<Payslip>(`/api/payslips/${id}`).then(setSlip).catch((e: Error) => setError(e.message))
    api<YearToDate>(`/api/payslips/${id}/year-to-date`).then(setYtd).catch(() => undefined)
  }, [id])

  if (error) return <section><ErrorText error={error} /></section>
  if (!slip) return <section><p className="muted">Loading…</p></section>

  const s = slip.snapshot
  const cur = slip.currency
  const of = (kind: PayslipLine['kind']) => slip.lines.filter((l) => l.kind === kind)
  const staff = can('payroll.run') || can('payroll.approve') || can('payslip.read.all')
  const rateCell = (l: PayslipLine) => (l.rate === null ? '' : l.kind === 'EARNING' && l.elementCode !== 'HOUSING' ? num(l.rate, 4) : `${num(l.rate, 3)}%`)
  const section = (title: string, lines: PayslipLine[], total: number, totalLabel: string) => (
    <>
      <tr className="group"><th colSpan={4}>{title}</th></tr>
      {lines.map((l, i) => (
        <tr key={title + i}>
          <td>{l.label}</td><td className="num">{num(l.base)}</td><td className="num">{rateCell(l)}</td><td className="num">{money(l.amount, cur)}</td>
        </tr>
      ))}
      <tr className="subtotal"><td colSpan={3}>{totalLabel}</td><td className="num">{money(total, cur)}</td></tr>
    </>
  )

  return (
    <section className="narrow payslip">
      <p className="no-print">
        {staff ? <Link to="/payroll">← Payroll</Link> : <Link to="/my/payslips">← My payslips</Link>}
        {' · '}<a href={`/api/payslips/${id}.pdf`} target="_blank" rel="noreferrer">Download PDF</a>
        {' · '}<Button className="link" onClick={() => window.print()}>Print</Button>
      </p>
      {slip.runStatus !== 'CLOSED' && <p className="notice">Draft: this payroll is {slip.runStatus.toLowerCase()} and not yet closed.</p>}
      {slip.warnings.length > 0 && staff && (
        <div className="notice no-print"><strong>To check</strong><ul>{slip.warnings.map((w) => <li key={w}>{w}</li>)}</ul></div>
      )}

      <div className="card">
        {employer && (
          <div className="payslip-employer small">
            {brand?.hasLogo && <img src="/api/company/logo" alt="" />}
            <div>
              <strong>{employer.name}</strong>{brand?.legalForm ? ` · ${brand.legalForm}` : ''}<br />
              {employer.address && <>{employer.address}<br /></>}
              {[brand?.phone, brand?.email, brand?.website].filter(Boolean).join(' · ')}
              {(brand?.phone || brand?.email || brand?.website) && <br />}
              <span className="muted">{[['RCCM', employer.rccm], ['Id. Nat.', employer.nationalId], ['NIF', employer.taxNumber], ['CNSS', employer.cnssNumber]]
                .filter(([, v]) => v).map(([k, v]) => `${k} ${v}`).join(' · ')}</span>
            </div>
          </div>
        )}
        <div className="payslip-head">
          <div>
            <h2>Payslip · {monthName(slip.month)}</h2>
            <p className="muted">{slip.number ?? 'Not numbered yet'}</p>
          </div>
          <div className="right">
            <strong>{String(s.name)}</strong><br />
            Matricule {String(s.registrationNo ?? '')}<br />
            {String(s.grade ?? '')}{s.occupation ? ` · ${String(s.occupation)}` : ''}
            {s.unit ? <><br />{String(s.unit)}{s.direction && s.direction !== s.unit ? ` · ${String(s.direction)}` : ''}{s.costCentre ? ` · CC ${String(s.costCentre)}` : ''}</> : null}
          </div>
        </div>
        <div className="grid3 small">
          <div>Contract: {String(s.contractType ?? '').replace('_', ' ').toLowerCase()} since {String(s.contractStart ?? '')}</div>
          <div>CNSS no.: {String(s.cnssNumber ?? '—')} · NIF: {String(s.taxNumber ?? '—')}</div>
          <div>Hired: {String(s.hireDate ?? s.contractStart ?? '—')}{s.seniority ? ` · seniority ${Number(s.seniorityYears)} yr ${Number(s.seniorityMonths)} mo` : ''}</div>
          <div title={s.maritalCode ? MARITAL[String(s.maritalCode)] : undefined}>Marital status: {String(s.maritalCode ?? '—')}</div>
          <div>Children (family allowances): {String(s.childrenInCharge ?? 0)}</div>
          <div>Hours paid: {hours(slip.paidMinutes)} of {hours(slip.scheduledMinutes)}</div>
          <div>Days worked: {slip.daysWorked}</div>
          <div>Monthly basic: {money(Number(s.basicMonthly), cur)}</div>
        </div>

        <div className="table-wrap">
          <table className="lines">
            <thead><tr><th>Item</th><th className="num">Base</th><th className="num">Rate</th><th className="num">Amount</th></tr></thead>
            <tbody>
              {section('Earnings', of('EARNING'), slip.gross, 'Gross pay')}
              {section('Deductions', of('DEDUCTION'), slip.employeeDeductions, 'Total deductions')}
              <tr className="net"><td colSpan={3}>Net pay</td><td className="num">{money(slip.net, cur)}</td></tr>
            </tbody>
          </table>
        </div>
        <p className="small">
          Paid by {PAYMENT[String(s.paymentMethod)] ?? String(s.paymentMethod)}
          {s.paymentMethod === 'BANK' && s.bankName ? `: ${String(s.bankName)} ${String(s.bankAccount ?? '')}` : ''}
          {s.paymentMethod === 'MOBILE_MONEY' ? `: ${String(s.mobileMoneyOperator ?? '')} ${String(s.mobileMoneyNumber ?? '')}` : ''}
          {cur === 'USD' && ` · net in CDF: ${new Intl.NumberFormat().format(Math.round(slip.net * slip.usdCdf))} CDF at the BCC rate of ${slip.rateDate} ($1 = ${slip.usdCdf} CDF)`}
        </p>

        {ytd && (
          <div className="table-wrap">
            <table className="lines">
              <thead><tr><th>Year to date {ytd.year} ({ytd.months} month{ytd.months === 1 ? '' : 's'})</th><th className="num">Gross</th><th className="num">CNSS</th><th className="num">IPR</th><th className="num">Net</th></tr></thead>
              <tbody><tr><td>Total</td><td className="num">{money(ytd.gross, ytd.currency)}</td><td className="num">{money(ytd.cnss, ytd.currency)}</td>
                <td className="num">{money(ytd.ipr, ytd.currency)}</td><td className="num">{money(ytd.net, ytd.currency)}</td></tr></tbody>
            </table>
            {ytd.otherCurrencies && <p className="hint">Months paid in another currency are not included.</p>}
          </div>
        )}

        <details open={staff}>
          <summary>Employer charges (not deducted from pay)</summary>
          <table className="lines">
            <tbody>
              {of('EMPLOYER_CHARGE').filter((l) => l.amount > 0).map((l, i) => (
                <tr key={i}><td>{l.label}</td><td className="num">{num(l.base)}</td><td className="num">{num(l.rate, 3)}%</td><td className="num">{money(l.amount, cur)}</td></tr>
              ))}
              <tr className="subtotal"><td colSpan={3}>Total employer charges</td><td className="num">{money(slip.employerCharges, cur)}</td></tr>
            </tbody>
          </table>
        </details>
        <p className="hint">Taxable income for IPR: {money(slip.taxableBase, cur)} ({new Intl.NumberFormat().format(slip.taxableBaseCdf)} CDF). CNSS base: {money(slip.cnssBase, cur)}.</p>
      </div>
    </section>
  )
}
