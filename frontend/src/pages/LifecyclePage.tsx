import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import {
  api, CERTIFICATE_KINDS, CHECKLIST_ACTIONS, CHECKLIST_KINDS, LETTER_KINDS, longDay,
  type ChecklistAction, type ChecklistKind, type ChecklistStep, type DocumentType, type LetterKind, type LetterTemplate, type LifecycleOverview,
} from '../api'
import { Button, Form } from '../components/Button'
import { useAsk } from '../components/Dialogs'
import { ErrorText } from '../components/ErrorText'
import { Modal } from '../components/Modal'
import { t } from '../i18n'

type Tab = 'overview' | 'types' | 'steps' | 'templates'

/** Hiring and departures: open checklists, documents about to expire, certificates asked for, letters waiting. */
export default function LifecyclePage() {
  const [tab, setTab] = useState<Tab>('overview')
  const tabs: Record<Tab, string> = { overview: t('To do'), types: t('Document types'), steps: t('Checklist steps'), templates: t('Contracts and letters') }
  return (
    <section>
      <h2>{t('Hiring and departures')}</h2>
      <p className="muted">{t('Checklists for each hiring and departure, the documents kept for each person, letters and contracts signed with a code, and certificates.')}</p>
      <div className="segmented" role="tablist">
        {(Object.keys(tabs) as Tab[]).map((k) => (
          <button key={k} type="button" role="tab" aria-selected={tab === k} className={tab === k ? 'on' : ''} onClick={() => setTab(k)}>{tabs[k]}</button>
        ))}
      </div>
      {tab === 'overview' && <Overview />}
      {tab === 'types' && <DocumentTypes />}
      {tab === 'steps' && <Steps />}
      {tab === 'templates' && <Templates />}
    </section>
  )
}

function Overview() {
  const [data, setData] = useState<LifecycleOverview | null>(null)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => { api<LifecycleOverview>('/api/lifecycle/overview?days=60').then(setData).catch((e: Error) => setError(e.message)) }, [])
  if (!data) return <ErrorText error={error} />
  const person = (userId: number, name: string) => <Link to={`/users/${userId}/lifecycle`}>{name}</Link>
  return (
    <>
      <div className="card">
        <h3>{t('Open checklists')}</h3>
        <p className="small muted">{t('A departure checklist starts by itself when a departure is confirmed. Start a hiring checklist from the person\'s page.')}</p>
        <div className="table-wrap">
          <table>
            <thead><tr><th>{t('Person')}</th><th>{t('Checklist')}</th><th>{t('Due')}</th><th>{t('Progress')}</th></tr></thead>
            <tbody>
              {data.checklists.length === 0 && <tr><td colSpan={4} className="muted">{t('No open checklist.')}</td></tr>}
              {data.checklists.map((c) => (
                <tr key={c.id}>
                  <td>{person(c.userId, c.name)} <span className="muted small">{c.registrationNo}</span></td>
                  <td>{CHECKLIST_KINDS[c.kind]}</td>
                  <td>{longDay(c.dueOn)}</td>
                  <td><div className="row"><div className="progress" aria-hidden><span style={{ width: `${c.total ? (100 * c.done) / c.total : 0}%` }} /></div><span className="small">{c.done}/{c.total}</span></div></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      <div className="cards2">
        <div className="card">
          <h3>{t('Certificates asked for')}</h3>
          {data.certificates.length === 0 ? <p className="muted small">{t('None waiting.')}</p> : (
            <ul className="events">
              {data.certificates.map((c) => <li key={c.id}>{person(c.userId, c.employeeName)} · {CERTIFICATE_KINDS[c.kind]}{c.purpose && <span className="muted"> · {c.purpose}</span>} <span className="muted small">{longDay(c.requestedAt)}</span></li>)}
            </ul>
          )}
        </div>
        <div className="card">
          <h3>{t('Waiting for a signature')}</h3>
          {data.letters.length === 0 ? <p className="muted small">{t('None waiting.')}</p> : (
            <ul className="events">
              {data.letters.map((l) => <li key={l.id}>{person(l.userId, l.employeeName)} · {l.title} <span className="muted small">{t('sent {a}', { a: longDay(l.sentAt) })}</span></li>)}
            </ul>
          )}
        </div>
      </div>

      <div className="cards2">
        <div className="card">
          <h3>{t('Documents expiring within 60 days')}</h3>
          {data.expiring.length === 0 ? <p className="muted small">{t('None.')}</p> : (
            <ul className="events">
              {data.expiring.map((d) => {
                const past = new Date(d.expiresOn) < new Date()
                return <li key={d.id}>{person(d.userId, d.name)} · {d.typeName} <span className={past ? 'badge off' : 'badge warn'}>{past ? t('expired {a}', { a: longDay(d.expiresOn) }) : longDay(d.expiresOn)}</span></li>
              })}
            </ul>
          )}
        </div>
        <div className="card">
          <h3>{t('Equipment still out with people leaving')}</h3>
          {data.equipment.length === 0 ? <p className="muted small">{t('None.')}</p> : (
            <ul className="events">
              {data.equipment.map((e) => <li key={e.id}>{person(e.userId, e.name)} · {e.kind}{e.serialNo && <span className="muted"> · {e.serialNo}</span>}</li>)}
            </ul>
          )}
        </div>
      </div>
    </>
  )
}

// ---- settings ---------------------------------------------------------------------------------------------------

type TypeDraft = Omit<DocumentType, 'id'> & { id?: number }

function DocumentTypes() {
  const [types, setTypes] = useState<DocumentType[]>([])
  const [draft, setDraft] = useState<TypeDraft | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [formError, setFormError] = useState<string | null>(null)
  const load = () => api<DocumentType[]>('/api/lifecycle/document-types').then(setTypes).catch((e: Error) => setError(e.message))
  useEffect(() => { void load() }, [])
  const save = async () => {
    if (!draft) return
    setFormError(null)
    try {
      const { id, ...body } = draft
      await api(id ? `/api/lifecycle/document-types/${id}` : '/api/lifecycle/document-types', { method: id ? 'PUT' : 'POST', body })
      setDraft(null)
      await load()
    } catch (e) { setFormError((e as Error).message) }
  }
  return (
    <div className="card">
      <div className="row between">
        <h3>{t('Document types')}</h3>
        <Button onClick={() => { setFormError(null); setDraft({ code: '', name: '', hasExpiry: false, remindDays: 30, employeeCanUpload: false, employeeCanSee: true, position: types.length + 1, active: true }) }}>{t('Add a type')}</Button>
      </div>
      <ErrorText error={error} />
      <div className="table-wrap">
        <table>
          <thead><tr><th>{t('Name')}</th><th>{t('Code')}</th><th>{t('Expires')}</th><th>{t('Employee')}</th><th>{t('State')}</th><th /></tr></thead>
          <tbody>
            {types.map((d) => (
              <tr key={d.id}>
                <td>{d.name}</td><td><code>{d.code}</code></td>
                <td>{d.hasExpiry ? t('Yes, reminder {a} days before', { a: d.remindDays }) : '—'}</td>
                <td className="small">{[d.employeeCanSee && t('sees it'), d.employeeCanUpload && t('can send it')].filter(Boolean).join(', ') || t('HR only')}</td>
                <td>{d.active ? <span className="badge ok">{t('Active')}</span> : <span className="badge">{t('Off')}</span>}</td>
                <td><Button className="secondary" onClick={() => { setFormError(null); setDraft({ ...d }) }}>{t('Edit')}</Button></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {draft && (
        <Modal title={draft.id ? t('Edit the document type') : t('Add a document type')} onClose={() => setDraft(null)}>
          <Form onSubmit={save}>
            <label>{t('Name')}<input required maxLength={100} value={draft.name} onChange={(e) => setDraft({ ...draft, name: e.target.value })} /></label>
            <label>{t('Code')}<input required maxLength={40} pattern="[A-Za-z0-9_]+" value={draft.code} onChange={(e) => setDraft({ ...draft, code: e.target.value.toUpperCase() })} /></label>
            <label className="check"><input type="checkbox" checked={draft.hasExpiry} onChange={(e) => setDraft({ ...draft, hasExpiry: e.target.checked })} />{t('Has an expiry date (HR and the person are reminded)')}</label>
            {draft.hasExpiry && <label>{t('Remind this many days before')}<input type="number" min={1} max={365} value={draft.remindDays} onChange={(e) => setDraft({ ...draft, remindDays: Number(e.target.value) })} /></label>}
            <label className="check"><input type="checkbox" checked={draft.employeeCanSee} onChange={(e) => setDraft({ ...draft, employeeCanSee: e.target.checked })} />{t('The employee sees these documents')}</label>
            <label className="check"><input type="checkbox" checked={draft.employeeCanUpload} onChange={(e) => setDraft({ ...draft, employeeCanUpload: e.target.checked })} />{t('The employee can send one from the app')}</label>
            <label>{t('Order')}<input type="number" value={draft.position} onChange={(e) => setDraft({ ...draft, position: Number(e.target.value) })} /></label>
            <label className="check"><input type="checkbox" checked={draft.active} onChange={(e) => setDraft({ ...draft, active: e.target.checked })} />{t('Active')}</label>
            <ErrorText error={formError} />
            <div className="modal-foot"><Button type="submit">{t('Save')}</Button></div>
          </Form>
        </Modal>
      )}
    </div>
  )
}

type StepDraft = Omit<ChecklistStep, 'id'> & { id?: number }

function Steps() {
  const ask = useAsk()
  const [steps, setSteps] = useState<ChecklistStep[]>([])
  const [types, setTypes] = useState<DocumentType[]>([])
  const [draft, setDraft] = useState<StepDraft | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [formError, setFormError] = useState<string | null>(null)
  const load = () => api<ChecklistStep[]>('/api/lifecycle/checklist-steps').then(setSteps).catch((e: Error) => setError(e.message))
  useEffect(() => {
    void load()
    api<DocumentType[]>('/api/lifecycle/document-types').then(setTypes).catch(() => undefined)
  }, [])
  const save = async () => {
    if (!draft) return
    setFormError(null)
    try {
      const { id, ...body } = draft
      await api(id ? `/api/lifecycle/checklist-steps/${id}` : '/api/lifecycle/checklist-steps', { method: id ? 'PUT' : 'POST', body })
      setDraft(null)
      await load()
    } catch (e) { setFormError((e as Error).message) }
  }
  const typeName = (id: number | null) => types.find((d) => d.id === id)?.name
  return (
    <>
      {(Object.keys(CHECKLIST_KINDS) as ChecklistKind[]).map((kind) => (
        <div key={kind} className="card">
          <div className="row between">
            <h3>{t('{a} checklist', { a: CHECKLIST_KINDS[kind] })}</h3>
            <Button onClick={() => { setFormError(null); setDraft({ kind, position: steps.filter((s) => s.kind === kind).length + 1, label: '', documentTypeId: null, action: null, active: true }) }}>{t('Add a step')}</Button>
          </div>
          <ErrorText error={error} />
          <ul className="checklist">
            {steps.filter((s) => s.kind === kind).map((s) => (
              <li key={s.id}>
                <span className="muted small">{s.position}</span>
                <span style={{ flex: 1 }}>{s.label}{!s.active && <span className="badge"> {t('Off')}</span>}
                  {s.action && <div className="small muted">{t('Ticks itself: {a}', { a: s.action === 'DOCUMENT' ? `${CHECKLIST_ACTIONS.DOCUMENT} (${typeName(s.documentTypeId) ?? '?'})` : CHECKLIST_ACTIONS[s.action] })}</div>}
                </span>
                <Button className="secondary" onClick={() => { setFormError(null); setDraft({ ...s }) }}>{t('Edit')}</Button>
                <Button className="secondary" onClick={() => ask({
                  title: t('Remove the step {a}?', { a: s.label }), message: t('Checklists already started keep it.'), confirmLabel: t('Remove'), danger: true,
                  onConfirm: async () => { await api(`/api/lifecycle/checklist-steps/${s.id}`, { method: 'DELETE' }); await load() },
                })}>{t('Remove')}</Button>
              </li>
            ))}
          </ul>
        </div>
      ))}
      {draft && (
        <Modal title={draft.id ? t('Edit the step') : t('Add a step')} onClose={() => setDraft(null)}>
          <Form onSubmit={save}>
            <label>{t('Step')}<input required maxLength={200} value={draft.label} onChange={(e) => setDraft({ ...draft, label: e.target.value })} /></label>
            <label>{t('Ticks itself when')}
              <select value={draft.action ?? ''} onChange={(e) => setDraft({ ...draft, action: (e.target.value || null) as ChecklistAction | null, documentTypeId: e.target.value === 'DOCUMENT' ? draft.documentTypeId : null })}>
                <option value="">{t('Never: ticked by hand')}</option>
                {(Object.keys(CHECKLIST_ACTIONS) as ChecklistAction[]).map((a) => <option key={a} value={a}>{CHECKLIST_ACTIONS[a]}</option>)}
              </select>
            </label>
            {draft.action === 'DOCUMENT' && (
              <label>{t('Document')}
                <select required value={draft.documentTypeId ?? ''} onChange={(e) => setDraft({ ...draft, documentTypeId: Number(e.target.value) || null })}>
                  <option value="" disabled>{t('Choose…')}</option>
                  {types.filter((d) => d.active).map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
                </select>
              </label>
            )}
            <label>{t('Order')}<input type="number" value={draft.position} onChange={(e) => setDraft({ ...draft, position: Number(e.target.value) })} /></label>
            <label className="check"><input type="checkbox" checked={draft.active} onChange={(e) => setDraft({ ...draft, active: e.target.checked })} />{t('Active')}</label>
            <ErrorText error={formError} />
            <div className="modal-foot"><Button type="submit">{t('Save')}</Button></div>
          </Form>
        </Modal>
      )}
    </>
  )
}

type TemplateDraft = Omit<LetterTemplate, 'id' | 'updatedBy' | 'updatedAt'> & { id?: number }

function Templates() {
  const [templates, setTemplates] = useState<LetterTemplate[]>([])
  const [fields, setFields] = useState<Record<string, string>>({})
  const [types, setTypes] = useState<DocumentType[]>([])
  const [draft, setDraft] = useState<TemplateDraft | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [formError, setFormError] = useState<string | null>(null)
  const load = () => api<LetterTemplate[]>('/api/lifecycle/templates').then(setTemplates).catch((e: Error) => setError(e.message))
  useEffect(() => {
    void load()
    api<Record<string, string>>('/api/lifecycle/template-fields').then(setFields).catch(() => undefined)
    api<DocumentType[]>('/api/lifecycle/document-types').then(setTypes).catch(() => undefined)
  }, [])
  const save = async () => {
    if (!draft) return
    setFormError(null)
    try {
      const { id, ...body } = draft
      await api(id ? `/api/lifecycle/templates/${id}` : '/api/lifecycle/templates', { method: id ? 'PUT' : 'POST', body })
      setDraft(null)
      await load()
    } catch (e) { setFormError((e as Error).message) }
  }

  if (draft) {
    return (
      <div className="card">
        <h3>{draft.id ? t('Edit the template') : t('New template')}</h3>
        <Form onSubmit={save} className="stack">
          <div className="grid2">
            <label>{t('Name')}<input required maxLength={100} value={draft.name} onChange={(e) => setDraft({ ...draft, name: e.target.value })} /></label>
            <label>{t('Code')}<input required maxLength={40} pattern="[A-Za-z0-9_]+" value={draft.code} onChange={(e) => setDraft({ ...draft, code: e.target.value.toUpperCase() })} /></label>
            <label>{t('Kind')}
              <select value={draft.kind} onChange={(e) => setDraft({ ...draft, kind: e.target.value as LetterKind })}>
                {(Object.keys(LETTER_KINDS) as LetterKind[]).map((k) => <option key={k} value={k}>{LETTER_KINDS[k]}</option>)}
              </select>
            </label>
            <label>{t('Filed under')}
              <select value={draft.documentTypeId ?? ''} onChange={(e) => setDraft({ ...draft, documentTypeId: Number(e.target.value) || null })}>
                <option value="">{draft.kind === 'CONTRACT' ? t('Contracts') : t('Letters')}</option>
                {types.filter((d) => d.active).map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
              </select>
            </label>
          </div>
          <label>{t('Text')}
            <textarea className="template" required maxLength={50_000} value={draft.body} onChange={(e) => setDraft({ ...draft, body: e.target.value })} />
          </label>
          <details>
            <summary className="small">{t('Fields filled in for each person')}</summary>
            <p className="small muted">{t('A blank line starts a new paragraph; a line starting with ## is a heading.')}</p>
            <div className="table-wrap"><table><tbody>
              {Object.entries(fields).map(([k, label]) => <tr key={k}><td><code>{`{{${k}}}`}</code></td><td className="small">{label}</td></tr>)}
            </tbody></table></div>
          </details>
          <label className="check"><input type="checkbox" checked={draft.requiresSignature} onChange={(e) => setDraft({ ...draft, requiresSignature: e.target.checked })} />{t('The employee signs it with a one-time code')}</label>
          <label className="check"><input type="checkbox" checked={draft.active} onChange={(e) => setDraft({ ...draft, active: e.target.checked })} />{t('Active')}</label>
          <ErrorText error={formError} />
          <div className="row">
            <Button type="submit">{t('Save')}</Button>
            <Button className="secondary" onClick={() => setDraft(null)}>{t('Cancel')}</Button>
          </div>
        </Form>
      </div>
    )
  }
  return (
    <div className="card">
      <div className="row between">
        <h3>{t('Contracts and letters')}</h3>
        <Button onClick={() => { setFormError(null); setDraft({ code: '', name: '', kind: 'LETTER', body: '', requiresSignature: true, documentTypeId: null, active: true }) }}>{t('New template')}</Button>
      </div>
      <p className="small muted">{t('The starting texts are drafts: have them checked by your lawyer before using them.')}</p>
      <ErrorText error={error} />
      <div className="table-wrap">
        <table>
          <thead><tr><th>{t('Name')}</th><th>{t('Kind')}</th><th>{t('Signature')}</th><th>{t('State')}</th><th /></tr></thead>
          <tbody>
            {templates.map((l) => (
              <tr key={l.id}>
                <td>{l.name} <code className="small">{l.code}</code></td><td>{LETTER_KINDS[l.kind]}</td>
                <td>{l.requiresSignature ? t('Signed with a code') : t('No signature')}</td>
                <td>{l.active ? <span className="badge ok">{t('Active')}</span> : <span className="badge">{t('Off')}</span>}</td>
                <td><Button className="secondary" onClick={() => { setFormError(null); setDraft({ ...l }) }}>{t('Edit')}</Button></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}
