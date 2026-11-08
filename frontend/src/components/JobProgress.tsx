import { useEffect, useState } from 'react'
import { NavLink } from 'react-router-dom'
import { X } from 'lucide-react'
import { jobFinished, type Job, type JobEvent } from '../api'
import { t } from '../i18n'

/**
 * The jobs this page started that run in the background (a payroll of thousands of people): a progress bar at the
 * bottom of the screen, on every page, until each ends. The person can go on working meanwhile.
 */
export function JobProgress() {
  const [jobs, setJobs] = useState<Record<number, Job>>({})
  useEffect(() => {
    const on = (e: Event) => {
      const job = (e as JobEvent).detail
      setJobs((all) => ({ ...all, [job.id]: job }))
      if (jobFinished(job)) {
        window.setTimeout(() => setJobs((all) => { const { [job.id]: _, ...rest } = all; return rest }), job.status === 'DONE' ? 4000 : 10000)
      }
    }
    window.addEventListener('hrpay:job', on)
    return () => window.removeEventListener('hrpay:job', on)
  }, [])
  const list = Object.values(jobs)
  if (list.length === 0) return null
  return (
    <div className="job-progress no-print" role="status" aria-live="polite">
      {list.map((j) => (
        <div key={j.id} className={`job-card${j.status === 'FAILED' ? ' failed' : ''}`}>
          <div className="row between">
            <strong>{j.title}</strong>
            <button type="button" className="icon-button" aria-label={t('Hide')}
              onClick={() => setJobs((all) => { const { [j.id]: _, ...rest } = all; return rest })}><X size={14} aria-hidden /></button>
          </div>
          {j.status === 'FAILED' ? <p className="error">{j.error}</p> : (
            <>
              <div className="progress"><span style={{ width: `${j.status === 'DONE' ? 100 : Math.max(3, j.progress)}%` }} /></div>
              <p className="small muted">
                {j.status === 'WAITING' ? t('Waiting for a free worker…') : j.status === 'DONE' ? t('Done.') : `${j.progress}%${j.step ? ` · ${j.step}` : ''}`}
              </p>
              {!jobFinished(j) && <p className="small">{t('You can leave this page: the bell tells you when it ends.')}{' '}<NavLink to="/jobs">{t('My jobs')}</NavLink></p>}
            </>
          )}
        </div>
      ))}
    </div>
  )
}
