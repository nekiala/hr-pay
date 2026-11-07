import { useState } from 'react'
import { useAuth } from '../auth'

function lastMonth() {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
}

export default function ReportsPage() {
  const { can } = useAuth()
  const [month, setMonth] = useState(lastMonth())
  return (
    <section className="narrow">
      <h2>Reports</h2>
      <p className="muted">Spreadsheets (CSV) that open in Excel. The current month runs to today. Each download is recorded in the audit log.</p>
      <label>Month<input type="month" value={month} onChange={(e) => setMonth(e.target.value)} /></label>
      {can('report.attendance.export') && (
        <div className="card">
          <h3>Attendance</h3>
          <p className="hint">{can('attendance.read.all') ? 'All active employees.' : 'Your team.'} Paid and worked hours, late days, absences, leave, overtime.</p>
          <div className="row">
            <a className="button" href={`/api/reports/attendance.csv?month=${month}`}>One row per employee</a>
            <a className="button secondary" href={`/api/reports/attendance.csv?month=${month}&detail=true`}>One row per day</a>
          </div>
        </div>
      )}
      {can('report.finance.export') && can('salary.read') && (
        <div className="card">
          <h3>Finance</h3>
          <p className="hint">Gross salary earned to date and approved overtime pay, per employee, before taxes and deductions.</p>
          <a className="button" href={`/api/reports/finance.csv?month=${month}`}>Download</a>
        </div>
      )}
    </section>
  )
}
