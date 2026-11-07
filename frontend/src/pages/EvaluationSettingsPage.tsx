import { useEffect, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { ApiError, FREQUENCY, api, type EvalCriterion, type EvalTemplate, type GradeCategory } from '../api'
import { ErrorText } from '../components/ErrorText'
import { UnitSelect } from '../components/OrgCard'
import { unitPath, type OrgUnit } from '../org'

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
    } catch (e) {
      setError(e instanceof ApiError ? [e.message, ...Object.values(e.fieldErrors)].join(' ') : 'Something went wrong.')
    }
  }

  const saveCriterion = (ev: FormEvent) => {
    ev.preventDefault()
    if (!criterion) return
    const body = { ...criterion, description: criterion.description || null }
    void run(async () => {
      await api(criterion.id ? `/api/evaluation-criteria/${criterion.id}` : '/api/evaluation-criteria', { method: criterion.id ? 'PUT' : 'POST', body })
      setCriterion(null)
    }, 'Criterion saved.')
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
    void run(async () => {
      await api(template.id ? `/api/evaluation-templates/${template.id}` : '/api/evaluation-templates', { method: template.id ? 'PUT' : 'POST', body })
      setTemplate(null)
    }, 'Template saved.')
  }

  const total = template ? Object.values(template.weights).reduce((s, w) => s + (Number(w) || 0), 0) : 0
  const categoryName = (id: number | null) => (id === null ? 'Everyone' : categories.find((c) => c.id === id)?.name ?? `#${id}`)

  return (
    <section>
      <p><Link to="/evaluations">← Evaluations</Link></p>
      <h2>Evaluation criteria and templates</h2>
      <ErrorText error={error} />
      {message && <p className="success">{message}</p>}

      <div className="card">
        <h3>Templates</h3>
        <p className="hint">A template says how often people are evaluated and how much each criterion weighs. Weights must add up to 100. One active template per grade category and unit. The most specific applies: the person's unit (or the nearest unit above with a template), then their grade category, then “Everyone”.</p>
        <div className="table-wrap">
          <table>
            <thead><tr><th>Name</th><th>How often</th><th>For</th><th>Unit</th><th>Criteria</th><th>Active</th><th></th></tr></thead>
            <tbody>
              {templates.map((t) => (
                <tr key={t.id}>
                  <td>{t.name}</td>
                  <td>{FREQUENCY[t.frequency]}</td>
                  <td>{categoryName(t.gradeCategoryId)}</td>
                  <td>{t.orgUnitId === null ? 'All units' : unitPath(units, t.orgUnitId)}</td>
                  <td className="small">{t.criteria.map((c) => `${c.name} ${c.weight}%`).join(', ')}</td>
                  <td>{t.active ? 'Yes' : 'No'}</td>
                  <td><button className="secondary" onClick={() => setTemplate({
                    id: t.id, name: t.name, frequency: t.frequency, active: t.active, gradeCategoryId: t.gradeCategoryId === null ? '' : String(t.gradeCategoryId),
                    orgUnitId: t.orgUnitId === null ? '' : String(t.orgUnitId),
                    weights: Object.fromEntries(t.criteria.map((c) => [c.criterionId, String(c.weight)])),
                  })}>Edit</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {!template && <button onClick={() => setTemplate(blankTemplate)}>New template</button>}
        {template && (
          <form onSubmit={saveTemplate} className="sub-form">
            <h4>{template.id ? `Edit ${template.name}` : 'New template'}</h4>
            <div className="grid3">
              <label>Name<input value={template.name} required maxLength={150} onChange={(e) => setTemplate({ ...template, name: e.target.value })} /></label>
              <label>How often
                <select value={template.frequency} onChange={(e) => setTemplate({ ...template, frequency: e.target.value as EvalTemplate['frequency'] })}>
                  {Object.entries(FREQUENCY).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
                </select>
              </label>
              <label>For
                <select value={template.gradeCategoryId} onChange={(e) => setTemplate({ ...template, gradeCategoryId: e.target.value })}>
                  <option value="">Everyone</option>
                  {categories.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
                </select>
              </label>
              <label>Unit
                <UnitSelect units={units} value={template.orgUnitId} placeholder="All units"
                  onChange={(v) => setTemplate({ ...template, orgUnitId: v })} />
              </label>
            </div>
            <table>
              <thead><tr><th>Criterion</th><th>Weight %</th></tr></thead>
              <tbody>
                {criteria.filter((c) => c.active || template.weights[c.id]).map((c) => (
                  <tr key={c.id}>
                    <td>{c.name}{c.source === 'ATTENDANCE' && <span className="hint"> (from attendance)</span>}</td>
                    <td><input type="number" className="narrow-input" min={0} max={100} step={0.01} value={template.weights[c.id] ?? ''}
                      onChange={(e) => setTemplate({ ...template, weights: { ...template.weights, [c.id]: e.target.value } })} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
            <p className={total === 100 ? 'success' : 'notice'}>Total: {total}%{total !== 100 && ' (must be 100)'}</p>
            <label className="check"><input type="checkbox" checked={template.active} onChange={(e) => setTemplate({ ...template, active: e.target.checked })} /> Active</label>
            <div className="row">
              <button type="submit">Save</button>
              <button type="button" className="secondary" onClick={() => setTemplate(null)}>Cancel</button>
            </div>
          </form>
        )}
      </div>

      <div className="card">
        <h3>Criteria</h3>
        <p className="hint">The manager scores each criterion from 0 to 100. A criterion “from attendance” is scored by the app: 100, minus 5 per late day and 10 per absent day in the period.</p>
        <div className="table-wrap">
          <table>
            <thead><tr><th>Code</th><th>Name</th><th>Scored by</th><th>Active</th><th></th></tr></thead>
            <tbody>
              {criteria.map((c) => (
                <tr key={c.id}>
                  <td>{c.code}</td>
                  <td>{c.name}{c.description && <div className="hint">{c.description}</div>}</td>
                  <td>{c.source === 'ATTENDANCE' ? 'Attendance' : 'Manager'}</td>
                  <td>{c.active ? 'Yes' : 'No'}</td>
                  <td><button className="secondary" onClick={() => setCriterion({ id: c.id, code: c.code, name: c.name, description: c.description ?? '', source: c.source, active: c.active })}>Edit</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {!criterion && <button onClick={() => setCriterion(blankCriterion)}>New criterion</button>}
        {criterion && (
          <form onSubmit={saveCriterion} className="sub-form">
            <h4>{criterion.id ? `Edit ${criterion.code}` : 'New criterion'}</h4>
            <div className="grid3">
              <label>Code<input value={criterion.code} disabled={!!criterion.id} required pattern="[A-Za-z0-9_-]{1,32}" onChange={(e) => setCriterion({ ...criterion, code: e.target.value.toUpperCase() })} /></label>
              <label>Name<input value={criterion.name} required maxLength={150} onChange={(e) => setCriterion({ ...criterion, name: e.target.value })} /></label>
              <label>Scored by
                <select value={criterion.source} onChange={(e) => setCriterion({ ...criterion, source: e.target.value as CriterionForm['source'] })}>
                  <option value="MANAGER">Manager</option><option value="ATTENDANCE">Attendance</option>
                </select>
              </label>
            </div>
            <label>Description (optional)<input value={criterion.description} maxLength={400} onChange={(e) => setCriterion({ ...criterion, description: e.target.value })} /></label>
            {criterion.id && <label className="check"><input type="checkbox" checked={criterion.active} onChange={(e) => setCriterion({ ...criterion, active: e.target.checked })} /> Active</label>}
            <div className="row">
              <button type="submit">Save</button>
              <button type="button" className="secondary" onClick={() => setCriterion(null)}>Cancel</button>
            </div>
          </form>
        )}
      </div>
    </section>
  )
}
