import { useEffect, useState } from 'react'
import { api, money } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'
import { UnitSelect } from '../components/OrgCard'
import type { OrgUnit } from '../org'
import { localCurrency, t } from '../i18n'

function lastMonth() {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
}

type CostRow = { id: number; name: string; costCentre: string | null; headcount: number; payslips: number; grossUsd: number; netUsd: number; employerUsd: number; costUsd: number; costCdf: number | null }
type CostReport = { month: string; runStatus: string | null; usdCdf: number | null; directions: CostRow[]; units: CostRow[]; total: CostRow }

export default function ReportsPage() {
  const { can } = useAuth()
  const [month, setMonth] = useState(lastMonth())
  const [unit, setUnit] = useState('')
  const [units, setUnits] = useState<OrgUnit[]>([])
  useEffect(() => { api<OrgUnit[]>('/api/org/units').then(setUnits).catch(() => undefined) }, [])
  const q = `month=${month}${unit ? `&unitId=${unit}` : ''}`
  return (
    <section>
      <h2>{t('Reports')}</h2>
      <p className="muted">{t('Excel or CSV. The current month runs to today. Each download is recorded in the audit log.')}</p>
      <div className="grid2">
        <label>{t('Month')}<input type="month" value={month} onChange={(e) => setMonth(e.target.value)} /></label>
        <label>{t('Unit (with the units below it)')}<UnitSelect units={units} value={unit} placeholder={t('Everyone')} onChange={setUnit} /></label>
      </div>
      {can('report.attendance.export') && (
        <div className="card">
          <h3>{t('Attendance')}</h3>
          <p className="hint">{can('attendance.read.all') ? t('All active employees.') : t('Your team: everyone in the units you head, and below.')}{' '}{t('Paid and worked hours, late days, absences, leave, overtime, with each person\'s Direction and unit.')}</p>
          <div className="row">
            <a className="button" href={`/api/reports/attendance.xlsx?${q}`}>{t('One row per employee (Excel)')}</a>
            <a className="button secondary" href={`/api/reports/attendance.xlsx?${q}&detail=true`}>{t('One row per day (Excel)')}</a>
            <a className="link" href={`/api/reports/attendance.csv?${q}`}>{t('CSV')}</a>
            <a className="link" href={`/api/reports/attendance.csv?${q}&detail=true`}>{t('CSV per day')}</a>
          </div>
        </div>
      )}
      {can('report.finance.export') && can('salary.read') && (
        <div className="card">
          <h3>{t('Finance')}</h3>
          <p className="hint">{t('Gross salary earned to date and approved overtime pay, per employee, before taxes and deductions, with Direction, unit and cost centre.')}</p>
          <div className="row">
            <a className="button" href={`/api/reports/finance.xlsx?${q}`}>{t('Download (Excel)')}</a>
            <a className="link" href={`/api/reports/finance.csv?${q}`}>{t('CSV')}</a>
          </div>
        </div>
      )}
      {can('payslip.read.all') && <CostCard month={month} />}
    </section>
  )
}

function CostCard({ month }: { month: string }) {
  const [data, setData] = useState<CostReport | null>(null)
  const [detail, setDetail] = useState(false)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => {
    setError(null)
    api<CostReport>(`/api/reports/structure?month=${month}`).then(setData).catch((e: Error) => setError(e.message))
  }, [month])
  const rows = data ? (detail ? data.units : data.directions) : []
  const usd = (n: number) => money(n, 'USD')
  return (
    <div className="card">
      <h3>{t('Headcount and payroll cost by Direction')}</h3>
      <p className="hint">{t('Headcount is the active people at the end of the month. Cost is gross pay plus employer charges from that month\'s payslips, in USD')}{data?.usdCdf ? t(' and CDF at the payroll rate of {a}', { a: data.usdCdf }) : ''}.
        {data && !data.runStatus && t(' No payroll has been calculated for this month, so only headcounts are shown.')}
        {data?.runStatus && data.runStatus !== 'CLOSED' && t(' The payroll for this month is not closed yet.')}
      </p>
      <label className="check"><input type="checkbox" checked={detail} onChange={(e) => setDetail(e.target.checked)} />{t('Show every unit')}</label>
      <ErrorText error={error} />
      {data && (
        <div className="table-wrap">
          <table className="lines">
            <thead><tr><th>{detail ? t('Unit') : t('Direction')}</th>{detail && <th>{t('Cost centre')}</th>}<th className="num">{t('People')}</th><th className="num">{t('Payslips')}</th><th className="num">{t('Gross')}</th><th className="num">{t('Employer charges')}</th><th className="num">{t('Total cost')}</th>{data.usdCdf && <th className="num">{t('Total cost (CDF)')}</th>}</tr></thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.id}><td>{r.name}</td>{detail && <td>{r.costCentre ?? ''}</td>}<td className="num">{r.headcount}</td><td className="num">{r.payslips}</td>
                  <td className="num">{usd(r.grossUsd)}</td><td className="num">{usd(r.employerUsd)}</td><td className="num">{usd(r.costUsd)}</td>
                  {data.usdCdf && <td className="num">{r.costCdf === null ? '' : money(r.costCdf, localCurrency())}</td>}</tr>
              ))}
              <tr className="subtotal"><td>{t('Total')}</td>{detail && <td />}<td className="num">{data.total.headcount}</td><td className="num">{data.total.payslips}</td>
                <td className="num">{usd(data.total.grossUsd)}</td><td className="num">{usd(data.total.employerUsd)}</td><td className="num">{usd(data.total.costUsd)}</td>
                {data.usdCdf && <td className="num">{data.total.costCdf === null ? '' : money(data.total.costCdf, localCurrency())}</td>}</tr>
            </tbody>
          </table>
        </div>
      )}
      <p className="row"><a className="button secondary" href={`/api/reports/structure.xlsx?month=${month}`}>{t('Download (Excel)')}</a>
        <a className="link" href={`/api/reports/structure.csv?month=${month}`}>{t('CSV')}</a></p>
    </div>
  )
}
