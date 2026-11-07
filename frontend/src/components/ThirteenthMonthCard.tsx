import { useEffect, useState } from 'react'
import { ApiError, api, money } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from './ErrorText'
import { Button } from './Button'
import { plural, t } from '../i18n'

type Settings = { enabled: boolean; base: 'AVERAGE_BASIC' | 'LAST_BASIC'; fallbackPercent: number; payMonth: number; prorate: boolean }
type Row = { userId: number; name: string; scorePercent: number; scoreSource: 'EVALUATIONS' | 'FALLBACK'; evaluations: number; base: number; currency: string; monthsWorked: number; amount: number }
type Preview = { year: number; settings: Settings; rows: Row[] }

const MONTHS = [t('January'), t('February'), t('March'), t('April'), t('May'), t('June'), t('July'), t('August'), t('September'), t('October'), t('November'), t('December')]

/** HR's view of the 13th month: the rules and what each employee would get for a year. */
export function ThirteenthMonthCard() {
  const { can } = useAuth()
  const editable = can('masterdata.manage')
  const [year, setYear] = useState(new Date().getFullYear())
  const [data, setData] = useState<Preview | null>(null)
  const [form, setForm] = useState<Settings | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)

  const load = (y: number) => api<Preview>(`/api/thirteenth-month?year=${y}`).then((p) => { setData(p); setForm(p.settings) }).catch((e: Error) => setError(e.message))
  useEffect(() => { void load(year) }, [year])

  const save = async () => {
    if (!form || !data) return
    setError(null)
    setMessage(null)
    const changes: [string, string][] = [
      ['THIRTEENTH_ENABLED', String(form.enabled)], ['THIRTEENTH_BASE', form.base], ['THIRTEENTH_FALLBACK_PERCENT', String(form.fallbackPercent)],
      ['THIRTEENTH_PAY_MONTH', String(form.payMonth)], ['THIRTEENTH_PRORATE', String(form.prorate)],
    ]
    const old: Record<string, string> = {
      THIRTEENTH_ENABLED: String(data.settings.enabled), THIRTEENTH_BASE: data.settings.base, THIRTEENTH_FALLBACK_PERCENT: String(data.settings.fallbackPercent),
      THIRTEENTH_PAY_MONTH: String(data.settings.payMonth), THIRTEENTH_PRORATE: String(data.settings.prorate),
    }
    try {
      for (const [key, value] of changes) {
        if (old[key] !== value) await api(`/api/statutory/settings/${key}`, { method: 'PUT', body: { value } })
      }
      setMessage(t('13th month rules saved.'))
      await load(year)
    } catch (e) {
      setError(e instanceof ApiError ? [e.message, ...Object.values(e.fieldErrors)].join(' ') : t('Something went wrong.'))
    }
  }

  return (
    <div className="card">
      <h3>{t('13th month')}</h3>
      <ErrorText error={error} />
      {message && <p className="success">{message}</p>}
      {form && (
        <>
          <p className="hint">{t('Amount = base × the year\'s evaluation score (approved evaluations, weighted by the months each covers), or the fallback % for someone with no approved evaluation')}{form.prorate ? t(', × months under contract ÷ 12') : ''}{t('. It is added to the payslip of the pay month.')}</p>
          <div className="grid3">
            <label className="check"><input type="checkbox" disabled={!editable} checked={form.enabled} onChange={(e) => setForm({ ...form, enabled: e.target.checked })} />{' '}{t('Pay a 13th month')}</label>
            <label>{t('Base')}<select disabled={!editable} value={form.base} onChange={(e) => setForm({ ...form, base: e.target.value as Settings['base'] })}>
                <option value="AVERAGE_BASIC">{t('Average basic over the year')}</option>
                <option value="LAST_BASIC">{t('Last basic of the year')}</option>
              </select>
            </label>
            <label>{t('Paid with the payroll of')}<select disabled={!editable} value={form.payMonth} onChange={(e) => setForm({ ...form, payMonth: Number(e.target.value) })}>
                {MONTHS.map((m, i) => <option key={m} value={i + 1}>{m}</option>)}
              </select>
            </label>
            <label>{t('Fallback score %')}<input type="number" disabled={!editable} min={0} max={100} step={0.01} value={form.fallbackPercent} onChange={(e) => setForm({ ...form, fallbackPercent: Number(e.target.value) })} /></label>
            <label className="check"><input type="checkbox" disabled={!editable} checked={form.prorate} onChange={(e) => setForm({ ...form, prorate: e.target.checked })} />{' '}{t('Prorate by months worked')}</label>
          </div>
          {editable && <Button className="secondary" onClick={() => save()}>{t('Save rules')}</Button>}
        </>
      )}

      <h4>{t('Preview')}</h4>
      <label className="month-pick">{t('Year')}{' '}<input type="number" className="narrow-input" min={2000} max={2100} value={year} onChange={(e) => e.target.value.length === 4 && setYear(Number(e.target.value))} /></label>
      {data && (data.rows.length === 0 ? <p className="muted">{t('Nobody under contract with a salary in')}{' '}{data.year}.</p> : (
        <div className="table-wrap">
          <table>
            <thead><tr><th>{t('Employee')}</th><th>{t('Score')}</th><th>{t('Base')}</th><th>{t('Months')}</th><th>{t('Amount')}</th></tr></thead>
            <tbody>
              {data.rows.map((r) => (
                <tr key={r.userId}>
                  <td>{r.name}</td>
                  <td>{r.scorePercent}% <span className="muted">{r.scoreSource === 'EVALUATIONS' ? plural('({n} evaluation)', '({n} evaluations)', r.evaluations) : t('(fallback)')}</span></td>
                  <td>{money(r.base, r.currency)}</td>
                  <td>{r.monthsWorked}</td>
                  <td><strong>{money(r.amount, r.currency)}</strong></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ))}
    </div>
  )
}
