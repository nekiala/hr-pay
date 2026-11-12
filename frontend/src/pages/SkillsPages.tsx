import { useEffect, useState, type FormEvent } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { ApiError, api, day, openFile } from '../api'
import { useAuth } from '../auth'
import { Button, Form } from '../components/Button'
import { ErrorText } from '../components/ErrorText'
import { Modal } from '../components/Modal'
import { PageTitle } from '../components/SettingsLink'
import { t } from '../i18n'

// ---- types ---------------------------------------------------------------------------------------------------------

type Family = 'TECHNICAL' | 'BEHAVIOURAL' | 'LANGUAGE' | 'TOOL'
export type Skill = {
  id: number; family: Family; name: string; description: string | null; level1: string | null; level2: string | null
  level3: string | null; level4: string | null; active: boolean; jobs: number; people: number; courses: number
}
type JobSkill = { skillId: number; name: string; family: Family; level: number }
type Job = { id: number; name: string; familyName: string | null; freeText: string | null; skills: number; holders: number }
type JobView = { id: number; name: string; freeText: string | null; skills: JobSkill[] }
type PersonSkill = {
  skillId: number; name: string; family: Family; required: number | null; selfLevel: number | null; selfNote: string | null
  selfAt: string | null; level: number | null; confirmedByName: string | null; confirmedNote: string | null; confirmedAt: string | null
  status: 'SELF' | 'CONFIRMED' | null; gap: number | null; level1: string | null; level2: string | null; level3: string | null; level4: string | null
}
type Certification = {
  documentId: number; userId: number; name: string; fileName: string; issuedOn: string | null; expiresOn: string | null
  note: string | null; state: 'VALID' | 'EXPIRING' | 'EXPIRED'
}
type Profile = {
  userId: number; name: string; occupationId: number | null; occupationName: string | null; unitName: string | null; self: boolean
  canConfirm: boolean; skills: PersonSkill[]; certifications: Certification[]; gaps: number; waiting: number
}
type Review = { userId: number; name: string; unitName: string | null; occupationName: string | null; waiting: number; since: string }
type Cell = { skillId: number; required: number | null; level: number | null; selfLevel: number | null }
type Row = { userId: number; name: string; registrationNo: string; unitName: string | null; occupationName: string | null; cells: Cell[]; gaps: number }
type Matrix = { skills: JobSkill[]; rows: Row[]; gaps: number; people: number }
type Holder = { userId: number; name: string; registrationNo: string; unitName: string | null; occupationName: string | null; level: number; confirmedAt: string | null }
type Unit = { id: number; name: string; open: boolean }

export const FAMILIES: Record<Family, string> = { TECHNICAL: t('Technical'), BEHAVIOURAL: t('Behavioural'), LANGUAGE: t('Languages'), TOOL: t('Tools') }
export const LEVELS: Record<number, string> = { 1: t('Notions'), 2: t('Practitioner'), 3: t('Confirmed'), 4: t('Expert') }
const CERT_STATE: Record<Certification['state'], [string, string]> = { VALID: [t('Valid'), 'ok'], EXPIRING: [t('Expires soon'), 'warn'], EXPIRED: [t('Expired'), 'off'] }

const errorText = (e: unknown) => (e instanceof ApiError ? [e.message, ...Object.values(e.fieldErrors)].join(' ') : t('Something went wrong.'))
const levelText = (n: number | null) => (n == null ? '—' : `${n} · ${LEVELS[n]}`)
const levelHelp = (s: { level1: string | null; level2: string | null; level3: string | null; level4: string | null }) =>
  [s.level1, s.level2, s.level3, s.level4].map((d, i) => (d ? `${i + 1} · ${LEVELS[i + 1]}: ${d}` : null)).filter(Boolean).join('\n')

function LevelSelect({ value, onChange, label, blank = '—' }: { value: number | null; onChange: (n: number | null) => void; label: string; blank?: string }) {
  return (
    <select className="auto-width" value={value ?? ''} aria-label={label} onChange={(e) => onChange(e.target.value ? Number(e.target.value) : null)}>
      <option value="">{blank}</option>
      {[1, 2, 3, 4].map((n) => <option key={n} value={n}>{levelText(n)}</option>)}
    </select>
  )
}

/** A level against what the job needs: green when it is reached, amber one level short, red more. */
function GapBadge({ level, required }: { level: number | null; required: number | null }) {
  if (required == null) return level == null ? null : <span className="badge">{level}</span>
  const gap = required - (level ?? 0)
  const cls = gap <= 0 ? 'ok' : gap === 1 ? 'warn' : 'off'
  return <span className={`badge ${cls}`} title={t('Level {a}, the job needs {b}', { a: level ?? 0, b: required })}>{level ?? '–'}/{required}</span>
}

// ---- the HR and manager page ---------------------------------------------------------------------------------------

type Tab = 'matrix' | 'reviews' | 'catalogue' | 'jobs' | 'search' | 'certifications'

/** Skills: the gap matrix, levels to confirm, the catalogue, the skills each job needs, the search and certifications. */
export default function SkillsPage() {
  const { can } = useAuth()
  const hr = can('skills.manage')
  const [params, setParams] = useSearchParams()
  const tabs: Partial<Record<Tab, string>> = hr
    ? { matrix: t('Gap matrix'), reviews: t('To confirm'), catalogue: t('Catalogue'), jobs: t('Skills per job'), search: t('Search'), certifications: t('Licences and certifications') }
    : { matrix: t('Gap matrix'), reviews: t('To confirm') }
  const wanted = params.get('tab') as Tab | null
  const tab: Tab = wanted && tabs[wanted] ? wanted : 'matrix'
  const [skills, setSkills] = useState<Skill[]>([])
  const loadSkills = () => api<Skill[]>(`/api/skills?all=${hr}`).then(setSkills).catch(() => undefined)
  useEffect(() => { void loadSkills() }, []) // eslint-disable-line react-hooks/exhaustive-deps
  return (
    <section>
      <PageTitle title={t('Skills')} />
      <div className="segmented" role="tablist">
        {(Object.keys(tabs) as Tab[]).map((k) => (
          <button key={k} type="button" role="tab" aria-selected={tab === k} className={tab === k ? 'on' : ''}
            onClick={() => setParams({ tab: k }, { replace: true })}>{tabs[k]}</button>
        ))}
      </div>
      {tab === 'matrix' && <MatrixTab hr={hr} />}
      {tab === 'reviews' && <ReviewsTab />}
      {tab === 'catalogue' && <CatalogueTab skills={skills} reload={loadSkills} />}
      {tab === 'jobs' && <JobsTab skills={skills} />}
      {tab === 'search' && <SearchTab skills={skills} />}
      {tab === 'certifications' && <CertificationsTab />}
    </section>
  )
}

function MatrixTab({ hr }: { hr: boolean }) {
  const { can } = useAuth()
  const [units, setUnits] = useState<Unit[]>([])
  const [unitId, setUnitId] = useState<number | null>(null)
  const [matrix, setMatrix] = useState<Matrix | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [done, setDone] = useState<string | null>(null)
  useEffect(() => { if (hr) api<Unit[]>('/api/org/units').then((u) => setUnits(u.filter((x) => x.open))).catch(() => undefined) }, [hr])
  useEffect(() => {
    setMatrix(null); setError(null)
    api<Matrix>(`/api/skills/matrix${unitId ? `?unitId=${unitId}` : ''}`).then(setMatrix).catch((e: Error) => setError(e.message))
  }, [unitId])
  const gaps = matrix ? matrix.rows.flatMap((r) => r.cells.filter((c) => c.required != null && (c.level ?? 0) < c.required).map((c) => ({ userId: r.userId, skillId: c.skillId }))) : []
  const toNeeds = async () => {
    setError(null); setDone(null)
    try {
      const r = await api<{ added: number }>('/api/skills/gaps/needs', { method: 'POST', body: gaps })
      setDone(t('{a} training needs added. Plan them under Training, Needs and requests.', { a: r.added }))
    } catch (e) { setError(errorText(e)) }
  }
  return (
    <>
      <p className="muted">{t('Each person against the skills their job needs. Green: reached; amber: one level short; red: more. A level the employee gave but nobody confirmed yet counts as missing.')}</p>
      <div className="row space">
        <div className="row">
          {hr && (
            <select className="auto-width" value={unitId ?? ''} aria-label={t('Unit')} onChange={(e) => setUnitId(e.target.value ? Number(e.target.value) : null)}>
              <option value="">{t('All units')}</option>
              {units.map((u) => <option key={u.id} value={u.id}>{u.name}</option>)}
            </select>
          )}
          {matrix && <span className="muted small">{t('{a} people · {b} gaps', { a: matrix.people, b: matrix.gaps })}</span>}
        </div>
        {(can('skills.manage') || can('training.manage')) && gaps.length > 0 && <Button onClick={() => void toNeeds()}>{t('Turn the gaps into training needs')}</Button>}
      </div>
      <ErrorText error={error} />
      {done && <p className="notice small">{done}</p>}
      {matrix && (
        <div className="card">
          {matrix.skills.length === 0 ? <p className="muted">{t('No job here lists the skills it needs yet. Fill them in under Skills per job.')}</p> : (
            <div className="table-wrap">
              <table className="matrix">
                <thead>
                  <tr>
                    <th>{t('Person')}</th><th>{t('Occupation')}</th>
                    {matrix.skills.map((s) => <th key={s.skillId} title={FAMILIES[s.family]} className="small">{s.name}</th>)}
                    <th>{t('Gaps')}</th>
                  </tr>
                </thead>
                <tbody>
                  {matrix.rows.filter((r) => r.cells.some((c) => c.required != null)).map((r) => (
                    <tr key={r.userId}>
                      <td><Link to={`/users/${r.userId}`}>{r.name}</Link><div className="small muted">{r.unitName}</div></td>
                      <td className="small">{r.occupationName ?? '—'}</td>
                      {r.cells.map((c) => <td key={c.skillId}><GapBadge level={c.level} required={c.required} />{c.selfLevel != null && c.selfLevel !== c.level && <div className="small muted">{t('says {a}', { a: c.selfLevel })}</div>}</td>)}
                      <td>{r.gaps > 0 ? <strong>{r.gaps}</strong> : <span className="muted">0</span>}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {matrix.rows.some((r) => !r.cells.some((c) => c.required != null)) && (
                <p className="small muted">{t('{a} more people are not shown: their job lists no skill yet, or they have no job on their contract.', { a: matrix.rows.filter((r) => !r.cells.some((c) => c.required != null)).length })}</p>
              )}
            </div>
          )}
        </div>
      )}
    </>
  )
}

function ReviewsTab() {
  const [rows, setRows] = useState<Review[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [open, setOpen] = useState<Profile | null>(null)
  const load = () => api<Review[]>('/api/skills/reviews').then(setRows).catch((e: Error) => setError(e.message))
  useEffect(() => { void load() }, [])
  const review = (userId: number) => api<Profile>(`/api/skills/people/${userId}`).then(setOpen).catch((e: Error) => setError(e.message))
  return (
    <>
      <p className="muted">{t('People who rated their skills. Confirm the level you see in their work, or correct it; the confirmed level is the one that counts.')}</p>
      <ErrorText error={error} />
      <div className="card">
        <div className="table-wrap">
          <table>
            <thead><tr><th>{t('Person')}</th><th>{t('Occupation')}</th><th>{t('Skills to confirm')}</th><th>{t('Since')}</th><th /></tr></thead>
            <tbody>
              {rows?.length === 0 && <tr><td colSpan={5} className="muted">{t('Nothing to confirm.')}</td></tr>}
              {rows?.map((r) => (
                <tr key={r.userId}>
                  <td><Link to={`/users/${r.userId}`}>{r.name}</Link><div className="small muted">{r.unitName}</div></td>
                  <td>{r.occupationName ?? '—'}</td><td>{r.waiting}</td><td>{day(r.since.slice(0, 10))}</td>
                  <td><Button className="small" onClick={() => void review(r.userId)}>{t('Review')}</Button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
      {open && <ConfirmModal profile={open} onClose={() => setOpen(null)} onSaved={() => { setOpen(null); void load() }} />}
    </>
  )
}

/** The manager or HR confirms or corrects each level. */
function ConfirmModal({ profile, onClose, onSaved }: { profile: Profile; onClose: () => void; onSaved: (p: Profile) => void }) {
  const [levels, setLevels] = useState<Record<number, { level: number | null; note: string }>>(
    () => Object.fromEntries(profile.skills.map((s) => [s.skillId, { level: s.status === 'SELF' ? s.selfLevel : s.level, note: s.confirmedNote ?? '' }])),
  )
  const [error, setError] = useState<string | null>(null)
  const save = async () => {
    const body = profile.skills.filter((s) => levels[s.skillId]?.level != null)
      .filter((s) => s.status === 'SELF' || levels[s.skillId].level !== s.level || (levels[s.skillId].note || null) !== s.confirmedNote)
      .map((s) => ({ skillId: s.skillId, level: levels[s.skillId].level, note: levels[s.skillId].note || null }))
    try { onSaved(await api<Profile>(`/api/skills/people/${profile.userId}`, { method: 'PUT', body })) } catch (e) { setError(errorText(e)) }
  }
  return (
    <Modal title={t('Skills of {a}', { a: profile.name })} size="lg" onClose={onClose}>
      <p className="muted small">{profile.occupationName ? t('Job: {a}', { a: profile.occupationName }) : t('No job on the contract.')}</p>
      <div className="table-wrap">
        <table>
          <thead><tr><th>{t('Skill')}</th><th>{t('Job needs')}</th><th>{t('The person says')}</th><th>{t('Confirmed level')}</th><th>{t('Comment')}</th></tr></thead>
          <tbody>
            {profile.skills.map((s) => (
              <tr key={s.skillId}>
                <td title={levelHelp(s)}><strong>{s.name}</strong><div className="small muted">{FAMILIES[s.family]}</div></td>
                <td>{levelText(s.required)}</td>
                <td>{levelText(s.selfLevel)}{s.selfNote && <div className="small muted">{s.selfNote}</div>}{s.status === 'SELF' && <div><span className="badge warn">{t('To confirm')}</span></div>}</td>
                <td><LevelSelect value={levels[s.skillId]?.level ?? null} label={t('Confirmed level')} onChange={(n) => setLevels({ ...levels, [s.skillId]: { ...levels[s.skillId], level: n } })} /></td>
                <td><input maxLength={500} value={levels[s.skillId]?.note ?? ''} aria-label={t('Comment')} onChange={(e) => setLevels({ ...levels, [s.skillId]: { ...levels[s.skillId], note: e.target.value } })} /></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <ErrorText error={error} />
      <div className="modal-foot"><Button className="secondary" onClick={onClose}>{t('Cancel')}</Button><Button onClick={() => void save()}>{t('Confirm the levels')}</Button></div>
    </Modal>
  )
}

function CatalogueTab({ skills, reload }: { skills: Skill[]; reload: () => void }) {
  const [editing, setEditing] = useState<Skill | 'new' | null>(null)
  return (
    <>
      <div className="row space">
        <p className="muted">{t('Each skill has four levels: 1 notions, 2 practitioner, 3 confirmed, 4 expert. Describe each level in a line so that everyone rates the same way.')}</p>
        <Button onClick={() => setEditing('new')}>{t('Add a skill')}</Button>
      </div>
      <div className="card">
        <div className="table-wrap">
          <table>
            <thead><tr><th>{t('Skill')}</th><th>{t('Family')}</th><th>{t('Jobs')}</th><th>{t('People')}</th><th>{t('Courses')}</th><th /></tr></thead>
            <tbody>
              {skills.length === 0 && <tr><td colSpan={6} className="muted">{t('No skill yet.')}</td></tr>}
              {skills.map((s) => (
                <tr key={s.id} className={s.active ? undefined : 'muted'}>
                  <td title={levelHelp(s)}><strong>{s.name}</strong>{!s.active && <span className="badge off" style={{ marginLeft: 6 }}>{t('Not used')}</span>}{s.description && <div className="small muted">{s.description}</div>}</td>
                  <td>{FAMILIES[s.family]}</td><td>{s.jobs}</td><td>{s.people}</td><td>{s.courses}</td>
                  <td><Button className="secondary small" onClick={() => setEditing(s)}>{t('Edit')}</Button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
      {editing && <SkillModal skill={editing === 'new' ? null : editing} onClose={() => setEditing(null)} onSaved={() => { setEditing(null); reload() }} />}
    </>
  )
}

function SkillModal({ skill, onClose, onSaved }: { skill: Skill | null; onClose: () => void; onSaved: () => void }) {
  const [form, setForm] = useState({
    family: skill?.family ?? 'TECHNICAL', name: skill?.name ?? '', description: skill?.description ?? '', level1: skill?.level1 ?? '',
    level2: skill?.level2 ?? '', level3: skill?.level3 ?? '', level4: skill?.level4 ?? '', active: skill?.active ?? true,
  })
  const [error, setError] = useState<string | null>(null)
  const save = async (e: FormEvent) => {
    e.preventDefault()
    const body = { ...form, description: form.description || null, level1: form.level1 || null, level2: form.level2 || null, level3: form.level3 || null, level4: form.level4 || null }
    try { await api(skill ? `/api/skills/${skill.id}` : '/api/skills', { method: skill ? 'PUT' : 'POST', body }); onSaved() } catch (err) { setError(errorText(err)) }
  }
  return (
    <Modal title={skill ? t('Skill') : t('Add a skill')} size="lg" onClose={onClose}>
      <Form onSubmit={save}>
        <div className="grid2">
          <label>{t('Name')}<input maxLength={150} value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} required /></label>
          <label>{t('Family')}<select value={form.family} onChange={(e) => setForm({ ...form, family: e.target.value as Family })}>
            {(Object.keys(FAMILIES) as Family[]).map((k) => <option key={k} value={k}>{FAMILIES[k]}</option>)}</select></label>
        </div>
        <label>{t('Description')}<input maxLength={1000} value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} /></label>
        {([1, 2, 3, 4] as const).map((n) => {
          const key = `level${n}` as 'level1' | 'level2' | 'level3' | 'level4'
          return <label key={n}>{levelText(n)}<input maxLength={300} value={form[key]} onChange={(e) => setForm({ ...form, [key]: e.target.value })} /></label>
        })}
        <label className="check"><input type="checkbox" checked={form.active} onChange={(e) => setForm({ ...form, active: e.target.checked })} />{t('In use')}</label>
        <ErrorText error={error} />
        <div className="modal-foot"><Button className="secondary" onClick={onClose}>{t('Cancel')}</Button><Button type="submit">{t('Save')}</Button></div>
      </Form>
    </Modal>
  )
}

function JobsTab({ skills }: { skills: Skill[] }) {
  const [jobs, setJobs] = useState<Job[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [open, setOpen] = useState<JobView | null>(null)
  const load = () => api<Job[]>('/api/skills/jobs').then(setJobs).catch((e: Error) => setError(e.message))
  useEffect(() => { void load() }, [])
  const edit = (id: number) => api<JobView>(`/api/skills/jobs/${id}`).then(setOpen).catch((e: Error) => setError(e.message))
  return (
    <>
      <p className="muted">{t('The skills each job needs and the level. The matrix compares each person with the job on their contract.')}</p>
      <ErrorText error={error} />
      <div className="card">
        <div className="table-wrap">
          <table>
            <thead><tr><th>{t('Occupation')}</th><th>{t('Family')}</th><th>{t('Skills needed')}</th><th>{t('People')}</th><th /></tr></thead>
            <tbody>
              {jobs?.length === 0 && <tr><td colSpan={5} className="muted">{t('No job yet.')}</td></tr>}
              {jobs?.map((j) => (
                <tr key={j.id}>
                  <td><strong>{j.name}</strong></td><td>{j.familyName ?? '—'}</td>
                  <td>{j.skills > 0 ? j.skills : <span className="badge warn">{t('None yet')}</span>}</td><td>{j.holders}</td>
                  <td><Button className="secondary small" onClick={() => void edit(j.id)}>{t('Edit')}</Button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
      {open && <JobModal job={open} skills={skills.filter((s) => s.active)} onClose={() => setOpen(null)} onSaved={() => { setOpen(null); void load() }} />}
    </>
  )
}

function JobModal({ job, skills, onClose, onSaved }: { job: JobView; skills: Skill[]; onClose: () => void; onSaved: () => void }) {
  const [rows, setRows] = useState<{ skillId: number | null; level: number | null }[]>(job.skills.map((s) => ({ skillId: s.skillId, level: s.level })))
  const [error, setError] = useState<string | null>(null)
  const save = async () => {
    const body = rows.filter((r) => r.skillId != null).map((r) => ({ skillId: r.skillId, level: r.level ?? 2 }))
    try { await api(`/api/skills/jobs/${job.id}`, { method: 'PUT', body }); onSaved() } catch (e) { setError(errorText(e)) }
  }
  return (
    <Modal title={t('Skills for {a}', { a: job.name })} size="lg" onClose={onClose}>
      {job.freeText && <div className="notice small"><strong>{t('Written in the job description:')}</strong><div style={{ whiteSpace: 'pre-line' }}>{job.freeText}</div></div>}
      <div className="table-wrap">
        <table>
          <thead><tr><th>{t('Skill')}</th><th>{t('Level needed')}</th><th /></tr></thead>
          <tbody>
            {rows.length === 0 && <tr><td colSpan={3} className="muted">{t('No skill yet.')}</td></tr>}
            {rows.map((r, i) => (
              <tr key={i}>
                <td>
                  <select value={r.skillId ?? ''} aria-label={t('Skill')} onChange={(e) => setRows(rows.map((x, j) => (j === i ? { ...x, skillId: e.target.value ? Number(e.target.value) : null } : x)))}>
                    <option value="">{t('Choose…')}</option>
                    {(Object.keys(FAMILIES) as Family[]).map((f) => (
                      <optgroup key={f} label={FAMILIES[f]}>
                        {skills.filter((s) => s.family === f).map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
                      </optgroup>
                    ))}
                  </select>
                </td>
                <td><LevelSelect value={r.level} label={t('Level needed')} onChange={(n) => setRows(rows.map((x, j) => (j === i ? { ...x, level: n } : x)))} /></td>
                <td><Button className="secondary small" onClick={() => setRows(rows.filter((_, j) => j !== i))}>{t('Remove')}</Button></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <Button className="secondary" onClick={() => setRows([...rows, { skillId: null, level: 2 }])}>{t('Add a skill')}</Button>
      <ErrorText error={error} />
      <div className="modal-foot"><Button className="secondary" onClick={onClose}>{t('Cancel')}</Button><Button onClick={() => void save()}>{t('Save')}</Button></div>
    </Modal>
  )
}

function SearchTab({ skills }: { skills: Skill[] }) {
  const [skillId, setSkillId] = useState<number | null>(null)
  const [min, setMin] = useState<number | null>(3)
  const [rows, setRows] = useState<Holder[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => {
    if (!skillId) { setRows(null); return }
    api<Holder[]>(`/api/skills/search?skillId=${skillId}&minLevel=${min ?? 1}`).then(setRows).catch((e: Error) => setError(e.message))
  }, [skillId, min])
  return (
    <>
      <p className="muted">{t('Who has a skill at a level or more, from confirmed levels only. Useful to find a trainer, a successor or an internal candidate.')}</p>
      <div className="row">
        <select className="auto-width" value={skillId ?? ''} aria-label={t('Skill')} onChange={(e) => setSkillId(e.target.value ? Number(e.target.value) : null)}>
          <option value="">{t('Choose a skill…')}</option>
          {skills.filter((s) => s.active).map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
        </select>
        <LevelSelect value={min} label={t('At least')} blank={t('Any level')} onChange={setMin} />
      </div>
      <ErrorText error={error} />
      {rows && (
        <div className="card">
          <div className="table-wrap">
            <table>
              <thead><tr><th>{t('Person')}</th><th>{t('Occupation')}</th><th>{t('Unit')}</th><th>{t('Level')}</th><th>{t('Confirmed on')}</th></tr></thead>
              <tbody>
                {rows.length === 0 && <tr><td colSpan={5} className="muted">{t('Nobody at this level yet.')}</td></tr>}
                {rows.map((h) => (
                  <tr key={h.userId}>
                    <td><Link to={`/users/${h.userId}`}>{h.name}</Link><div className="small muted">{h.registrationNo}</div></td>
                    <td>{h.occupationName ?? '—'}</td><td>{h.unitName ?? '—'}</td><td>{levelText(h.level)}</td>
                    <td>{h.confirmedAt ? day(h.confirmedAt.slice(0, 10)) : '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </>
  )
}

function CertificationTable({ rows, withName }: { rows: Certification[]; withName: boolean }) {
  return (
    <div className="table-wrap">
      <table>
        <thead><tr>{withName && <th>{t('Person')}</th>}<th>{t('Document')}</th><th>{t('Issued on')}</th><th>{t('Expires on')}</th><th>{t('Status')}</th><th /></tr></thead>
        <tbody>
          {rows.length === 0 && <tr><td colSpan={withName ? 6 : 5} className="muted">{t('No licence or certification yet.')}</td></tr>}
          {rows.map((c) => (
            <tr key={c.documentId}>
              {withName && <td><Link to={`/users/${c.userId}`}>{c.name}</Link></td>}
              <td>{c.note ?? c.fileName}{c.note && <div className="small muted">{c.fileName}</div>}</td>
              <td>{c.issuedOn ? day(c.issuedOn) : '—'}</td><td>{c.expiresOn ? day(c.expiresOn) : '—'}</td>
              <td><span className={`badge ${CERT_STATE[c.state][1]}`}>{CERT_STATE[c.state][0]}</span></td>
              <td><Button className="link" onClick={() => void openFile(`/api/lifecycle/documents/${c.documentId}/file`)}>{t('Open')}</Button></td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function CertificationsTab() {
  const [rows, setRows] = useState<Certification[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => { api<Certification[]>('/api/skills/certifications').then(setRows).catch((e: Error) => setError(e.message)) }, [])
  return (
    <>
      <p className="muted">{t('Driving licences, safety and professional certificates. Add them in the person\'s documents with the type Licences and certifications and an expiry date: HR is reminded before they expire.')}</p>
      <ErrorText error={error} />
      {rows && <div className="card"><CertificationTable rows={rows} withName /></div>}
    </>
  )
}

// ---- the employee's own page ---------------------------------------------------------------------------------------

/** My skills: what my job needs, my own rating, and what my manager confirmed. */
export function MySkillsPage() {
  const [profile, setProfile] = useState<Profile | null>(null)
  const [catalogue, setCatalogue] = useState<Skill[]>([])
  const [ratings, setRatings] = useState<Record<number, { level: number | null; note: string }>>({})
  const [extra, setExtra] = useState<number | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)
  const show = (p: Profile) => {
    setProfile(p)
    setRatings(Object.fromEntries(p.skills.map((s) => [s.skillId, { level: s.selfLevel, note: s.selfNote ?? '' }])))
  }
  useEffect(() => {
    api<Profile>('/api/skills/me').then(show).catch((e: Error) => setError(e.message))
    api<Skill[]>('/api/skills').then(setCatalogue).catch(() => undefined)
  }, [])
  if (!profile) return <section><ErrorText error={error} /></section>
  const add = () => {
    const s = catalogue.find((x) => x.id === extra)
    if (!s) return
    setProfile({ ...profile, skills: [...profile.skills, { skillId: s.id, name: s.name, family: s.family, required: null, selfLevel: null, selfNote: null, selfAt: null, level: null, confirmedByName: null, confirmedNote: null, confirmedAt: null, status: null, gap: null, level1: s.level1, level2: s.level2, level3: s.level3, level4: s.level4 }] })
    setExtra(null)
  }
  const save = async () => {
    setError(null); setSaved(false)
    const body = profile.skills.filter((s) => ratings[s.skillId]?.level != null)
      .filter((s) => ratings[s.skillId].level !== s.selfLevel || (ratings[s.skillId].note || null) !== s.selfNote)
      .map((s) => ({ skillId: s.skillId, level: ratings[s.skillId].level, note: ratings[s.skillId].note || null }))
    try { show(await api<Profile>('/api/skills/me', { method: 'PUT', body })); setSaved(true) } catch (e) { setError(errorText(e)) }
  }
  const shown = new Set(profile.skills.map((s) => s.skillId))
  return (
    <section>
      <PageTitle title={t('My skills')}><Button onClick={() => void save()}>{t('Send my ratings')}</Button></PageTitle>
      <p className="muted">{t('Rate yourself on each skill your job needs, and add others you have. Your manager confirms or corrects; the confirmed level counts. Hover a skill to read what each level means.')}</p>
      <ErrorText error={error} />
      {saved && <p className="notice small">{t('Sent. Your manager is asked to confirm.')}</p>}
      <div className="tiles">
        <div className="tile"><span>{t('My job')}</span><strong className="small">{profile.occupationName ?? '—'}</strong></div>
        <div className="tile"><span>{t('Skills to grow')}</span><strong>{profile.gaps}</strong><small>{t('below what the job needs')}</small></div>
        <div className="tile"><span>{t('Waiting for my manager')}</span><strong>{profile.waiting}</strong></div>
      </div>
      <div className="card">
        <div className="table-wrap">
          <table>
            <thead><tr><th>{t('Skill')}</th><th>{t('Job needs')}</th><th>{t('My rating')}</th><th>{t('Example or comment')}</th><th>{t('Confirmed level')}</th></tr></thead>
            <tbody>
              {profile.skills.length === 0 && <tr><td colSpan={5} className="muted">{t('Your job lists no skill yet. Add the skills you have below.')}</td></tr>}
              {profile.skills.map((s) => (
                <tr key={s.skillId}>
                  <td title={levelHelp(s)}><strong>{s.name}</strong><div className="small muted">{FAMILIES[s.family]}</div></td>
                  <td>{levelText(s.required)}</td>
                  <td><LevelSelect value={ratings[s.skillId]?.level ?? null} label={t('My rating')} onChange={(n) => setRatings({ ...ratings, [s.skillId]: { note: ratings[s.skillId]?.note ?? '', level: n } })} /></td>
                  <td><input maxLength={500} value={ratings[s.skillId]?.note ?? ''} aria-label={t('Example or comment')} onChange={(e) => setRatings({ ...ratings, [s.skillId]: { level: ratings[s.skillId]?.level ?? null, note: e.target.value } })} /></td>
                  <td>
                    <GapBadge level={s.level} required={s.required} /> {s.level != null && LEVELS[s.level]}
                    {s.status === 'SELF' && <div><span className="badge warn">{t('To confirm')}</span></div>}
                    {s.confirmedNote && <div className="small muted">{s.confirmedNote}</div>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div className="row">
          <select className="auto-width" value={extra ?? ''} aria-label={t('Another skill')} onChange={(e) => setExtra(e.target.value ? Number(e.target.value) : null)}>
            <option value="">{t('Another skill…')}</option>
            {catalogue.filter((s) => !shown.has(s.id)).map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
          </select>
          <Button className="secondary" disabled={!extra} onClick={add}>{t('Add')}</Button>
        </div>
      </div>
      <div className="card">
        <h3>{t('My licences and certifications')}</h3>
        <CertificationTable rows={profile.certifications} withName={false} />
        <p className="small muted">{t('Add a new one in My documents.')} <Link to="/my/documents">{t('My documents')}</Link></p>
      </div>
    </section>
  )
}

/** A person's skills on their record (HR and their manager), with the confirmation. */
export function SkillsCard({ userId }: { userId: string }) {
  const [profile, setProfile] = useState<Profile | null>(null)
  const [open, setOpen] = useState(false)
  useEffect(() => { api<Profile>(`/api/skills/people/${userId}`).then(setProfile).catch(() => setProfile(null)) }, [userId])
  if (!profile || profile.self) return null
  return (
    <div className="card">
      <div className="row space">
        <h3>{t('Skills')}</h3>
        <div className="row">
          {profile.gaps > 0 && <span className="badge warn">{t('{a} gaps', { a: profile.gaps })}</span>}
          {profile.canConfirm && profile.skills.length > 0 && <Button className={profile.waiting > 0 ? 'small' : 'secondary small'} onClick={() => setOpen(true)}>{profile.waiting > 0 ? t('Confirm {a} levels', { a: profile.waiting }) : t('Review the levels')}</Button>}
        </div>
      </div>
      {profile.skills.length === 0 ? <p className="muted">{t('No skill recorded yet.')}</p> : (
        <div className="table-wrap">
          <table>
            <thead><tr><th>{t('Skill')}</th><th>{t('Job needs')}</th><th>{t('The person says')}</th><th>{t('Confirmed level')}</th></tr></thead>
            <tbody>
              {profile.skills.map((s) => (
                <tr key={s.skillId}>
                  <td title={levelHelp(s)}>{s.name}</td><td>{levelText(s.required)}</td><td>{levelText(s.selfLevel)}</td>
                  <td><GapBadge level={s.level} required={s.required} />{s.confirmedByName && <div className="small muted">{s.confirmedByName}</div>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {profile.certifications.length > 0 && <><h4>{t('Licences and certifications')}</h4><CertificationTable rows={profile.certifications} withName={false} /></>}
      {open && <ConfirmModal profile={profile} onClose={() => setOpen(false)} onSaved={(p) => { setProfile(p); setOpen(false) }} />}
    </div>
  )
}
