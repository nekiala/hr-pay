import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { Plus } from 'lucide-react'
import { api, longDay, money, payRunLink, type PayRunMonth, type PayRunRow, type PayRunStage, type PayRunType } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'
import { PageTitle } from '../components/SettingsLink'
import { PAY_RUN_TYPES, STAGE, typeName } from '../payRunTypes'
import { locale, t } from '../i18n'

const thisMonth = () => new Date().toISOString().slice(0, 7)
const monthName = (m: string) => new Date(m.slice(0, 7) + '-01T00:00:00').toLocaleDateString(locale(), { month: 'long', year: 'numeric' })
const total = (r: PayRunRow) => r.totals.map((x) => money(x.amount, x.currency)).join(' · ') || '—'

export function StageBadge({ stage }: { stage: PayRunStage }) {
  return <span className={`badge ${STAGE[stage].badge}`}>{STAGE[stage].label}</span>
}

/**
 * Every pay run in one place: the month's runs in pay-date order with what holds them up, then all runs of the last
 * twelve months. New pay run picks the type and its conditions.
 */
export default function PayRunsPage() {
  const { can } = useAuth()
  const [params, setParams] = useSearchParams()
  const month = params.get('month') ?? thisMonth()
  const [view, setView] = useState<PayRunMonth | null>(null)
  const [all, setAll] = useState<PayRunRow[]>([])
  const [type, setType] = useState<PayRunType | ''>('')
  const [stage, setStage] = useState<PayRunStage | ''>('')
  const [error, setError] = useState<string | null>(null)

  useEffect(() => { api<PayRunRow[]>('/api/pay-runs').then(setAll).catch((e: Error) => setError(e.message)) }, [])
  useEffect(() => { api<PayRunMonth>(`/api/pay-runs/month/${month}`).then(setView).catch((e: Error) => setError(e.message)) }, [month])

  const timeline = [...(view?.runs ?? [])].sort((a, b) => (a.payDate ?? a.month).localeCompare(b.payDate ?? b.month))
  const shown = all.filter((r) => (!type || r.type === type) && (!stage || r.stage === stage))
  const waiting = all.filter((r) => r.stage === 'TO_APPROVE' || r.stage === 'TO_CLOSE').length

  return (
    <section>
      <PageTitle title={t('Pay runs')}>
        {can('payroll.run') && <Link className="button" to={`/payroll/new?month=${month}`}><Plus size={16} aria-hidden />{' '}{t('New pay run')}</Link>}
      </PageTitle>
      <ErrorText error={error} />

      <div className="card">
        <div className="row space">
          <h3>{monthName(month)}</h3>
          <label className="month-pick">{t('Month')}{' '}
            <input type="month" value={month} onChange={(e) => e.target.value && setParams({ month: e.target.value })} /></label>
        </div>
        {timeline.length === 0 && <p className="muted">{t('No pay run for this month yet.')}</p>}
        {timeline.length > 0 && (
          <ol className="timeline">
            {timeline.map((r) => (
              <li key={r.type + r.key}>
                <Link to={payRunLink(r)}>
                  <span className="when">{r.payDate ? longDay(r.payDate) : monthName(r.month)}</span>
                  <strong>{r.title}</strong>
                  <span>{t('{n} people', { n: r.people })} · {total(r)}</span>
                  <span><StageBadge stage={r.stage} /></span>
                </Link>
              </li>
            ))}
          </ol>
        )}
        {(view?.notices ?? []).map((n, i) => (
          <p key={i} className={n.level === 'WARN' ? 'notice' : 'hint'}>{n.text}{' '}
            <Link to={n.type === 'LEAVE_PAY' && !view?.runs.some((r) => r.type === 'LEAVE_PAY') ? `/payroll/new?type=LEAVE_PAY`
              : n.type === 'MONTHLY' && !view?.runs.some((r) => r.type === 'MONTHLY') ? `/payroll/new?type=MONTHLY&month=${month}`
                : payRunLink(n)}>{t('Open')}</Link></p>
        ))}
      </div>

      <div className="card">
        <div className="row space">
          <h3>{t('All pay runs')}{waiting > 0 ? ` · ${t('{n} waiting', { n: waiting })}` : ''}</h3>
          <div className="row">
            <label className="inline-filter">{t('Type')}<select value={type} onChange={(e) => setType(e.target.value as PayRunType | '')}>
              <option value="">{t('All')}</option>
              {PAY_RUN_TYPES.map((x) => <option key={x.type} value={x.type}>{x.name}</option>)}
            </select></label>
            <label className="inline-filter">{t('Status')}<select value={stage} onChange={(e) => setStage(e.target.value as PayRunStage | '')}>
              <option value="">{t('All')}</option>
              {(Object.keys(STAGE) as PayRunStage[]).map((s) => <option key={s} value={s}>{STAGE[s].label}</option>)}
            </select></label>
          </div>
        </div>
        {shown.length === 0 ? <p className="muted">{t('None yet.')}</p> : (
          <div className="table-wrap">
            <table>
              <thead><tr><th>{t('Month')}</th><th>{t('Type')}</th><th>{t('Pay date')}</th><th className="num">{t('People')}</th><th className="num">{t('Total')}</th><th>{t('Status')}</th><th>{t('Worked out by')}</th><th /></tr></thead>
              <tbody>
                {shown.map((r) => (
                  <tr key={r.type + r.key}>
                    <td>{monthName(r.month)}</td>
                    <td>{r.type === 'SETTLEMENT' || r.type === 'THIRTEENTH' ? r.title : typeName(r.type)}</td>
                    <td>{r.payDate ? longDay(r.payDate) : '—'}</td>
                    <td className="num">{r.people}</td>
                    <td className="num">{total(r)}</td>
                    <td><StageBadge stage={r.stage} /></td>
                    <td className="small">{r.calculatedBy ?? ''}</td>
                    <td><Link to={payRunLink(r)}>{t('Open')}</Link></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </section>
  )
}
