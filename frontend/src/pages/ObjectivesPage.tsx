import { useEffect, useState, type ReactNode } from 'react'
import { useSearchParams } from 'react-router-dom'
import { ApiError, api, longDay } from '../api'
import { useAuth } from '../auth'
import { Button, Form } from '../components/Button'
import { useAsk } from '../components/Dialogs'
import { ErrorText } from '../components/ErrorText'
import { Modal } from '../components/Modal'
import { PageTitle } from '../components/SettingsLink'
import { locale, t } from '../i18n'

export type Objective = {
  id: number; userId: number | null; orgUnitId: number | null; unitName: string | null; year: number; parentId: number | null
  parentTitle: string | null; title: string; measure: string | null; target: string | null; weight: number; dueDate: string | null
  status: 'DRAFT' | 'SHARED' | 'ACKNOWLEDGED' | 'CANCELLED'; setBy: number | null; setByName: string | null
  employeeComment: string | null; acknowledgedAt: string | null; progress: number | null; checkinComment: string | null
  checkinAt: string | null; developmentNeed: string | null; lastScore: number | null
}
type Person = { userId: number; name: string; year: number; canEdit: boolean; mine: boolean; weightTotal: number; late: boolean; objectives: Objective[]; unitObjectives: Objective[] }
type TeamRow = { userId: number; name: string; unit: string | null; objectives: number; weightTotal: number; drafts: number; shared: number; acknowledged: number; checkedIn: number; averageProgress: number | null; late: boolean }
type UnitRow = { unitId: number; unitName: string | null; people: number; withObjectives: number; acknowledged: number; checkedIn: number; objectives: number; reached: number }
type Gap = { userId: number; name: string; unit: string | null; detail: string | null }
type Overview = { year: number; checkinDue: boolean; units: UnitRow[]; noObjectives: Gap[]; lateCheckins: Gap[]; developmentNeeds: Gap[] }
type Unit = { id: number; name: string; open: boolean }
type Tab = 'mine' | 'team' | 'units' | 'overview'

const num = (n: number | null | undefined) => n == null ? '—' : Number(n).toLocaleString(locale())
const errorText = (e: unknown) => (e instanceof ApiError ? [e.message, ...Object.values(e.fieldErrors)].join(' ') : t('Something went wrong.'))
const STATUS: Record<Objective['status'], string> = { DRAFT: t('Draft'), SHARED: t('To acknowledge'), ACKNOWLEDGED: t('Acknowledged'), CANCELLED: t('Called off') }
const statusBadge = (s: Objective['status']) => <span className={s === 'ACKNOWLEDGED' ? 'badge ok' : s === 'SHARED' ? 'badge warn' : 'badge'}>{STATUS[s]}</span>

/** Objectives: one's own, the team's (manager or HR), the units', and HR's overview. */
export default function ObjectivesPage() {
  const { can } = useAuth()
  const [params, setParams] = useSearchParams()
  const manager = can('evaluation.manage') || can('evaluation.approve') || can('evaluation.score.team')
  const hr = can('evaluation.manage') || can('evaluation.approve')
  const person = params.get('person')
  const [tab, setTab] = useState<Tab>(person ? 'team' : 'mine')
  const [year, setYear] = useState(new Date().getFullYear())
  const tabs: Partial<Record<Tab, string>> = { mine: t('My objectives') }
  if (manager) { tabs.team = t('My team'); tabs.units = t('Unit objectives') }
  if (hr) tabs.overview = t('Overview')
  const thisYear = new Date().getFullYear()
  return (
    <section>
      <PageTitle title={t('Objectives')}>
        <select value={year} onChange={(e) => setYear(Number(e.target.value))} aria-label={t('Year')}>
          {[thisYear - 1, thisYear, thisYear + 1].map((y) => <option key={y} value={y}>{y}</option>)}
        </select>
      </PageTitle>
      {Object.keys(tabs).length > 1 && (
        <div className="segmented" role="tablist">
          {(Object.keys(tabs) as Tab[]).map((k) => (
            <button key={k} type="button" role="tab" aria-selected={tab === k} className={tab === k ? 'on' : ''}
              onClick={() => { setTab(k); if (person) setParams({}) }}>{tabs[k]}</button>
          ))}
        </div>
      )}
      {tab === 'mine' && <Mine year={year} />}
      {tab === 'team' && (person
        ? <PersonPanel userId={Number(person)} year={year} onBack={() => setParams({})} />
        : <TeamTab year={year} open={(id) => setParams({ person: String(id) })} />)}
      {tab === 'units' && <UnitsTab year={year} />}
      {tab === 'overview' && <OverviewTab year={year} open={(id) => { setTab('team'); setParams({ person: String(id) }) }} />}
    </section>
  )
}

// ---- one's own ---------------------------------------------------------------------------------------------------

function Mine({ year }: { year: number }) {
  const [data, setData] = useState<Person | null>(null)
  const [comment, setComment] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const load = () => api<Person>(`/api/objectives/mine?year=${year}`).then(setData).catch((e: Error) => setError(e.message))
  useEffect(() => { setData(null); void load() }, [year])
  const acknowledge = async () => {
    setError(null)
    try {
      setData(await api<Person>('/api/objectives/mine/acknowledge', { method: 'POST', body: { year, comment: comment || null } }))
      setMessage(t('Thank you. Your manager is told.'))
    } catch (e) { setError(errorText(e)) }
  }
  if (!data) return <ErrorText error={error} />
  const toAck = data.objectives.some((o) => o.status === 'SHARED')
  return (
    <>
      <p className="muted">{t('Your manager writes your objectives for the year, each with its weight. Read them and acknowledge them; your evaluation scores them.')}</p>
      <ErrorText error={error} />
      {message && <p className="success">{message}</p>}
      {data.objectives.length === 0
        ? <div className="card"><p className="muted">{t('No objective shared with you for {a}.', { a: year })}</p></div>
        : <div className="card"><ObjectiveTable list={data.objectives} /></div>}
      {toAck && (
        <div className="card">
          <h3>{t('Acknowledge my objectives')}</h3>
          <label>{t('Comment (optional)')}<textarea rows={3} maxLength={1000} value={comment} onChange={(e) => setComment(e.target.value)} /></label>
          <Button onClick={acknowledge}>{t('I acknowledge these objectives')}</Button>
        </div>
      )}
    </>
  )
}

function ObjectiveTable({ list, actions }: { list: Objective[]; actions?: (o: Objective) => ReactNode }) {
  return (
    <div className="table-wrap">
      <table>
        <thead><tr><th>{t('Objective')}</th><th>{t('Weight')}</th><th>{t('Due')}</th><th>{t('Status')}</th><th>{t('Progress check')}</th>{actions && <th />}</tr></thead>
        <tbody>
          {list.map((o) => (
            <tr key={o.id} className={o.status === 'CANCELLED' ? 'muted' : undefined}>
              <td>
                <strong>{o.title}</strong>
                {o.measure && <div className="small">{t('Measured by: {a}', { a: o.measure })}</div>}
                {o.target && <div className="small">{t('Target: {a}', { a: o.target })}</div>}
                {o.parentTitle && <div className="small muted">{t('Follows: {a}', { a: o.parentTitle })}</div>}
                {o.employeeComment && <div className="small muted">{t('Employee: {a}', { a: o.employeeComment })}</div>}
              </td>
              <td className="nowrap">{o.userId == null ? '—' : `${num(o.weight)} %`}</td>
              <td className="nowrap">{o.dueDate ? longDay(o.dueDate) : '—'}</td>
              <td>{statusBadge(o.status)}</td>
              <td className="small">
                {o.checkinAt ? <>{t('{a} % on {b}', { a: o.progress ?? 0, b: longDay(o.checkinAt) })}{o.checkinComment && <div className="muted">{o.checkinComment}</div>}</> : '—'}
                {o.developmentNeed && <div>{t('Development need: {a}', { a: o.developmentNeed })}</div>}
                {o.lastScore != null && <div>{t('Last score: {a} %', { a: num(o.lastScore) })}</div>}
              </td>
              {actions && <td className="row">{actions(o)}</td>}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

// ---- the team ----------------------------------------------------------------------------------------------------

function TeamTab({ year, open }: { year: number; open: (id: number) => void }) {
  const [rows, setRows] = useState<TeamRow[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => { setRows(null); api<TeamRow[]>(`/api/objectives/team?year=${year}`).then(setRows).catch((e: Error) => setError(e.message)) }, [year])
  return (
    <>
      <p className="muted">{t('Write each person\'s objectives for the year, with weights that add up to 100 %, then share them. Around mid-year, record a check-in for each objective. The evaluation scores them when its template has the Individual objectives criterion.')}</p>
      <ErrorText error={error} />
      <div className="card">
        {rows && rows.length === 0 ? <p className="muted">{t('Nobody in your team for now.')}</p> : (
          <div className="table-wrap">
            <table>
              <thead><tr><th>{t('Person')}</th><th>{t('Unit')}</th><th>{t('Objectives')}</th><th>{t('Weights')}</th><th>{t('Where it stands')}</th><th>{t('Progress')}</th><th /></tr></thead>
              <tbody>
                {(rows ?? []).map((r) => (
                  <tr key={r.userId}>
                    <td>{r.name}</td>
                    <td className="small">{r.unit ?? '—'}</td>
                    <td>{r.objectives}</td>
                    <td>{r.objectives === 0 ? '—' : <span className={Number(r.weightTotal) === 100 ? '' : 'warn-text'}>{num(r.weightTotal)} %</span>}</td>
                    <td className="small">
                      {r.objectives === 0 ? <span className="badge">{t('No objectives yet')}</span> : <>
                        {r.drafts > 0 && <span className="badge">{t('{a} drafts', { a: r.drafts })}</span>}{' '}
                        {r.shared > 0 && <span className="badge warn">{t('{a} to acknowledge', { a: r.shared })}</span>}{' '}
                        {r.acknowledged > 0 && <span className="badge ok">{t('{a} acknowledged', { a: r.acknowledged })}</span>}{' '}
                        {r.late && <span className="badge off">{t('Check-in late')}</span>}
                      </>}
                    </td>
                    <td>{r.averageProgress == null ? '—' : `${r.averageProgress} %`}</td>
                    <td><Button className="secondary" onClick={() => open(r.userId)}>{t('Open')}</Button></td>
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

function PersonPanel({ userId, year, onBack }: { userId: number; year: number; onBack: () => void }) {
  const [data, setData] = useState<Person | null>(null)
  const [editing, setEditing] = useState<Objective | 'new' | null>(null)
  const [checkin, setCheckin] = useState<Objective | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const ask = useAsk()
  const load = () => api<Person>(`/api/objectives/people/${userId}?year=${year}`).then(setData).catch((e: Error) => setError(e.message))
  useEffect(() => { setData(null); void load() }, [userId, year])
  const share = async () => {
    setError(null); setMessage(null)
    try { setData(await api<Person>(`/api/objectives/people/${userId}/share?year=${year}`, { method: 'POST' })); setMessage(t('Shared. The person is asked to acknowledge them.')) } catch (e) { setError(errorText(e)) }
  }
  const remove = (o: Objective) => ask({
    title: o.status === 'DRAFT' ? t('Delete this objective?') : t('Cancel this objective?'),
    message: o.status === 'DRAFT' ? o.title : t('It stays in the history, with no weight in the evaluation.'),
    confirmLabel: o.status === 'DRAFT' ? t('Delete') : t('Cancel the objective'), danger: true,
    onConfirm: async () => { await api(`/api/objectives/${o.id}`, { method: 'DELETE' }); void load() },
  })
  if (!data) return <><p><button type="button" className="link" onClick={onBack}>{t('← My team')}</button></p><ErrorText error={error} /></>
  const live = data.objectives.filter((o) => o.status !== 'CANCELLED')
  const drafts = live.some((o) => o.status === 'DRAFT')
  return (
    <>
      <p><button type="button" className="link" onClick={onBack}>{t('← My team')}</button></p>
      <ErrorText error={error} />
      {message && <p className="success">{message}</p>}
      <div className="card">
        <div className="row space">
          <div>
            <h3>{t('Objectives of {a} for {b}', { a: data.name, b: year })}</h3>
            <p className="small muted">{t('Weights: {a} % of 100 %', { a: num(data.weightTotal) })}{data.late && <> · <span className="warn-text">{t('Mid-year check-in late')}</span></>}</p>
          </div>
          {data.canEdit && (
            <div className="row">
              <Button className="secondary" onClick={() => setEditing('new')}>{t('Add an objective')}</Button>
              {drafts && <Button onClick={share}>{t('Share with {a}', { a: data.name })}</Button>}
            </div>
          )}
        </div>
        {data.objectives.length === 0 ? <p className="muted">{t('No objective for this year yet.')}</p> : (
          <ObjectiveTable list={data.objectives} actions={data.canEdit ? (o) => o.status === 'CANCELLED' ? null : <>
            <Button className="secondary" onClick={() => setEditing(o)}>{t('Edit')}</Button>
            {o.status !== 'DRAFT' && <Button className="secondary" onClick={() => setCheckin(o)}>{t('Progress check')}</Button>}
            <button type="button" className="link" onClick={() => remove(o)}>{o.status === 'DRAFT' ? t('Delete') : t('Cancel')}</button>
          </> : undefined} />
        )}
      </div>
      {editing && <ObjectiveModal objective={editing === 'new' ? null : editing} userId={userId} year={year} parents={data.unitObjectives}
        onClose={() => setEditing(null)} onDone={() => { setEditing(null); void load() }} />}
      {checkin && <CheckinModal objective={checkin} onClose={() => setCheckin(null)} onDone={() => { setCheckin(null); void load() }} />}
    </>
  )
}

function ObjectiveModal({ objective, userId, unitId, year, parents, units, onClose, onDone }: {
  objective: Objective | null; userId?: number; unitId?: number; year: number; parents?: Objective[]; units?: Unit[]
  onClose: () => void; onDone: () => void
}) {
  const forUnit = userId == null
  const [title, setTitle] = useState(objective?.title ?? '')
  const [measure, setMeasure] = useState(objective?.measure ?? '')
  const [target, setTarget] = useState(objective?.target ?? '')
  const [weight, setWeight] = useState(objective ? String(objective.weight) : '25')
  const [dueDate, setDueDate] = useState(objective?.dueDate ?? `${year}-12-31`)
  const [parentId, setParentId] = useState(objective?.parentId ? String(objective.parentId) : '')
  const [orgUnitId, setOrgUnitId] = useState(objective?.orgUnitId ? String(objective.orgUnitId) : unitId ? String(unitId) : '')
  const [error, setError] = useState<string | null>(null)
  const submit = async () => {
    setError(null)
    try {
      await api(objective ? `/api/objectives/${objective.id}` : '/api/objectives', { method: objective ? 'PUT' : 'POST', body: {
        userId: forUnit ? null : userId, orgUnitId: forUnit ? Number(orgUnitId) : null, year, parentId: parentId ? Number(parentId) : null,
        title, measure, target, weight: forUnit ? 0 : Number(weight || 0), dueDate: dueDate || null } })
      onDone()
    } catch (e) { setError(errorText(e)) }
  }
  return (
    <Modal title={objective ? t('Edit the objective') : t('Add an objective')} size="lg" onClose={onClose}
      footer={<><Button className="secondary" type="button" onClick={onClose}>{t('Cancel')}</Button><Button type="submit" form="objective">{t('Save')}</Button></>}>
      <Form id="objective" onSubmit={submit}>
        {forUnit && !objective && (
          <label>{t('Unit')}
            <select required value={orgUnitId} onChange={(e) => setOrgUnitId(e.target.value)}>
              <option value="">{t('Choose…')}</option>{(units ?? []).map((u) => <option key={u.id} value={u.id}>{u.name}</option>)}
            </select>
          </label>
        )}
        <label>{t('Objective')}<input required maxLength={200} value={title} onChange={(e) => setTitle(e.target.value)} placeholder={t('For example: close the monthly accounts by the 5th')} /></label>
        <div className="grid2">
          <label>{t('Measured by (optional)')}<input maxLength={400} value={measure} onChange={(e) => setMeasure(e.target.value)} placeholder={t('For example: closing date each month')} /></label>
          <label>{t('Target (optional)')}<input maxLength={200} value={target} onChange={(e) => setTarget(e.target.value)} placeholder={t('For example: 12 closings out of 12')} /></label>
          {!forUnit && <label>{t('Weight (%)')}<input type="number" required min={0} max={100} step={1} value={weight} onChange={(e) => setWeight(e.target.value)} /></label>}
          <label>{t('Due date (optional)')}<input type="date" value={dueDate} onChange={(e) => setDueDate(e.target.value)} /></label>
        </div>
        {!forUnit && (parents ?? []).length > 0 && (
          <label>{t('Follows a unit objective (optional)')}
            <select value={parentId} onChange={(e) => setParentId(e.target.value)}>
              <option value="">{t('None')}</option>{(parents ?? []).map((p) => <option key={p.id} value={p.id}>{p.unitName} · {p.title}</option>)}
            </select>
          </label>
        )}
        {objective && objective.status === 'ACKNOWLEDGED' && <p className="notice small">{t('The person acknowledged this objective: a change asks them to acknowledge it again.')}</p>}
        <ErrorText error={error} />
      </Form>
    </Modal>
  )
}

function CheckinModal({ objective, onClose, onDone }: { objective: Objective; onClose: () => void; onDone: () => void }) {
  const [progress, setProgress] = useState(String(objective.progress ?? 50))
  const [comment, setComment] = useState(objective.checkinComment ?? '')
  const [need, setNeed] = useState(objective.developmentNeed ?? '')
  const [error, setError] = useState<string | null>(null)
  const submit = async () => {
    setError(null)
    try {
      await api(`/api/objectives/${objective.id}/checkin`, { method: 'PUT', body: { progress: Number(progress), comment, developmentNeed: need } })
      onDone()
    } catch (e) { setError(errorText(e)) }
  }
  return (
    <Modal title={t('Check-in: {a}', { a: objective.title })} onClose={onClose}
      footer={<><Button className="secondary" type="button" onClick={onClose}>{t('Cancel')}</Button><Button type="submit" form="checkin">{t('Save')}</Button></>}>
      <Form id="checkin" onSubmit={submit}>
        <label>{t('Progress (%)')}<input type="number" required min={0} max={100} step={5} value={progress} onChange={(e) => setProgress(e.target.value)} /></label>
        <label>{t('Comment')}<textarea rows={3} maxLength={1000} value={comment} onChange={(e) => setComment(e.target.value)} /></label>
        <label>{t('Development need (optional)')}<input maxLength={400} value={need} onChange={(e) => setNeed(e.target.value)} placeholder={t('For example: Excel training')} /></label>
        <ErrorText error={error} />
      </Form>
    </Modal>
  )
}

// ---- units -------------------------------------------------------------------------------------------------------

function UnitsTab({ year }: { year: number }) {
  const [list, setList] = useState<Objective[]>([])
  const [units, setUnits] = useState<Unit[]>([])
  const [editing, setEditing] = useState<Objective | 'new' | null>(null)
  const [checkin, setCheckin] = useState<Objective | null>(null)
  const [error, setError] = useState<string | null>(null)
  const ask = useAsk()
  const load = () => api<Objective[]>(`/api/objectives/units?year=${year}`).then(setList).catch((e: Error) => setError(e.message))
  useEffect(() => { void load() }, [year])
  useEffect(() => { api<Unit[]>('/api/org/units').then((u) => setUnits(u.filter((x) => x.open))).catch(() => undefined) }, [])
  const remove = (o: Objective) => ask({ title: t('Cancel this objective?'), message: o.title, confirmLabel: t('Cancel the objective'), danger: true,
    onConfirm: async () => { await api(`/api/objectives/${o.id}`, { method: 'DELETE' }); void load() } })
  return (
    <>
      <p className="muted">{t('A unit\'s own objectives for the year, set by HR or the unit\'s head. A person\'s objective can follow one, so the unit\'s goals reach each person.')}</p>
      <ErrorText error={error} />
      <div className="card">
        <div className="row space">
          <h3>{t('Unit objectives')}</h3>
          <Button onClick={() => setEditing('new')}>{t('Add a unit objective')}</Button>
        </div>
        {list.length === 0 ? <p className="muted">{t('No unit objective for {a}.', { a: year })}</p> : (
          <div className="table-wrap">
            <table>
              <thead><tr><th>{t('Unit')}</th><th>{t('Objective')}</th><th>{t('Due')}</th><th>{t('Progress check')}</th><th /></tr></thead>
              <tbody>
                {list.map((o) => (
                  <tr key={o.id} className={o.status === 'CANCELLED' ? 'muted' : undefined}>
                    <td>{o.unitName}</td>
                    <td><strong>{o.title}</strong>{o.measure && <div className="small">{t('Measured by: {a}', { a: o.measure })}</div>}{o.target && <div className="small">{t('Target: {a}', { a: o.target })}</div>}</td>
                    <td className="nowrap">{o.dueDate ? longDay(o.dueDate) : '—'}</td>
                    <td className="small">{o.checkinAt ? t('{a} % on {b}', { a: o.progress ?? 0, b: longDay(o.checkinAt) }) : '—'}</td>
                    <td className="row">{o.status !== 'CANCELLED' && <>
                      <Button className="secondary" onClick={() => setEditing(o)}>{t('Edit')}</Button>
                      <Button className="secondary" onClick={() => setCheckin(o)}>{t('Progress check')}</Button>
                      <button type="button" className="link" onClick={() => remove(o)}>{t('Cancel')}</button>
                    </>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
      {editing && <ObjectiveModal objective={editing === 'new' ? null : editing} year={year} units={units}
        onClose={() => setEditing(null)} onDone={() => { setEditing(null); void load() }} />}
      {checkin && <CheckinModal objective={checkin} onClose={() => setCheckin(null)} onDone={() => { setCheckin(null); void load() }} />}
    </>
  )
}

// ---- HR overview -------------------------------------------------------------------------------------------------

function OverviewTab({ year, open }: { year: number; open: (id: number) => void }) {
  const [data, setData] = useState<Overview | null>(null)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => { setData(null); api<Overview>(`/api/objectives/overview?year=${year}`).then(setData).catch((e: Error) => setError(e.message)) }, [year])
  if (!data) return <ErrorText error={error} />
  const gaps = (title: string, list: Gap[], empty: string) => (
    <div className="card">
      <h3>{title} <span className="muted">({list.length})</span></h3>
      {list.length === 0 ? <p className="muted">{empty}</p> : (
        <div className="table-wrap">
          <table>
            <tbody>
              {list.map((g, i) => (
                <tr key={`${g.userId}-${i}`}>
                  <td><button type="button" className="link" onClick={() => open(g.userId)}>{g.name}</button></td>
                  <td className="small">{g.unit ?? '—'}</td>
                  <td className="small">{g.detail ?? ''}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
  return (
    <>
      <p className="muted">{t('Where each unit stands: people with shared objectives, acknowledged, checked in, and objectives reached (100 % progress or a score of 75 or more in an evaluation).')}</p>
      <div className="card">
        <h3>{t('By unit')}</h3>
        <div className="table-wrap">
          <table>
            <thead><tr><th>{t('Unit')}</th><th>{t('People')}</th><th>{t('With objectives')}</th><th>{t('Acknowledged')}</th><th>{t('Progress checked')}</th><th>{t('Objectives reached')}</th></tr></thead>
            <tbody>
              {data.units.map((u) => (
                <tr key={u.unitId}>
                  <td>{u.unitName ?? t('No unit')}</td><td>{u.people}</td><td>{u.withObjectives}</td><td>{u.acknowledged}</td><td>{u.checkedIn}</td>
                  <td>{u.objectives === 0 ? '—' : t('{a} of {b}', { a: u.reached, b: u.objectives })}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
      {gaps(t('People with no objectives'), data.noObjectives, t('Everyone has objectives.'))}
      {data.checkinDue && gaps(t('Mid-year check-in late'), data.lateCheckins, t('Every objective has its check-in.'))}
      {gaps(t('Development needs'), data.developmentNeeds, t('No development need recorded.'))}
    </>
  )
}
