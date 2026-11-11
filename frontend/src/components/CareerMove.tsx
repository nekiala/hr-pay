import { useEffect, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { ApiError, CAREER_KINDS, LETTER_STATUS, api, day, money, type CareerKind, type CareerMove, type Contract, type Grade, type Occupation, type Site } from '../api'
import { useAuth } from '../auth'
import type { OrgUnit } from '../org'
import { Button, Form } from './Button'
import { DateInput } from './DateFields'
import { ErrorText } from './ErrorText'
import { Modal } from './Modal'
import { UnitSelect } from './OrgCard'
import { t } from '../i18n'

const firstOfNext = () => { const d = new Date(); return new Date(d.getFullYear(), d.getMonth() + 1, 1, 12).toISOString().slice(0, 10) }

/**
 * Promotes or transfers an employee from a date: a new grade or occupation (an avenant), a new unit or site (a
 * mutation), or both. HR Pay writes the avenant or the note de mutation for the employee to sign (Labour Code art. 36,
 * 44, 55 and 61 ter: the job and the place of work belong to the written contract).
 */
export function CareerMoveModal({ userId, contract, unitId, siteId, onDone, onClose }: {
  userId: number | string; contract: Contract | null; unitId: number | null; siteId: number | null
  onDone: (warnings: string[]) => void; onClose: () => void
}) {
  const [grades, setGrades] = useState<Grade[]>([])
  const [occupations, setOccupations] = useState<Occupation[]>([])
  const [units, setUnits] = useState<OrgUnit[]>([])
  const [sites, setSites] = useState<Site[]>([])
  const [form, setForm] = useState({
    kind: (contract ? 'PROMOTION' : 'TRANSFER') as CareerKind, effectiveOn: firstOfNext(), categoryId: contract ? String(contract.categoryId) : '',
    gradeId: '', occupationId: '', unitId: '', siteId: '', trialMonths: '', keepNet: false, note: '',
  })
  const [error, setError] = useState<string | null>(null)
  useEffect(() => {
    api<Grade[]>('/api/grades').then(setGrades).catch(() => undefined)
    api<Occupation[]>('/api/occupations').then(setOccupations).catch(() => undefined)
    api<OrgUnit[]>('/api/org/units').then(setUnits).catch(() => undefined)
    api<Site[]>('/api/sites').then(setSites).catch(() => undefined)
  }, [])

  const promotion = form.kind === 'PROMOTION' || form.kind === 'PROMOTION_TRANSFER' || form.kind === 'RECLASSIFICATION'
  const transfer = form.kind === 'TRANSFER' || form.kind === 'PROMOTION_TRANSFER' || form.kind === 'RECLASSIFICATION'
  const categories = [...new Map(grades.filter((g) => g.active).map((g) => [g.categoryId, g.categoryName])).entries()]
  const current = grades.find((g) => g.id === contract?.gradeId)
  const chosen = grades.find((g) => g.id === Number(form.gradeId))
  const set = (patch: Partial<typeof form>) => setForm({ ...form, ...patch })

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setError(null)
    try {
      const r = await api<{ move: CareerMove; warnings: string[] }>(`/api/users/${userId}/career`, {
        method: 'POST',
        body: {
          kind: form.kind, effectiveOn: form.effectiveOn, note: form.note || null, keepNet: form.keepNet,
          gradeId: promotion && form.gradeId ? Number(form.gradeId) : null,
          occupationId: promotion && form.occupationId ? Number(form.occupationId) : null,
          unitId: transfer && form.unitId ? Number(form.unitId) : null,
          siteId: transfer && form.siteId ? Number(form.siteId) : null,
          trialMonths: form.kind.startsWith('PROMOTION') && form.trialMonths ? Number(form.trialMonths) : null,
        },
      })
      onDone(r.warnings)
    } catch (err) {
      setError(err instanceof ApiError ? [err.message, ...Object.values(err.fieldErrors)].join(' ') : t('Could not save.'))
    }
  }

  return (
    <Modal title={t('Promote or transfer')} size="lg" onClose={onClose}>
      <Form onSubmit={submit}>
        <div className="grid2">
          <label>{t('Kind of move')}<select value={form.kind} onChange={(e) => set({ kind: e.target.value as CareerKind })}>
              {(Object.keys(CAREER_KINDS) as CareerKind[]).filter((k) => contract || k === 'TRANSFER').map((k) => <option key={k} value={k}>{CAREER_KINDS[k]}</option>)}
            </select>
          </label>
          <label>{t('Takes effect on')}<DateInput value={form.effectiveOn} onChange={(e) => set({ effectiveOn: e.target.value })} required /></label>
        </div>
        {contract && <p className="muted">{t('Now:')}{' '}<strong>{contract.gradeCode}</strong> · {contract.gradeName}{current ? ` · ${money(current.baseSalary, current.currency)}` : ''}{contract.occupationName ? ` · ${contract.occupationName}` : ''}</p>}
        {promotion && contract && (
          <fieldset>
            <legend>{t('Grade and occupation')}</legend>
            <div className="grid3">
              <label>{t('Grade category')}<select value={form.categoryId} onChange={(e) => set({ categoryId: e.target.value, gradeId: '' })}>
                  {categories.map(([id, name]) => <option key={id} value={id}>{name}</option>)}
                </select>
              </label>
              <label>{t('New grade')}<select value={form.gradeId} onChange={(e) => set({ gradeId: e.target.value })}>
                  <option value="">{t('Unchanged')}</option>
                  {grades.filter((g) => g.active && g.categoryId === Number(form.categoryId) && g.id !== contract.gradeId)
                    .map((g) => <option key={g.id} value={g.id}>{g.code} · {g.name} · {money(g.baseSalary, g.currency)}</option>)}
                </select>
              </label>
              <label>{t('New occupation')}<select value={form.occupationId} onChange={(e) => set({ occupationId: e.target.value })}>
                  <option value="">{t('Unchanged')}</option>
                  {occupations.filter((o) => o.active && o.id !== contract.occupationId).map((o) => <option key={o.id} value={o.id}>{o.name}</option>)}
                </select>
              </label>
            </div>
            {chosen && <label className="check"><input type="checkbox" checked={form.keepNet} onChange={(e) => set({ keepNet: e.target.checked })} />{t('Keep the same net pay (the salary complement absorbs the new basic)')}</label>}
            {form.kind !== 'RECLASSIFICATION' && (
              <label>{t('Trial period (months, optional)')}<input type="number" min={0} max={6} value={form.trialMonths} onChange={(e) => set({ trialMonths: e.target.value })} style={{ maxWidth: '8rem' }} />
                <span className="hint">{t('Written in the avenant, 6 months at most (Labour Code art. 43). If it is not conclusive, the employee returns to the previous post.')}</span>
              </label>
            )}
          </fieldset>
        )}
        {transfer && (
          <fieldset>
            <legend>{t('Unit and place of work')}</legend>
            <div className="grid2">
              <label>{t('New unit')}<UnitSelect units={units} value={form.unitId} onChange={(v) => set({ unitId: v })} exclude={unitId ?? undefined} placeholder={t('Unchanged')} /></label>
              <label>{t('New site')}<select value={form.siteId} onChange={(e) => set({ siteId: e.target.value })}>
                  <option value="">{t('Unchanged')}</option>
                  {sites.filter((s) => s.active && s.id !== siteId).map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
                </select>
              </label>
            </div>
            {form.siteId && <p className="hint">{t('A transfer outside the place of employment: the company owes decent housing for the worker and the family, or an allowance (Labour Code art. 138).')}</p>}
          </fieldset>
        )}
        <label>{t('Note')}<input value={form.note} maxLength={300} onChange={(e) => set({ note: e.target.value })} placeholder={t('For example: head of accounts')} /></label>
        <p className="hint">{form.kind === 'RECLASSIFICATION'
          ? t('A reclassification corrects the job without a document to sign.')
          : t('HR Pay writes the {a} for the employee to sign; it stays a draft until you send it. Seniority does not change.', { a: form.kind === 'TRANSFER' ? t('note de mutation') : t('avenant') })}</p>
        <ErrorText error={error} />
        <div className="modal-foot"><Button className="secondary" onClick={onClose}>{t('Cancel')}</Button><Button type="submit">{t('Save the move')}</Button></div>
      </Form>
    </Modal>
  )
}

/** Every promotion and transfer of the employee, the latest first. */
export function CareerHistory({ userId, moves }: { userId: number | string; moves: CareerMove[] }) {
  const { can } = useAuth()
  if (moves.length === 0) return <p className="muted">{t('No promotion or transfer yet.')}</p>
  const change = (from: string | null, to: string | null) => to ? <>{from ? <span className="muted">{from} → </span> : null}{to}</> : null
  return (
    <div className="table-wrap">
      <table>
        <thead><tr><th>{t('From')}</th><th>{t('Kind of move')}</th><th>{t('What changes')}</th><th>{t('Trial')}</th><th>{t('Document')}</th><th>{t('By')}</th></tr></thead>
        <tbody>
          {moves.map((m) => (
            <tr key={m.id}>
              <td>{day(m.effectiveOn)}</td>
              <td>{CAREER_KINDS[m.kind]}{m.note && <div className="hint">{m.note}</div>}</td>
              <td className="small">
                {[change(m.fromGrade, m.toGrade), change(m.fromOccupation, m.toOccupation), change(m.fromUnit, m.toUnit), change(m.fromSite, m.toSite)]
                  .filter(Boolean).map((x, i) => <div key={i}>{x}</div>)}
                {m.keepNet && <div className="hint">{t('Same net pay')}</div>}
              </td>
              <td>{m.trialMonths ? t('{a} months', { a: m.trialMonths }) : '—'}</td>
              <td>{m.letterStatus
                ? can('lifecycle.manage')
                  ? <Link to={`/users/${userId}/lifecycle`} className={LETTER_STATUS[m.letterStatus][1]}>{LETTER_STATUS[m.letterStatus][0]}</Link>
                  : <span className={LETTER_STATUS[m.letterStatus][1]}>{LETTER_STATUS[m.letterStatus][0]}</span>
                : '—'}</td>
              <td className="small muted">{m.createdBy}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
