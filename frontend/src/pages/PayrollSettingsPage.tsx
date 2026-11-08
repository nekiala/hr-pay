import { useEffect, useState } from 'react'
import { ApiError, api, money, type GradeCategory, type Grade, type Occupation } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'
import { Button } from '../components/Button'
import { Modal } from '../components/Modal'
import { t } from '../i18n'

type LegalMinimum = {
  from: string; smigDaily: number; usdLocal: number; rateDate: string; applied: boolean
  grades: { gradeId: number; code: string; currency: string; coefficient: number; minimumLocal: number; oldBase: number; newBase: number }[]
  people: { userId: number; name: string; registrationNo: string | null; oldGrade: string; oldBasic: number; oldComplement: number; net: number
    newGrade: string | null; newBasic: number | null; newComplement: number | null; newNet: number | null; currency: string; problem: string | null }[]
}

const firstOfMonth = () => { const d = new Date(); return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-01` }

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
  const [minimumFrom, setMinimumFrom] = useState(firstOfMonth())
  const [minimum, setMinimum] = useState<LegalMinimum | null>(null)
  const [minimumBusy, setMinimumBusy] = useState(false)
  const [minimumError, setMinimumError] = useState<string | null>(null)
  const legalMinimum = async (apply: boolean) => {
    setMinimumBusy(true)
    setMinimumError(null)
    try {
      const r = await api<LegalMinimum>(`/api/grades/legal-minimum?from=${minimumFrom}`, apply ? { method: 'POST' } : {})
      setMinimum(r)
      if (apply) load()
    } catch (e) {
      setMinimumError(e instanceof Error ? e.message : t('Something went wrong.'))
    } finally {
      setMinimumBusy(false)
    }
  }

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
        {editable && can('salary.write') && grades.some((g) => g.smigCoefficient != null) && (
          <div className="row">
            <Button className="secondary" onClick={() => { setMinimumError(null); setMinimum({ from: minimumFrom, smigDaily: 0, usdLocal: 0, rateDate: '', applied: false, grades: [], people: [] }); void legalMinimum(false) }}>
              {t('Raise grades to the legal minimum…')}
            </Button>
          </div>
        )}
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
      {minimum && (
        <Modal size="lg" title={t('Raise grades to the legal minimum')} onClose={() => setMinimum(null)}
          footer={minimum.applied ? <Button onClick={() => setMinimum(null)}>{t('Close')}</Button> : (
            <>
              <Button className="secondary" onClick={() => setMinimum(null)}>{t('Cancel')}</Button>
              <Button disabled={minimumBusy || minimum.grades.length === 0} onClick={() => legalMinimum(true)}>{t('Apply')}</Button>
            </>
          )}>
          <p className="hint">{t('Each grade base below SMIG x coefficient x 26 days (Décret 25/22) is raised. Each person on a raised grade keeps the same net: the basic becomes the new base and the complement shrinks; when the net is too low for the new base, the person moves to the nearest grade below whose base fits, with a complement for the rest.')}</p>
          <label>{t('From')}<input type="date" value={minimumFrom} disabled={minimum.applied} onChange={(e) => setMinimumFrom(e.target.value)} onBlur={() => !minimum.applied && legalMinimum(false)} /></label>
          <ErrorText error={minimumError} />
          {minimumBusy && <p className="muted">{t('Working it out…')}</p>}
          {!minimumBusy && minimum.smigDaily > 0 && (
            <>
              {minimum.applied && <p className="success">{t('Applied from {a}.', { a: minimum.from })}</p>}
              <p className="muted">{t('SMIG {a} FC a day; {b} FC to the dollar ({c}).', { a: minimum.smigDaily, b: minimum.usdLocal, c: minimum.rateDate })}</p>
              {minimum.grades.length === 0 ? <p>{t('Every grade is at or above its legal minimum.')}</p> : (
                <div className="table-wrap">
                  <table>
                    <thead><tr><th>{t('Grade')}</th><th>{t('Coefficient')}</th><th>{t('Legal minimum')}</th><th>{t('Base now')}</th><th>{t('New base')}</th></tr></thead>
                    <tbody>{minimum.grades.map((g) => (
                      <tr key={g.gradeId}><td>{g.code}</td><td>{g.coefficient}</td><td>{money(g.minimumLocal, 'CDF')}</td><td>{money(g.oldBase, g.currency)}</td><td><strong>{money(g.newBase, g.currency)}</strong></td></tr>
                    ))}</tbody>
                  </table>
                </div>
              )}
              {minimum.people.length > 0 && (
                <div className="table-wrap">
                  <table>
                    <thead><tr><th>{t('Employee')}</th><th>{t('Grade')}</th><th>{t('Basic')}</th><th>{t('Complement')}</th><th>{t('Net')}</th><th></th></tr></thead>
                    <tbody>{minimum.people.map((p) => (
                      <tr key={p.userId}>
                        <td>{p.name}{p.registrationNo && <span className="muted"> · {p.registrationNo}</span>}</td>
                        <td>{p.oldGrade}{p.newGrade && p.newGrade !== p.oldGrade ? ` → ${p.newGrade}` : ''}</td>
                        <td>{money(p.oldBasic, p.currency)}{p.newBasic != null && ` → ${money(p.newBasic, p.currency)}`}</td>
                        <td>{money(p.oldComplement, p.currency)}{p.newComplement != null && ` → ${money(p.newComplement, p.currency)}`}</td>
                        <td>{money(p.net, p.currency)}{p.newNet != null && ` → ${money(p.newNet, p.currency)}`}</td>
                        <td>{p.problem && <span className="badge warn">{p.problem}</span>}</td>
                      </tr>
                    ))}</tbody>
                  </table>
                </div>
              )}
            </>
          )}
        </Modal>
      )}
    </section>
  )
}
