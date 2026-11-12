import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { ApiError, api, longDay, openFile } from '../api'
import { useAuth } from '../auth'
import { Button, Form } from './Button'
import { ErrorText } from './ErrorText'
import { Modal } from './Modal'
import { PersonSelect, type FoundPerson } from './PersonSelect'
import { t } from '../i18n'

export type VisitKind = 'HIRING' | 'PROVISIONAL' | 'YEARLY' | 'RETURN' | 'SPECIAL'
export type Result = 'FIT' | 'FIT_RESTRICTED' | 'UNFIT_TEMPORARY' | 'UNFIT'
export type AccidentKind = 'WORK' | 'COMMUTING' | 'DISEASE'

export type Visit = {
  id: number; userId: number; name: string; registrationNo: string; unit: string | null; kind: VisitKind; dueOn: string | null
  visitedOn: string | null; doctor: string | null; result: Result | null; restrictions: string | null; validUntil: string | null
  note: string | null; fileName: string | null; hasFile: boolean; accidentId: number | null; leaveRequestId: number | null
}
export type Accident = {
  id: number; userId: number; name: string; registrationNo: string; unit: string | null; kind: AccidentKind; occurredAt: string
  place: string | null; description: string; witnesses: string | null; injury: string | null; firstAid: string | null
  offFrom: string | null; offTo: string | null; daysOff: number; status: 'REPORTED' | 'RECORDED'; reportedBy: string; reportedAt: string
  declareBy: string; daysLeft: number; declaredOn: string | null; declarationRef: string | null; inspectionCopyOn: string | null
  victimCopyOn: string | null; letterId: number | null; leaveRequestId: number | null; note: string | null
}
type Fitness = { userId: number; name: string; registrationNo: string; result: Result | null; restrictions: string | null; validUntil: string | null; nextDue: string | null }
type Deadline = { kind: string; title: string; due: string; daysLeft: number; legalBasis: string; verified: boolean }

export const VISIT_KINDS: Record<VisitKind, string> = {
  HIRING: t('Hiring visit'), PROVISIONAL: t('Nurse\'s provisional visit'), YEARLY: t('Yearly visit'), RETURN: t('Return to work'), SPECIAL: t('Special watch'),
}
export const RESULTS: Record<Result, string> = {
  FIT: t('Fit'), FIT_RESTRICTED: t('Fit with restrictions'), UNFIT_TEMPORARY: t('Unfit for now'), UNFIT: t('Unfit'),
}
export const ACCIDENT_KINDS: Record<AccidentKind, string> = {
  WORK: t('Accident at work'), COMMUTING: t('Accident on the way to or from work'), DISEASE: t('Occupational disease'),
}

const errorText = (e: unknown) => (e instanceof ApiError ? [e.message, ...Object.values(e.fieldErrors)].join(' ') : t('Something went wrong.'))
const badge = (r: Result | null) => r ? <span className={`badge ${r === 'FIT' ? 'ok' : r === 'UNFIT' ? 'off' : 'warn'}`}>{RESULTS[r]}</span> : <span className="muted">—</span>
const nowLocal = () => {
  const d = new Date()
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}

/** Reports an accident: for oneself, for one's team (a unit head), or for anyone (HR). HR is told at once. */
export function ReportAccidentModal({ onClose, onDone }: { onClose: () => void; onDone: () => void }) {
  const [userId, setUserId] = useState('')
  const [kind, setKind] = useState<AccidentKind>('WORK')
  const [when, setWhen] = useState(nowLocal())
  const [place, setPlace] = useState('')
  const [description, setDescription] = useState('')
  const [injury, setInjury] = useState('')
  const [firstAid, setFirstAid] = useState('')
  const [witnesses, setWitnesses] = useState('')
  const [error, setError] = useState<string | null>(null)
  useEffect(() => {
    // Someone who can only report for themselves finds their own name already chosen.
    api<FoundPerson[]>('/api/health/accidents/reportable?size=2').then((p) => { if (p.length === 1) setUserId(String(p[0].userId)) }).catch((e: Error) => setError(e.message))
  }, [])
  const submit = async () => {
    setError(null)
    try {
      await api('/api/health/accidents/report', { method: 'POST', body: {
        userId: Number(userId), kind, occurredAt: new Date(when).toISOString(), place, description, injury, firstAid, witnesses } })
      onDone()
    } catch (e) { setError(errorText(e)) }
  }
  return (
    <Modal title={t('Report a work accident')} onClose={onClose}
      footer={<><Button className="secondary" type="button" onClick={onClose}>{t('Cancel')}</Button><Button type="submit" form="accident-report">{t('Send the report')}</Button></>}>
      <Form id="accident-report" onSubmit={submit}>
        <label>{t('Who was hurt')}
          <PersonSelect required value={userId} onChange={setUserId} source="/api/health/accidents/reportable" />
        </label>
        <div className="grid2">
          <label>{t('Kind')}
            <select value={kind} onChange={(e) => setKind(e.target.value as AccidentKind)}>
              {(Object.keys(ACCIDENT_KINDS) as AccidentKind[]).map((k) => <option key={k} value={k}>{ACCIDENT_KINDS[k]}</option>)}
            </select>
          </label>
          <label>{t('When')}<input type="datetime-local" required max={nowLocal()} value={when} onChange={(e) => setWhen(e.target.value)} /></label>
        </div>
        <label>{t('Place')}<input maxLength={200} value={place} onChange={(e) => setPlace(e.target.value)} /></label>
        <label>{t('What happened')}<textarea required rows={3} maxLength={4000} value={description} onChange={(e) => setDescription(e.target.value)} /></label>
        <div className="grid2">
          <label>{t('Injury (optional)')}<input maxLength={500} value={injury} onChange={(e) => setInjury(e.target.value)} placeholder={t('For example: left hand')} /></label>
          <label>{t('First aid (optional)')}<input maxLength={500} value={firstAid} onChange={(e) => setFirstAid(e.target.value)} /></label>
        </div>
        <label>{t('Witnesses (optional)')}<input maxLength={500} value={witnesses} onChange={(e) => setWitnesses(e.target.value)} /></label>
        <p className="hint">{t('HR is told at once and completes the declaration.')}</p>
        <ErrorText error={error} />
      </Form>
    </Modal>
  )
}

/** The employee's own medical visits and accidents, and the button to report an accident. */
export function MyHealthCard() {
  const [data, setData] = useState<{ visits: Visit[]; accidents: Accident[] } | null>(null)
  const [reporting, setReporting] = useState(false)
  const [info, setInfo] = useState<string | null>(null)
  const load = () => api<{ visits: Visit[]; accidents: Accident[] }>('/api/health/mine').then(setData).catch(() => undefined)
  useEffect(() => { void load() }, [])
  if (!data) return null
  return (
    <div className="card">
      <div className="row between">
        <h3>{t('Health at work')}</h3>
        <Button className="secondary" onClick={() => setReporting(true)}>{t('Report a work accident')}</Button>
      </div>
      {info && <p className="success">{info}</p>}
      {data.visits.length === 0 && data.accidents.length === 0 && <p className="muted">{t('No medical visit or accident recorded.')}</p>}
      {data.visits.length > 0 && (
        <table>
          <thead><tr><th>{t('Visit')}</th><th>{t('Date')}</th><th>{t('Result')}</th><th>{t('Valid until')}</th></tr></thead>
          <tbody>
            {data.visits.map((v) => (
              <tr key={v.id}>
                <td>{VISIT_KINDS[v.kind]}{v.note && !v.visitedOn && <div className="small muted">{v.note}</div>}</td>
                <td>{v.visitedOn ? longDay(v.visitedOn) : t('Due {a}', { a: longDay(v.dueOn) })}</td>
                <td>{v.visitedOn ? badge(v.result) : '—'}{v.restrictions && <div className="small">{v.restrictions}</div>}</td>
                <td>{longDay(v.validUntil)}{v.hasFile && <div><button type="button" className="link small" onClick={() => void openFile(`/api/health/visits/${v.id}/file`)}>{t('Certificate')}</button></div>}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {data.accidents.length > 0 && (
        <ul className="checklist">
          {data.accidents.map((a) => (
            <li key={a.id}><span style={{ flex: 1 }}>{ACCIDENT_KINDS[a.kind]} · {longDay(a.occurredAt)}<div className="small muted">{a.description}</div></span>
              <span className={`badge ${a.declaredOn ? 'ok' : ''}`}>{a.declaredOn ? t('Declared {a}', { a: longDay(a.declaredOn) }) : t('Being declared')}</span></li>
          ))}
        </ul>
      )}
      {reporting && <ReportAccidentModal onClose={() => setReporting(false)} onDone={() => { setReporting(false); setInfo(t('Reported: HR is told.')); void load() }} />}
    </div>
  )
}

/** A unit head's team: fit or not and the restrictions, nothing else (the medical secret). */
export function TeamFitnessCard() {
  const [rows, setRows] = useState<Fitness[]>([])
  const [reporting, setReporting] = useState(false)
  const [info, setInfo] = useState<string | null>(null)
  useEffect(() => { api<Fitness[]>('/api/health/team').then(setRows).catch(() => undefined) }, [])
  if (rows.length === 0) return null
  const limited = rows.filter((r) => r.result && r.result !== 'FIT')
  return (
    <div className="card">
      <div className="row between">
        <h3>{t('Fitness for work')}</h3>
        <Button className="secondary" onClick={() => setReporting(true)}>{t('Report a work accident')}</Button>
      </div>
      {info && <p className="success">{info}</p>}
      <p className="small muted">{t('Only the result of the last medical visit is shown, never the reason.')}</p>
      {limited.length === 0 ? <p className="muted">{t('No restriction in your team.')}</p> : (
        <table>
          <thead><tr><th>{t('Person')}</th><th>{t('Result')}</th><th>{t('Restrictions')}</th><th>{t('Until')}</th></tr></thead>
          <tbody>
            {limited.map((r) => <tr key={r.userId}><td>{r.name}</td><td>{badge(r.result)}</td><td className="small">{r.restrictions ?? '—'}</td><td>{longDay(r.validUntil)}</td></tr>)}
          </tbody>
        </table>
      )}
      {reporting && <ReportAccidentModal onClose={() => setReporting(false)} onDone={() => { setReporting(false); setInfo(t('Reported: HR is told.')) }} />}
    </div>
  )
}

/** Health and safety deadlines, on the declarations calendar. */
export function HealthDeadlinesCard() {
  const { can } = useAuth()
  const [rows, setRows] = useState<Deadline[]>([])
  useEffect(() => { api<Deadline[]>('/api/health/deadlines').then(setRows).catch(() => undefined) }, [])
  if (rows.length === 0) return null
  return (
    <div className="card">
      <h3>{t('Health and safety deadlines')}</h3>
      <div className="table-wrap">
        <table>
          <thead><tr><th>{t('What')}</th><th>{t('Due')}</th><th>{t('Basis')}</th></tr></thead>
          <tbody>
            {rows.map((d) => (
              <tr key={d.kind + d.title}>
                <td>{can('health.safety') ? <Link to="/health">{d.title}</Link> : d.title}</td>
                <td>{longDay(d.due)}<div><span className={`badge ${d.daysLeft < 0 ? 'off' : d.daysLeft <= 7 ? 'warn' : ''}`}>
                  {d.daysLeft < 0 ? t('Late by {a} days', { a: -d.daysLeft }) : t('In {a} days', { a: d.daysLeft })}</span></div></td>
                <td className="small">{d.legalBasis}{!d.verified && <span className="badge warn" style={{ marginLeft: 6 }}>{t('to verify')}</span>}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}
