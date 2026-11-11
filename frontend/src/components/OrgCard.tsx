import { useEffect, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { ApiError, api, day } from '../api'
import { useAuth } from '../auth'
import { flatten, REASON, unitPath, type OrgAssignment, type OrgBoss, type OrgHead, type OrgUnit } from '../org'
import { ErrorText } from './ErrorText'
import { Button, Form } from './Button'
import { Modal } from './Modal'
import { t } from '../i18n'
import { DateInput } from './DateFields'

/** A select of the open units, indented by depth. */
export function UnitSelect({ units, value, onChange, required, exclude, placeholder = t('Choose a unit') }: {
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
      setError(err instanceof ApiError ? [err.message, ...Object.values(err.fieldErrors)].join(' ') : t('Could not save.'))
    }
  }

  const current = info?.history.find((a) => a.validTo === null)
  return (
    <div className="card">
      <h3>{t('Organization')}</h3>
      {current ? (
        <p><Link to={`/org/units/${current.unitId}`}>{current.unitName}</Link> <span className="muted">{t('since')}{' '}{day(current.validFrom)}</span>
          <br /><span className="small muted">{unitPath(units, current.unitId)}</span></p>
      ) : <p className="muted">{t('No unit.')}</p>}
      <p>{t('Reports to:')}{' '}{info?.manager ? <><strong>{info.manager.userName}</strong> <span className="muted">{info.manager.manual ? t('(set by hand)') : t('(head of the unit)')}</span></> : <span className="muted">{t('nobody (top of the structure)')}</span>}</p>
      {info && info.heads.length > 0 && (
        <p>{t('Heads:')}{' '}{info.heads.map((h, i) => (
          <span key={h.id}>{i > 0 && ', '}<Link to={`/org/units/${h.unitId}`}>{units.find((u) => u.id === h.unitId)?.name ?? `#${h.unitId}`}</Link>
            {h.role === 'DEPUTY' && t(' (deputy)')}{h.interim && ' (a.i.)'}</span>
        ))}</p>
      )}
      {info && info.history.length > 1 && (
        <details>
          <summary>{t('History (')}{info.history.length})</summary>
          <table>
            <thead><tr><th>{t('Unit')}</th><th>{t('From')}</th><th>{t('To')}</th><th>{t('Why')}</th></tr></thead>
            <tbody>{info.history.map((a) => (
              <tr key={a.id}><td>{a.unitName}</td><td>{day(a.validFrom)}</td><td>{a.validTo ? day(a.validTo) : '—'}</td><td>{REASON[a.reason]}{a.note && <div className="hint">{a.note}</div>}</td></tr>
            ))}</tbody>
          </table>
        </details>
      )}
      {editable && !move && (
        <div className="row">
          {can('salary.write') && <Link className="button secondary" to={`/users/${userId}/file?move=1`}>{t('Promote or transfer')}</Link>}
          <Button className="secondary" onClick={() => setMove({ unitId: '', from: today(), reason: 'REORGANISATION', note: '' })}>{t('Reorganisation or correction')}</Button>
        </div>
      )}
      {move && (
        <Modal title={t('Reorganisation or correction')} size="md" onClose={() => setMove(null)}>
          <Form onSubmit={(e) => save(e)}>
            <div className="grid3">
              <label>{t('New unit')}<UnitSelect units={units} value={move.unitId} required exclude={current?.unitId} onChange={(v) => setMove({ ...move, unitId: v })} /></label>
              <label>{t('From')}<DateInput required value={move.from} onChange={(e) => setMove({ ...move, from: e.target.value })} /></label>
              <label>{t('Why')}<select value={move.reason} onChange={(e) => setMove({ ...move, reason: e.target.value })}>
                  {['REORGANISATION', 'CORRECTION'].map((r) => <option key={r} value={r}>{REASON[r]}</option>)}
                </select>
              </label>
            </div>
            <p className="hint">{t('A transfer or a promotion goes through Promote or transfer, which writes the note de mutation or the avenant to sign.')}</p>
            {move.reason === 'CORRECTION' && <p className="hint">{t('A correction fixes the current unit without adding a line to the history.')}</p>}
            <label>{t('Note (optional)')}<input maxLength={300} value={move.note} onChange={(e) => setMove({ ...move, note: e.target.value })} /></label>
            <ErrorText error={error} />
            <div className="modal-foot"><Button className="secondary" onClick={() => setMove(null)}>{t('Cancel')}</Button><Button type="submit">{t('Save')}</Button></div>
          </Form>
        </Modal>
      )}
      <ErrorText error={error} />
    </div>
  )
}
