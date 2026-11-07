import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api, money } from '../api'
import { ErrorText } from '../components/ErrorText'
import { METHOD, type PayMethodKind } from './PaymentsPage'

type Line = { payslipId: number; userId: number; beneficiary: string; registrationNo: string | null; account: string | null; amount: number; currency: string }
type View = {
  month: string
  batch: { id: number; method: PayMethodKind; provider: string; currency: string; total: number; status: string; paidOn: string | null; reference: string | null }
  lines: Line[]
}
type Employer = { name: string; address: string | null; bankName: string | null; bankAccount: string | null }

/** A payment file on screen; for cash, the list people sign when they collect their pay. */
export default function PaymentFilePage() {
  const { id } = useParams()
  const [view, setView] = useState<View | null>(null)
  const [employer, setEmployer] = useState<Employer | null>(null)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => {
    api<View>(`/api/payment-batches/${id}`).then(setView).catch((e: Error) => setError(e.message))
    api<Employer>('/api/company').then(setEmployer).catch(() => undefined)
  }, [id])

  if (!view) return <section><ErrorText error={error} /></section>
  const b = view.batch
  const cash = b.method === 'CASH'
  return (
    <section className="narrow">
      <p className="no-print"><Link to={`/payroll/payments?month=${view.month}`}>← Payments {view.month}</Link>
        {' · '}<button className="link" onClick={() => window.print()}>Print or save as PDF</button></p>
      <div className="card payslip">
        <h2>{cash ? 'Cash pay list' : 'Payment file'} · salaries {view.month}</h2>
        <p>{employer?.name}{employer?.address && <> · {employer.address}</>}</p>
        <p className="muted">File #{b.id} · {METHOD[b.method]}{!cash && <> · {b.provider}</>} · {b.status === 'PAID' ? `paid on ${b.paidOn}${b.reference ? `, ref. ${b.reference}` : ''}` : b.status.toLowerCase()}</p>
        {!cash && b.method === 'BANK' && employer?.bankAccount && <p className="small">Debit account: {employer.bankName} {employer.bankAccount}</p>}
        <table>
          <thead><tr><th>No</th><th>Employee</th><th>{cash ? 'Reg. no' : b.method === 'BANK' ? 'Account' : 'Number'}</th><th>Amount</th>{cash && <th className="signature">Signature</th>}</tr></thead>
          <tbody>
            {view.lines.map((l, i) => (
              <tr key={l.payslipId}>
                <td>{i + 1}</td><td>{l.beneficiary}</td><td>{cash ? l.registrationNo : l.account}</td><td>{money(l.amount, l.currency)}</td>
                {cash && <td className="signature"></td>}
              </tr>
            ))}
          </tbody>
          <tfoot><tr><td></td><td><strong>Total ({view.lines.length})</strong></td><td></td><td><strong>{money(b.total, b.currency)}</strong></td>{cash && <td></td>}</tr></tfoot>
        </table>
        {cash && <p className="small">Paid by (name and signature): ____________________ &nbsp; Date: ____________</p>}
      </div>
    </section>
  )
}
