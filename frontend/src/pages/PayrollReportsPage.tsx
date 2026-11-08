import { useEffect, useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { api, download } from '../api'
import { useAuth } from '../auth'
import { Button } from '../components/Button'
import { ErrorText } from '../components/ErrorText'
import { Modal } from '../components/Modal'
import { UnitSelect } from '../components/OrgCard'
import { PageTitle } from '../components/SettingsLink'
import { locale, t } from '../i18n'
import type { OrgUnit } from '../org'

type Column = { label: string; type: 'TEXT' | 'MONEY' | 'NUMBER' }
type Row = { cells: (string | number | null)[]; style: 'SUBTOTAL' | 'TOTAL' | 'GROUP' | 'COUNT' | null; depth: number; drill: Record<string, string> | null }
type Table = { title: string; columns: Column[]; rows: Row[]; rowsPerPage: number; large: boolean }
type Report = { code: string; title: string; subtitle: string; provisional: boolean; tables: Table[]; notes: string[] }
type Options = {
  dimensions: { code: string; label: string }[]
  levels: { rank: number; name: string }[]
  sites: { id: number; name: string }[]
  categories: { id: number; code: string; name: string }[]
  quinzaines: { id: number; month: string; payDate: string; status: string }[]
  months: { month: string; status: string }[]
}

const REPORTS: { code: string; label: string; named?: boolean }[] = [
  { code: 'summary', label: t('General summary') },
  { code: 'by', label: t('Totals by grouping') },
  { code: 'tree', label: t('Organisational structure') },
  { code: 'elements', label: t('By pay element') },
  { code: 'cross', label: t('Pay elements by grouping') },
  { code: 'payment', label: t('By payment method') },
  { code: 'variance', label: t('Differences with last month'), named: true },
  { code: 'livre', label: t('Livre de paie'), named: true },
  { code: 'quinzaine', label: t('Quinzaine') },
]

const amount = (v: number, decimals: boolean) =>
  new Intl.NumberFormat(locale(), decimals ? { minimumFractionDigits: 2, maximumFractionDigits: 2 } : { maximumFractionDigits: 2 }).format(v)

function cell(v: string | number | null, c: Column, count: boolean) {
  if (v === null || v === '') return ''
  if (typeof v === 'number' && c.type !== 'TEXT') return amount(v, c.type === 'MONEY' && !count)
  return String(v)
}

/** A report's tables; a row that lists people opens them when clicked. */
function Tables({ report, onDrill }: { report: Report; onDrill?: (d: Record<string, string>) => void }) {
  return (
    <>
      {report.tables.map((table, ti) => (
        <div key={ti} className="report-table">
          <h4>{table.title}</h4>
          <div className="table-wrap">
            <table className="report">
              <thead><tr>{table.columns.map((c, i) => <th key={i} className={c.type === 'TEXT' ? '' : 'num'}>{c.label}</th>)}</tr></thead>
              <tbody>
                {table.rows.length === 0 && <tr><td colSpan={table.columns.length} className="muted">{t('None.')}</td></tr>}
                {table.rows.map((r, ri) => {
                  const drill = r.drill && onDrill ? () => onDrill(r.drill!) : undefined
                  return (
                    <tr key={ri} className={[r.style && r.style !== 'COUNT' ? r.style.toLowerCase() : '', drill ? 'clickable' : ''].join(' ')}
                      onClick={drill} title={drill ? t('Show the employees') : undefined}>
                      {table.columns.map((c, i) => (
                        <td key={i} className={c.type === 'TEXT' ? '' : 'num'} style={i === 0 && r.depth > 0 ? { paddingLeft: `${0.5 + r.depth * 1.1}rem` } : undefined}>
                          {cell(r.cells[i] ?? null, c, r.style === 'COUNT')}
                        </td>
                      ))}
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        </div>
      ))}
      {report.notes.length > 0 && <ul className="muted small">{report.notes.map((n, i) => <li key={i}>{n}</li>)}</ul>}
    </>
  )
}

/**
 * The payroll general reports (états généraux de la paie) of a month: totals by grade category, grade, site, unit,
 * payment method and pay element, the differences with the month before, the quinzaine and the livre de paie. Each
 * can be printed (PDF) or exported (Excel).
 */
export default function PayrollReportsPage() {
  const { can } = useAuth()
  const named = can('payslip.read.all')
  const [params, setParams] = useSearchParams()
  const [options, setOptions] = useState<Options | null>(null)
  const [units, setUnits] = useState<OrgUnit[]>([])
  const [report, setReport] = useState<Report | null>(null)
  const [drilled, setDrilled] = useState<Report | null>(null)
  const [drillQuery, setDrillQuery] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)

  const get = (k: string, d = '') => params.get(k) ?? d
  const set = (k: string, v: string) => {
    const next = new URLSearchParams(params)
    if (v) next.set(k, v)
    else next.delete(k)
    setParams(next, { replace: true })
  }
  const month = get('month')
  const kind = get('report', 'summary')

  useEffect(() => {
    api<Options>('/api/payroll/reports/options').then((o) => {
      setOptions(o)
      if (!params.get('month') && o.months.length > 0) set('month', o.months[0].month)
    }).catch((e: Error) => setError(e.message))
    api<OrgUnit[]>('/api/org/units').then(setUnits).catch(() => undefined)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const query = useMemo(() => {
    const q = new URLSearchParams()
    for (const k of ['unitId', 'siteId', 'categoryId', 'currency', 'dim', 'then', 'level', 'thenLevel', 'threshold', 'quinzaineId']) {
      const v = params.get(k)
      if (v) q.set(k, v)
    }
    if (params.get('complementary') === 'false') q.set('complementary', 'false')
    return q
  }, [params])

  const path = (format?: string, extra?: URLSearchParams) => {
    const q = new URLSearchParams(query)
    extra?.forEach((v, k) => q.set(k, v))
    if (format) q.set('format', format)
    const m = kind === 'quinzaine' && !month ? options?.quinzaines[0]?.month.slice(0, 7) ?? '' : month
    return `/api/payroll/reports/${m}/${kind}?${q}`
  }

  useEffect(() => {
    if (!month || (kind === 'quinzaine' && !params.get('quinzaineId'))) {
      setReport(null)
      return
    }
    setLoading(true)
    setError(null)
    api<Report>(path()).then(setReport).catch((e: Error) => { setReport(null); setError(e.message) }).finally(() => setLoading(false))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [month, kind, query])

  const drill = (d: Record<string, string>) => {
    if (!named) return
    const q = new URLSearchParams(d)
    setDrillQuery(q.toString())
    api<Report>(`/api/payroll/reports/${month}/employees?${new URLSearchParams([...query, ...q])}`).then(setDrilled)
      .catch((e: Error) => setError(e.message))
  }
  const fetchFile = (url: string, name: string) => download(url, name).catch((e: Error) => setError(e.message))

  const dims = options?.dimensions ?? []
  const levelSelect = (key: string, label: string) => (
    <label>{label}
      <select value={get(key)} onChange={(e) => set(key, e.target.value)}>
        <option value="">{t('Own unit')}</option>
        {options?.levels.map((l) => <option key={l.rank} value={l.rank}>{l.name}</option>)}
      </select>
    </label>
  )
  const usesDim = kind === 'by' || kind === 'cross'
  const reports = REPORTS.filter((r) => !r.named || named)

  return (
    <section>
      <PageTitle title={t('Payroll reports')} />
      <p className="muted">{t('The general payroll statements of a month, on screen, in PDF or in Excel.')}</p>
      <ErrorText error={error} />

      <div className="card">
        <div className="row">
          <label>{t('Report')}
            <select value={kind} onChange={(e) => set('report', e.target.value)}>
              {reports.map((r) => <option key={r.code} value={r.code}>{r.label}</option>)}
            </select>
          </label>
          {kind === 'quinzaine' ? (
            <label>{t('Quinzaine')}
              <select value={get('quinzaineId')} onChange={(e) => {
                const q = options?.quinzaines.find((x) => String(x.id) === e.target.value)
                const next = new URLSearchParams(params)
                next.set('quinzaineId', e.target.value)
                if (q) next.set('month', q.month.slice(0, 7))
                setParams(next, { replace: true })
              }}>
                <option value="">{t('Choose')}</option>
                {options?.quinzaines.map((q) => <option key={q.id} value={q.id}>{q.month.slice(0, 7)} · {q.payDate}</option>)}
              </select>
            </label>
          ) : (
            <label>{t('Month')}
              <select value={month} onChange={(e) => set('month', e.target.value)}>
                {options?.months.length === 0 && <option value="">{t('No payroll yet')}</option>}
                {options?.months.map((m) => <option key={m.month} value={m.month}>{m.month}{m.status === 'CLOSED' ? '' : ` (${t('not closed')})`}</option>)}
              </select>
            </label>
          )}
          {usesDim && (
            <label>{t('Group by')}
              <select value={get('dim', 'CATEGORY')} onChange={(e) => set('dim', e.target.value)}>
                {dims.map((d) => <option key={d.code} value={d.code}>{d.label}</option>)}
              </select>
            </label>
          )}
          {usesDim && get('dim', 'CATEGORY') === 'UNIT' && levelSelect('level', t('Unit level'))}
          {kind === 'by' && (
            <label>{t('Then by')}
              <select value={get('then')} onChange={(e) => set('then', e.target.value)}>
                <option value="">{t('Nothing')}</option>
                {dims.filter((d) => d.code !== get('dim', 'CATEGORY')).map((d) => <option key={d.code} value={d.code}>{d.label}</option>)}
              </select>
            </label>
          )}
          {kind === 'by' && get('then') === 'UNIT' && levelSelect('thenLevel', t('Unit level'))}
          {kind === 'quinzaine' && levelSelect('level', t('Unit level'))}
          {kind === 'variance' && (
            <label>{t('Change above (%)')}<input type="number" min={0} max={100} step={1} placeholder="10" value={get('threshold')}
              onChange={(e) => set('threshold', e.target.value)} /></label>
          )}
        </div>
        <div className="row">
          <label>{t('Unit')}<UnitSelect units={units} value={get('unitId')} onChange={(v) => set('unitId', v)} placeholder={t('All units')} /></label>
          <label>{t('Site')}
            <select value={get('siteId')} onChange={(e) => set('siteId', e.target.value)}>
              <option value="">{t('All sites')}</option>
              {options?.sites.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
            </select>
          </label>
          <label>{t('Grade category')}
            <select value={get('categoryId')} onChange={(e) => set('categoryId', e.target.value)}>
              <option value="">{t('All categories')}</option>
              {options?.categories.map((c) => <option key={c.id} value={c.id}>{c.code} · {c.name}</option>)}
            </select>
          </label>
          <label>{t('Amounts')}
            <select value={get('currency', 'PAID')} onChange={(e) => set('currency', e.target.value === 'PAID' ? '' : e.target.value)}>
              <option value="PAID">{t('As paid')}</option>
              <option value="LOCAL">{t('Local currency')}</option>
              <option value="USD">{t('US dollars')}</option>
            </select>
          </label>
          <label className="check"><input type="checkbox" checked={get('complementary') !== 'false'}
            onChange={(e) => set('complementary', e.target.checked ? '' : 'false')} />{t('With corrections')}</label>
        </div>
        <div className="row">
          <Button className="secondary" disabled={!report} onClick={() => fetchFile(path('pdf'), 'report.pdf')}>{t('Download PDF')}</Button>
          <Button className="secondary" disabled={!report} onClick={() => fetchFile(path('xlsx'), 'report.xlsx')}>{t('Download Excel')}</Button>
          {month && (
            <Button className="secondary" onClick={() => fetchFile(`/api/payroll/reports/${month}/all?${new URLSearchParams([...query, ['format', 'xlsx']])}`,
              `payroll-reports-${month}.xlsx`)}>{t('Whole month (Excel)')}</Button>
          )}
        </div>
      </div>

      {loading && <p className="muted">{t('Loading…')}</p>}
      {report && !loading && (
        <div className="card">
          <h3>{report.title}</h3>
          <p className="muted">{report.subtitle}</p>
          {report.provisional && <p className="notice">{t('Provisional: this payroll is not closed, the figures can still change.')}</p>}
          <Tables report={report} onDrill={named ? drill : undefined} />
        </div>
      )}

      {drilled && (
        <Modal title={drilled.title} size="lg" onClose={() => setDrilled(null)} footer={
          <Button className="secondary" onClick={() => fetchFile(`/api/payroll/reports/${month}/employees?${new URLSearchParams([...query, ...new URLSearchParams(drillQuery), ['format', 'xlsx']])}`, 'employees.xlsx')}>
            {t('Download Excel')}</Button>
        }>
          <Tables report={drilled} />
        </Modal>
      )}
    </section>
  )
}
