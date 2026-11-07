import { useState } from 'react'
import { Link } from 'react-router-dom'
import { api, money, type Comparison, type Figures } from '../api'
import { Button, Form } from '../components/Button'
import { ErrorText } from '../components/ErrorText'
import { t } from '../i18n'

const lastMonth = () => { const d = new Date(); d.setDate(1); d.setMonth(d.getMonth() - 1); return d.toISOString().slice(0, 7) }
const STATUS: Record<Comparison['rows'][number]['status'], string> = {
  MATCH: t('Same'), DIFFERENT: t('Different'), ONLY_OURS: t('Only in HR Pay'), ONLY_THEIRS: t('Only in the old payroll'),
}
const BADGE: Record<Comparison['rows'][number]['status'], string> = { MATCH: 'ok', DIFFERENT: 'off', ONLY_OURS: 'warn', ONLY_THEIRS: 'warn' }

/** The parallel run: the old payroll's figures for a month against HR Pay's, person by person. */
export default function ComparePage() {
  const [month, setMonth] = useState(lastMonth())
  const [csv, setCsv] = useState<string | null>(null)
  const [file, setFile] = useState('')
  const [tolerance, setTolerance] = useState('1')
  const [result, setResult] = useState<Comparison | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [only, setOnly] = useState(false)

  const read = (f: File | undefined) => {
    if (!f) return
    setFile(f.name)
    void f.text().then(setCsv)
  }
  const compare = async () => {
    setError(null)
    setResult(await api<Comparison>(`/api/payroll/runs/${month}/compare`, { method: 'POST', body: { csv, tolerance: Number(tolerance.replace(',', '.')) } }))
  }
  const cell = (f: Figures | null, k: keyof Figures, cur: string | null) => (f?.[k] === null || f?.[k] === undefined ? '' : money(f[k] as number, cur ?? ''))
  const rows = result?.rows.filter((r) => !only || r.status !== 'MATCH') ?? []
  const tax = t('IPR')

  return (
    <section>
      <p><Link to="/payroll">{t('← Payroll')}</Link></p>
      <h2>{t('Compare with the old payroll')}</h2>
      <p className="muted">{t('Run both payrolls side by side for two months before relying on HR Pay. Save the old payroll\'s figures as CSV (from the old software or a spreadsheet): one row per person with the registration number, and the gross, CNSS, tax and net you have.')}
        {' '}<a href="/api/payroll/compare-template.csv">{t('Download a template')}</a></p>
      <Form className="card" onSubmit={compare}>
        <div className="row">
          <label className="inline">{t('Month')}<input type="month" value={month} onChange={(e) => setMonth(e.target.value)} required /></label>
          <label className="inline">{t('Old payroll (CSV)')}<input type="file" accept=".csv,text/csv,text/plain" onChange={(e) => read(e.target.files?.[0])} required /></label>
          <label className="inline">{t('Accepted difference')}<input className="narrow-input" inputMode="decimal" value={tolerance} onChange={(e) => setTolerance(e.target.value)} /></label>
          <Button type="submit" disabled={!csv}>{t('Compare')}</Button>
        </div>
        {file && <p className="small muted">{file}</p>}
      </Form>
      <ErrorText error={error} />
      {result && (
        <div className="card">
          {result.warnings.map((w) => <p key={w} className="notice">{w}</p>)}
          <p>
            <span className="badge ok">{t('{a} same', { a: result.matching })}</span>{' '}
            <span className="badge off">{t('{a} different', { a: result.different })}</span>{' '}
            <span className="badge warn">{t('{a} only in HR Pay', { a: result.onlyOurs })}</span>{' '}
            <span className="badge warn">{t('{a} only in the old payroll', { a: result.onlyTheirs })}</span>
          </p>
          <label className="check"><input type="checkbox" checked={only} onChange={(e) => setOnly(e.target.checked)} />{t('Show only the differences')}</label>
          <div className="table-wrap">
            <table>
              <thead>
                <tr><th>{t('Person')}</th><th>{t('Status')}</th><th className="num">{t('Gross')}</th><th className="num">CNSS</th><th className="num">{tax}</th><th className="num">{t('Net')}</th></tr>
              </thead>
              <tbody>
                {rows.map((r) => (
                  <tr key={r.registrationNo + r.status}>
                    <td>{r.name ?? '—'} <span className="muted small">{r.registrationNo}</span></td>
                    <td><span className={`badge ${BADGE[r.status]}`}>{STATUS[r.status]}</span></td>
                    {(['gross', 'cnss', 'tax', 'net'] as const).map((k) => (
                      <td key={k} className="num">
                        {cell(r.ours, k, r.currency)}
                        {r.theirs && <><br /><span className="muted small">{t('old:')} {cell(r.theirs, k, r.currency)}</span></>}
                        {r.difference?.[k] ? <><br /><strong className="small">{cell(r.difference, k, r.currency)}</strong></> : null}
                      </td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </section>
  )
}
