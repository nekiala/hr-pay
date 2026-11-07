import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import {
  api, CERTIFICATE_KINDS, CERTIFICATE_STATUS, CHECKLIST_KINDS, DOC_SOURCES, fileSize, fileToBase64, LETTER_KINDS, LETTER_STATUS, longDay, openFile,
  type CertificateKind, type Checklist, type ChecklistKind, type DocumentType, type Letter, type LetterDraft, type LetterKind, type LetterTemplate,
  type PersonLifecycle, type User,
} from '../api'
import { Button, Form } from '../components/Button'
import { useAsk } from '../components/Dialogs'
import { ErrorText } from '../components/ErrorText'
import { Modal } from '../components/Modal'
import { t } from '../i18n'

const today = () => new Date().toISOString().slice(0, 10)

/** One person's hiring or departure: checklists, documents, equipment, letters and certificates. */
export default function PersonLifecyclePage() {
  const { id } = useParams()
  const userId = Number(id)
  const ask = useAsk()
  const [user, setUser] = useState<User | null>(null)
  const [data, setData] = useState<PersonLifecycle | null>(null)
  const [types, setTypes] = useState<DocumentType[]>([])
  const [error, setError] = useState<string | null>(null)
  const [info, setInfo] = useState<string | null>(null)
  const [upload, setUpload] = useState(false)
  const [lend, setLend] = useState(false)
  const [letter, setLetter] = useState<{ id?: number; templateId: number | null; title: string; body: string; kind: LetterKind; requiresSignature: boolean; missing: string[] } | null>(null)

  const load = () => api<PersonLifecycle>(`/api/lifecycle/people/${userId}`).then(setData).catch((e: Error) => setError(e.message))
  useEffect(() => {
    void load()
    api<User>(`/api/users/${userId}`).then(setUser).catch(() => undefined)
    api<DocumentType[]>('/api/lifecycle/document-types').then(setTypes).catch(() => undefined)
  }, [userId]) // eslint-disable-line react-hooks/exhaustive-deps

  const run = async (f: () => Promise<unknown>, done?: string) => {
    setError(null)
    setInfo(null)
    try { await f(); await load(); if (done) setInfo(done) } catch (e) { setError((e as Error).message) }
  }
  const open = (path: string) => { openFile(path).catch((e: Error) => setError(e.message)) }

  if (!data) return <ErrorText error={error} />
  const name = user ? `${user.firstName} ${user.lastName}` : ''
  const openKinds = new Set(data.checklists.filter((c) => c.status === 'OPEN').map((c) => c.kind))

  return (
    <section>
      <p className="small"><Link to="/lifecycle">{t('Hiring and departures')}</Link> › <Link to={`/users/${userId}`}>{name}</Link></p>
      <h2>{name}{user && <span className="muted small"> · {user.registrationNo}</span>}</h2>
      <ErrorText error={error} />
      {info && <p className="success">{info}</p>}

      {data.checklists.map((c) => <ChecklistCard key={c.id} checklist={c} onChange={load} onError={setError} />)}
      <div className="row">
        {(Object.keys(CHECKLIST_KINDS) as ChecklistKind[]).filter((k) => !openKinds.has(k)).map((k) => (
          <Button key={k} className="secondary" onClick={() => ask({
            title: t('Start the {a} checklist?', { a: CHECKLIST_KINDS[k].toLowerCase() }), confirmLabel: t('Start'),
            fields: [{ label: t('To finish by (optional)'), type: 'date' }],
            onConfirm: async ([due]) => { await api(`/api/lifecycle/people/${userId}/checklists`, { method: 'POST', body: { kind: k, dueOn: due || null } }); await load() },
          })}>{t('Start the {a} checklist', { a: CHECKLIST_KINDS[k].toLowerCase() })}</Button>
        ))}
      </div>

      <div className="card">
        <div className="row between">
          <h3>{t('Documents')}</h3>
          <Button onClick={() => setUpload(true)}>{t('Add a document')}</Button>
        </div>
        <div className="table-wrap">
          <table>
            <thead><tr><th>{t('Document')}</th><th>{t('File')}</th><th>{t('Expires')}</th><th>{t('From')}</th><th /></tr></thead>
            <tbody>
              {data.documents.length === 0 && <tr><td colSpan={5} className="muted">{t('No document yet.')}</td></tr>}
              {data.documents.map((d) => {
                const expired = d.expiresOn && d.expiresOn < today()
                return (
                  <tr key={d.id}>
                    <td>{d.typeName}{d.note && <div className="small muted">{d.note}</div>}</td>
                    <td><Button className="link" onClick={() => open(`/api/lifecycle/documents/${d.id}/file`)}>{d.fileName}</Button> <span className="small muted">{fileSize(d.sizeBytes)}</span></td>
                    <td>{d.expiresOn ? <span className={expired ? 'badge off' : ''}>{longDay(d.expiresOn)}</span> : '—'}</td>
                    <td className="small">{DOC_SOURCES[d.source]}<div className="muted">{longDay(d.uploadedAt)}</div></td>
                    <td>{d.source !== 'SIGNED' && d.source !== 'CERTIFICATE' && (
                      <Button className="secondary" onClick={() => ask({
                        title: t('Remove {a}?', { a: d.fileName }), message: t('It is kept in the audit trail but no longer shown.'), confirmLabel: t('Remove'), danger: true,
                        onConfirm: async () => { await api(`/api/lifecycle/documents/${d.id}`, { method: 'DELETE' }); await load() },
                      })}>{t('Remove')}</Button>
                    )}</td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      </div>

      <div className="card">
        <div className="row between">
          <h3>{t('Equipment')}</h3>
          <Button onClick={() => setLend(true)}>{t('Lend equipment')}</Button>
        </div>
        <div className="table-wrap">
          <table>
            <thead><tr><th>{t('Equipment')}</th><th>{t('Serial number')}</th><th>{t('Lent on')}</th><th>{t('Back')}</th><th /></tr></thead>
            <tbody>
              {data.equipment.length === 0 && <tr><td colSpan={5} className="muted">{t('Nothing lent.')}</td></tr>}
              {data.equipment.map((e) => (
                <tr key={e.id}>
                  <td>{e.kind}{e.description && <div className="small muted">{e.description}</div>}</td>
                  <td>{e.serialNo ?? '—'}</td>
                  <td>{longDay(e.issuedOn)}</td>
                  <td>{e.returnedOn ? <span className="badge ok">{longDay(e.returnedOn)}</span> : <span className="badge warn">{t('Not yet')}</span>}</td>
                  <td>
                    <div className="row">
                      {!e.returnedOn && <Button className="secondary" onClick={() => ask({
                        title: t('{a} is back?', { a: e.kind }), confirmLabel: t('Record the return'),
                        fields: [{ label: t('On'), type: 'date', required: true, defaultValue: today() }, { label: t('Note (optional)') }],
                        onConfirm: async ([on, note]) => { await api(`/api/lifecycle/equipment/${e.id}/return`, { method: 'POST', body: { on, note: note || null } }); await load() },
                      })}>{t('Returned')}</Button>}
                      <Button className="secondary" onClick={() => ask({
                        title: t('Delete {a}?', { a: e.kind }), message: t('Only for a mistake: to record a return, use Returned.'), confirmLabel: t('Delete'), danger: true,
                        onConfirm: async () => { await api(`/api/lifecycle/equipment/${e.id}`, { method: 'DELETE' }); await load() },
                      })}>{t('Delete')}</Button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      <LettersCard letters={data.letters} userId={userId} onOpen={open} onRun={run} onEdit={setLetter} />

      <div className="card">
        <div className="row between">
          <h3>{t('Certificates')}</h3>
          <div className="row">
            {(Object.keys(CERTIFICATE_KINDS) as CertificateKind[]).map((k) => (
              <Button key={k} className="secondary" onClick={() => ask({
                title: t('Issue a {a}?', { a: CERTIFICATE_KINDS[k].toLowerCase() }), confirmLabel: t('Issue'),
                message: t('It is numbered, filed in the person\'s documents and they are told.'),
                fields: [{ label: t('Purpose (optional)'), placeholder: t('For example: visa, bank loan') }],
                onConfirm: async ([purpose]) => { await api(`/api/lifecycle/people/${userId}/certificates`, { method: 'POST', body: { kind: k, purpose: purpose || null } }); await load() },
              })}>{t('Issue a {a}', { a: CERTIFICATE_KINDS[k].toLowerCase() })}</Button>
            ))}
          </div>
        </div>
        <CertificatesTable rows={data.certificates} onOpen={(docId) => open(`/api/lifecycle/documents/${docId}/file`)}
          onIssue={(id) => run(() => api(`/api/lifecycle/certificates/${id}/issue`, { method: 'POST' }), t('Issued and filed in the documents.'))}
          onReject={(id) => ask({
            title: t('Refuse this request?'), confirmLabel: t('Refuse'), danger: true, fields: [{ label: t('Reason'), required: true }],
            onConfirm: async ([reason]) => { await api(`/api/lifecycle/certificates/${id}/reject`, { method: 'POST', body: { reason } }); await load() },
          })} />
      </div>

      {upload && <UploadModal userId={userId} types={types} onClose={() => setUpload(false)} onDone={async () => { setUpload(false); await load() }} />}
      {lend && <LendModal userId={userId} onClose={() => setLend(false)} onDone={async () => { setLend(false); await load() }} />}
      {letter && <LetterModal userId={userId} draft={letter} onClose={() => setLetter(null)} onDone={async () => { setLetter(null); await load() }} />}
    </section>
  )
}

function ChecklistCard({ checklist: c, onChange, onError }: { checklist: Checklist; onChange: () => Promise<unknown>; onError: (e: string) => void }) {
  const ask = useAsk()
  const done = c.items.filter((i) => i.done).length
  const tick = async (id: number, value: boolean) => {
    try { await api(`/api/lifecycle/checklist-items/${id}`, { method: 'POST', body: { done: value } }); await onChange() } catch (e) { onError((e as Error).message) }
  }
  return (
    <div className="card">
      <div className="row between">
        <h3>{t('{a} checklist', { a: CHECKLIST_KINDS[c.kind] })}{' '}
          <span className={c.status === 'OPEN' ? 'badge warn' : c.status === 'DONE' ? 'badge ok' : 'badge'}>
            {c.status === 'OPEN' ? t('{a} of {b} done', { a: done, b: c.items.length }) : c.status === 'DONE' ? t('Done') : t('Cancelled')}
          </span>
        </h3>
        {c.status === 'OPEN' && (
          <div className="row">
            <Button disabled={done < c.items.length} onClick={() => api(`/api/lifecycle/checklists/${c.id}/close`, { method: 'POST', body: { cancel: false } }).then(onChange).catch((e: Error) => onError(e.message))}>{t('Close as done')}</Button>
            <Button className="secondary" onClick={() => ask({
              title: t('Cancel this checklist?'), confirmLabel: t('Cancel the checklist'), danger: true,
              onConfirm: async () => { await api(`/api/lifecycle/checklists/${c.id}/close`, { method: 'POST', body: { cancel: true } }); await onChange() },
            })}>{t('Cancel')}</Button>
          </div>
        )}
      </div>
      <p className="small muted">{t('Started {a} by {b}', { a: longDay(c.startedAt), b: c.startedBy })}{c.dueOn && ` · ${t('to finish by {a}', { a: longDay(c.dueOn) })}`}</p>
      <ul className="checklist">
        {c.items.map((i) => (
          <li key={i.id} className={i.done ? 'done' : ''}>
            <input type="checkbox" checked={i.done} disabled={i.auto || c.status !== 'OPEN'} aria-label={i.label} onChange={(e) => void tick(i.id, e.target.checked)} />
            <span style={{ flex: 1 }}>
              <span className="step-label">{i.label}</span>
              {i.auto && <span className="small muted"> · {t('ticks itself')}</span>}
              {i.done && i.doneBy && <div className="small muted">{longDay(i.doneAt)} · {i.doneBy}{i.note && ` · ${i.note}`}</div>}
            </span>
          </li>
        ))}
      </ul>
    </div>
  )
}

function LettersCard({ letters, userId, onOpen, onRun, onEdit }: {
  letters: Letter[]; userId: number; onOpen: (path: string) => void
  onRun: (f: () => Promise<unknown>, done?: string) => Promise<void>
  onEdit: (d: { id?: number; templateId: number | null; title: string; body: string; kind: LetterKind; requiresSignature: boolean; missing: string[] }) => void
}) {
  const ask = useAsk()
  const [templates, setTemplates] = useState<LetterTemplate[]>([])
  const [template, setTemplate] = useState('')
  useEffect(() => { api<LetterTemplate[]>('/api/lifecycle/templates').then((l) => setTemplates(l.filter((x) => x.active))).catch(() => undefined) }, [])
  const start = async () => {
    const tpl = templates.find((x) => x.id === Number(template))
    if (!tpl) {
      onEdit({ templateId: null, title: '', body: '', kind: 'LETTER', requiresSignature: true, missing: [] })
      return
    }
    const d = await api<LetterDraft>(`/api/lifecycle/people/${userId}/letters/preview`, { method: 'POST', body: { templateId: tpl.id } })
    // The template's own text is kept, with its fields: they are filled in when it is sent.
    onEdit({ templateId: tpl.id, title: tpl.name, body: tpl.body, kind: tpl.kind, requiresSignature: tpl.requiresSignature, missing: d.missing })
  }
  return (
    <div className="card">
      <div className="row between">
        <h3>{t('Contracts and letters')}</h3>
        <div className="row">
          <select value={template} onChange={(e) => setTemplate(e.target.value)} aria-label={t('Template')}>
            <option value="">{t('Blank letter')}</option>
            {templates.map((x) => <option key={x.id} value={x.id}>{x.name}</option>)}
          </select>
          <Button onClick={start}>{t('Prepare')}</Button>
        </div>
      </div>
      <div className="table-wrap">
        <table>
          <thead><tr><th>{t('Document')}</th><th>{t('No.')}</th><th>{t('State')}</th><th /></tr></thead>
          <tbody>
            {letters.length === 0 && <tr><td colSpan={4} className="muted">{t('No document yet.')}</td></tr>}
            {letters.map((l) => (
              <tr key={l.id}>
                <td>{l.title} <span className="small muted">{LETTER_KINDS[l.kind]}</span>
                  {l.status === 'SIGNED' && <div className="small muted">{t('Signed {a} as “{b}”', { a: longDay(l.signedAt), b: l.signedName })}</div>}
                  {l.status === 'DECLINED' && l.declinedReason && <div className="small muted">{l.declinedReason}</div>}
                </td>
                <td>{l.number ?? '—'}</td>
                <td><span className={LETTER_STATUS[l.status][1]}>{l.requiresSignature || l.status !== 'SENT' ? LETTER_STATUS[l.status][0] : t('Sent')}</span></td>
                <td>
                  <div className="row">
                    <Button className="secondary" onClick={() => onOpen(`/api/lifecycle/letters/${l.id}/file`)}>{l.status === 'DRAFT' ? t('Preview') : t('PDF')}</Button>
                    {l.status === 'DRAFT' && <Button className="secondary" onClick={() => onEdit({ id: l.id, templateId: l.templateId, title: l.title, body: l.body ?? '', kind: l.kind, requiresSignature: l.requiresSignature, missing: [] })}>{t('Edit')}</Button>}
                    {l.status === 'DRAFT' && <Button onClick={() => ask({
                      title: t('Send {a}?', { a: l.title }),
                      message: l.requiresSignature ? t('It is numbered and the employee is asked to sign it with a one-time code. It cannot change afterwards.') : t('It is numbered and filed in the employee\'s documents. It cannot change afterwards.'),
                      confirmLabel: t('Send'),
                      onConfirm: async () => { await onRun(() => api(`/api/lifecycle/letters/${l.id}/send`, { method: 'POST' }), t('Sent.')) },
                    })}>{t('Send')}</Button>}
                    {(l.status === 'DRAFT' || l.status === 'SENT') && <Button className="secondary" onClick={() => ask({
                      title: t('Cancel {a}?', { a: l.title }), confirmLabel: t('Cancel the document'), danger: true,
                      onConfirm: async () => { await onRun(() => api(`/api/lifecycle/letters/${l.id}/cancel`, { method: 'POST' })) },
                    })}>{t('Cancel')}</Button>}
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}

export function CertificatesTable({ rows, onOpen, onIssue, onReject, onCancel }: {
  rows: import('../api').CertificateRequest[]; onOpen: (documentId: number) => void
  onIssue?: (id: number) => void; onReject?: (id: number) => void; onCancel?: (id: number) => void
}) {
  return (
    <div className="table-wrap">
      <table>
        <thead><tr><th>{t('Certificate')}</th><th>{t('Asked')}</th><th>{t('State')}</th><th /></tr></thead>
        <tbody>
          {rows.length === 0 && <tr><td colSpan={4} className="muted">{t('None yet.')}</td></tr>}
          {rows.map((c) => (
            <tr key={c.id}>
              <td>{CERTIFICATE_KINDS[c.kind]}{c.number && <span className="small muted"> · {c.number}</span>}{c.purpose && <div className="small muted">{c.purpose}</div>}</td>
              <td className="small">{longDay(c.requestedAt)}</td>
              <td><span className={CERTIFICATE_STATUS[c.status][1]}>{CERTIFICATE_STATUS[c.status][0]}</span>{c.status === 'REJECTED' && c.reason && <div className="small muted">{c.reason}</div>}</td>
              <td>
                <div className="row">
                  {c.documentId && <Button className="secondary" onClick={() => onOpen(c.documentId!)}>{t('PDF')}</Button>}
                  {c.status === 'PENDING' && onIssue && <Button onClick={() => onIssue(c.id)}>{t('Issue')}</Button>}
                  {c.status === 'PENDING' && onReject && <Button className="secondary" onClick={() => onReject(c.id)}>{t('Refuse')}</Button>}
                  {c.status === 'PENDING' && onCancel && <Button className="secondary" onClick={() => onCancel(c.id)}>{t('Withdraw')}</Button>}
                </div>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

export function UploadModal({ userId, types, onClose, onDone, mine }: { userId?: number; types: DocumentType[]; onClose: () => void; onDone: () => Promise<void>; mine?: boolean }) {
  const usable = types.filter((d) => d.active)
  const [typeId, setTypeId] = useState<number | ''>(usable[0]?.id ?? '')
  const [file, setFile] = useState<File | null>(null)
  const [issuedOn, setIssuedOn] = useState('')
  const [expiresOn, setExpiresOn] = useState('')
  const [note, setNote] = useState('')
  const [error, setError] = useState<string | null>(null)
  const type = usable.find((d) => d.id === typeId)
  const save = async () => {
    if (!file || !typeId) return
    setError(null)
    if (file.size > 10 * 1024 * 1024) { setError(t('A document is at most 10 MB.')); return }
    try {
      const body = { typeId, fileName: file.name, content: await fileToBase64(file), issuedOn: issuedOn || null, expiresOn: expiresOn || null, note: note || null }
      await api(mine ? '/api/my/documents' : `/api/lifecycle/people/${userId}/documents`, { method: 'POST', body })
      await onDone()
    } catch (e) { setError((e as Error).message) }
  }
  return (
    <Modal title={t('Add a document')} onClose={onClose}>
      <Form onSubmit={save}>
        <label>{t('Document')}
          <select required value={typeId} onChange={(e) => setTypeId(Number(e.target.value))}>
            {usable.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
          </select>
        </label>
        <label>{t('File (PDF, JPEG or PNG, at most 10 MB)')}<input type="file" required accept="application/pdf,image/jpeg,image/png" capture={mine ? 'environment' : undefined} onChange={(e) => setFile(e.target.files?.[0] ?? null)} /></label>
        <div className="grid2">
          <label>{t('Issued on (optional)')}<input type="date" value={issuedOn} onChange={(e) => setIssuedOn(e.target.value)} /></label>
          <label>{type?.hasExpiry ? t('Expires on') : t('Expires on (optional)')}<input type="date" required={type?.hasExpiry} value={expiresOn} onChange={(e) => setExpiresOn(e.target.value)} /></label>
        </div>
        {!mine && <label>{t('Note (optional)')}<input maxLength={300} value={note} onChange={(e) => setNote(e.target.value)} /></label>}
        <ErrorText error={error} />
        <div className="modal-foot"><Button type="submit" disabled={!file}>{t('Add')}</Button></div>
      </Form>
    </Modal>
  )
}

function LendModal({ userId, onClose, onDone }: { userId: number; onClose: () => void; onDone: () => Promise<void> }) {
  const [form, setForm] = useState({ kind: '', description: '', serialNo: '', issuedOn: today(), note: '' })
  const [error, setError] = useState<string | null>(null)
  const save = async () => {
    setError(null)
    try {
      await api(`/api/lifecycle/people/${userId}/equipment`, { method: 'POST', body: { ...form, description: form.description || null, serialNo: form.serialNo || null, note: form.note || null } })
      await onDone()
    } catch (e) { setError((e as Error).message) }
  }
  return (
    <Modal title={t('Lend equipment')} onClose={onClose}>
      <Form onSubmit={save}>
        <label>{t('Equipment')}<input required maxLength={60} placeholder={t('For example: Laptop, badge, phone, uniform')} value={form.kind} onChange={(e) => setForm({ ...form, kind: e.target.value })} /></label>
        <label>{t('Description (optional)')}<input maxLength={200} value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} /></label>
        <div className="grid2">
          <label>{t('Serial number (optional)')}<input maxLength={80} value={form.serialNo} onChange={(e) => setForm({ ...form, serialNo: e.target.value })} /></label>
          <label>{t('Lent on')}<input type="date" required value={form.issuedOn} onChange={(e) => setForm({ ...form, issuedOn: e.target.value })} /></label>
        </div>
        <ErrorText error={error} />
        <div className="modal-foot"><Button type="submit">{t('Save')}</Button></div>
      </Form>
    </Modal>
  )
}

function LetterModal({ userId, draft, onClose, onDone }: {
  userId: number
  draft: { id?: number; templateId: number | null; title: string; body: string; kind: LetterKind; requiresSignature: boolean; missing: string[] }
  onClose: () => void; onDone: () => Promise<void>
}) {
  const [form, setForm] = useState(draft)
  const [fields, setFields] = useState<Record<string, string>>({})
  const [error, setError] = useState<string | null>(null)
  useEffect(() => { api<Record<string, string>>('/api/lifecycle/template-fields').then(setFields).catch(() => undefined) }, [])
  const save = async () => {
    setError(null)
    try {
      const body = { templateId: form.templateId, title: form.title, body: form.body, kind: form.kind, requiresSignature: form.requiresSignature }
      await api(form.id ? `/api/lifecycle/letters/${form.id}` : `/api/lifecycle/people/${userId}/letters`, { method: form.id ? 'PUT' : 'POST', body })
      await onDone()
    } catch (e) { setError((e as Error).message) }
  }
  return (
    <Modal title={form.id ? t('Edit the draft') : t('Prepare a document')} onClose={onClose} size="lg">
      <Form onSubmit={save}>
        <div className="grid2">
          <label>{t('Title')}<input required maxLength={150} value={form.title} onChange={(e) => setForm({ ...form, title: e.target.value })} /></label>
          {!form.id && (
            <label>{t('Kind')}
              <select value={form.kind} onChange={(e) => setForm({ ...form, kind: e.target.value as LetterKind })}>
                {(Object.keys(LETTER_KINDS) as LetterKind[]).map((k) => <option key={k} value={k}>{LETTER_KINDS[k]}</option>)}
              </select>
            </label>
          )}
        </div>
        <label>{t('Text')}<textarea className="template" required maxLength={50_000} value={form.body} onChange={(e) => setForm({ ...form, body: e.target.value })} /></label>
        {form.missing.length > 0 && (
          <p className="notice small">{t('Not known yet for this person, fill in their file or replace in the text before sending: {a}.', { a: form.missing.map((k) => fields[k] ?? k).join(', ') })}</p>
        )}
        <details>
          <summary className="small">{t('Fields filled in when it is sent')}</summary>
          <div className="table-wrap"><table><tbody>
            {Object.entries(fields).map(([k, label]) => <tr key={k}><td><code>{`{{${k}}}`}</code></td><td className="small">{label}</td></tr>)}
          </tbody></table></div>
        </details>
        <label className="check"><input type="checkbox" checked={form.requiresSignature} onChange={(e) => setForm({ ...form, requiresSignature: e.target.checked })} />{t('The employee signs it with a one-time code')}</label>
        <p className="small muted">{t('Saved as a draft: preview the PDF, then send it.')}</p>
        <ErrorText error={error} />
        <div className="modal-foot"><Button type="submit">{t('Save the draft')}</Button></div>
      </Form>
    </Modal>
  )
}
