import { useEffect, useState, type FormEvent } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { ApiError, api, day, fileToBase64, money, openFile, type GradeCategory } from '../api'
import { useAuth } from '../auth'
import { Button, Form } from '../components/Button'
import { useAsk } from '../components/Dialogs'
import { DateInput } from '../components/DateFields'
import { ErrorText } from '../components/ErrorText'
import { Modal } from '../components/Modal'
import { PageTitle, SettingsLink } from '../components/SettingsLink'
import type { MyPerson } from '../components/UnitFilter'
import { t } from '../i18n'
import type { Skill } from './SkillsPages'

// ---- types ---------------------------------------------------------------------------------------------------------

type Provider = 'INTERNAL' | 'INPP' | 'EXTERNAL'
type Mode = 'CLASSROOM' | 'ON_THE_JOB' | 'ONLINE'
type Priority = 'HIGH' | 'MEDIUM' | 'LOW'
type NeedStatus = 'REQUESTED' | 'OPEN' | 'PLANNED' | 'DONE' | 'REFUSED' | 'DROPPED'
type NeedSource = 'EMPLOYEE' | 'MANAGER' | 'OBJECTIVE' | 'EVALUATION' | 'SKILL_GAP' | 'HR'
export type Course = {
  id: number; title: string; providerKind: Provider; providerName: string | null; mode: Mode; hours: number; costPerPerson: number
  currency: string; description: string | null; active: boolean; sessions: number; trained: number
}
type Need = {
  id: number; year: number; userId: number | null; userName: string | null; unitId: number | null; unitName: string | null; topic: string
  courseId: number | null; courseTitle: string | null; source: NeedSource; priority: Priority; status: NeedStatus; reason: string | null
  managerOpinion: 'SUPPORT' | 'OPPOSE' | null; managerComment: string | null; decidedBy: string | null; decisionComment: string | null
  planLineId: number | null; planLineTopic: string | null; requestedByName: string | null; createdAt: string
}
type Plan = {
  id: number; year: number; status: 'DRAFT' | 'CONSULTED' | 'APPROVED' | 'CLOSED'; budget: number | null; currency: string
  consultedOn: string | null; representativesOpinion: string | null; approvedBy: string | null; approvedAt: string | null; note: string | null
}
type Line = {
  id: number; courseId: number | null; courseTitle: string | null; topic: string; categoryId: number | null; categoryName: string | null
  unitId: number | null; unitName: string | null; quarter: number | null; people: number; budget: number | null; nationalManagers: boolean
  note: string | null; needs: number; sessions: number; trained: number
}
type Coverage = { categoryId: number; code: string; name: string; staff: number; lines: number; trained: number }
type Levy = { usd: number; local: number; localCurrency: string; months: number }
type PlanView = {
  year: number; plan: Plan | null; lines: Line[]; coverage: Coverage[]; levy: Levy; plannedBudget: number; spent: number; committed: number
  currency: string; openNeeds: number; lead: string; warnings: string[]
}
type Session = {
  id: number; courseId: number; courseTitle: string; providerKind: Provider; mode: Mode; hours: number; planLineId: number | null
  planLineTopic: string | null; startsOn: string; endsOn: string; place: string | null; trainer: string | null
  status: 'PLANNED' | 'DONE' | 'CANCELLED'; actualCost: number | null; expectedCost: number; currency: string; note: string | null
  enrolled: number; attended: number; rating: number | null
}
type Attendee = {
  id: number; sessionId: number; userId: number; name: string; registrationNo: string; unitName: string | null; needId: number | null
  attended: boolean | null; rating: number | null; ratingComment: string | null; effectDueOn: string | null; effect: Effect | null
  effectComment: string | null; effectByName: string | null; documentId: number | null
}
type SessionView = { session: Session; attendees: Attendee[]; openNeeds: Need[] }
type Effect = 'YES' | 'PARTLY' | 'NO'
type History = {
  attendeeId: number; sessionId: number; courseTitle: string; providerKind: Provider; hours: number; startsOn: string; endsOn: string
  status: Session['status']; attended: boolean | null; rating: number | null; ratingComment: string | null; effect: Effect | null
  effectComment: string | null; documentId: number | null
}
type Mine = { history: History[]; requests: Need[]; hoursThisYear: number; courses: Course[] }
type EffectDue = { attendeeId: number; userId: number; name: string; courseTitle: string; endsOn: string; effectDueOn: string }
type ReportRow = { key: string; label: string; people: number; sessions: number; hours: number; cost: number }
type Report = {
  year: number; trained: number; staff: number; hours: number; cost: number; currency: string; levy: Levy
  byCategory: ReportRow[]; byUnit: ReportRow[]; byPerson: ReportRow[]; apprentices: number; womenTrained: number
}
type Apprentice = {
  contractId: number; userId: number; name: string; registrationNo: string; startDate: string; endDate: string | null; gradeCode: string
  occupationName: string | null; trade: string | null; masterUserId: number | null; masterName: string | null; onemVisaOn: string | null
  onemVisaNo: string | null; medicalOn: string | null; waiverOn: string | null; birthDate: string | null; ageAtStart: number | null
  allowanceReviewOn: string | null; endCertificateOn: string | null; endReportedOn: string | null; stayMonths: number | null
  stayUntil: string | null; note: string | null; warnings: string[]
}
type Settings = { lead: string; effectMonths: number; apprenticeContributions: 'NONE' | 'SALARY'; workerEducationDays: number; workerEducationNoticeDays: number }
type Unit = { id: number; name: string; open: boolean }

export const PROVIDERS: Record<Provider, string> = { INTERNAL: t('In-house'), INPP: t('INPP'), EXTERNAL: t('Outside firm') }
const MODES: Record<Mode, string> = { CLASSROOM: t('Classroom'), ON_THE_JOB: t('On the job'), ONLINE: t('Online') }
const PRIORITIES: Record<Priority, string> = { HIGH: t('High'), MEDIUM: t('Medium'), LOW: t('Low') }
const NEED_STATUS: Record<NeedStatus, string> = {
  REQUESTED: t('Asked, to decide'), OPEN: t('To plan'), PLANNED: t('Planned'), DONE: t('Done'), REFUSED: t('Refused'), DROPPED: t('Dropped'),
}
const SOURCES: Record<NeedSource, string> = {
  EMPLOYEE: t('Asked by the employee'), MANAGER: t('Asked by the manager'), OBJECTIVE: t('Objective'), EVALUATION: t('Evaluation'),
  SKILL_GAP: t('Skill gap'), HR: t('HR'),
}
const PLAN_STATUS: Record<Plan['status'], string> = { DRAFT: t('Draft'), CONSULTED: t('Representatives consulted'), APPROVED: t('Approved'), CLOSED: t('Closed') }
const SESSION_STATUS: Record<Session['status'], string> = { PLANNED: t('Planned'), DONE: t('Done'), CANCELLED: t('Cancelled') }
const EFFECTS: Record<Effect, string> = { YES: t('Yes, clearly'), PARTLY: t('Partly'), NO: t('Not yet') }

const errorText = (e: unknown) => (e instanceof ApiError ? [e.message, ...Object.values(e.fieldErrors)].join(' ') : t('Something went wrong.'))
const thisYear = new Date().getFullYear()
const statusBadge = (s: NeedStatus) => <span className={`badge ${s === 'DONE' ? 'ok' : s === 'REFUSED' || s === 'DROPPED' ? 'off' : s === 'REQUESTED' ? 'warn' : ''}`}>{NEED_STATUS[s]}</span>
const stars = (n: number | null) => n == null ? <span className="muted">—</span> : <span className="stars" aria-label={t('{a} out of 5', { a: n })}>{'★'.repeat(Math.round(n))}{'☆'.repeat(5 - Math.round(n))}</span>

function Notices({ warnings }: { warnings: string[] }) {
  return warnings.length > 0 ? <div className="notice small">{warnings.map((w) => <div key={w}>{w}</div>)}</div> : null
}

function YearPicker({ year, onChange }: { year: number; onChange: (y: number) => void }) {
  return (
    <select className="auto-width" value={year} onChange={(e) => onChange(Number(e.target.value))} aria-label={t('Year')}>
      {[thisYear + 1, thisYear, thisYear - 1, thisYear - 2].map((y) => <option key={y} value={y}>{y}</option>)}
    </select>
  )
}

// ---- the HR and manager page ---------------------------------------------------------------------------------------

type Tab = 'plan' | 'needs' | 'sessions' | 'courses' | 'effects' | 'apprentices' | 'report'

/** Training: the annual plan, needs and requests, sessions, the catalogue, the effect check, apprentices and figures. */
export default function TrainingPage() {
  const { can } = useAuth()
  const hr = can('training.manage')
  const [params, setParams] = useSearchParams()
  const tabs: Partial<Record<Tab, string>> = hr
    ? { plan: t('Annual plan'), needs: t('Needs and requests'), sessions: t('Sessions'), courses: t('Courses'), effects: t('Effect check'), apprentices: t('Apprentices'), report: t('Figures') }
    : { needs: t('Requests of my team'), effects: t('Effect check') }
  const wanted = params.get('tab') as Tab | null
  const tab: Tab = wanted && tabs[wanted] ? wanted : hr ? 'plan' : 'needs'
  const [year, setYear] = useState(thisYear)
  const [courses, setCourses] = useState<Course[]>([])
  const loadCourses = () => api<Course[]>(`/api/training/courses?all=${hr}`).then(setCourses).catch(() => undefined)
  useEffect(() => { void loadCourses() }, []) // eslint-disable-line react-hooks/exhaustive-deps
  return (
    <section>
      <PageTitle title={t('Training')}>
        {['plan', 'needs', 'sessions', 'report'].includes(tab) && <YearPicker year={year} onChange={setYear} />}
        {hr && <SettingsLink to="/training/settings" />}
      </PageTitle>
      <div className="segmented" role="tablist">
        {(Object.keys(tabs) as Tab[]).map((k) => (
          <button key={k} type="button" role="tab" aria-selected={tab === k} className={tab === k ? 'on' : ''}
            onClick={() => setParams({ tab: k }, { replace: true })}>{tabs[k]}</button>
        ))}
      </div>
      {tab === 'plan' && <PlanTab year={year} courses={courses} />}
      {tab === 'needs' && <NeedsTab year={year} courses={courses} hr={hr} />}
      {tab === 'sessions' && <SessionsTab year={year} courses={courses} />}
      {tab === 'courses' && <CoursesTab courses={courses} reload={loadCourses} />}
      {tab === 'effects' && <EffectsTab />}
      {tab === 'apprentices' && <ApprenticesTab />}
      {tab === 'report' && <ReportTab year={year} />}
    </section>
  )
}

// ---- the plan ------------------------------------------------------------------------------------------------------

function PlanTab({ year, courses }: { year: number; courses: Course[] }) {
  const [view, setView] = useState<PlanView | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [editing, setEditing] = useState(false)
  const [consulting, setConsulting] = useState(false)
  const [line, setLine] = useState<Line | 'new' | null>(null)
  const [categories, setCategories] = useState<GradeCategory[]>([])
  const [units, setUnits] = useState<Unit[]>([])
  const ask = useAsk()
  const load = () => api<PlanView>(`/api/training/plans/${year}`).then(setView).catch((e: Error) => setError(e.message))
  useEffect(() => { setView(null); void load() }, [year]) // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => {
    api<GradeCategory[]>('/api/leave/categories').then((c) => setCategories(c.filter((x) => x.active))).catch(() => undefined)
    api<Unit[]>('/api/org/units').then((u) => setUnits(u.filter((x) => x.open))).catch(() => undefined)
  }, [])
  const act = (path: string, title: string, confirmLabel: string) => ask({
    title, confirmLabel, onConfirm: async () => { setError(null); setView(await api<PlanView>(path, { method: 'POST' })) },
  })
  const removeLine = (l: Line) => ask({
    title: t('Remove this line from the plan?'), message: l.topic, confirmLabel: t('Remove'), danger: true,
    onConfirm: async () => setView(await api<PlanView>(`/api/training/lines/${l.id}`, { method: 'DELETE' })),
  })
  if (!view) return <ErrorText error={error} />
  const p = view.plan
  const cur = view.currency
  return (
    <>
      <p className="muted">{t('Every employer trains its workers (Code du travail art. 8). The plan leaves no category of staff out, trains national managers first, and is drawn up with the workers\' representatives (Ordonnance 71-055 art. 17-19).')}</p>
      <ErrorText error={error} />
      <Notices warnings={view.warnings} />
      {!p && (
        <div className="card">
          <h3>{t('No plan for {a} yet', { a: year })}</h3>
          <p>{t('{a} needs wait to be planned.', { a: view.openNeeds })}</p>
          <Button onClick={() => setEditing(true)}>{t('Start the {a} plan', { a: year })}</Button>
        </div>
      )}
      {p && (
        <>
          <div className="tiles">
            <div className="tile"><span>{t('Status')}</span><strong>{PLAN_STATUS[p.status]}</strong><small>{p.approvedAt ? t('by {a}', { a: p.approvedBy ?? '' }) : p.consultedOn ? t('Consulted on {a}', { a: day(p.consultedOn) }) : t('Not consulted yet')}</small></div>
            <div className="tile"><span>{t('Budget')}</span><strong>{p.budget != null ? money(p.budget, cur) : '—'}</strong><small>{t('Lines: {a}', { a: money(view.plannedBudget, cur) })}</small></div>
            <div className={`tile${p.budget != null && view.spent + view.committed > p.budget ? ' warn' : ''}`}><span>{t('Spent')}</span><strong>{money(view.spent, cur)}</strong><small>{t('{a} more planned', { a: money(view.committed, cur) })}</small></div>
            <div className="tile"><span>{t('INPP levy paid')}</span><strong>{money(view.levy.usd, 'USD')}</strong><small>{t('{a} closed months · {b}', { a: view.levy.months, b: money(view.levy.local, view.levy.localCurrency) })}</small></div>
            <div className="tile"><span>{t('Needs to plan')}</span><strong>{view.openNeeds}</strong><small><Link to="/training?tab=needs">{t('See the needs')}</Link></small></div>
          </div>
          <div className="row">
            {p.status !== 'CLOSED' && <Button className="secondary" onClick={() => setEditing(true)}>{t('Budget and note')}</Button>}
            {p.status !== 'CLOSED' && <Button className="secondary" onClick={() => setConsulting(true)}>{t('Record the representatives\' opinion')}</Button>}
            {(p.status === 'DRAFT' || p.status === 'CONSULTED') && <Button onClick={() => act(`/api/training/plans/${year}/approve`,
              p.consultedOn ? t('Approve the {a} plan?', { a: year }) : t('The workers\' representatives have not been consulted. Approve the plan anyway?'), t('Approve the plan'))}>{t('Approve the plan')}</Button>}
            {p.status === 'APPROVED' && <Button className="secondary" onClick={() => act(`/api/training/plans/${year}/close`, t('Close the {a} plan? It can no longer be changed.', { a: year }), t('Close the plan'))}>{t('Close the plan')}</Button>}
          </div>
          {p.representativesOpinion && <div className="card"><h3>{t('Representatives\' opinion')}</h3><p className="small">{day(p.consultedOn)}: {p.representativesOpinion}</p></div>}
          <div className="card">
            <div className="row space">
              <h3>{t('Lines of the plan')}</h3>
              {p.status !== 'CLOSED' && <Button onClick={() => setLine('new')}>{t('Add a line')}</Button>}
            </div>
            <div className="table-wrap">
              <table>
                <thead><tr><th>{t('Training')}</th><th>{t('For')}</th><th>{t('Quarter')}</th><th>{t('People')}</th><th>{t('Budget')}</th><th>{t('Progress')}</th><th /></tr></thead>
                <tbody>
                  {view.lines.length === 0 && <tr><td colSpan={7} className="muted">{t('No line yet.')}</td></tr>}
                  {view.lines.map((l) => (
                    <tr key={l.id}>
                      <td><strong>{l.topic}</strong>{l.courseTitle && l.courseTitle !== l.topic && <div className="small muted">{l.courseTitle}</div>}
                        {l.nationalManagers && <div><span className="badge">{t('National managers')}</span></div>}</td>
                      <td>{l.categoryName ?? t('All categories')}{l.unitName && <div className="small muted">{l.unitName}</div>}</td>
                      <td>{l.quarter ? `T${l.quarter}` : '—'}</td>
                      <td>{l.people}</td>
                      <td>{l.budget != null ? money(l.budget, cur) : '—'}</td>
                      <td className="small">{t('Needs {a} · sessions {b} · trained {c}', { a: l.needs, b: l.sessions, c: l.trained })}</td>
                      <td className="row">{p.status !== 'CLOSED' && <>
                        <Button className="secondary small" onClick={() => setLine(l)}>{t('Edit')}</Button>
                        <Button className="secondary small void" onClick={() => removeLine(l)}>{t('Remove')}</Button></>}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
          <div className="card">
            <h3>{t('Every category covered')}</h3>
            <div className="table-wrap">
              <table>
                <thead><tr><th>{t('Category')}</th><th>{t('Staff')}</th><th>{t('Lines')}</th><th>{t('Trained this year')}</th></tr></thead>
                <tbody>
                  {view.coverage.map((c) => (
                    <tr key={c.categoryId}>
                      <td>{c.name}</td><td>{c.staff}</td>
                      <td>{c.lines > 0 ? c.lines : <span className={c.staff > 0 ? 'badge warn' : 'muted'}>{c.staff > 0 ? t('None') : '—'}</span>}</td>
                      <td>{c.trained}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        </>
      )}
      {editing && <PlanModal year={year} plan={p} onClose={() => setEditing(false)} onSaved={(v) => { setEditing(false); setView(v) }} />}
      {consulting && p && <ConsultModal year={year} plan={p} onClose={() => setConsulting(false)} onSaved={(v) => { setConsulting(false); setView(v) }} />}
      {line && p && <LineModal year={year} line={line === 'new' ? null : line} courses={courses} categories={categories} units={units}
        onClose={() => setLine(null)} onSaved={(v) => { setLine(null); setView(v) }} />}
    </>
  )
}

function PlanModal({ year, plan, onClose, onSaved }: { year: number; plan: Plan | null; onClose: () => void; onSaved: (v: PlanView) => void }) {
  const { me } = useAuth()
  const local = me?.company?.localCurrency ?? 'CDF'
  const [form, setForm] = useState({ budget: plan?.budget?.toString() ?? '', currency: plan?.currency ?? 'USD', note: plan?.note ?? '' })
  const [error, setError] = useState<string | null>(null)
  const save = async (e: FormEvent) => {
    e.preventDefault()
    try {
      onSaved(await api<PlanView>(`/api/training/plans/${year}`, { method: 'PUT', body: { budget: form.budget ? Number(form.budget) : null, currency: form.currency, note: form.note || null } }))
    } catch (err) { setError(errorText(err)) }
  }
  return (
    <Modal title={t('Training plan {a}', { a: year })} onClose={onClose}>
      <Form onSubmit={save}>
        <div className="grid2">
          <label>{t('Budget')}<input type="number" min="0" step="0.01" value={form.budget} onChange={(e) => setForm({ ...form, budget: e.target.value })} /></label>
          <label>{t('Currency')}<select value={form.currency} onChange={(e) => setForm({ ...form, currency: e.target.value })}>
            <option value="USD">USD</option><option value={local}>{local}</option></select></label>
        </div>
        <label>{t('Note')}<textarea rows={3} maxLength={2000} value={form.note} onChange={(e) => setForm({ ...form, note: e.target.value })} /></label>
        <ErrorText error={error} />
        <div className="modal-foot"><Button className="secondary" onClick={onClose}>{t('Cancel')}</Button><Button type="submit">{t('Save')}</Button></div>
      </Form>
    </Modal>
  )
}

function ConsultModal({ year, plan, onClose, onSaved }: { year: number; plan: Plan; onClose: () => void; onSaved: (v: PlanView) => void }) {
  const [form, setForm] = useState({ consultedOn: plan.consultedOn ?? new Date().toISOString().slice(0, 10), opinion: plan.representativesOpinion ?? '' })
  const [error, setError] = useState<string | null>(null)
  const save = async (e: FormEvent) => {
    e.preventDefault()
    try { onSaved(await api<PlanView>(`/api/training/plans/${year}/consult`, { method: 'POST', body: form })) } catch (err) { setError(errorText(err)) }
  }
  return (
    <Modal title={t('The workers\' representatives\' opinion')} onClose={onClose}>
      <Form onSubmit={save}>
        <p className="hint">{t('The plan is drawn up with the workers\' representatives (O. 71-055 art. 19). Their opinion is recorded; it does not block the plan.')}</p>
        <label>{t('Consulted on')}<DateInput value={form.consultedOn} onChange={(e) => setForm({ ...form, consultedOn: e.target.value })} required /></label>
        <label>{t('Their opinion')}<textarea rows={4} maxLength={2000} value={form.opinion} onChange={(e) => setForm({ ...form, opinion: e.target.value })} /></label>
        <ErrorText error={error} />
        <div className="modal-foot"><Button className="secondary" onClick={onClose}>{t('Cancel')}</Button><Button type="submit">{t('Save')}</Button></div>
      </Form>
    </Modal>
  )
}

function LineModal({ year, line, courses, categories, units, onClose, onSaved }: {
  year: number; line: Line | null; courses: Course[]; categories: GradeCategory[]; units: Unit[]; onClose: () => void; onSaved: (v: PlanView) => void
}) {
  const [form, setForm] = useState({
    courseId: line?.courseId?.toString() ?? '', topic: line?.topic ?? '', categoryId: line?.categoryId?.toString() ?? '', unitId: line?.unitId?.toString() ?? '',
    quarter: line?.quarter?.toString() ?? '', people: line?.people?.toString() ?? '1', budget: line?.budget?.toString() ?? '',
    nationalManagers: line?.nationalManagers ?? false, note: line?.note ?? '',
  })
  const [error, setError] = useState<string | null>(null)
  const course = courses.find((c) => c.id === Number(form.courseId))
  const save = async (e: FormEvent) => {
    e.preventDefault()
    const body = {
      courseId: form.courseId ? Number(form.courseId) : null, topic: form.topic || null, categoryId: form.categoryId ? Number(form.categoryId) : null,
      unitId: form.unitId ? Number(form.unitId) : null, quarter: form.quarter ? Number(form.quarter) : null, people: Number(form.people),
      budget: form.budget ? Number(form.budget) : null, nationalManagers: form.nationalManagers, note: form.note || null,
    }
    try {
      onSaved(await api<PlanView>(line ? `/api/training/plans/${year}/lines/${line.id}` : `/api/training/plans/${year}/lines`, { method: line ? 'PUT' : 'POST', body }))
    } catch (err) { setError(errorText(err)) }
  }
  return (
    <Modal title={line ? t('Line of the plan') : t('Add a line')} size="lg" onClose={onClose}>
      <Form onSubmit={save}>
        <div className="grid2">
          <label>{t('Course')}<select value={form.courseId} onChange={(e) => setForm({ ...form, courseId: e.target.value })}>
            <option value="">{t('None yet')}</option>
            {courses.filter((c) => c.active || c.id === line?.courseId).map((c) => <option key={c.id} value={c.id}>{c.title}</option>)}
          </select></label>
          <label>{t('Topic')}<input maxLength={300} value={form.topic} placeholder={course?.title ?? ''} onChange={(e) => setForm({ ...form, topic: e.target.value })} required={!form.courseId} /></label>
        </div>
        <div className="grid2">
          <label>{t('Category of staff')}<select value={form.categoryId} onChange={(e) => setForm({ ...form, categoryId: e.target.value })}>
            <option value="">{t('All categories')}</option>
            {categories.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select></label>
          <label>{t('Unit')}<select value={form.unitId} onChange={(e) => setForm({ ...form, unitId: e.target.value })}>
            <option value="">{t('Whole company')}</option>
            {units.map((u) => <option key={u.id} value={u.id}>{u.name}</option>)}
          </select></label>
        </div>
        <div className="grid3">
          <label>{t('Quarter')}<select value={form.quarter} onChange={(e) => setForm({ ...form, quarter: e.target.value })}>
            <option value="">{t('Any time')}</option>{[1, 2, 3, 4].map((q) => <option key={q} value={q}>T{q}</option>)}</select></label>
          <label>{t('People')}<input type="number" min="1" max="10000" value={form.people} onChange={(e) => setForm({ ...form, people: e.target.value })} required /></label>
          <label>{t('Budget')}<input type="number" min="0" step="0.01" value={form.budget}
            placeholder={course ? String(course.costPerPerson * Number(form.people || 0)) : ''} onChange={(e) => setForm({ ...form, budget: e.target.value })} /></label>
        </div>
        <label className="check"><input type="checkbox" checked={form.nationalManagers} onChange={(e) => setForm({ ...form, nationalManagers: e.target.checked })} />{t('Trains national managers (O. 71-055 art. 18)')}</label>
        <label>{t('Note')}<input maxLength={500} value={form.note} onChange={(e) => setForm({ ...form, note: e.target.value })} /></label>
        <ErrorText error={error} />
        <div className="modal-foot"><Button className="secondary" onClick={onClose}>{t('Cancel')}</Button><Button type="submit">{t('Save')}</Button></div>
      </Form>
    </Modal>
  )
}

// ---- needs and requests --------------------------------------------------------------------------------------------

function NeedsTab({ year, courses, hr }: { year: number; courses: Course[]; hr: boolean }) {
  const [needs, setNeeds] = useState<Need[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const [filter, setFilter] = useState<'todo' | 'all'>('todo')
  const [asking, setAsking] = useState(false)
  const [deciding, setDeciding] = useState<Need | null>(null)
  const [opining, setOpining] = useState<Need | null>(null)
  const [planning, setPlanning] = useState<Need | null>(null)
  const [people, setPeople] = useState<MyPerson[]>([])
  const ask = useAsk()
  const load = () => api<Need[]>(`/api/training/needs?year=${year}`).then(setNeeds).catch((e: Error) => setError(e.message))
  useEffect(() => { setNeeds(null); void load() }, [year]) // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { api<MyPerson[]>('/api/org/my-people').then((p) => setPeople([...p].sort((a, b) => a.name.localeCompare(b.name)))).catch(() => undefined) }, [])
  const collect = async () => {
    setError(null)
    try {
      const r = await api<{ added: number }>(`/api/training/needs/collect?year=${year}`, { method: 'POST' })
      setMessage(t('{a} needs added from objectives and evaluations.', { a: r.added }))
      void load()
    } catch (e) { setError(errorText(e)) }
  }
  const change = async (n: Need, status: 'OPEN' | 'DROPPED') => {
    try { await api(`/api/training/needs/${n.id}/change`, { method: 'POST', body: { status } }); void load() } catch (e) { setError(errorText(e)) }
  }
  const drop = (n: Need) => ask({ title: t('Drop this need?'), message: n.topic, confirmLabel: t('Drop'), danger: true, onConfirm: () => change(n, 'DROPPED') })
  if (!needs) return <ErrorText error={error} />
  const shown = filter === 'todo' ? needs.filter((n) => ['REQUESTED', 'OPEN', 'PLANNED'].includes(n.status)) : needs
  return (
    <>
      <p className="muted">{hr
        ? t('Needs come from requests of employees and managers, from the development needs written on objectives, and from criteria scored below half in evaluations. Accepted needs are tied to a line of the plan, then to a session.')
        : t('Ask HR for training for someone of your team, and give your opinion on what your team asks for. HR decides.')}</p>
      <ErrorText error={error} />
      {message && <p className="success">{message}</p>}
      <div className="row space">
        <select className="auto-width" value={filter} onChange={(e) => setFilter(e.target.value as 'todo' | 'all')} aria-label={t('Show')}>
          <option value="todo">{t('To decide or plan')}</option><option value="all">{t('All of {a}', { a: year })}</option>
        </select>
        <div className="row">
          {hr && <Button className="secondary" onClick={() => void collect()}>{t('Collect from objectives and evaluations')}</Button>}
          <Button onClick={() => setAsking(true)}>{hr ? t('Add a need') : t('Ask for training')}</Button>
        </div>
      </div>
      <div className="card">
        <div className="table-wrap">
          <table>
            <thead><tr><th>{t('Person or unit')}</th><th>{t('Need')}</th><th>{t('From')}</th><th>{t('Priority')}</th><th>{t('Status')}</th><th /></tr></thead>
            <tbody>
              {shown.length === 0 && <tr><td colSpan={6} className="muted">{t('Nothing here.')}</td></tr>}
              {shown.map((n) => (
                <tr key={n.id}>
                  <td>{n.userId ? <Link to={`/users/${n.userId}`}>{n.userName}</Link> : <strong>{n.unitName}</strong>}{n.userId && n.unitName && <div className="small muted">{n.unitName}</div>}</td>
                  <td><strong>{n.topic}</strong>{n.courseTitle && n.courseTitle !== n.topic && <div className="small muted">{n.courseTitle}</div>}
                    {n.reason && <div className="small">{n.reason}</div>}
                    {n.managerOpinion && <div className="small"><span className={`badge ${n.managerOpinion === 'SUPPORT' ? 'ok' : 'off'}`}>{n.managerOpinion === 'SUPPORT' ? t('Manager supports') : t('Manager against')}</span> {n.managerComment}</div>}
                    {n.decisionComment && <div className="small muted">{t('HR: {a}', { a: n.decisionComment })}</div>}
                    {n.planLineTopic && <div className="small muted">{t('Plan: {a}', { a: n.planLineTopic })}</div>}</td>
                  <td className="small">{SOURCES[n.source]}{n.requestedByName && n.source !== 'OBJECTIVE' && n.source !== 'EVALUATION' && <div className="muted">{n.requestedByName}</div>}</td>
                  <td>{PRIORITIES[n.priority]}</td>
                  <td>{statusBadge(n.status)}</td>
                  <td className="row">
                    {n.status === 'REQUESTED' && !hr && n.source === 'EMPLOYEE' && <Button className="secondary small" onClick={() => setOpining(n)}>{t('Give my opinion')}</Button>}
                    {n.status === 'REQUESTED' && hr && <Button className="small" onClick={() => setDeciding(n)}>{t('Decide')}</Button>}
                    {hr && (n.status === 'OPEN' || n.status === 'PLANNED') && <Button className="secondary small" onClick={() => setPlanning(n)}>{t('Plan line')}</Button>}
                    {hr && (n.status === 'OPEN' || n.status === 'PLANNED') && <Button className="secondary small void" onClick={() => drop(n)}>{t('Drop')}</Button>}
                    {hr && n.status === 'DROPPED' && <Button className="secondary small" onClick={() => void change(n, 'OPEN')}>{t('Open again')}</Button>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
      {asking && <AskModal year={year} courses={courses} people={people} hr={hr} onClose={() => setAsking(false)} onSaved={() => { setAsking(false); void load() }} />}
      {deciding && <DecideModal need={deciding} onClose={() => setDeciding(null)} onSaved={() => { setDeciding(null); void load() }} />}
      {opining && <OpinionModal need={opining} onClose={() => setOpining(null)} onSaved={() => { setOpining(null); void load() }} />}
      {planning && <PlanLineModal need={planning} onClose={() => setPlanning(null)} onSaved={() => { setPlanning(null); void load() }} />}
    </>
  )
}

function AskModal({ year, courses, people, hr, self, onClose, onSaved }: {
  year: number; courses: Course[]; people: MyPerson[]; hr: boolean; self?: boolean; onClose: () => void; onSaved: () => void
}) {
  const [units, setUnits] = useState<Unit[]>([])
  const [form, setForm] = useState({ who: self ? 'me' : 'person', userId: '', unitId: '', topic: '', courseId: '', reason: '', priority: 'MEDIUM' })
  const [error, setError] = useState<string | null>(null)
  useEffect(() => { if (hr) api<Unit[]>('/api/org/units').then((u) => setUnits(u.filter((x) => x.open))).catch(() => undefined) }, [hr])
  const course = courses.find((c) => c.id === Number(form.courseId))
  const save = async (e: FormEvent) => {
    e.preventDefault()
    const body = {
      userId: form.who === 'person' && form.userId ? Number(form.userId) : null, unitId: form.who === 'unit' && form.unitId ? Number(form.unitId) : null,
      topic: form.topic || course?.title || '', courseId: form.courseId ? Number(form.courseId) : null, reason: form.reason || null,
      priority: hr ? form.priority : null, year: self ? null : year,
    }
    try { await api('/api/training/needs', { method: 'POST', body }); onSaved() } catch (err) { setError(errorText(err)) }
  }
  return (
    <Modal title={self ? t('Ask for training') : hr ? t('Add a need') : t('Ask for training for my team')} onClose={onClose}>
      <Form onSubmit={save}>
        {!self && (
          <div className="grid2">
            {hr && <label>{t('For')}<select value={form.who} onChange={(e) => setForm({ ...form, who: e.target.value })}>
              <option value="person">{t('A person')}</option><option value="unit">{t('A whole unit')}</option></select></label>}
            {form.who === 'person' && <label>{t('Person')}<select value={form.userId} onChange={(e) => setForm({ ...form, userId: e.target.value })} required>
              <option value="">{t('Choose')}</option>{people.map((p) => <option key={p.userId} value={p.userId}>{p.name} · {p.registrationNo}</option>)}</select></label>}
            {form.who === 'unit' && <label>{t('Unit')}<select value={form.unitId} onChange={(e) => setForm({ ...form, unitId: e.target.value })} required>
              <option value="">{t('Choose')}</option>{units.map((u) => <option key={u.id} value={u.id}>{u.name}</option>)}</select></label>}
          </div>
        )}
        <label>{t('Course from the catalogue (optional)')}<select value={form.courseId} onChange={(e) => setForm({ ...form, courseId: e.target.value })}>
          <option value="">{t('None: describe the need')}</option>{courses.filter((c) => c.active).map((c) => <option key={c.id} value={c.id}>{c.title} · {PROVIDERS[c.providerKind]}</option>)}</select></label>
        <label>{t('What to learn')}<input maxLength={300} value={form.topic} placeholder={course?.title ?? ''} onChange={(e) => setForm({ ...form, topic: e.target.value })} required={!form.courseId} /></label>
        <label>{t('Why')}<textarea rows={3} maxLength={1000} value={form.reason} onChange={(e) => setForm({ ...form, reason: e.target.value })} /></label>
        {hr && <label className="auto-width">{t('Priority')}<select value={form.priority} onChange={(e) => setForm({ ...form, priority: e.target.value })}>
          {(Object.keys(PRIORITIES) as Priority[]).map((k) => <option key={k} value={k}>{PRIORITIES[k]}</option>)}</select></label>}
        {!hr && <p className="hint">{self ? t('Your manager gives an opinion, then HR decides. You are told the answer.') : t('HR decides and you are told the answer.')}</p>}
        <ErrorText error={error} />
        <div className="modal-foot"><Button className="secondary" onClick={onClose}>{t('Cancel')}</Button><Button type="submit">{t('Send')}</Button></div>
      </Form>
    </Modal>
  )
}

function DecideModal({ need, onClose, onSaved }: { need: Need; onClose: () => void; onSaved: () => void }) {
  const [form, setForm] = useState({ comment: '', priority: need.priority })
  const [error, setError] = useState<string | null>(null)
  const decide = async (accept: boolean) => {
    try { await api(`/api/training/needs/${need.id}/decision`, { method: 'POST', body: { accept, comment: form.comment || null, priority: form.priority } }); onSaved() } catch (err) { setError(errorText(err)) }
  }
  return (
    <Modal title={t('Training request: {a}', { a: need.userName ?? need.unitName ?? '' })} onClose={onClose}>
      <p><strong>{need.topic}</strong></p>
      {need.reason && <p className="small">{need.reason}</p>}
      <p className="small">{need.managerOpinion ? <>{need.managerOpinion === 'SUPPORT' ? t('The manager supports it.') : t('The manager is against it.')} {need.managerComment}</> : <span className="muted">{t('The manager has not given an opinion.')}</span>}</p>
      <label className="auto-width">{t('Priority')}<select value={form.priority} onChange={(e) => setForm({ ...form, priority: e.target.value as Priority })}>
        {(Object.keys(PRIORITIES) as Priority[]).map((k) => <option key={k} value={k}>{PRIORITIES[k]}</option>)}</select></label>
      <label>{t('Comment (required to refuse)')}<textarea rows={3} maxLength={1000} value={form.comment} onChange={(e) => setForm({ ...form, comment: e.target.value })} /></label>
      <ErrorText error={error} />
      <div className="modal-foot">
        <Button className="secondary void" onClick={() => decide(false)}>{t('Refuse')}</Button>
        <Button onClick={() => decide(true)}>{t('Accept')}</Button>
      </div>
    </Modal>
  )
}

function OpinionModal({ need, onClose, onSaved }: { need: Need; onClose: () => void; onSaved: () => void }) {
  const [comment, setComment] = useState('')
  const [error, setError] = useState<string | null>(null)
  const send = async (opinion: 'SUPPORT' | 'OPPOSE') => {
    try { await api(`/api/training/needs/${need.id}/opinion`, { method: 'POST', body: { opinion, comment: comment || null } }); onSaved() } catch (err) { setError(errorText(err)) }
  }
  return (
    <Modal title={t('Training request: {a}', { a: need.userName ?? '' })} onClose={onClose}>
      <p><strong>{need.topic}</strong></p>
      {need.reason && <p className="small">{need.reason}</p>}
      <label>{t('Comment')}<textarea rows={3} maxLength={1000} value={comment} onChange={(e) => setComment(e.target.value)} /></label>
      <ErrorText error={error} />
      <div className="modal-foot">
        <Button className="secondary void" onClick={() => send('OPPOSE')}>{t('Against')}</Button>
        <Button onClick={() => send('SUPPORT')}>{t('Support')}</Button>
      </div>
    </Modal>
  )
}

function PlanLineModal({ need, onClose, onSaved }: { need: Need; onClose: () => void; onSaved: () => void }) {
  const [view, setView] = useState<PlanView | null>(null)
  const [lineId, setLineId] = useState(need.planLineId?.toString() ?? '')
  const [error, setError] = useState<string | null>(null)
  useEffect(() => { api<PlanView>(`/api/training/plans/${need.year}`).then(setView).catch((e: Error) => setError(e.message)) }, [need.year])
  const save = async () => {
    try { await api(`/api/training/needs/${need.id}/change`, { method: 'POST', body: { planLineId: lineId ? Number(lineId) : null } }); onSaved() } catch (err) { setError(errorText(err)) }
  }
  return (
    <Modal title={t('Tie the need to the plan')} onClose={onClose}>
      <p><strong>{need.topic}</strong> · {need.userName ?? need.unitName}</p>
      {view && !view.plan && <p className="notice small">{t('There is no training plan for {a} yet.', { a: need.year })}</p>}
      {view?.plan && <label>{t('Line of the plan')}<select value={lineId} onChange={(e) => setLineId(e.target.value)}>
        <option value="">{t('None')}</option>{view.lines.map((l) => <option key={l.id} value={l.id}>{l.topic}{l.quarter ? ` · T${l.quarter}` : ''}</option>)}</select></label>}
      <ErrorText error={error} />
      <div className="modal-foot"><Button className="secondary" onClick={onClose}>{t('Cancel')}</Button><Button onClick={save} disabled={!view?.plan}>{t('Save')}</Button></div>
    </Modal>
  )
}

// ---- sessions ------------------------------------------------------------------------------------------------------

function SessionsTab({ year, courses }: { year: number; courses: Course[] }) {
  const [sessions, setSessions] = useState<Session[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [open, setOpen] = useState<number | null>(null)
  const [creating, setCreating] = useState(false)
  const load = () => api<Session[]>(`/api/training/sessions?year=${year}`).then(setSessions).catch((e: Error) => setError(e.message))
  useEffect(() => { setSessions(null); void load() }, [year]) // eslint-disable-line react-hooks/exhaustive-deps
  if (!sessions) return <ErrorText error={error} />
  return (
    <>
      <div className="row space">
        <p className="muted">{t('Enrol people from their needs, record who came and the real cost; certificates go to the employee file. The manager is asked about the effect a few months later.')}</p>
        <Button onClick={() => setCreating(true)} disabled={courses.length === 0}>{t('Plan a session')}</Button>
      </div>
      {courses.length === 0 && <p className="notice small">{t('Add a course to the catalogue first.')}</p>}
      <div className="card">
        <div className="table-wrap">
          <table>
            <thead><tr><th>{t('Course')}</th><th>{t('Dates')}</th><th>{t('People')}</th><th>{t('Cost')}</th><th>{t('Rating')}</th><th>{t('Status')}</th></tr></thead>
            <tbody>
              {sessions.length === 0 && <tr><td colSpan={6} className="muted">{t('No session in {a}.', { a: year })}</td></tr>}
              {sessions.map((s) => (
                <tr key={s.id} className="clickable" onClick={() => setOpen(s.id)}>
                  <td><Button className="link" onClick={() => setOpen(s.id)}>{s.courseTitle}</Button><div className="small muted">{PROVIDERS[s.providerKind]} · {s.hours} h{s.place ? ` · ${s.place}` : ''}</div></td>
                  <td>{day(s.startsOn)}{s.endsOn !== s.startsOn && <> – {day(s.endsOn)}</>}</td>
                  <td>{s.status === 'DONE' ? t('{a} of {b} came', { a: s.attended, b: s.enrolled }) : t('{a} enrolled', { a: s.enrolled })}</td>
                  <td>{money(s.actualCost ?? s.expectedCost, s.currency)}{s.actualCost == null && <div className="small muted">{t('expected')}</div>}</td>
                  <td>{stars(s.rating)}</td>
                  <td><span className={`badge ${s.status === 'DONE' ? 'ok' : s.status === 'CANCELLED' ? 'off' : ''}`}>{SESSION_STATUS[s.status]}</span></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
      {creating && <SessionModal year={year} courses={courses} session={null} onClose={() => setCreating(false)} onSaved={(v) => { setCreating(false); void load(); setOpen(v.session.id) }} />}
      {open !== null && <SessionDetail id={open} courses={courses} year={year} onClose={() => { setOpen(null); void load() }} />}
    </>
  )
}

function SessionModal({ year, courses, session, onClose, onSaved }: { year: number; courses: Course[]; session: Session | null; onClose: () => void; onSaved: (v: SessionView) => void }) {
  const [plan, setPlan] = useState<PlanView | null>(null)
  const [form, setForm] = useState({
    courseId: session?.courseId.toString() ?? '', planLineId: session?.planLineId?.toString() ?? '', startsOn: session?.startsOn ?? '', endsOn: session?.endsOn ?? '',
    place: session?.place ?? '', trainer: session?.trainer ?? '', actualCost: session?.actualCost?.toString() ?? '', note: session?.note ?? '',
  })
  const [error, setError] = useState<string | null>(null)
  useEffect(() => { api<PlanView>(`/api/training/plans/${year}`).then(setPlan).catch(() => undefined) }, [year])
  const save = async (e: FormEvent) => {
    e.preventDefault()
    const body = {
      courseId: Number(form.courseId), planLineId: form.planLineId ? Number(form.planLineId) : null, startsOn: form.startsOn, endsOn: form.endsOn || form.startsOn,
      place: form.place || null, trainer: form.trainer || null, actualCost: form.actualCost ? Number(form.actualCost) : null, note: form.note || null,
    }
    try { onSaved(await api<SessionView>(session ? `/api/training/sessions/${session.id}` : '/api/training/sessions', { method: session ? 'PUT' : 'POST', body })) } catch (err) { setError(errorText(err)) }
  }
  return (
    <Modal title={session ? t('Session') : t('Plan a session')} size="lg" onClose={onClose}>
      <Form onSubmit={save}>
        <div className="grid2">
          <label>{t('Course')}<select value={form.courseId} onChange={(e) => setForm({ ...form, courseId: e.target.value })} required>
            <option value="">{t('Choose')}</option>{courses.filter((c) => c.active || c.id === session?.courseId).map((c) => <option key={c.id} value={c.id}>{c.title}</option>)}</select></label>
          <label>{t('Line of the plan')}<select value={form.planLineId} onChange={(e) => setForm({ ...form, planLineId: e.target.value })}>
            <option value="">{t('None')}</option>{plan?.lines.map((l) => <option key={l.id} value={l.id}>{l.topic}</option>)}</select></label>
        </div>
        <div className="grid2">
          <label>{t('First day')}<DateInput value={form.startsOn} onChange={(e) => setForm({ ...form, startsOn: e.target.value })} required /></label>
          <label>{t('Last day')}<DateInput value={form.endsOn} onChange={(e) => setForm({ ...form, endsOn: e.target.value })} /></label>
        </div>
        <div className="grid3">
          <label>{t('Place')}<input maxLength={200} value={form.place} onChange={(e) => setForm({ ...form, place: e.target.value })} /></label>
          <label>{t('Trainer')}<input maxLength={200} value={form.trainer} onChange={(e) => setForm({ ...form, trainer: e.target.value })} /></label>
          <label>{t('Real cost (if known)')}<input type="number" min="0" step="0.01" value={form.actualCost} onChange={(e) => setForm({ ...form, actualCost: e.target.value })} /></label>
        </div>
        <label>{t('Note')}<input maxLength={1000} value={form.note} onChange={(e) => setForm({ ...form, note: e.target.value })} /></label>
        <ErrorText error={error} />
        <div className="modal-foot"><Button className="secondary" onClick={onClose}>{t('Cancel')}</Button><Button type="submit">{t('Save')}</Button></div>
      </Form>
    </Modal>
  )
}

function SessionDetail({ id, courses, year, onClose }: { id: number; courses: Course[]; year: number; onClose: () => void }) {
  const [view, setView] = useState<SessionView | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [people, setPeople] = useState<MyPerson[]>([])
  const [adding, setAdding] = useState('')
  const [present, setPresent] = useState<Set<number>>(new Set())
  const [cost, setCost] = useState('')
  const [editing, setEditing] = useState(false)
  const ask = useAsk()
  const load = () => api<SessionView>(`/api/training/sessions/${id}`).then((v) => { setView(v); setPresent(new Set(v.attendees.map((a) => a.userId))) }).catch((e: Error) => setError(e.message))
  useEffect(() => { void load() }, [id]) // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { api<MyPerson[]>('/api/org/my-people').then((p) => setPeople([...p].sort((a, b) => a.name.localeCompare(b.name)))).catch(() => undefined) }, [])
  const call = async (path: string, method: string, body?: unknown) => {
    setError(null)
    try { const v = await api<SessionView>(path, { method, body }); setView(v); setPresent(new Set(v.attendees.map((a) => a.userId))) } catch (e) { setError(errorText(e)) }
  }
  const upload = async (a: Attendee, file: File) => {
    await call(`/api/training/attendees/${a.id}/certificate`, 'POST', { fileName: file.name, data: await fileToBase64(file) })
  }
  if (!view) return <Modal title={t('Session')} onClose={onClose}><ErrorText error={error} /></Modal>
  const s = view.session
  const planned = s.status === 'PLANNED'
  const over = s.endsOn <= new Date().toISOString().slice(0, 10)
  return (
    <Modal title={`${s.courseTitle} · ${day(s.startsOn)}`} size="lg" onClose={onClose}>
      <p className="small muted">{PROVIDERS[s.providerKind]} · {MODES[s.mode]} · {s.hours} h{s.place ? ` · ${s.place}` : ''}{s.trainer ? ` · ${s.trainer}` : ''}{s.planLineTopic ? ` · ${t('Plan: {a}', { a: s.planLineTopic })}` : ''}</p>
      <ErrorText error={error} />
      {planned && (
        <div className="row">
          <select className="auto-width" value={adding} onChange={(e) => setAdding(e.target.value)} aria-label={t('Person')}>
            <option value="">{t('Add a person')}</option>
            {people.filter((p) => !view.attendees.some((a) => a.userId === p.userId)).map((p) => <option key={p.userId} value={p.userId}>{p.name} · {p.registrationNo}</option>)}
          </select>
          <Button className="secondary" disabled={!adding} onClick={() => { void call(`/api/training/sessions/${id}/attendees`, 'POST', { userIds: [Number(adding)] }); setAdding('') }}>{t('Enrol')}</Button>
          <Button className="secondary" onClick={() => setEditing(true)}>{t('Edit the session')}</Button>
        </div>
      )}
      {planned && view.openNeeds.length > 0 && (
        <div className="notice small">
          <strong>{t('Needs this session answers')}</strong>
          {view.openNeeds.map((n) => (
            <div key={n.id} className="row space">
              <span>{n.userName} · {n.topic} <span className="muted">({PRIORITIES[n.priority]})</span></span>
              <Button className="secondary small" onClick={() => void call(`/api/training/sessions/${id}/attendees`, 'POST', { needIds: [n.id] })}>{t('Enrol')}</Button>
            </div>
          ))}
          <Button className="small" onClick={() => void call(`/api/training/sessions/${id}/attendees`, 'POST', { needIds: view.openNeeds.map((n) => n.id) })}>{t('Enrol them all')}</Button>
        </div>
      )}
      <div className="table-wrap">
        <table>
          <thead><tr><th>{planned && over ? t('Came') : ''}</th><th>{t('Person')}</th><th>{t('Rating')}</th><th>{t('Effect')}</th><th>{t('Certificate')}</th><th /></tr></thead>
          <tbody>
            {view.attendees.length === 0 && <tr><td colSpan={6} className="muted">{t('Nobody enrolled yet.')}</td></tr>}
            {view.attendees.map((a) => (
              <tr key={a.id}>
                <td>{planned ? (over && <input type="checkbox" aria-label={t('Came')} checked={present.has(a.userId)}
                  onChange={(e) => { const next = new Set(present); if (e.target.checked) next.add(a.userId); else next.delete(a.userId); setPresent(next) }} />)
                  : a.attended ? <span className="badge ok">{t('Came')}</span> : <span className="badge off">{t('Absent')}</span>}</td>
                <td><Link to={`/users/${a.userId}`}>{a.name}</Link> <span className="small muted">{a.registrationNo}</span>{a.unitName && <div className="small muted">{a.unitName}</div>}</td>
                <td>{stars(a.rating)}{a.ratingComment && <div className="small">{a.ratingComment}</div>}</td>
                <td className="small">{a.effect ? <>{EFFECTS[a.effect]}{a.effectComment && <div>{a.effectComment}</div>}</> : a.effectDueOn ? <span className="muted">{t('Asked from {a}', { a: day(a.effectDueOn) })}</span> : '—'}</td>
                <td>{a.documentId ? <Button className="link" onClick={() => void openFile(`/api/lifecycle/documents/${a.documentId}/file`)}>{t('Open')}</Button>
                  : s.status === 'DONE' && a.attended ? <label className="button secondary small">{t('Add')}<input type="file" hidden accept="application/pdf,image/jpeg,image/png"
                    onChange={(e) => { const f = e.target.files?.[0]; if (f) void upload(a, f) }} /></label> : '—'}</td>
                <td>{planned && <Button className="secondary small void" onClick={() => void call(`/api/training/sessions/${id}/attendees/${a.userId}`, 'DELETE')}>{t('Remove')}</Button>}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {planned && over && (
        <div className="row">
          <label className="auto-width">{t('Real cost')}<input type="number" min="0" step="0.01" value={cost} placeholder={String(s.actualCost ?? s.expectedCost)} onChange={(e) => setCost(e.target.value)} /></label>
          <Button onClick={() => void call(`/api/training/sessions/${id}/done`, 'POST', { present: [...present], actualCost: cost ? Number(cost) : null })}>{t('Close the session')}</Button>
        </div>
      )}
      {planned && !over && <p className="hint">{t('After the last day, tick who came and close the session.')}</p>}
      <div className="modal-foot">
        {planned && <Button className="secondary void" onClick={() => ask({ title: t('Cancel this session?'), message: t('The people enrolled are told.'), confirmLabel: t('Cancel the session'), danger: true, onConfirm: () => call(`/api/training/sessions/${id}/cancel`, 'POST') })}>{t('Cancel the session')}</Button>}
        <Button className="secondary" onClick={onClose}>{t('Close')}</Button>
      </div>
      {editing && <SessionModal year={year} courses={courses} session={s} onClose={() => setEditing(false)} onSaved={(v) => { setEditing(false); setView(v) }} />}
    </Modal>
  )
}

// ---- the catalogue -------------------------------------------------------------------------------------------------

function CoursesTab({ courses, reload }: { courses: Course[]; reload: () => void }) {
  const [editing, setEditing] = useState<Course | 'new' | null>(null)
  return (
    <>
      <div className="row space">
        <p className="muted">{t('The courses the company runs or buys: in-house, at the INPP, or from an outside firm.')}</p>
        <Button onClick={() => setEditing('new')}>{t('Add a course')}</Button>
      </div>
      <div className="card">
        <div className="table-wrap">
          <table>
            <thead><tr><th>{t('Course')}</th><th>{t('Provider')}</th><th>{t('Hours')}</th><th>{t('Cost per person')}</th><th>{t('Trained')}</th><th /></tr></thead>
            <tbody>
              {courses.length === 0 && <tr><td colSpan={6} className="muted">{t('No course yet.')}</td></tr>}
              {courses.map((c) => (
                <tr key={c.id} className={c.active ? undefined : 'muted'}>
                  <td><strong>{c.title}</strong>{!c.active && <span className="badge off" style={{ marginLeft: 6 }}>{t('Not used')}</span>}{c.description && <div className="small muted">{c.description}</div>}</td>
                  <td>{PROVIDERS[c.providerKind]}{c.providerName && <div className="small muted">{c.providerName}</div>}<div className="small muted">{MODES[c.mode]}</div></td>
                  <td>{c.hours}</td>
                  <td>{money(c.costPerPerson, c.currency)}</td>
                  <td>{c.trained}<div className="small muted">{t('{a} sessions', { a: c.sessions })}</div></td>
                  <td><Button className="secondary small" onClick={() => setEditing(c)}>{t('Edit')}</Button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
      {editing && <CourseModal course={editing === 'new' ? null : editing} onClose={() => setEditing(null)} onSaved={() => { setEditing(null); reload() }} />}
    </>
  )
}

function CourseModal({ course, onClose, onSaved }: { course: Course | null; onClose: () => void; onSaved: () => void }) {
  const { me } = useAuth()
  const local = me?.company?.localCurrency ?? 'CDF'
  const [form, setForm] = useState({
    title: course?.title ?? '', providerKind: course?.providerKind ?? 'INTERNAL', providerName: course?.providerName ?? '', mode: course?.mode ?? 'CLASSROOM',
    hours: course?.hours?.toString() ?? '', costPerPerson: course?.costPerPerson?.toString() ?? '0', currency: course?.currency ?? 'USD',
    description: course?.description ?? '', active: course?.active ?? true,
  })
  const [error, setError] = useState<string | null>(null)
  const [skills, setSkills] = useState<Skill[]>([])
  const [built, setBuilt] = useState<number[] | null>(null)
  useEffect(() => {
    api<Skill[]>('/api/skills').then(setSkills).catch(() => undefined)
    api<{ courseId: number; skillId: number }[]>('/api/skills/courses')
      .then((r) => setBuilt(r.filter((x) => x.courseId === course?.id).map((x) => x.skillId))).catch(() => setBuilt(null))
  }, [course?.id])
  const save = async (e: FormEvent) => {
    e.preventDefault()
    const body = { ...form, providerName: form.providerName || null, hours: Number(form.hours), costPerPerson: Number(form.costPerPerson || 0), description: form.description || null }
    try {
      const saved = await api<Course>(course ? `/api/training/courses/${course.id}` : '/api/training/courses', { method: course ? 'PUT' : 'POST', body })
      if (built) await api(`/api/skills/courses/${saved.id}`, { method: 'PUT', body: built })
      onSaved()
    } catch (err) { setError(errorText(err)) }
  }
  return (
    <Modal title={course ? t('Course') : t('Add a course')} size="lg" onClose={onClose}>
      <Form onSubmit={save}>
        <label>{t('Title')}<input maxLength={200} value={form.title} onChange={(e) => setForm({ ...form, title: e.target.value })} required /></label>
        <div className="grid3">
          <label>{t('Provider')}<select value={form.providerKind} onChange={(e) => setForm({ ...form, providerKind: e.target.value as Provider })}>
            {(Object.keys(PROVIDERS) as Provider[]).map((k) => <option key={k} value={k}>{PROVIDERS[k]}</option>)}</select></label>
          <label>{t('Name of the provider')}<input maxLength={200} value={form.providerName} onChange={(e) => setForm({ ...form, providerName: e.target.value })} /></label>
          <label>{t('How')}<select value={form.mode} onChange={(e) => setForm({ ...form, mode: e.target.value as Mode })}>
            {(Object.keys(MODES) as Mode[]).map((k) => <option key={k} value={k}>{MODES[k]}</option>)}</select></label>
        </div>
        <div className="grid3">
          <label>{t('Hours')}<input type="number" min="0.5" max="2000" step="0.5" value={form.hours} onChange={(e) => setForm({ ...form, hours: e.target.value })} required /></label>
          <label>{t('Cost per person')}<input type="number" min="0" step="0.01" value={form.costPerPerson} onChange={(e) => setForm({ ...form, costPerPerson: e.target.value })} /></label>
          <label>{t('Currency')}<select value={form.currency} onChange={(e) => setForm({ ...form, currency: e.target.value })}>
            <option value="USD">USD</option><option value={local}>{local}</option></select></label>
        </div>
        <label>{t('Description')}<textarea rows={3} maxLength={2000} value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} /></label>
        {built && skills.length > 0 && (
          <fieldset>
            <legend>{t('Skills it builds')}</legend>
            <div className="row">
              {skills.map((s) => (
                <label key={s.id} className="check"><input type="checkbox" checked={built.includes(s.id)}
                  onChange={(e) => setBuilt(e.target.checked ? [...built, s.id] : built.filter((x) => x !== s.id))} />{s.name}</label>
              ))}
            </div>
          </fieldset>
        )}
        <label className="check"><input type="checkbox" checked={form.active} onChange={(e) => setForm({ ...form, active: e.target.checked })} />{t('In use')}</label>
        <ErrorText error={error} />
        <div className="modal-foot"><Button className="secondary" onClick={onClose}>{t('Cancel')}</Button><Button type="submit">{t('Save')}</Button></div>
      </Form>
    </Modal>
  )
}

// ---- the effect check ----------------------------------------------------------------------------------------------

function EffectsTab() {
  const [due, setDue] = useState<EffectDue[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [answering, setAnswering] = useState<EffectDue | null>(null)
  const [effect, setEffect] = useState<Effect>('YES')
  const [comment, setComment] = useState('')
  useEffect(() => { api<EffectDue[]>('/api/training/effects').then(setDue).catch((e: Error) => setError(e.message)) }, [])
  const save = async () => {
    if (!answering) return
    try {
      setDue(await api<EffectDue[]>(`/api/training/attendees/${answering.attendeeId}/effect`, { method: 'POST', body: { effect, comment: comment || null } }))
      setAnswering(null); setComment('')
    } catch (e) { setError(errorText(e)) }
  }
  if (!due) return <ErrorText error={error} />
  return (
    <>
      <p className="muted">{t('A few months after a training, the manager says whether it changed the person\'s work. It tells HR which courses are worth running again.')}</p>
      <ErrorText error={error} />
      <div className="card">
        <div className="table-wrap">
          <table>
            <thead><tr><th>{t('Person')}</th><th>{t('Training')}</th><th>{t('Ended')}</th><th /></tr></thead>
            <tbody>
              {due.length === 0 && <tr><td colSpan={4} className="muted">{t('Nothing to answer.')}</td></tr>}
              {due.map((d) => (
                <tr key={d.attendeeId}>
                  <td><Link to={`/users/${d.userId}`}>{d.name}</Link></td><td>{d.courseTitle}</td><td>{day(d.endsOn)}</td>
                  <td><Button className="small" onClick={() => { setAnswering(d); setEffect('YES') }}>{t('Answer')}</Button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
      {answering && (
        <Modal title={t('Did the training help {a}?', { a: answering.name })} onClose={() => setAnswering(null)}>
          <p>{answering.courseTitle}</p>
          <div className="row">
            {(Object.keys(EFFECTS) as Effect[]).map((k) => <label key={k} className="check"><input type="radio" name="effect" checked={effect === k} onChange={() => setEffect(k)} />{EFFECTS[k]}</label>)}
          </div>
          <label>{t('What changed')}<textarea rows={3} maxLength={1000} value={comment} onChange={(e) => setComment(e.target.value)} /></label>
          <ErrorText error={error} />
          <div className="modal-foot"><Button className="secondary" onClick={() => setAnswering(null)}>{t('Cancel')}</Button><Button onClick={() => void save()}>{t('Save')}</Button></div>
        </Modal>
      )}
    </>
  )
}

// ---- apprentices ---------------------------------------------------------------------------------------------------

function ApprenticesTab() {
  const [list, setList] = useState<Apprentice[] | null>(null)
  const [ended, setEnded] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [editing, setEditing] = useState<Apprentice | null>(null)
  const load = () => api<Apprentice[]>(`/api/apprentices?ended=${ended}`).then(setList).catch((e: Error) => setError(e.message))
  useEffect(() => { void load() }, [ended]) // eslint-disable-line react-hooks/exhaustive-deps
  if (!list) return <ErrorText error={error} />
  return (
    <>
      <p className="muted">{t('An apprenticeship contract lasts four years at most, needs the ONEM\'s visa (without it, it is an ordinary employment contract) and a medical certificate. Start one from the person\'s file with the type Apprentice (Code du travail art. 18-35).')}</p>
      <label className="check"><input type="checkbox" checked={ended} onChange={(e) => setEnded(e.target.checked)} />{t('Show ended apprenticeships')}</label>
      <div className="card">
        <div className="table-wrap">
          <table>
            <thead><tr><th>{t('Apprentice')}</th><th>{t('Trade')}</th><th>{t('Contract')}</th><th>{t('ONEM visa')}</th><th>{t('To do')}</th><th /></tr></thead>
            <tbody>
              {list.length === 0 && <tr><td colSpan={6} className="muted">{t('No apprentice.')}</td></tr>}
              {list.map((a) => (
                <tr key={a.contractId}>
                  <td><Link to={`/users/${a.userId}/file`}>{a.name}</Link> <span className="small muted">{a.registrationNo}</span>{a.ageAtStart != null && <div className="small muted">{t('{a} years at the start', { a: a.ageAtStart })}</div>}</td>
                  <td>{a.trade ?? a.occupationName ?? '—'}{a.masterName && <div className="small muted">{t('Master: {a}', { a: a.masterName })}</div>}</td>
                  <td>{day(a.startDate)} – {a.endDate ? day(a.endDate) : '—'}</td>
                  <td>{a.onemVisaOn ? <span className="badge ok">{day(a.onemVisaOn)}</span> : <span className="badge warn">{t('Missing')}</span>}</td>
                  <td className="small">{a.warnings.length === 0 ? <span className="badge ok">{t('Up to date')}</span> : a.warnings.map((w) => <div key={w}>{w}</div>)}</td>
                  <td><Button className="secondary small" onClick={() => setEditing(a)}>{t('Follow-up')}</Button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
      {editing && <ApprenticeModal apprentice={editing} onClose={() => setEditing(null)} onSaved={() => { setEditing(null); void load() }} />}
    </>
  )
}

export function ApprenticeModal({ apprentice, onClose, onSaved }: { apprentice: Apprentice; onClose: () => void; onSaved: (a: Apprentice) => void }) {
  const [people, setPeople] = useState<MyPerson[]>([])
  const a = apprentice
  const [form, setForm] = useState({
    trade: a.trade ?? '', masterUserId: a.masterUserId?.toString() ?? '', onemVisaOn: a.onemVisaOn ?? '', onemVisaNo: a.onemVisaNo ?? '', medicalOn: a.medicalOn ?? '',
    waiverOn: a.waiverOn ?? '', allowanceReviewOn: a.allowanceReviewOn ?? '', endCertificateOn: a.endCertificateOn ?? '', endReportedOn: a.endReportedOn ?? '',
    stayMonths: a.stayMonths?.toString() ?? '', note: a.note ?? '',
  })
  const [error, setError] = useState<string | null>(null)
  useEffect(() => { api<MyPerson[]>('/api/org/my-people').then((p) => setPeople([...p].sort((x, y) => x.name.localeCompare(y.name)))).catch(() => undefined) }, [])
  const set = (k: keyof typeof form) => (e: { target: { value: string } }) => setForm({ ...form, [k]: e.target.value })
  const save = async (e: FormEvent) => {
    e.preventDefault()
    const body = Object.fromEntries(Object.entries(form).map(([k, v]) => [k, v === '' ? null : k === 'masterUserId' || k === 'stayMonths' ? Number(v) : v]))
    try { onSaved(await api<Apprentice>(`/api/apprentices/${a.contractId}`, { method: 'PUT', body })) } catch (err) { setError(errorText(err)) }
  }
  return (
    <Modal title={t('Apprenticeship: {a}', { a: a.name })} size="lg" onClose={onClose}>
      <Form onSubmit={save}>
        <Notices warnings={a.warnings} />
        <div className="grid2">
          <label>{t('Trade learned')}<input maxLength={200} value={form.trade} placeholder={a.occupationName ?? ''} onChange={set('trade')} /></label>
          <label>{t('Master')}<select value={form.masterUserId} onChange={set('masterUserId')}>
            <option value="">{t('Choose')}</option>{people.filter((p) => p.userId !== a.userId).map((p) => <option key={p.userId} value={p.userId}>{p.name}</option>)}</select></label>
        </div>
        <div className="grid3">
          <label>{t('ONEM visa on')}<DateInput value={form.onemVisaOn} onChange={set('onemVisaOn')} /></label>
          <label>{t('Visa number')}<input maxLength={60} value={form.onemVisaNo} onChange={set('onemVisaNo')} /></label>
          <label>{t('Medical certificate of')}<DateInput value={form.medicalOn} onChange={set('medicalOn')} /></label>
        </div>
        {a.ageAtStart != null && a.ageAtStart < 18 && (
          <label>{t('Waiver of the Tribunal de paix (under 18)')}<DateInput value={form.waiverOn} onChange={set('waiverOn')} /></label>
        )}
        <div className="grid3">
          <label>{t('Allowance last reviewed')}<DateInput value={form.allowanceReviewOn} onChange={set('allowanceReviewOn')} />
            <span className="hint">{t('Change the amount with Change grade or a new contract.')}</span></label>
          <label>{t('End certificate given')}<DateInput value={form.endCertificateOn} onChange={set('endCertificateOn')} /></label>
          <label>{t('End reported to the inspector and ONEM')}<DateInput value={form.endReportedOn} onChange={set('endReportedOn')} /></label>
        </div>
        <label className="auto-width">{t('Months to stay after the end (24 at most)')}<input type="number" min="0" max="24" value={form.stayMonths} onChange={set('stayMonths')} /></label>
        <label>{t('Note')}<textarea rows={2} maxLength={1000} value={form.note} onChange={set('note')} /></label>
        <p className="hint">{t('The end certificate and the commitment letter are in Documents and checklists, under letters.')}</p>
        <ErrorText error={error} />
        <div className="modal-foot"><Button className="secondary" onClick={onClose}>{t('Cancel')}</Button><Button type="submit">{t('Save')}</Button></div>
      </Form>
    </Modal>
  )
}

/** On the person's contract page: the apprenticeship follow-up of their apprenticeship contracts. */
export function ApprenticeCard({ userId, contractIds }: { userId: string; contractIds: number[] }) {
  const [list, setList] = useState<Apprentice[]>([])
  const [editing, setEditing] = useState<Apprentice | null>(null)
  const key = contractIds.join(',')
  useEffect(() => {
    Promise.all(contractIds.map((id) => api<Apprentice>(`/api/apprentices/${id}`).catch(() => null)))
      .then((r) => setList(r.filter((x): x is Apprentice => x !== null))).catch(() => undefined)
  }, [userId, key]) // eslint-disable-line react-hooks/exhaustive-deps
  if (list.length === 0) return null
  return (
    <div className="card">
      <h3>{t('Apprenticeship')}</h3>
      {list.map((a) => (
        <div key={a.contractId}>
          <dl className="facts">
            <div><dt>{t('Trade learned')}</dt><dd>{a.trade ?? a.occupationName ?? '—'}</dd></div>
            <div><dt>{t('Master')}</dt><dd>{a.masterName ?? '—'}</dd></div>
            <div><dt>{t('ONEM visa')}</dt><dd>{a.onemVisaOn ? `${day(a.onemVisaOn)}${a.onemVisaNo ? ` · ${a.onemVisaNo}` : ''}` : '—'}</dd></div>
            <div><dt>{t('Medical certificate of')}</dt><dd>{a.medicalOn ? day(a.medicalOn) : '—'}</dd></div>
            {a.stayUntil && <div><dt>{t('Bound to stay until')}</dt><dd>{day(a.stayUntil)}</dd></div>}
          </dl>
          <Notices warnings={a.warnings} />
          <Button className="secondary" onClick={() => setEditing(a)}>{t('Follow-up')}</Button>
        </div>
      ))}
      {editing && <ApprenticeModal apprentice={editing} onClose={() => setEditing(null)}
        onSaved={(a) => { setEditing(null); setList(list.map((x) => x.contractId === a.contractId ? a : x)) }} />}
    </div>
  )
}

// ---- figures -------------------------------------------------------------------------------------------------------

function ReportTab({ year }: { year: number }) {
  const [report, setReport] = useState<Report | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [by, setBy] = useState<'byCategory' | 'byUnit' | 'byPerson'>('byCategory')
  useEffect(() => { setReport(null); api<Report>(`/api/training/report?year=${year}`).then(setReport).catch((e: Error) => setError(e.message)) }, [year])
  if (!report) return <ErrorText error={error} />
  const rows = report[by]
  return (
    <>
      <div className="tiles">
        <div className="tile"><span>{t('People trained')}</span><strong>{report.trained}</strong><small>{t('of {a} on staff', { a: report.staff })}</small></div>
        <div className="tile"><span>{t('Hours of training')}</span><strong>{report.hours}</strong><small>{report.trained > 0 ? t('{a} h a person', { a: (report.hours / report.trained).toFixed(1) }) : ''}</small></div>
        <div className="tile"><span>{t('Cost')}</span><strong>{money(report.cost, report.currency)}</strong><small>{t('INPP levy: {a}', { a: money(report.levy.usd, 'USD') })}</small></div>
        <div className="tile"><span>{t('Women trained')}</span><strong>{report.womenTrained}</strong></div>
        <div className="tile"><span>{t('Apprentices')}</span><strong>{report.apprentices}</strong><small>{t('in {a}', { a: year })}</small></div>
      </div>
      <p className="muted small">{t('These figures also feed the training and apprentice part of the yearly workforce declaration to ONEM.')}</p>
      <div className="card">
        <div className="row space">
          <h3>{t('Training in {a}', { a: year })}</h3>
          <select className="auto-width" value={by} onChange={(e) => setBy(e.target.value as typeof by)} aria-label={t('Group by')}>
            <option value="byCategory">{t('By category')}</option><option value="byUnit">{t('By unit')}</option><option value="byPerson">{t('By person')}</option>
          </select>
        </div>
        <div className="table-wrap">
          <table>
            <thead><tr><th /><th>{t('People')}</th><th>{t('Sessions')}</th><th>{t('Hours')}</th><th>{t('Cost')}</th></tr></thead>
            <tbody>
              {rows.length === 0 && <tr><td colSpan={5} className="muted">{t('No training done in {a}.', { a: year })}</td></tr>}
              {rows.map((r) => <tr key={r.key}><td>{r.label}</td><td>{r.people}</td><td>{r.sessions}</td><td>{r.hours}</td><td>{money(r.cost, report.currency)}</td></tr>)}
            </tbody>
          </table>
        </div>
      </div>
    </>
  )
}

// ---- settings ------------------------------------------------------------------------------------------------------

export function TrainingSettingsPage() {
  const [form, setForm] = useState<Settings | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  useEffect(() => { api<Settings>('/api/training/settings').then(setForm).catch((e: Error) => setError(e.message)) }, [])
  const save = async (e: FormEvent) => {
    e.preventDefault()
    setError(null); setMessage(null)
    try { setForm(await api<Settings>('/api/training/settings', { method: 'PUT', body: form })); setMessage(t('Settings saved.')) } catch (err) { setError(errorText(err)) }
  }
  if (!form) return <section><ErrorText error={error} /></section>
  return (
    <section>
      <p><Link to="/training">{t('← Training')}</Link></p>
      <h2>{t('Training settings')}</h2>
      <Form onSubmit={save}>
        <div className="card">
          <h3>{t('Training')}</h3>
          <label>{t('In charge of training')}<input maxLength={200} value={form.lead} placeholder={t('A person or a department')} onChange={(e) => setForm({ ...form, lead: e.target.value })} />
            <span className="hint">{t('Ordonnance 71-055 art. 22 asks for a training department or named people.')}</span></label>
          <label className="auto-width">{t('Months after a session when the manager is asked about its effect')}<input type="number" min="1" max="12" value={form.effectMonths} onChange={(e) => setForm({ ...form, effectMonths: Number(e.target.value) })} /></label>
        </div>
        <div className="card">
          <h3>{t('Apprentices')}</h3>
          <label>{t('CNSS and tax on the apprentice allowance')}<select value={form.apprenticeContributions} onChange={(e) => setForm({ ...form, apprenticeContributions: e.target.value as Settings['apprenticeContributions'] })}>
            <option value="NONE">{t('None (the allowance is not a salary)')}</option><option value="SALARY">{t('As on a salary')}</option></select>
            <span className="hint">{t('The Code does not settle it; check with your accountant. Employer levies on the gross (INPP, ONEM) still apply.')}</span></label>
        </div>
        <div className="card">
          <h3>{t('Workers\' education leave')}</h3>
          <div className="grid2">
            <label>{t('Working days a year')}<input type="number" min="0" max="60" value={form.workerEducationDays} onChange={(e) => setForm({ ...form, workerEducationDays: Number(e.target.value) })} /></label>
            <label>{t('Days of notice')}<input type="number" min="0" max="90" value={form.workerEducationNoticeDays} onChange={(e) => setForm({ ...form, workerEducationNoticeDays: Number(e.target.value) })} /></label>
          </div>
          <p className="hint">{t('For union members and delegates who follow workers\' education courses: 12 working days a year, paid, asked 30 days ahead (Code du travail art. 268-271). Turn the leave type on or off in Leave settings.')}</p>
        </div>
        <ErrorText error={error} />
        {message && <p className="success">{message}</p>}
        <Button type="submit">{t('Save')}</Button>
      </Form>
    </section>
  )
}

// ---- the employee's own page and the person's history --------------------------------------------------------------

export function MyTrainingPage() {
  const [data, setData] = useState<Mine | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [asking, setAsking] = useState(false)
  const [rating, setRating] = useState<History | null>(null)
  const [stars5, setStars5] = useState(4)
  const [comment, setComment] = useState('')
  const load = () => api<Mine>('/api/training/me').then(setData).catch((e: Error) => setError(e.message))
  useEffect(() => { void load() }, [])
  const rate = async () => {
    if (!rating) return
    try { setData(await api<Mine>(`/api/training/attendees/${rating.attendeeId}/rating`, { method: 'POST', body: { rating: stars5, comment: comment || null } })); setRating(null) } catch (e) { setError(errorText(e)) }
  }
  if (!data) return <section><ErrorText error={error} /></section>
  const today = new Date().toISOString().slice(0, 10)
  const upcoming = data.history.filter((h) => h.status === 'PLANNED' && h.endsOn >= today)
  const past = data.history.filter((h) => !(h.status === 'PLANNED' && h.endsOn >= today))
  return (
    <section>
      <PageTitle title={t('My training')}><Button onClick={() => setAsking(true)}>{t('Ask for training')}</Button></PageTitle>
      <ErrorText error={error} />
      <div className="tiles">
        <div className="tile"><span>{t('Hours this year')}</span><strong>{data.hoursThisYear}</strong></div>
        <div className="tile"><span>{t('Coming')}</span><strong>{upcoming.length}</strong></div>
        <div className="tile"><span>{t('Requests')}</span><strong>{data.requests.filter((r) => r.status === 'REQUESTED').length}</strong><small>{t('waiting for an answer')}</small></div>
      </div>
      {upcoming.length > 0 && (
        <div className="card">
          <h3>{t('Coming sessions')}</h3>
          {upcoming.map((h) => <p key={h.attendeeId}><strong>{h.courseTitle}</strong> · {day(h.startsOn)}{h.endsOn !== h.startsOn && <> – {day(h.endsOn)}</>} · {h.hours} h</p>)}
        </div>
      )}
      <div className="card">
        <h3>{t('My requests')}</h3>
        <div className="table-wrap">
          <table>
            <thead><tr><th>{t('Need')}</th><th>{t('Asked on')}</th><th>{t('Status')}</th></tr></thead>
            <tbody>
              {data.requests.length === 0 && <tr><td colSpan={3} className="muted">{t('No request yet.')}</td></tr>}
              {data.requests.map((r) => <tr key={r.id}><td>{r.topic}{r.decisionComment && <div className="small muted">{r.decisionComment}</div>}</td><td>{day(r.createdAt.slice(0, 10))}</td><td>{statusBadge(r.status)}</td></tr>)}
            </tbody>
          </table>
        </div>
      </div>
      <div className="card">
        <h3>{t('My training history')}</h3>
        <HistoryTable rows={past} onRate={(h) => { setRating(h); setStars5(h.rating ?? 4); setComment(h.ratingComment ?? '') }} />
      </div>
      {asking && <AskModal year={new Date().getFullYear()} courses={data.courses} people={[]} hr={false} self onClose={() => setAsking(false)} onSaved={() => { setAsking(false); void load() }} />}
      {rating && (
        <Modal title={t('How was {a}?', { a: rating.courseTitle })} onClose={() => setRating(null)}>
          <div className="row" role="radiogroup" aria-label={t('Rating')}>
            {[1, 2, 3, 4, 5].map((n) => <Button key={n} className={n <= stars5 ? 'small' : 'secondary small'} onClick={() => setStars5(n)} aria-label={t('{a} out of 5', { a: n })}>★</Button>)}
          </div>
          <label>{t('Comment')}<textarea rows={3} maxLength={1000} value={comment} onChange={(e) => setComment(e.target.value)} /></label>
          <div className="modal-foot"><Button className="secondary" onClick={() => setRating(null)}>{t('Cancel')}</Button><Button onClick={() => void rate()}>{t('Send')}</Button></div>
        </Modal>
      )}
    </section>
  )
}

function HistoryTable({ rows, onRate }: { rows: History[]; onRate?: (h: History) => void }) {
  return (
    <div className="table-wrap">
      <table>
        <thead><tr><th>{t('Training')}</th><th>{t('Dates')}</th><th>{t('Hours')}</th><th>{t('Attended')}</th><th>{t('Rating')}</th><th>{t('Certificate')}</th></tr></thead>
        <tbody>
          {rows.length === 0 && <tr><td colSpan={6} className="muted">{t('No training yet.')}</td></tr>}
          {rows.map((h) => (
            <tr key={h.attendeeId}>
              <td><strong>{h.courseTitle}</strong><div className="small muted">{PROVIDERS[h.providerKind]}</div>{h.effect && <div className="small">{t('Effect: {a}', { a: EFFECTS[h.effect] })}</div>}</td>
              <td>{day(h.startsOn)}{h.endsOn !== h.startsOn && <> – {day(h.endsOn)}</>}</td>
              <td>{h.hours}</td>
              <td>{h.status === 'PLANNED' ? <span className="badge">{t('Planned')}</span> : h.attended ? <span className="badge ok">{t('Yes')}</span> : <span className="badge off">{t('No')}</span>}</td>
              <td>{h.attended && onRate ? <Button className="link" onClick={() => onRate(h)}>{h.rating ? stars(h.rating) : t('Rate it')}</Button> : stars(h.rating)}</td>
              <td>{h.documentId ? <Button className="link" onClick={() => void openFile(`/api/lifecycle/documents/${h.documentId}/file`)}>{t('Open')}</Button> : '—'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

/** The training a person followed, on their record (HR and their manager). */
export function TrainingHistoryCard({ userId }: { userId: string }) {
  const [rows, setRows] = useState<History[] | null>(null)
  useEffect(() => { api<History[]>(`/api/training/people/${userId}`).then(setRows).catch(() => setRows(null)) }, [userId])
  if (!rows) return null
  const hours = rows.filter((h) => h.attended).reduce((s, h) => s + h.hours, 0)
  return (
    <div className="card">
      <div className="row space"><h3>{t('Training')}</h3><span className="muted small">{t('{a} h in all', { a: hours })}</span></div>
      <HistoryTable rows={rows} />
    </div>
  )
}
