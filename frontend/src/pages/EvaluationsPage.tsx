import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { ApiError, EVAL_STATUS, api, type Evaluation } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'
import { Button } from '../components/Button'
import { t } from '../i18n'
import { PageTitle, SettingsLink } from '../components/SettingsLink'

type ToDo = { userId: number; name: string; template?: string; periodStart?: string; periodEnd?: string; evaluationId?: number | null; problem?: string }

const pct = (n: number | null) => (n === null ? '—' : `${n}%`)

function Table({ rows, showEmployee }: { rows: Evaluation[]; showEmployee: boolean }) {
  if (rows.length === 0) return <p className="muted">{t('None.')}</p>
  return (
    <div className="table-wrap">
      <table>
        <thead><tr>{showEmployee && <th>{t('Employee')}</th>}<th>{t('Period')}</th><th>{t('Score')}</th><th>{t('Status')}</th><th>{t('Evaluator')}</th><th></th></tr></thead>
        <tbody>
          {rows.map((e) => (
            <tr key={e.id}>
              {showEmployee && <td>{e.userName}</td>}
              <td>{e.periodStart}{' '}{t('to')}{' '}{e.periodEnd}</td>
              <td>{pct(e.totalPercent)}</td>
              <td>{EVAL_STATUS[e.status]}</td>
              <td>{e.evaluatorName}</td>
              <td><Link to={`/evaluations/${e.id}`}>{t('Open')}</Link></td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

export default function EvaluationsPage() {
  const { can } = useAuth()
  const navigate = useNavigate()
  const hr = can('evaluation.manage') || can('evaluation.approve')
  const scorer = hr || can('evaluation.score.team')
  const [mine, setMine] = useState<Evaluation[]>([])
  const [team, setTeam] = useState<Evaluation[]>([])
  const [toDo, setToDo] = useState<ToDo[]>([])
  const [status, setStatus] = useState(hr ? 'ACKNOWLEDGED' : '')
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    api<Evaluation[]>('/api/evaluations?scope=mine').then(setMine).catch((e: Error) => setError(e.message))
    if (scorer) api<ToDo[]>('/api/evaluations/to-do').then(setToDo).catch((e: Error) => setError(e.message))
  }, [scorer])
  useEffect(() => {
    if (!scorer) return
    const params = new URLSearchParams({ scope: hr ? 'all' : 'team' })
    if (status) params.set('status', status)
    api<Evaluation[]>(`/api/evaluations?${params}`).then(setTeam).catch((e: Error) => setError(e.message))
  }, [scorer, hr, status])

  const start = async (userId: number) => {
    setError(null)
    try {
      const e = await api<Evaluation>('/api/evaluations', { method: 'POST', body: { userId } })
      navigate(`/evaluations/${e.id}`)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : t('Something went wrong.'))
    }
  }

  const waiting = mine.filter((e) => e.status === 'SUBMITTED')

  return (
    <section>
      <PageTitle title={t('Evaluations')}>{can('evaluation.manage') && <SettingsLink to="/evaluations/settings" />}</PageTitle>
      <ErrorText error={error} />
      {waiting.length > 0 && (
        <p className="notice">{t('Your manager has scored you for')}{' '}{waiting.map((e) => t('{a} to {b}', { a: e.periodStart, b: e.periodEnd })).join(', ')}. <Link to={`/evaluations/${waiting[0].id}`}>{t('Read and answer')}</Link>.</p>
      )}

      {scorer && (
        <div className="card">
          <h3>{t('This period')}</h3>
          {toDo.length === 0 ? <p className="muted">{t('Nobody to evaluate.')}</p> : (
            <div className="table-wrap">
              <table>
                <thead><tr><th>{t('Employee')}</th><th>{t('Period')}</th><th></th></tr></thead>
                <tbody>
                  {toDo.map((tItem) => (
                    <tr key={tItem.userId}>
                      <td>{tItem.name}</td>
                      <td>{tItem.problem ?? t('{a} to {b} · {c}', { a: tItem.periodStart, b: tItem.periodEnd, c: tItem.template })}</td>
                      <td>{!tItem.problem && (tItem.evaluationId ? <Link to={`/evaluations/${tItem.evaluationId}`}>{t('Open')}</Link> : <Button className="secondary" onClick={() => start(tItem.userId)}>{t('Start')}</Button>)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}

      {scorer && (
        <div className="card">
          <h3>{hr ? t('All evaluations') : t('My team')}</h3>
          <label className="month-pick">{t('Show')}<select value={status} onChange={(e) => setStatus(e.target.value)}>
              <option value="">{t('All')}</option>
              {Object.entries(EVAL_STATUS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
            </select>
          </label>
          <Table rows={team} showEmployee />
        </div>
      )}

      <div className="card">
        <h3>{t('My evaluations')}</h3>
        <Table rows={mine} showEmployee={false} />
      </div>
    </section>
  )
}
