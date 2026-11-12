import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { ApiError, api, download, fileToBase64, longDay, openFile } from '../api'
import { Button, Form } from '../components/Button'
import { useAsk } from '../components/Dialogs'
import { DateInput } from '../components/DateFields'
import { ErrorText } from '../components/ErrorText'
import { ACCIDENT_KINDS, type Accident, type AccidentKind, ReportAccidentModal, RESULTS, type Result, VISIT_KINDS, type Visit, type VisitKind } from '../components/HealthCards'
import { Modal } from '../components/Modal'
import { PageTitle } from '../components/SettingsLink'
import { PersonSelect } from '../components/PersonSelect'
import { t } from '../i18n'

type Due = {
  userId: number; name: string; registrationNo: string; unit: string | null; site: string | null; kind: VisitKind
  dueOn: string; daysLeft: number; plannedId: number | null; lastVisitOn: string | null; lastResult: Result | null; restrictions: string | null
}
type Service = { organisation: 'OWN' | 'SHARED' | 'OUTSIDE'; headcount: number; required: string; verified: boolean }
type VisitPage = { due: Due[]; recent: Visit[]; service: Service }
type Figures = {
  year: number; accidents: number; withTimeOff: number; daysLost: number; headcount: number; hoursWorked: number
  frequencyRate: number | null; severityRate: number | null; byMonth: { month: string; accidents: number; daysLost: number }[]
  byUnit: { unit: string; accidents: number; daysLost: number }[]
}
type AccidentPage = { year: number; accidents: Accident[]; undeclared: Accident[]; figures: Figures }
type Role = 'PRESIDENT' | 'SECRETARY' | 'DOCTOR' | 'TRAINING' | 'WORKER'
type Member = { id: number; userId: number | null; name: string; role: Role; fromOn: string; termEnd: string; endedOn: string | null; active: boolean }
type EventKind = 'MEETING' | 'INSPECTION' | 'REPORT' | 'MEDICAL_REPORT'
type SafetyEvent = { id: number; kind: EventKind; heldOn: string; year: number | null; title: string; note: string | null; fileName: string | null; hasFile: boolean }
type SafetyAction = { id: number; eventId: number | null; accidentId: number | null; risk: string; action: string; ownerId: number | null; ownerName: string | null; dueOn: string | null; doneOn: string | null; late: boolean }
type Committee = {
  headcount: number; from: number; required: boolean; workerSeats: number | null; workers: number; lastMeeting: string | null
  nextMeetingDue: string | null; lastInspection: string | null; nextInspectionDue: string | null; alerts: string[]
  members: Member[]; events: SafetyEvent[]; actions: SafetyAction[]
}
type Saved<T> = { item: T; warnings: string[] }
type Tab = 'visits' | 'accidents' | 'committee'

const ROLES: Record<Role, string> = {
  PRESIDENT: t('President (employer)'), SECRETARY: t('Secretary (safety head)'), DOCTOR: t('Doctor'), TRAINING: t('Training head'), WORKER: t('Worker member'),
}
const EVENT_KINDS: Record<EventKind, string> = {
  MEETING: t('Committee meeting'), INSPECTION: t('Inspection walk'), REPORT: t('Committee yearly report sent'), MEDICAL_REPORT: t('Medical service report sent'),
}
const ORGANISATIONS: Record<Service['organisation'], string> = {
  OWN: t('Own medical service'), SHARED: t('Service shared with other companies'), OUTSIDE: t('Outside doctor or clinic under contract'),
}

const errorText = (e: unknown) => (e instanceof ApiError ? [e.message, ...Object.values(e.fieldErrors)].join(' ') : t('Something went wrong.'))
const today = () => new Date().toISOString().slice(0, 10)
const resultBadge = (r: Result | null) => r ? <span className={`badge ${r === 'FIT' ? 'ok' : r === 'UNFIT' ? 'off' : 'warn'}`}>{RESULTS[r]}</span> : <span className="muted">—</span>

/** Health and safety at work: medical visits, the work accident register and the health and safety committee. */
export default function HealthSafetyPage() {
  const [tab, setTab] = useState<Tab>('visits')
  useEffect(() => {
  }, [])
  const tabs: Record<Tab, string> = { visits: t('Medical visits'), accidents: t('Work accidents'), committee: t('Safety committee') }
  return (
    <section>
      <PageTitle title={t('Health and safety')} />
      <div className="segmented" role="tablist">
        {(Object.keys(tabs) as Tab[]).map((k) => (
          <button key={k} type="button" role="tab" aria-selected={tab === k} className={tab === k ? 'on' : ''} onClick={() => setTab(k)}>{tabs[k]}</button>
        ))}
      </div>
      {tab === 'visits' && <Visits />}
      {tab === 'accidents' && <Accidents />}
      {tab === 'committee' && <CommitteeTab />}
    </section>
  )
}

function Notices({ warnings }: { warnings: string[] }) {
  return warnings.length > 0 ? <div className="notice small">{warnings.map((w) => <div key={w}>{w}</div>)}</div> : null
}

// ---- medical visits ------------------------------------------------------------------------------------------------

function Visits() {
  const [days, setDays] = useState(60)
  const [data, setData] = useState<VisitPage | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [warnings, setWarnings] = useState<string[]>([])
  const [adding, setAdding] = useState<{ userId?: number; kind?: VisitKind; dueOn?: string } | null>(null)
  const [recording, setRecording] = useState<{ id: number; name: string } | null>(null)
  const ask = useAsk()
  const load = () => api<VisitPage>(`/api/health/visits?days=${days}`).then(setData).catch((e: Error) => setError(e.message))
  useEffect(() => { void load() }, [days]) // eslint-disable-line react-hooks/exhaustive-deps
  const done = (w: string[] = []) => { setAdding(null); setRecording(null); setWarnings(w); void load() }
  const saveService = async (organisation: string) => {
    try { await api('/api/health/service', { method: 'PUT', body: { organisation } }); void load() } catch (e) { setError(errorText(e)) }
  }
  if (!data) return <ErrorText error={error} />
  const late = data.due.filter((d) => d.daysLeft < 0).length
  return (
    <>
      <p className="muted">{t('A medical exam before hiring, then once a year (every six months under 18), and before going back after 30 days or more off. The employer pays. Only the result reaches the company: fit, fit with restrictions, unfit for now, or unfit; never a diagnosis.')}</p>
      <ErrorText error={error} />
      <Notices warnings={warnings} />
      <div className="tiles">
        <div className={`tile${late > 0 ? ' warn' : ''}`}><span>{t('Visits due')}</span><strong>{data.due.length}</strong><small>{t('{a} late', { a: late })}</small></div>
        <div className="tile"><span>{t('Workers under contract')}</span><strong>{data.service.headcount}</strong><small>{ORGANISATIONS[data.service.organisation]}</small></div>
      </div>
      <div className="card">
        <h3>{t('Medical service')}</h3>
        <p className="small">{data.service.required}{!data.service.verified && <span className="badge warn" style={{ marginLeft: 6 }}>{t('to verify')}</span>}</p>
        <label className="auto-width">{t('How medical care is organised')}
          <select value={data.service.organisation} onChange={(e) => void saveService(e.target.value)}>
            {(Object.keys(ORGANISATIONS) as Service['organisation'][]).map((k) => <option key={k} value={k}>{ORGANISATIONS[k]}</option>)}
          </select>
        </label>
      </div>
      <div className="card">
        <div className="row space">
          <h3>{t('Visits due')}</h3>
          <div className="row">
            <select className="auto-width" value={days} onChange={(e) => setDays(Number(e.target.value))} aria-label={t('Period')}>
              {[30, 60, 90, 180].map((d) => <option key={d} value={d}>{t('Next {a} days', { a: d })}</option>)}
            </select>
            <Button className="secondary" onClick={() => void download(`/api/health/visits/due-list?days=${days}&format=pdf`, 'visites-medicales.pdf')}>{t('List for the clinic (PDF)')}</Button>
            <Button onClick={() => setAdding({})}>{t('Add a visit')}</Button>
          </div>
        </div>
        <div className="table-wrap">
          <table>
            <thead><tr><th>{t('Person')}</th><th>{t('Visit')}</th><th>{t('Due')}</th><th>{t('Last result')}</th><th /></tr></thead>
            <tbody>
              {data.due.length === 0 && <tr><td colSpan={5} className="muted">{t('Nothing due in this period.')}</td></tr>}
              {data.due.map((d) => (
                <tr key={`${d.userId}-${d.kind}`}>
                  <td><Link to={`/users/${d.userId}`}>{d.name}</Link> <span className="small muted">{d.registrationNo}</span>{(d.unit || d.site) && <div className="small muted">{[d.unit, d.site].filter(Boolean).join(' · ')}</div>}</td>
                  <td>{VISIT_KINDS[d.kind]}{d.plannedId && <div className="small muted">{t('Planned')}</div>}</td>
                  <td>{longDay(d.dueOn)}<div><span className={`badge ${d.daysLeft < 0 ? 'off' : d.daysLeft <= 15 ? 'warn' : ''}`}>
                    {d.daysLeft < 0 ? t('Late by {a} days', { a: -d.daysLeft }) : d.daysLeft === 0 ? t('Due today') : t('In {a} days', { a: d.daysLeft })}</span></div></td>
                  <td>{resultBadge(d.lastResult)}{d.lastVisitOn && <div className="small muted">{longDay(d.lastVisitOn)}</div>}{d.restrictions && <div className="small">{d.restrictions}</div>}</td>
                  <td className="row">
                    {d.plannedId
                      ? <Button className="secondary small" onClick={() => setRecording({ id: d.plannedId!, name: d.name })}>{t('Record the result')}</Button>
                      : <Button className="secondary small" onClick={() => setAdding({ userId: d.userId, kind: d.kind, dueOn: d.dueOn < today() ? today() : d.dueOn })}>{t('Plan the visit')}</Button>}
                    {d.plannedId && <button type="button" className="link small" onClick={() => ask({ title: t('Cancel this planned visit?'), confirmLabel: t('Delete'), danger: true,
                      onConfirm: async () => { await api(`/api/health/visits/${d.plannedId}`, { method: 'DELETE' }); done() } })}>{t('Delete')}</button>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
      <div className="card">
        <h3>{t('Latest visits')}</h3>
        <div className="table-wrap">
          <table>
            <thead><tr><th>{t('Date')}</th><th>{t('Person')}</th><th>{t('Visit')}</th><th>{t('Result')}</th><th>{t('Valid until')}</th><th>{t('Certificate')}</th></tr></thead>
            <tbody>
              {data.recent.length === 0 && <tr><td colSpan={6} className="muted">{t('No visit recorded yet.')}</td></tr>}
              {data.recent.map((v) => (
                <tr key={v.id}>
                  <td>{longDay(v.visitedOn)}{v.doctor && <div className="small muted">{v.doctor}</div>}</td>
                  <td><Link to={`/users/${v.userId}`}>{v.name}</Link></td>
                  <td>{VISIT_KINDS[v.kind]}</td>
                  <td>{resultBadge(v.result)}{v.restrictions && <div className="small">{v.restrictions}</div>}</td>
                  <td>{longDay(v.validUntil)}</td>
                  <td>{v.hasFile ? <button type="button" className="link" onClick={() => void openFile(`/api/health/visits/${v.id}/file`)}>{v.fileName}</button> : '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
      {adding && <VisitModal start={adding} onClose={() => setAdding(null)} onDone={done} />}
      {recording && <ResultModal visit={recording} onClose={() => setRecording(null)} onDone={done} />}
    </>
  )
}

function ResultFields({ result, setResult, restrictions, setRestrictions }: {
  result: Result; setResult: (r: Result) => void; restrictions: string; setRestrictions: (v: string) => void
}) {
  return (
    <>
      <label>{t('Result given by the doctor')}
        <select value={result} onChange={(e) => setResult(e.target.value as Result)}>
          {(Object.keys(RESULTS) as Result[]).map((r) => <option key={r} value={r}>{RESULTS[r]}</option>)}
        </select>
      </label>
      {result !== 'FIT' && <label>{t('Restrictions or what the person cannot do')}<input maxLength={500} required={result === 'FIT_RESTRICTED'} value={restrictions}
        onChange={(e) => setRestrictions(e.target.value)} placeholder={t('For example: no loads above 10 kg for 3 months')} /></label>}
      <p className="hint">{t('Never write a diagnosis here: the medical secret stays with the doctor.')}</p>
    </>
  )
}

function VisitModal({ start, onClose, onDone }: {
  start: { userId?: number; kind?: VisitKind; dueOn?: string }; onClose: () => void; onDone: (w: string[]) => void
}) {
  const [userId, setUserId] = useState(start.userId ? String(start.userId) : '')
  const [kind, setKind] = useState<VisitKind>(start.kind ?? 'YEARLY')
  const [took, setTook] = useState(false)
  const [dueOn, setDueOn] = useState(start.dueOn ?? today())
  const [visitedOn, setVisitedOn] = useState(today())
  const [doctor, setDoctor] = useState('')
  const [result, setResult] = useState<Result>('FIT')
  const [restrictions, setRestrictions] = useState('')
  const [note, setNote] = useState('')
  const [file, setFile] = useState<File | null>(null)
  const [error, setError] = useState<string | null>(null)
  const submit = async () => {
    setError(null)
    try {
      const content = took && file ? await fileToBase64(file) : null
      const r = await api<Saved<Visit>>('/api/health/visits', { method: 'POST', body: took
        ? { userId: Number(userId), kind, visitedOn, doctor, result, restrictions, note, fileName: file?.name ?? null, content }
        : { userId: Number(userId), kind, dueOn, doctor, note } })
      onDone(r.warnings)
    } catch (e) { setError(errorText(e)) }
  }
  return (
    <Modal title={t('Add a medical visit')} onClose={onClose}
      footer={<><Button className="secondary" type="button" onClick={onClose}>{t('Cancel')}</Button><Button type="submit" form="visit">{t('Save')}</Button></>}>
      <Form id="visit" onSubmit={submit}>
        <label>{t('Person')}
          <PersonSelect required value={userId} onChange={setUserId} />
        </label>
        <div className="grid2">
          <label>{t('Visit')}
            <select value={kind} onChange={(e) => setKind(e.target.value as VisitKind)}>
              {(Object.keys(VISIT_KINDS) as VisitKind[]).map((k) => <option key={k} value={k}>{VISIT_KINDS[k]}</option>)}
            </select>
          </label>
          <label>{t('Status')}
            <select value={took ? 'done' : 'planned'} onChange={(e) => setTook(e.target.value === 'done')}>
              <option value="planned">{t('To plan')}</option><option value="done">{t('Took place')}</option>
            </select>
          </label>
        </div>
        {took
          ? <label>{t('Day of the visit')}<DateInput required max={today()} value={visitedOn} onChange={(e) => setVisitedOn(e.target.value)} /></label>
          : <label>{t('Due on')}<DateInput required value={dueOn} onChange={(e) => setDueOn(e.target.value)} /></label>}
        <label>{t('Doctor or clinic (optional)')}<input maxLength={150} value={doctor} onChange={(e) => setDoctor(e.target.value)} /></label>
        {took && <ResultFields result={result} setResult={setResult} restrictions={restrictions} setRestrictions={setRestrictions} />}
        <label>{t('Practical note (optional)')}<input maxLength={500} value={note} onChange={(e) => setNote(e.target.value)} placeholder={t('Place, time, what to bring')} /></label>
        {took && <label>{t('Certificate (PDF, JPEG or PNG, optional)')}<input type="file" accept="application/pdf,image/jpeg,image/png" onChange={(e) => setFile(e.target.files?.[0] ?? null)} /></label>}
        {!took && <p className="hint">{t('The person is told the visit is due.')}</p>}
        <ErrorText error={error} />
      </Form>
    </Modal>
  )
}

function ResultModal({ visit, onClose, onDone }: { visit: { id: number; name: string }; onClose: () => void; onDone: (w: string[]) => void }) {
  const [visitedOn, setVisitedOn] = useState(today())
  const [doctor, setDoctor] = useState('')
  const [result, setResult] = useState<Result>('FIT')
  const [restrictions, setRestrictions] = useState('')
  const [file, setFile] = useState<File | null>(null)
  const [error, setError] = useState<string | null>(null)
  const submit = async () => {
    setError(null)
    try {
      const content = file ? await fileToBase64(file) : null
      const r = await api<Saved<Visit>>(`/api/health/visits/${visit.id}/result`, { method: 'POST', body: {
        visitedOn, doctor, result, restrictions, fileName: file?.name ?? null, content } })
      onDone(r.warnings)
    } catch (e) { setError(errorText(e)) }
  }
  return (
    <Modal title={t('Result of the visit: {a}', { a: visit.name })} onClose={onClose}
      footer={<><Button className="secondary" type="button" onClick={onClose}>{t('Cancel')}</Button><Button type="submit" form="visit-result">{t('Save')}</Button></>}>
      <Form id="visit-result" onSubmit={submit}>
        <div className="grid2">
          <label>{t('Day of the visit')}<DateInput required max={today()} value={visitedOn} onChange={(e) => setVisitedOn(e.target.value)} /></label>
          <label>{t('Doctor or clinic (optional)')}<input maxLength={150} value={doctor} onChange={(e) => setDoctor(e.target.value)} /></label>
        </div>
        <ResultFields result={result} setResult={setResult} restrictions={restrictions} setRestrictions={setRestrictions} />
        <label>{t('Certificate (PDF, JPEG or PNG, optional)')}<input type="file" accept="application/pdf,image/jpeg,image/png" onChange={(e) => setFile(e.target.files?.[0] ?? null)} /></label>
        <ErrorText error={error} />
      </Form>
    </Modal>
  )
}

// ---- work accidents ------------------------------------------------------------------------------------------------

function Accidents() {
  const [year, setYear] = useState(new Date().getFullYear())
  const [data, setData] = useState<AccidentPage | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [warnings, setWarnings] = useState<string[]>([])
  const [reporting, setReporting] = useState(false)
  const [editing, setEditing] = useState<Accident | null>(null)
  const [declaring, setDeclaring] = useState<Accident | null>(null)
  const ask = useAsk()
  const load = () => api<AccidentPage>(`/api/health/accidents?year=${year}`).then(setData).catch((e: Error) => setError(e.message))
  useEffect(() => { void load() }, [year]) // eslint-disable-line react-hooks/exhaustive-deps
  const done = (w: string[] = []) => { setReporting(false); setEditing(null); setDeclaring(null); setWarnings(w); void load() }
  const letter = async (a: Accident) => {
    try { const r = await api<Saved<Accident>>(`/api/health/accidents/${a.id}/letter`, { method: 'POST' }); done(r.warnings) } catch (e) { setError(errorText(e)) }
  }
  if (!data) return <ErrorText error={error} />
  const f = data.figures
  const shown = [...data.undeclared.filter((u) => !data.accidents.some((a) => a.id === u.id)), ...data.accidents]
  return (
    <>
      <p className="muted">{t('Every accident at work, on the way to or from work, and every occupational disease. Declare it to the CNSS with a copy to the labour inspection and the victim (DRC: 60 days for an accident, 120 for a disease). Days off are paid at two thirds for six months (art. 106); the accident day is paid in full.')}</p>
      <ErrorText error={error} />
      <Notices warnings={warnings} />
      <div className="row space">
        <select className="auto-width" value={year} onChange={(e) => setYear(Number(e.target.value))} aria-label={t('Year')}>
          {[0, 1, 2, 3].map((n) => new Date().getFullYear() - n).map((y) => <option key={y} value={y}>{y}</option>)}
        </select>
        <Button onClick={() => setReporting(true)}>{t('Report an accident')}</Button>
      </div>
      <div className="tiles">
        <div className={`tile${data.undeclared.length > 0 ? ' warn' : ''}`}><span>{t('To declare')}</span><strong>{data.undeclared.length}</strong>
          <small>{data.undeclared[0] ? t('First by {a}', { a: longDay(data.undeclared[0].declareBy) }) : t('Nothing waiting')}</small></div>
        <div className="tile"><span>{t('Accidents in {a}', { a: year })}</span><strong>{f.accidents}</strong><small>{t('{a} with time off', { a: f.withTimeOff })}</small></div>
        <div className="tile"><span>{t('Days lost')}</span><strong>{f.daysLost}</strong><small>{t('Severity rate {a}', { a: f.severityRate ?? '—' })}</small></div>
        <div className="tile"><span>{t('Frequency rate')}</span><strong>{f.frequencyRate ?? '—'}</strong><small>{t('Per million hours (estimate)')}</small></div>
      </div>
      <div className="card">
        <h3>{t('Accident register')}</h3>
        <div className="table-wrap">
          <table>
            <thead><tr><th>{t('When')}</th><th>{t('Person')}</th><th>{t('What happened')}</th><th>{t('Days off')}</th><th>{t('Declaration')}</th><th /></tr></thead>
            <tbody>
              {shown.length === 0 && <tr><td colSpan={6} className="muted">{t('No accident recorded.')}</td></tr>}
              {shown.map((a) => (
                <tr key={a.id}>
                  <td>{longDay(a.occurredAt)}<div className="small muted">{ACCIDENT_KINDS[a.kind]}</div></td>
                  <td><Link to={`/users/${a.userId}`}>{a.name}</Link>{a.unit && <div className="small muted">{a.unit}</div>}</td>
                  <td>{a.description}{a.place && <div className="small muted">{a.place}</div>}{a.injury && <div className="small">{a.injury}</div>}
                    {a.status === 'REPORTED' && <div><span className="badge warn">{t('Reported by {a}', { a: a.reportedBy })}</span></div>}</td>
                  <td>{a.offFrom ? <>{a.offTo ? t('{a} days off', { a: a.daysOff }) : t('From {a}', { a: longDay(a.offFrom) })}{a.leaveRequestId && <div className="small muted">{t('Accident leave recorded')}</div>}</> : '—'}</td>
                  <td>{a.declaredOn
                    ? <><span className="badge ok">{t('Declared {a}', { a: longDay(a.declaredOn) })}</span>{a.declarationRef && <div className="small muted">{a.declarationRef}</div>}</>
                    : <span className={`badge ${a.daysLeft < 0 ? 'off' : a.daysLeft <= 7 ? 'warn' : ''}`}>
                      {a.daysLeft < 0 ? t('Late since {a}', { a: longDay(a.declareBy) }) : t('By {a}', { a: longDay(a.declareBy) })}</span>}</td>
                  <td>
                    <div className="row">
                      <Button className="secondary small" onClick={() => setEditing(a)}>{a.status === 'REPORTED' ? t('Complete') : t('Edit')}</Button>
                      <Button className="secondary small" onClick={() => setDeclaring(a)}>{a.declaredOn ? t('Edit the declaration') : t('Record the declaration')}</Button>
                    </div>
                    <div className="row">
                      <button type="button" className="link small" onClick={() => void letter(a)}>{a.letterId ? t('Declaration letter') : t('Draft the declaration')}</button>
                      <button type="button" className="link small" onClick={() => ask({ title: t('Delete this accident, entered by mistake?'), message: t('The accident leave it recorded is cancelled.'),
                        confirmLabel: t('Delete'), danger: true, onConfirm: async () => { await api(`/api/health/accidents/${a.id}`, { method: 'DELETE' }); done() } })}>{t('Delete')}</button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {data.accidents.some((a) => a.letterId) && <p className="small muted">{t('Drafted letters are on the person\'s page, under Documents and letters, to read and send.')}</p>}
      </div>
      {f.byUnit.length > 0 && (
        <div className="card">
          <h3>{t('By unit')}</h3>
          <table><thead><tr><th>{t('Unit')}</th><th>{t('Accidents')}</th><th>{t('Days lost')}</th></tr></thead>
            <tbody>{f.byUnit.map((u) => <tr key={u.unit}><td>{u.unit}</td><td>{u.accidents}</td><td>{u.daysLost}</td></tr>)}</tbody></table>
        </div>
      )}
      {reporting && <ReportAccidentModal onClose={() => setReporting(false)} onDone={() => done()} />}
      {editing && <CompleteModal accident={editing} onClose={() => setEditing(null)} onDone={done} />}
      {declaring && <DeclareModal accident={declaring} onClose={() => setDeclaring(null)} onDone={() => done()} />}
    </>
  )
}

/** A local date and time for the input, from an ISO instant. */
const localInput = (iso: string) => {
  const d = new Date(iso)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function CompleteModal({ accident, onClose, onDone }: { accident: Accident; onClose: () => void; onDone: (w: string[]) => void }) {
  const [kind, setKind] = useState<AccidentKind>(accident.kind)
  const [when, setWhen] = useState(localInput(accident.occurredAt))
  const [place, setPlace] = useState(accident.place ?? '')
  const [description, setDescription] = useState(accident.description)
  const [witnesses, setWitnesses] = useState(accident.witnesses ?? '')
  const [injury, setInjury] = useState(accident.injury ?? '')
  const [firstAid, setFirstAid] = useState(accident.firstAid ?? '')
  const [offFrom, setOffFrom] = useState(accident.offFrom ?? '')
  const [offTo, setOffTo] = useState(accident.offTo ?? '')
  const [note, setNote] = useState(accident.note ?? '')
  const [error, setError] = useState<string | null>(null)
  const submit = async () => {
    setError(null)
    try {
      const r = await api<Saved<Accident>>(`/api/health/accidents/${accident.id}`, { method: 'PUT', body: {
        kind, occurredAt: new Date(when).toISOString(), place, description, witnesses, injury, firstAid,
        offFrom: offFrom || null, offTo: offTo || null, note } })
      onDone(r.warnings)
    } catch (e) { setError(errorText(e)) }
  }
  return (
    <Modal title={t('Accident of {a}', { a: accident.name })} onClose={onClose}
      footer={<><Button className="secondary" type="button" onClick={onClose}>{t('Cancel')}</Button><Button type="submit" form="accident-complete">{t('Save')}</Button></>}>
      <Form id="accident-complete" onSubmit={submit}>
        <div className="grid2">
          <label>{t('Kind')}
            <select value={kind} onChange={(e) => setKind(e.target.value as AccidentKind)}>
              {(Object.keys(ACCIDENT_KINDS) as AccidentKind[]).map((k) => <option key={k} value={k}>{ACCIDENT_KINDS[k]}</option>)}
            </select>
          </label>
          <label>{t('When')}<input type="datetime-local" required value={when} onChange={(e) => setWhen(e.target.value)} /></label>
        </div>
        <label>{t('Place')}<input maxLength={200} value={place} onChange={(e) => setPlace(e.target.value)} /></label>
        <label>{t('What happened')}<textarea required rows={3} maxLength={4000} value={description} onChange={(e) => setDescription(e.target.value)} /></label>
        <div className="grid2">
          <label>{t('Injury')}<input maxLength={500} value={injury} onChange={(e) => setInjury(e.target.value)} /></label>
          <label>{t('First aid')}<input maxLength={500} value={firstAid} onChange={(e) => setFirstAid(e.target.value)} /></label>
        </div>
        <label>{t('Witnesses')}<input maxLength={500} value={witnesses} onChange={(e) => setWitnesses(e.target.value)} /></label>
        <div className="grid2">
          <label>{t('First day off')}<DateInput value={offFrom} onChange={(e) => setOffFrom(e.target.value)} /></label>
          <label>{t('Last day off')}<DateInput value={offTo} min={offFrom || undefined} onChange={(e) => setOffTo(e.target.value)} /></label>
        </div>
        <p className="hint">{t('The days off are recorded as work accident leave, paid at its share for six months at most. From 30 days off, a return-to-work visit is planned.')}</p>
        <label>{t('Note (optional)')}<input maxLength={1000} value={note} onChange={(e) => setNote(e.target.value)} /></label>
        <ErrorText error={error} />
      </Form>
    </Modal>
  )
}

function DeclareModal({ accident, onClose, onDone }: { accident: Accident; onClose: () => void; onDone: () => void }) {
  const [declaredOn, setDeclaredOn] = useState(accident.declaredOn ?? today())
  const [reference, setReference] = useState(accident.declarationRef ?? '')
  const [inspection, setInspection] = useState(accident.inspectionCopyOn ?? '')
  const [victim, setVictim] = useState(accident.victimCopyOn ?? '')
  const [error, setError] = useState<string | null>(null)
  const submit = async () => {
    setError(null)
    try {
      await api(`/api/health/accidents/${accident.id}/declaration`, { method: 'PUT', body: {
        declaredOn: declaredOn || null, reference, inspectionCopyOn: inspection || null, victimCopyOn: victim || null } })
      onDone()
    } catch (e) { setError(errorText(e)) }
  }
  return (
    <Modal title={t('Declaration of the accident')} onClose={onClose}
      footer={<><Button className="secondary" type="button" onClick={onClose}>{t('Cancel')}</Button><Button type="submit" form="accident-declare">{t('Save')}</Button></>}>
      <Form id="accident-declare" onSubmit={submit}>
        <div className="grid2">
          <label>{t('Sent to the CNSS on')}<DateInput max={today()} value={declaredOn} onChange={(e) => setDeclaredOn(e.target.value)} /></label>
          <label>{t('Reference')}<input maxLength={100} value={reference} onChange={(e) => setReference(e.target.value)} /></label>
        </div>
        <div className="grid2">
          <label>{t('Copy to the labour inspection on')}<DateInput max={today()} value={inspection} onChange={(e) => setInspection(e.target.value)} /></label>
          <label>{t('Copy to the victim on')}<DateInput max={today()} value={victim} onChange={(e) => setVictim(e.target.value)} /></label>
        </div>
        <ErrorText error={error} />
      </Form>
    </Modal>
  )
}

// ---- the committee -------------------------------------------------------------------------------------------------

function CommitteeTab() {
  const [data, setData] = useState<Committee | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [modal, setModal] = useState<'member' | 'event' | 'action' | null>(null)
  const ask = useAsk()
  const load = () => api<Committee>('/api/health/committee').then(setData).catch((e: Error) => setError(e.message))
  useEffect(() => { void load() }, [])
  const done = () => { setModal(null); void load() }
  const remove = (path: string, title: string) => ask({ title, confirmLabel: t('Delete'), danger: true,
    onConfirm: async () => { await api(path, { method: 'DELETE' }); done() } })
  const markDone = async (a: SafetyAction) => {
    try { await api(`/api/health/committee/actions/${a.id}/done`, { method: 'PUT', body: { doneOn: a.doneOn ? null : today() } }); void load() } catch (e) { setError(errorText(e)) }
  }
  if (!data) return <ErrorText error={error} />
  const year = new Date().getFullYear()
  return (
    <>
      <p className="muted">{data.from > 0
        ? t('From {a} workers, a health and safety committee (CSHE): the employer presides, with the safety head, the doctor, the training head and worker members named by the delegates, for two years. It meets at least every two months, inspects the workplace every month, and sends a yearly report by 31 March (arrêté 043/2008).', { a: data.from })
        : t('No rule on a health and safety committee is known for this country yet: the committee can still be kept here.')}</p>
      <ErrorText error={error} />
      <div className="tiles">
        <div className={`tile${data.required && data.workerSeats !== null && data.workers < data.workerSeats ? ' warn' : ''}`}><span>{t('Worker members')}</span>
          <strong>{data.workers}{data.workerSeats ? ` / ${data.workerSeats}` : ''}</strong>
          <small>{data.required ? t('Required: {a} workers', { a: data.headcount }) : t('Not required: {a} workers', { a: data.headcount })}</small></div>
        <div className="tile"><span>{t('Next meeting due')}</span><strong>{longDay(data.nextMeetingDue)}</strong><small>{t('Last one: {a}', { a: longDay(data.lastMeeting) })}</small></div>
        <div className="tile"><span>{t('Next inspection walk')}</span><strong>{longDay(data.nextInspectionDue)}</strong><small>{t('Last one: {a}', { a: longDay(data.lastInspection) })}</small></div>
        <div className="tile"><span>{t('Yearly report')}</span><strong>{year - 1}</strong>
          <small><button type="button" className="link" onClick={() => void download(`/api/health/committee/report/${year - 1}?format=pdf`, `rapport-cshe-${year - 1}.pdf`)}>{t('Print (PDF)')}</button>
            {' · '}<button type="button" className="link" onClick={() => void download(`/api/health/committee/report/${year}?format=pdf`, `rapport-cshe-${year}.pdf`)}>{t('{a} so far', { a: year })}</button></small></div>
      </div>
      {data.alerts.length > 0 && <div className="notice">{data.alerts.map((a) => <div key={a}>{a}</div>)}</div>}

      <div className="card">
        <div className="row space"><h3>{t('Members')}</h3><Button className="secondary" onClick={() => setModal('member')}>{t('Add a member')}</Button></div>
        <div className="table-wrap">
          <table>
            <thead><tr><th>{t('Name')}</th><th>{t('Role')}</th><th>{t('From')}</th><th>{t('Until')}</th><th /></tr></thead>
            <tbody>
              {data.members.length === 0 && <tr><td colSpan={5} className="muted">{t('No member recorded.')}</td></tr>}
              {data.members.map((m) => (
                <tr key={m.id} className={m.active ? '' : 'muted'}>
                  <td>{m.userId ? <Link to={`/users/${m.userId}`}>{m.name}</Link> : m.name}</td>
                  <td>{ROLES[m.role]}</td><td>{longDay(m.fromOn)}</td><td>{longDay(m.endedOn ?? m.termEnd)}</td>
                  <td className="row">
                    {m.active && <button type="button" className="link small" onClick={() => ask({ title: t('End this mandate today?'), confirmLabel: t('End the mandate'),
                      onConfirm: async () => { await api(`/api/health/committee/members/${m.id}/end`, { method: 'POST', body: { endedOn: today() } }); done() } })}>{t('End the mandate')}</button>}
                    <button type="button" className="link small" onClick={() => remove(`/api/health/committee/members/${m.id}`, t('Delete this entry, made by mistake?'))}>{t('Delete')}</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      <div className="card">
        <div className="row space"><h3>{t('Meetings, inspection walks and reports')}</h3><Button className="secondary" onClick={() => setModal('event')}>{t('Add a record')}</Button></div>
        <div className="table-wrap">
          <table>
            <thead><tr><th>{t('Date')}</th><th>{t('What')}</th><th>{t('File')}</th><th /></tr></thead>
            <tbody>
              {data.events.length === 0 && <tr><td colSpan={4} className="muted">{t('Nothing recorded yet.')}</td></tr>}
              {data.events.map((e) => (
                <tr key={e.id}>
                  <td>{longDay(e.heldOn)}</td>
                  <td><span className="badge">{EVENT_KINDS[e.kind]}</span> {e.title}{e.note && <div className="small muted">{e.note}</div>}</td>
                  <td>{e.hasFile ? <button type="button" className="link" onClick={() => void openFile(`/api/health/committee/events/${e.id}/file`)}>{e.fileName}</button> : '—'}</td>
                  <td><button type="button" className="link small" onClick={() => remove(`/api/health/committee/events/${e.id}`, t('Delete this record?'))}>{t('Delete')}</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      <div className="card">
        <div className="row space"><h3>{t('Actions')}</h3><Button className="secondary" onClick={() => setModal('action')}>{t('Add an action')}</Button></div>
        <div className="table-wrap">
          <table>
            <thead><tr><th>{t('Risk found')}</th><th>{t('Action')}</th><th>{t('Who')}</th><th>{t('Due')}</th><th>{t('Status')}</th><th /></tr></thead>
            <tbody>
              {data.actions.length === 0 && <tr><td colSpan={6} className="muted">{t('No action yet.')}</td></tr>}
              {data.actions.map((a) => (
                <tr key={a.id}>
                  <td>{a.risk}</td><td>{a.action}</td><td>{a.ownerName ?? '—'}</td><td>{longDay(a.dueOn)}</td>
                  <td>{a.doneOn ? <span className="badge ok">{t('Done {a}', { a: longDay(a.doneOn) })}</span> : <span className={`badge ${a.late ? 'off' : ''}`}>{a.late ? t('Late') : t('Not done yet')}</span>}</td>
                  <td className="row">
                    <button type="button" className="link small" onClick={() => void markDone(a)}>{a.doneOn ? t('Reopen') : t('Mark done')}</button>
                    <button type="button" className="link small" onClick={() => remove(`/api/health/committee/actions/${a.id}`, t('Delete this action?'))}>{t('Delete')}</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
      {modal === 'member' && <MemberModal onClose={() => setModal(null)} onDone={done} />}
      {modal === 'event' && <SafetyEventModal onClose={() => setModal(null)} onDone={done} />}
      {modal === 'action' && <ActionModal onClose={() => setModal(null)} onDone={done} />}
    </>
  )
}

function MemberModal({ onClose, onDone }: { onClose: () => void; onDone: () => void }) {
  const [userId, setUserId] = useState('')
  const [name, setName] = useState('')
  const [role, setRole] = useState<Role>('WORKER')
  const [fromOn, setFromOn] = useState(today())
  const [error, setError] = useState<string | null>(null)
  const submit = async () => {
    setError(null)
    try {
      await api('/api/health/committee/members', { method: 'POST', body: { userId: userId ? Number(userId) : null, name: userId ? null : name, role, fromOn } })
      onDone()
    } catch (e) { setError(errorText(e)) }
  }
  return (
    <Modal title={t('Add a member')} onClose={onClose}
      footer={<><Button className="secondary" type="button" onClick={onClose}>{t('Cancel')}</Button><Button type="submit" form="member">{t('Add')}</Button></>}>
      <Form id="member" onSubmit={submit}>
        <label>{t('Role')}
          <select value={role} onChange={(e) => setRole(e.target.value as Role)}>
            {(Object.keys(ROLES) as Role[]).map((r) => <option key={r} value={r}>{ROLES[r]}</option>)}
          </select>
        </label>
        <label>{t('Employee')}
          <PersonSelect value={userId} onChange={setUserId} />
          <span className="hint">{t('Leave empty for someone from outside the company.')}</span>
        </label>
        {!userId && <label>{t('Name')}<input required maxLength={150} value={name} onChange={(e) => setName(e.target.value)} placeholder={t('For example: the company doctor')} /></label>}
        <label>{t('From')}<DateInput required value={fromOn} onChange={(e) => setFromOn(e.target.value)} /></label>
        <p className="hint">{t('The mandate lasts two years. Worker members are named by the staff delegates.')}</p>
        <ErrorText error={error} />
      </Form>
    </Modal>
  )
}

function SafetyEventModal({ onClose, onDone }: { onClose: () => void; onDone: () => void }) {
  const [kind, setKind] = useState<EventKind>('MEETING')
  const [heldOn, setHeldOn] = useState(today())
  const [year, setYear] = useState(String(new Date().getFullYear() - 1))
  const [note, setNote] = useState('')
  const [file, setFile] = useState<File | null>(null)
  const [error, setError] = useState<string | null>(null)
  const report = kind === 'REPORT' || kind === 'MEDICAL_REPORT'
  const submit = async () => {
    setError(null)
    try {
      const content = file ? await fileToBase64(file) : null
      await api('/api/health/committee/events', { method: 'POST', body: { kind, heldOn, year: report ? Number(year) : null, note, fileName: file?.name ?? null, content } })
      onDone()
    } catch (e) { setError(errorText(e)) }
  }
  return (
    <Modal title={t('Add a record')} onClose={onClose}
      footer={<><Button className="secondary" type="button" onClick={onClose}>{t('Cancel')}</Button><Button type="submit" form="safety-event">{t('Save')}</Button></>}>
      <Form id="safety-event" onSubmit={submit}>
        <div className="grid2">
          <label>{t('What')}
            <select value={kind} onChange={(e) => setKind(e.target.value as EventKind)}>
              {(Object.keys(EVENT_KINDS) as EventKind[]).map((k) => <option key={k} value={k}>{EVENT_KINDS[k]}</option>)}
            </select>
          </label>
          <label>{report ? t('Day sent') : t('Date')}<DateInput required max={today()} value={heldOn} onChange={(e) => setHeldOn(e.target.value)} /></label>
        </div>
        {report && <label>{t('Year covered')}<input type="number" min="2000" max={new Date().getFullYear()} value={year} onChange={(e) => setYear(e.target.value)} /></label>}
        <label>{kind === 'INSPECTION' ? t('What was found') : t('Subjects (optional)')}<textarea rows={3} value={note} onChange={(e) => setNote(e.target.value)} /></label>
        <label>{t('Minutes or report (PDF, JPEG or PNG, optional)')}<input type="file" accept="application/pdf,image/jpeg,image/png" onChange={(e) => setFile(e.target.files?.[0] ?? null)} /></label>
        <ErrorText error={error} />
      </Form>
    </Modal>
  )
}

function ActionModal({ onClose, onDone }: { onClose: () => void; onDone: () => void }) {
  const [risk, setRisk] = useState('')
  const [action, setAction] = useState('')
  const [ownerId, setOwnerId] = useState('')
  const [dueOn, setDueOn] = useState('')
  const [error, setError] = useState<string | null>(null)
  const submit = async () => {
    setError(null)
    try {
      await api('/api/health/committee/actions', { method: 'POST', body: { risk, action, ownerId: ownerId ? Number(ownerId) : null, dueOn: dueOn || null } })
      onDone()
    } catch (e) { setError(errorText(e)) }
  }
  return (
    <Modal title={t('Add an action')} onClose={onClose}
      footer={<><Button className="secondary" type="button" onClick={onClose}>{t('Cancel')}</Button><Button type="submit" form="safety-action">{t('Add')}</Button></>}>
      <Form id="safety-action" onSubmit={submit}>
        <label>{t('Risk found')}<input required maxLength={500} value={risk} onChange={(e) => setRisk(e.target.value)} /></label>
        <label>{t('Action')}<input required maxLength={500} value={action} onChange={(e) => setAction(e.target.value)} /></label>
        <div className="grid2">
          <label>{t('Who')}
            <PersonSelect value={ownerId} onChange={setOwnerId} />
          </label>
          <label>{t('Due')}<DateInput value={dueOn} onChange={(e) => setDueOn(e.target.value)} /></label>
        </div>
        <ErrorText error={error} />
      </Form>
    </Modal>
  )
}
