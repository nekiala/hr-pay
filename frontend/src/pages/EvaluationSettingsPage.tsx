import { useEffect, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { ApiError, FREQUENCY, api, type EvalCriterion, type EvalTemplate, type GradeCategory } from '../api'
import { ErrorText } from '../components/ErrorText'
import { UnitSelect } from '../components/OrgCard'
import { unitPath, type OrgUnit } from '../org'
import { Button, Form } from '../components/Button'
import { Modal } from '../components/Modal'
import { t } from '../i18n'

type CriterionForm = { id?: number; code: string; name: string; description: string; source: 'MANAGER' | 'ATTENDANCE'; active: boolean }
type TemplateForm = { id?: number; name: string; frequency: EvalTemplate['frequency']; gradeCategoryId: string; orgUnitId: string; active: boolean; weights: Record<number, string> }

const blankCriterion: CriterionForm = { code: '', name: '', description: '', source: 'MANAGER', active: true }
const blankTemplate: TemplateForm = { name: '', frequency: 'QUARTERLY', gradeCategoryId: '', orgUnitId: '', active: true, weights: {} }

export default function EvaluationSettingsPage() {
  const [criteria, setCriteria] = useState<EvalCriterion[]>([])
  const [templates, setTemplates] = useState<EvalTemplate[]>([])
  const [categories, setCategories] = useState<GradeCategory[]>([])
  const [units, setUnits] = useState<OrgUnit[]>([])
  const [criterion, setCriterion] = useState<CriterionForm | null>(null)
  const [template, setTemplate] = useState<TemplateForm | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)

  const load = () => {
    api<EvalCriterion[]>('/api/evaluation-criteria').then(setCriteria).catch((e: Error) => setError(e.message))
    api<EvalTemplate[]>('/api/evaluation-templates').then(setTemplates).catch((e: Error) => setError(e.message))
  }
  useEffect(() => {
    load()
    api<GradeCategory[]>('/api/leave/categories').then(setCategories).catch(() => undefined)
    api<OrgUnit[]>('/api/org/units').then(setUnits).catch(() => undefined)
  }, [])

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

  const saveCriterion = (ev: FormEvent) => {
    ev.preventDefault()
    if (!criterion) return
    const body = { ...criterion, description: criterion.description || null }
    return run(async () => {
      await api(criterion.id ? `/api/evaluation-criteria/${criterion.id}` : '/api/evaluation-criteria', { method: criterion.id ? 'PUT' : 'POST', body })
      setCriterion(null)
    }, t('Criterion saved.'))
  }

  const saveTemplate = (ev: FormEvent) => {
    ev.preventDefault()
    if (!template) return
    const body = {
      name: template.name, frequency: template.frequency, active: template.active,
      gradeCategoryId: template.gradeCategoryId === '' ? null : Number(template.gradeCategoryId),
      orgUnitId: template.orgUnitId === '' ? null : Number(template.orgUnitId),
      criteria: Object.entries(template.weights).filter(([, w]) => w !== '' && Number(w) > 0).map(([id, w]) => ({ criterionId: Number(id), weight: Number(w) })),
    }
    return run(async () => {
      await api(template.id ? `/api/evaluation-templates/${template.id}` : '/api/evaluation-templates', { method: template.id ? 'PUT' : 'POST', body })
      setTemplate(null)
    }, t('Template saved.'))
  }

  const total = template ? Object.values(template.weights).reduce((s, w) => s + (Number(w) || 0), 0) : 0
  const categoryName = (id: number | null) => (id === null ? t('Everyone') : categories.find((c) => c.id === id)?.name ?? `#${id}`)

  return (
    <section>
      <p><Link to="/evaluations">{t('← Evaluations')}</Link></p>
      <h2>{t('Evaluation criteria and templates')}</h2>
      <ErrorText error={error} />
      {message && <p className="success">{message}</p>}

      <div className="card">
        <h3>{t('Templates')}</h3>
        <p className="hint">{t('A template says how often people are evaluated and how much each criterion weighs. Weights must add up to 100. One active template per grade category and unit. The most specific applies: the person\'s unit (or the nearest unit above with a template), then their grade category, then “Everyone”.')}</p>
        <div className="table-wrap">
          <table>
            <thead><tr><th>{t('Name')}</th><th>{t('How often')}</th><th>{t('For')}</th><th>{t('Unit')}</th><th>{t('Criteria')}</th><th>{t('Active')}</th><th></th></tr></thead>
            <tbody>
              {templates.map((tItem) => (
                <tr key={tItem.id}>
                  <td>{tItem.name}</td>
                  <td>{FREQUENCY[tItem.frequency]}</td>
                  <td>{categoryName(tItem.gradeCategoryId)}</td>
                  <td>{tItem.orgUnitId === null ? t('All units') : unitPath(units, tItem.orgUnitId)}</td>
                  <td className="small">{tItem.criteria.map((c) => `${c.name} ${c.weight}%`).join(', ')}</td>
                  <td>{tItem.active ? t('Yes') : t('No')}</td>
                  <td><Button className="secondary" onClick={() => setTemplate({
                    id: tItem.id, name: tItem.name, frequency: tItem.frequency, active: tItem.active, gradeCategoryId: tItem.gradeCategoryId === null ? '' : String(tItem.gradeCategoryId),
                    orgUnitId: tItem.orgUnitId === null ? '' : String(tItem.orgUnitId),
                    weights: Object.fromEntries(tItem.criteria.map((c) => [c.criterionId, String(c.weight)])),
                  })}>{t('Edit')}</Button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {!template && <Button onClick={() => setTemplate(blankTemplate)}>{t('New template')}</Button>}
        {template && (
          <Modal title={template.id ? t('Edit {a}', { a: template.name }) : t('New template')} size="lg" onClose={() => setTemplate(null)}>
            <Form onSubmit={saveTemplate}>
              <div className="grid3">
                <label>{t('Name')}<input value={template.name} required maxLength={150} onChange={(e) => setTemplate({ ...template, name: e.target.value })} /></label>
                <label>{t('How often')}<select value={template.frequency} onChange={(e) => setTemplate({ ...template, frequency: e.target.value as EvalTemplate['frequency'] })}>
                    {Object.entries(FREQUENCY).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
                  </select>
                </label>
                <label>{t('For')}<select value={template.gradeCategoryId} onChange={(e) => setTemplate({ ...template, gradeCategoryId: e.target.value })}>
                    <option value="">{t('Everyone')}</option>
                    {categories.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
                  </select>
                </label>
                <label>{t('Unit')}<UnitSelect units={units} value={template.orgUnitId} placeholder={t('All units')}
                    onChange={(v) => setTemplate({ ...template, orgUnitId: v })} />
                </label>
              </div>
              <table>
                <thead><tr><th>{t('Criterion')}</th><th>{t('Weight %')}</th></tr></thead>
                <tbody>
                  {criteria.filter((c) => c.active || template.weights[c.id]).map((c) => (
                    <tr key={c.id}>
                      <td>{c.name}{c.source === 'ATTENDANCE' && <span className="hint">{' '}{t('(from attendance)')}</span>}</td>
                      <td><input type="number" className="narrow-input" min={0} max={100} step={0.01} value={template.weights[c.id] ?? ''}
                        onChange={(e) => setTemplate({ ...template, weights: { ...template.weights, [c.id]: e.target.value } })} /></td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <p className={total === 100 ? 'success' : 'notice'}>{t('Total:')}{' '}{total}%{total !== 100 && t(' (must be 100)')}</p>
              <label className="check"><input type="checkbox" checked={template.active} onChange={(e) => setTemplate({ ...template, active: e.target.checked })} />{' '}{t('Active')}</label>
              <div className="modal-foot"><Button className="secondary" onClick={() => setTemplate(null)}>{t('Cancel')}</Button><Button type="submit">{t('Save')}</Button></div>
  
            </Form>
          </Modal>
        )}
      </div>

      <div className="card">
        <h3>{t('Criteria')}</h3>
        <p className="hint">{t('The manager scores each criterion from 0 to 100. A criterion “from attendance” is scored by the app: 100, minus 5 per late day and 10 per absent day in the period.')}</p>
        <div className="table-wrap">
          <table>
            <thead><tr><th>{t('Code')}</th><th>{t('Name')}</th><th>{t('Scored by')}</th><th>{t('Active')}</th><th></th></tr></thead>
            <tbody>
              {criteria.map((c) => (
                <tr key={c.id}>
                  <td>{c.code}</td>
                  <td>{c.name}{c.description && <div className="hint">{c.description}</div>}</td>
                  <td>{c.source === 'ATTENDANCE' ? t('Attendance') : t('Manager')}</td>
                  <td>{c.active ? t('Yes') : t('No')}</td>
                  <td><Button className="secondary" onClick={() => setCriterion({ id: c.id, code: c.code, name: c.name, description: c.description ?? '', source: c.source, active: c.active })}>{t('Edit')}</Button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {!criterion && <Button onClick={() => setCriterion(blankCriterion)}>{t('New criterion')}</Button>}
        {criterion && (
          <Modal title={criterion.id ? t('Edit {a}', { a: criterion.code }) : t('New criterion')} size="lg" onClose={() => setCriterion(null)}>
            <Form onSubmit={saveCriterion}>
              <div className="grid3">
                <label>{t('Code')}<input value={criterion.code} disabled={!!criterion.id} required pattern="[A-Za-z0-9_-]{1,32}" onChange={(e) => setCriterion({ ...criterion, code: e.target.value.toUpperCase() })} /></label>
                <label>{t('Name')}<input value={criterion.name} required maxLength={150} onChange={(e) => setCriterion({ ...criterion, name: e.target.value })} /></label>
                <label>{t('Scored by')}<select value={criterion.source} onChange={(e) => setCriterion({ ...criterion, source: e.target.value as CriterionForm['source'] })}>
                    <option value="MANAGER">{t('Manager')}</option><option value="ATTENDANCE">{t('Attendance')}</option>
                  </select>
                </label>
              </div>
              <label>{t('Description (optional)')}<input value={criterion.description} maxLength={400} onChange={(e) => setCriterion({ ...criterion, description: e.target.value })} /></label>
              {criterion.id && <label className="check"><input type="checkbox" checked={criterion.active} onChange={(e) => setCriterion({ ...criterion, active: e.target.checked })} />{' '}{t('Active')}</label>}
              <div className="modal-foot"><Button className="secondary" onClick={() => setCriterion(null)}>{t('Cancel')}</Button><Button type="submit">{t('Save')}</Button></div>
  
            </Form>
          </Modal>
        )}
      </div>
    </section>
  )
}
