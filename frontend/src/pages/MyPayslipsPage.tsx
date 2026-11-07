import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api, money, type PayslipSummary } from '../api'
import { ErrorText } from '../components/ErrorText'
import { t } from '../i18n'

export default function MyPayslipsPage() {
  const [slips, setSlips] = useState<PayslipSummary[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => { api<PayslipSummary[]>('/api/my/payslips').then(setSlips).catch((e: Error) => setError(e.message)) }, [])

  return (
    <section className="narrow">
      <h2>{t('My payslips')}</h2>
      <p><Link to="/my/year">{t('Annual pay statement')}</Link></p>
      <ErrorText error={error} />
      {slips?.length === 0 && <p className="muted">{t('No payslips yet. They appear here once HR closes the month.')}</p>}
      {slips && slips.length > 0 && (
        <div className="card">
          <table>
            <thead><tr><th>{t('Payslip')}</th><th>{t('Gross')}</th><th>{t('Net')}</th><th></th></tr></thead>
            <tbody>
              {slips.map((s) => (
                <tr key={s.id}>
                  <td>{s.number}</td><td>{money(s.gross, s.currency)}</td><td><strong>{money(s.net, s.currency)}</strong></td>
                  <td><Link to={`/payslips/${s.id}`}>{t('Open')}</Link></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}
