import { useEffect, useState, type FormEvent } from 'react'
import { ApiError, api, money, type Contract, type Grade } from '../api'
import { Button, Form } from './Button'
import { ErrorText } from './ErrorText'
import { Modal } from './Modal'
import { t } from '../i18n'
import { DateInput } from './DateFields'

/**
 * Moves an employee to another grade. "change": from a date, the contract going on under the new grade (a
 * promotion or a reclassification). "correct": fixes a grade typed wrongly on the latest contract.
 */
export function GradeChangeModal({ userId, contract, mode, onDone, onClose }: {
  userId: number | string; contract: Contract; mode: 'change' | 'correct'; onDone: (c: Contract) => void; onClose: () => void
}) {
  const [grades, setGrades] = useState<Grade[]>([])
  const [categoryId, setCategoryId] = useState(String(contract.categoryId))
  const [gradeId, setGradeId] = useState('')
  const firstOfNext = () => { const d = new Date(); return new Date(d.getFullYear(), d.getMonth() + 1, 1, 12).toISOString().slice(0, 10) }
  const [from, setFrom] = useState(firstOfNext())
  const [keepNet, setKeepNet] = useState(false)
  const [note, setNote] = useState('')
  const [error, setError] = useState<string | null>(null)
  useEffect(() => { api<Grade[]>('/api/grades').then(setGrades).catch(() => undefined) }, [])

  const categories = [...new Map(grades.filter((g) => g.active).map((g) => [g.categoryId, g.categoryName])).entries()]
  const inCategory = grades.filter((g) => g.active && g.categoryId === Number(categoryId))
  const chosen = grades.find((g) => g.id === Number(gradeId))
  const current = grades.find((g) => g.id === contract.gradeId)

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setError(null)
    try {
      const saved = mode === 'change'
        ? await api<Contract>(`/api/users/${userId}/grade-change`, { method: 'POST', body: { gradeId: Number(gradeId), from, keepNet, note: note || null } })
        : await api<Contract>(`/api/users/${userId}/contracts/${contract.id}/grade`, { method: 'PUT', body: { gradeId: Number(gradeId) } })
      onDone(saved)
    } catch (err) {
      setError(err instanceof ApiError ? [err.message, ...Object.values(err.fieldErrors)].join(' ') : t('Could not save.'))
    }
  }

  return (
    <Modal title={mode === 'change' ? t('Change grade') : t('Correct the grade')} size="md" onClose={onClose}>
      <Form onSubmit={submit}>
        <p className="muted">{t('Now:')}{' '}<strong>{contract.gradeCode}</strong> · {contract.gradeName}{current ? ` · ${money(current.baseSalary, current.currency)}` : ''}</p>
        <div className="grid2">
          <label>{t('Grade category')}<select value={categoryId} onChange={(e) => { setCategoryId(e.target.value); setGradeId('') }} required>
              {categories.map(([id, name]) => <option key={id} value={id}>{name}</option>)}
            </select>
          </label>
          <label>{t('Grade')}<select value={gradeId} onChange={(e) => setGradeId(e.target.value)} required>
              <option value="">{t('Choose')}</option>
              {inCategory.filter((g) => g.id !== contract.gradeId).map((g) => <option key={g.id} value={g.id}>{g.code} · {g.name} · {money(g.baseSalary, g.currency)}</option>)}
            </select>
          </label>
        </div>
        {mode === 'change' ? (
          <>
            <div className="grid2">
              <label>{t('From')}<DateInput value={from} onChange={(e) => setFrom(e.target.value)} required /></label>
              <label>{t('Note')}<input value={note} maxLength={300} placeholder={t('For example: promotion')} onChange={(e) => setNote(e.target.value)} /></label>
            </div>
            <label className="check"><input type="checkbox" checked={keepNet} onChange={(e) => setKeepNet(e.target.checked)} />{t('Keep the same net pay (the salary complement absorbs the new basic)')}</label>
            <p className="hint">{t('The contract goes on under the new grade: the current record ends the day before and the basic becomes the grade\'s{a}.', { a: chosen ? ` (${money(chosen.baseSalary, chosen.currency)})` : '' })}{' '}
              {keepNet ? '' : t('The salary complement, if any, stays as it is.')}</p>
          </>
        ) : (
          <p className="hint">{t('For a grade typed wrongly. The contract keeps its dates; the basic recorded at its start follows when it was the old grade\'s. Not possible once a payroll using this contract is approved.')}</p>
        )}
        <ErrorText error={error} />
        <div className="modal-foot"><Button className="secondary" onClick={onClose}>{t('Cancel')}</Button><Button type="submit">{mode === 'change' ? t('Change grade') : t('Correct')}</Button></div>
      </Form>
    </Modal>
  )
}
