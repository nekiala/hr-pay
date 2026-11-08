import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { ApiError, EVAL_STATUS, api, type Evaluation } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'
import { Button } from '../components/Button'
import { t } from '../i18n'

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
      return true
    } catch (e) {
      setError(e instanceof ApiError ? [e.message, ...Object.values(e.fieldErrors)].join(' ') : t('Something went wrong.'))
      return false
    }
  }

  if (!ev) return <section><ErrorText error={error} />{!error && <p className="muted">{t('Loading…')}</p>}</section>

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
  }), submit ? t('Submitted. The employee is asked to read it.') : t('Saved as draft.'))

  const preview = (ev.scores ?? []).reduce((sum, s) => {
    const v = s.source === 'MANAGER' ? Number(scores[s.criterionId]?.score || 0) : Number(s.score ?? 0)
    return sum + (v * s.weight) / 100
  }, 0)

  return (
    <section>
      <p><Link to="/evaluations">{t('← Evaluations')}</Link></p>
      <h2>{ev.userName} · {ev.periodStart}{' '}{t('to')}{' '}{ev.periodEnd}</h2>
      <p className="muted">{ev.templateName}{' '}{t('· evaluated by')}{' '}{ev.evaluatorName} · <strong>{EVAL_STATUS[ev.status]}</strong></p>
      <ErrorText error={error} />
      {message && <p className="success">{message}</p>}

      <div className="card">
        <div className="table-wrap">
          <table>
            <thead><tr><th>{t('Criterion')}</th><th>{t('Weight')}</th><th>{t('Score (0–100)')}</th><th>{t('Comment')}</th></tr></thead>
            <tbody>
              {(ev.scores ?? []).map((s) => (
                <tr key={s.criterionId}>
                  <td>{s.name}{s.source === 'ATTENDANCE' && <div className="hint">{t('From attendance')}</div>}</td>
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
        <p><strong>{t('Total:')}{' '}{ev.totalPercent !== null && !canScore ? `${ev.totalPercent}%` : `${preview.toFixed(2)}%`}</strong>{canScore && <span className="hint">{' '}{t('(updates as you type)')}</span>}</p>

        {canScore ? (
          <>
            <label>{t('Comment for the employee')}<textarea rows={3} maxLength={1000} value={managerComment} onChange={(e) => setManagerComment(e.target.value)} /></label>
            {ev.status === 'CONTESTED' && <p className="notice">{t('The employee contested: “')}{ev.employeeComment}{t('”. Revise and submit again, or leave it for HR to decide.')}</p>}
            <div className="row">
              <Button className="secondary" onClick={() => save(false)}>{t('Save draft')}</Button>
              <Button onClick={() => save(true)}>{ev.status === 'DRAFT' ? t('Submit to the employee') : t('Submit again')}</Button>
            </div>
          </>
        ) : ev.managerComment && <p><strong>{t('Manager:')}</strong> {ev.managerComment}</p>}
        {ev.employeeComment && !canScore && <p><strong>{t('Employee:')}</strong> {ev.employeeComment}</p>}
        {ev.hrComment && <p><strong>{t('HR:')}</strong> {ev.hrComment}</p>}
      </div>

      {isEmployee && ev.status === 'SUBMITTED' && (
        <div className="card">
          <h3>{t('Your answer')}</h3>
          <label>{t('Comment (needed if you disagree)')}<textarea rows={3} maxLength={1000} value={answer} onChange={(e) => setAnswer(e.target.value)} /></label>
          <div className="row">
            <Button onClick={() => run(() => api<Evaluation>(`/api/evaluations/${ev.id}/answer`, { method: 'POST', body: { acknowledge: true, comment: answer || null } }), t('Thank you. HR will finalise it.'))}>{t('I acknowledge')}</Button>
            <Button className="secondary" onClick={() => run(() => api<Evaluation>(`/api/evaluations/${ev.id}/answer`, { method: 'POST', body: { acknowledge: false, comment: answer } }), t('Sent back to your manager and HR.'))}>{t('I disagree')}</Button>
          </div>
        </div>
      )}

      {canApprove && (
        <div className="card">
          <h3>{t('HR decision')}</h3>
          <label>{ev.status === 'CONTESTED' ? t('Decision on the contest (required)') : t('Comment (optional)')}<textarea rows={2} maxLength={1000} value={hrComment} onChange={(e) => setHrComment(e.target.value)} /></label>
          <Button onClick={() => run(() => api<Evaluation>(`/api/evaluations/${ev.id}/approve`, { method: 'POST', body: { hrComment: hrComment || null } }), t('Approved. It now counts for the 13th month.'))}>{t('Approve with this score')}</Button>
        </div>
      )}
    </section>
  )
}
