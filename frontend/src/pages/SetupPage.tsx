import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { Check, Circle, Rocket } from 'lucide-react'
import { api, longDay } from '../api'
import { Button } from '../components/Button'
import { ErrorText } from '../components/ErrorText'
import { t } from '../i18n'

type Step = { key: string; done: boolean; manual: boolean; link: string; count: number | null; markedAt: string | null; markedBy: string | null }
type Guide = { steps: Step[]; done: number; total: number; hidden: boolean }

const STEPS: Record<string, [string, string]> = {
  company: [t('Company details'), t('Legal name, address, CNSS and tax numbers, logo: they appear on payslips and declarations.')],
  sites: [t('Workplaces'), t('Each site with its GPS area or IP addresses, for check-in.')],
  structure: [t('Structure'), t('Directions, departments and services with their heads: approvals follow them.')],
  schedules: [t('Working hours'), t('Check the planned hours per day; they drive pay and lateness.')],
  grades: [t('Grades and salaries'), t('Check the basic salary of each grade, from your collective agreement.')],
  rates: [t('Statutory rates'), t('Have your accountant check the CNSS, IPR, INPP and ONEM rates before the first payroll.')],
  payroll_settings: [t('Pay elements'), t('Check the bonuses and allowances you pay, and their rules.')],
  holidays: [t("This year's holidays"), t('The public holidays, with the date the government actually set.')],
  employees: [t('Employees'), t('Import them from Excel, or add them one by one.')],
  first_payroll: [t('First payroll'), t('Calculate it, have Finance approve it, then close the month.')],
}

/** The set-up guide: on its own page, or as a card on the home page until it is done or put away. */
export function SetupGuideCard({ home = false }: { home?: boolean }) {
  const [guide, setGuide] = useState<Guide | null>(null)
  const [error, setError] = useState<string | null>(null)
  const load = () => api<Guide>('/api/setup').then(setGuide).catch((e: Error) => setError(e.message))
  useEffect(() => { void load() }, [])
  if (!guide) return home ? null : <ErrorText error={error} />
  if (home && (guide.hidden || guide.done === guide.total)) return null

  const act = async (path: string, method = 'POST') => {
    setError(null)
    try { await api(path, { method }); await load() } catch (e) { setError((e as Error).message) }
  }
  const next = guide.steps.find((s) => !s.done)
  const steps = home ? guide.steps.filter((s) => !s.done).slice(0, 3) : guide.steps
  return (
    <div className="card setup-guide">
      <div className="card-head">
        <div>
          <h3><Rocket size={18} aria-hidden />{' '}{t('Set up your company')}</h3>
          <p>{t('{a} of {b} steps done', { a: guide.done, b: guide.total })}</p>
        </div>
        {home
          ? <div className="row"><Link className="button secondary" to="/setup">{t('See all steps')}</Link><Button className="link" onClick={() => act('/api/setup/hide')}>{t('Hide')}</Button></div>
          : guide.hidden && <Button className="secondary" onClick={() => act('/api/setup/show')}>{t('Show on the home page')}</Button>}
      </div>
      <div className="progress" role="progressbar" aria-valuemin={0} aria-valuemax={guide.total} aria-valuenow={guide.done}>
        <span style={{ width: `${(100 * guide.done) / guide.total}%` }} />
      </div>
      <ErrorText error={error} />
      <ol className="setup-steps">
        {steps.map((s) => (
          <li key={s.key} className={s.done ? 'done' : s === next ? 'next' : ''}>
            <span className="setup-mark" aria-hidden>{s.done ? <Check size={14} /> : <Circle size={10} />}</span>
            <div style={{ flex: 1 }}>
              <Link to={s.link}><strong>{STEPS[s.key]?.[0] ?? s.key}</strong></Link>
              <div className="small muted">{STEPS[s.key]?.[1]}{s.markedBy && <> {t('Checked by {a} on {b}.', { a: s.markedBy, b: longDay(s.markedAt) })}</>}</div>
            </div>
            {s.manual && (s.done
              ? !home && <Button className="link" onClick={() => act(`/api/setup/steps/${s.key}`, 'DELETE')}>{t('Not done')}</Button>
              : <Button className="secondary" onClick={() => act(`/api/setup/steps/${s.key}`)}>{t('Mark as checked')}</Button>)}
          </li>
        ))}
      </ol>
    </div>
  )
}

export default function SetupPage() {
  return (
    <section>
      <p className="muted small"><Link to="/help/first-steps">{t('How it works')}</Link></p>
      <h2>{t('Set-up guide')}</h2>
      <SetupGuideCard />
    </section>
  )
}
