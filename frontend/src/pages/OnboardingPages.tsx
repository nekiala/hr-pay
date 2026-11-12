import { useEffect, useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import { Copy, Hand, Send, Star, UserRound } from 'lucide-react'
import {
  api, fileToBase64, ID_DOCUMENTS, longDay, STEP_OWNERS,
  type ChecklistItem, type Checklist, type IdDocumentType, type StepOwner,
} from '../api'
import { useAuth } from '../auth'
import { Button, Form } from '../components/Button'
import { DateInput } from '../components/DateFields'
import { useAsk } from '../components/Dialogs'
import { ErrorText } from '../components/ErrorText'
import { LanguageToggle } from '../components/Language'
import { Markdown } from '../components/Markdown'
import { Modal } from '../components/Modal'
import { PageTitle } from '../components/SettingsLink'
import { PersonSelect } from '../components/PersonSelect'
import { language, setLanguage, t, type Language } from '../i18n'

// Kinshasa is UTC+1.
const today = () => new Date(Date.now() + 60 * 60 * 1000).toISOString().slice(0, 10)

// ---- types (as the API sends them) -------------------------------------------------------------------------------

type FamilyMember = { name: string; relationship: 'SPOUSE' | 'CHILD'; birthDate: string | null }
type Answers = {
  birthDate: string | null; gender: 'FEMALE' | 'MALE' | null; maritalStatus: string | null; nationalityCode: string | null
  addressQuarter: string | null; addressStreet: string | null; idDocumentType: IdDocumentType | null; idDocumentNumber: string | null
  idDocumentIssuedOn: string | null; idDocumentIssuedAt: string | null; cnssNumber: string | null
  paymentMethod: 'BANK' | 'MOBILE_MONEY' | 'CASH' | null; bankName: string | null; bankAccount: string | null
  mobileMoneyOperator: string | null; mobileMoneyNumber: string | null; family: FamilyMember[]
}
type DocType = { id: number; name: string; received: boolean }
type SentDocument = { id: number; documentTypeId: number; typeName: string; fileName: string; status: 'PENDING' | 'ACCEPTED' | 'REJECTED'; reason: string | null; uploadedAt: string; hasExpiry: boolean }
type PreStart = { company: string; firstName: string; startOn: string; documents: DocType[]; answers: Answers | null; sent: SentDocument[]; submittedAt: string | null; language: Language }
type Row = {
  userId: number; name: string; registrationNo: string | null; unit: string | null; startOn: string; status: 'OPEN' | 'DONE'
  linkSentAt: string | null; submittedAt: string | null; detailsAppliedAt: string | null; pending: number; buddy: string | null
  lateSteps: number; openSteps: number
}
type Review = {
  id: number; userId: number; name: string; kind: 'MID' | 'END'; dueOn: string; trialEndsOn: string; reviewerId: number | null; reviewer: string | null
  status: 'PLANNED' | 'DONE' | 'CANCELLED'; rating: number | null; strengths: string | null; improvements: string | null
  recommendation: 'CONFIRM' | 'END' | null; comment: string | null; doneBy: string | null; doneAt: string | null
}
type Detail = { row: Row; buddyUserId: number | null; answers: Answers | null; documents: SentDocument[]; checklist: Checklist | null; reviews: Review[]; link: string | null; linkExpiresOn: string | null }
type Overview = { open: number; lateSteps: number; waitingCheck: number; trialsConfirmed: number; trialsEnded: number; reviewsLate: number }
export type Welcome = { firstName: string; startOn: string; manager: string | null; buddy: string | null; unit: string | null; job: string | null; steps: ChecklistItem[]; text: string | null; seen: boolean }

const EMPTY: Answers = {
  birthDate: null, gender: null, maritalStatus: null, nationalityCode: null, addressQuarter: null, addressStreet: null,
  idDocumentType: null, idDocumentNumber: null, idDocumentIssuedOn: null, idDocumentIssuedAt: null, cnssNumber: null,
  paymentMethod: null, bankName: null, bankAccount: null, mobileMoneyOperator: null, mobileMoneyNumber: null, family: [],
}
const MARITAL: Record<string, string> = { SINGLE: t('Single'), MARRIED: t('Married'), DIVORCED: t('Divorced'), WIDOWED: t('Widowed') }
const PAYMENT: Record<string, string> = { BANK: t('Bank account'), MOBILE_MONEY: t('Mobile money'), CASH: t('Cash') }
const RELATION: Record<string, string> = { SPOUSE: t('Spouse'), CHILD: t('Child') }
const REVIEW_KIND: Record<Review['kind'], string> = { MID: t('One-month review'), END: t('End-of-trial review') }
const PAPER: Record<SentDocument['status'], string> = { PENDING: t('Waiting for HR'), ACCEPTED: t('Accepted'), REJECTED: t('Refused') }

// ---- the new hire's form (public, from the emailed link) ---------------------------------------------------------

/** /welcome-form/{token}: the new hire fills their file and sends their papers before the first day, without an account. */
export function WelcomeFormPage() {
  const { token = '' } = useParams()
  const [data, setData] = useState<PreStart | null>(null)
  const [a, setA] = useState<Answers>(EMPTY)
  const [error, setError] = useState<string | null>(null)
  const [saved, setSaved] = useState<string | null>(null)
  const base = `/api/public/onboarding/${encodeURIComponent(token)}`
  const take = (p: PreStart) => { setData(p); setA({ ...EMPTY, ...(p.answers ?? {}), family: p.answers?.family ?? [] }) }
  useEffect(() => {
    api<PreStart>(base).then((p) => {
      let chosen = false
      try { chosen = localStorage.getItem('hrpay.language') != null } catch { /* no storage */ }
      if (!chosen && p.language !== language()) { setLanguage(p.language); window.location.reload(); return }
      take(p)
    }).catch((e: Error) => setError(e.message))
  }, [base])
  const set = (patch: Partial<Answers>) => { setSaved(null); setA({ ...a, ...patch }) }
  const text = (k: keyof Answers) => ({ value: (a[k] as string | null) ?? '', onChange: (e: { target: { value: string } }) => set({ [k]: e.target.value || null }) })
  const save = async (submit: boolean) => {
    setError(null)
    try {
      const p = await api<PreStart>(base, { method: 'POST', body: { answers: a, submit } })
      setData(p)
      setSaved(submit ? t('Sent. HR checks your details and papers and comes back to you if something is missing.') : t('Saved. You can come back to this page until your first day.'))
    } catch (e) { setError((e as Error).message) }
  }
  const upload = async (typeId: number, file: File | undefined) => {
    if (!file) return
    setError(null)
    if (file.size > 5 * 1024 * 1024) { setError(t('The file is larger than 5 MB.')); return }
    try {
      setData(await api<PreStart>(`${base}/documents`, { method: 'POST', body: { documentTypeId: typeId, fileName: file.name, contentType: file.type, data: await fileToBase64(file) } }))
    } catch (e) { setError((e as Error).message) }
  }
  const remove = async (id: number) => {
    try { setData(await api<PreStart>(`${base}/documents/${id}/remove`, { method: 'POST' })) } catch (e) { setError((e as Error).message) }
  }
  const family = (i: number, patch: Partial<FamilyMember>) => set({ family: a.family.map((m, j) => (j === i ? { ...m, ...patch } : m)) })
  return (
    <div className="public">
      <header className="public-head"><span className="public-brand">{data?.company ?? ''}</span><LanguageToggle /></header>
      <main className="public-main">
        {!data && <ErrorText error={error} />}
        {data && (
          <Form className="stack" onSubmit={() => save(true)}>
            <h1>{t('Welcome, {name}', { name: data.firstName })}</h1>
            <p className="muted">{t('You start at {company} on {d}. Fill in what you can before then: HR prepares your file, your pay and your first day with it. Nothing here is shared outside HR.', { company: data.company, d: longDay(data.startOn) })}</p>
            {data.submittedAt && <p className="success">{t('Sent to HR on {d}. You can still correct it until your first day.', { d: longDay(data.submittedAt) })}</p>}

            <div className="card">
              <h3>{t('About you')}</h3>
              <div className="grid3">
                <label>{t('Date of birth')}<DateInput value={a.birthDate ?? ''} max={today()} onChange={(e) => set({ birthDate: e.target.value || null })} /></label>
                <label>{t('Gender')}<select value={a.gender ?? ''} onChange={(e) => set({ gender: (e.target.value || null) as Answers['gender'] })}>
                  <option value="">{t('Not given')}</option><option value="FEMALE">{t('Female')}</option><option value="MALE">{t('Male')}</option>
                </select></label>
                <label>{t('Marital status')}<select value={a.maritalStatus ?? ''} onChange={(e) => set({ maritalStatus: e.target.value || null })}>
                  <option value="">{t('Not given')}</option>
                  {Object.entries(MARITAL).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
                </select></label>
              </div>
              <div className="grid2">
                <label>{t('Quarter (quartier)')}<input maxLength={100} {...text('addressQuarter')} /></label>
                <label>{t('Street and number')}<input maxLength={200} {...text('addressStreet')} /></label>
              </div>
            </div>

            <div className="card">
              <h3>{t('Identity document')}</h3>
              <div className="grid2">
                <label>{t('Document')}<select value={a.idDocumentType ?? ''} onChange={(e) => set({ idDocumentType: (e.target.value || null) as IdDocumentType | null })}>
                  <option value="">{t('Not given')}</option>
                  {(Object.keys(ID_DOCUMENTS) as IdDocumentType[]).map((k) => <option key={k} value={k}>{ID_DOCUMENTS[k]}</option>)}
                </select></label>
                <label>{t('Document number')}<input maxLength={40} {...text('idDocumentNumber')} /></label>
                <label>{t('Issued on')}<DateInput value={a.idDocumentIssuedOn ?? ''} max={today()} onChange={(e) => set({ idDocumentIssuedOn: e.target.value || null })} /></label>
                <label>{t('Issued at')}<input maxLength={100} {...text('idDocumentIssuedAt')} /></label>
              </div>
              <label>{t('CNSS number (if you already have one)')}<input maxLength={30} {...text('cnssNumber')} /></label>
            </div>

            <div className="card">
              <h3>{t('How you want to be paid')}</h3>
              <label>{t('Payment')}<select value={a.paymentMethod ?? ''} onChange={(e) => set({ paymentMethod: (e.target.value || null) as Answers['paymentMethod'] })}>
                <option value="">{t('Not decided yet')}</option>
                {Object.entries(PAYMENT).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
              </select></label>
              {a.paymentMethod === 'BANK' && <div className="grid2">
                <label>{t('Bank')}<input maxLength={100} {...text('bankName')} /></label>
                <label>{t('Account number')}<input maxLength={40} {...text('bankAccount')} /></label>
              </div>}
              {a.paymentMethod === 'MOBILE_MONEY' && <div className="grid2">
                <label>{t('Operator')}<input maxLength={100} placeholder="M-Pesa, Orange Money, Airtel Money…" {...text('mobileMoneyOperator')} /></label>
                <label>{t('Mobile money number')}<input maxLength={20} placeholder="+243 …" {...text('mobileMoneyNumber')} /></label>
              </div>}
            </div>

            <div className="card">
              <h3>{t('Your family')}</h3>
              <p className="small muted">{t('Your spouse and children give the family allowances and the tax reduction. HR asks for the certificates.')}</p>
              {a.family.map((m, i) => (
                <div key={i} className="grid3">
                  <label>{t('Full name')}<input required maxLength={150} value={m.name} onChange={(e) => family(i, { name: e.target.value })} /></label>
                  <label>{t('Relationship')}<select value={m.relationship} onChange={(e) => family(i, { relationship: e.target.value as FamilyMember['relationship'] })}>
                    {Object.entries(RELATION).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
                  </select></label>
                  <label>{t('Date of birth')}<DateInput value={m.birthDate ?? ''} max={today()} onChange={(e) => family(i, { birthDate: e.target.value || null })} />
                    <button type="button" className="link small" onClick={() => set({ family: a.family.filter((_, j) => j !== i) })}>{t('Remove')}</button></label>
                </div>
              ))}
              <div><Button className="secondary" onClick={() => set({ family: [...a.family, { name: '', relationship: 'CHILD', birthDate: null }] })}>{t('Add a family member')}</Button></div>
            </div>

            {data.documents.length > 0 && (
              <div className="card">
                <h3>{t('Your papers')}</h3>
                <p className="small muted">{t('A photo or a scan: PDF, JPEG or PNG, 5 MB at most. HR checks each one.')}</p>
                <ul className="checklist">
                  {data.documents.map((d) => {
                    const mine = data.sent.filter((s) => s.documentTypeId === d.id)
                    const ok = d.received || mine.some((s) => s.status !== 'REJECTED')
                    return (
                      <li key={d.id}>
                        <span style={{ flex: 1 }}>
                          <span className="step-label">{d.name}</span>{ok && <span className="badge ok" style={{ marginLeft: 8 }}>✓</span>}
                          {d.received && <span className="small muted"> · {t('HR already has it')}</span>}
                          {mine.map((s) => (
                            <div key={s.id} className="small">
                              {s.fileName} · <span className={s.status === 'ACCEPTED' ? 'success' : s.status === 'REJECTED' ? 'error' : 'muted'}>{PAPER[s.status]}</span>
                              {s.reason && ` · ${s.reason}`}
                              {s.status === 'PENDING' && <> · <button type="button" className="link" onClick={() => remove(s.id)}>{t('Remove')}</button></>}
                            </div>
                          ))}
                        </span>
                        {!d.received && (
                          <label className="button secondary small">{t('Send a file')}
                            <input type="file" hidden accept="application/pdf,image/jpeg,image/png" onChange={(e) => { void upload(d.id, e.target.files?.[0]); e.target.value = '' }} />
                          </label>
                        )}
                      </li>
                    )
                  })}
                </ul>
              </div>
            )}

            <ErrorText error={error} />
            {saved && <p className="success">{saved}</p>}
            <div className="row">
              <Button className="secondary" onClick={() => save(false)}>{t('Save for later')}</Button>
              <Button type="submit"><Send size={16} aria-hidden /> {t('Send to HR')}</Button>
            </div>
            <p className="small muted">{t('This link is personal and works until your first day. Then your account takes over.')}</p>
          </Form>
        )}
      </main>
      <footer className="public-links small"><Link to="/legal/privacy-policy">{t('Privacy policy')}</Link></footer>
    </div>
  )
}

// ---- HR and managers: /onboarding --------------------------------------------------------------------------------

type Tab = 'hires' | 'reviews'

export default function OnboardingPage() {
  const { can } = useAuth()
  const hr = can('lifecycle.manage')
  const [params, setParams] = useSearchParams()
  const user = Number(params.get('user')) || null
  const tab: Tab = params.get('tab') === 'reviews' ? 'reviews' : 'hires'
  const [welcome, setWelcome] = useState(false)
  const [starting, setStarting] = useState(false)
  const [reload, setReload] = useState(0)
  return (
    <section>
      <PageTitle title={t('Onboarding')}>
        {hr && <Button className="secondary" onClick={() => setStarting(true)}>{t('Start an onboarding')}</Button>}
        {hr && <Button className="secondary" onClick={() => setWelcome(true)}>{t('Welcome page')}</Button>}
      </PageTitle>
      <p className="muted">{t('From the hire to the end of the trial: the new hire\'s papers before day one, each step with its owner and date, a buddy, and the manager\'s trial reviews.')}</p>
      <div className="segmented" role="tablist">
        <button type="button" role="tab" aria-selected={tab === 'hires'} className={tab === 'hires' ? 'on' : ''} onClick={() => setParams({})}>{t('New hires')}</button>
        <button type="button" role="tab" aria-selected={tab === 'reviews'} className={tab === 'reviews' ? 'on' : ''} onClick={() => setParams({ tab: 'reviews' })}>{t('Trial reviews')}</button>
      </div>
      {tab === 'hires' ? <HiresTab key={reload} hr={hr} open={(id) => setParams({ user: String(id) })} /> : <ReviewsTab />}
      {user && <DetailModal userId={user} hr={hr} onClose={() => { setParams(tab === 'reviews' ? { tab } : {}); setReload((n) => n + 1) }} />}
      {welcome && <WelcomeTextModal onClose={() => setWelcome(false)} />}
      {starting && <StartModal onClose={() => setStarting(false)} onDone={(id) => { setStarting(false); setReload((n) => n + 1); setParams({ user: String(id) }) }} />}
    </section>
  )
}

function HiresTab({ hr, open }: { hr: boolean; open: (id: number) => void }) {
  const [rows, setRows] = useState<Row[] | null>(null)
  const [overview, setOverview] = useState<Overview | null>(null)
  const [closed, setClosed] = useState(false)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => { api<Row[]>(`/api/onboarding?closed=${closed}`).then(setRows).catch((e: Error) => setError(e.message)) }, [closed])
  useEffect(() => { if (hr) api<Overview>('/api/onboarding/overview').then(setOverview).catch(() => undefined) }, [hr])
  const now = today()
  return (
    <>
      {overview && (
        <div className="tiles">
          <div className="tile"><span>{t('Onboardings open')}</span><strong>{overview.open}</strong></div>
          <div className={`tile${overview.lateSteps ? ' warn' : ''}`}><span>{t('Steps late')}</span><strong>{overview.lateSteps}</strong></div>
          <div className={`tile${overview.waitingCheck ? ' warn' : ''}`}><span>{t('Papers to check')}</span><strong>{overview.waitingCheck}</strong></div>
          <div className={`tile${overview.reviewsLate ? ' warn' : ''}`}><span>{t('Trial reviews late')}</span><strong>{overview.reviewsLate}</strong></div>
          <div className="tile"><span>{t('Trials this year')}</span><strong>{overview.trialsConfirmed}</strong><small>{t('confirmed · {a} ended', { a: overview.trialsEnded })}</small></div>
        </div>
      )}
      <ErrorText error={error} />
      <div className="card">
        <div className="row space">
          <h3>{t('New hires')}</h3>
          <label className="check"><input type="checkbox" checked={closed} onChange={(e) => setClosed(e.target.checked)} />{t('Show closed onboardings')}</label>
        </div>
        {rows && rows.length === 0 && <p className="muted">{t('No onboarding open. Hiring a candidate starts one; HR can also start one for anyone hired.')}</p>}
        {rows && rows.length > 0 && (
          <div className="table-wrap"><table>
            <thead><tr><th>{t('Name')}</th><th>{t('Starts')}</th><th>{t('Before day one')}</th><th>{t('Steps')}</th><th>{t('Buddy')}</th></tr></thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.userId}>
                  <td><button type="button" className="link" onClick={() => open(r.userId)}><strong>{r.name}</strong></button>
                    <div className="small muted">{[r.registrationNo, r.unit].filter(Boolean).join(' · ')}</div></td>
                  <td>{longDay(r.startOn)}{r.startOn > now && <div className="small muted">{t('in {a} days', { a: Math.round((Date.parse(r.startOn) - Date.parse(now)) / 86400000) })}</div>}
                    {r.status === 'DONE' && <div><span className="badge ok">{t('Closed')}</span></div>}</td>
                  <td>{r.submittedAt ? <span className="badge ok">{t('Details sent')}</span> : r.linkSentAt ? <span className="badge warn">{t('Link sent')}</span> : <span className="badge">{t('No link yet')}</span>}
                    {r.pending > 0 && <div className="small">{t('{a} papers to check', { a: r.pending })}</div>}</td>
                  <td>{r.openSteps === 0 ? <span className="badge ok">{t('All done')}</span> : t('{a} open', { a: r.openSteps })}
                    {r.lateSteps > 0 && <div><span className="badge off">{t('{a} late', { a: r.lateSteps })}</span></div>}</td>
                  <td>{r.buddy ?? <span className="muted">{t('No buddy yet')}</span>}</td>
                </tr>
              ))}
            </tbody>
          </table></div>
        )}
      </div>
    </>
  )
}

function DetailModal({ userId, hr, onClose }: { userId: number; hr: boolean; onClose: () => void }) {
  const ask = useAsk()
  const [d, setD] = useState<Detail | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [review, setReview] = useState<Review | null>(null)
  const [refusing, setRefusing] = useState<SentDocument | null>(null)
  const [accepting, setAccepting] = useState<SentDocument | null>(null)
  const [copied, setCopied] = useState(false)
  const base = `/api/onboarding/${userId}`
  const load = () => api<Detail>(base).then(setD).catch((e: Error) => setError(e.message))
  useEffect(() => { void load() }, [userId]) // eslint-disable-line react-hooks/exhaustive-deps
  const run = async (f: () => Promise<Detail>) => { setError(null); try { setD(await f()) } catch (e) { setError((e as Error).message) } }
  const post = (path: string, body?: unknown) => run(() => api<Detail>(path, { method: 'POST', body }))
  const accept = (doc: SentDocument) => doc.hasExpiry ? setAccepting(doc) : post(`/api/onboarding/documents/${doc.id}/check`, { accept: true })
  const r = d?.row
  const open = r?.status === 'OPEN'
  const now = today()
  const canTick = (i: ChecklistItem) => open && !i.auto && (hr || i.owner === 'MANAGER' || i.owner === 'BUDDY')
  return (
    <Modal title={r?.name ?? t('Onboarding')} onClose={onClose} size="lg"
      footer={hr && open && <Button className="secondary" onClick={() => ask({
        title: t('Close this onboarding?'), message: t('The new hire\'s link stops working. The hiring checklist and the trial reviews stay.'), confirmLabel: t('Close'),
        onConfirm: () => post(`${base}/close`),
      })}>{t('Close the onboarding')}</Button>}>
      <ErrorText error={error} />
      {d && r && (
        <div className="stack">
          <p className="muted small">{[r.registrationNo, r.unit, t('Starts {d}', { d: longDay(r.startOn) })].filter(Boolean).join(' · ')}</p>

          {hr && (
            <div className="card">
              <h3>{t('Before day one')}</h3>
              {d.link ? (
                <>
                  <p className="small">{t('Link sent {a}, works until {b}.', { a: longDay(r.linkSentAt), b: longDay(d.linkExpiresOn) })}</p>
                  <div className="row">
                    <input readOnly value={d.link} style={{ flex: 1 }} onFocus={(e) => e.target.select()} />
                    <Button className="secondary" onClick={() => { void navigator.clipboard?.writeText(d.link!); setCopied(true) }}><Copy size={14} aria-hidden /> {copied ? t('Copied') : t('Copy')}</Button>
                  </div>
                  <p className="hint">{t('You can also send it by WhatsApp. Whoever has the link sees the form: send it to the new hire only.')}</p>
                </>
              ) : <p className="small muted">{r.startOn >= now ? t('The new hire gets a link by email to fill in their details and send their papers, with no account.') : t('The start date has passed: the employee fills their file from their account.')}</p>}
              {open && r.startOn >= now && <div style={{ marginBottom: 12 }}><Button onClick={() => post(`${base}/link`)}><Send size={14} aria-hidden /> {d.link ? t('Send a new link') : t('Send the link')}</Button></div>}

              {d.answers && (
                <>
                  <h4>{t('Details sent')}{r.submittedAt && <span className="small muted"> · {longDay(r.submittedAt)}</span>}</h4>
                  <AnswersTable a={d.answers} />
                  {r.detailsAppliedAt ? <p className="small success">{t('Copied into the employee file on {d}.', { d: longDay(r.detailsAppliedAt) })}</p> : null}
                  <div><Button className={r.detailsAppliedAt ? 'secondary' : ''} onClick={() => ask({
                    title: t('Copy into the employee file?'), message: t('What the new hire typed replaces what the file holds; empty answers keep it. Family members are added to the dependants. Check the papers first.'),
                    confirmLabel: t('Copy'), onConfirm: () => post(`${base}/apply-details`),
                  })}>{t('Copy into the employee file')}</Button></div>
                </>
              )}

              {d.documents.length > 0 && (
                <>
                  <h4>{t('Papers sent')}</h4>
                  <ul className="checklist">
                    {d.documents.map((doc) => (
                      <li key={doc.id} className={doc.status === 'ACCEPTED' ? 'done' : ''}>
                        <span style={{ flex: 1 }}>
                          <span className="step-label">{doc.typeName}</span>
                          <div className="small muted">{doc.fileName} · {PAPER[doc.status]}{doc.reason && ` · ${doc.reason}`}</div>
                        </span>
                        {doc.status === 'PENDING' && <>
                          <a className="button secondary small" href={`/api/onboarding/documents/${doc.id}/file`} target="_blank" rel="noreferrer">{t('View')}</a>
                          <Button className="small" onClick={() => accept(doc)}>{t('Accept')}</Button>
                          <Button className="secondary small" onClick={() => setRefusing(doc)}>{t('Refuse')}</Button>
                        </>}
                      </li>
                    ))}
                  </ul>
                </>
              )}
            </div>
          )}

          <div className="card">
            <h3>{t('Buddy')}</h3>
            <p className="small muted">{t('A colleague who shows the new hire around in the first weeks: the team, the places, the habits. They are told by a notification.')}</p>
            <PersonSelect value={d.buddyUserId ?? ''} disabled={!open} exclude={[userId]}
              onChange={(v) => run(() => api<Detail>(`${base}/buddy`, { method: 'PUT', body: { buddyUserId: Number(v) || null } }))} />
          </div>

          {d.checklist && (
            <div className="card">
              <h3>{t('Hiring steps')}</h3>
              <ul className="checklist">
                {d.checklist.items.map((i) => {
                  const late = !i.done && i.dueOn && i.dueOn < now
                  return (
                    <li key={i.id} className={i.done ? 'done' : ''}>
                      <input type="checkbox" checked={i.done} disabled={!canTick(i)} aria-label={i.label}
                        onChange={(e) => post(`${base}/steps/${i.id}`, { done: e.target.checked })} />
                      <span style={{ flex: 1 }}>
                        <span className="step-label">{i.label}</span>
                        <div className="small muted">
                          {STEP_OWNERS[i.owner as StepOwner] ?? i.owner}
                          {i.dueOn && <> · {t('by {d}', { d: longDay(i.dueOn) })}</>}
                          {late && <> <span className="badge off">{t('Late')}</span></>}
                          {i.auto && ` · ${t('ticks itself')}`}
                          {i.done && i.doneBy && ` · ${longDay(i.doneAt)} · ${i.doneBy}`}
                        </div>
                      </span>
                    </li>
                  )
                })}
              </ul>
              {hr && <p className="small"><Link to={`/users/${userId}/lifecycle`}>{t('Open the full hiring file')}</Link></p>}
            </div>
          )}

          <div className="card">
            <h3>{t('Trial reviews')}</h3>
            {d.reviews.length === 0 && <p className="small muted">{t('No trial period on the contract: no review is planned.')}</p>}
            <ReviewList reviews={d.reviews} onOpen={setReview} />
          </div>
        </div>
      )}
      {review && <ReviewModal review={review} onClose={() => setReview(null)} onDone={async () => { setReview(null); await load() }} />}
      {refusing && <RefuseModal doc={refusing} onClose={() => setRefusing(null)} onDone={async (reason) => { setRefusing(null); await post(`/api/onboarding/documents/${refusing.id}/check`, { accept: false, reason }) }} />}
      {accepting && <ExpiryModal doc={accepting} onClose={() => setAccepting(null)} onDone={async (expiresOn) => { setAccepting(null); await post(`/api/onboarding/documents/${accepting.id}/check`, { accept: true, expiresOn }) }} />}
    </Modal>
  )
}

function AnswersTable({ a }: { a: Answers }) {
  const rows: [string, string | null][] = [
    [t('Date of birth'), a.birthDate && longDay(a.birthDate)],
    [t('Gender'), a.gender && (a.gender === 'FEMALE' ? t('Female') : t('Male'))],
    [t('Marital status'), a.maritalStatus && MARITAL[a.maritalStatus]],
    [t('Address'), [a.addressStreet, a.addressQuarter].filter(Boolean).join(', ') || null],
    [t('Identity document'), a.idDocumentType && [ID_DOCUMENTS[a.idDocumentType], a.idDocumentNumber, a.idDocumentIssuedOn && longDay(a.idDocumentIssuedOn), a.idDocumentIssuedAt].filter(Boolean).join(' · ')],
    [t('CNSS number'), a.cnssNumber],
    [t('Payment'), a.paymentMethod && [PAYMENT[a.paymentMethod], a.bankName, a.bankAccount, a.mobileMoneyOperator, a.mobileMoneyNumber].filter(Boolean).join(' · ')],
    [t('Family'), a.family.length ? a.family.map((m) => `${m.name} (${RELATION[m.relationship]}${m.birthDate ? `, ${longDay(m.birthDate)}` : ''})`).join('; ') : null],
  ]
  return (
    <div className="table-wrap"><table><tbody>
      {rows.map(([k, v]) => <tr key={k}><th>{k}</th><td>{v ?? <span className="muted">{t('Not given')}</span>}</td></tr>)}
    </tbody></table></div>
  )
}

function RefuseModal({ doc, onClose, onDone }: { doc: SentDocument; onClose: () => void; onDone: (reason: string) => Promise<void> }) {
  const [reason, setReason] = useState('')
  return (
    <Modal title={t('Refuse {a}', { a: doc.typeName })} onClose={onClose}>
      <Form onSubmit={() => onDone(reason)}>
        <label>{t('Why (the new hire reads it)')}<input required maxLength={300} value={reason} onChange={(e) => setReason(e.target.value)} placeholder={t('Blurred photo, expired card…')} /></label>
        <div className="modal-foot"><Button type="submit">{t('Refuse')}</Button></div>
      </Form>
    </Modal>
  )
}

function ExpiryModal({ doc, onClose, onDone }: { doc: SentDocument; onClose: () => void; onDone: (expiresOn: string) => Promise<void> }) {
  const [on, setOn] = useState('')
  return (
    <Modal title={t('Accept {a}', { a: doc.typeName })} onClose={onClose}>
      <Form onSubmit={() => onDone(on)}>
        <label>{t('Expires on')}<DateInput required value={on} min={today()} onChange={(e) => setOn(e.target.value)} />
          <span className="hint">{t('Read it on the paper: HR is reminded before it expires.')}</span></label>
        <div className="modal-foot"><Button type="submit">{t('Accept')}</Button></div>
      </Form>
    </Modal>
  )
}

function StartModal({ onClose, onDone }: { onClose: () => void; onDone: (userId: number) => void }) {
  const [q, setQ] = useState('')
  const [people, setPeople] = useState<{ id: number; firstName: string; lastName: string; registrationNo: string | null }[]>([])
  const [pick, setPick] = useState<number | null>(null)
  const [on, setOn] = useState(today())
  const [error, setError] = useState<string | null>(null)
  useEffect(() => {
    if (q.trim().length < 2) { setPeople([]); return }
    const h = setTimeout(() => { api<{ items: typeof people }>(`/api/users?q=${encodeURIComponent(q.trim())}&size=8`).then((p) => setPeople(p.items)).catch(() => setPeople([])) }, 250)
    return () => clearTimeout(h)
  }, [q])
  const start = async () => {
    setError(null)
    if (!pick) { setError(t('Choose the person.')); return }
    try { await api(`/api/onboarding/${pick}`, { method: 'POST', body: { startOn: on } }); onDone(pick) } catch (e) { setError((e as Error).message) }
  }
  return (
    <Modal title={t('Start an onboarding')} onClose={onClose}>
      <Form onSubmit={start}>
        <p className="small muted">{t('For someone hired without the recruitment pages. Hiring a candidate starts it by itself.')}</p>
        <label>{t('Person')}<input value={q} onChange={(e) => { setQ(e.target.value); setPick(null) }} placeholder={t('Name or matricule')} /></label>
        {people.length > 0 && (
          <ul className="checklist">
            {people.map((p) => (
              <li key={p.id} className={pick === p.id ? 'done' : ''}>
                <label className="check" style={{ flex: 1 }}><input type="radio" name="person" checked={pick === p.id} onChange={() => setPick(p.id)} />
                  {p.lastName} {p.firstName}{p.registrationNo && <span className="muted small"> · {p.registrationNo}</span>}</label>
              </li>
            ))}
          </ul>
        )}
        <label>{t('First day')}<DateInput required value={on} onChange={(e) => setOn(e.target.value)} /></label>
        <ErrorText error={error} />
        <div className="modal-foot"><Button type="submit">{t('Start')}</Button></div>
      </Form>
    </Modal>
  )
}

function WelcomeTextModal({ onClose }: { onClose: () => void }) {
  const [text, setText] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => { api<{ text: string | null }>('/api/onboarding/welcome-text').then((r) => setText(r.text ?? '')).catch((e: Error) => setError(e.message)) }, [])
  const save = async () => {
    setError(null)
    try { await api('/api/onboarding/welcome-text', { method: 'PUT', body: { text } }); onClose() } catch (e) { setError((e as Error).message) }
  }
  return (
    <Modal title={t('Welcome page')} onClose={onClose} size="lg">
      <Form onSubmit={save}>
        <p className="small muted">{t('What every new hire reads on their first day, under their manager, buddy and first steps: where to go, the hours, the rules, who to ask. Markdown: ## title, - list.')}</p>
        {text !== null && <textarea rows={14} maxLength={8000} value={text} onChange={(e) => setText(e.target.value)}
          placeholder={t('## Your first day\n- Come at 8:00 to reception, ground floor\n- Bring your ID card\n\n## Good to know\n- Working hours: 8:00 to 16:30\n- The rules of the house are in My documents')} />}
        <ErrorText error={error} />
        <div className="modal-foot"><Button type="submit">{t('Save')}</Button></div>
      </Form>
    </Modal>
  )
}

// ---- trial reviews -----------------------------------------------------------------------------------------------

function ReviewsTab() {
  const [rows, setRows] = useState<Review[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [open, setOpen] = useState<Review | null>(null)
  const load = () => api<Review[]>('/api/trial-reviews/mine').then(setRows).catch((e: Error) => setError(e.message))
  useEffect(() => { void load() }, [])
  return (
    <div className="card">
      <h3>{t('Trial reviews')}</h3>
      <p className="small muted">{t('One after the first month, one two weeks before the trial ends: there is still time to give the written notice (Labour Code art. 43).')}</p>
      <ErrorText error={error} />
      {rows && rows.length === 0 && <p className="muted">{t('No review to write.')}</p>}
      {rows && <ReviewList reviews={rows} withName onOpen={setOpen} />}
      {open && <ReviewModal review={open} onClose={() => setOpen(null)} onDone={async () => { setOpen(null); await load() }} />}
    </div>
  )
}

function ReviewList({ reviews, withName, onOpen }: { reviews: Review[]; withName?: boolean; onOpen: (r: Review) => void }) {
  const now = today()
  return (
    <ul className="checklist">
      {reviews.map((r) => (
        <li key={r.id} className={r.status === 'DONE' ? 'done' : ''}>
          <span style={{ flex: 1 }}>
            <span className="step-label">{withName ? `${r.name} · ${REVIEW_KIND[r.kind]}` : REVIEW_KIND[r.kind]}</span>
            <div className="small muted">
              {r.status === 'DONE'
                ? <>{t('Done {d}', { d: longDay(r.doneAt) })}{r.rating && <> · {'★'.repeat(r.rating)}</>}{r.recommendation && <> · <span className={r.recommendation === 'CONFIRM' ? 'success' : 'error'}>{r.recommendation === 'CONFIRM' ? t('Confirm') : t('End the trial')}</span></>}</>
                : <>{t('Due {d}', { d: longDay(r.dueOn) })}{r.dueOn < now && <> <span className="badge off">{t('Late')}</span></>}</>}
              {' · '}{t('trial ends {d}', { d: longDay(r.trialEndsOn) })}{r.reviewer && ` · ${r.reviewer}`}
            </div>
          </span>
          {r.status !== 'CANCELLED' && <Button className={r.status === 'DONE' ? 'secondary small' : 'small'} onClick={() => onOpen(r)}>{r.status === 'DONE' ? t('See') : t('Write')}</Button>}
        </li>
      ))}
    </ul>
  )
}

function ReviewModal({ review: r, onClose, onDone }: { review: Review; onClose: () => void; onDone: () => Promise<void> }) {
  const [f, setF] = useState({ rating: r.rating ?? 0, strengths: r.strengths ?? '', improvements: r.improvements ?? '', recommendation: r.recommendation ?? '', comment: r.comment ?? '' })
  const [error, setError] = useState<string | null>(null)
  const save = async () => {
    setError(null)
    if (!f.rating) { setError(t('Give a rating from 1 to 5.')); return }
    try {
      await api(`/api/trial-reviews/${r.id}`, { method: 'PUT', body: { ...f, recommendation: f.recommendation || null } })
      await onDone()
    } catch (e) { setError((e as Error).message) }
  }
  return (
    <Modal title={`${REVIEW_KIND[r.kind]} · ${r.name}`} onClose={onClose} size="lg">
      <Form onSubmit={save}>
        <p className="small muted">{t('The trial ends on {d}.', { d: longDay(r.trialEndsOn) })}{' '}
          {r.kind === 'END' ? t('Confirm or end it now: ending it needs a written notice before the last day.') : t('A short look at the first month: what goes well, what to work on.')}</p>
        <fieldset className="stars" aria-label={t('Rating')}>
          <span>{t('Overall')}</span>
          {[1, 2, 3, 4, 5].map((n) => (
            <button key={n} type="button" className={n <= f.rating ? 'star on' : 'star'} aria-label={String(n)} aria-pressed={n === f.rating} onClick={() => setF({ ...f, rating: n })}><Star size={20} aria-hidden /></button>
          ))}
        </fieldset>
        <label>{t('What goes well')}<textarea rows={3} maxLength={2000} value={f.strengths} onChange={(e) => setF({ ...f, strengths: e.target.value })} /></label>
        <label>{t('What to work on')}<textarea rows={3} maxLength={2000} value={f.improvements} onChange={(e) => setF({ ...f, improvements: e.target.value })} /></label>
        <label>{r.kind === 'END' ? t('Recommendation') : t('Recommendation (optional)')}
          <select required={r.kind === 'END'} value={f.recommendation} onChange={(e) => setF({ ...f, recommendation: e.target.value })}>
            <option value="">{r.kind === 'END' ? t('Choose…') : t('Too early to say')}</option>
            <option value="CONFIRM">{t('Confirm the hire')}</option>
            <option value="END">{t('End the trial')}</option>
          </select>
        </label>
        <label>{t('Comment')}<textarea rows={2} maxLength={2000} value={f.comment} onChange={(e) => setF({ ...f, comment: e.target.value })} /></label>
        {r.status === 'DONE' && <p className="small muted">{t('Written by {a} on {d}.', { a: r.doneBy ?? '', d: longDay(r.doneAt) })}</p>}
        <ErrorText error={error} />
        <div className="modal-foot"><Button type="submit">{t('Save the review')}</Button></div>
      </Form>
    </Modal>
  )
}

// ---- the new hire's welcome page ---------------------------------------------------------------------------------

/** /welcome: what a new hire reads on day one. */
export function WelcomePage() {
  const [w, setW] = useState<Welcome | null | undefined>(undefined)
  useEffect(() => {
    api<Welcome | undefined>('/api/onboarding/me').then((x) => {
      setW(x ?? null)
      if (x && !x.seen) void api('/api/onboarding/me/seen', { method: 'POST' }).catch(() => undefined)
    }).catch(() => setW(null))
  }, [])
  if (w === undefined) return null
  if (w === null) return <section><PageTitle title={t('Welcome')} /><div className="card"><p className="muted">{t('Nothing here: this page is for new hires, in their first weeks.')}</p></div></section>
  return (
    <section>
      <PageTitle title={t('Welcome, {name}', { name: w.firstName })} />
      <div className="tiles">
        <div className="tile"><span>{t('Your post')}</span><strong style={{ fontSize: 16 }}>{w.job ?? '—'}</strong><small>{w.unit ?? ''}</small></div>
        <div className="tile"><span><UserRound size={14} aria-hidden /> {t('Your manager')}</span><strong style={{ fontSize: 16 }}>{w.manager ?? '—'}</strong></div>
        <div className="tile"><span><Hand size={14} aria-hidden /> {t('Your buddy')}</span><strong style={{ fontSize: 16 }}>{w.buddy ?? t('Named soon')}</strong><small>{w.buddy ? t('Ask them anything, the small things too.') : ''}</small></div>
      </div>
      {w.text && <div className="card"><Markdown text={w.text} /></div>}
      {w.steps.length > 0 && (
        <div className="card">
          <h3>{t('Your first days')}</h3>
          <ul className="checklist">
            {w.steps.map((i) => (
              <li key={i.id} className={i.done ? 'done' : ''}>
                <input type="checkbox" checked={i.done} disabled aria-label={i.label} />
                <span style={{ flex: 1 }}><span className="step-label">{i.label}</span>
                  <div className="small muted">{STEP_OWNERS[i.owner as StepOwner] ?? i.owner}{i.dueOn && ` · ${longDay(i.dueOn)}`}</div></span>
              </li>
            ))}
          </ul>
        </div>
      )}
      <p className="small"><Link to="/my/documents">{t('My documents')}</Link> · <Link to="/account">{t('My account')}</Link> · <Link to="/help">{t('Help')}</Link></p>
    </section>
  )
}

/** On the home page of a new hire: a link to the welcome page in the first weeks. */
export function WelcomeBanner() {
  const [w, setW] = useState<Welcome | null>(null)
  useEffect(() => { api<Welcome | undefined>('/api/onboarding/me').then((x) => setW(x ?? null)).catch(() => undefined) }, [])
  if (!w) return null
  return (
    <div className="banner">
      <span className="banner-icon"><Hand size={20} /></span>
      <div style={{ flex: 1 }}>
        <strong>{t('Welcome to the team, {name}', { name: w.firstName })}</strong>
        <span className="muted">{w.buddy ? t('Your manager, your buddy ({a}) and your first steps are on your welcome page.', { a: w.buddy }) : t('Your manager and your first steps are on your welcome page.')}</span>
      </div>
      <Link className="button secondary" to="/welcome">{t('Open')}</Link>
    </div>
  )
}

