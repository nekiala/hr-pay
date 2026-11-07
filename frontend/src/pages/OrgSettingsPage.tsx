import { useEffect, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { ApiError, api } from '../api'
import { ErrorText } from '../components/ErrorText'
import { UnitSelect } from '../components/OrgCard'
import { REASON, type OrgLevel, type OrgMember, type OrgUnit } from '../org'
import { UnitForm } from './OrgUnitPage'

const message = (err: unknown) => err instanceof ApiError ? [err.message, ...Object.values(err.fieldErrors)].join(' ') : 'Something went wrong.'
type LevelForm = { id: number | null; code: string; name: string; rank: string; headTitle: string; staff: boolean; active: boolean }

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
      setImported(`${r.created} units created, ${r.updated} updated, ${r.headsSet} heads appointed.`)
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
      setMoved(`${r.moved} people moved.`)
      setPicked([])
      load()
    } catch (err) { setError({ bulk: message(err) }) }
  }

  const source = members.filter((m) => String(m.unitId) === bulk.fromUnit)
  return (
    <section className="narrow">
      <p className="small muted"><Link to="/org">Org chart</Link></p>
      <h2>Organizational structure</h2>

      <div className="card">
        <h3>Levels</h3>
        <p className="hint">A unit sits under a unit of a higher level (smaller number); levels can be skipped. Staff units, such as a Cellule, can sit anywhere.</p>
        <table>
          <thead><tr><th>Rank</th><th>Code</th><th>Name</th><th>Head's title</th><th></th></tr></thead>
          <tbody>{levels.map((l) => (
            <tr key={l.id} className={l.active ? '' : 'muted'}>
              <td>{l.rank}</td><td>{l.code}</td><td>{l.name}{l.staff && ' (staff)'}{!l.active && ' · not used'}</td><td>{l.headTitle}</td>
              <td className="actions"><button className="secondary void" onClick={() => setLevel({ ...l, rank: String(l.rank) })}>Edit</button></td>
            </tr>
          ))}</tbody>
        </table>
        {!level && <button className="secondary" onClick={() => setLevel({ id: null, code: '', name: '', rank: '', headTitle: '', staff: false, active: true })}>Add a level</button>}
        {level && (
          <form className="sub-form" onSubmit={(e) => void saveLevel(e)}>
            <div className="grid3">
              <label>Code<input required maxLength={32} disabled={!!level.id} value={level.code} onChange={(e) => setLevel({ ...level, code: e.target.value.toUpperCase() })} /></label>
              <label>Name<input required maxLength={100} value={level.name} onChange={(e) => setLevel({ ...level, name: e.target.value })} /></label>
              <label>Rank<input type="number" min={1} max={20} required value={level.rank} onChange={(e) => setLevel({ ...level, rank: e.target.value })} /></label>
              <label>Head's title<input required maxLength={100} placeholder="Chef de service" value={level.headTitle} onChange={(e) => setLevel({ ...level, headTitle: e.target.value })} /></label>
              <label className="check"><input type="checkbox" checked={level.staff} onChange={(e) => setLevel({ ...level, staff: e.target.checked })} />Staff unit (can sit anywhere)</label>
              <label className="check"><input type="checkbox" checked={level.active} onChange={(e) => setLevel({ ...level, active: e.target.checked })} />In use</label>
            </div>
            <div className="row"><button type="submit">Save level</button><button type="button" className="secondary" onClick={() => setLevel(null)}>Cancel</button></div>
          </form>
        )}
        <ErrorText error={error.levels ?? null} />
      </div>

      {newUnit ? <UnitForm units={units} levels={levels} onCancel={() => setNewUnit(false)} onSaved={() => { setNewUnit(false); load() }} />
        : <p><button className="secondary" onClick={() => setNewUnit(true)}>Add a unit</button></p>}

      <div className="card">
        <h3>Spreadsheet</h3>
        <p><a className="button secondary" href="/api/org/units.csv">Download the structure (CSV)</a></p>
        <p className="hint">Columns: code, name, level, parent, cost_centre, head_registration_no. Level and parent are codes. Commas or semicolons both work.
          Units are matched by code: new codes are created, existing ones updated. Nothing is saved if any line has a problem.</p>
        <label>Import
          <input type="file" accept=".csv,text/csv" onChange={(e) => { const f = e.target.files?.[0]; if (f) void f.text().then(setCsv) }} />
        </label>
        <textarea rows={6} placeholder="Or paste the CSV here" value={csv} onChange={(e) => setCsv(e.target.value)} />
        <button disabled={!csv.trim()} onClick={() => void importCsv()}>Import</button>
        {imported && <p className="success">{imported}</p>}
        <ErrorText error={error.csv ?? null} />
      </div>

      <form className="card" onSubmit={(e) => void moveAll(e)}>
        <h3>Move several people</h3>
        <div className="grid2">
          <label>From unit<UnitSelect units={units} value={bulk.fromUnit} onChange={(v) => { setBulk({ ...bulk, fromUnit: v }); setPicked([]) }} /></label>
          <label>To unit<UnitSelect units={units} value={bulk.unitId} required exclude={Number(bulk.fromUnit) || undefined} onChange={(v) => setBulk({ ...bulk, unitId: v })} /></label>
        </div>
        {bulk.fromUnit && source.length === 0 && <p className="muted">Nobody works directly in this unit.</p>}
        {source.length > 0 && (
          <fieldset>
            <legend><label className="check"><input type="checkbox" checked={picked.length === source.length}
              onChange={(e) => setPicked(e.target.checked ? source.map((m) => m.userId) : [])} />Everyone ({source.length})</label></legend>
            {source.map((m) => (
              <label key={m.userId} className="check"><input type="checkbox" checked={picked.includes(m.userId)}
                onChange={() => setPicked(picked.includes(m.userId) ? picked.filter((x) => x !== m.userId) : [...picked, m.userId])} />
                {m.name} <span className="muted">{m.registrationNo}</span></label>
            ))}
          </fieldset>
        )}
        <div className="grid3">
          <label>From<input type="date" required value={bulk.from} onChange={(e) => setBulk({ ...bulk, from: e.target.value })} /></label>
          <label>Why
            <select value={bulk.reason} onChange={(e) => setBulk({ ...bulk, reason: e.target.value })}>
              {['REORGANISATION', 'TRANSFER', 'PROMOTION', 'CORRECTION'].map((r) => <option key={r} value={r}>{REASON[r]}</option>)}
            </select></label>
          <label>Note (optional)<input maxLength={300} value={bulk.note} onChange={(e) => setBulk({ ...bulk, note: e.target.value })} /></label>
        </div>
        <button type="submit" disabled={picked.length === 0 || !bulk.unitId}>Move {picked.length || ''}</button>
        {moved && <p className="success">{moved}</p>}
        <ErrorText error={error.bulk ?? null} />
      </form>
    </section>
  )
}
