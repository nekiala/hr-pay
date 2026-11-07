import { useEffect, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { ApiError, api } from '../api'
import { useAuth } from '../auth'
import { flatten, REASON, unitPath, type OrgAssignment, type OrgBoss, type OrgHead, type OrgUnit } from '../org'
import { ErrorText } from './ErrorText'
import { Button, Form } from './Button'
import { Modal } from './Modal'

/** A select of the open units, indented by depth. */
export function UnitSelect({ units, value, onChange, required, exclude, placeholder = 'Choose a unit' }: {
  units: OrgUnit[]; value: string; onChange: (v: string) => void; required?: boolean; exclude?: number; placeholder?: string
}) {
  return (
    <select value={value} required={required} onChange={(e) => onChange(e.target.value)}>
      <option value="">{placeholder}</option>
      {flatten(units.filter((u) => u.open)).filter((f) => f.unit.id !== exclude).map(({ unit, depth }) => (
        <option key={unit.id} value={unit.id}>{'  '.repeat(depth)}{unit.name} ({unit.levelName})</option>
      ))}
    </select>
  )
}

type Info = { history: OrgAssignment[]; manager: OrgBoss | null; heads: OrgHead[] }
const today = () => new Date().toISOString().slice(0, 10)

/** Where an employee sits in the structure, who they report to, and the moves over time. */
export function OrgCard({ userId }: { userId: number }) {
  const { can } = useAuth()
  const editable = can('org.manage')
  const [info, setInfo] = useState<Info | null>(null)
  const [units, setUnits] = useState<OrgUnit[]>([])
  const [move, setMove] = useState<{ unitId: string; from: string; reason: string; note: string } | null>(null)
  const [error, setError] = useState<string | null>(null)

  const load = () => api<Info>(`/api/org/users/${userId}/assignments`).then(setInfo).catch((e: Error) => setError(e.message))
  useEffect(() => {
    void load()
    api<OrgUnit[]>('/api/org/units').then(setUnits).catch(() => undefined)
  }, [userId]) // eslint-disable-line react-hooks/exhaustive-deps

  const save = async (e: FormEvent) => {
    e.preventDefault()
    if (!move) return
    setError(null)
    try {
      await api(`/api/org/users/${userId}/assignments`, { method: 'POST', body: { unitId: Number(move.unitId), from: move.from, reason: move.reason, note: move.note || null } })
      setMove(null)
      await load()
    } catch (err) {
      setError(err instanceof ApiError ? [err.message, ...Object.values(err.fieldErrors)].join(' ') : 'Could not save.')
    }
  }

  const current = info?.history.find((a) => a.validTo === null)
  return (
    <div className="card">
      <h3>Organization</h3>
      {current ? (
        <p><Link to={`/org/units/${current.unitId}`}>{current.unitName}</Link> <span className="muted">since {current.validFrom}</span>
          <br /><span className="small muted">{unitPath(units, current.unitId)}</span></p>
      ) : <p className="muted">No unit.</p>}
      <p>Reports to: {info?.manager ? <><strong>{info.manager.userName}</strong> <span className="muted">{info.manager.manual ? '(set by hand)' : '(head of the unit)'}</span></> : <span className="muted">nobody (top of the structure)</span>}</p>
      {info && info.heads.length > 0 && (
        <p>Heads: {info.heads.map((h, i) => (
          <span key={h.id}>{i > 0 && ', '}<Link to={`/org/units/${h.unitId}`}>{units.find((u) => u.id === h.unitId)?.name ?? `#${h.unitId}`}</Link>
            {h.role === 'DEPUTY' && ' (deputy)'}{h.interim && ' (a.i.)'}</span>
        ))}</p>
      )}
      {info && info.history.length > 1 && (
        <details>
          <summary>History ({info.history.length})</summary>
          <table>
            <thead><tr><th>Unit</th><th>From</th><th>To</th><th>Why</th></tr></thead>
            <tbody>{info.history.map((a) => (
              <tr key={a.id}><td>{a.unitName}</td><td>{a.validFrom}</td><td>{a.validTo ?? '—'}</td><td>{REASON[a.reason]}{a.note && <div className="hint">{a.note}</div>}</td></tr>
            ))}</tbody>
          </table>
        </details>
      )}
      {editable && !move && <Button className="secondary" onClick={() => setMove({ unitId: '', from: today(), reason: 'TRANSFER', note: '' })}>Move to another unit</Button>}
      {move && (
        <Modal title="Move to another unit" size="md" onClose={() => setMove(null)}>
          <Form onSubmit={(e) => save(e)}>
            <div className="grid3">
              <label>New unit<UnitSelect units={units} value={move.unitId} required exclude={current?.unitId} onChange={(v) => setMove({ ...move, unitId: v })} /></label>
              <label>From<input type="date" required value={move.from} onChange={(e) => setMove({ ...move, from: e.target.value })} /></label>
              <label>Why
                <select value={move.reason} onChange={(e) => setMove({ ...move, reason: e.target.value })}>
                  {['TRANSFER', 'PROMOTION', 'REORGANISATION', 'CORRECTION'].map((r) => <option key={r} value={r}>{REASON[r]}</option>)}
                </select>
              </label>
            </div>
            {move.reason === 'CORRECTION' && <p className="hint">A correction fixes the current unit without adding a line to the history.</p>}
            <label>Note (optional)<input maxLength={300} value={move.note} onChange={(e) => setMove({ ...move, note: e.target.value })} /></label>
            <ErrorText error={error} />
            <div className="modal-foot"><Button className="secondary" onClick={() => setMove(null)}>Cancel</Button><Button type="submit">Save</Button></div>
          </Form>
        </Modal>
      )}
      <ErrorText error={error} />
    </div>
  )
}
