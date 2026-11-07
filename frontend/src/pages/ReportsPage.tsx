import { useEffect, useState } from 'react'
import { api, money } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'
import { UnitSelect } from '../components/OrgCard'
import type { OrgUnit } from '../org'

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
    <section className="narrow">
      <h2>Reports</h2>
      <p className="muted">Spreadsheets (CSV) that open in Excel. The current month runs to today. Each download is recorded in the audit log.</p>
      <div className="grid2">
        <label>Month<input type="month" value={month} onChange={(e) => setMonth(e.target.value)} /></label>
        <label>Unit (with the units below it)<UnitSelect units={units} value={unit} placeholder="Everyone" onChange={setUnit} /></label>
      </div>
      {can('report.attendance.export') && (
        <div className="card">
          <h3>Attendance</h3>
          <p className="hint">{can('attendance.read.all') ? 'All active employees.' : 'Your team: everyone in the units you head, and below.'} Paid and worked hours, late days, absences, leave, overtime, with each person's Direction and unit.</p>
          <div className="row">
            <a className="button" href={`/api/reports/attendance.csv?${q}`}>One row per employee</a>
            <a className="button secondary" href={`/api/reports/attendance.csv?${q}&detail=true`}>One row per day</a>
          </div>
        </div>
      )}
      {can('report.finance.export') && can('salary.read') && (
        <div className="card">
          <h3>Finance</h3>
          <p className="hint">Gross salary earned to date and approved overtime pay, per employee, before taxes and deductions, with Direction, unit and cost centre.</p>
          <a className="button" href={`/api/reports/finance.csv?${q}`}>Download</a>
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
      <h3>Headcount and payroll cost by Direction</h3>
      <p className="hint">
        Headcount is the active people at the end of the month. Cost is gross pay plus employer charges from that month's payslips,
        in USD{data?.usdCdf ? ` and CDF at the payroll rate of ${data.usdCdf}` : ''}.
        {data && !data.runStatus && ' No payroll has been calculated for this month, so only headcounts are shown.'}
        {data?.runStatus && data.runStatus !== 'CLOSED' && ' The payroll for this month is not closed yet.'}
      </p>
      <label className="check"><input type="checkbox" checked={detail} onChange={(e) => setDetail(e.target.checked)} />Show every unit</label>
      <ErrorText error={error} />
      {data && (
        <div className="table-wrap">
          <table className="lines">
            <thead><tr><th>{detail ? 'Unit' : 'Direction'}</th>{detail && <th>Cost centre</th>}<th className="num">People</th><th className="num">Payslips</th><th className="num">Gross</th><th className="num">Employer charges</th><th className="num">Total cost</th>{data.usdCdf && <th className="num">Total cost (CDF)</th>}</tr></thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.id}><td>{r.name}</td>{detail && <td>{r.costCentre ?? ''}</td>}<td className="num">{r.headcount}</td><td className="num">{r.payslips}</td>
                  <td className="num">{usd(r.grossUsd)}</td><td className="num">{usd(r.employerUsd)}</td><td className="num">{usd(r.costUsd)}</td>
                  {data.usdCdf && <td className="num">{r.costCdf === null ? '' : money(r.costCdf, 'CDF')}</td>}</tr>
              ))}
              <tr className="subtotal"><td>Total</td>{detail && <td />}<td className="num">{data.total.headcount}</td><td className="num">{data.total.payslips}</td>
                <td className="num">{usd(data.total.grossUsd)}</td><td className="num">{usd(data.total.employerUsd)}</td><td className="num">{usd(data.total.costUsd)}</td>
                {data.usdCdf && <td className="num">{data.total.costCdf === null ? '' : money(data.total.costCdf, 'CDF')}</td>}</tr>
            </tbody>
          </table>
        </div>
      )}
      <p><a className="button secondary" href={`/api/reports/structure.csv?month=${month}`}>Download (CSV)</a></p>
    </div>
  )
}
