import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api, money, type PayslipSummary } from '../api'
import { ErrorText } from '../components/ErrorText'

export default function MyPayslipsPage() {
  const [slips, setSlips] = useState<PayslipSummary[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => { api<PayslipSummary[]>('/api/my/payslips').then(setSlips).catch((e: Error) => setError(e.message)) }, [])

  return (
    <section className="narrow">
      <h2>My payslips</h2>
      <ErrorText error={error} />
      {slips?.length === 0 && <p className="muted">No payslips yet. They appear here once HR closes the month.</p>}
      {slips && slips.length > 0 && (
        <div className="card">
          <table>
            <thead><tr><th>Payslip</th><th>Gross</th><th>Net</th><th></th></tr></thead>
            <tbody>
              {slips.map((s) => (
                <tr key={s.id}>
                  <td>{s.number}</td><td>{money(s.gross, s.currency)}</td><td><strong>{money(s.net, s.currency)}</strong></td>
                  <td><Link to={`/payslips/${s.id}`}>Open</Link></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}
