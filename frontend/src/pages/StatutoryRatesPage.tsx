import { useEffect, useState } from 'react'
import { ApiError, api } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'
import { Modal } from '../components/Modal'
import { Button, Form } from '../components/Button'
import { t, locale } from '../i18n'

type RateType = { code: string; name: string; unit: 'PERCENT' | 'COUNT' | 'CDF'; description: string; value: number | null; effectiveFrom: string | null; nextChange: string | null; nextValue: number | null }
type RateEntry = { id: number; code: string; effectiveFrom: string; value: number; note: string | null; createdBy: string; createdAt: string }
type Band = { lowerBound: number; upperBound: number | null; ratePercent: number }
type Overview = { on: string; rates: RateType[]; ipr: { effectiveFrom: string; brackets: Band[] }; iprDates: string[]; settings: Record<string, string> }
type FxRate = { id: number; currency: string; rateDate: string; cdfPerUnit: number; source: string; sourceUrl: string | null; enteredBy: string | null; fetchedAt: string }
type FxOverview = { today: string; latest: FxRate | null; stale: boolean; recent: FxRate[] }
type IprResult = { beforeReduction: number; reduction: number; capped: number; tax: number }

const cdf = (n: number) => new Intl.NumberFormat(locale(), { maximumFractionDigits: 2 }).format(n) + ' CDF'
const show = (r: { unit: RateType['unit'] }, v: number | null) => (v === null ? '—' : r.unit === 'PERCENT' ? `${v}%` : r.unit === 'CDF' ? cdf(v) : String(v))
const today = () => new Date().toISOString().slice(0, 10)
const firstOfNextMonth = () => { const d = new Date(); return new Date(Date.UTC(d.getFullYear(), d.getMonth() + 1, 1)).toISOString().slice(0, 10) }

export default function StatutoryRatesPage() {
  const { can } = useAuth()
  const editable = can('masterdata.manage')
  const fxEditable = editable || can('salary.write')
  const [on, setOn] = useState(today())
  const [data, setData] = useState<Overview | null>(null)
  const [open, setOpen] = useState<string | null>(null)
  const [history, setHistory] = useState<RateEntry[]>([])
  const [change, setChange] = useState({ effectiveFrom: firstOfNextMonth(), value: '', note: '' })
  const [scale, setScale] = useState<{ effectiveFrom: string; bands: { lowerBound: string; upperBound: string; ratePercent: string }[] } | null>(null)
  const [trial, setTrial] = useState({ taxableMonthlyCdf: '1000000', dependants: '0' })
  const [trialResult, setTrialResult] = useState<IprResult | null>(null)
  const [fx, setFx] = useState<FxOverview | null>(null)
  const [fxEntry, setFxEntry] = useState({ rateDate: today(), cdfPerUnit: '' })
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)

  const load = () => Promise.all([
    api<Overview>(`/api/statutory?on=${on}`).then(setData),
    api<FxOverview>('/api/exchange-rates?currency=USD').then(setFx),
  ]).catch((e: Error) => setError(e.message))
  useEffect(() => { void load() }, [on]) // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => {
    if (open) api<RateEntry[]>(`/api/statutory/rates/${open}`).then(setHistory).catch(() => setHistory([]))
  }, [open, data])

  const run = async (action: () => Promise<unknown>, done: string) => {
    setError(null)
    setMessage(null)
    try {
      await action()
      setMessage(done)
      await load()
      return true
    } catch (e) {
      setError(e instanceof ApiError ? [e.message, ...Object.values(e.fieldErrors)].join(' ') : e instanceof Error ? e.message : t('Something went wrong.'))
      return false
    }
  }

  const editScale = () => data && setScale({
    effectiveFrom: firstOfNextMonth(),
    bands: data.ipr.brackets.map((b) => ({ lowerBound: String(b.lowerBound), upperBound: b.upperBound === null ? '' : String(b.upperBound), ratePercent: String(b.ratePercent) })),
  })
  const setBand = (i: number, patch: Partial<{ lowerBound: string; upperBound: string; ratePercent: string }>) => {
    if (!scale) return
    const bands = scale.bands.map((b, j) => (j === i ? { ...b, ...patch } : b))
    // Keep bands continuous: a band's upper limit is the next band's lower limit.
    if (patch.upperBound !== undefined && bands[i + 1]) bands[i + 1] = { ...bands[i + 1], lowerBound: patch.upperBound }
    setScale({ ...scale, bands })
  }

  return (
    <section>
      <h2>{t('Statutory rates')}</h2>
      <p className="notice">{t('Sample values from memory. A DRC payroll accountant must confirm every rate and the IPR barème before the first real payroll.')}</p>
      <ErrorText error={error} />
      {message && <p className="success">{message}</p>}
      <label className="month-pick">{t('Rates in force on')}{' '}<input type="date" value={on} onChange={(e) => setOn(e.target.value || today())} /></label>

      <div className="card">
        <h3>{t('Exchange rate (BCC)')}</h3>
        {fx?.latest ? (
          <p><strong>$1 = {new Intl.NumberFormat(locale(), { maximumFractionDigits: 6 }).format(fx.latest.cdfPerUnit)}{' '}{t('CDF')}</strong>{' '}{t('on')}{' '}{fx.latest.rateDate} · {fx.latest.enteredBy ? t('entered by {a}', { a: fx.latest.enteredBy }) : fx.latest.source}</p>
        ) : <p className="muted">{t('No rate stored yet.')}</p>}
        {fx?.stale && <p className="notice">{t('The latest rate is more than 4 days old. Fetch it again, or enter today\'s BCC rate by hand.')}</p>}
        <p className="hint">{t('Fetched automatically at start-up and twice a day. Payroll uses the rate set in Settings below, and each run records the rate it used.')}</p>
        {fxEditable && (
          <div className="row">
            <Button className="secondary" onClick={() => run(async () => {
              const r = await api<{ stored: number; alreadyKnown: number; error: string | null }>('/api/exchange-rates/fetch', { method: 'POST' })
              if (r.error) throw new Error(r.error)
            }, t('Rates fetched.'))}>{t('Fetch now')}</Button>
            <Form className="row" onSubmit={(e) => {
              e.preventDefault()
              return run(async () => {
                await api('/api/exchange-rates', { method: 'POST', body: { currency: 'USD', rateDate: fxEntry.rateDate, cdfPerUnit: Number(fxEntry.cdfPerUnit) } })
                setFxEntry({ ...fxEntry, cdfPerUnit: '' })
              }, t('Rate saved.'))
            }}>
              <input type="date" value={fxEntry.rateDate} required onChange={(e) => setFxEntry({ ...fxEntry, rateDate: e.target.value })} />
              <input type="number" min={0.000001} step="any" placeholder={t('CDF per $1')} value={fxEntry.cdfPerUnit} required onChange={(e) => setFxEntry({ ...fxEntry, cdfPerUnit: e.target.value })} />
              <Button type="submit" className="secondary">{t('Enter rate')}</Button>
            </Form>
          </div>
        )}
        {fx && fx.recent.length > 1 && (
          <details>
            <summary>{t('Last')}{' '}{fx.recent.length}{' '}{t('rates')}</summary>
            <ul>{fx.recent.map((r) => <li key={r.id}>{r.rateDate}: {r.cdfPerUnit}{' '}{t('CDF')}{r.enteredBy ? t(' (entered by {a})', { a: r.enteredBy }) : ''}</li>)}</ul>
          </details>
        )}
      </div>

      <div className="card">
        <h3>{t('Contributions and taxes')}</h3>
        <div className="table-wrap">
          <table>
            <thead><tr><th>{t('Rate')}</th><th>{t('Value')}</th><th>{t('Since')}</th><th>{t('Next change')}</th><th></th></tr></thead>
            <tbody>
              {data?.rates.map((r) => (
                <tr key={r.code}>
                  <td><strong>{r.name}</strong><div className="hint">{r.description}</div></td>
                  <td>{show(r, r.value)}</td>
                  <td>{r.effectiveFrom ?? '—'}</td>
                  <td>{r.nextChange ? t('{a} from {b}', { a: show(r, r.nextValue), b: r.nextChange }) : ''}</td>
                  <td><Button className="secondary" onClick={() => setOpen(r.code)}>{editable ? t('History and change') : t('History')}</Button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {open && (
          <Modal title={data?.rates.find((r) => r.code === open)?.name ?? open} size="lg" onClose={() => setOpen(null)}>
            <h4>{t('History')}</h4>
            <ul>
              {history.map((h) => <li key={h.id}>{show(data!.rates.find((r) => r.code === open)!, h.value)}{' '}{t('from')}{' '}{h.effectiveFrom}{h.note ? ` · ${h.note}` : ''} <span className="muted">· {h.createdBy}</span></li>)}
            </ul>
            {editable && (
              <Form className="grid3" onSubmit={(e) => {
                e.preventDefault()
                return run(async () => {
                  await api(`/api/statutory/rates/${open}`, { method: 'POST', body: { effectiveFrom: change.effectiveFrom, value: Number(change.value), note: change.note || null } })
                  setChange({ ...change, value: '', note: '' })
                }, t('New value saved.'))
              }}>
                <label>{t('New value')}<input type="number" min={0} step="any" value={change.value} required onChange={(e) => setChange({ ...change, value: e.target.value })} /></label>
                <label>{t('From')}<input type="date" value={change.effectiveFrom} required onChange={(e) => setChange({ ...change, effectiveFrom: e.target.value })} /></label>
                <label>{t('Reference (decree, circular)')}<input value={change.note} maxLength={300} onChange={(e) => setChange({ ...change, note: e.target.value })} /></label>
                <div><Button type="submit">{t('Add value')}</Button></div>
              </Form>
            )}
            <p className="hint">{t('Values are never overwritten: a new value starts on its date and earlier payslips keep the old one.')}</p>
            <ErrorText error={error} />
          </Modal>
        )}
      </div>

      <div className="card">
        <h3>{t('IPR barème')}</h3>
        <p className="hint">{t('Annual taxable income bands in CDF, in force since')}{' '}{data?.ipr.effectiveFrom}{t('. Each month the taxable income is multiplied by 12, taxed on these bands and divided by 12; then the reduction per dependant and the cap apply.')}</p>
        {!scale && (
          <div className="table-wrap">
            <table>
              <thead><tr><th>{t('From')}</th><th>{t('Up to')}</th><th>{t('Rate')}</th></tr></thead>
              <tbody>
                {data?.ipr.brackets.map((b) => (
                  <tr key={b.lowerBound}><td>{cdf(b.lowerBound)}</td><td>{b.upperBound === null ? t('and above') : cdf(b.upperBound)}</td><td>{b.ratePercent}%</td></tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {editable && !scale && <Button className="secondary" onClick={editScale}>{t('New barème from a date')}</Button>}
        {scale && (
          <Form onSubmit={(e) => {
            e.preventDefault()
            return run(async () => {
              await api('/api/statutory/ipr', {
                method: 'POST',
                body: { effectiveFrom: scale.effectiveFrom, bands: scale.bands.map((b) => ({ lowerBound: Number(b.lowerBound), upperBound: b.upperBound === '' ? null : Number(b.upperBound), ratePercent: Number(b.ratePercent) })) },
              })
              setScale(null)
            }, t('New barème saved.'))
          }}>
            <label className="month-pick">{t('Applies from')}{' '}<input type="date" value={scale.effectiveFrom} required onChange={(e) => setScale({ ...scale, effectiveFrom: e.target.value })} /></label>
            <div className="table-wrap">
              <table>
                <thead><tr><th>{t('From (CDF)')}</th><th>{t('Up to (CDF, empty for the last band)')}</th><th>{t('Rate (%)')}</th><th></th></tr></thead>
                <tbody>
                  {scale.bands.map((b, i) => (
                    <tr key={i}>
                      <td>{b.lowerBound}</td>
                      <td><input type="number" min={0} step="any" value={b.upperBound} required={i < scale.bands.length - 1} onChange={(e) => setBand(i, { upperBound: e.target.value })} /></td>
                      <td><input type="number" min={0} max={100} step="any" className="narrow-input" value={b.ratePercent} required onChange={(e) => setBand(i, { ratePercent: e.target.value })} /></td>
                      <td>{scale.bands.length > 1 && <Button type="button" className="secondary" onClick={() => {
                        const bands = scale.bands.filter((_, j) => j !== i)
                        if (bands[i]) bands[i] = { ...bands[i], lowerBound: i === 0 ? '0' : bands[i - 1].upperBound }
                        setScale({ ...scale, bands })
                      }}>{t('Remove')}</Button>}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div className="row">
              <Button type="button" className="secondary" onClick={() => setScale({ ...scale, bands: [...scale.bands, { lowerBound: scale.bands[scale.bands.length - 1]?.upperBound || '0', upperBound: '', ratePercent: '' }] })}>{t('Add band')}</Button>
              <Button type="submit">{t('Save barème')}</Button>
              <Button type="button" className="secondary" onClick={() => setScale(null)}>{t('Cancel')}</Button>
            </div>
          </Form>
        )}

        <h4>{t('Try it')}</h4>
        <Form className="grid3" onSubmit={(e) => {
          e.preventDefault()
          setError(null)
          return api<IprResult>('/api/statutory/ipr/try', { method: 'POST', body: { taxableMonthlyCdf: Number(trial.taxableMonthlyCdf), dependants: Number(trial.dependants), on } })
            .then(setTrialResult).catch((err: Error) => setError(err.message))
        }}>
          <label>{t('Monthly taxable income (CDF)')}<input type="number" min={0} step="any" value={trial.taxableMonthlyCdf} onChange={(e) => setTrial({ ...trial, taxableMonthlyCdf: e.target.value })} /></label>
          <label>{t('Dependants')}<input type="number" min={0} max={30} value={trial.dependants} onChange={(e) => setTrial({ ...trial, dependants: e.target.value })} /></label>
          <div><Button type="submit" className="secondary">{t('Work out IPR')}</Button></div>
        </Form>
        {trialResult && (
          <p>{t('Barème:')}{' '}{cdf(trialResult.beforeReduction)}{' '}{t('· dependants: −')}{cdf(trialResult.reduction)}{trialResult.capped > 0 ? t(' · cap: −{a}', { a: cdf(trialResult.capped) }) : ''} · <strong>{t('IPR')}{' '}{cdf(trialResult.tax)}</strong></p>
        )}
      </div>

      <div className="card">
        <h3>{t('Settings')}</h3>
        <label>{t('Company size for INPP')}<select value={data?.settings.INPP_BAND ?? 'LARGE'} disabled={!editable}
            onChange={(e) => void run(() => api('/api/statutory/settings/INPP_BAND', { method: 'PUT', body: { value: e.target.value } }), t('Saved.'))}>
            <option value="SMALL">{t('Up to 50 employees')}</option>
            <option value="MEDIUM">{t('51 to 300 employees')}</option>
            <option value="LARGE">{t('More than 300 employees')}</option>
          </select>
        </label>
        <label>{t('Exchange rate used by a payroll run')}<select value={data?.settings.RATE_DAY ?? 'LAST_OF_MONTH'} disabled={!editable}
            onChange={(e) => void run(() => api('/api/statutory/settings/RATE_DAY', { method: 'PUT', body: { value: e.target.value } }), t('Saved.'))}>
            <option value="LAST_OF_MONTH">{t('Last BCC rate of the pay month')}</option>
            <option value="RUN_DATE">{t('BCC rate on the day of the run')}</option>
          </select>
        </label>
      </div>
    </section>
  )
}
