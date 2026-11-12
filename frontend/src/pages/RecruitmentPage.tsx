import { useEffect, useState } from 'react'
import { useSearchParams, useNavigate } from 'react-router-dom'
import { ApiError, api, fileToBase64, longDay, openFile } from '../api'
import { useAuth } from '../auth'
import { Button, Form } from '../components/Button'
import { DateInput, DateTimeInput } from '../components/DateFields'
import { useAsk } from '../components/Dialogs'
import { ErrorText } from '../components/ErrorText'
import { Modal } from '../components/Modal'
import { CountrySelect } from '../components/ReferencePickers'
import { PageTitle } from '../components/SettingsLink'
import { PersonSelect } from '../components/PersonSelect'
import { locale, t } from '../i18n'

type Request = {
  id: number; unitId: number; unitName: string; occupationId: number; occupationName: string; posts: number
  reason: 'NEW_POST' | 'REPLACEMENT' | 'TEMPORARY'; replacesUserId: number | null; replacesName: string | null
  contractType: string; wantedOn: string | null; gradeId: number | null; gradeCode: string | null; note: string | null
  postWarning: string | null; status: 'PENDING' | 'APPROVED' | 'REFUSED' | 'CANCELLED'; requestedBy: number; requestedByName: string
  requestedAt: string; decidedBy: string | null; decidedAt: string | null; decisionComment: string | null; vacancyId: number | null
}
type VacancyRow = {
  id: number; requestId: number | null; unitId: number; unitName: string; occupationId: number; occupationName: string; posts: number
  title: string; contractType: string; gradeId: number | null; gradeCode: string | null; place: string | null; closesOn: string | null
  visibility: 'INTERNAL' | 'PUBLIC' | 'INTERNAL_FIRST'; internalDays: number; status: VacancyStatus; onemDeclaredOn: string | null
  openedOn: string | null; closedOn: string | null; applications: number; active: number; interviews: number; offers: number; hired: number
}
type VacancyStatus = 'DRAFT' | 'OPEN' | 'ON_HOLD' | 'FILLED' | 'CLOSED' | 'CANCELLED'
type Flag = { ground: string; words: string }
type AppRow = {
  id: number; vacancyId: number; candidateId: number; name: string; email: string | null; phone: string | null; source: string
  stage: Stage; stageAt: string; nationalityCode: string | null; hasCv: boolean; score: number | null; scorecards: number
  offerAnswer: string | null; hiredUserId: number | null; userId: number | null
}
type Vacancy = { row: VacancyRow; adText: string; flags: Flag[]; applications: AppRow[]; skills: string[]; onemReminder: boolean }
type Stage = 'APPLIED' | 'SCREENED' | 'INTERVIEW' | 'OFFER' | 'HIRED' | 'REJECTED' | 'WITHDRAWN' | 'ON_HOLD'
type Candidate = {
  id: number; firstName: string; middleName: string | null; lastName: string; email: string | null; phone: string | null
  birthDate: string | null; gender: string | null; nationalityCode: string | null; city: string | null; currentJob: string | null
  cvName: string | null; keepForFuture: boolean; consentAt: string | null; deleteAfter: string | null; userId: number | null; note: string | null
}
type SkillScore = { skill: string; score: number }
type Scorecard = { id: number; userId: number; name: string; scores: SkillScore[]; overall: number; recommendation: 'YES' | 'MAYBE' | 'NO'; comment: string | null; savedAt: string }
type Interview = { id: number; applicationId: number; startsAt: string; place: string | null; note: string | null; panel: { userId: number; name: string }[]; scorecards: Scorecard[] }
type Offer = {
  gradeId: number | null; gradeCode: string | null; net: number | null; start: string; end: string | null; trialMonths: number | null
  body: string | null; sentOn: string | null; answer: 'ACCEPTED' | 'DECLINED' | null; answerOn: string | null; minorWaiverOn: string | null; earliestHire: string | null
}
type Warning = { level: 'BLOCK' | 'WARN'; code: string; message: string }
type Application = {
  row: AppRow; candidate: Candidate; vacancyTitle: string; vacancyStatus: VacancyStatus; coverLetter: string | null; rejectReason: string | null
  noRefusalEmail: boolean; events: { at: string; byName: string | null; stage: string | null; note: string | null }[]; interviews: Interview[]
  offer: Offer | null; warnings: Warning[]; skills: string[]; canHire: boolean; internal: Internal | null; channel: string | null
}
type Internal = {
  userId: number; registrationNo: string | null; unit: string | null; occupation: string | null; grade: string | null; inJobSince: string | null
  years: number | null; score: number | null; evaluations: number; onLadder: boolean; minYears: number | null; minScore: number | null
  eligible: boolean; requirement: string | null; manager: string | null; managerToldAt: string | null
}
type CareersSettings = { slug: string | null; enabled: boolean; link: string | null }
type MyInterview = { id: number; applicationId: number; candidate: string; vacancyTitle: string; startsAt: string; place: string | null; scored: boolean }
type Overview = { openVacancies: number; pendingRequests: number; activeApplications: number; sources: Record<string, number>; averageDaysToHire: number | null; hiredThisYear: number; foreignShare: { foreign: number; staff: number } }
type Me = { hr: boolean; units: number[]; interviews: number }
type Unit = { id: number; name: string; open: boolean }
type Occupation = { id: number; code: string; name: string; active: boolean }
type Grade = { id: number; code: string; name: string; active: boolean; baseSalary: number; currency: string }
type Tab = 'overview' | 'requests' | 'vacancies' | 'interviews'

const errorText = (e: unknown) => (e instanceof ApiError ? [e.message, ...Object.values(e.fieldErrors)].join(' ') : t('Something went wrong.'))
const num = (n: number | null | undefined, digits = 0) => n == null ? '—' : Number(n).toLocaleString(locale(), { maximumFractionDigits: digits })
const dateTime = (d: string) => new Date(d).toLocaleString(locale(), { day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' })

const CONTRACT: Record<string, string> = { PERMANENT: t('Permanent (CDI)'), FIXED_TERM: t('Fixed term (CDD)'), INTERN: t('Internship'), CONSULTANT: t('Consultant'), DAILY: t('Day worker'), APPRENTICE: t('Apprenticeship') }
const REASON: Record<Request['reason'], string> = { NEW_POST: t('New post'), REPLACEMENT: t('Replacement'), TEMPORARY: t('Temporary need') }
const REQUEST_STATUS: Record<Request['status'], string> = { PENDING: t('Waiting for HR'), APPROVED: t('Approved'), REFUSED: t('Refused'), CANCELLED: t('Cancelled') }
const VACANCY_STATUS: Record<VacancyStatus, string> = { DRAFT: t('Draft'), OPEN: t('Open for applications'), ON_HOLD: t('On hold'), FILLED: t('Filled'), CLOSED: t('Closed'), CANCELLED: t('Cancelled') }
const VISIBILITY: Record<VacancyRow['visibility'], string> = { PUBLIC: t('Public and staff'), INTERNAL: t('Staff only'), INTERNAL_FIRST: t('Staff first, then public') }
const STAGE: Record<Stage, string> = { APPLIED: t('Application received'), SCREENED: t('Shortlisted'), INTERVIEW: t('Interview'), OFFER: t('Offer'), HIRED: t('Hired'), REJECTED: t('Not selected'), WITHDRAWN: t('Withdrew'), ON_HOLD: t('Kept for later') }
const SOURCE: Record<string, string> = { CAREERS: t('Careers page'), INTERNAL: t('Staff'), REFERRAL: t('Referral'), AGENCY: t('Agency'), OTHER: t('Other') }
const GROUND: Record<string, string> = { SEX: t('Sex'), AGE: t('Age'), MARITAL: t('Marital status'), PREGNANCY: t('Pregnancy'), RELIGION: t('Religion'), ETHNICITY: t('Ethnic origin'), NATIONALITY: t('Nationality'), DISABILITY: t('Disability'), PHOTO: t('Photo') }
const RECOMMEND: Record<Scorecard['recommendation'], string> = { YES: t('Recommended'), MAYBE: t('Maybe'), NO: t('Not recommended') }

const stageBadge = (s: Stage) => <span className={s === 'HIRED' ? 'badge ok' : s === 'OFFER' || s === 'INTERVIEW' ? 'badge warn' : 'badge'}>{STAGE[s]}</span>

/** Recruitment: requests to hire, vacancies, candidates, interviews, offers and the hire. */
export default function RecruitmentPage() {
  const { can } = useAuth()
  const [params, setParams] = useSearchParams()
  const [me, setMe] = useState<Me | null>(null)
  const hr = can('recruitment.manage')
  const vacancy = params.get('vacancy')
  const application = params.get('application')
  const [tab, setTab] = useState<Tab>((params.get('tab') as Tab) ?? (hr ? 'vacancies' : 'requests'))
  useEffect(() => { api<Me>('/api/recruitment/me').then(setMe).catch(() => undefined) }, [])
  const tabs: Partial<Record<Tab, string>> = {}
  if (hr) tabs.overview = t('Overview')
  if (hr || (me?.units.length ?? 0) > 0) { tabs.requests = t('Requests to hire'); tabs.vacancies = t('Vacancies') }
  if ((me?.interviews ?? 0) > 0) tabs.interviews = t('My interviews')
  const go = (k: Tab) => { setTab(k); setParams({}) }
  const [careers, setCareers] = useState(false)
  return (
    <section>
      <PageTitle title={t('Recruitment')}>
        {hr && <Button className="secondary" onClick={() => setCareers(true)}>{t('Careers page')}</Button>}
      </PageTitle>
      {careers && <CareersModal onClose={() => setCareers(false)} />}
      {Object.keys(tabs).length > 1 && !vacancy && !application && (
        <div className="segmented" role="tablist">
          {(Object.keys(tabs) as Tab[]).map((k) => (
            <button key={k} type="button" role="tab" aria-selected={tab === k} className={tab === k ? 'on' : ''} onClick={() => go(k)}>{tabs[k]}</button>
          ))}
        </div>
      )}
      {application ? <ApplicationPanel id={Number(application)} hr={hr} onBack={(v) => setParams(v ? { vacancy: String(v) } : {})} />
        : vacancy ? <VacancyPanel id={Number(vacancy)} hr={hr} onBack={() => setParams({})} openApp={(id) => setParams({ application: String(id) })} />
          : <>
            {tab === 'overview' && hr && <OverviewTab />}
            {tab === 'requests' && <RequestsTab hr={hr} openVacancy={(id) => setParams({ vacancy: String(id) })} />}
            {tab === 'vacancies' && <VacanciesTab hr={hr} open={(id) => setParams({ vacancy: String(id) })} />}
            {tab === 'interviews' && <InterviewsTab open={(id) => setParams({ application: String(id) })} />}
          </>}
    </section>
  )
}

// ---- careers page settings ---------------------------------------------------------------------------------------

function CareersModal({ onClose }: { onClose: () => void }) {
  const [data, setData] = useState<CareersSettings | null>(null)
  const [slug, setSlug] = useState('')
  const [enabled, setEnabled] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)
  useEffect(() => {
    api<CareersSettings>('/api/recruitment/careers').then((d) => { setData(d); setSlug(d.slug ?? ''); setEnabled(d.enabled) }).catch((e) => setError(errorText(e)))
  }, [])
  const submit = async () => {
    setError(null); setSaved(false)
    try {
      const d = await api<CareersSettings>('/api/recruitment/careers', { method: 'PUT', body: { slug: slug.trim() || null, enabled } })
      setData(d); setSaved(true)
    } catch (e) { setError(errorText(e)) }
  }
  return (
    <Modal title={t('Careers page')} onClose={onClose}
      footer={<><Button className="secondary" type="button" onClick={onClose}>{t('Close')}</Button><Button type="submit" form="careers">{t('Save')}</Button></>}>
      <Form id="careers" onSubmit={submit}>
        <p className="small">{t('A public page lists the vacancies marked public, and those for staff first once their days have passed. Candidates apply there without an account; staff see every open vacancy on Job offers.')}</p>
        <label>{t('Short name in the link')}<input maxLength={40} value={slug} onChange={(e) => setSlug(e.target.value.toLowerCase())} placeholder="ntoprog" pattern="[a-z0-9][a-z0-9-]{1,38}[a-z0-9]" /></label>
        <p className="muted small">{t('3 to 40 lowercase letters, digits or dashes.')}</p>
        <label className="check"><input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} />{t('The careers page is on')}</label>
        {data?.link && data.enabled && <p className="small">{t('Page link')}: <a href={data.link} target="_blank" rel="noreferrer">{data.link}</a></p>}
        {saved && <p className="success small">{t('Saved.')}</p>}
        <ErrorText error={error} />
      </Form>
    </Modal>
  )
}

/** The links HR posts on WhatsApp, LinkedIn or a job board: each records where the candidate came from. */
function ShareOffer({ v }: { v: VacancyRow }) {
  const [settings, setSettings] = useState<CareersSettings | null>(null)
  const [copied, setCopied] = useState<string | null>(null)
  useEffect(() => { api<CareersSettings>('/api/recruitment/careers').then(setSettings).catch(() => undefined) }, [])
  if (v.status !== 'OPEN') return null
  if (v.visibility === 'INTERNAL') return <p className="small muted">{t('Staff see this offer on Job offers. It is not on the careers page.')}</p>
  if (!settings) return null
  if (!settings.enabled || !settings.link) return <p className="small muted">{t('Turn the careers page on to share this offer outside the company.')}</p>
  const url = (src: string) => `${settings.link}/${v.id}?src=${src}`
  const copy = async (src: string) => {
    try { await navigator.clipboard.writeText(url(src)); setCopied(src) } catch { window.prompt(t('Copy the link'), url(src)) }
  }
  const later = v.visibility === 'INTERNAL_FIRST' && v.openedOn
    ? new Date(new Date(v.openedOn + 'T00:00:00').getTime() + v.internalDays * 86400000) : null
  return (
    <div className="small">
      <strong>{t('Share the offer')}</strong>
      {later && later > new Date() && <span className="muted"> · {t('on the careers page from {a}', { a: longDay(later.toISOString().slice(0, 10)) })}</span>}
      <div className="row">
        <a className="button secondary" href={`https://wa.me/?text=${encodeURIComponent(`${v.title} ${url('whatsapp')}`)}`} target="_blank" rel="noreferrer">WhatsApp</a>
        <a className="button secondary" href={`https://www.linkedin.com/sharing/share-offsite/?url=${encodeURIComponent(url('linkedin'))}`} target="_blank" rel="noreferrer">LinkedIn</a>
        <Button className="secondary" onClick={() => copy('jobboard')}>{copied === 'jobboard' ? t('Copied') : t('Copy the link for a job board')}</Button>
        <Button className="secondary" onClick={() => copy('link')}>{copied === 'link' ? t('Copied') : t('Copy the link')}</Button>
      </div>
    </div>
  )
}

// ---- overview ----------------------------------------------------------------------------------------------------

function OverviewTab() {
  const [data, setData] = useState<Overview | null>(null)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => { api<Overview>('/api/recruitment/overview').then(setData).catch((e: Error) => setError(e.message)) }, [])
  if (!data) return <ErrorText error={error} />
  const share = data.foreignShare.staff === 0 ? null : (100 * data.foreignShare.foreign) / data.foreignShare.staff
  return (
    <>
      <div className="tiles">
        <div className="tile"><span>{t('Open vacancies')}</span><strong>{data.openVacancies}</strong></div>
        <div className="tile"><span>{t('Requests waiting')}</span><strong>{data.pendingRequests}</strong></div>
        <div className="tile"><span>{t('Candidates in progress')}</span><strong>{data.activeApplications}</strong></div>
        <div className="tile"><span>{t('Hired this year')}</span><strong>{data.hiredThisYear}</strong></div>
        <div className="tile"><span>{t('Average days to hire')}</span><strong>{num(data.averageDaysToHire)}</strong></div>
      </div>
      <div className="card">
        <h3>{t('Where candidates came from (last 12 months)')}</h3>
        <div className="table-wrap"><table><tbody>
          {Object.entries(data.sources).map(([k, v]) => <tr key={k}><td>{SOURCE[k] ?? k}</td><td>{v}</td></tr>)}
        </tbody></table></div>
      </div>
      <div className="card">
        <h3>{t('Foreign staff')}</h3>
        <p>{t('{a} of {b} people under contract ({c} %).', { a: data.foreignShare.foreign, b: data.foreignShare.staff, c: num(share, 1) })}</p>
        <p className="muted small">{t('In the DRC, the share of foreign staff is capped by sector and grade (AM 70/0010). A foreign worker needs a work card, and the post must stay unfilled 30 days after it is declared (AD 87/005).')}</p>
      </div>
    </>
  )
}

// ---- requests to hire ----------------------------------------------------------------------------------------------

function RequestsTab({ hr, openVacancy }: { hr: boolean; openVacancy: (id: number) => void }) {
  const [list, setList] = useState<Request[] | null>(null)
  const [asking, setAsking] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const ask = useAsk()
  const load = () => api<Request[]>('/api/recruitment/requests').then(setList).catch((e: Error) => setError(e.message))
  useEffect(() => { void load() }, [])
  const decide = (r: Request, approve: boolean) => ask({
    title: approve ? t('Approve this request?') : t('Refuse this request?'),
    message: `${r.posts} × ${r.occupationName} · ${r.unitName}`,
    confirmLabel: approve ? t('Approve and open a draft vacancy') : t('Refuse'), danger: !approve,
    fields: [{ label: approve ? t('Comment (optional)') : t('Why'), required: !approve, multiline: true }],
    onConfirm: async ([comment]) => { await api(`/api/recruitment/requests/${r.id}/decision`, { method: 'POST', body: { approve, comment } }); void load() },
  })
  const cancel = (r: Request) => ask({ title: t('Cancel this request?'), confirmLabel: t('Cancel the request'), danger: true,
    onConfirm: async () => { await api(`/api/recruitment/requests/${r.id}/cancel`, { method: 'POST' }); void load() } })
  return (
    <>
      <p className="muted">{t('A unit head asks to hire for their unit; HR approves, and the approved request becomes a vacancy with its ad written from the job description.')}</p>
      <ErrorText error={error} />
      <div className="card">
        <div className="row space"><h3>{t('Requests to hire')}</h3><Button onClick={() => setAsking(true)}>{t('Ask to hire')}</Button></div>
        {list && list.length === 0 && <p className="muted">{t('No request yet.')}</p>}
        {list && list.length > 0 && (
          <div className="table-wrap">
            <table>
              <thead><tr><th>{t('Job title')}</th><th>{t('Unit')}</th><th>{t('Why')}</th><th>{t('Wanted from')}</th><th>{t('Asked by')}</th><th>{t('Status')}</th><th /></tr></thead>
              <tbody>
                {list.map((r) => (
                  <tr key={r.id}>
                    <td><strong>{r.posts > 1 ? `${r.posts} × ` : ''}{r.occupationName}</strong><div className="small muted">{CONTRACT[r.contractType]}{r.gradeCode ? ` · ${r.gradeCode}` : ''}</div>
                      {r.postWarning && <div className="small warn-text">{r.postWarning}</div>}
                      {r.note && <div className="small">{r.note}</div>}</td>
                    <td>{r.unitName}</td>
                    <td>{REASON[r.reason]}{r.replacesName && <div className="small muted">{r.replacesName}</div>}</td>
                    <td className="nowrap">{longDay(r.wantedOn)}</td>
                    <td className="small">{r.requestedByName}<div className="muted">{longDay(r.requestedAt)}</div></td>
                    <td><span className={r.status === 'APPROVED' ? 'badge ok' : r.status === 'PENDING' ? 'badge warn' : 'badge'}>{REQUEST_STATUS[r.status]}</span>
                      {r.decisionComment && <div className="small muted">{r.decisionComment}</div>}</td>
                    <td className="row">
                      {hr && r.status === 'PENDING' && <><Button onClick={() => decide(r, true)}>{t('Approve')}</Button><Button className="secondary" onClick={() => decide(r, false)}>{t('Refuse')}</Button></>}
                      {r.status === 'PENDING' && <button type="button" className="link" onClick={() => cancel(r)}>{t('Cancel')}</button>}
                      {r.vacancyId && <button type="button" className="link" onClick={() => openVacancy(r.vacancyId!)}>{t('Vacancy')}</button>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
      {asking && <RequestModal onClose={() => setAsking(false)} onDone={() => { setAsking(false); void load() }} />}
    </>
  )
}

function useLists() {
  const [units, setUnits] = useState<Unit[]>([])
  const [jobs, setJobs] = useState<Occupation[]>([])
  const [grades, setGrades] = useState<Grade[]>([])
  useEffect(() => {
    api<Unit[]>('/api/org/units').then((u) => setUnits(u.filter((x) => x.open))).catch(() => undefined)
    api<Occupation[]>('/api/occupations').then((o) => setJobs(o.filter((x) => x.active))).catch(() => undefined)
    api<Grade[]>('/api/grades').then((g) => setGrades(g.filter((x) => x.active).sort((a, b) => a.baseSalary - b.baseSalary))).catch(() => undefined)
  }, [])
  return { units, jobs, grades }
}

function RequestModal({ onClose, onDone }: { onClose: () => void; onDone: () => void }) {
  const { units, jobs, grades } = useLists()
  const [me, setMe] = useState<Me | null>(null)
  const [f, setF] = useState({ unitId: '', occupationId: '', posts: '1', reason: 'NEW_POST', replacesUserId: '', contractType: 'PERMANENT', wantedOn: '', gradeId: '', note: '' })
  const [error, setError] = useState<string | null>(null)
  useEffect(() => {
    api<Me>('/api/recruitment/me').then(setMe).catch(() => undefined)
  }, [])
  const set = (k: keyof typeof f) => (e: { target: { value: string } }) => setF({ ...f, [k]: e.target.value })
  const mine = me?.hr ? units : units.filter((u) => me?.units.includes(u.id))
  const submit = async () => {
    setError(null)
    try {
      await api('/api/recruitment/requests', { method: 'POST', body: {
        unitId: Number(f.unitId), occupationId: Number(f.occupationId), posts: Number(f.posts), reason: f.reason,
        replacesUserId: f.replacesUserId ? Number(f.replacesUserId) : null, contractType: f.contractType, wantedOn: f.wantedOn || null,
        gradeId: f.gradeId ? Number(f.gradeId) : null, note: f.note || null } })
      onDone()
    } catch (e) { setError(errorText(e)) }
  }
  return (
    <Modal title={t('Ask to hire')} size="lg" onClose={onClose}
      footer={<><Button className="secondary" type="button" onClick={onClose}>{t('Cancel')}</Button><Button type="submit" form="hire-request">{t('Send to HR')}</Button></>}>
      <Form id="hire-request" onSubmit={submit}>
        <div className="grid2">
          <label>{t('Unit')}<select required value={f.unitId} onChange={set('unitId')}><option value="">{t('Choose…')}</option>{mine.map((u) => <option key={u.id} value={u.id}>{u.name}</option>)}</select></label>
          <label>{t('Job title')}<select required value={f.occupationId} onChange={set('occupationId')}><option value="">{t('Choose…')}</option>{jobs.map((o) => <option key={o.id} value={o.id}>{o.name}</option>)}</select></label>
          <label>{t('Number of posts')}<input type="number" min={1} max={100} required value={f.posts} onChange={set('posts')} /></label>
          <label>{t('Why')}<select value={f.reason} onChange={set('reason')}>{Object.entries(REASON).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select></label>
          {f.reason === 'REPLACEMENT' && <label>{t('Person replaced')}<PersonSelect required value={f.replacesUserId} onChange={(v) => setF({ ...f, replacesUserId: v })} /></label>}
          <label>{t('Contract')}<select value={f.contractType} onChange={set('contractType')}>{Object.entries(CONTRACT).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select></label>
          <label>{t('Wanted from (optional)')}<DateInput value={f.wantedOn} onChange={set('wantedOn')} /></label>
          <label>{t('Grade (optional)')}<select value={f.gradeId} onChange={set('gradeId')}><option value="">{t('HR decides')}</option>{grades.map((g) => <option key={g.id} value={g.id}>{g.code} · {g.name}</option>)}</select></label>
        </div>
        <label>{t('Why this hire is needed (optional)')}<textarea rows={3} maxLength={1000} value={f.note} onChange={set('note')} /></label>
        <ErrorText error={error} />
      </Form>
    </Modal>
  )
}

// ---- vacancies ---------------------------------------------------------------------------------------------------

function VacanciesTab({ hr, open }: { hr: boolean; open: (id: number) => void }) {
  const [closed, setClosed] = useState(false)
  const [list, setList] = useState<VacancyRow[] | null>(null)
  const [adding, setAdding] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const load = () => api<VacancyRow[]>(`/api/recruitment/vacancies?closed=${closed}`).then(setList).catch((e: Error) => setError(e.message))
  useEffect(() => { void load() }, [closed])
  return (
    <>
      <ErrorText error={error} />
      <div className="card">
        <div className="row space">
          <h3>{t('Vacancies')}</h3>
          <div className="row">
            <label className="check"><input type="checkbox" checked={closed} onChange={(e) => setClosed(e.target.checked)} />{t('Show closed')}</label>
            {hr && <Button onClick={() => setAdding(true)}>{t('Add a vacancy')}</Button>}
          </div>
        </div>
        {list && list.length === 0 && <p className="muted">{t('No vacancy.')}</p>}
        {list && list.length > 0 && (
          <div className="table-wrap">
            <table>
              <thead><tr><th>{t('Vacancy')}</th><th>{t('Unit')}</th><th>{t('Status')}</th><th>{t('Candidates')}</th><th>{t('Interview')}</th><th>{t('Offer')}</th><th>{t('Hired')}</th></tr></thead>
              <tbody>
                {list.map((v) => (
                  <tr key={v.id}>
                    <td><button type="button" className="link" onClick={() => open(v.id)}><strong>{v.title}</strong></button>
                      <div className="small muted">{v.posts > 1 ? `${v.posts} × ` : ''}{CONTRACT[v.contractType]} · {VISIBILITY[v.visibility]}</div></td>
                    <td>{v.unitName}</td>
                    <td><span className={v.status === 'OPEN' ? 'badge ok' : 'badge'}>{VACANCY_STATUS[v.status]}</span>
                      {v.openedOn && <div className="small muted">{t('Open since {a}', { a: longDay(v.openedOn) })}</div>}</td>
                    <td>{v.active}<span className="muted small"> / {v.applications}</span></td>
                    <td>{v.interviews}</td><td>{v.offers}</td><td>{v.hired} / {v.posts}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
      {adding && <VacancyModal onClose={() => setAdding(false)} onDone={(id) => { setAdding(false); open(id) }} />}
    </>
  )
}

function VacancyModal({ vacancy, onClose, onDone }: { vacancy?: Vacancy; onClose: () => void; onDone: (id: number) => void }) {
  const { units, jobs, grades } = useLists()
  const v = vacancy?.row
  const [f, setF] = useState({
    unitId: v ? String(v.unitId) : '', occupationId: v ? String(v.occupationId) : '', posts: v ? String(v.posts) : '1', title: v?.title ?? '',
    adText: vacancy?.adText ?? '', contractType: v?.contractType ?? 'PERMANENT', gradeId: v?.gradeId ? String(v.gradeId) : '', place: v?.place ?? '',
    closesOn: v?.closesOn ?? '', visibility: v?.visibility ?? 'PUBLIC', internalDays: v ? String(v.internalDays || 7) : '7',
  })
  const [flags, setFlags] = useState<Flag[]>(vacancy?.flags ?? [])
  const [error, setError] = useState<string | null>(null)
  const set = (k: keyof typeof f) => (e: { target: { value: string } }) => setF({ ...f, [k]: e.target.value })
  useEffect(() => {
    const id = setTimeout(() => {
      api<Flag[]>('/api/recruitment/ad-check', { method: 'POST', body: { title: f.title, adText: f.adText } }).then(setFlags).catch(() => undefined)
    }, 500)
    return () => clearTimeout(id)
  }, [f.title, f.adText])
  const submit = async () => {
    setError(null)
    try {
      const saved = await api<Vacancy>(v ? `/api/recruitment/vacancies/${v.id}` : '/api/recruitment/vacancies', { method: v ? 'PUT' : 'POST', body: {
        unitId: Number(f.unitId), occupationId: Number(f.occupationId), posts: Number(f.posts), title: f.title, adText: f.adText,
        contractType: f.contractType, gradeId: f.gradeId ? Number(f.gradeId) : null, place: f.place || null, closesOn: f.closesOn || null,
        visibility: f.visibility, internalDays: f.visibility === 'INTERNAL_FIRST' ? Number(f.internalDays) : 0 } })
      onDone(saved.row.id)
    } catch (e) { setError(errorText(e)) }
  }
  return (
    <Modal title={v ? t('Edit the vacancy') : t('Add a vacancy')} size="lg" onClose={onClose}
      footer={<><Button className="secondary" type="button" onClick={onClose}>{t('Cancel')}</Button><Button type="submit" form="vacancy">{t('Save')}</Button></>}>
      <Form id="vacancy" onSubmit={submit}>
        <div className="grid2">
          <label>{t('Unit')}<select required value={f.unitId} onChange={set('unitId')}><option value="">{t('Choose…')}</option>{units.map((u) => <option key={u.id} value={u.id}>{u.name}</option>)}</select></label>
          <label>{t('Job title')}<select required value={f.occupationId} onChange={(e) => {
            const job = jobs.find((o) => String(o.id) === e.target.value)
            setF({ ...f, occupationId: e.target.value, title: f.title || job?.name || '' })
          }}><option value="">{t('Choose…')}</option>{jobs.map((o) => <option key={o.id} value={o.id}>{o.name}</option>)}</select></label>
          <label>{t('Title of the ad')}<input required maxLength={200} value={f.title} onChange={set('title')} /></label>
          <label>{t('Number of posts')}<input type="number" min={1} max={100} required value={f.posts} onChange={set('posts')} /></label>
          <label>{t('Contract')}<select value={f.contractType} onChange={set('contractType')}>{Object.entries(CONTRACT).map(([k, x]) => <option key={k} value={k}>{x}</option>)}</select></label>
          <label>{t('Grade (optional)')}<select value={f.gradeId} onChange={set('gradeId')}><option value="">—</option>{grades.map((g) => <option key={g.id} value={g.id}>{g.code} · {g.name}</option>)}</select></label>
          <label>{t('Place of work (optional)')}<input maxLength={200} value={f.place} onChange={set('place')} /></label>
          <label>{t('Applications until (optional)')}<DateInput value={f.closesOn} onChange={set('closesOn')} /></label>
          <label>{t('Who sees the offer')}<select value={f.visibility} onChange={set('visibility')}>{Object.entries(VISIBILITY).map(([k, x]) => <option key={k} value={k}>{x}</option>)}</select></label>
          {f.visibility === 'INTERNAL_FIRST' && <label>{t('Days for staff only')}<input type="number" min={1} max={90} value={f.internalDays} onChange={set('internalDays')} /></label>}
        </div>
        <label>{t('Ad')}<textarea rows={12} required maxLength={20000} value={f.adText} onChange={set('adText')} /></label>
        <p className="muted small">{t('A line starting with "## " is a heading. The text from the job description is a starting point: keep it short and concrete.')}</p>
        <AdFlags flags={flags} />
        <ErrorText error={error} />
      </Form>
    </Modal>
  )
}

function AdFlags({ flags }: { flags: Flag[] }) {
  if (flags.length === 0) return null
  return (
    <div className="notice small">
      <strong>{t('Words to check in the ad')}</strong>
      <p>{t('An ad must not exclude people for their sex, age, marital status, religion, origin, nationality, pregnancy or disability, unless the law requires it for this job (AM 012/2008 art. 15; Labour Code art. 1, 128).')}</p>
      <ul>{flags.map((f) => <li key={f.ground}>{GROUND[f.ground] ?? f.ground}: <em>{f.words}</em></li>)}</ul>
    </div>
  )
}

function VacancyPanel({ id, hr, onBack, openApp }: { id: number; hr: boolean; onBack: () => void; openApp: (id: number) => void }) {
  const [data, setData] = useState<Vacancy | null>(null)
  const [editing, setEditing] = useState(false)
  const [adding, setAdding] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const ask = useAsk()
  const load = () => api<Vacancy>(`/api/recruitment/vacancies/${id}`).then(setData).catch((e: Error) => setError(e.message))
  useEffect(() => { void load() }, [id])
  if (!data) return <><button type="button" className="link" onClick={onBack}>← {t('Back')}</button><ErrorText error={error} /></>
  const v = data.row
  const status = (s: string, label: string, message?: string) => ask({ title: label, message, confirmLabel: label, danger: s === 'CANCELLED' || s === 'CLOSED',
    onConfirm: async () => { setData(await api<Vacancy>(`/api/recruitment/vacancies/${id}/status`, { method: 'POST', body: { status: s } })) } })
  const onem = () => ask({ title: t('Declared to ONEM'), message: t('The date the vacancy was declared to the placement office (ONEM).'), confirmLabel: t('Save'),
    fields: [{ label: t('Date'), type: 'date', required: true, defaultValue: v.onemDeclaredOn ?? new Date().toISOString().slice(0, 10) }],
    onConfirm: async ([on]) => { setData(await api<Vacancy>(`/api/recruitment/vacancies/${id}/onem`, { method: 'PUT', body: { on } })) } })
  const closedVacancy = ['FILLED', 'CLOSED', 'CANCELLED'].includes(v.status)
  return (
    <>
      <button type="button" className="link" onClick={onBack}>← {t('All vacancies')}</button>
      <ErrorText error={error} />
      <div className="card">
        <div className="row space">
          <div>
            <h3>{v.title} <span className={v.status === 'OPEN' ? 'badge ok' : 'badge'}>{VACANCY_STATUS[v.status]}</span></h3>
            <p className="muted small">{v.unitName} · {v.occupationName} · {v.posts > 1 ? `${v.posts} × ` : ''}{CONTRACT[v.contractType]}{v.gradeCode ? ` · ${v.gradeCode}` : ''} · {VISIBILITY[v.visibility]}{v.closesOn ? ` · ${t('until {a}', { a: longDay(v.closesOn) })}` : ''}</p>
          </div>
          {hr && <div className="row">
            {!closedVacancy && <Button className="secondary" onClick={() => setEditing(true)}>{t('Edit')}</Button>}
            {(v.status === 'DRAFT' || v.status === 'ON_HOLD') && <Button onClick={() => status('OPEN', t('Open the vacancy'), data.flags.length > 0 ? t('The ad has words to check. Open it anyway?') : undefined)}>{t('Open the vacancy')}</Button>}
            {v.status === 'OPEN' && <Button className="secondary" onClick={() => status('ON_HOLD', t('Put on hold'))}>{t('Put on hold')}</Button>}
            {!closedVacancy && v.status !== 'DRAFT' && <Button className="secondary" onClick={() => status('CLOSED', t('Close the vacancy'), t('Candidates still in progress are told by email that they were not selected.'))}>{t('Close')}</Button>}
            {v.status === 'DRAFT' && <button type="button" className="link" onClick={() => status('CANCELLED', t('Cancel the vacancy'))}>{t('Cancel')}</button>}
          </div>}
        </div>
        <p className="small">
          {t('Declared to ONEM')}: {v.onemDeclaredOn ? longDay(v.onemDeclaredOn) : <span className="warn-text">{t('not yet')}</span>}
          {hr && !closedVacancy && <> · <button type="button" className="link" onClick={onem}>{v.onemDeclaredOn ? t('Change') : t('Enter the date')}</button></>}
        </p>
        <AdFlags flags={data.flags} />
        {hr && <ShareOffer v={v} />}
        <details><summary>{t('Ad')}</summary><div className="pre small">{data.adText}</div></details>
      </div>
      <div className="card">
        <div className="row space"><h3>{t('Candidates')}</h3>{hr && !closedVacancy && <Button onClick={() => setAdding(true)}>{t('Add a candidate')}</Button>}</div>
        {data.applications.length === 0 ? <p className="muted">{t('No candidate yet.')}</p> : (
          <div className="table-wrap">
            <table>
              <thead><tr><th>{t('Candidate')}</th><th>{t('Came from')}</th><th>{t('Stage')}</th><th>{t('Interview score')}</th><th>{t('CV')}</th></tr></thead>
              <tbody>
                {data.applications.map((a) => (
                  <tr key={a.id} className={a.stage === 'REJECTED' || a.stage === 'WITHDRAWN' ? 'muted' : undefined}>
                    <td><button type="button" className="link" onClick={() => openApp(a.id)}><strong>{a.name}</strong></button>
                      <div className="small muted">{[a.email, a.phone].filter(Boolean).join(' · ')}</div></td>
                    <td className="small">{SOURCE[a.source] ?? a.source}</td>
                    <td>{stageBadge(a.stage)}<div className="small muted">{longDay(a.stageAt)}</div></td>
                    <td>{a.score == null ? '—' : t('{a} / 5 ({b})', { a: num(a.score, 1), b: a.scorecards })}</td>
                    <td>{a.hasCv ? <button type="button" className="link" onClick={() => void openFile(`/api/recruitment/candidates/${a.candidateId}/cv`).catch(() => undefined)}>{t('Open')}</button> : '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
      {editing && <VacancyModal vacancy={data} onClose={() => setEditing(false)} onDone={() => { setEditing(false); void load() }} />}
      {adding && <CandidateModal vacancyId={id} onClose={() => setAdding(false)} onDone={(appId) => { setAdding(false); openApp(appId) }} />}
    </>
  )
}

// ---- candidates ----------------------------------------------------------------------------------------------------

function CandidateModal({ vacancyId, application, onClose, onDone }: { vacancyId?: number; application?: Application; onClose: () => void; onDone: (applicationId: number) => void }) {
  const c = application?.candidate
  const [f, setF] = useState({
    firstName: c?.firstName ?? '', middleName: c?.middleName ?? '', lastName: c?.lastName ?? '', email: c?.email ?? '', phone: c?.phone ?? '',
    birthDate: c?.birthDate ?? '', gender: c?.gender ?? '', nationalityCode: c?.nationalityCode ?? '', city: c?.city ?? '', currentJob: c?.currentJob ?? '',
    note: c?.note ?? '', source: 'OTHER', keepForFuture: c?.keepForFuture ?? false,
  })
  const [file, setFile] = useState<File | null>(null)
  const [error, setError] = useState<string | null>(null)
  const set = (k: keyof typeof f) => (e: { target: { value: string } }) => setF({ ...f, [k]: e.target.value })
  const submit = async () => {
    setError(null)
    try {
      const body = { ...f, middleName: f.middleName || null, email: f.email || null, phone: f.phone || null, birthDate: f.birthDate || null, gender: f.gender || null,
        nationalityCode: f.nationalityCode || null, city: f.city || null, currentJob: f.currentJob || null, note: f.note || null,
        cv: file ? await fileToBase64(file) : null, cvName: file?.name ?? null, cvType: file?.type || null }
      const saved = await api<Application>(application ? `/api/recruitment/applications/${application.row.id}/candidate` : `/api/recruitment/vacancies/${vacancyId}/candidates`,
        { method: application ? 'PUT' : 'POST', body })
      onDone(saved.row.id)
    } catch (e) { setError(errorText(e)) }
  }
  return (
    <Modal title={application ? t('Edit the candidate') : t('Add a candidate')} size="lg" onClose={onClose}
      footer={<><Button className="secondary" type="button" onClick={onClose}>{t('Cancel')}</Button><Button type="submit" form="candidate">{t('Save')}</Button></>}>
      <Form id="candidate" onSubmit={submit}>
        <div className="grid2">
          <label>{t('Last name')}<input required maxLength={100} value={f.lastName} onChange={set('lastName')} /></label>
          <label>{t('Middle name (postnom)')}<input maxLength={100} value={f.middleName} onChange={set('middleName')} /></label>
          <label>{t('First name')}<input required maxLength={100} value={f.firstName} onChange={set('firstName')} /></label>
          <label>{t('Email')}<input type="email" maxLength={254} value={f.email} onChange={set('email')} /></label>
          <label>{t('Phone')}<input maxLength={20} value={f.phone} onChange={set('phone')} placeholder="+243 81 234 5678" /></label>
          <label>{t('Birth date')}<DateInput value={f.birthDate} onChange={set('birthDate')} /></label>
          <label>{t('Gender')}<select value={f.gender} onChange={set('gender')}><option value="">—</option><option value="FEMALE">{t('Female')}</option><option value="MALE">{t('Male')}</option></select></label>
          <label>{t('Nationality')}<CountrySelect nationality value={f.nationalityCode} onChange={(code) => setF({ ...f, nationalityCode: code ?? '' })} /></label>
          <label>{t('City')}<input maxLength={100} value={f.city} onChange={set('city')} /></label>
          <label>{t('Current job (optional)')}<input maxLength={200} value={f.currentJob} onChange={set('currentJob')} /></label>
          {!application && <label>{t('Came from')}<select value={f.source} onChange={set('source')}>{['REFERRAL', 'AGENCY', 'OTHER'].map((k) => <option key={k} value={k}>{SOURCE[k]}</option>)}</select></label>}
          <label>{t('CV (PDF or Word, 5 MB at most)')}<input type="file" accept=".pdf,.doc,.docx,application/pdf,application/msword,application/vnd.openxmlformats-officedocument.wordprocessingml.document" onChange={(e) => setFile(e.target.files?.[0] ?? null)} /></label>
        </div>
        <label>{t('Note (optional)')}<textarea rows={2} maxLength={1000} value={f.note} onChange={set('note')} /></label>
        <label className="check"><input type="checkbox" checked={f.keepForFuture} onChange={(e) => setF({ ...f, keepForFuture: e.target.checked })} />{t('The candidate agreed to be kept for later offers')}</label>
        <p className="muted small">{t('Without that agreement, the file is deleted 12 months after the vacancy closes.')}</p>
        <ErrorText error={error} />
      </Form>
    </Modal>
  )
}

function ApplicationPanel({ id, hr, onBack }: { id: number; hr: boolean; onBack: (vacancyId?: number) => void }) {
  const { me: user } = useAuth()
  const navigate = useNavigate()
  const [data, setData] = useState<Application | null>(null)
  const [modal, setModal] = useState<'edit' | 'interview' | 'offer' | 'hire' | { score: Interview } | null>(null)
  const [error, setError] = useState<string | null>(null)
  const ask = useAsk()
  const load = () => api<Application>(`/api/recruitment/applications/${id}`).then(setData).catch((e: Error) => setError(e.message))
  useEffect(() => { void load() }, [id])
  if (!data) return <><button type="button" className="link" onClick={() => onBack()}>← {t('Back')}</button><ErrorText error={error} /></>
  const a = data.row
  const c = data.candidate
  const open = ['APPLIED', 'SCREENED', 'INTERVIEW', 'OFFER', 'ON_HOLD'].includes(a.stage)
  const move = (stage: Stage) => {
    if (stage === 'REJECTED') {
      ask({ title: t('Not selected'), message: t('The candidate gets a short, polite email unless you untick it below.'), confirmLabel: t('Not selected'), danger: true,
        fields: [{ label: t('Reason (kept here, not sent)'), required: false }, { label: t('Send the email? (yes / no)'), defaultValue: t('yes') }],
        onConfirm: async ([reason, tell]) => {
          setData(await api<Application>(`/api/recruitment/applications/${id}/stage`, { method: 'POST', body: { stage, rejectReason: reason, tell: !/^(n|non|no)$/i.test(tell.trim()) } }))
        } })
      return
    }
    void api<Application>(`/api/recruitment/applications/${id}/stage`, { method: 'POST', body: { stage } }).then(setData).catch((e) => setError(errorText(e)))
  }
  const note = () => ask({ title: t('Add a note'), confirmLabel: t('Save'), fields: [{ label: t('Note'), required: true, multiline: true }],
    onConfirm: async ([n]) => { setData(await api<Application>(`/api/recruitment/applications/${id}/notes`, { method: 'POST', body: { note: n } })) } })
  const offerSent = () => ask({ title: t('Offer sent'), confirmLabel: t('Save'), fields: [{ label: t('Date'), type: 'date', required: true, defaultValue: new Date().toISOString().slice(0, 10) }],
    onConfirm: async ([on]) => { setData(await api<Application>(`/api/recruitment/applications/${id}/offer/sent`, { method: 'POST', body: { on } })) } })
  const answer = (ans: 'ACCEPTED' | 'DECLINED') => ask({ title: ans === 'ACCEPTED' ? t('Offer accepted') : t('Offer declined'), confirmLabel: t('Save'),
    fields: [{ label: t('Date'), type: 'date', required: true, defaultValue: new Date().toISOString().slice(0, 10) }],
    onConfirm: async ([on]) => { setData(await api<Application>(`/api/recruitment/applications/${id}/offer/answer`, { method: 'POST', body: { answer: ans, on } })) } })
  const cancelInterview = (i: Interview) => ask({ title: t('Cancel this interview?'), confirmLabel: t('Cancel the interview'), danger: true,
    onConfirm: async () => { setData(await api<Application>(`/api/recruitment/interviews/${i.id}`, { method: 'DELETE' })) } })
  return (
    <>
      <button type="button" className="link" onClick={() => onBack(hr ? a.vacancyId : undefined)}>← {data.vacancyTitle}</button>
      <ErrorText error={error} />
      <div className="card">
        <div className="row space">
          <div>
            <h3>{a.name} {stageBadge(a.stage)}</h3>
            <p className="muted small">{data.vacancyTitle} · {SOURCE[a.source] ?? a.source}{data.channel ? ` (${data.channel})` : ''}</p>
          </div>
          {hr && <div className="row">
            <Button className="secondary" onClick={() => setModal('edit')}>{t('Edit')}</Button>
            {open && a.stage !== 'SCREENED' && a.stage !== 'OFFER' && <Button className="secondary" onClick={() => move('SCREENED')}>{t('Shortlist')}</Button>}
            {open && <Button className="secondary" onClick={() => setModal('interview')}>{t('Plan an interview')}</Button>}
            {open && <Button onClick={() => setModal('offer')}>{data.offer ? t('Change the offer') : t('Make an offer')}</Button>}
            {open && a.stage !== 'ON_HOLD' && <button type="button" className="link" onClick={() => move('ON_HOLD')}>{t('Keep for later')}</button>}
            {open && <button type="button" className="link" onClick={() => move('WITHDRAWN')}>{t('Withdrew')}</button>}
            {open && <button type="button" className="link" onClick={() => move('REJECTED')}>{t('Not selected')}</button>}
          </div>}
        </div>
        <div className="grid2 small">
          <div>{t('Email')}: {c.email ?? '—'}</div><div>{t('Phone')}: {c.phone ?? '—'}</div>
          <div>{t('Birth date')}: {longDay(c.birthDate)}</div><div>{t('Nationality')}: {c.nationalityCode ?? '—'}</div>
          <div>{t('City')}: {c.city ?? '—'}</div><div>{t('Current job')}: {c.currentJob ?? '—'}</div>
          <div>{t('CV')}: {a.hasCv ? <button type="button" className="link" onClick={() => void openFile(`/api/recruitment/candidates/${c.id}/cv`).catch((e) => setError(errorText(e)))}>{c.cvName ?? t('Open')}</button> : '—'}</div>
          <div>{c.keepForFuture ? t('Kept for later offers') : c.deleteAfter ? t('File deleted after {a}', { a: longDay(c.deleteAfter) }) : ''}</div>
        </div>
        {data.internal && <InternalFacts i={data.internal} />}
        {data.coverLetter && <details><summary>{t('Cover letter')}</summary><div className="pre small">{data.coverLetter}</div></details>}
        {c.note && <p className="small">{c.note}</p>}
        {data.rejectReason && <p className="small muted">{t('Reason: {a}', { a: data.rejectReason })}</p>}
      </div>
      {data.warnings.length > 0 && hr && (
        <div className="notice small">
          <strong>{t('Before hiring')}</strong>
          <ul>{data.warnings.map((w) => <li key={w.code} className={w.level === 'BLOCK' ? 'error' : undefined}>{w.message}</li>)}</ul>
        </div>
      )}
      <div className="card">
        <h3>{t('Interviews')}</h3>
        {data.interviews.length === 0 ? <p className="muted">{t('No interview planned.')}</p> : data.interviews.map((i) => {
          const mine = i.panel.some((p) => p.userId === user?.id)
          const scored = i.scorecards.some((s) => s.userId === user?.id)
          return (
            <div key={i.id} className="sub-card">
              <div className="row space">
                <div><strong>{dateTime(i.startsAt)}</strong>{i.place && <span className="muted"> · {i.place}</span>}
                  <div className="small muted">{t('Panel')}: {i.panel.map((p) => p.name).join(', ')}</div>{i.note && <div className="small">{i.note}</div>}</div>
                <div className="row">
                  {(mine || hr) && <Button className={scored ? 'secondary' : undefined} onClick={() => setModal({ score: i })}>{scored ? t('Change my scorecard') : t('Fill my scorecard')}</Button>}
                  {hr && i.scorecards.length === 0 && <button type="button" className="link" onClick={() => cancelInterview(i)}>{t('Cancel')}</button>}
                </div>
              </div>
              {i.scorecards.length > 0 && (
                <div className="table-wrap">
                  <table>
                    <thead><tr><th>{t('Panel member')}</th>{data.skills.map((s) => <th key={s} className="small">{s}</th>)}<th>{t('Overall')}</th><th>{t('View')}</th></tr></thead>
                    <tbody>
                      {i.scorecards.map((s) => (
                        <tr key={s.id}>
                          <td>{s.name}{s.comment && <div className="small muted">{s.comment}</div>}</td>
                          {data.skills.map((k) => <td key={k}>{s.scores.find((x) => x.skill === k)?.score ?? '—'}</td>)}
                          <td><strong>{s.overall}</strong> / 5</td>
                          <td><span className={s.recommendation === 'YES' ? 'badge ok' : s.recommendation === 'NO' ? 'badge off' : 'badge warn'}>{RECOMMEND[s.recommendation]}</span></td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
              {!hr && !scored && mine && <p className="muted small">{t("You see the others' scores once you have given yours.")}</p>}
            </div>
          )
        })}
      </div>
      {data.offer && hr && (
        <div className="card">
          <div className="row space">
            <h3>{t('Offer')}</h3>
            <div className="row">
              <Button className="secondary" onClick={() => void openFile(`/api/recruitment/applications/${id}/offer.pdf`).catch((e) => setError(errorText(e)))}>{t('Offer letter (PDF)')}</Button>
              {a.stage === 'OFFER' && <Button className="secondary" onClick={offerSent}>{data.offer.sentOn ? t('Change the date sent') : t('Mark as sent')}</Button>}
              {a.stage === 'OFFER' && data.offer.answer !== 'ACCEPTED' && <Button className="secondary" onClick={() => answer('ACCEPTED')}>{t('Accepted')}</Button>}
              {a.stage === 'OFFER' && data.offer.answer == null && <button type="button" className="link" onClick={() => answer('DECLINED')}>{t('Declined')}</button>}
              {data.canHire && <Button onClick={() => setModal('hire')}>{a.userId ? t('Move to this post') : t('Hire')}</Button>}
            </div>
          </div>
          <div className="grid2 small">
            <div>{t('Grade')}: {data.offer.gradeCode ?? '—'}</div>
            <div>{t('Net per month')}: {num(data.offer.net, 2)}</div>
            <div>{t('Start date')}: {longDay(data.offer.start)}{data.offer.end ? ` → ${longDay(data.offer.end)}` : ''}</div>
            <div>{t('Trial period')}: {data.offer.trialMonths ? t('{a} month(s)', { a: data.offer.trialMonths }) : '—'}</div>
            <div>{t('Date sent')}: {longDay(data.offer.sentOn)}</div>
            <div>{t('Answer')}: {data.offer.answer === 'ACCEPTED' ? t('Accepted on {a}', { a: longDay(data.offer.answerOn) }) : data.offer.answer === 'DECLINED' ? t('Declined on {a}', { a: longDay(data.offer.answerOn) }) : '—'}</div>
          </div>
          {a.hiredUserId && <p><button type="button" className="link" onClick={() => navigate(`/users/${a.hiredUserId}`)}>{t('Open the employee file')}</button></p>}
        </div>
      )}
      {hr && (
        <div className="card">
          <div className="row space"><h3>{t('History')}</h3><Button className="secondary" onClick={note}>{t('Add a note')}</Button></div>
          <ul className="small">
            {data.events.map((e, i) => <li key={i}><span className="muted">{dateTime(e.at)}</span> · {e.stage ? STAGE[e.stage as Stage] ?? e.stage : ''}{e.note ? ` · ${e.note}` : ''}{e.byName ? <span className="muted"> · {e.byName}</span> : ''}</li>)}
          </ul>
        </div>
      )}
      {modal === 'edit' && <CandidateModal application={data} onClose={() => setModal(null)} onDone={() => { setModal(null); void load() }} />}
      {modal === 'interview' && <InterviewModal applicationId={id} onClose={() => setModal(null)} onDone={(x) => { setModal(null); setData(x) }} />}
      {modal === 'offer' && <OfferModal data={data} onClose={() => setModal(null)} onDone={(x) => { setModal(null); setData(x) }} />}
      {modal === 'hire' && (a.userId ? <MoveModal data={data} onClose={() => setModal(null)} onDone={(userId) => navigate(`/users/${userId}`)} />
        : <HireModal data={data} onClose={() => setModal(null)} onDone={(userId) => navigate(`/users/${userId}`)} />)}
      {modal && typeof modal === 'object' && <ScoreModal interview={modal.score} skills={data.skills} onClose={() => setModal(null)} onDone={(x) => { setModal(null); setData(x) }} />}
    </>
  )
}

function InterviewModal({ applicationId, onClose, onDone }: { applicationId: number; onClose: () => void; onDone: (a: Application) => void }) {
  const [startsAt, setStartsAt] = useState('')
  const [place, setPlace] = useState('')
  const [note, setNote] = useState('')
  const [query, setQuery] = useState('')
  const [found, setFound] = useState<{ id: number; fullName: string }[]>([])
  const [panel, setPanel] = useState<{ id: number; fullName: string }[]>([])
  const [error, setError] = useState<string | null>(null)
  useEffect(() => {
    const id = setTimeout(() => {
      api<{ items: { id: number; fullName: string }[] }>(`/api/users?size=10&q=${encodeURIComponent(query)}`).then((p) => setFound(p.items)).catch(() => undefined)
    }, 300)
    return () => clearTimeout(id)
  }, [query])
  const submit = async () => {
    setError(null)
    try {
      onDone(await api<Application>(`/api/recruitment/applications/${applicationId}/interviews`, { method: 'POST', body: {
        startsAt: new Date(startsAt).toISOString(), place: place || null, note: note || null, panel: panel.map((p) => p.id) } }))
    } catch (e) { setError(errorText(e)) }
  }
  return (
    <Modal title={t('Plan an interview')} size="lg" onClose={onClose}
      footer={<><Button className="secondary" type="button" onClick={onClose}>{t('Cancel')}</Button><Button type="submit" form="interview">{t('Plan and tell the panel')}</Button></>}>
      <Form id="interview" onSubmit={submit}>
        <div className="grid2">
          <label>{t('Day and time')}<DateTimeInput required value={startsAt} onChange={(e) => setStartsAt(e.target.value)} /></label>
          <label>{t('Place or link (optional)')}<input maxLength={200} value={place} onChange={(e) => setPlace(e.target.value)} /></label>
        </div>
        <label>{t('Panel')}<input value={query} onChange={(e) => setQuery(e.target.value)} placeholder={t('Search a name')} /></label>
        <div className="row">
          {found.filter((p) => !panel.some((x) => x.id === p.id)).map((p) => <button key={p.id} type="button" className="chip" onClick={() => setPanel([...panel, p])}>+ {p.fullName}</button>)}
        </div>
        {panel.length > 0 && <div className="row">{panel.map((p) => <button key={p.id} type="button" className="chip on" onClick={() => setPanel(panel.filter((x) => x.id !== p.id))}>{p.fullName} ×</button>)}</div>}
        <label>{t('Note for the panel (optional)')}<textarea rows={2} maxLength={1000} value={note} onChange={(e) => setNote(e.target.value)} /></label>
        <ErrorText error={error} />
      </Form>
    </Modal>
  )
}

function ScoreModal({ interview, skills, onClose, onDone }: { interview: Interview; skills: string[]; onClose: () => void; onDone: (a: Application) => void }) {
  const { me: user } = useAuth()
  const mine = interview.scorecards.find((s) => s.userId === user?.id)
  const [scores, setScores] = useState<Record<string, number>>(Object.fromEntries(skills.map((s) => [s, mine?.scores.find((x) => x.skill === s)?.score ?? 0])))
  const [overall, setOverall] = useState(mine?.overall ?? 0)
  const [recommendation, setRecommendation] = useState<string>(mine?.recommendation ?? '')
  const [comment, setComment] = useState(mine?.comment ?? '')
  const [error, setError] = useState<string | null>(null)
  const submit = async () => {
    setError(null)
    if (overall === 0 || Object.values(scores).some((v) => v === 0)) { setError(t('Give every score, from 1 to 5.')); return }
    try {
      onDone(await api<Application>(`/api/recruitment/interviews/${interview.id}/scorecard`, { method: 'PUT', body: {
        scores: skills.map((s) => ({ skill: s, score: scores[s] })), overall, recommendation, comment: comment || null } }))
    } catch (e) { setError(errorText(e)) }
  }
  const scale = (value: number, change: (n: number) => void, label: string) => (
    <div className="row score-row" role="radiogroup" aria-label={label}>
      {[1, 2, 3, 4, 5].map((n) => <button key={n} type="button" role="radio" aria-checked={value === n} className={value === n ? 'chip on' : 'chip'} onClick={() => change(n)}>{n}</button>)}
    </div>
  )
  return (
    <Modal title={t('My scorecard')} size="lg" onClose={onClose}
      footer={<><Button className="secondary" type="button" onClick={onClose}>{t('Cancel')}</Button><Button type="submit" form="scorecard">{t('Save')}</Button></>}>
      <Form id="scorecard" onSubmit={submit}>
        <p className="muted small">{t('1 = far below what the job needs, 3 = what the job needs, 5 = well above. Score only what the job description asks, never sex, age, family, religion or origin.')}</p>
        {skills.map((s) => <div key={s} className="row space"><span>{s}</span>{scale(scores[s] ?? 0, (n) => setScores({ ...scores, [s]: n }), s)}</div>)}
        <div className="row space"><strong>{t('Overall')}</strong>{scale(overall, setOverall, t('Overall'))}</div>
        <label>{t('Do you recommend this person?')}
          <select required value={recommendation} onChange={(e) => setRecommendation(e.target.value)}>
            <option value="">{t('Choose…')}</option>{Object.entries(RECOMMEND).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
          </select>
        </label>
        <label>{t('Comment (optional)')}<textarea rows={3} maxLength={2000} value={comment} onChange={(e) => setComment(e.target.value)} /></label>
        <ErrorText error={error} />
      </Form>
    </Modal>
  )
}

function OfferModal({ data, onClose, onDone }: { data: Application; onClose: () => void; onDone: (a: Application) => void }) {
  const { grades } = useLists()
  const o = data.offer
  const [f, setF] = useState({ gradeId: o?.gradeId ? String(o.gradeId) : '', net: o?.net != null ? String(o.net) : '', start: o?.start ?? '', end: o?.end ?? '',
    trialMonths: o?.trialMonths != null ? String(o.trialMonths) : '', body: o?.body ?? '' })
  const [error, setError] = useState<string | null>(null)
  const set = (k: keyof typeof f) => (e: { target: { value: string } }) => setF({ ...f, [k]: e.target.value })
  const submit = async () => {
    setError(null)
    try {
      onDone(await api<Application>(`/api/recruitment/applications/${data.row.id}/offer`, { method: 'PUT', body: {
        gradeId: Number(f.gradeId), net: f.net ? Number(f.net) : null, start: f.start, end: f.end || null,
        trialMonths: f.trialMonths ? Number(f.trialMonths) : null, body: f.body || null } }))
    } catch (e) { setError(errorText(e)) }
  }
  return (
    <Modal title={o ? t('Change the offer') : t('Make an offer')} size="lg" onClose={onClose}
      footer={<><Button className="secondary" type="button" onClick={onClose}>{t('Cancel')}</Button><Button type="submit" form="offer">{t('Save the offer')}</Button></>}>
      <Form id="offer" onSubmit={submit}>
        <div className="grid2">
          <label>{t('Grade')}<select required value={f.gradeId} onChange={set('gradeId')}><option value="">{t('Choose…')}</option>{grades.map((g) => <option key={g.id} value={g.id}>{g.code} · {g.name}</option>)}</select></label>
          <label>{t('Net per month (optional)')}<input type="number" min={0.01} step={0.01} value={f.net} onChange={set('net')} /></label>
          <label>{t('Start date')}<DateInput required value={f.start} onChange={set('start')} /></label>
          <label>{t('End date (fixed term)')}<DateInput value={f.end} onChange={set('end')} /></label>
          <label>{t('Trial period (months)')}<input type="number" min={0} max={6} value={f.trialMonths} onChange={set('trialMonths')} /></label>
        </div>
        <label>{t('Letter text (optional: left empty, it is written from the terms above)')}<textarea rows={10} maxLength={20000} value={f.body} onChange={set('body')} /></label>
        <p className="muted small">{t('Without a net, the basic of the grade applies. The net becomes the agreed net of the first contract, solved as grade basic plus complement.')}</p>
        <ErrorText error={error} />
      </Form>
    </Modal>
  )
}

/** A staff applicant: where they stand today, and what the ladder asks to reach this job. */
function InternalFacts({ i }: { i: Internal }) {
  return (
    <div className="sub-card small">
      <strong>{t('Staff member')}</strong>{i.registrationNo && <span className="muted"> · {i.registrationNo}</span>}
      {' · '}<a href={`/users/${i.userId}`}>{t('Employee file')}</a>
      <div className="grid2">
        <div>{t('Unit')}: {i.unit ?? '—'}</div><div>{t('Job title')}: {i.occupation ?? '—'}</div>
        <div>{t('Grade')}: {i.grade ?? '—'}</div><div>{t('In the job since')}: {i.inJobSince ? `${longDay(i.inJobSince)} (${t('{a} years', { a: num(i.years, 1) })})` : '—'}</div>
        <div>{t('Evaluation average (2 years)')}: {i.score == null ? '—' : `${num(i.score, 1)} % (${i.evaluations})`}</div>
        <div>{t('Manager')}: {i.manager ?? '—'}{i.managerToldAt ? ` · ${t('told on {a}', { a: longDay(i.managerToldAt) })}` : ` · ${t('told at the shortlist')}`}</div>
      </div>
      {i.onLadder ? (
        <p className={i.eligible ? 'success' : 'warn-text'}>
          {i.eligible ? t('The ladder leads here and its conditions are met.') : t('The ladder leads here, but not all conditions are met.')}
          {' '}{t('Asks {y} years in the job', { y: num(i.minYears, 1) })}{i.minScore != null ? `, ${t('an average of {s} %', { s: num(i.minScore, 1) })}` : ''}{i.requirement ? `, ${i.requirement}` : ''}.
        </p>
      ) : <p className="muted">{t('No ladder leads from their job to this one.')}</p>}
    </div>
  )
}

function HireModal({ data, onClose, onDone }: { data: Application; onClose: () => void; onDone: (userId: number) => void }) {
  const c = data.candidate
  const minor = data.warnings.some((w) => w.code === 'MINOR')
  const [email, setEmail] = useState(c.email ?? '')
  const [phone, setPhone] = useState(c.phone ?? '')
  const [registrationNo, setRegistrationNo] = useState('')
  const [waiver, setWaiver] = useState(data.offer?.minorWaiverOn ?? '')
  const [error, setError] = useState<string | null>(null)
  const submit = async () => {
    setError(null)
    try {
      const r = await api<{ userId: number }>(`/api/recruitment/applications/${data.row.id}/hire`, { method: 'POST', body: {
        email, phone, registrationNo: registrationNo || null, minorWaiverOn: waiver || null } })
      onDone(r.userId)
    } catch (e) { setError(errorText(e)) }
  }
  return (
    <Modal title={t('Hire {a}', { a: data.row.name })} onClose={onClose}
      footer={<><Button className="secondary" type="button" onClick={onClose}>{t('Cancel')}</Button><Button type="submit" form="hire">{t('Hire')}</Button></>}>
      <Form id="hire" onSubmit={submit}>
        <p className="small">{t('This creates the employee in the vacancy\'s unit with the first contract from the offer, opens the hiring checklist, and starts the hire declaration to ONEM.')}</p>
        <label>{t('Email')}<input type="email" required value={email} onChange={(e) => setEmail(e.target.value)} /></label>
        <label>{t('Phone')}<input required value={phone} onChange={(e) => setPhone(e.target.value)} /></label>
        <label>{t('Registration number (optional)')}<input maxLength={32} value={registrationNo} onChange={(e) => setRegistrationNo(e.target.value)} placeholder={t('Empty: numbered by HR Pay when the company has a format')} /></label>
        {minor && <label>{t('Date of the waiver of the Président du Tribunal de paix')}<DateInput required value={waiver} onChange={(e) => setWaiver(e.target.value)} /></label>}
        {data.offer?.earliestHire && <p className="muted small">{t('The contract can be signed from {a}, two working days after the draft reached the person (art. 46).', { a: longDay(data.offer.earliestHire) })}</p>}
        <ErrorText error={error} />
      </Form>
    </Modal>
  )
}

/** A staff member who won the vacancy: a promotion or a transfer, with its avenant, not a second hire. */
function MoveModal({ data, onClose, onDone }: { data: Application; onClose: () => void; onDone: (userId: number) => void }) {
  const [error, setError] = useState<string | null>(null)
  const submit = async () => {
    setError(null)
    try {
      const r = await api<{ userId: number }>(`/api/recruitment/applications/${data.row.id}/hire`, { method: 'POST', body: {} })
      onDone(r.userId)
    } catch (e) { setError(errorText(e)) }
  }
  return (
    <Modal title={t('Move {a} to this post', { a: data.row.name })} onClose={onClose}
      footer={<><Button className="secondary" type="button" onClick={onClose}>{t('Cancel')}</Button><Button type="submit" form="move">{t('Move to this post')}</Button></>}>
      <Form id="move" onSubmit={submit}>
        <p className="small">{t('From the offer\'s start date, the employee moves to the vacancy\'s unit, job and grade: a promotion, a transfer or both. The avenant is drafted in Documents and letters, and the pay follows the new grade.')}</p>
        <ErrorText error={error} />
      </Form>
    </Modal>
  )
}

// ---- my interviews -------------------------------------------------------------------------------------------------

function InterviewsTab({ open }: { open: (applicationId: number) => void }) {
  const [list, setList] = useState<MyInterview[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => { api<MyInterview[]>('/api/recruitment/interviews/mine').then(setList).catch((e: Error) => setError(e.message)) }, [])
  return (
    <>
      <ErrorText error={error} />
      <div className="card">
        <h3>{t('Interviews I sit on')}</h3>
        {list && list.length === 0 && <p className="muted">{t('No interview.')}</p>}
        {list && list.length > 0 && (
          <div className="table-wrap"><table>
            <thead><tr><th>{t('When')}</th><th>{t('Candidate')}</th><th>{t('Vacancy')}</th><th>{t('My scorecard')}</th></tr></thead>
            <tbody>{list.map((i) => (
              <tr key={i.id}>
                <td className="nowrap">{dateTime(i.startsAt)}{i.place && <div className="small muted">{i.place}</div>}</td>
                <td><button type="button" className="link" onClick={() => open(i.applicationId)}>{i.candidate}</button></td>
                <td>{i.vacancyTitle}</td>
                <td>{i.scored ? <span className="badge ok">{t('Done')}</span> : <span className="badge warn">{t('To fill')}</span>}</td>
              </tr>
            ))}</tbody>
          </table></div>
        )}
      </div>
    </>
  )
}


