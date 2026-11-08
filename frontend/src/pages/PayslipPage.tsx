import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api, money, type Employer, type Payslip, type PayslipLine, type PayslipSettings } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'
import { Button } from '../components/Button'
import { locale, plural, t } from '../i18n'

const monthName = (iso: string) => new Date(iso + 'T00:00:00').toLocaleDateString(locale(), { month: 'long', year: 'numeric' })
const hours = (min: number) => `${Math.floor(min / 60)} h ${String(min % 60).padStart(2, '0')}`
const num = (n: number | null, digits = 2) => (n === null ? '' : new Intl.NumberFormat(locale(), { maximumFractionDigits: digits }).format(n))
type YearToDate = { year: number; months: number; currency: string; gross: number; cnss: number; ipr: number; net: number; otherCurrencies: boolean }
const MARITAL: Record<string, string> = { S: 'single', M: 'married', D: 'divorced', W: 'widowed' }
const PAYMENT: Record<string, string> = { BANK: t('Bank transfer'), MOBILE_MONEY: t('Mobile money'), CASH: t('Cash') }

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
  if (!slip) return <section><p className="muted">{t('Loading…')}</p></section>

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
    <section className="payslip">
      <p className="no-print">
        {staff ? <Link to="/payroll">{t('← Payroll')}</Link> : <Link to="/my/payslips">{t('← My payslips')}</Link>}
        {' · '}<a href={`/api/payslips/${id}.pdf`} target="_blank" rel="noreferrer">{t('Download PDF')}</a>
        {' · '}<Button className="link" onClick={() => window.print()}>{t('Print')}</Button>
        {can('payroll.run') && slip.runStatus === 'CLOSED' && slip.kind === 'REGULAR' && <>{' · '}<Link to={`/payslips/${id}/correct`}>{t('Correct')}</Link></>}
      </p>
      {slip.kind === 'COMPLEMENTARY' && (
        <p className="notice">{t('Complementary payslip: it corrects payslip {a}.', { a: slip.correctsNumber ?? '' })}
          {' '}{slip.reason}{slip.number === null ? ` ${t('It waits for Finance\'s approval.')}` : ''}
          {staff && slip.correctsId && <>{' '}<Link to={`/payslips/${slip.correctsId}`}>{t('Open the payslip it corrects')}</Link></>}</p>
      )}
      {slip.runStatus !== 'CLOSED' && slip.kind === 'REGULAR' && <p className="notice">{t('Draft: this payroll is')}{' '}{slip.runStatus.toLowerCase()}{' '}{t('and not yet closed.')}</p>}
      {slip.warnings.length > 0 && staff && (
        <div className="notice no-print"><strong>{t('To check')}</strong><ul>{slip.warnings.map((w) => <li key={w}>{w}</li>)}</ul></div>
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
              <span className="muted">{[['RCCM', employer.rccm], [t('Id. Nat.'), employer.nationalId], ['NIF', employer.taxNumber], ['CNSS', employer.cnssNumber]]
                .filter(([, v]) => v).map(([k, v]) => `${k} ${v}`).join(' · ')}</span>
            </div>
          </div>
        )}
        <div className="payslip-head">
          <div>
            <h2>{slip.kind === 'COMPLEMENTARY' ? t('Complementary payslip ·') : t('Payslip ·')}{' '}{monthName(slip.month)}</h2>
            <p className="muted">{slip.number ?? t('Not numbered yet')}</p>
          </div>
          <div className="right">
            <strong>{String(s.name)}</strong><br />{t('Matricule')}{' '}{String(s.registrationNo ?? '')}<br />
            {String(s.grade ?? '')}{s.occupation ? ` · ${String(s.occupation)}` : ''}
            {s.unit ? <><br />{String(s.unit)}{s.direction && s.direction !== s.unit ? ` · ${String(s.direction)}` : ''}{s.costCentre ? t(' · CC {a}', { a: String(s.costCentre) }) : ''}</> : null}
          </div>
        </div>
        <div className="grid3 small">
          <div>{t('Contract:')}{' '}{String(s.contractType ?? '').replace('_', ' ').toLowerCase()}{' '}{t('since')}{' '}{String(s.contractStart ?? '')}</div>
          <div>{t('CNSS no.:')}{' '}{String(s.cnssNumber ?? '—')}{' '}{t('· NIF:')}{' '}{String(s.taxNumber ?? '—')}</div>
          <div>{t('Hired:')}{' '}{String(s.hireDate ?? s.contractStart ?? '—')}{s.seniority ? t(' · seniority {a} yr {b} mo', { a: Number(s.seniorityYears), b: Number(s.seniorityMonths) }) : ''}</div>
          <div title={s.maritalCode ? MARITAL[String(s.maritalCode)] : undefined}>{t('Marital status:')}{' '}{String(s.maritalCode ?? '—')}</div>
          <div>{t('Children (family allowances):')}{' '}{String(s.childrenInCharge ?? 0)}</div>
          <div>{t('Hours paid:')}{' '}{hours(slip.paidMinutes)}{' '}{t('of')}{' '}{hours(slip.scheduledMinutes)}</div>
          <div>{t('Days worked:')}{' '}{slip.daysWorked}</div>
          <div>{t('Monthly basic:')}{' '}{money(Number(s.basicMonthly), cur)}</div>
        </div>

        <div className="table-wrap">
          <table className="lines">
            <thead><tr><th>{t('Item')}</th><th className="num">{t('Base')}</th><th className="num">{t('Rate')}</th><th className="num">{t('Amount')}</th></tr></thead>
            <tbody>
              {section(t('Earnings'), of('EARNING'), slip.gross, t('Gross pay'))}
              {section(t('Deductions'), of('DEDUCTION'), slip.employeeDeductions, t('Total deductions'))}
              <tr className="net"><td colSpan={3}>{t('Net pay')}</td><td className="num">{money(slip.net, cur)}</td></tr>
            </tbody>
          </table>
        </div>
        <p className="small">{t('Paid by')}{' '}{PAYMENT[String(s.paymentMethod)] ?? String(s.paymentMethod)}
          {s.paymentMethod === 'BANK' && s.bankName ? `: ${String(s.bankName)} ${String(s.bankAccount ?? '')}` : ''}
          {s.paymentMethod === 'MOBILE_MONEY' ? `: ${String(s.mobileMoneyOperator ?? '')} ${String(s.mobileMoneyNumber ?? '')}` : ''}
          {cur === 'USD' && t(' · net in CDF: {a} CDF at the BCC rate of {b} ($1 = {c} CDF)', { a: new Intl.NumberFormat(locale()).format(Math.round(slip.net * slip.usdCdf)), b: slip.rateDate, c: slip.usdCdf })}
        </p>

        {ytd && (
          <div className="table-wrap">
            <table className="lines">
              <thead><tr><th>{t('Year to date')}{' '}{ytd.year} ({plural('{n} month', '{n} months', ytd.months)})</th><th className="num">{t('Gross')}</th><th className="num">{t('CNSS')}</th><th className="num">{t('IPR')}</th><th className="num">{t('Net')}</th></tr></thead>
              <tbody><tr><td>{t('Total')}</td><td className="num">{money(ytd.gross, ytd.currency)}</td><td className="num">{money(ytd.cnss, ytd.currency)}</td>
                <td className="num">{money(ytd.ipr, ytd.currency)}</td><td className="num">{money(ytd.net, ytd.currency)}</td></tr></tbody>
            </table>
            {ytd.otherCurrencies && <p className="hint">{t('Months paid in another currency are not included.')}</p>}
          </div>
        )}

        <details open={staff}>
          <summary>{t('Employer charges (not deducted from pay)')}</summary>
          <table className="lines">
            <tbody>
              {of('EMPLOYER_CHARGE').filter((l) => l.amount > 0).map((l, i) => (
                <tr key={i}><td>{l.label}</td><td className="num">{num(l.base)}</td><td className="num">{num(l.rate, 3)}%</td><td className="num">{money(l.amount, cur)}</td></tr>
              ))}
              <tr className="subtotal"><td colSpan={3}>{t('Total employer charges')}</td><td className="num">{money(slip.employerCharges, cur)}</td></tr>
            </tbody>
          </table>
        </details>
        <p className="hint">{t('Taxable income for IPR:')}{' '}{money(slip.taxableBase, cur)} ({new Intl.NumberFormat(locale()).format(slip.taxableBaseCdf)}{' '}{t('CDF). CNSS base:')}{' '}{money(slip.cnssBase, cur)}.</p>
      </div>
    </section>
  )
}
