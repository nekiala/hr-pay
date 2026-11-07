import { useEffect, useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import { api, money } from '../api'
import { ErrorText } from '../components/ErrorText'
import { Button } from '../components/Button'
import { t } from '../i18n'

type Row = { userId: number; name: string; registrationNo: string; currency: string; months: number; gross: number; cnss: number; ipr: number; otherDeductions: number; net: number; iprCdf: number }
type Month = { month: string; payslipId: number; number: string; currency: string; gross: number; cnss: number; ipr: number; otherDeductions: number; net: number; taxableCdf: number; iprCdf: number; paidOn: string | null }
type Totals = { currency: string; months: number; gross: number; cnss: number; ipr: number; otherDeductions: number; net: number }
type Statement = { userId: number; name: string | null; registrationNo: string | null; year: number; details: { cnssNumber?: string; taxNumber?: string }; months: Month[]; totals: Totals[]; taxableCdf: number; iprCdf: number }

const lastYearWithPay = () => new Date().getFullYear()

function YearPicker({ year, onChange }: { year: number; onChange: (y: number) => void }) {
  return <label className="month-pick no-print">{t('Year')}{' '}<input type="number" className="narrow-input" min={2000} max={2100} value={year} onChange={(e) => e.target.value.length === 4 && onChange(Number(e.target.value))} /></label>
}

/** HR: everyone's totals for a year. */
export function YearSummariesPage() {
  const [params, setParams] = useSearchParams()
  const year = Number(params.get('year') ?? lastYearWithPay())
  const [rows, setRows] = useState<Row[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => { api<Row[]>(`/api/year-summaries?year=${year}`).then(setRows).catch((e: Error) => setError(e.message)) }, [year])

  return (
    <section>
      <h2>{t('Year summary')}</h2>
      <YearPicker year={year} onChange={(y) => setParams({ year: String(y) })} />
      <ErrorText error={error} />
      <p className="hint">{t('Closed months only. Open an employee for their annual pay statement, which they can also see under My payslips.')}</p>
      {rows && (rows.length === 0 ? <p className="muted">{t('No closed payroll in')}{' '}{year}.</p> : (
        <div className="card">
          <div className="table-wrap">
            <table>
              <thead><tr><th>{t('Employee')}</th><th>{t('Months')}</th><th>{t('Gross')}</th><th>{t('CNSS')}</th><th>{t('IPR')}</th><th>{t('Other deductions')}</th><th>{t('Net')}</th><th></th></tr></thead>
              <tbody>
                {rows.map((r) => (
                  <tr key={`${r.userId}-${r.currency}`}>
                    <td>{r.name} <span className="muted">{r.registrationNo}</span></td><td>{r.months}</td>
                    <td>{money(r.gross, r.currency)}</td><td>{money(r.cnss, r.currency)}</td><td>{money(r.ipr, r.currency)}</td>
                    <td>{money(r.otherDeductions, r.currency)}</td><td><strong>{money(r.net, r.currency)}</strong></td>
                    <td><Link to={`/payroll/year/${r.userId}?year=${year}`}>{t('Statement')}</Link></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <a className="button secondary" href={`/api/year-summaries.csv?year=${year}`}>{t('Download CSV')}</a>
        </div>
      ))}
    </section>
  )
}

/** One employee's annual pay statement: their own (/my/year) or, for HR, anyone's. */
export function AnnualStatementPage() {
  const { userId } = useParams()
  const [params, setParams] = useSearchParams()
  const year = Number(params.get('year') ?? lastYearWithPay())
  const [s, setS] = useState<Statement | null>(null)
  const [employer, setEmployer] = useState<{ name: string; address: string | null; taxNumber: string | null } | null>(null)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => {
    const url = userId ? `/api/year-summaries/${userId}?year=${year}` : `/api/my/year-summary?year=${year}`
    api<Statement>(url).then(setS).catch((e: Error) => setError(e.message))
  }, [userId, year])
  useEffect(() => { api<typeof employer>('/api/company').then(setEmployer).catch(() => undefined) }, [])

  return (
    <section className="narrow">
      <p className="no-print">
        {userId ? <Link to={`/payroll/year?year=${year}`}>{t('← Year summary')}</Link> : <Link to="/my/payslips">{t('← My payslips')}</Link>}
        {' · '}<Button className="link" onClick={() => window.print()}>{t('Print or save as PDF')}</Button>
      </p>
      <YearPicker year={year} onChange={(y) => setParams({ year: String(y) })} />
      <ErrorText error={error} />
      {s && (
        <div className="card payslip">
          <h2>{t('Annual pay statement')}{' '}{s.year}</h2>
          {employer && <p>{employer.name}{employer.address && ` · ${employer.address}`}{employer.taxNumber && t(' · NIF {a}', { a: employer.taxNumber })}</p>}
          <p><strong>{s.name}</strong> {s.registrationNo && <span className="muted">{s.registrationNo}</span>}
            {s.details.cnssNumber && <>{' '}{t('· CNSS')}{' '}{s.details.cnssNumber}</>}{s.details.taxNumber && <>{' '}{t('· NIF')}{' '}{s.details.taxNumber}</>}</p>
          {s.months.length === 0 ? <p className="muted">{t('No closed payslip in')}{' '}{s.year}.</p> : (
            <>
              <div className="table-wrap">
                <table>
                  <thead><tr><th>{t('Month')}</th><th>{t('Gross')}</th><th>{t('CNSS')}</th><th>{t('IPR')}</th><th>{t('Other')}</th><th>{t('Net')}</th><th>{t('Paid')}</th></tr></thead>
                  <tbody>
                    {s.months.map((m) => (
                      <tr key={m.payslipId}>
                        <td><Link to={`/payslips/${m.payslipId}`}>{m.month}</Link></td>
                        <td>{money(m.gross, m.currency)}</td><td>{money(m.cnss, m.currency)}</td><td>{money(m.ipr, m.currency)}</td>
                        <td>{money(m.otherDeductions, m.currency)}</td><td><strong>{money(m.net, m.currency)}</strong></td>
                        <td>{m.paidOn ?? <span className="muted">—</span>}</td>
                      </tr>
                    ))}
                  </tbody>
                  <tfoot>
                    {s.totals.map((tItem) => (
                      <tr key={tItem.currency}>
                        <td><strong>{t('Total')}{s.totals.length > 1 && ` ${tItem.currency}`}</strong></td>
                        <td>{money(tItem.gross, tItem.currency)}</td><td>{money(tItem.cnss, tItem.currency)}</td><td>{money(tItem.ipr, tItem.currency)}</td>
                        <td>{money(tItem.otherDeductions, tItem.currency)}</td><td><strong>{money(tItem.net, tItem.currency)}</strong></td><td></td>
                      </tr>
                    ))}
                  </tfoot>
                </table>
              </div>
              <p className="small">{t('Taxable income for IPR over the year:')}{' '}{money(s.taxableCdf, 'CDF')}{t('. IPR withheld:')}{' '}{money(s.iprCdf, 'CDF')}.</p>
            </>
          )}
        </div>
      )}
    </section>
  )
}
