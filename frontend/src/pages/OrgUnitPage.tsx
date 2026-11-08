import { useEffect, useState, type FormEvent } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ApiError, api, type User } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'
import { UnitSelect } from '../components/OrgCard'
import { headTitle, unitPath, type OrgHead, type OrgLevel, type OrgMember, type OrgUnit } from '../org'
import { Button, Form } from '../components/Button'
import { Modal } from '../components/Modal'
import { t } from '../i18n'

type Detail = { unit: OrgUnit; heads: OrgHead[]; members?: OrgMember[] }
const today = () => new Date().toISOString().slice(0, 10)
const message = (err: unknown) => err instanceof ApiError ? [err.message, ...Object.values(err.fieldErrors)].join(' ') : t('Something went wrong.')

/** One unit: who heads it, its people, its sub-units, and (for HR) the forms to change it. */
export default function OrgUnitPage() {
  const { id } = useParams()
  const navigate = useNavigate()
  const { can } = useAuth()
  const manage = can('org.manage')
  const [data, setData] = useState<Detail | null>(null)
  const [units, setUnits] = useState<OrgUnit[]>([])
  const [levels, setLevels] = useState<OrgLevel[]>([])
  const [mode, setMode] = useState<'' | 'edit' | 'child' | 'close'>('')
  const [error, setError] = useState<string | null>(null)

  const load = () => {
    api<Detail>(`/api/org/units/${id}`).then(setData).catch((e: Error) => setError(e.message))
    api<OrgUnit[]>('/api/org/units?closed=true').then(setUnits).catch(() => undefined)
  }
  useEffect(() => {
    setMode('')
    load()
    api<OrgLevel[]>('/api/org/levels').then(setLevels).catch(() => undefined)
  }, [id]) // eslint-disable-line react-hooks/exhaustive-deps

  if (!data) return <section><ErrorText error={error} /></section>
  const u = data.unit
  const children = units.filter((c) => c.parentId === u.id)
  const current = (role: 'HEAD' | 'DEPUTY') => role === 'HEAD' ? u.head : u.deputy

  return (
    <section>
      <p className="small muted"><Link to="/org">{t('Org chart')}</Link> · {unitPath(units, u.id)}</p>
      <h2>{u.name} <span className="muted">({u.code})</span></h2>
      <p className="muted">
        {u.levelName}{u.staff && t(' (staff unit)')}{' '}{t('· since')}{' '}{u.validFrom}{u.validTo && t(' · closed on {a}', { a: u.validTo })}
        {u.costCentre && t(' · cost centre {a}', { a: u.costCentre })}
        {u.totalMembers !== null && t(' · {a} people here, {b} with sub-units', { a: u.members, b: u.totalMembers })}
      </p>
      <ErrorText error={error} />

      <div className="card">
        <h3>{t('Head')}</h3>
        {(['HEAD', 'DEPUTY'] as const).map((role) => (
          <HeadRow key={role} unit={u} role={role} head={current(role)} levels={levels} editable={manage && u.open} onSaved={load} />
        ))}
        {data.heads.some((h) => h.validTo !== null) && (
          <details>
            <summary>{t('Earlier heads')}</summary>
            <table>
              <thead><tr><th>{t('Person')}</th><th>{t('Role')}</th><th>{t('From')}</th><th>{t('To')}</th></tr></thead>
              <tbody>{data.heads.filter((h) => h.validTo !== null).map((h) => (
                <tr key={h.id}><td>{h.userName}</td><td>{headTitle(u, h, levels)}</td><td>{h.validFrom}</td><td>{h.validTo}</td></tr>
              ))}</tbody>
            </table>
          </details>
        )}
      </div>

      {children.length > 0 && (
        <div className="card">
          <h3>{t('Sub-units')}</h3>
          {children.map((c) => (
            <p key={c.id}><Link to={`/org/units/${c.id}`}>{c.name}</Link> <span className="muted">{c.levelName} · {c.head ? c.head.userName : 'vacant'}{!c.open && t(' · closed')}</span></p>
          ))}
        </div>
      )}

      {data.members && (
        <div className="card">
          <h3>{t('People (')}{data.members.length})</h3>
          {data.members.length === 0 && <p className="muted">{t('Nobody works directly in this unit.')}</p>}
          {data.members.length > 0 && (
            <table>
              <thead><tr><th>{t('Name')}</th><th>{t('Registration')}</th><th>{t('Since')}</th><th>{t('Reports to')}</th></tr></thead>
              <tbody>{data.members.map((m) => (
                <tr key={m.userId}><td><Link to={`/users/${m.userId}`}>{m.name}</Link></td><td>{m.registrationNo}</td><td>{m.since}</td>
                  <td>{m.reportsToName ?? <span className="muted">{t('nobody')}</span>}</td></tr>
              ))}</tbody>
            </table>
          )}
        </div>
      )}

      {manage && u.open && (
        <div className="row">
          <Button className="secondary" onClick={() => setMode('edit')}>{t('Edit')}</Button>
          <Button className="secondary" onClick={() => setMode('child')}>{t('Add a sub-unit')}</Button>
          {u.parentId !== null && <Button className="danger" onClick={() => setMode('close')}>{t('Close this unit')}</Button>}
        </div>
      )}
      {mode === 'edit' && <UnitForm units={units} levels={levels} unit={u} onCancel={() => setMode('')}
        onSaved={() => { setMode(''); load() }} />}
      {mode === 'child' && <UnitForm units={units} levels={levels} parentId={u.id} onCancel={() => setMode('')}
        onSaved={(created) => navigate(`/org/units/${created.id}`)} />}
      {mode === 'close' && <CloseForm unit={u} onCancel={() => setMode('')} onSaved={() => { setMode(''); load() }} />}
    </section>
  )
}

function HeadRow({ unit, role, head, levels, editable, onSaved }: {
  unit: OrgUnit; role: 'HEAD' | 'DEPUTY'; head: OrgHead | null; levels: OrgLevel[]; editable: boolean; onSaved: () => void
}) {
  const [open, setOpen] = useState(false)
  const [query, setQuery] = useState('')
  const [results, setResults] = useState<User[]>([])
  const [form, setForm] = useState({ userId: 0, name: '', interim: false, title: '', from: today() })
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (query.trim().length < 2) { setResults([]); return }
    const tItem = setTimeout(() => {
      api<{ items: User[] }>(`/api/users?q=${encodeURIComponent(query.trim())}&size=8`).then((p) => setResults(p.items)).catch(() => setResults([]))
    }, 250)
    return () => clearTimeout(tItem)
  }, [query])

  const save = async (e: FormEvent) => {
    e.preventDefault()
    setError(null)
    try {
      await api(`/api/org/units/${unit.id}/head`, { method: 'PUT', body: { userId: form.userId, role, interim: form.interim, title: form.title || null, from: form.from } })
      setOpen(false)
      setQuery('')
      onSaved()
    } catch (err) { setError(message(err)) }
  }
  const end = async () => {
    setError(null)
    try {
      await api(`/api/org/units/${unit.id}/head/end`, { method: 'POST', body: { role, on: today() } })
      onSaved()
    } catch (err) { setError(message(err)) }
  }

  const label = role === 'HEAD' ? t('Head') : t('Deputy')
  return (
    <div>
      <p>
        <span className="muted">{label}: </span>
        {head ? <><strong>{head.userName}</strong> <span className="muted">{headTitle(unit, head, levels)}{' '}{t('since')}{' '}{head.validFrom}</span></>
          : <span className="muted">{role === 'HEAD' ? t('Vacant. Its people report to the nearest head above.') : t('None')}</span>}
        {editable && !open && <> <Button className="secondary void" onClick={() => setOpen(true)}>{head ? t('Replace') : t('Appoint')}</Button></>}
        {editable && head && !open && <> <Button className="secondary void" onClick={() => end()}>{t('End today')}</Button></>}
      </p>
      {open && (
        <Modal title={t('{a} the {b}', { a: head ? 'Replace' : 'Appoint', b: label.toLowerCase() })} size="md" onClose={() => setOpen(false)}>
          <Form onSubmit={(e) => save(e)}>
            {form.userId ? (
              <p>{form.name} <Button type="button" className="secondary void" onClick={() => setForm({ ...form, userId: 0, name: '' })}>{t('Change')}</Button></p>
            ) : (
              <>
                <input placeholder={t('Search by name or registration number')} value={query} onChange={(e) => setQuery(e.target.value)} autoFocus />
                {results.map((r) => (
                  <p key={r.id}><Button type="button" className="secondary void" onClick={() => setForm({ ...form, userId: r.id, name: `${r.firstName} ${r.lastName}` })}>{t('Choose')}</Button> {r.firstName} {r.lastName} <span className="muted">{r.registrationNo}</span></p>
                ))}
              </>
            )}
            <div className="grid3">
              <label>{t('From')}<input type="date" required value={form.from} onChange={(e) => setForm({ ...form, from: e.target.value })} /></label>
              <label>{t('Title (optional)')}<input maxLength={100} placeholder={levels.find((l) => l.id === unit.levelId)?.headTitle} value={form.title} onChange={(e) => setForm({ ...form, title: e.target.value })} /></label>
              <label className="check"><input type="checkbox" checked={form.interim} onChange={(e) => setForm({ ...form, interim: e.target.checked })} />{t('Ad interim (a.i.)')}</label>
            </div>
            <p className="hint">{t('One person can head several units. The previous')}{' '}{label.toLowerCase()}{t('\'s term ends the day before.')}</p>
            <div className="modal-foot"><Button className="secondary" onClick={() => setOpen(false)}>{t('Cancel')}</Button><Button type="submit" disabled={!form.userId}>{t('Save')}</Button></div>
            <ErrorText error={error} />
          </Form>
        </Modal>
      )}
      {!open && <ErrorText error={error} />}
    </div>
  )
}

export function UnitForm({ units, levels, unit, parentId, onSaved, onCancel }: {
  units: OrgUnit[]; levels: OrgLevel[]; unit?: OrgUnit; parentId?: number; onSaved: (u: OrgUnit) => void; onCancel: () => void
}) {
  const [form, setForm] = useState({
    code: unit?.code ?? '', name: unit?.name ?? '', levelId: unit ? String(unit.levelId) : '',
    parentId: unit?.parentId ? String(unit.parentId) : parentId ? String(parentId) : '', costCentre: unit?.costCentre ?? '', validFrom: today(),
  })
  const [error, setError] = useState<string | null>(null)
  const parent = units.find((x) => String(x.id) === form.parentId)
  const allowed = levels.filter((l) => l.active && (parent ? l.staff || l.rank > parent.rank : l.rank === 1))

  const save = async (e: FormEvent) => {
    e.preventDefault()
    setError(null)
    const body = { code: form.code, name: form.name, levelId: Number(form.levelId), parentId: form.parentId ? Number(form.parentId) : null,
      costCentre: form.costCentre || null, validFrom: form.validFrom }
    try {
      onSaved(await api<OrgUnit>(unit ? `/api/org/units/${unit.id}` : '/api/org/units', { method: unit ? 'PUT' : 'POST', body }))
    } catch (err) { setError(message(err)) }
  }

  return (
    <Modal title={unit ? t('Edit {a}', { a: unit.name }) : t('New unit')} size="lg" onClose={onCancel}>
    <Form onSubmit={(e) => save(e)}>
      <div className="grid2">
        <label>{t('Code')}<input required maxLength={32} pattern="[A-Za-z0-9_.\-]+" disabled={!!unit} value={form.code} onChange={(e) => setForm({ ...form, code: e.target.value.toUpperCase() })} />
          <span className="hint">{t('Short and unique, for example DAF or SPAIE.')}</span></label>
        <label>{t('Name')}<input required maxLength={150} value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} /></label>
        <label>{t('Above it')}<UnitSelect units={units} value={form.parentId} exclude={unit?.id} onChange={(v) => setForm({ ...form, parentId: v })} /></label>
        <label>{t('Level')}<select required value={form.levelId} onChange={(e) => setForm({ ...form, levelId: e.target.value })}>
            <option value="">{t('Choose a level')}</option>
            {allowed.map((l) => <option key={l.id} value={l.id}>{l.name}</option>)}
          </select>
          <span className="hint">{t('Only levels below the unit above are offered; a level may be skipped.')}</span></label>
        <label>{t('Cost centre (optional)')}<input maxLength={40} value={form.costCentre} onChange={(e) => setForm({ ...form, costCentre: e.target.value })} /></label>
        {!unit && <label>{t('Opens on')}<input type="date" required value={form.validFrom} onChange={(e) => setForm({ ...form, validFrom: e.target.value })} /></label>}
      </div>
      <ErrorText error={error} />
      <div className="modal-foot"><Button className="secondary" onClick={onCancel}>{t('Cancel')}</Button><Button type="submit">{t('Save')}</Button></div>
    </Form>
    </Modal>
  )
}

function CloseForm({ unit, onSaved, onCancel }: { unit: OrgUnit; onSaved: () => void; onCancel: () => void }) {
  const [on, setOn] = useState(today())
  const [error, setError] = useState<string | null>(null)
  const save = async (e: FormEvent) => {
    e.preventDefault()
    setError(null)
    try {
      await api(`/api/org/units/${unit.id}/close`, { method: 'POST', body: { on } })
      onSaved()
    } catch (err) { setError(message(err)) }
  }
  return (
    <Modal title={t('Close {a}', { a: unit.name })} size="sm" onClose={onCancel}>
    <Form onSubmit={(e) => save(e)}>
      <p className="hint">{t('Move its people and sub-units elsewhere first. Closing ends the head\'s and deputy\'s terms; the history stays.')}</p>
      <label>{t('Last day')}<input type="date" required value={on} onChange={(e) => setOn(e.target.value)} /></label>
      <ErrorText error={error} />
      <div className="modal-foot"><Button className="secondary" onClick={onCancel}>{t('Cancel')}</Button><Button type="submit" className="danger">{t('Close unit')}</Button></div>
    </Form>
    </Modal>
  )
}
