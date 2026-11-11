import { useEffect, useMemo, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { api, longDay, money } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'
import { Modal } from '../components/Modal'
import { UnitSelect } from '../components/OrgCard'
import { PageTitle } from '../components/SettingsLink'
import { Spinner } from '../components/Button'
import type { OrgUnit } from '../org'
import { localCurrency, locale, t } from '../i18n'
import { DateInput } from '../components/DateFields'

type KindInfo = { kind: string; title: string; hint: string; period: boolean; byUnit: boolean }
type Column = { key: string; label: string; type: 'TEXT' | 'NUMBER' | 'HOURS' | 'PERCENT' | 'MONEY' | 'DATE' }
type Row = {
  userId: number | null; unitId: number | null; name: string; registrationNo: string | null; directionName: string | null
  unitName: string | null; values: Record<string, string | number | null>; score: number; previous: number | null
}
type Ranking = {
  kind: string; title: string; hint: string; from: string; to: string; previousFrom: string | null; previousTo: string | null
  companyWide: boolean; byUnit: boolean; scoreLabel: string; columns: Column[]; rows: Row[]; total: number
}
type Line = { date: string | null; label: string; value: string | null; detail: string | null }

const iso = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`

/** The usual periods: this month, last month, the last three months, this year. */
function preset(name: string): [string, string] {
  const now = new Date()
  const y = now.getFullYear()
  const m = now.getMonth()
  switch (name) {
    case 'last-month': return [iso(new Date(y, m - 1, 1)), iso(new Date(y, m, 0))]
    case 'quarter': return [iso(new Date(y, m - 2, 1)), iso(now)]
    case 'year': return [iso(new Date(y, 0, 1)), iso(now)]
    default: return [iso(new Date(y, m, 1)), iso(now)]
  }
}

function show(v: string | number | null | undefined, type: Column['type']) {
  if (v === null || v === undefined || v === '') return '—'
  if (type === 'DATE') return longDay(String(v))
  if (type === 'MONEY') return money(Number(v), localCurrency())
  if (type === 'PERCENT') return `${Number(v).toLocaleString(locale(), { maximumFractionDigits: 1 })} %`
  if (typeof v === 'number') return v.toLocaleString(locale(), { maximumFractionDigits: 1 })
  return v
}

/** Up or down from the previous period: more lateness is worse, so an increase shows as a warning. */
function Trend({ now, before }: { now: number; before: number | null }) {
  if (before === null) return <span className="muted">—</span>
  const diff = Number(now) - Number(before)
  const n = Number(before).toLocaleString(locale(), { maximumFractionDigits: 1 })
  if (Math.abs(diff) < 0.05) return <span className="muted">{n} =</span>
  return <span className={diff > 0 ? 'badge warn' : 'badge ok'} title={t('Previous period')}>{n} {diff > 0 ? '↑' : '↓'}</span>
}

/** HR rankings: who is late, absent, on overtime, sanctioned most, and which units lose people, period by period. */
export default function RankingsPage() {
  const { can } = useAuth()
  const navigate = useNavigate()
  const [params, setParams] = useSearchParams()
  const [kinds, setKinds] = useState<KindInfo[]>([])
  const [units, setUnits] = useState<OrgUnit[]>([])
  const [range, setRange] = useState(params.get('range') ?? 'month')
  const [[from, to], setPeriod] = useState<[string, string]>(() => preset(params.get('range') ?? 'month'))
  const [unit, setUnit] = useState('')
  const [top, setTop] = useState('20')
  const [byUnit, setByUnit] = useState(false)
  const [data, setData] = useState<Ranking | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [person, setPerson] = useState<Row | null>(null)
  const kind = params.get('kind') ?? kinds[0]?.kind ?? ''
  const info = kinds.find((k) => k.kind === kind)

  useEffect(() => {
    api<KindInfo[]>('/api/rankings').then(setKinds).catch((e: Error) => setError(e.message))
    api<OrgUnit[]>('/api/org/units').then(setUnits).catch(() => undefined)
  }, [])

  const query = useMemo(() => {
    const q = new URLSearchParams({ from, to, top })
    if (unit) q.set('unitId', unit)
    if (byUnit) q.set('byUnit', 'true')
    return q.toString()
  }, [from, to, top, unit, byUnit])
  const path = `/api/rankings/${kind.toLowerCase().replace('_', '-')}`

  useEffect(() => {
    if (!kind) return
    setLoading(true)
    setError(null)
    api<Ranking>(`${path}?${query}`).then(setData).catch((e: Error) => { setError(e.message); setData(null) })
      .finally(() => setLoading(false))
  }, [path, query, kind])

  const pick = (k: string) => { params.set('kind', k); setParams(params, { replace: true }) }
  const choose = (name: string) => {
    setRange(name)
    if (name !== 'custom') setPeriod(preset(name))
  }
  const units_ = data?.kind === 'TURNOVER' || data?.kind === 'UNITS'
  const people = !units_
  const canOpenCase = can('discipline.manage') || can('discipline.propose')

  return (
    <section>
      <PageTitle title={t('HR rankings')}>
        <a className="button secondary" href={`${path}?${query}&format=xlsx`}>{t('Excel')}</a>
        <a className="button secondary" href={`${path}?${query}&format=pdf`}>{t('PDF')}</a>
      </PageTitle>
      <p className="muted">{t('Who is late, absent or on overtime most, compared with the period just before. A number is a reason to look at the days behind it, never a sanction by itself. Each view and download is recorded in the audit log.')}</p>

      <div className="segmented" role="tablist">
        {kinds.map((k) => <button key={k.kind} type="button" role="tab" aria-selected={k.kind === kind} className={k.kind === kind ? 'on' : ''} onClick={() => pick(k.kind)}>{k.title}</button>)}
      </div>

      <div className="card">
        <div className="grid2">
          {info?.period !== false && (
            <label>{t('Period')}
              <select value={range} onChange={(e) => choose(e.target.value)}>
                <option value="month">{t('This month')}</option>
                <option value="last-month">{t('Last month')}</option>
                <option value="quarter">{t('The last three months')}</option>
                <option value="year">{t('This year')}</option>
                <option value="custom">{t('Other dates')}</option>
              </select>
            </label>
          )}
          {range === 'custom' && info?.period !== false && (
            <div className="row">
              <label>{t('From')}<DateInput value={from} onChange={(e) => setPeriod([e.target.value, to])} /></label>
              <label>{t('To')}<DateInput value={to} onChange={(e) => setPeriod([from, e.target.value])} /></label>
            </div>
          )}
          <label>{t('Unit (with the units below it)')}<UnitSelect units={units} value={unit} placeholder={data?.companyWide === false ? t('My team') : t('Everyone')} onChange={setUnit} /></label>
          {people && (
            <label>{t('Show')}
              <select value={top} onChange={(e) => setTop(e.target.value)}>
                <option value="10">{t('The first 10')}</option>
                <option value="20">{t('The first 20')}</option>
                <option value="50">{t('The first 50')}</option>
                <option value="0">{t('Everyone with a number')}</option>
              </select>
            </label>
          )}
          {info?.byUnit && (
            <label className="check"><input type="checkbox" checked={byUnit} onChange={(e) => setByUnit(e.target.checked)} />{t('Every unit, not only Directions')}</label>
          )}
        </div>
      </div>

      <ErrorText error={error} />
      {data && (
        <div className="card">
          <h3>{data.title} {loading && <Spinner />}</h3>
          <p className="hint">{data.hint}</p>
          <p className="small muted">
            {data.previousFrom ? t('From {a} to {b}, compared with {c} to {d}.', { a: longDay(data.from), b: longDay(data.to), c: longDay(data.previousFrom), d: longDay(data.previousTo) })
              : t('Today, {a}.', { a: longDay(data.from) })}
            {' '}{data.companyWide ? '' : t('Your team only.')}
            {people && data.total > data.rows.length && ` ${t('{a} of {b} people shown.', { a: data.rows.length, b: data.total })}`}
          </p>
          {data.rows.length === 0 ? <p className="muted">{t('Nobody in this ranking for the period.')}</p> : (
            <div className="table-wrap">
              <table className="lines">
                <thead>
                  <tr>
                    <th>#</th>
                    <th>{people ? t('Name') : data.byUnit ? t('Unit') : t('Direction')}</th>
                    {people && <th>{t('Unit')}</th>}
                    <th className="num">{data.scoreLabel}</th>
                    {data.columns.map((c) => <th key={c.key} className={c.type === 'TEXT' || c.type === 'DATE' ? '' : 'num'}>{c.label}</th>)}
                    {data.previousFrom && data.kind !== 'FOLLOW_UP' && <th className="num">{t('Previous period')}</th>}
                    {data.kind === 'FOLLOW_UP' && canOpenCase && <th></th>}
                  </tr>
                </thead>
                <tbody>
                  {data.rows.map((r, i) => (
                    <tr key={`${r.userId ?? 'u'}-${r.unitId ?? ''}-${i}`}>
                      <td>{i + 1}</td>
                      <td>
                        {people
                          ? <button type="button" className="link" onClick={() => setPerson(r)}>{r.name}</button>
                          : r.name}
                        {people && <div className="small muted">{r.registrationNo}</div>}
                        {!people && data.byUnit && r.directionName && <div className="small muted">{r.directionName}</div>}
                      </td>
                      {people && <td>{r.unitName ?? '—'}{r.directionName && r.directionName !== r.unitName && <div className="small muted">{r.directionName}</div>}</td>}
                      <td className="num"><strong>{show(r.score, units_ ? 'PERCENT' : 'NUMBER')}</strong></td>
                      {data.columns.map((c) => <td key={c.key} className={c.type === 'TEXT' || c.type === 'DATE' ? '' : 'num'}>{show(r.values[c.key], c.type)}</td>)}
                      {data.previousFrom && data.kind !== 'FOLLOW_UP' && <td className="num"><Trend now={r.score} before={r.previous} /></td>}
                      {data.kind === 'FOLLOW_UP' && canOpenCase && (
                        <td><button type="button" className="button secondary" onClick={() => navigate(`/discipline?new=${r.userId}&month=${r.values.month ?? ''}`)}>{t('Open a case')}</button></td>
                      )}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}
      {!data && loading && <p className="muted"><Spinner /> {t('Working it out…')}</p>}
      {person && data && <Behind kind={data.kind} path={path} row={person} from={data.from} to={data.to} onClose={() => setPerson(null)} />}
    </section>
  )
}

/** The days, leave, missions or cases behind one person's number. */
function Behind({ kind, path, row, from, to, onClose }: { kind: string; path: string; row: Row; from: string; to: string; onClose: () => void }) {
  const [lines, setLines] = useState<Line[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => {
    api<Line[]>(`${path}/people/${row.userId}?from=${from}&to=${to}`).then(setLines).catch((e: Error) => setError(e.message))
  }, [path, row.userId, from, to])
  return (
    <Modal title={row.name} onClose={onClose} size="lg">
      <p className="small muted">{row.registrationNo} · {row.unitName ?? ''} · {kind === 'LEAVE' ? t('Leave this year and last year') : t('From {a} to {b}', { a: longDay(from), b: longDay(to) })}</p>
      <ErrorText error={error} />
      {!lines && !error && <p className="muted"><Spinner /> {t('Loading…')}</p>}
      {lines && lines.length === 0 && <p className="muted">{t('Nothing to show.')}</p>}
      {lines && lines.length > 0 && (
        <div className="table-wrap">
          <table className="lines">
            <thead><tr><th>{t('Date')}</th><th>{t('What')}</th><th>{t('Value')}</th><th>{t('Detail')}</th></tr></thead>
            <tbody>
              {lines.map((l, i) => <tr key={i}><td>{longDay(l.date)}</td><td>{l.label}</td><td>{l.value ?? ''}</td><td className="small">{l.detail ?? ''}</td></tr>)}
            </tbody>
          </table>
        </div>
      )}
    </Modal>
  )
}
