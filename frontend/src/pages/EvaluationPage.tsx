import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { ApiError, EVAL_STATUS, api, type Evaluation } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'

export default function EvaluationPage() {
  const { id } = useParams()
  const { me, can } = useAuth()
  const [ev, setEv] = useState<Evaluation | null>(null)
  const [scores, setScores] = useState<Record<number, { score: string; comment: string }>>({})
  const [managerComment, setManagerComment] = useState('')
  const [answer, setAnswer] = useState('')
  const [hrComment, setHrComment] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)

  const show = (e: Evaluation) => {
    setEv(e)
    setScores(Object.fromEntries((e.scores ?? []).map((s) => [s.criterionId, { score: s.score === null ? '' : String(s.score), comment: s.comment ?? '' }])))
    setManagerComment(e.managerComment ?? '')
  }
  useEffect(() => { api<Evaluation>(`/api/evaluations/${id}`).then(show).catch((e: Error) => setError(e.message)) }, [id])

  const run = async (action: () => Promise<Evaluation>, done: string) => {
    setError(null)
    setMessage(null)
    try {
      show(await action())
      setMessage(done)
    } catch (e) {
      setError(e instanceof ApiError ? [e.message, ...Object.values(e.fieldErrors)].join(' ') : 'Something went wrong.')
    }
  }

  if (!ev) return <section><ErrorText error={error} />{!error && <p className="muted">Loading…</p>}</section>

  const isEmployee = me?.id === ev.userId
  const canScore = !isEmployee && (me?.id === ev.evaluatorId || can('evaluation.manage') || can('evaluation.approve')) && ['DRAFT', 'SUBMITTED', 'CONTESTED'].includes(ev.status)
  const canApprove = !isEmployee && can('evaluation.approve') && ['SUBMITTED', 'ACKNOWLEDGED', 'CONTESTED'].includes(ev.status)
  const save = (submit: boolean) => run(() => api<Evaluation>(`/api/evaluations/${ev.id}/scores`, {
    method: 'PUT',
    body: {
      submit, managerComment: managerComment || null,
      scores: (ev.scores ?? []).filter((s) => s.source === 'MANAGER').map((s) => ({
        criterionId: s.criterionId, score: scores[s.criterionId]?.score === '' ? null : Number(scores[s.criterionId]?.score), comment: scores[s.criterionId]?.comment || null,
      })),
    },
  }), submit ? 'Submitted. The employee is asked to read it.' : 'Saved as draft.')

  const preview = (ev.scores ?? []).reduce((sum, s) => {
    const v = s.source === 'MANAGER' ? Number(scores[s.criterionId]?.score || 0) : Number(s.score ?? 0)
    return sum + (v * s.weight) / 100
  }, 0)

  return (
    <section className="narrow">
      <p><Link to="/evaluations">← Evaluations</Link></p>
      <h2>{ev.userName} · {ev.periodStart} to {ev.periodEnd}</h2>
      <p className="muted">{ev.templateName} · evaluated by {ev.evaluatorName} · <strong>{EVAL_STATUS[ev.status]}</strong></p>
      <ErrorText error={error} />
      {message && <p className="success">{message}</p>}

      <div className="card">
        <div className="table-wrap">
          <table>
            <thead><tr><th>Criterion</th><th>Weight</th><th>Score (0–100)</th><th>Comment</th></tr></thead>
            <tbody>
              {(ev.scores ?? []).map((s) => (
                <tr key={s.criterionId}>
                  <td>{s.name}{s.source === 'ATTENDANCE' && <div className="hint">From attendance</div>}</td>
                  <td>{s.weight}%</td>
                  <td>
                    {canScore && s.source === 'MANAGER'
                      ? <input type="number" min={0} max={100} step={1} className="narrow-input" value={scores[s.criterionId]?.score ?? ''}
                          onChange={(e) => setScores({ ...scores, [s.criterionId]: { ...scores[s.criterionId], score: e.target.value } })} />
                      : (s.score ?? '—')}
                  </td>
                  <td>
                    {canScore && s.source === 'MANAGER'
                      ? <input value={scores[s.criterionId]?.comment ?? ''} maxLength={500}
                          onChange={(e) => setScores({ ...scores, [s.criterionId]: { ...scores[s.criterionId], comment: e.target.value } })} />
                      : <span className="small">{s.comment}</span>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <p><strong>Total: {ev.totalPercent !== null && !canScore ? `${ev.totalPercent}%` : `${preview.toFixed(2)}%`}</strong>{canScore && <span className="hint"> (updates as you type)</span>}</p>

        {canScore ? (
          <>
            <label>Comment for the employee<textarea rows={3} maxLength={1000} value={managerComment} onChange={(e) => setManagerComment(e.target.value)} /></label>
            {ev.status === 'CONTESTED' && <p className="notice">The employee contested: “{ev.employeeComment}”. Revise and submit again, or leave it for HR to decide.</p>}
            <div className="row">
              <button className="secondary" onClick={() => void save(false)}>Save draft</button>
              <button onClick={() => void save(true)}>{ev.status === 'DRAFT' ? 'Submit to the employee' : 'Submit again'}</button>
            </div>
          </>
        ) : ev.managerComment && <p><strong>Manager:</strong> {ev.managerComment}</p>}
        {ev.employeeComment && !canScore && <p><strong>Employee:</strong> {ev.employeeComment}</p>}
        {ev.hrComment && <p><strong>HR:</strong> {ev.hrComment}</p>}
      </div>

      {isEmployee && ev.status === 'SUBMITTED' && (
        <div className="card">
          <h3>Your answer</h3>
          <label>Comment (needed if you disagree)<textarea rows={3} maxLength={1000} value={answer} onChange={(e) => setAnswer(e.target.value)} /></label>
          <div className="row">
            <button onClick={() => void run(() => api<Evaluation>(`/api/evaluations/${ev.id}/answer`, { method: 'POST', body: { acknowledge: true, comment: answer || null } }), 'Thank you. HR will finalise it.')}>I acknowledge</button>
            <button className="secondary" onClick={() => void run(() => api<Evaluation>(`/api/evaluations/${ev.id}/answer`, { method: 'POST', body: { acknowledge: false, comment: answer } }), 'Sent back to your manager and HR.')}>I disagree</button>
          </div>
        </div>
      )}

      {canApprove && (
        <div className="card">
          <h3>HR decision</h3>
          <label>{ev.status === 'CONTESTED' ? 'Decision on the contest (required)' : 'Comment (optional)'}<textarea rows={2} maxLength={1000} value={hrComment} onChange={(e) => setHrComment(e.target.value)} /></label>
          <button onClick={() => void run(() => api<Evaluation>(`/api/evaluations/${ev.id}/approve`, { method: 'POST', body: { hrComment: hrComment || null } }), 'Approved. It now counts for the 13th month.')}>Approve with this score</button>
        </div>
      )}
    </section>
  )
}
