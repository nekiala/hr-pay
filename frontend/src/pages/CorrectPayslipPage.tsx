import { useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { api, money, type Correction, type CorrectionMode, type CorrectionPreview, type PayElement } from '../api'
import { Button, Form } from '../components/Button'
import { ErrorText } from '../components/ErrorText'
import { locale, t } from '../i18n'

type Extra = { elementId: string; amount: string; note: string }

const monthName = (iso: string) => new Date(iso + 'T00:00:00').toLocaleDateString(locale(), { month: 'long', year: 'numeric' })

const MODES: { mode: CorrectionMode; title: string; text: string }[] = [
  { mode: 'COMPLEMENTARY', title: t('Complementary payslip of that month'), text: t('A payslip with the difference, numbered and paid once Finance approves it; the month\'s declarations include it.') },
  { mode: 'NEXT_PAYROLL', title: t('In the next payroll'), text: t('The difference in earnings is added to the next open payroll as back pay (or taken back), and taxed there.') },
  { mode: 'REVERSAL', title: t('Cancel the whole payslip'), text: t('A complementary payslip that takes back everything, loan repayments included. What the person owes becomes an advance to recover.') },
]

/** Correcting a closed payslip: work it out again, see the difference, choose how it is settled. */
export default function CorrectPayslipPage() {
  const { id } = useParams()
  const navigate = useNavigate()
  const [preview, setPreview] = useState<CorrectionPreview | null>(null)
  const [elements, setElements] = useState<PayElement[]>([])
  const [extra, setExtra] = useState<Extra[]>([])
  const [mode, setMode] = useState<CorrectionMode>('COMPLEMENTARY')
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string | null>(null)

  const extras = () => extra.filter((x) => x.elementId && x.amount)
    .map((x) => ({ elementId: Number(x.elementId), amount: Number(x.amount.replace(',', '.')), note: x.note || null }))

  const load = async () => {
    setError(null)
    try {
      setPreview(await api<CorrectionPreview>(`/api/payslips/${id}/correction/preview`, { method: 'POST', body: { extra: extras() } }))
    } catch (e) {
      setError((e as Error).message)
    }
  }
  useEffect(() => {
    void load()
    api<PayElement[]>('/api/pay-elements').then((all) => setElements(all.filter((e) => e.active && e.kind !== 'EMPLOYER_CHARGE'))).catch(() => undefined)
  }, [id])
  useEffect(() => { if (preview?.carriedBefore) setMode('NEXT_PAYROLL') }, [preview?.carriedBefore])

  const submit = async () => {
    setError(null)
    const c = await api<Correction>(`/api/payslips/${id}/corrections`, { method: 'POST', body: { mode, reason, extra: mode === 'REVERSAL' ? [] : extras() } })
    navigate(c.complementaryId ? `/payslips/${c.complementaryId}` : '/payroll/corrections')
  }

  const cur = preview?.currency ?? 'USD'
  const changed = preview?.lines.filter((l) => Math.abs(l.change) >= 0.01) ?? []
  return (
    <section>
      <p><Link to={`/payslips/${id}`}>{t('← Back to the payslip')}</Link></p>
      <h2>{t('Correct a closed payslip')}{preview ? ` · ${preview.name}, ${monthName(preview.month)}` : ''}</h2>
      <p className="muted">{t('A closed payslip is never changed. It is worked out again with today\'s data (salary, attendance, the month\'s entries and the lines you add below), and the difference is settled the way you choose.')}</p>
      <ErrorText error={error} />
      {preview?.pending && <p className="notice">{t('A complementary payslip for this payslip waits for approval: approve or discard it first.')} <Link to="/payroll/corrections">{t('Open the corrections')}</Link></p>}

      <div className="card">
        <h3>{t('Lines to add for the correction')}</h3>
        {extra.map((x, i) => (
          <div className="row" key={i}>
            <select value={x.elementId} onChange={(e) => setExtra(extra.map((y, j) => (j === i ? { ...y, elementId: e.target.value } : y)))}>
              <option value="">{t('Choose an element')}</option>
              {elements.map((e) => <option key={e.id} value={e.id}>{e.name}</option>)}
            </select>
            <input className="narrow-input" inputMode="decimal" placeholder={cur} value={x.amount} onChange={(e) => setExtra(extra.map((y, j) => (j === i ? { ...y, amount: e.target.value } : y)))} />
            <input placeholder={t('Note')} value={x.note} onChange={(e) => setExtra(extra.map((y, j) => (j === i ? { ...y, note: e.target.value } : y)))} />
            <Button className="link" onClick={() => setExtra(extra.filter((_, j) => j !== i))}>{t('Remove')}</Button>
          </div>
        ))}
        <div className="row">
          <Button className="secondary" onClick={() => setExtra([...extra, { elementId: '', amount: '', note: '' }])}>{t('Add a line')}</Button>
          <Button className="secondary" onClick={load}>{t('Work it out again')}</Button>
        </div>
      </div>

      {preview && (
        <div className="card">
          <h3>{t('What changes')}</h3>
          {preview.basicPaid !== null && preview.basicNow !== null && preview.basicPaid !== preview.basicNow && (
            <p className="small">{t('Basic salary on file for the month: {a} (paid with {b}).', { a: money(preview.basicNow, cur), b: money(preview.basicPaid, cur) })}</p>
          )}
          {changed.length === 0 ? <p className="muted">{t('Worked out again today, this payslip does not change.')}</p> : (
            <div className="table-wrap">
              <table>
                <thead><tr><th>{t('Item')}</th><th className="num">{t('Paid')}</th><th className="num">{t('Now')}</th><th className="num">{t('Difference')}</th></tr></thead>
                <tbody>
                  {changed.map((l) => (
                    <tr key={l.kind + l.code}><td>{l.label}</td><td className="num">{money(l.before, cur)}</td><td className="num">{money(l.after, cur)}</td><td className="num"><strong>{money(l.change, cur)}</strong></td></tr>
                  ))}
                  <tr className="net"><td>{t('Net pay')}</td><td className="num">{money(preview.netBefore, cur)}</td><td className="num">{money(preview.netAfter, cur)}</td><td className="num">{money(preview.netChange, cur)}</td></tr>
                </tbody>
              </table>
            </div>
          )}
          {preview.carried !== 0 && <p className="small">{t('Already carried into later payrolls: {a}.', { a: money(preview.carried, cur) })}</p>}
          {preview.warnings.length > 0 && <ul className="small muted">{preview.warnings.map((w) => <li key={w}>{w}</li>)}</ul>}
        </div>
      )}

      <Form className="card" onSubmit={submit}>
        <h3>{t('How to settle it')}</h3>
        {MODES.map((m) => (
          <label key={m.mode} className="check">
            <input type="radio" name="mode" checked={mode === m.mode} onChange={() => setMode(m.mode)}
              disabled={preview?.carriedBefore ? m.mode !== 'NEXT_PAYROLL' : false} />
            <span><strong>{m.title}</strong><br /><span className="small muted">{m.text}</span></span>
          </label>
        ))}
        <label>{t('Reason (kept with the correction)')}<input value={reason} onChange={(e) => setReason(e.target.value)} required maxLength={300} /></label>
        <Button type="submit" disabled={!preview || preview.pending || (mode !== 'REVERSAL' && changed.length === 0)}>{t('Record the correction')}</Button>
      </Form>
    </section>
  )
}
