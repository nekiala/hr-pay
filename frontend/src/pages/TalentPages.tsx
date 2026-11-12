import { useEffect, useState, type FormEvent } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { ApiError, api, day, type GradeCategory, type Occupation } from '../api'
import { Button, Form } from '../components/Button'
import { DateInput } from '../components/DateFields'
import { useAsk } from '../components/Dialogs'
import { ErrorText } from '../components/ErrorText'
import { Modal } from '../components/Modal'
import { PageTitle, SettingsLink } from '../components/SettingsLink'
import type { MyPerson } from '../components/UnitFilter'
import { t } from '../i18n'
import type { Course } from './TrainingPages'

// ---- types ---------------------------------------------------------------------------------------------------------

type Readiness = 'NOW' | 'ONE_TWO' | 'THREE_PLUS'
type Risk = 'LOW' | 'MEDIUM' | 'HIGH'
type Mobility = 'NOW' | 'LATER' | 'NO'
type ActionKind = 'COURSE' | 'MISSION' | 'MENTOR' | 'ACTING' | 'OTHER'
type Person = { userId: number; name: string }
type Action = {
  id: number; successorId: number; kind: ActionKind; title: string; courseId: number | null; courseTitle: string | null
  mentorUserId: number | null; mentorName: string | null; dueOn: string | null; doneOn: string | null; needId: number | null
}
type Successor = { id: number; postId: number; userId: number; name: string; occupationName: string | null; readiness: Readiness; note: string | null; box: number | null; actions: Action[] }
type Post = {
  id: number; unitId: number; unitName: string; head: boolean; occupationId: number | null; occupationName: string | null; title: string | null
  reason: string | null; holders: Person[]; retiresOn: string | null; flightRisk: Risk | null; successors: Successor[]; warnings: string[]
}
type Candidate = { userId: number; name: string; occupationName: string | null; unitName: string | null; onLadder: boolean; eligible: boolean; note: string | null; skillMatch: number | null; box: number | null; successorElsewhere: boolean }
type Review = {
  id: number; year: number; name: string; categoryId: number | null; categoryName: string | null; unitId: number | null; unitName: string | null
  status: 'OPEN' | 'CLOSED'; note: string | null; openedBy: string; openedAt: string; members: number; proposed: number; settled: number
}
type Member = {
  id: number; reviewId: number; userId: number; name: string; occupationName: string | null; unitName: string | null; managerName: string | null
  score: number | null; performanceAuto: number | null; performance: number | null; performanceReason: string | null; potentialProposed: number | null
  potentialReason: string | null; proposedByName: string | null; potential: number | null; flightRisk: Risk | null; mobility: Mobility | null
  note: string | null; settledBy: string | null; box: number | null
}
type ReviewView = { review: Review; members: Member[]; grid: Record<string, number> }
type Proposal = { memberId: number; reviewName: string; year: number; userId: number; name: string; occupationName: string | null; potentialProposed: number | null; potentialReason: string | null }
type Coverage = { posts: number; covered: number; withoutReady: number; holderAtRisk: number; holderRetiring: number; vacant: number; atRisk: Post[] }
type PersonView = { userId: number; box: number | null; performance: number | null; potential: number | null; flightRisk: Risk | null; mobility: Mobility | null; reviewName: string | null; successorOf: string[]; holds: string[] }
type Unit = { id: number; name: string; open: boolean }

const READINESS: Record<Readiness, string> = { NOW: t('Ready now'), ONE_TWO: t('Ready in 1-2 years'), THREE_PLUS: t('Ready in 3 years or more') }
const RISKS: Record<Risk, string> = { LOW: t('Low'), MEDIUM: t('Medium'), HIGH: t('High') }
const MOBILITY: Record<Mobility, string> = { NOW: t('Can move now'), LATER: t('Can move later'), NO: t('Does not want to move') }
const ACTIONS: Record<ActionKind, string> = { COURSE: t('Course'), MISSION: t('Mission or project'), MENTOR: t('Mentor'), ACTING: t('Acting in the post'), OTHER: t('Other') }
const RATING: Record<number, string> = { 1: t('Low'), 2: t('Medium'), 3: t('High') }
/** The 9 boxes, by (potential - 1) × 3 + performance. */
export const BOXES: Record<number, string> = {
  1: t('To watch'), 2: t('Effective'), 3: t('Trusted professional'),
  4: t('Dilemma'), 5: t('Core player'), 6: t('High performer'),
  7: t('Enigma'), 8: t('Rising talent'), 9: t('Future leader'),
}
const boxClass = (b: number | null) => (b == null ? '' : b >= 8 || b === 6 ? 'ok' : b === 1 || b === 4 || b === 7 ? 'warn' : '')
const BoxBadge = ({ box }: { box: number | null }) => (box == null ? <span className="muted">—</span> : <span className={`badge ${boxClass(box)}`}>{BOXES[box]}</span>)
const errorText = (e: unknown) => (e instanceof ApiError ? [e.message, ...Object.values(e.fieldErrors)].join(' ') : t('Something went wrong.'))
const label = (p: Post) => p.title ?? (p.head ? t('Head of {a}', { a: p.unitName }) : `${p.occupationName} · ${p.unitName}`)
const thisYear = new Date().getFullYear()

function useAccess() {
  const [access, setAccess] = useState<boolean | null>(null)
  useEffect(() => { api<{ talent: boolean }>('/api/talent/access').then((a) => setAccess(a.talent)).catch(() => setAccess(false)) }, [])
  return access
}

function RatingSelect({ value, onChange, label: aria }: { value: number | null; onChange: (n: number | null) => void; label: string }) {
  return (
    <select className="auto-width" value={value ?? ''} aria-label={aria} onChange={(e) => onChange(e.target.value ? Number(e.target.value) : null)}>
      <option value="">—</option>
      {[1, 2, 3].map((n) => <option key={n} value={n}>{n} · {RATING[n]}</option>)}
    </select>
  )
}

// ---- the page ------------------------------------------------------------------------------------------------------

type Tab = 'posts' | 'review' | 'coverage'

/** Talent and succession: critical posts and successors, the talent review, and the coverage report. Confidential. */
export default function TalentPage() {
  const access = useAccess()
  const [params, setParams] = useSearchParams()
  const tabs: Record<Tab, string> = { posts: t('Critical posts'), review: t('Talent review'), coverage: t('Coverage') }
  const wanted = params.get('tab') as Tab | null
  const tab: Tab = wanted && tabs[wanted] ? wanted : 'posts'
  if (access === null) return null
  if (!access) return <section><PageTitle title={t('Talent and succession')} /><p className="muted">{t('Only HR and the Direction générale see the talent review and succession.')}</p></section>
  return (
    <section>
      <PageTitle title={t('Talent and succession')}><SettingsLink to="/talent/settings" /></PageTitle>
      <p className="notice small">{t('Confidential: only HR and the Direction générale see this page, and every visit is recorded. Employees never see their box, their potential or whether they are a successor.')}</p>
      <div className="segmented" role="tablist">
        {(Object.keys(tabs) as Tab[]).map((k) => (
          <button key={k} type="button" role="tab" aria-selected={tab === k} className={tab === k ? 'on' : ''}
            onClick={() => setParams({ tab: k }, { replace: true })}>{tabs[k]}</button>
        ))}
      </div>
      {tab === 'posts' && <PostsTab />}
      {tab === 'review' && <ReviewTab />}
      {tab === 'coverage' && <CoverageTab />}
    </section>
  )
}

// ---- critical posts and successors ---------------------------------------------------------------------------------

function PostsTab() {
  const ask = useAsk()
  const [posts, setPosts] = useState<Post[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [editing, setEditing] = useState<Post | 'new' | null>(null)
  const [picking, setPicking] = useState<Post | null>(null)
  const [acting, setActing] = useState<Successor | null>(null)
  const [people, setPeople] = useState<MyPerson[]>([])
  const [courses, setCourses] = useState<Course[]>([])
  const load = () => api<Post[]>('/api/talent/posts').then(setPosts).catch((e: Error) => setError(e.message))
  useEffect(() => {
    void load()
    api<MyPerson[]>('/api/org/my-people').then((p) => setPeople([...p].sort((a, b) => a.name.localeCompare(b.name)))).catch(() => undefined)
    api<Course[]>('/api/training/courses').then(setCourses).catch(() => undefined)
  }, [])
  const replace = (p: Post) => setPosts((all) => (all ?? []).map((x) => (x.id === p.id ? p : x)))
  const run = async (f: () => Promise<unknown>) => { setError(null); try { await f() } catch (e) { setError(errorText(e)) } }
  const suggest = () => run(async () => { await api('/api/talent/posts/suggest', { method: 'POST' }); await load() })
  return (
    <>
      <div className="row space">
        <p className="muted">{t('The posts that are hard to replace, who holds them, and up to three successors each, with how soon they could take over.')}</p>
        <div className="row">
          <Button className="secondary" onClick={() => void suggest()}>{t('Suggest the top posts')}</Button>
          <Button onClick={() => setEditing('new')}>{t('Add a post')}</Button>
        </div>
      </div>
      <ErrorText error={error} />
      {posts?.length === 0 && <div className="card"><p className="muted">{t('No critical post yet. "Suggest the top posts" adds the head of the Direction générale and of each direction.')}</p></div>}
      {posts?.map((p) => (
        <div key={p.id} className="card">
          <div className="row space">
            <div>
              <h3>{label(p)}</h3>
              <div className="small muted">{p.unitName}{p.reason && ` · ${p.reason}`}</div>
            </div>
            <div className="row">
              <Button className="secondary small" onClick={() => setEditing(p)}>{t('Edit')}</Button>
              <Button className="secondary small" onClick={() => ask({ title: t('Remove this post from the list?'), confirmLabel: t('Remove'), danger: true, onConfirm: () => run(async () => { await api(`/api/talent/posts/${p.id}`, { method: 'DELETE' }); await load() }) })}>{t('Remove')}</Button>
            </div>
          </div>
          <p>
            <strong>{t('Held by:')}</strong> {p.holders.length === 0 ? <span className="badge off">{t('Vacant post')}</span> : p.holders.map((h, i) => <span key={h.userId}>{i > 0 && ', '}<Link to={`/users/${h.userId}`}>{h.name}</Link></span>)}
            {p.flightRisk && <> · {t('Flight risk: {a}', { a: RISKS[p.flightRisk] })}</>}
            {p.retiresOn && <> · {t('Retirement age on {a}', { a: day(p.retiresOn) })}</>}
          </p>
          {p.warnings.length > 0 && <div className="notice small">{p.warnings.map((w) => <div key={w}>{w}</div>)}</div>}
          <div className="table-wrap">
            <table>
              <thead><tr><th>{t('Successor')}</th><th>{t('Readiness')}</th><th>{t('Last review')}</th><th>{t('Development')}</th><th /></tr></thead>
              <tbody>
                {p.successors.length === 0 && <tr><td colSpan={5} className="muted">{t('No successor yet.')}</td></tr>}
                {p.successors.map((s) => (
                  <tr key={s.id}>
                    <td><Link to={`/users/${s.userId}`}>{s.name}</Link><div className="small muted">{s.occupationName}</div>{s.note && <div className="small muted">{s.note}</div>}</td>
                    <td>
                      <select className="auto-width" value={s.readiness} aria-label={t('Readiness')}
                        onChange={(e) => void run(async () => replace(await api<Post>(`/api/talent/successors/${s.id}`, { method: 'PUT', body: { readiness: e.target.value, note: s.note } })))}>
                        {(Object.keys(READINESS) as Readiness[]).map((k) => <option key={k} value={k}>{READINESS[k]}</option>)}
                      </select>
                    </td>
                    <td><BoxBadge box={s.box} /></td>
                    <td>
                      {s.actions.map((a) => (
                        <label key={a.id} className="check small">
                          <input type="checkbox" checked={!!a.doneOn} onChange={(e) => void run(async () => replace(await api<Post>(`/api/talent/actions/${a.id}/done`, { method: 'POST', body: { done: e.target.checked } })))} />
                          <span>{t('{a}: {b}', { a: ACTIONS[a.kind], b: a.title })}{a.mentorName && ` (${a.mentorName})`}{a.dueOn && ` · ${day(a.dueOn)}`}{a.needId && <span className="muted"> · {t('in the training needs')}</span>}</span>
                        </label>
                      ))}
                      <Button className="link small" onClick={() => setActing(s)}>{t('Add an action')}</Button>
                    </td>
                    <td><Button className="secondary small" onClick={() => ask({ title: t('Remove {a} from the successors?', { a: s.name }), confirmLabel: t('Remove'), danger: true, onConfirm: () => run(async () => replace(await api<Post>(`/api/talent/successors/${s.id}`, { method: 'DELETE' }))) })}>{t('Remove')}</Button></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {p.successors.length < 3 && <Button className="secondary" onClick={() => setPicking(p)}>{t('Add a successor')}</Button>}
        </div>
      ))}
      {editing && <PostModal post={editing === 'new' ? null : editing} onClose={() => setEditing(null)} onSaved={() => { setEditing(null); void load() }} />}
      {picking && <SuccessorModal post={picking} people={people} onClose={() => setPicking(null)} onSaved={(p) => { setPicking(null); replace(p) }} />}
      {acting && <ActionModal successor={acting} people={people} courses={courses} onClose={() => setActing(null)} onSaved={(p) => { setActing(null); replace(p) }} />}
    </>
  )
}

function PostModal({ post, onClose, onSaved }: { post: Post | null; onClose: () => void; onSaved: () => void }) {
  const [units, setUnits] = useState<Unit[]>([])
  const [jobs, setJobs] = useState<Occupation[]>([])
  const [form, setForm] = useState({ unitId: post?.unitId ?? null as number | null, head: post?.head ?? true, occupationId: post?.occupationId ?? null as number | null, title: post?.title ?? '', reason: post?.reason ?? '' })
  const [error, setError] = useState<string | null>(null)
  useEffect(() => {
    api<Unit[]>('/api/org/units').then((u) => setUnits(u.filter((x) => x.open))).catch(() => undefined)
    api<Occupation[]>('/api/occupations').then((o) => setJobs(o.filter((x) => x.active))).catch(() => undefined)
  }, [])
  const save = async (e: FormEvent) => {
    e.preventDefault()
    const body = { ...form, occupationId: form.head ? null : form.occupationId, title: form.title || null, reason: form.reason || null }
    try { await api(post ? `/api/talent/posts/${post.id}` : '/api/talent/posts', { method: post ? 'PUT' : 'POST', body }); onSaved() } catch (err) { setError(errorText(err)) }
  }
  return (
    <Modal title={post ? t('Critical post') : t('Add a post')} onClose={onClose}>
      <Form onSubmit={save}>
        <label>{t('Unit')}<select value={form.unitId ?? ''} required onChange={(e) => setForm({ ...form, unitId: e.target.value ? Number(e.target.value) : null })}>
          <option value="">{t('Choose…')}</option>{units.map((u) => <option key={u.id} value={u.id}>{u.name}</option>)}</select></label>
        <div className="row">
          <label className="check"><input type="radio" name="kind" checked={form.head} onChange={() => setForm({ ...form, head: true })} />{t('The head of the unit')}</label>
          <label className="check"><input type="radio" name="kind" checked={!form.head} onChange={() => setForm({ ...form, head: false })} />{t('A job in the unit')}</label>
        </div>
        {!form.head && <label>{t('Occupation')}<select value={form.occupationId ?? ''} required onChange={(e) => setForm({ ...form, occupationId: e.target.value ? Number(e.target.value) : null })}>
          <option value="">{t('Choose…')}</option>{jobs.map((j) => <option key={j.id} value={j.id}>{j.name}</option>)}</select></label>}
        <label>{t('Name of the post (optional)')}<input maxLength={200} value={form.title} onChange={(e) => setForm({ ...form, title: e.target.value })} /></label>
        <label>{t('Why it is critical')}<input maxLength={500} value={form.reason} onChange={(e) => setForm({ ...form, reason: e.target.value })} /></label>
        <ErrorText error={error} />
        <div className="modal-foot"><Button className="secondary" onClick={onClose}>{t('Cancel')}</Button><Button type="submit">{t('Save')}</Button></div>
      </Form>
    </Modal>
  )
}

function SuccessorModal({ post, people, onClose, onSaved }: { post: Post; people: MyPerson[]; onClose: () => void; onSaved: (p: Post) => void }) {
  const [candidates, setCandidates] = useState<Candidate[] | null>(null)
  const [userId, setUserId] = useState<number | null>(null)
  const [readiness, setReadiness] = useState<Readiness>('ONE_TWO')
  const [note, setNote] = useState('')
  const [error, setError] = useState<string | null>(null)
  useEffect(() => { api<Candidate[]>(`/api/talent/posts/${post.id}/candidates`).then(setCandidates).catch((e: Error) => setError(e.message)) }, [post.id])
  const save = async () => {
    if (!userId) return
    try { onSaved(await api<Post>(`/api/talent/posts/${post.id}/successors`, { method: 'POST', body: { userId, readiness, note: note || null } })) } catch (e) { setError(errorText(e)) }
  }
  const taken = new Set([...post.holders.map((h) => h.userId), ...post.successors.map((s) => s.userId)])
  return (
    <Modal title={t('Successor for {a}', { a: label(post) })} size="lg" onClose={onClose}>
      <p className="muted small">{t('Suggested from the career ladders (with their conditions), the skills the job needs and the last talent review.')}</p>
      <div className="table-wrap">
        <table>
          <thead><tr><th /><th>{t('Person')}</th><th>{t('Career ladder')}</th><th>{t('Skills')}</th><th>{t('Last review')}</th></tr></thead>
          <tbody>
            {candidates?.length === 0 && <tr><td colSpan={5} className="muted">{t('Nobody stands out; choose a person below.')}</td></tr>}
            {candidates?.map((c) => (
              <tr key={c.userId}>
                <td><input type="radio" name="candidate" checked={userId === c.userId} onChange={() => setUserId(c.userId)} aria-label={c.name} /></td>
                <td>{c.name}<div className="small muted">{c.occupationName} · {c.unitName}</div>{c.successorElsewhere && <div className="small muted">{t('Already a successor elsewhere')}</div>}</td>
                <td>{c.onLadder ? <span className={`badge ${c.eligible ? 'ok' : 'warn'}`}>{c.eligible ? t('Eligible') : t('Not yet')}</span> : <span className="muted">—</span>}{c.note && <div className="small muted">{c.note}</div>}</td>
                <td>{c.skillMatch == null ? '—' : `${c.skillMatch} %`}</td>
                <td><BoxBadge box={c.box} /></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <label>{t('Or another person')}<select value={userId ?? ''} onChange={(e) => setUserId(e.target.value ? Number(e.target.value) : null)}>
        <option value="">{t('Choose…')}</option>{people.filter((p) => !taken.has(p.userId)).map((p) => <option key={p.userId} value={p.userId}>{p.name} · {p.registrationNo}</option>)}</select></label>
      <div className="grid2">
        <label>{t('Readiness')}<select value={readiness} onChange={(e) => setReadiness(e.target.value as Readiness)}>
          {(Object.keys(READINESS) as Readiness[]).map((k) => <option key={k} value={k}>{READINESS[k]}</option>)}</select></label>
        <label>{t('Note')}<input maxLength={500} value={note} onChange={(e) => setNote(e.target.value)} /></label>
      </div>
      <ErrorText error={error} />
      <div className="modal-foot"><Button className="secondary" onClick={onClose}>{t('Cancel')}</Button><Button disabled={!userId} onClick={() => void save()}>{t('Add a successor')}</Button></div>
    </Modal>
  )
}

function ActionModal({ successor, people, courses, onClose, onSaved }: { successor: Successor; people: MyPerson[]; courses: Course[]; onClose: () => void; onSaved: (p: Post) => void }) {
  const [form, setForm] = useState({ kind: 'COURSE' as ActionKind, title: '', courseId: null as number | null, mentorUserId: null as number | null, dueOn: '' })
  const [error, setError] = useState<string | null>(null)
  const save = async (e: FormEvent) => {
    e.preventDefault()
    const body = { kind: form.kind, title: form.title || null, courseId: form.kind === 'COURSE' ? form.courseId : null, mentorUserId: form.kind === 'MENTOR' ? form.mentorUserId : null, dueOn: form.dueOn || null }
    try { onSaved(await api<Post>(`/api/talent/successors/${successor.id}/actions`, { method: 'POST', body })) } catch (err) { setError(errorText(err)) }
  }
  return (
    <Modal title={t('Development of {a}', { a: successor.name })} onClose={onClose}>
      <Form onSubmit={save}>
        <label>{t('Kind')}<select value={form.kind} onChange={(e) => setForm({ ...form, kind: e.target.value as ActionKind })}>
          {(Object.keys(ACTIONS) as ActionKind[]).map((k) => <option key={k} value={k}>{ACTIONS[k]}</option>)}</select></label>
        {form.kind === 'COURSE' && <label>{t('Course')}<select value={form.courseId ?? ''} onChange={(e) => setForm({ ...form, courseId: e.target.value ? Number(e.target.value) : null })}>
          <option value="">{t('None: describe the need')}</option>{courses.map((c) => <option key={c.id} value={c.id}>{c.title}</option>)}</select></label>}
        {form.kind === 'MENTOR' && <label>{t('Mentor')}<select value={form.mentorUserId ?? ''} required onChange={(e) => setForm({ ...form, mentorUserId: e.target.value ? Number(e.target.value) : null })}>
          <option value="">{t('Choose…')}</option>{people.filter((p) => p.userId !== successor.userId).map((p) => <option key={p.userId} value={p.userId}>{p.name}</option>)}</select></label>}
        <label>{t('What')}<input maxLength={300} value={form.title} required={!(form.kind === 'COURSE' && form.courseId)} onChange={(e) => setForm({ ...form, title: e.target.value })} /></label>
        <label>{t('By')}<DateInput value={form.dueOn} onChange={(e) => setForm({ ...form, dueOn: e.target.value })} /></label>
        {form.kind === 'COURSE' && <p className="small muted">{t('A course is added to the training needs, so HR plans it with the rest.')}</p>}
        <ErrorText error={error} />
        <div className="modal-foot"><Button className="secondary" onClick={onClose}>{t('Cancel')}</Button><Button type="submit">{t('Add')}</Button></div>
      </Form>
    </Modal>
  )
}

// ---- the talent review ---------------------------------------------------------------------------------------------

function ReviewTab() {
  const [reviews, setReviews] = useState<Review[] | null>(null)
  const [open, setOpen] = useState<number | null>(null)
  const [opening, setOpening] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const load = () => api<Review[]>('/api/talent/reviews').then((r) => { setReviews(r); if (open == null && r.length > 0) setOpen(r[0].id) }).catch((e: Error) => setError(e.message))
  useEffect(() => { void load() }, []) // eslint-disable-line react-hooks/exhaustive-deps
  return (
    <>
      <div className="row space">
        <p className="muted">{t('Once a year: performance comes from the last approved evaluation, each manager proposes the potential of their people, then HR and the Direction settle both and place each person in the 9-box grid.')}</p>
        <Button onClick={() => setOpening(true)}>{t('Open a review')}</Button>
      </div>
      <ErrorText error={error} />
      {reviews && reviews.length > 0 && (
        <div className="row">
          <select className="auto-width" value={open ?? ''} aria-label={t('Talent review')} onChange={(e) => setOpen(Number(e.target.value))}>
            {reviews.map((r) => <option key={r.id} value={r.id}>{r.year} · {r.name}{r.status === 'CLOSED' ? ` (${t('Closed')})` : ''}</option>)}
          </select>
        </div>
      )}
      {reviews?.length === 0 && <div className="card"><p className="muted">{t('No talent review yet.')}</p></div>}
      {open != null && <ReviewDetail key={open} id={open} onChanged={() => void load()} />}
      {opening && <OpenReviewModal onClose={() => setOpening(false)} onSaved={(v) => { setOpening(false); setOpen(v.review.id); void load() }} />}
    </>
  )
}

function OpenReviewModal({ onClose, onSaved }: { onClose: () => void; onSaved: (v: ReviewView) => void }) {
  const [categories, setCategories] = useState<GradeCategory[]>([])
  const [units, setUnits] = useState<Unit[]>([])
  const [form, setForm] = useState({ year: thisYear, name: t('Talent review {a}', { a: thisYear }), categoryId: null as number | null, unitId: null as number | null, note: '' })
  const [error, setError] = useState<string | null>(null)
  useEffect(() => {
    api<GradeCategory[]>('/api/leave/categories').then((c) => setCategories(c.filter((x) => x.active))).catch(() => undefined)
    api<Unit[]>('/api/org/units').then((u) => setUnits(u.filter((x) => x.open))).catch(() => undefined)
  }, [])
  const save = async (e: FormEvent) => {
    e.preventDefault()
    try { onSaved(await api<ReviewView>('/api/talent/reviews', { method: 'POST', body: { ...form, note: form.note || null } })) } catch (err) { setError(errorText(err)) }
  }
  return (
    <Modal title={t('Open a review')} onClose={onClose}>
      <Form onSubmit={save}>
        <div className="grid2">
          <label>{t('Year')}<input type="number" min={2000} max={2100} value={form.year} onChange={(e) => setForm({ ...form, year: Number(e.target.value) })} required /></label>
          <label>{t('Name')}<input maxLength={150} value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} required /></label>
        </div>
        <div className="grid2">
          <label>{t('Staff category')}<select value={form.categoryId ?? ''} onChange={(e) => setForm({ ...form, categoryId: e.target.value ? Number(e.target.value) : null })}>
            <option value="">{t('Every category')}</option>{categories.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}</select></label>
          <label>{t('Unit')}<select value={form.unitId ?? ''} onChange={(e) => setForm({ ...form, unitId: e.target.value ? Number(e.target.value) : null })}>
            <option value="">{t('All units')}</option>{units.map((u) => <option key={u.id} value={u.id}>{u.name}</option>)}</select></label>
        </div>
        <label>{t('Note')}<input maxLength={1000} value={form.note} onChange={(e) => setForm({ ...form, note: e.target.value })} /></label>
        <p className="small muted">{t('Each manager is asked to propose the potential of their people.')}</p>
        <ErrorText error={error} />
        <div className="modal-foot"><Button className="secondary" onClick={onClose}>{t('Cancel')}</Button><Button type="submit">{t('Open the review')}</Button></div>
      </Form>
    </Modal>
  )
}

function ReviewDetail({ id, onChanged }: { id: number; onChanged: () => void }) {
  const ask = useAsk()
  const [view, setView] = useState<ReviewView | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [settling, setSettling] = useState<Member | null>(null)
  const [chosen, setChosen] = useState<number | null>(null)
  useEffect(() => { api<ReviewView>(`/api/talent/reviews/${id}`).then(setView).catch((e: Error) => setError(e.message)) }, [id])
  if (!view) return <ErrorText error={error} />
  const r = view.review
  const shown = chosen ? view.members.filter((m) => m.box === chosen) : view.members
  return (
    <>
      <div className="tiles">
        <div className="tile"><span>{t('People')}</span><strong>{r.members}</strong><small>{[r.categoryName, r.unitName].filter(Boolean).join(' · ') || t('Whole company')}</small></div>
        <div className="tile"><span>{t('Potential proposed')}</span><strong>{r.proposed}</strong><small>{t('by the managers')}</small></div>
        <div className="tile"><span>{t('Placed in the grid')}</span><strong>{r.settled}</strong></div>
      </div>
      <ErrorText error={error} />
      <div className="card">
        <div className="row space">
          <h3>{t('9-box grid')}</h3>
          {r.status === 'OPEN' && <Button className="secondary" onClick={() => ask({ title: t('Close this review?'), message: t('Managers can no longer propose and the grid is kept as it is.'), confirmLabel: t('Close the review'), onConfirm: async () => { try { setView(await api<ReviewView>(`/api/talent/reviews/${id}/close`, { method: 'POST' })); onChanged() } catch (e) { setError(errorText(e)) } } })}>{t('Close the review')}</Button>}
        </div>
        <div className="ninebox">
          <div className="ninebox-axis potential">{t('Potential')} →</div>
          <div className="ninebox-grid">
            {[3, 2, 1].map((pot) => [1, 2, 3].map((perf) => {
              const b = (pot - 1) * 3 + perf
              const names = view.members.filter((m) => m.box === b)
              return (
                <button key={b} type="button" className={`ninebox-cell b${b}${chosen === b ? ' chosen' : ''}`} onClick={() => setChosen(chosen === b ? null : b)}>
                  <strong>{BOXES[b]}</strong><span className="count">{view.grid[b] ?? 0}</span>
                  <span className="small">{names.slice(0, 4).map((m) => m.name).join(', ')}{names.length > 4 ? '…' : ''}</span>
                </button>
              )
            }))}
          </div>
          <div className="ninebox-axis performance">{t('Performance')} →</div>
        </div>
      </div>
      <div className="card">
        {chosen && <p className="row space"><span><strong>{BOXES[chosen]}</strong></span><Button className="link" onClick={() => setChosen(null)}>{t('Show everyone')}</Button></p>}
        <div className="table-wrap">
          <table>
            <thead><tr><th>{t('Person')}</th><th>{t('Evaluation')}</th><th>{t('Performance')}</th><th>{t('Manager proposes')}</th><th>{t('Potential')}</th><th>{t('Flight risk')}</th><th>{t('Box')}</th><th /></tr></thead>
            <tbody>
              {shown.map((m) => (
                <tr key={m.id}>
                  <td><Link to={`/users/${m.userId}`}>{m.name}</Link><div className="small muted">{m.occupationName}{m.unitName && ` · ${m.unitName}`}</div></td>
                  <td>{m.score == null ? <span className="muted">{t('None')}</span> : `${m.score} %`}</td>
                  <td>{m.performance ? `${m.performance} · ${RATING[m.performance]}` : '—'}{m.performanceAuto && m.performance && m.performance !== m.performanceAuto && <div className="small muted">{t('Evaluation says {a}', { a: m.performanceAuto })}</div>}</td>
                  <td>{m.potentialProposed ? `${m.potentialProposed} · ${RATING[m.potentialProposed]}` : <span className="muted">{t('Waiting')}</span>}{m.potentialReason && <div className="small muted">{m.potentialReason}</div>}{m.proposedByName && <div className="small muted">{m.proposedByName}</div>}</td>
                  <td>{m.potential ? `${m.potential} · ${RATING[m.potential]}` : '—'}</td>
                  <td>{m.flightRisk ? <span className={`badge ${m.flightRisk === 'HIGH' ? 'off' : m.flightRisk === 'MEDIUM' ? 'warn' : ''}`}>{RISKS[m.flightRisk]}</span> : '—'}</td>
                  <td><BoxBadge box={m.box} /></td>
                  <td>{r.status === 'OPEN' && <Button className="secondary small" onClick={() => setSettling(m)}>{t('Settle')}</Button>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
      {settling && <SettleModal member={settling} onClose={() => setSettling(null)} onSaved={(v) => { setSettling(null); setView(v); onChanged() }} />}
    </>
  )
}

function SettleModal({ member, onClose, onSaved }: { member: Member; onClose: () => void; onSaved: (v: ReviewView) => void }) {
  const [form, setForm] = useState({
    performance: member.performance, performanceReason: member.performanceReason ?? '', potential: member.potential ?? member.potentialProposed,
    flightRisk: member.flightRisk, mobility: member.mobility, note: member.note ?? '',
  })
  const [error, setError] = useState<string | null>(null)
  const save = async () => {
    const body = { ...form, performanceReason: form.performanceReason || null, note: form.note || null }
    try { onSaved(await api<ReviewView>(`/api/talent/members/${member.id}`, { method: 'PUT', body })) } catch (e) { setError(errorText(e)) }
  }
  const moved = member.performanceAuto != null && form.performance != null && form.performance !== member.performanceAuto
  const box = form.performance && form.potential ? (form.potential - 1) * 3 + form.performance : null
  return (
    <Modal title={member.name} onClose={onClose}>
      <p className="small muted">{member.score != null ? t('Last approved evaluation: {a} %', { a: member.score }) : t('No approved evaluation.')}{member.potentialReason && <> · {t('Manager: {a}', { a: member.potentialReason })}</>}</p>
      <div className="grid2">
        <label>{t('Performance')}<RatingSelect value={form.performance} label={t('Performance')} onChange={(n) => setForm({ ...form, performance: n })} /></label>
        <label>{t('Potential')}<RatingSelect value={form.potential} label={t('Potential')} onChange={(n) => setForm({ ...form, potential: n })} /></label>
      </div>
      {moved && <label>{t('Why the performance differs from the evaluation')}<input maxLength={500} value={form.performanceReason} required onChange={(e) => setForm({ ...form, performanceReason: e.target.value })} /></label>}
      <div className="grid2">
        <label>{t('Flight risk')}<select value={form.flightRisk ?? ''} onChange={(e) => setForm({ ...form, flightRisk: (e.target.value || null) as Risk | null })}>
          <option value="">—</option>{(Object.keys(RISKS) as Risk[]).map((k) => <option key={k} value={k}>{RISKS[k]}</option>)}</select></label>
        <label>{t('Mobility')}<select value={form.mobility ?? ''} onChange={(e) => setForm({ ...form, mobility: (e.target.value || null) as Mobility | null })}>
          <option value="">—</option>{(Object.keys(MOBILITY) as Mobility[]).map((k) => <option key={k} value={k}>{MOBILITY[k]}</option>)}</select></label>
      </div>
      <label>{t('Note')}<input maxLength={1000} value={form.note} onChange={(e) => setForm({ ...form, note: e.target.value })} /></label>
      <p>{t('Box:')} <BoxBadge box={box} /></p>
      <ErrorText error={error} />
      <div className="modal-foot"><Button className="secondary" onClick={onClose}>{t('Cancel')}</Button><Button onClick={() => void save()}>{t('Save')}</Button></div>
    </Modal>
  )
}

// ---- coverage ------------------------------------------------------------------------------------------------------

function CoverageTab() {
  const [c, setC] = useState<Coverage | null>(null)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => { api<Coverage>('/api/talent/coverage').then(setC).catch((e: Error) => setError(e.message)) }, [])
  if (!c) return <ErrorText error={error} />
  return (
    <>
      <div className="tiles">
        <div className="tile"><span>{t('Critical posts')}</span><strong>{c.posts}</strong></div>
        <div className="tile"><span>{t('Covered')}</span><strong>{c.covered}</strong><small>{t('a successor ready within 2 years')}</small></div>
        <div className="tile"><span>{t('Holder may leave')}</span><strong>{c.holderAtRisk}</strong></div>
        <div className="tile"><span>{t('Retirement within 2 years')}</span><strong>{c.holderRetiring}</strong></div>
        <div className="tile"><span>{t('Vacant')}</span><strong>{c.vacant}</strong></div>
      </div>
      <div className="card">
        <h3>{t('Posts at risk')}</h3>
        <div className="table-wrap">
          <table>
            <thead><tr><th>{t('Post')}</th><th>{t('Held by')}</th><th>{t('Successors')}</th><th>{t('Why')}</th></tr></thead>
            <tbody>
              {c.atRisk.length === 0 && <tr><td colSpan={4} className="muted">{t('Every critical post is covered.')}</td></tr>}
              {c.atRisk.map((p) => (
                <tr key={p.id}>
                  <td><strong>{label(p)}</strong><div className="small muted">{p.unitName}</div></td>
                  <td>{p.holders.map((h) => h.name).join(', ') || <span className="badge off">{t('Vacant post')}</span>}</td>
                  <td>{p.successors.length === 0 ? '—' : p.successors.map((s) => <div key={s.id} className="small">{s.name} · {READINESS[s.readiness]}</div>)}</td>
                  <td className="small">{p.warnings.map((w) => <div key={w}>{w}</div>)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </>
  )
}

// ---- the manager's proposals ---------------------------------------------------------------------------------------

/** A manager proposes the potential of their people; they never see the grid or the performance HR settles. */
export function TalentProposalsPage() {
  const [rows, setRows] = useState<Proposal[] | null>(null)
  const [drafts, setDrafts] = useState<Record<number, { potential: number | null; reason: string }>>({})
  const [error, setError] = useState<string | null>(null)
  const [saved, setSaved] = useState<number | null>(null)
  const show = (r: Proposal[]) => { setRows(r); setDrafts(Object.fromEntries(r.map((p) => [p.memberId, { potential: p.potentialProposed, reason: p.potentialReason ?? '' }]))) }
  useEffect(() => { api<Proposal[]>('/api/talent/proposals').then(show).catch((e: Error) => setError(e.message)) }, [])
  const save = async (p: Proposal) => {
    setError(null); setSaved(null)
    const d = drafts[p.memberId]
    try { show(await api<Proposal[]>(`/api/talent/proposals/${p.memberId}`, { method: 'PUT', body: { potential: d.potential, reason: d.reason } })); setSaved(p.memberId) } catch (e) { setError(errorText(e)) }
  }
  return (
    <section>
      <PageTitle title={t('Potential of my team')} />
      <p className="muted">{t('For the talent review, say how far each person could grow in the next years: 1 low (stays at this level), 2 medium (one step up), 3 high (could take on much larger roles). Give a short reason. HR and the Direction decide; the result is confidential.')}</p>
      <ErrorText error={error} />
      <div className="card">
        <div className="table-wrap">
          <table>
            <thead><tr><th>{t('Person')}</th><th>{t('Talent review')}</th><th>{t('Potential')}</th><th>{t('Reason')}</th><th /></tr></thead>
            <tbody>
              {rows?.length === 0 && <tr><td colSpan={5} className="muted">{t('No talent review is open for your team.')}</td></tr>}
              {rows?.map((p) => (
                <tr key={p.memberId}>
                  <td>{p.name}<div className="small muted">{p.occupationName}</div></td>
                  <td className="small">{p.reviewName}</td>
                  <td><RatingSelect value={drafts[p.memberId]?.potential ?? null} label={t('Potential')} onChange={(n) => setDrafts({ ...drafts, [p.memberId]: { ...drafts[p.memberId], potential: n } })} /></td>
                  <td><input maxLength={500} value={drafts[p.memberId]?.reason ?? ''} aria-label={t('Reason')} onChange={(e) => setDrafts({ ...drafts, [p.memberId]: { ...drafts[p.memberId], reason: e.target.value } })} /></td>
                  <td>
                    <Button className="small" disabled={!drafts[p.memberId]?.potential || !drafts[p.memberId]?.reason.trim()} onClick={() => void save(p)}>{t('Save')}</Button>
                    {saved === p.memberId && <span className="small muted"> {t('Saved')}</span>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </section>
  )
}

/** On a person's page, for HR and the Direction only. */
export function TalentCard({ userId }: { userId: string }) {
  const access = useAccess()
  const [v, setV] = useState<PersonView | null>(null)
  useEffect(() => { if (access) api<PersonView>(`/api/talent/people/${userId}`).then(setV).catch(() => setV(null)) }, [access, userId])
  if (!access || !v) return null
  if (v.box == null && v.successorOf.length === 0 && v.holds.length === 0) return null
  return (
    <div className="card">
      <div className="row space"><h3>{t('Talent and succession')}</h3><span className="badge">{t('Confidential')}</span></div>
      <dl className="facts">
        <dt>{t('Last review')}</dt><dd><BoxBadge box={v.box} />{v.reviewName && <span className="small muted"> · {v.reviewName}</span>}</dd>
        {v.flightRisk && <><dt>{t('Flight risk')}</dt><dd>{RISKS[v.flightRisk]}</dd></>}
        {v.mobility && <><dt>{t('Mobility')}</dt><dd>{MOBILITY[v.mobility]}</dd></>}
        {v.holds.length > 0 && <><dt>{t('Holds a critical post')}</dt><dd>{v.holds.join(', ')}</dd></>}
        {v.successorOf.length > 0 && <><dt>{t('Successor for')}</dt><dd>{v.successorOf.join(', ')}</dd></>}
      </dl>
      <Link to="/talent">{t('Talent and succession')}</Link>
    </div>
  )
}

// ---- settings ------------------------------------------------------------------------------------------------------

type TalentSettings = { retirementAge: number; performanceLow: number; performanceHigh: number }

/** The retirement age behind the succession warnings, and how an evaluation % becomes performance 1, 2 or 3. */
export function TalentSettingsPage() {
  const [form, setForm] = useState<TalentSettings | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  useEffect(() => { api<TalentSettings>('/api/talent/settings').then(setForm).catch((e: Error) => setError(e.message)) }, [])
  const save = async (e: FormEvent) => {
    e.preventDefault()
    setError(null); setMessage(null)
    try { setForm(await api<TalentSettings>('/api/talent/settings', { method: 'PUT', body: form })); setMessage(t('Settings saved.')) } catch (err) { setError(errorText(err)) }
  }
  if (!form) return <section><ErrorText error={error} /></section>
  const num = (k: keyof TalentSettings) => (e: { target: { value: string } }) => setForm({ ...form, [k]: Number(e.target.value) })
  return (
    <section>
      <p><Link to="/talent">{t('← Talent and succession')}</Link></p>
      <h2>{t('Talent settings')}</h2>
      <Form onSubmit={save}>
        <div className="card">
          <label className="auto-width">{t('Retirement age')}<input type="number" min="40" max="80" value={form.retirementAge} onChange={num('retirementAge')} />
            <span className="hint">{t('Used to warn when the holder of a critical post retires within two years. Check the age with your pension fund (CNSS).')}</span></label>
        </div>
        <div className="card">
          <h3>{t('Performance from the evaluation')}</h3>
          <div className="grid2">
            <label>{t('Below this %, performance 1')}<input type="number" min="1" max="99" value={form.performanceLow} onChange={num('performanceLow')} /></label>
            <label>{t('From this %, performance 3')}<input type="number" min="1" max="100" value={form.performanceHigh} onChange={num('performanceHigh')} /></label>
          </div>
          <p className="hint">{t('In between, performance 2. Applies to reviews opened from now on; HR and the Direction can still change it, with a reason.')}</p>
        </div>
        <ErrorText error={error} />
        {message && <p className="success">{message}</p>}
        <Button type="submit">{t('Save')}</Button>
      </Form>
    </section>
  )
}
