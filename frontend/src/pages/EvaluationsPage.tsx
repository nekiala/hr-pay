import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { ApiError, EVAL_STATUS, api, type Evaluation } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'

type ToDo = { userId: number; name: string; template?: string; periodStart?: string; periodEnd?: string; evaluationId?: number | null; problem?: string }

const pct = (n: number | null) => (n === null ? '—' : `${n}%`)

function Table({ rows, showEmployee }: { rows: Evaluation[]; showEmployee: boolean }) {
  if (rows.length === 0) return <p className="muted">None.</p>
  return (
    <div className="table-wrap">
      <table>
        <thead><tr>{showEmployee && <th>Employee</th>}<th>Period</th><th>Score</th><th>Status</th><th>Evaluator</th><th></th></tr></thead>
        <tbody>
          {rows.map((e) => (
            <tr key={e.id}>
              {showEmployee && <td>{e.userName}</td>}
              <td>{e.periodStart} to {e.periodEnd}</td>
              <td>{pct(e.totalPercent)}</td>
              <td>{EVAL_STATUS[e.status]}</td>
              <td>{e.evaluatorName}</td>
              <td><Link to={`/evaluations/${e.id}`}>Open</Link></td>
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
      setError(e instanceof ApiError ? e.message : 'Something went wrong.')
    }
  }

  const waiting = mine.filter((e) => e.status === 'SUBMITTED')

  return (
    <section>
      <h2>Evaluations</h2>
      <ErrorText error={error} />
      {waiting.length > 0 && (
        <p className="notice">Your manager has scored you for {waiting.map((e) => `${e.periodStart} to ${e.periodEnd}`).join(', ')}. <Link to={`/evaluations/${waiting[0].id}`}>Read and answer</Link>.</p>
      )}

      {scorer && (
        <div className="card">
          <h3>This period</h3>
          {toDo.length === 0 ? <p className="muted">Nobody to evaluate.</p> : (
            <div className="table-wrap">
              <table>
                <thead><tr><th>Employee</th><th>Period</th><th></th></tr></thead>
                <tbody>
                  {toDo.map((t) => (
                    <tr key={t.userId}>
                      <td>{t.name}</td>
                      <td>{t.problem ?? `${t.periodStart} to ${t.periodEnd} · ${t.template}`}</td>
                      <td>{!t.problem && (t.evaluationId ? <Link to={`/evaluations/${t.evaluationId}`}>Open</Link> : <button className="secondary" onClick={() => void start(t.userId)}>Start</button>)}</td>
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
          <h3>{hr ? 'All evaluations' : 'My team'}</h3>
          <label className="month-pick">Show
            <select value={status} onChange={(e) => setStatus(e.target.value)}>
              <option value="">All</option>
              {Object.entries(EVAL_STATUS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
            </select>
          </label>
          <Table rows={team} showEmployee />
        </div>
      )}

      <div className="card">
        <h3>My evaluations</h3>
        <Table rows={mine} showEmployee={false} />
      </div>
      {can('evaluation.manage') && <p><Link to="/evaluations/settings">Criteria and templates</Link></p>}
    </section>
  )
}
