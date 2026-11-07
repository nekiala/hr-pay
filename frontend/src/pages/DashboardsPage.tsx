import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api'
import { ErrorText } from '../components/ErrorText'
import { locale, t } from '../i18n'

type Month = { month: string; headcount: number; hires: number; departures: number; turnover: number; absenceRate: number | null; cost: number | null; payrollFinal: boolean }
type Board = { months: Month[]; costByDirection: { month: string; direction: string; cost: number }[]; directions: { name: string; headcount: number }[]; currency: string }

/** Categorical slots in fixed order (validated for colour-blind separation); the rest folds into "Other". */
const SERIES = ['var(--series-1)', 'var(--series-2)', 'var(--series-3)', 'var(--series-4)', 'var(--series-5)', 'var(--series-6)']
const OTHER = 'var(--series-other)'

const monthLabel = (m: string, long = false) => new Date(m + '-01T00:00:00').toLocaleDateString(locale(), long ? { month: 'long', year: 'numeric' } : { month: 'short' })
const num = (n: number, digits = 0) => n.toLocaleString(locale(), { maximumFractionDigits: digits, minimumFractionDigits: digits })
const usd = (n: number) => n.toLocaleString(locale(), { style: 'currency', currency: 'USD', maximumFractionDigits: 0 })

type Segment = { key: string; value: number; color: string }
type Bar = { label: string; title: string; segments: Segment[] }

/** Bars by month: one value, two side by side, or stacked segments. Hover a month for its values. */
function BarChart({ bars, format, grouped = false, height = 180 }: { bars: Bar[]; format: (n: number) => string; grouped?: boolean; height?: number }) {
  const [hover, setHover] = useState<number | null>(null)
  const max = Math.max(1, ...bars.map((b) => grouped ? Math.max(...b.segments.map((s) => s.value)) : b.segments.reduce((n, s) => n + s.value, 0)))
  const w = 640
  const top = 8
  const bottom = 22
  const plot = height - top - bottom
  const slot = w / bars.length
  const barW = Math.min(36, slot * 0.6)
  const y = (v: number) => top + plot - (v / max) * plot
  const ticks = [0, max / 2, max]
  return (
    <div className="chart" onMouseLeave={() => setHover(null)}>
      <svg viewBox={`0 0 ${w} ${height}`} role="img" aria-label={t('Chart')} preserveAspectRatio="none">
        {ticks.map((v) => <line key={v} x1={0} x2={w} y1={y(v)} y2={y(v)} className="grid" />)}
        {bars.map((b, i) => {
          const x0 = i * slot + (slot - barW) / 2
          let acc = 0
          return (
            <g key={b.label}>
              <rect x={i * slot} y={0} width={slot} height={height - bottom} fill="transparent" onMouseEnter={() => setHover(i)} />
              {grouped
                ? b.segments.map((s, k) => {
                  const bw = barW / b.segments.length - 1
                  const h = (s.value / max) * plot
                  return h > 0 && <rect key={s.key} x={x0 + k * (bw + 2)} y={y(s.value)} width={bw} height={h} rx={2} fill={s.color} pointerEvents="none" />
                })
                : b.segments.map((s) => {
                  const h = (s.value / max) * plot
                  const yy = y(acc + s.value)
                  acc += s.value
                  return h > 0 && <rect key={s.key} x={x0} y={yy} width={barW} height={Math.max(0, h - 2)} rx={2} fill={s.color} pointerEvents="none" />
                })}
              <text x={i * slot + slot / 2} y={height - 6} textAnchor="middle" className="axis">{b.label}</text>
              {hover === i && <rect x={i * slot} y={0} width={slot} height={height - bottom} className="hover-band" pointerEvents="none" />}
            </g>
          )
        })}
      </svg>
      <span className="axis-max small muted">{format(max)}</span>
      {hover !== null && (
        <div className="chart-tip" style={{ left: `${Math.min(80, Math.max(5, ((hover + 0.5) / bars.length) * 100))}%` }}>
          <strong>{bars[hover].title}</strong>
          {bars[hover].segments.filter((s) => s.value > 0 || bars[hover].segments.length === 1).map((s) => (
            <div key={s.key} className="row between"><span><i className="swatch" style={{ background: s.color }} />{s.key}</span><span>{format(s.value)}</span></div>
          ))}
        </div>
      )}
    </div>
  )
}

function Legend({ items }: { items: { key: string; color: string }[] }) {
  return <ul className="legend">{items.map((i) => <li key={i.key}><i className="swatch" style={{ background: i.color }} />{i.key}</li>)}</ul>
}

/** Management dashboards: headcount, movements, turnover, absence and payroll cost by direction. */
export default function DashboardsPage() {
  const [months, setMonths] = useState(12)
  const [board, setBoard] = useState<Board | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [table, setTable] = useState(false)
  useEffect(() => { setError(null); api<Board>(`/api/dashboards?months=${months}`).then(setBoard).catch((e: Error) => setError(e.message)) }, [months])

  // Directions keep their colour whatever the period: ordered by today's headcount, the seventh and after are "Other".
  const colours = useMemo(() => {
    const map = new Map<string, string>()
    board?.directions.slice(0, SERIES.length).forEach((d, i) => map.set(d.name, SERIES[i]))
    return map
  }, [board])

  if (!board) return <section><h2>{t('Dashboards')}</h2><ErrorText error={error} /></section>
  const ms = board.months
  const last = ms[ms.length - 1]
  const departures = ms.reduce((n, m) => n + m.departures, 0)
  const avg = ms.reduce((n, m) => n + m.headcount, 0) / ms.length
  const turnover = avg > 0 ? (100 * departures) / avg : 0
  const lastAbsence = [...ms].reverse().find((m) => m.absenceRate !== null)
  const lastCost = [...ms].reverse().find((m) => m.cost !== null)
  const otherName = t('Other')
  const directionOf = (name: string) => colours.has(name) ? name : otherName
  const costKeys = [...colours.keys(), ...(board.costByDirection.some((c) => !colours.has(c.direction)) ? [otherName] : [])]
  const colourOf = (k: string) => colours.get(k) ?? OTHER

  const costBars: Bar[] = ms.map((m) => {
    const by = new Map<string, number>()
    board.costByDirection.filter((c) => c.month === m.month).forEach((c) => by.set(directionOf(c.direction), (by.get(directionOf(c.direction)) ?? 0) + c.cost))
    return { label: monthLabel(m.month), title: monthLabel(m.month, true) + (m.cost !== null && !m.payrollFinal ? ` · ${t('not approved yet')}` : ''),
      segments: costKeys.map((k) => ({ key: k, value: by.get(k) ?? 0, color: colourOf(k) })) }
  })
  const one = (key: string, pick: (m: Month) => number | null, color = SERIES[0]): Bar[] =>
    ms.map((m) => ({ label: monthLabel(m.month), title: monthLabel(m.month, true), segments: [{ key, value: pick(m) ?? 0, color }] }))

  return (
    <section>
      <div className="row between">
        <h2>{t('Dashboards')}</h2>
        <div className="row">
          <select value={months} onChange={(e) => setMonths(Number(e.target.value))} aria-label={t('Period')}>
            <option value={6}>{t('Last 6 months')}</option><option value={12}>{t('Last 12 months')}</option><option value={24}>{t('Last 24 months')}</option>
          </select>
          <button type="button" className="secondary" onClick={() => setTable(!table)}>{table ? t('Show charts') : t('Show as a table')}</button>
        </div>
      </div>
      <ErrorText error={error} />

      <div className="tiles">
        <div className="tile"><span>{t('Headcount today')}</span><strong>{num(last.headcount)}</strong><small>{t('{a} hired · {b} left this month', { a: last.hires, b: last.departures })}</small></div>
        <div className="tile"><span>{t('Turnover over the period')}</span><strong>{num(turnover, 1)} %</strong><small>{t('{a} departures', { a: departures })}</small></div>
        <div className="tile"><span>{t('Unpaid absence')}</span><strong>{lastAbsence ? `${num(lastAbsence.absenceRate!, 1)} %` : '—'}</strong><small>{lastAbsence ? monthLabel(lastAbsence.month, true) : t('After the first payroll')}</small></div>
        <div className="tile accent"><span>{t('Payroll cost')}</span><strong>{lastCost ? usd(lastCost.cost!) : '—'}</strong><small>{lastCost ? `${monthLabel(lastCost.month, true)} · ${t('gross and employer charges')}` : t('After the first payroll')}</small></div>
      </div>

      {table ? (
        <div className="card">
          <div className="table-wrap">
            <table>
              <thead><tr><th>{t('Month')}</th><th className="num">{t('Headcount')}</th><th className="num">{t('Hired')}</th><th className="num">{t('Departures')}</th><th className="num">{t('Turnover')}</th><th className="num">{t('Unpaid absence')}</th><th className="num">{t('Payroll cost (USD)')}</th></tr></thead>
              <tbody>
                {ms.map((m) => (
                  <tr key={m.month}><td>{monthLabel(m.month, true)}</td><td className="num">{m.headcount}</td><td className="num">{m.hires}</td><td className="num">{m.departures}</td>
                    <td className="num">{num(m.turnover, 1)} %</td><td className="num">{m.absenceRate === null ? '—' : `${num(m.absenceRate, 1)} %`}</td>
                    <td className="num">{m.cost === null ? '—' : usd(m.cost)}{m.cost !== null && !m.payrollFinal && ' *'}</td></tr>
                ))}
              </tbody>
            </table>
          </div>
          {board.costByDirection.length > 0 && (
            <>
              <h4>{t('Payroll cost by direction (USD)')}</h4>
              <div className="table-wrap">
                <table>
                  <thead><tr><th>{t('Month')}</th><th>{t('Direction')}</th><th className="num">{t('Cost')}</th></tr></thead>
                  <tbody>{board.costByDirection.map((c) => <tr key={c.month + c.direction}><td>{monthLabel(c.month, true)}</td><td>{c.direction}</td><td className="num">{usd(c.cost)}</td></tr>)}</tbody>
                </table>
              </div>
            </>
          )}
          <p className="small muted">{t('* Payroll calculated, not approved yet.')}</p>
        </div>
      ) : (
        <>
          <div className="card">
            <h3>{t('Payroll cost by direction')}</h3>
            <p className="small muted">{t('Gross pay and employer charges, in US dollars at each month’s payroll rate.')}</p>
            {board.costByDirection.length === 0 ? <p className="muted">{t('No payroll calculated in this period.')}</p> : <>
              <Legend items={costKeys.map((k) => ({ key: k, color: colourOf(k) }))} />
              <BarChart bars={costBars} format={usd} height={220} />
            </>}
          </div>
          <div className="cards2">
            <div className="card">
              <h3>{t('Headcount at month end')}</h3>
              <BarChart bars={one(t('Headcount'), (m) => m.headcount)} format={(n) => num(n)} />
            </div>
            <div className="card">
              <h3>{t('Hires and departures')}</h3>
              <Legend items={[{ key: t('Hired'), color: SERIES[0] }, { key: t('Departures'), color: SERIES[1] }]} />
              <BarChart grouped format={(n) => num(n)} bars={ms.map((m) => ({ label: monthLabel(m.month), title: monthLabel(m.month, true),
                segments: [{ key: t('Hired'), value: m.hires, color: SERIES[0] }, { key: t('Departures'), value: m.departures, color: SERIES[1] }] }))} />
            </div>
            <div className="card">
              <h3>{t('Turnover per month')}</h3>
              <BarChart bars={one(t('Turnover'), (m) => m.turnover)} format={(n) => `${num(n, 1)} %`} />
            </div>
            <div className="card">
              <h3>{t('Unpaid absence per month')}</h3>
              <p className="small muted">{t('Share of scheduled time not paid (absences, lateness), from the payslips.')}</p>
              <BarChart bars={one(t('Unpaid absence'), (m) => m.absenceRate)} format={(n) => `${num(n, 1)} %`} />
            </div>
          </div>
          <div className="card">
            <h3>{t('Headcount by direction today')}</h3>
            <ul className="kv-list">{board.directions.map((d) => <li key={d.name}><span><i className="swatch" style={{ background: colourOf(directionOf(d.name)) }} />{d.name}</span><strong>{d.headcount}</strong></li>)}</ul>
          </div>
        </>
      )}
      <p className="small muted"><Link to="/help/dashboards">{t('How these figures are calculated')}</Link></p>
    </section>
  )
}
