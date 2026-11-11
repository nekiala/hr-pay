import { useEffect, useState } from 'react'
import { api, longDay, openFile } from '../api'
import { useAuth } from '../auth'
import { Button } from './Button'
import { locale, t } from '../i18n'

type Step = {
  pathId: number; toId: number; toName: string; minYears: number; years: number; yearsMet: boolean; minScore: number | null
  score: number | null; scoreMet: boolean; requirement: string | null; eligible: boolean; freePosts: number
}
type NextSteps = { occupationId: number | null; occupationName: string | null; inJobSince: string | null; years: number | null; score: number | null; evaluations: number; steps: Step[] }

const num = (n: number | null) => n == null ? '—' : Number(n).toLocaleString(locale())
const mark = (ok: boolean) => <span className={ok ? 'ok-text' : 'muted'} aria-label={ok ? t('met') : t('not met')}>{ok ? '✓' : '✗'}</span>

/** The warning shown before saving a contract when the person's unit has no free post of that job. */
export function PostWarning({ userId, occupationId, on }: { userId: string | number; occupationId: string; on: string }) {
  const [warning, setWarning] = useState<string | null>(null)
  useEffect(() => {
    setWarning(null)
    if (!occupationId) return
    const q = new URLSearchParams({ userId: String(userId), occupationId })
    if (on) q.set('on', on)
    api<{ warning?: string }>(`/api/positions/check?${q}`).then((r) => setWarning(r.warning ?? null)).catch(() => undefined)
  }, [userId, occupationId, on])
  return warning ? <p className="notice small">{warning}</p> : null
}

/** On the person's file: the job, its description, and the next jobs on the ladder with the conditions met or not. */
export function NextStepsCard({ userId }: { userId: string }) {
  const { can } = useAuth()
  const [data, setData] = useState<NextSteps | null>(null)
  useEffect(() => { api<NextSteps>(`/api/job-catalogue/people/${userId}/next-steps`).then(setData).catch(() => undefined) }, [userId])
  if (!data || !data.occupationId) return null
  return (
    <div className="card">
      <div className="row space">
        <h3>{t('Job and next steps')}</h3>
        <Button className="secondary" onClick={() => void openFile(`/api/job-catalogue/people/${userId}/pdf`)}>{t('Job description (PDF)')}</Button>
      </div>
      <p>{data.occupationName}{data.inJobSince && <span className="muted"> · {t('in the job since {a} ({b} years)', { a: longDay(data.inJobSince), b: data.years ?? 0 })}</span>}</p>
      <p className="small muted">{data.score == null ? t('No approved evaluation in the last two years.')
        : t('Average of {a} approved evaluations over two years: {b} %', { a: data.evaluations, b: data.score })}</p>
      {data.steps.length === 0 ? <p className="small muted">{can('jobs.manage') ? t('No next job is set for this job: add one under Jobs and positions, Career ladders.') : t('No next job is set for this job.')}</p> : (
        <table>
          <thead><tr><th>{t('Next job')}</th><th>{t('Years in the job')}</th><th>{t('Evaluation average')}</th><th>{t('Also needed')}</th><th /></tr></thead>
          <tbody>
            {data.steps.map((s) => (
              <tr key={s.pathId}>
                <td>{s.toName}{s.freePosts > 0 && <div className="small muted">{t('{a} free posts', { a: s.freePosts })}</div>}</td>
                <td>{mark(s.yearsMet)} {num(s.years)} / {num(s.minYears)}</td>
                <td>{s.minScore == null ? '—' : <>{mark(s.scoreMet)} {num(s.score)} / {num(s.minScore)} %</>}</td>
                <td className="small">{s.requirement ?? '—'}</td>
                <td>{s.eligible ? <span className="badge ok">{t('Conditions met')}</span> : <span className="badge">{t('Not yet')}</span>}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  )
}

/** On My documents: one's own job description. */
export function MyJobCard() {
  const [job, setJob] = useState<{ occupationId: number; name: string; described: boolean } | null>(null)
  useEffect(() => { api<typeof job>('/api/job-catalogue/mine').then((j) => setJob(j ?? null)).catch(() => undefined) }, [])
  if (!job || !job.described) return null
  return (
    <div className="card">
      <div className="row space">
        <div><h3>{t('My job description')}</h3><p className="small muted">{job.name}</p></div>
        <Button className="secondary" onClick={() => void openFile('/api/job-catalogue/mine/pdf')}>{t('Job description (PDF)')}</Button>
      </div>
    </div>
  )
}
