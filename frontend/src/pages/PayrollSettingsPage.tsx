import { useEffect, useState } from 'react'
import { ApiError, api, type GradeCategory, type Grade, type Occupation } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'
import { Button } from '../components/Button'
import { t } from '../i18n'

type CategoryWithRaise = GradeCategory & {
  anniversaryRaisePercent?: number; raiseTiming?: 'ANNIVERSARY_MONTH' | 'NEXT_MONTH'
  noticeBase: number; noticeBaseUnit: 'DAYS' | 'MONTHS'; noticeDaysPerYear: number; noticeDayKind: 'WORKING' | 'CALENDAR'; noticeResignationShare: number
}

export default function PayrollSettingsPage() {
  const { can } = useAuth()
  const editable = can('masterdata.manage')
  const [categories, setCategories] = useState<CategoryWithRaise[]>([])
  const [grades, setGrades] = useState<Grade[]>([])
  const [occupations, setOccupations] = useState<Occupation[]>([])
  const [newGrade, setNewGrade] = useState({ categoryId: '', code: '', name: '', baseSalary: '', currency: 'USD' })
  const [newOccupation, setNewOccupation] = useState({ code: '', name: '' })
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)

  const load = () => {
    api<CategoryWithRaise[]>('/api/leave/categories').then(setCategories).catch((e: Error) => setError(e.message))
    api<Grade[]>('/api/grades').then(setGrades).catch((e: Error) => setError(e.message))
    api<Occupation[]>('/api/occupations').then(setOccupations).catch((e: Error) => setError(e.message))
  }
  useEffect(load, [])

  const run = async (action: () => Promise<unknown>, done: string) => {
    setError(null)
    setMessage(null)
    try {
      await action()
      setMessage(done)
      load()
      return true
    } catch (e) {
      setError(e instanceof ApiError ? [e.message, ...Object.values(e.fieldErrors)].join(' ') : t('Something went wrong.'))
      return false
    }
  }
  const setGrade = (id: number, patch: Partial<Grade>) => setGrades(grades.map((g) => (g.id === id ? { ...g, ...patch } : g)))
  const setCategory = (id: number, patch: Partial<CategoryWithRaise>) => setCategories(categories.map((c) => (c.id === id ? { ...c, ...patch } : c)))
  const setOcc = (id: number, patch: Partial<Occupation>) => setOccupations(occupations.map((o) => (o.id === id ? { ...o, ...patch } : o)))

  return (
    <section>
      <h2>{t('Grades and occupations')}</h2>
      <p className="muted">{t('Sample values to start with: replace them with your collective agreement\'s grade table.')}</p>
      <ErrorText error={error} />
      {message && <p className="success">{message}</p>}

      <div className="card">
        <h3>{t('Anniversary raise by grade category')}</h3>
        <p className="hint">{t('Added each contract anniversary for permanent staff, as a percentage of the grade\'s base salary. HR gets a monthly email listing the raises.')}</p>
        <div className="table-wrap">
          <table>
            <thead><tr><th>{t('Category')}</th><th>{t('Raise (%)')}</th><th>{t('Starts')}</th><th></th></tr></thead>
            <tbody>
              {categories.map((c) => (
                <tr key={c.id}>
                  <td>{c.name}</td>
                  <td><input type="number" min={0} max={100} step={0.25} className="narrow-input" disabled={!editable}
                    value={c.anniversaryRaisePercent ?? 0} onChange={(e) => setCategory(c.id, { anniversaryRaisePercent: Number(e.target.value) })} /></td>
                  <td>
                    <select value={c.raiseTiming ?? 'NEXT_MONTH'} disabled={!editable} onChange={(e) => setCategory(c.id, { raiseTiming: e.target.value as CategoryWithRaise['raiseTiming'] })}>
                      <option value="ANNIVERSARY_MONTH">{t('1st of the anniversary month')}</option>
                      <option value="NEXT_MONTH">{t('1st of the following month')}</option>
                    </select>
                  </td>
                  <td>{editable && <Button className="secondary" onClick={() => run(() => api(`/api/grade-categories/${c.id}/raise`, { method: 'PUT', body: { percent: c.anniversaryRaisePercent ?? 0, timing: c.raiseTiming ?? 'NEXT_MONTH' } }), t("{a} saved.", { a: c.code }))}>{t('Save')}</Button>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      <div className="card">
        <h3>{t('Notice period by grade category')}</h3>
        <p className="hint">{t('A base plus days per full year of service; a person who resigns owes the share given here. DRC: Labour Code art. 64 and arrêté of 26 October 2005. A collective agreement can only give more.')}</p>
        <div className="table-wrap">
          <table>
            <thead><tr><th>{t('Category')}</th><th>{t('Base')}</th><th>{t('Per year of service')}</th><th>{t('Days counted')}</th><th>{t('Owed on resignation')}</th><th></th></tr></thead>
            <tbody>
              {categories.map((c) => (
                <tr key={c.id}>
                  <td>{c.name}</td>
                  <td><div className="inline-pair">
                    <input type="number" min={0} max={365} step={c.noticeBaseUnit === 'MONTHS' ? 1 : 0.5} className="narrow-input" disabled={!editable}
                      value={c.noticeBase} onChange={(e) => setCategory(c.id, { noticeBase: Number(e.target.value) })} />{' '}
                    <select className="auto-width" value={c.noticeBaseUnit} disabled={!editable} onChange={(e) => setCategory(c.id, { noticeBaseUnit: e.target.value as CategoryWithRaise['noticeBaseUnit'] })}>
                      <option value="DAYS">{t('days')}</option>
                      <option value="MONTHS">{t('months')}</option>
                    </select>
                  </div></td>
                  <td><div className="inline-pair"><input type="number" min={0} max={60} step={0.5} className="narrow-input" disabled={!editable}
                    value={c.noticeDaysPerYear} onChange={(e) => setCategory(c.id, { noticeDaysPerYear: Number(e.target.value) })} /> <span>{t('days')}</span></div></td>
                  <td>
                    <select className="auto-width" value={c.noticeDayKind} disabled={!editable} onChange={(e) => setCategory(c.id, { noticeDayKind: e.target.value as CategoryWithRaise['noticeDayKind'] })}>
                      <option value="WORKING">{t('Working days')}</option>
                      <option value="CALENDAR">{t('Calendar days')}</option>
                    </select>
                  </td>
                  <td>
                    <select className="auto-width" value={String(c.noticeResignationShare)} disabled={!editable} onChange={(e) => setCategory(c.id, { noticeResignationShare: Number(e.target.value) })}>
                      <option value="0.5">{t('Half')}</option>
                      <option value="1">{t('All of it')}</option>
                    </select>
                  </td>
                  <td>{editable && <Button className="secondary" onClick={() => run(() => api(`/api/grade-categories/${c.id}/notice`, { method: 'PUT', body: {
                    base: c.noticeBase, baseUnit: c.noticeBaseUnit, daysPerYear: c.noticeDaysPerYear, dayKind: c.noticeDayKind, resignationShare: c.noticeResignationShare } }),
                  t("{a} saved.", { a: c.code }))}>{t('Save')}</Button>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      <div className="card">
        <h3>{t('Grades')}</h3>
        <div className="table-wrap">
          <table>
            <thead><tr><th>{t('Code')}</th><th>{t('Name')}</th><th>{t('Category')}</th><th>{t('Base salary')}</th><th>{t('Currency')}</th><th title={t('Coefficient on the SMIG for the legal minimum of the grade (DRC Décret 25/22, 100 for the lowest)')}>{t('SMIG coefficient')}</th><th>{t('Active')}</th><th></th></tr></thead>
            <tbody>
              {grades.map((g) => (
                <tr key={g.id}>
                  <td>{g.code}</td>
                  <td><input value={g.name} disabled={!editable} onChange={(e) => setGrade(g.id, { name: e.target.value })} /></td>
                  <td>
                    <select value={g.categoryId} disabled={!editable} onChange={(e) => setGrade(g.id, { categoryId: Number(e.target.value) })}>
                      {categories.map((c) => <option key={c.id} value={c.id}>{c.code}</option>)}
                    </select>
                  </td>
                  <td><input type="number" min={0} step={0.01} className="narrow-input" disabled={!editable} value={g.baseSalary} onChange={(e) => setGrade(g.id, { baseSalary: Number(e.target.value) })} /></td>
                  <td>
                    <select value={g.currency} disabled={!editable} onChange={(e) => setGrade(g.id, { currency: e.target.value as Grade['currency'] })}>
                      <option>{t('USD')}</option><option>{t('CDF')}</option>
                    </select>
                  </td>
                  <td><input type="number" min={1} step={1} className="narrow-input" disabled={!editable} value={g.smigCoefficient ?? ''} placeholder="—" onChange={(e) => setGrade(g.id, { smigCoefficient: e.target.value === '' ? null : Number(e.target.value) })} /></td>
                  <td><input type="checkbox" checked={g.active} disabled={!editable} onChange={(e) => setGrade(g.id, { active: e.target.checked })} /></td>
                  <td>{editable && <Button className="secondary" onClick={() => run(() => api(`/api/grades/${g.id}`, { method: 'PUT', body: g }), t("{a} saved.", { a: g.code }))}>{t('Save')}</Button>}</td>
                </tr>
              ))}
              {editable && (
                <tr>
                  <td><input className="narrow-input" placeholder="G4A" value={newGrade.code} onChange={(e) => setNewGrade({ ...newGrade, code: e.target.value.toUpperCase() })} /></td>
                  <td><input placeholder={t('Name')} value={newGrade.name} onChange={(e) => setNewGrade({ ...newGrade, name: e.target.value })} /></td>
                  <td>
                    <select value={newGrade.categoryId} onChange={(e) => setNewGrade({ ...newGrade, categoryId: e.target.value })}>
                      <option value="">{t('Category')}</option>
                      {categories.map((c) => <option key={c.id} value={c.id}>{c.code}</option>)}
                    </select>
                  </td>
                  <td><input type="number" min={0} step={0.01} className="narrow-input" value={newGrade.baseSalary} onChange={(e) => setNewGrade({ ...newGrade, baseSalary: e.target.value })} /></td>
                  <td>
                    <select value={newGrade.currency} onChange={(e) => setNewGrade({ ...newGrade, currency: e.target.value })}><option>{t('USD')}</option><option>{t('CDF')}</option></select>
                  </td>
                  <td></td>
                  <td></td>
                  <td><Button onClick={() => run(async () => {
                    await api('/api/grades', { method: 'POST', body: { ...newGrade, categoryId: Number(newGrade.categoryId), baseSalary: Number(newGrade.baseSalary), active: true } })
                    setNewGrade({ categoryId: '', code: '', name: '', baseSalary: '', currency: 'USD' })
                  }, t('Grade added.'))}>{t('Add')}</Button></td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
        <p className="hint">{t('Changing a grade\'s base salary does not change anyone\'s current basic; it is the default for new contracts and the base for anniversary raises.')}</p>
      </div>

      <div className="card">
        <h3>{t('Occupations')}</h3>
        <div className="table-wrap">
          <table>
            <thead><tr><th>{t('Code')}</th><th>{t('Name')}</th><th>{t('Active')}</th><th></th></tr></thead>
            <tbody>
              {occupations.map((o) => (
                <tr key={o.id}>
                  <td>{o.code}</td>
                  <td><input value={o.name} disabled={!editable} onChange={(e) => setOcc(o.id, { name: e.target.value })} /></td>
                  <td><input type="checkbox" checked={o.active} disabled={!editable} onChange={(e) => setOcc(o.id, { active: e.target.checked })} /></td>
                  <td>{editable && <Button className="secondary" onClick={() => run(() => api(`/api/occupations/${o.id}`, { method: 'PUT', body: o }), t("{a} saved.", { a: o.name }))}>{t('Save')}</Button>}</td>
                </tr>
              ))}
              {editable && (
                <tr>
                  <td><input className="narrow-input" placeholder="CODE" value={newOccupation.code} onChange={(e) => setNewOccupation({ ...newOccupation, code: e.target.value.toUpperCase() })} /></td>
                  <td><input placeholder={t('Name')} value={newOccupation.name} onChange={(e) => setNewOccupation({ ...newOccupation, name: e.target.value })} /></td>
                  <td></td>
                  <td><Button onClick={() => run(async () => {
                    await api('/api/occupations', { method: 'POST', body: { ...newOccupation, active: true } })
                    setNewOccupation({ code: '', name: '' })
                  }, t('Occupation added.'))}>{t('Add')}</Button></td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </div>
    </section>
  )
}
