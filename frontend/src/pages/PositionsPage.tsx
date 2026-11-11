import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { ApiError, api, longDay, money, openFile } from '../api'
import { Button, Form } from '../components/Button'
import { useAsk } from '../components/Dialogs'
import { ErrorText } from '../components/ErrorText'
import { Modal } from '../components/Modal'
import { PageTitle } from '../components/SettingsLink'
import { locale, t } from '../i18n'

const num = (n: number | null) => n == null ? '—' : Number(n).toLocaleString(locale())

export type Job = {
  id: number; code: string; name: string; active: boolean; familyId: number | null; familyName: string | null
  purpose: string | null; duties: string | null; education: string | null; experience: string | null; skills: string | null
  conditions: string | null; minGradeId: number | null; minGrade: string | null; maxGradeId: number | null; maxGrade: string | null
  category: string | null; reportsToId: number | null; reportsTo: string | null; unskilled: boolean; version: number
  updatedBy: string | null; updatedAt: string | null; holders: number
}
type Family = { id: number; code: string; name: string; active: boolean; jobs: number }
type Version = { id: number; version: number; name: string; savedBy: string | null; savedAt: string }
type Path = { id: number; fromId: number; fromName: string; toId: number; toName: string; minYears: number; minScore: number | null; requirement: string | null }
type Grade = { id: number; code: string; name: string; categoryName: string; baseSalary: number; currency: string; active: boolean }
type Unit = { id: number; name: string; open: boolean; levelName: string }
type Line = { budgetId: number | null; occupationId: number; occupationName: string; budgeted: number; filled: number; vacant: number; over: number; budgetCost: number | null; note: string | null; vacantSince: string | null }
type UnitLine = { unitId: number; unitName: string; direction: string | null; budgeted: number; filled: number; vacant: number; over: number; budgetCost: number; realCost: number; jobs: Line[] }
type Headcount = { year: number; countedOn: string; months: number; budgeted: number; filled: number; vacant: number; over: number; budgetCost: number; realCost: number; units: UnitLine[] }
type Ready = { userId: number; name: string; registrationNo: string; unit: string | null; occupation: string; years: number; score: number | null; toId: number; toName: string; requirement: string | null; freePosts: number }
type Tab = 'jobs' | 'ladders' | 'positions' | 'ready'

const errorText = (e: unknown) => (e instanceof ApiError ? [e.message, ...Object.values(e.fieldErrors)].join(' ') : t('Something went wrong.'))
const gradeRange = (j: Job) => j.minGrade && j.maxGrade && j.minGrade !== j.maxGrade ? t('{a} to {b}', { a: j.minGrade.split(' · ')[0], b: j.maxGrade.split(' · ')[0] })
  : (j.minGrade ?? j.maxGrade)?.split(' · ')[0] ?? '—'

/** Job descriptions, career ladders, approved positions per unit and who is ready for promotion. */
export default function PositionsPage() {
  const [tab, setTab] = useState<Tab>('jobs')
  const [jobs, setJobs] = useState<Job[]>([])
  const [error, setError] = useState<string | null>(null)
  const loadJobs = () => api<Job[]>('/api/job-catalogue').then(setJobs).catch((e: Error) => setError(e.message))
  useEffect(() => { void loadJobs() }, [])
  const tabs: Record<Tab, string> = { jobs: t('Job descriptions'), ladders: t('Career ladders'), positions: t('Approved positions'), ready: t('Ready for promotion') }
  return (
    <section>
      <PageTitle title={t('Jobs and positions')} />
      <div className="segmented" role="tablist">
        {(Object.keys(tabs) as Tab[]).map((k) => (
          <button key={k} type="button" role="tab" aria-selected={tab === k} className={tab === k ? 'on' : ''} onClick={() => setTab(k)}>{tabs[k]}</button>
        ))}
      </div>
      <ErrorText error={error} />
      {tab === 'jobs' && <JobsTab jobs={jobs} reload={loadJobs} />}
      {tab === 'ladders' && <LaddersTab jobs={jobs} />}
      {tab === 'positions' && <PositionsTab jobs={jobs} />}
      {tab === 'ready' && <ReadyTab />}
    </section>
  )
}

// ---- job descriptions ------------------------------------------------------------------------------------------------

function JobsTab({ jobs, reload }: { jobs: Job[]; reload: () => void }) {
  const [families, setFamilies] = useState<Family[]>([])
  const [editing, setEditing] = useState<Job | 'new' | null>(null)
  const [family, setFamily] = useState<Family | 'new' | null>(null)
  const [filter, setFilter] = useState('')
  const [error, setError] = useState<string | null>(null)
  const ask = useAsk()
  const loadFamilies = () => api<Family[]>('/api/job-catalogue/families').then(setFamilies).catch((e: Error) => setError(e.message))
  useEffect(() => { void loadFamilies() }, [])
  const shown = jobs.filter((j) => !filter || String(j.familyId) === filter)
  const removeFamily = (f: Family) => ask({ title: t('Delete this job family?'), message: t('Its jobs stay, with no family.'), confirmLabel: t('Delete'), danger: true,
    onConfirm: async () => { await api(`/api/job-catalogue/families/${f.id}`, { method: 'DELETE' }); void loadFamilies(); reload() } })
  return (
    <>
      <p className="muted">{t('Each job has its description (fiche de poste), the grades it is paid in and so its legal category, and whether it is unskilled (a trial of one month at most). Each save keeps a version: a contract keeps the text it was signed with.')}</p>
      <ErrorText error={error} />
      <div className="card">
        <div className="row space">
          <h3>{t('Jobs')}</h3>
          <div className="row">
            <select className="auto-width" value={filter} onChange={(e) => setFilter(e.target.value)} aria-label={t('Job family')}>
              <option value="">{t('All families')}</option>
              {families.map((f) => <option key={f.id} value={f.id}>{f.name}</option>)}
            </select>
            <Button onClick={() => setEditing('new')}>{t('Add a job')}</Button>
          </div>
        </div>
        <div className="table-wrap">
          <table>
            <thead><tr><th>{t('Job title')}</th><th>{t('Job family')}</th><th>{t('Grades')}</th><th>{t('Category')}</th><th>{t('Holders')}</th><th>{t('Description')}</th><th /></tr></thead>
            <tbody>
              {shown.map((j) => (
                <tr key={j.id} className={j.active ? '' : 'muted'}>
                  <td>{j.name} <span className="small muted">{j.code}</span>{j.reportsTo && <div className="small muted">{t('Reports to {a}', { a: j.reportsTo })}</div>}</td>
                  <td>{j.familyName ?? '—'}</td>
                  <td>{gradeRange(j)}{j.unskilled && <div><span className="badge">{t('Unskilled')}</span></div>}</td>
                  <td className="small">{j.category ?? '—'}</td>
                  <td>{j.holders}</td>
                  <td>{j.version > 0 ? <span className="badge ok">{t('Version {a}', { a: j.version })}</span> : <span className="badge warn">{t('To write')}</span>}
                    {j.updatedAt && <div className="small muted">{longDay(j.updatedAt)}</div>}</td>
                  <td className="actions">
                    <Button className="secondary small" onClick={() => setEditing(j)}>{t('Edit')}</Button>
                    {j.version > 0 && <button type="button" className="link small" onClick={() => void openFile(`/api/job-catalogue/${j.id}/pdf`)}>{t('Job description (PDF)')}</button>}
                  </td>
                </tr>
              ))}
              {shown.length === 0 && <tr><td colSpan={7} className="muted">{t('No job yet.')}</td></tr>}
            </tbody>
          </table>
        </div>
      </div>
      <div className="card">
        <div className="row space">
          <h3>{t('Job families')}</h3>
          <Button className="secondary" onClick={() => setFamily('new')}>{t('Add a family')}</Button>
        </div>
        {families.length === 0 ? <p className="muted">{t('No job family yet: families group jobs for the ladders and reports, for example Finance or Logistics.')}</p> : (
          <table>
            <thead><tr><th>{t('Code')}</th><th>{t('Name')}</th><th>{t('Jobs')}</th><th /></tr></thead>
            <tbody>
              {families.map((f) => (
                <tr key={f.id} className={f.active ? '' : 'muted'}>
                  <td>{f.code}</td><td>{f.name}</td><td>{f.jobs}</td>
                  <td className="actions"><button type="button" className="link small" onClick={() => setFamily(f)}>{t('Edit')}</button>
                    <button type="button" className="link small" onClick={() => removeFamily(f)}>{t('Delete')}</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
      {editing && <JobModal job={editing === 'new' ? null : editing} jobs={jobs} families={families} onClose={() => setEditing(null)}
        onDone={() => { setEditing(null); reload(); void loadFamilies() }} />}
      {family && <FamilyModal family={family === 'new' ? null : family} onClose={() => setFamily(null)} onDone={() => { setFamily(null); void loadFamilies(); reload() }} />}
    </>
  )
}

function FamilyModal({ family, onClose, onDone }: { family: Family | null; onClose: () => void; onDone: () => void }) {
  const [code, setCode] = useState(family?.code ?? '')
  const [name, setName] = useState(family?.name ?? '')
  const [active, setActive] = useState(family?.active ?? true)
  const [error, setError] = useState<string | null>(null)
  const submit = async () => {
    setError(null)
    try {
      await api(family ? `/api/job-catalogue/families/${family.id}` : '/api/job-catalogue/families', { method: family ? 'PUT' : 'POST', body: { code, name, active } })
      onDone()
    } catch (e) { setError(errorText(e)) }
  }
  return (
    <Modal title={family ? t('Edit the job family') : t('Add a family')} onClose={onClose}
      footer={<><Button className="secondary" type="button" onClick={onClose}>{t('Cancel')}</Button><Button type="submit" form="job-family">{t('Save')}</Button></>}>
      <Form id="job-family" onSubmit={submit}>
        <div className="grid2">
          <label>{t('Code')}<input required maxLength={32} pattern="[A-Za-z0-9_\-]+" value={code} onChange={(e) => setCode(e.target.value)} /></label>
          <label>{t('Name')}<input required maxLength={150} value={name} onChange={(e) => setName(e.target.value)} /></label>
        </div>
        <label className="check"><input type="checkbox" checked={active} onChange={(e) => setActive(e.target.checked)} /> {t('Active')}</label>
        <ErrorText error={error} />
      </Form>
    </Modal>
  )
}

function JobModal({ job, jobs, families, onClose, onDone }: { job: Job | null; jobs: Job[]; families: Family[]; onClose: () => void; onDone: () => void }) {
  const [f, setF] = useState({
    code: job?.code ?? '', name: job?.name ?? '', active: job?.active ?? true, familyId: job?.familyId ? String(job.familyId) : '',
    purpose: job?.purpose ?? '', duties: job?.duties ?? '', education: job?.education ?? '', experience: job?.experience ?? '',
    skills: job?.skills ?? '', conditions: job?.conditions ?? '', minGradeId: job?.minGradeId ? String(job.minGradeId) : '',
    maxGradeId: job?.maxGradeId ? String(job.maxGradeId) : '', reportsToId: job?.reportsToId ? String(job.reportsToId) : '', unskilled: job?.unskilled ?? false,
  })
  const [grades, setGrades] = useState<Grade[]>([])
  const [versions, setVersions] = useState<Version[]>([])
  const [error, setError] = useState<string | null>(null)
  useEffect(() => {
    api<Grade[]>('/api/grades').then((g) => setGrades(g.filter((x) => x.active).sort((a, b) => a.baseSalary - b.baseSalary))).catch(() => undefined)
    if (job) api<Version[]>(`/api/job-catalogue/${job.id}/versions`).then(setVersions).catch(() => undefined)
  }, [job])
  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>) =>
    setF({ ...f, [k]: e.target.type === 'checkbox' ? (e.target as HTMLInputElement).checked : e.target.value })
  const num = (v: string) => (v ? Number(v) : null)
  const submit = async () => {
    setError(null)
    try {
      await api(job ? `/api/job-catalogue/${job.id}` : '/api/job-catalogue', { method: job ? 'PUT' : 'POST', body: {
        ...f, familyId: num(f.familyId), minGradeId: num(f.minGradeId), maxGradeId: num(f.maxGradeId), reportsToId: num(f.reportsToId) } })
      onDone()
    } catch (e) { setError(errorText(e)) }
  }
  const gradeOptions = grades.map((g) => <option key={g.id} value={g.id}>{g.code} · {g.name} ({g.categoryName})</option>)
  return (
    <Modal title={job ? t('Edit the job') : t('Add a job')} onClose={onClose} size="lg"
      footer={<><Button className="secondary" type="button" onClick={onClose}>{t('Cancel')}</Button><Button type="submit" form="job-form">{t('Save')}</Button></>}>
      <Form id="job-form" onSubmit={submit}>
        <div className="grid2">
          <label>{t('Code')}<input required maxLength={32} pattern="[A-Za-z0-9_\-]+" value={f.code} onChange={set('code')} /></label>
          <label>{t('Job title')}<input required maxLength={150} value={f.name} onChange={set('name')} /></label>
          <label>{t('Job family')}
            <select value={f.familyId} onChange={set('familyId')}>
              <option value="">{t('None')}</option>
              {families.filter((x) => x.active || String(x.id) === f.familyId).map((x) => <option key={x.id} value={x.id}>{x.name}</option>)}
            </select>
          </label>
          <label>{t('Reports to')}
            <select value={f.reportsToId} onChange={set('reportsToId')}>
              <option value="">{t('None')}</option>
              {jobs.filter((x) => x.id !== job?.id).map((x) => <option key={x.id} value={x.id}>{x.name}</option>)}
            </select>
          </label>
          <label>{t('Lowest grade')}<select value={f.minGradeId} onChange={set('minGradeId')}><option value="">{t('Not set')}</option>{gradeOptions}</select></label>
          <label>{t('Highest grade')}<select value={f.maxGradeId} onChange={set('maxGradeId')}><option value="">{t('Not set')}</option>{gradeOptions}</select></label>
        </div>
        <label className="check"><input type="checkbox" checked={f.unskilled} onChange={set('unskilled')} /> {t('Unskilled job (manœuvre): the trial is one month at most (Labour Code art. 43)')}</label>
        <label className="check"><input type="checkbox" checked={f.active} onChange={set('active')} /> {t('Active')}</label>
        <label>{t('Purpose of the job')}<textarea rows={2} maxLength={1000} value={f.purpose} onChange={set('purpose')} placeholder={t('Why the job exists, in one or two sentences.')} /></label>
        <label>{t('Main duties')}<textarea rows={5} maxLength={4000} value={f.duties} onChange={set('duties')} placeholder={t('One duty per line.')} /></label>
        <div className="grid2">
          <label>{t('Education')}<input maxLength={300} value={f.education} onChange={set('education')} /></label>
          <label>{t('Experience')}<input maxLength={300} value={f.experience} onChange={set('experience')} /></label>
        </div>
        <label>{t('Skills')}<textarea rows={3} maxLength={1000} value={f.skills} onChange={set('skills')} placeholder={t('One skill per line.')} /></label>
        <label>{t('Working conditions')}<textarea rows={2} maxLength={1000} value={f.conditions} onChange={set('conditions')} placeholder={t('For example: travel, night work, protective equipment.')} /></label>
        {versions.length > 0 && (
          <div>
            <h4>{t('Versions')}</h4>
            <ul className="plain small">
              {versions.map((v) => (
                <li key={v.id}>{t('Version {a}', { a: v.version })} · {longDay(v.savedAt)}{v.savedBy && v.savedBy !== 'system' ? ` · ${v.savedBy}` : ''}
                  {' '}<button type="button" className="link small" onClick={() => void openFile(`/api/job-catalogue/${job!.id}/pdf?version=${v.version}`)}>PDF</button></li>
              ))}
            </ul>
          </div>
        )}
        <ErrorText error={error} />
      </Form>
    </Modal>
  )
}

// ---- career ladders ----------------------------------------------------------------------------------------------

function LaddersTab({ jobs }: { jobs: Job[] }) {
  const [paths, setPaths] = useState<Path[]>([])
  const [editing, setEditing] = useState<Path | 'new' | null>(null)
  const [error, setError] = useState<string | null>(null)
  const ask = useAsk()
  const load = () => api<Path[]>('/api/job-catalogue/paths').then(setPaths).catch((e: Error) => setError(e.message))
  useEffect(() => { void load() }, [])
  const remove = (p: Path) => ask({ title: t('Delete this step?'), message: `${p.fromName} → ${p.toName}`, confirmLabel: t('Delete'), danger: true,
    onConfirm: async () => { await api(`/api/job-catalogue/paths/${p.id}`, { method: 'DELETE' }); void load() } })
  return (
    <>
      <p className="muted">{t('For each job, the jobs a person can move up to and what it takes: years in the job and the average of the approved evaluations of the last two years. A training or diploma is checked by hand. The person\'s page shows the next steps; the promotion itself goes through Career moves, with its letter.')}</p>
      <ErrorText error={error} />
      <div className="card">
        <div className="row space">
          <h3>{t('Career ladders')}</h3>
          <Button onClick={() => setEditing('new')}>{t('Add a step')}</Button>
        </div>
        {paths.length === 0 ? <p className="muted">{t('No step yet.')}</p> : (
          <table>
            <thead><tr><th>{t('From')}</th><th>{t('To')}</th><th>{t('Years in the job')}</th><th>{t('Evaluation average')}</th><th>{t('Also needed')}</th><th /></tr></thead>
            <tbody>
              {paths.map((p) => (
                <tr key={p.id}>
                  <td>{p.fromName}</td><td>→ {p.toName}</td><td>{num(p.minYears)}</td><td>{p.minScore == null ? '—' : `${num(p.minScore)} %`}</td>
                  <td className="small">{p.requirement ?? '—'}</td>
                  <td className="actions"><button type="button" className="link small" onClick={() => setEditing(p)}>{t('Edit')}</button>
                    <button type="button" className="link small" onClick={() => remove(p)}>{t('Delete')}</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
      {editing && <PathModal path={editing === 'new' ? null : editing} jobs={jobs} onClose={() => setEditing(null)} onDone={() => { setEditing(null); void load() }} />}
    </>
  )
}

function PathModal({ path, jobs, onClose, onDone }: { path: Path | null; jobs: Job[]; onClose: () => void; onDone: () => void }) {
  const [fromId, setFromId] = useState(path ? String(path.fromId) : '')
  const [toId, setToId] = useState(path ? String(path.toId) : '')
  const [minYears, setMinYears] = useState(path ? String(path.minYears) : '2')
  const [minScore, setMinScore] = useState(path?.minScore != null ? String(path.minScore) : '70')
  const [requirement, setRequirement] = useState(path?.requirement ?? '')
  const [error, setError] = useState<string | null>(null)
  const active = jobs.filter((j) => j.active)
  const submit = async () => {
    setError(null)
    try {
      await api(path ? `/api/job-catalogue/paths/${path.id}` : '/api/job-catalogue/paths', { method: path ? 'PUT' : 'POST', body: {
        fromId: Number(fromId), toId: Number(toId), minYears: Number(minYears || 0), minScore: minScore === '' ? null : Number(minScore), requirement } })
      onDone()
    } catch (e) { setError(errorText(e)) }
  }
  return (
    <Modal title={path ? t('Edit the step') : t('Add a step')} onClose={onClose}
      footer={<><Button className="secondary" type="button" onClick={onClose}>{t('Cancel')}</Button><Button type="submit" form="career-path">{t('Save')}</Button></>}>
      <Form id="career-path" onSubmit={submit}>
        <div className="grid2">
          <label>{t('From the job')}
            <select required value={fromId} onChange={(e) => setFromId(e.target.value)}>
              <option value="">{t('Choose…')}</option>{active.map((j) => <option key={j.id} value={j.id}>{j.name}</option>)}
            </select>
          </label>
          <label>{t('To the job')}
            <select required value={toId} onChange={(e) => setToId(e.target.value)}>
              <option value="">{t('Choose…')}</option>{active.filter((j) => String(j.id) !== fromId).map((j) => <option key={j.id} value={j.id}>{j.name}</option>)}
            </select>
          </label>
          <label>{t('Years in the job, at least')}<input type="number" min={0} max={50} step={0.5} value={minYears} onChange={(e) => setMinYears(e.target.value)} /></label>
          <label>{t('Evaluation average, at least (%)')}<input type="number" min={0} max={100} step={1} value={minScore} onChange={(e) => setMinScore(e.target.value)} placeholder={t('None')} /></label>
        </div>
        <label>{t('Also needed (optional)')}<input maxLength={300} value={requirement} onChange={(e) => setRequirement(e.target.value)} placeholder={t('For example: management training, a master\'s degree')} /></label>
        <ErrorText error={error} />
      </Form>
    </Modal>
  )
}

// ---- approved positions ----------------------------------------------------------------------------------------

function PositionsTab({ jobs }: { jobs: Job[] }) {
  const thisYear = new Date().getFullYear()
  const [year, setYear] = useState(thisYear)
  const [data, setData] = useState<Headcount | null>(null)
  const [units, setUnits] = useState<Unit[]>([])
  const [editing, setEditing] = useState<{ unitId?: number; line?: Line } | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [info, setInfo] = useState<string | null>(null)
  const ask = useAsk()
  const load = () => api<Headcount>(`/api/positions?year=${year}`).then(setData).catch((e: Error) => setError(e.message))
  useEffect(() => { void load() }, [year]) // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { api<Unit[]>('/api/org/units').then((u) => setUnits(u.filter((x) => x.open))).catch(() => undefined) }, [])
  const copy = async (fromFilled: boolean) => {
    setError(null)
    try {
      const r = await api<{ lines: number }>('/api/positions/copy', { method: 'POST', body: { fromYear: year - 1, toYear: year, fromFilled } })
      setInfo(t('{a} lines added.', { a: r.lines })); void load()
    } catch (e) { setError(errorText(e)) }
  }
  const remove = (l: Line, unit: string) => ask({ title: t('Remove these posts?'), message: `${unit} · ${l.occupationName}`, confirmLabel: t('Remove'), danger: true,
    onConfirm: async () => { await api(`/api/positions/${l.budgetId}`, { method: 'DELETE' }); void load() } })
  const usd = (v: number | null) => (v == null ? '—' : money(v, 'USD'))
  const planned = (data?.budgeted ?? 0) > 0
  return (
    <>
      <p className="muted">{t('The posts each unit may fill in the year, by job, against the people in place: those assigned to the unit whose contract has that job. Hiring or moving someone into a unit with no free post gives a warning, and HR can go on.')}</p>
      <ErrorText error={error} />
      {info && <p className="success">{info}</p>}
      <div className="row space">
        <select className="auto-width" value={year} onChange={(e) => setYear(Number(e.target.value))} aria-label={t('Year')}>
          {[thisYear - 1, thisYear, thisYear + 1].map((y) => <option key={y} value={y}>{y}</option>)}
        </select>
        <Button onClick={() => setEditing({})}>{t('Add posts')}</Button>
      </div>
      {data && (
        <>
          <div className="tiles">
            <div className="tile"><span>{t('Approved posts')}</span><strong>{data.budgeted}</strong><small>{t('Counted on {a}', { a: longDay(data.countedOn) })}</small></div>
            <div className="tile"><span>{t('Filled')}</span><strong>{data.filled}</strong><small>{t('{a} over the approved posts', { a: data.over })}</small></div>
            <div className={`tile${data.vacant > 0 ? ' warn' : ''}`}><span>{t('Vacant')}</span><strong>{data.vacant}</strong><small>{t('Posts to fill')}</small></div>
            <div className="tile"><span>{t('Budget for the year')}</span><strong>{usd(data.budgetCost)}</strong>
              <small>{t('Paid so far: {a} ({b} months)', { a: usd(data.realCost), b: data.months })}</small></div>
          </div>
          {!planned && (
            <div className="card">
              <h3>{t('No approved posts for {a} yet', { a: year })}</h3>
              <p className="small">{t('Start from last year\'s posts, or from the people in place today, then adjust.')}</p>
              <div className="row">
                <Button className="secondary" onClick={() => void copy(false)}>{t('Copy {a}', { a: year - 1 })}</Button>
                <Button className="secondary" onClick={() => void copy(true)}>{t('Start from the people in place')}</Button>
              </div>
            </div>
          )}
          <div className="card">
            <h3>{t('Headcount by unit')}</h3>
            <div className="table-wrap">
              <table>
                <thead><tr><th>{t('Unit and job')}</th><th>{t('Posts approved')}</th><th>{t('Filled')}</th><th>{t('Vacant')}</th><th>{t('Over')}</th><th>{t('Budget')}</th><th>{t('Paid so far')}</th><th /></tr></thead>
                <tbody>
                  {data.units.map((u) => [
                    <tr key={`u${u.unitId}`} className="group">
                      <td><strong>{u.unitName}</strong>{u.direction && u.direction !== u.unitName && <span className="small muted"> · {u.direction}</span>}</td>
                      <td><strong>{u.budgeted}</strong></td><td><strong>{u.filled}</strong></td><td><strong>{u.vacant || ''}</strong></td><td><strong>{u.over || ''}</strong></td>
                      <td>{u.budgetCost ? usd(u.budgetCost) : ''}</td><td>{u.realCost ? usd(u.realCost) : ''}</td>
                      <td className="actions"><button type="button" className="link small" onClick={() => setEditing({ unitId: u.unitId })}>{t('Add posts')}</button></td>
                    </tr>,
                    ...u.jobs.map((l) => (
                      <tr key={`${u.unitId}-${l.occupationId}`}>
                        <td style={{ paddingLeft: 24 }}>{l.occupationName}{l.note && <div className="small muted">{l.note}</div>}</td>
                        <td>{l.budgeted}</td><td>{l.filled}</td>
                        <td>{l.vacant > 0 ? <span className="badge warn">{l.vacant}</span> : ''}{l.vacant > 0 && <div className="small muted">{l.vacantSince ? t('Since {a}', { a: longDay(l.vacantSince) }) : t('Never filled')}</div>}</td>
                        <td>{l.over > 0 ? <span className="badge off">{l.over}</span> : ''}</td>
                        <td>{l.budgetCost != null ? usd(l.budgetCost) : ''}</td><td />
                        <td className="actions">
                          <button type="button" className="link small" onClick={() => setEditing({ unitId: u.unitId, line: l })}>{l.budgetId ? t('Edit') : t('Approve')}</button>
                          {l.budgetId && <button type="button" className="link small" onClick={() => remove(l, u.unitName)}>{t('Remove')}</button>}
                        </td>
                      </tr>
                    )),
                  ])}
                  {data.units.length === 0 && <tr><td colSpan={8} className="muted">{t('Nobody is assigned to a unit with a job yet.')}</td></tr>}
                </tbody>
              </table>
            </div>
          </div>
        </>
      )}
      {editing && <BudgetModal year={year} units={units} jobs={jobs} unitId={editing.unitId} line={editing.line}
        onClose={() => setEditing(null)} onDone={() => { setEditing(null); void load() }} />}
    </>
  )
}

function BudgetModal({ year, units, jobs, unitId, line, onClose, onDone }: {
  year: number; units: Unit[]; jobs: Job[]; unitId?: number; line?: Line; onClose: () => void; onDone: () => void
}) {
  const [unit, setUnit] = useState(unitId ? String(unitId) : '')
  const [job, setJob] = useState(line ? String(line.occupationId) : '')
  const [posts, setPosts] = useState(line ? String(line.budgetId ? line.budgeted : Math.max(line.filled, 1)) : '1')
  const [cost, setCost] = useState(line?.budgetCost != null ? String(line.budgetCost) : '')
  const [note, setNote] = useState(line?.note ?? '')
  const [error, setError] = useState<string | null>(null)
  const submit = async () => {
    setError(null)
    try {
      await api(line?.budgetId ? `/api/positions/${line.budgetId}` : '/api/positions', { method: line?.budgetId ? 'PUT' : 'POST', body: {
        year, unitId: Number(unit), occupationId: Number(job), posts: Number(posts), budgetCost: cost === '' ? null : Number(cost), note } })
      onDone()
    } catch (e) { setError(errorText(e)) }
  }
  return (
    <Modal title={line?.budgetId ? t('Edit the posts') : t('Add posts')} onClose={onClose}
      footer={<><Button className="secondary" type="button" onClick={onClose}>{t('Cancel')}</Button><Button type="submit" form="position-budget">{t('Save')}</Button></>}>
      <Form id="position-budget" onSubmit={submit}>
        <p className="small muted">{t('Year {a}', { a: year })}</p>
        <label>{t('Unit')}
          <select required value={unit} onChange={(e) => setUnit(e.target.value)} disabled={!!line}>
            <option value="">{t('Choose…')}</option>{units.map((u) => <option key={u.id} value={u.id}>{u.name} ({u.levelName})</option>)}
          </select>
        </label>
        <label>{t('Job title')}
          <select required value={job} onChange={(e) => setJob(e.target.value)} disabled={!!line}>
            <option value="">{t('Choose…')}</option>{jobs.filter((j) => j.active || String(j.id) === job).map((j) => <option key={j.id} value={j.id}>{j.name}</option>)}
          </select>
        </label>
        <div className="grid2">
          <label>{t('Approved posts')}<input type="number" required min={0} max={10000} value={posts} onChange={(e) => setPosts(e.target.value)} /></label>
          <label>{t('Yearly budget, USD (optional)')}<input type="number" min={0} step={0.01} value={cost} onChange={(e) => setCost(e.target.value)} /></label>
        </div>
        <label>{t('Note (optional)')}<input maxLength={300} value={note} onChange={(e) => setNote(e.target.value)} /></label>
        <ErrorText error={error} />
      </Form>
    </Modal>
  )
}

// ---- ready for promotion -----------------------------------------------------------------------------------------

function ReadyTab() {
  const [rows, setRows] = useState<Ready[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => { api<Ready[]>('/api/job-catalogue/ready').then(setRows).catch((e: Error) => setError(e.message)) }, [])
  return (
    <>
      <p className="muted">{t('The people who meet every condition of a next job on the ladder: years in the job and evaluation average. Check what is also needed, then promote from the person\'s page (Career moves).')}</p>
      <ErrorText error={error} />
      <div className="card">
        {rows && rows.length === 0 && <p className="muted">{t('Nobody meets the conditions of a next job for now, or no ladder is set.')}</p>}
        {rows && rows.length > 0 && (
          <div className="table-wrap">
            <table>
              <thead><tr><th>{t('Person')}</th><th>{t('Job today')}</th><th>{t('Years in the job')}</th><th>{t('Evaluation average')}</th><th>{t('Next job')}</th><th>{t('Free posts')}</th></tr></thead>
              <tbody>
                {rows.map((r) => (
                  <tr key={`${r.userId}-${r.toId}`}>
                    <td><Link to={`/users/${r.userId}/file`}>{r.name}</Link> <span className="small muted">{r.registrationNo}</span>{r.unit && <div className="small muted">{r.unit}</div>}</td>
                    <td>{r.occupation}</td><td>{num(r.years)}</td><td>{r.score == null ? '—' : `${num(r.score)} %`}</td>
                    <td>→ {r.toName}{r.requirement && <div className="small muted">{t('Also needed: {a}', { a: r.requirement })}</div>}</td>
                    <td>{r.freePosts > 0 ? <span className="badge ok">{r.freePosts}</span> : '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </>
  )
}
