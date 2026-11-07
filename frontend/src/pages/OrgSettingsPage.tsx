import { useEffect, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { ApiError, api } from '../api'
import { ErrorText } from '../components/ErrorText'
import { UnitSelect } from '../components/OrgCard'
import { REASON, type OrgLevel, type OrgMember, type OrgUnit } from '../org'
import { UnitForm } from './OrgUnitPage'
import { Button, Form } from '../components/Button'
import { Modal } from '../components/Modal'
import { t } from '../i18n'

const message = (err: unknown) => err instanceof ApiError ? [err.message, ...Object.values(err.fieldErrors)].join(' ') : t('Something went wrong.')
type LevelForm = { id: number | null; code: string; name: string; rank: string; headTitle: string; staff: boolean; active: boolean }

/** The units in tree order, each with its depth, so the list reads like the chart. */
function tree(units: OrgUnit[]): { unit: OrgUnit; depth: number }[] {
  const ids = new Set(units.map((u) => u.id))
  const children = (id: number | null) => units.filter((u) => (id === null ? u.parentId === null || !ids.has(u.parentId) : u.parentId === id))
    .sort((a, b) => a.rank - b.rank || a.name.localeCompare(b.name))
  const out: { unit: OrgUnit; depth: number }[] = []
  const walk = (id: number | null, depth: number) => children(id).forEach((u) => { out.push({ unit: u, depth }); walk(u.id, depth + 1) })
  walk(null, 0)
  return out
}

/** HR tools for the structure: the levels, a spreadsheet import and export, and moving several people at once. */
export default function OrgSettingsPage() {
  const [levels, setLevels] = useState<OrgLevel[]>([])
  const [units, setUnits] = useState<OrgUnit[]>([])
  const [members, setMembers] = useState<OrgMember[]>([])
  const [level, setLevel] = useState<LevelForm | null>(null)
  const [newUnit, setNewUnit] = useState(false)
  const [csv, setCsv] = useState('')
  const [imported, setImported] = useState<string | null>(null)
  const [bulk, setBulk] = useState({ fromUnit: '', unitId: '', from: new Date().toISOString().slice(0, 10), reason: 'REORGANISATION', note: '' })
  const [picked, setPicked] = useState<number[]>([])
  const [moved, setMoved] = useState<string | null>(null)
  const [error, setError] = useState<{ levels?: string; csv?: string; bulk?: string }>({})

  const load = () => {
    api<OrgLevel[]>('/api/org/levels').then(setLevels).catch(() => undefined)
    api<OrgUnit[]>('/api/org/units').then(setUnits).catch(() => undefined)
    api<OrgMember[]>('/api/org/people').then(setMembers).catch(() => undefined)
  }
  useEffect(load, [])

  const saveLevel = async (e: FormEvent) => {
    e.preventDefault()
    if (!level) return
    setError({})
    try {
      await api(level.id ? `/api/org/levels/${level.id}` : '/api/org/levels', {
        method: level.id ? 'PUT' : 'POST',
        body: { code: level.code, name: level.name, rank: Number(level.rank), headTitle: level.headTitle, staff: level.staff, active: level.active },
      })
      setLevel(null)
      load()
    } catch (err) { setError({ levels: message(err) }) }
  }

  const importCsv = async () => {
    setError({})
    setImported(null)
    try {
      const r = await api<{ created: number; updated: number; headsSet: number }>('/api/org/units/import', { method: 'POST', body: { csv } })
      setImported(t('{a} units created, {b} updated, {c} heads appointed.', { a: r.created, b: r.updated, c: r.headsSet }))
      setCsv('')
      load()
    } catch (err) { setError({ csv: message(err) }) }
  }

  const moveAll = async (e: FormEvent) => {
    e.preventDefault()
    setError({})
    setMoved(null)
    try {
      const r = await api<{ moved: number }>('/api/org/assignments/bulk', {
        method: 'POST', body: { userIds: picked, unitId: Number(bulk.unitId), from: bulk.from, reason: bulk.reason, note: bulk.note || null },
      })
      setMoved(t('{a} people moved.', { a: r.moved }))
      setPicked([])
      load()
    } catch (err) { setError({ bulk: message(err) }) }
  }

  const source = members.filter((m) => String(m.unitId) === bulk.fromUnit)
  return (
    <section>
      <p className="small muted"><Link to="/org">{t('Org chart')}</Link></p>
      <h2>{t('Organizational structure')}</h2>

      <div className="card">
        <h3>{t('Levels')}</h3>
        <p className="hint">{t('A unit sits under a unit of a higher level (smaller number); levels can be skipped. Staff units, such as a Cellule, can sit anywhere.')}</p>
        <table>
          <thead><tr><th>{t('Rank')}</th><th>{t('Code')}</th><th>{t('Name')}</th><th>{t('Head\'s title')}</th><th></th></tr></thead>
          <tbody>{levels.map((l) => (
            <tr key={l.id} className={l.active ? '' : 'muted'}>
              <td>{l.rank}</td><td>{l.code}</td><td>{l.name}{l.staff && t(' (staff)')}{!l.active && t(' · not used')}</td><td>{l.headTitle}</td>
              <td className="actions"><Button className="secondary void" onClick={() => setLevel({ ...l, rank: String(l.rank) })}>{t('Edit')}</Button></td>
            </tr>
          ))}</tbody>
        </table>
        {!level && <Button className="secondary" onClick={() => setLevel({ id: null, code: '', name: '', rank: '', headTitle: '', staff: false, active: true })}>{t('Add a level')}</Button>}
        {level && (
          <Modal title={level.id ? t('Edit {a}', { a: level.name }) : t('Add a level')} size="lg" onClose={() => setLevel(null)}>
            <Form onSubmit={(e) => saveLevel(e)}>
              <div className="grid3">
                <label>{t('Code')}<input required maxLength={32} disabled={!!level.id} value={level.code} onChange={(e) => setLevel({ ...level, code: e.target.value.toUpperCase() })} /></label>
                <label>{t('Name')}<input required maxLength={100} value={level.name} onChange={(e) => setLevel({ ...level, name: e.target.value })} /></label>
                <label>{t('Rank')}<input type="number" min={1} max={20} required value={level.rank} onChange={(e) => setLevel({ ...level, rank: e.target.value })} /></label>
                <label>{t('Head\'s title')}<input required maxLength={100} placeholder={t('Chef de service')} value={level.headTitle} onChange={(e) => setLevel({ ...level, headTitle: e.target.value })} /></label>
                <label className="check"><input type="checkbox" checked={level.staff} onChange={(e) => setLevel({ ...level, staff: e.target.checked })} />{t('Staff unit (can sit anywhere)')}</label>
                <label className="check"><input type="checkbox" checked={level.active} onChange={(e) => setLevel({ ...level, active: e.target.checked })} />{t('In use')}</label>
              </div>
              <ErrorText error={error.levels ?? null} />
              <div className="modal-foot"><Button className="secondary" onClick={() => setLevel(null)}>{t('Cancel')}</Button><Button type="submit">{t('Save level')}</Button></div>
            </Form>
          </Modal>
        )}
        <ErrorText error={error.levels ?? null} />
      </div>

      <div className="card">
        <h3>{t('Units')}</h3>
        <p className="hint">{t('Open a unit to rename it, move it, name its head or close it.')}</p>
        <div className="table-wrap">
          <table>
            <thead><tr><th>{t('Unit')}</th><th>{t('Code')}</th><th>{t('Level')}</th><th>{t('Head')}</th><th>{t('People')}</th></tr></thead>
            <tbody>
              {units.length === 0 && <tr><td colSpan={5} className="muted">{t('No unit yet.')}</td></tr>}
              {tree(units).map(({ unit: u, depth }) => (
                <tr key={u.id}>
                  <td style={{ paddingLeft: 12 + depth * 18 }}><Link to={`/org/units/${u.id}`}>{u.name}</Link></td>
                  <td><code>{u.code}</code></td><td>{u.levelName}</td>
                  <td>{u.head?.userName ?? <span className="muted">{t('Vacant')}</span>}</td>
                  <td>{u.members ?? 0}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {newUnit ? <UnitForm units={units} levels={levels} onCancel={() => setNewUnit(false)} onSaved={() => { setNewUnit(false); load() }} />
          : <Button className="secondary" onClick={() => setNewUnit(true)}>{t('Add a unit')}</Button>}
      </div>

      <div className="card">
        <h3>{t('Spreadsheet')}</h3>
        <p><a className="button secondary" href="/api/org/units.csv">{t('Download the structure (CSV)')}</a></p>
        <p className="hint">{t('Columns: code, name, level, parent, cost_centre, head_registration_no. Level and parent are codes. Commas or semicolons both work. Units are matched by code: new codes are created, existing ones updated. Nothing is saved if any line has a problem.')}</p>
        <label>{t('Import')}<input type="file" accept=".csv,text/csv" onChange={(e) => { const f = e.target.files?.[0]; if (f) void f.text().then(setCsv) }} />
        </label>
        <textarea rows={6} placeholder={t('Or paste the CSV here')} value={csv} onChange={(e) => setCsv(e.target.value)} />
        <Button disabled={!csv.trim()} onClick={() => importCsv()}>{t('Import')}</Button>
        {imported && <p className="success">{imported}</p>}
        <ErrorText error={error.csv ?? null} />
      </div>

      <Form className="card" onSubmit={(e) => moveAll(e)}>
        <h3>{t('Move several people')}</h3>
        <div className="grid2">
          <label>{t('From unit')}<UnitSelect units={units} value={bulk.fromUnit} onChange={(v) => { setBulk({ ...bulk, fromUnit: v }); setPicked([]) }} /></label>
          <label>{t('To unit')}<UnitSelect units={units} value={bulk.unitId} required exclude={Number(bulk.fromUnit) || undefined} onChange={(v) => setBulk({ ...bulk, unitId: v })} /></label>
        </div>
        {bulk.fromUnit && source.length === 0 && <p className="muted">{t('Nobody works directly in this unit.')}</p>}
        {source.length > 0 && (
          <fieldset>
            <legend><label className="check"><input type="checkbox" checked={picked.length === source.length}
              onChange={(e) => setPicked(e.target.checked ? source.map((m) => m.userId) : [])} />{t('Everyone (')}{source.length})</label></legend>
            {source.map((m) => (
              <label key={m.userId} className="check"><input type="checkbox" checked={picked.includes(m.userId)}
                onChange={() => setPicked(picked.includes(m.userId) ? picked.filter((x) => x !== m.userId) : [...picked, m.userId])} />
                {m.name} <span className="muted">{m.registrationNo}</span></label>
            ))}
          </fieldset>
        )}
        <div className="grid3">
          <label>{t('From')}<input type="date" required value={bulk.from} onChange={(e) => setBulk({ ...bulk, from: e.target.value })} /></label>
          <label>{t('Why')}<select value={bulk.reason} onChange={(e) => setBulk({ ...bulk, reason: e.target.value })}>
              {['REORGANISATION', 'TRANSFER', 'PROMOTION', 'CORRECTION'].map((r) => <option key={r} value={r}>{REASON[r]}</option>)}
            </select></label>
          <label>{t('Note (optional)')}<input maxLength={300} value={bulk.note} onChange={(e) => setBulk({ ...bulk, note: e.target.value })} /></label>
        </div>
        <Button type="submit" disabled={picked.length === 0 || !bulk.unitId}>{t('Move')}{' '}{picked.length || ''}</Button>
        {moved && <p className="success">{moved}</p>}
        <ErrorText error={error.bulk ?? null} />
      </Form>
    </section>
  )
}
