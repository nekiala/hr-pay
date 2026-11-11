import { useEffect, useMemo, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { ApiError, api, longDay, openFile } from '../api'
import { useAuth } from '../auth'
import { Button, Form } from '../components/Button'
import { ErrorText } from '../components/ErrorText'
import { Modal } from '../components/Modal'
import { PageTitle, SettingsLink } from '../components/SettingsLink'
import type { MyPerson } from '../components/UnitFilter'
import { t } from '../i18n'
import { DateInput, DateTimeInput } from '../components/DateFields'

export type CaseStatus = 'PROPOSED' | 'OPEN' | 'HEARING' | 'HEARD' | 'DECIDED' | 'NOTIFIED' | 'CLOSED' | 'REJECTED' | 'CANCELLED'

export type DisciplineCase = {
  id: number; userId: number; employeeName: string; registrationNo: string; number: string; status: CaseStatus
  origin: 'HR' | 'HEAD'; factsDate: string; knownDate: string; facts: string; ruleBroken: string | null
  latenessMonth: string | null; openedBy: string | null; openedByName: string | null; openedAt: string
  reviewedBy: string | null; reviewedAt: string | null; reviewNote: string | null; hearingAt: string | null
  hearingPlace: string | null; hearingLetterId: number | null; hearingLetterStatus: string | null; heardOn: string | null
  attended: boolean | null; statement: string | null; hearingNote: string | null; heardBy: string | null
  sanctionTypeId: number | null; sanctionCode: string | null; sanctionName: string | null; decisionNote: string | null
  decidedOn: string | null; decidedBy: string | null; suspensionFrom: string | null; suspensionTo: string | null
  suspensionDays: number | null; letterId: number | null; letterStatus: string | null; notifiedAt: string | null
  contest: string | null; contestedAt: string | null; cancelledBy: string | null; cancelledAt: string | null
  cancelReason: string | null; warnings: string[] | null
}

export type SanctionType = { id: number; code: string; name: string; weight: number; suspends: boolean; dismissal: boolean; letterCode: string | null; position: number; active: boolean }

export const CASE_STATUS: Record<CaseStatus, [string, string]> = {
  PROPOSED: [t('Proposed by the head'), 'warn'],
  OPEN: [t('Case open'), 'info'],
  HEARING: [t('Hearing set'), 'info'],
  HEARD: [t('Heard, to decide'), 'warn'],
  DECIDED: [t('Decided, to notify'), 'warn'],
  NOTIFIED: [t('Notified'), 'ok'],
  CLOSED: [t('Closed without sanction'), ''],
  REJECTED: [t('Case rejected'), 'off'],
  CANCELLED: [t('Case cancelled'), 'off'],
}

const LETTER: Record<string, string> = { DRAFT: t('draft, not sent'), SENT: t('sent, waiting for signature'), SIGNED: t('signed for receipt'), DECLINED: t('signature refused'), CANCELLED: t('cancelled') }

const errorText = (e: unknown) => (e instanceof ApiError ? [e.message, ...Object.values(e.fieldErrors)].join(' ') : t('Something went wrong.'))
const today = () => new Date().toISOString().slice(0, 10)

/**
 * The discipline register: facts, the hearing where the employee defends themself, HR's decision and its letter.
 * A unit head proposes a case for their team; HR accepts it and runs it.
 */
export default function DisciplinePage() {
  const { can } = useAuth()
  const hr = can('discipline.manage')
  const [params, setParams] = useSearchParams()
  const [cases, setCases] = useState<DisciplineCase[]>([])
  const [status, setStatus] = useState('ACTIVE')
  const [error, setError] = useState<string | null>(null)
  const [opening, setOpening] = useState<{ userId: string; month: string } | null>(null)
  const [shown, setShown] = useState<number | null>(null)

  const load = () => api<DisciplineCase[]>(`/api/discipline/cases${status ? `?status=${status}` : ''}`).then(setCases).catch((e: Error) => setError(e.message))
  useEffect(() => { void load() }, [status])
  useEffect(() => {
    const who = params.get('new')
    if (who) {
      setOpening({ userId: who, month: params.get('month') ?? '' })
      params.delete('new'); params.delete('month'); setParams(params, { replace: true })
    }
  }, [])

  return (
    <section>
      <PageTitle title={t('Discipline register')}>
        <Button onClick={() => setOpening({ userId: '', month: '' })}>{hr ? t('Open a case') : t('Propose a case')}</Button>
        {hr && <SettingsLink to="/discipline/settings" />}
      </PageTitle>
      <p className="muted">{hr
        ? t('Before any sanction the employee is invited to a hearing to explain themself (Labour Code art. 62). A sanction never takes money from pay: fines are forbidden (art. 111). Only a mise à pied makes its days unpaid.')
        : t('Propose a case for someone of your team: HR reviews it, hears the employee and decides.')}</p>
      <ErrorText error={error} />
      <div className="row">
        <label>{t('Show')}
          <select className="auto-width" value={status} onChange={(e) => setStatus(e.target.value)}>
            <option value="ACTIVE">{t('Cases in progress')}</option>
            <option value="">{t('Every case')}</option>
            {Object.entries(CASE_STATUS).map(([k, [label]]) => <option key={k} value={k}>{label}</option>)}
          </select>
        </label>
        <Link className="link" to="/reports/rankings?kind=FOLLOW_UP">{t('Lateness without follow-up')}</Link>
      </div>
      <div className="card">
        {cases.length === 0 ? <p className="muted">{t('No case.')}</p> : (
          <div className="table-wrap">
            <table className="lines">
              <thead><tr><th>{t('No.')}</th><th>{t('Employee')}</th><th>{t('Facts')}</th><th>{t('Status')}</th><th>{t('Sanction')}</th><th>{t('Opened')}</th></tr></thead>
              <tbody>
                {cases.map((c) => (
                  <tr key={c.id}>
                    <td><button type="button" className="link" onClick={() => setShown(c.id)}>{c.number}</button></td>
                    <td>{c.employeeName}<div className="small muted">{c.registrationNo}</div></td>
                    <td className="small">{longDay(c.factsDate)} · {c.facts.length > 90 ? `${c.facts.slice(0, 90)}…` : c.facts}</td>
                    <td><span className={`badge ${CASE_STATUS[c.status][1]}`}>{CASE_STATUS[c.status][0]}</span>
                      {(c.warnings?.length ?? 0) > 0 && <span className="badge warn" title={c.warnings?.join(' ')}>!</span>}</td>
                    <td>{c.sanctionName ?? '—'}{c.suspensionDays ? <div className="small muted">{t('{a} days from {b}', { a: c.suspensionDays, b: longDay(c.suspensionFrom) })}</div> : null}</td>
                    <td className="small">{longDay(c.openedAt)}<div className="muted">{c.openedByName}{c.origin === 'HEAD' ? ` · ${t('head')}` : ''}</div></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
      {opening && <OpenCase hr={hr} initial={opening} onClose={() => setOpening(null)} onDone={(c) => { setOpening(null); setShown(c.id); void load() }} />}
      {shown !== null && <CaseModal id={shown} onClose={() => { setShown(null); void load() }} />}
    </section>
  )
}

function OpenCase({ hr, initial, onClose, onDone }: { hr: boolean; initial: { userId: string; month: string }; onClose: () => void; onDone: (c: DisciplineCase) => void }) {
  const [people, setPeople] = useState<MyPerson[]>([])
  const [userId, setUserId] = useState(initial.userId)
  const [factsDate, setFactsDate] = useState(initial.month ? `${initial.month}-01` : today())
  const [knownDate, setKnownDate] = useState(today())
  const [facts, setFacts] = useState(initial.month ? t('Late on several days in {a}, beyond the grace period.', { a: initial.month }) : '')
  const [rule, setRule] = useState('')
  const [error, setError] = useState<string | null>(null)
  useEffect(() => { api<MyPerson[]>('/api/org/my-people').then(setPeople).catch(() => undefined) }, [])
  const sorted = useMemo(() => [...people].sort((a, b) => a.name.localeCompare(b.name)), [people])
  const submit = async () => {
    setError(null)
    try {
      onDone(await api<DisciplineCase>('/api/discipline/cases', { method: 'POST', body: {
        userId: Number(userId), factsDate, knownDate, facts, ruleBroken: rule, latenessMonth: initial.month ? `${initial.month}-01` : null,
      } }))
    } catch (e) { setError(errorText(e)) }
  }
  return (
    <Modal title={hr ? t('Open a discipline case') : t('Propose a discipline case')} onClose={onClose}
      footer={<><Button className="secondary" type="button" onClick={onClose}>{t('Cancel')}</Button><Button type="submit" form="open-case">{hr ? t('Open the case') : t('Propose the case')}</Button></>}>
      <Form id="open-case" onSubmit={submit}>
        <label>{t('Employee')}
          <select required value={userId} onChange={(e) => setUserId(e.target.value)}>
            <option value="">{t('Choose…')}</option>
            {sorted.map((p) => <option key={p.userId} value={p.userId}>{p.name} ({p.registrationNo})</option>)}
          </select>
        </label>
        <div className="grid2">
          <label>{t('Date of the facts')}<DateInput required max={today()} value={factsDate} onChange={(e) => setFactsDate(e.target.value)} /></label>
          <label>{t('Known on')}<DateInput required max={today()} value={knownDate} onChange={(e) => setKnownDate(e.target.value)} /></label>
        </div>
        <p className="hint">{t('The day the company learned of the facts: a dismissal for serious misconduct must be notified within 15 working days of it (art. 72).')}</p>
        <label>{t('Facts')}<textarea required rows={4} value={facts} onChange={(e) => setFacts(e.target.value)} placeholder={t('What happened, when and where, and who saw it.')} /></label>
        <label>{t('Rule not followed (optional)')}<input value={rule} onChange={(e) => setRule(e.target.value)} placeholder={t('For example: company rules art. 12, working hours')} /></label>
        <ErrorText error={error} />
      </Form>
    </Modal>
  )
}

/** One case and the next step: review, hearing, decision, letters, cancellation. */
function CaseModal({ id, onClose }: { id: number; onClose: () => void }) {
  const { can } = useAuth()
  const hr = can('discipline.manage')
  const [c, setC] = useState<DisciplineCase | null>(null)
  const [types, setTypes] = useState<SanctionType[]>([])
  const [error, setError] = useState<string | null>(null)
  const [editing, setEditing] = useState<'hearing' | 'decision' | null>(null)
  useEffect(() => {
    api<DisciplineCase>(`/api/discipline/cases/${id}`).then(setC).catch((e: Error) => setError(e.message))
    api<SanctionType[]>('/api/discipline/types').then(setTypes).catch(() => undefined)
  }, [id])
  const act = async (path: string, body?: unknown) => {
    setError(null)
    try { setC(await api<DisciplineCase>(`/api/discipline/cases/${id}${path}`, { method: 'POST', body })) } catch (e) { setError(errorText(e)) }
  }
  if (!c) return <Modal title={t('Discipline case')} onClose={onClose}><ErrorText error={error} /></Modal>
  const open = (which: string) => openFile(`/api/discipline/cases/${id}/letters/${which}/file`).catch((e: Error) => setError(e.message))
  const live = !['CLOSED', 'REJECTED', 'CANCELLED'].includes(c.status)

  return (
    <Modal title={`${c.number} · ${c.employeeName}`} onClose={onClose} size="lg">
      <p><span className={`badge ${CASE_STATUS[c.status][1]}`}>{CASE_STATUS[c.status][0]}</span>{' '}
        <span className="small muted">{t('Opened {a} by {b}', { a: longDay(c.openedAt), b: c.openedByName ?? c.openedBy })}{c.origin === 'HEAD' ? ` (${t('unit head')})` : ''}</span></p>
      {(c.warnings ?? []).map((w) => <p key={w} className="notice">{w}</p>)}
      <ErrorText error={error} />

      <h4>{t('Facts')}</h4>
      <p className="small muted">{t('On {a}, known on {b}', { a: longDay(c.factsDate), b: longDay(c.knownDate) })}</p>
      <p className="pre">{c.facts}</p>
      {c.ruleBroken && <p className="small">{t('Rule not followed: {a}', { a: c.ruleBroken })}</p>}
      {c.reviewNote && <p className="small muted">{t('HR: {a}', { a: c.reviewNote })}</p>}

      {c.status === 'PROPOSED' && hr && <Review onDone={(accept, note) => act('/review', { accept, note })} />}

      {(c.hearingAt || (hr && ['OPEN', 'HEARING'].includes(c.status))) && <h4>{t('Hearing')}</h4>}
      {c.hearingAt && <p>{t('{a} at {b}', { a: longDay(c.hearingAt), b: c.hearingAt.slice(11, 16) })}, {c.hearingPlace}</p>}
      {hr && c.hearingLetterId && (
        <p className="row small">
          <span>{t('Invitation: {a}', { a: LETTER[c.hearingLetterStatus ?? ''] ?? '' })}</span>
          <Button className="link" onClick={() => open('hearing')}>{t('Read (PDF)')}</Button>
          {c.hearingLetterStatus === 'DRAFT' && <>
            <Button className="link" onClick={() => setEditing('hearing')}>{t('Edit the text')}</Button>
            <Button onClick={() => act('/letters/hearing/send')}>{t('Send the invitation')}</Button>
          </>}
        </p>
      )}
      {hr && ['OPEN', 'HEARING'].includes(c.status) && <Convene c={c} onDone={(at, place) => act('/hearing', { at, place })} />}
      {hr && c.status === 'HEARING' && <Heard c={c} onDone={(body) => act('/heard', body)} />}
      {c.heardOn && (
        <div className="small">
          <p>{c.attended === false ? t('Heard on {a}: the employee did not come.', { a: longDay(c.heardOn) }) : t('Heard on {a}.', { a: longDay(c.heardOn) })}</p>
          {c.statement && <p className="pre"><strong>{t('The employee\'s explanation')}:</strong> {c.statement}</p>}
          {c.hearingNote && <p className="pre muted">{t('Notes')}: {c.hearingNote}</p>}
        </div>
      )}

      {c.status === 'HEARD' && can('discipline.decide') && <Decide types={types} onDone={(body) => act('/decision', body)} />}
      {c.decidedOn && (
        <>
          <h4>{t('Decision')}</h4>
          <p>{c.sanctionName ?? t('No sanction')} · {longDay(c.decidedOn)}
            {c.suspensionDays ? ` · ${t('{a} days, from {b} to {c}, unpaid', { a: c.suspensionDays, b: longDay(c.suspensionFrom), c: longDay(c.suspensionTo) })}` : ''}</p>
          {c.decisionNote && <p className="small pre">{c.decisionNote}</p>}
        </>
      )}
      {hr && c.letterId && (
        <p className="row small">
          <span>{t('Letter: {a}', { a: LETTER[c.letterStatus ?? ''] ?? '' })}</span>
          <Button className="link" onClick={() => open('decision')}>{t('Read (PDF)')}</Button>
          {c.letterStatus === 'DRAFT' && <>
            <Button className="link" onClick={() => setEditing('decision')}>{t('Edit the text')}</Button>
            <Button onClick={() => act('/letters/decision/send')}>{t('Send the letter')}</Button>
          </>}
        </p>
      )}
      {hr && c.sanctionCode === 'DISMISSAL' && ['DECIDED', 'NOTIFIED'].includes(c.status) && (
        <p className="hint">{t('Record the departure with the reason "serious misconduct" on the Departures page: it ends the contract and works out the final pay.')} <Link to="/departures">{t('Departures')}</Link></p>
      )}
      {c.contest && <><h4>{t('Contested by the employee')}</h4><p className="small muted">{longDay(c.contestedAt)}</p><p className="pre">{c.contest}</p></>}
      {c.status === 'CANCELLED' && <p className="small muted">{t('Cancelled on {a}: {b}', { a: longDay(c.cancelledAt), b: c.cancelReason ?? '' })}</p>}
      {hr && live && <Cancel onDone={(reason) => act('/cancel', { reason })} />}
      {editing && <EditLetter caseId={id} which={editing} onClose={() => setEditing(null)} onDone={(x) => { setC(x); setEditing(null) }} />}
    </Modal>
  )
}

function Review({ onDone }: { onDone: (accept: boolean, note: string) => Promise<void> }) {
  const [note, setNote] = useState('')
  return (
    <div className="card">
      <label>{t('Note to the head (required to reject)')}<input value={note} onChange={(e) => setNote(e.target.value)} /></label>
      <div className="row">
        <Button onClick={() => onDone(true, note)}>{t('Accept the case')}</Button>
        <Button className="secondary" onClick={() => onDone(false, note)}>{t('Reject the case')}</Button>
      </div>
    </div>
  )
}

function Convene({ c, onDone }: { c: DisciplineCase; onDone: (at: string, place: string) => Promise<void> }) {
  const [at, setAt] = useState(c.hearingAt?.slice(0, 16) ?? '')
  const [place, setPlace] = useState(c.hearingPlace ?? '')
  return (
    <Form className="card" onSubmit={() => onDone(at.length === 16 ? `${at}:00` : at, place)}>
      <div className="grid2">
        <label>{t('Day and time')}<DateTimeInput required value={at} onChange={(e) => setAt(e.target.value)} /></label>
        <label>{t('Place')}<input required value={place} onChange={(e) => setPlace(e.target.value)} placeholder={t('For example: the HR office')} /></label>
      </div>
      <p className="hint">{t('A letter inviting the employee is drafted from the template "Invitation to a disciplinary hearing". Read it, then send it: the employee signs it for receipt in HR Pay.')}</p>
      <Button type="submit" className="secondary">{c.hearingAt ? t('Move the hearing') : t('Set the hearing')}</Button>
    </Form>
  )
}

function Heard({ c, onDone }: { c: DisciplineCase; onDone: (body: unknown) => Promise<void> }) {
  const [on, setOn] = useState(c.hearingAt?.slice(0, 10) ?? today())
  const [attended, setAttended] = useState(true)
  const [statement, setStatement] = useState('')
  const [note, setNote] = useState('')
  return (
    <Form className="card" onSubmit={() => onDone({ on, attended, statement, note })}>
      <div className="grid2">
        <label>{t('Held on')}<DateInput required max={today()} value={on} onChange={(e) => setOn(e.target.value)} /></label>
        <label className="check"><input type="checkbox" checked={!attended} onChange={(e) => setAttended(!e.target.checked)} />{t('The employee did not come')}</label>
      </div>
      <label>{t('What the employee said in their defence')}<textarea rows={3} required={attended} value={statement} onChange={(e) => setStatement(e.target.value)} /></label>
      <label>{t('Notes (not shown to the employee)')}<textarea rows={2} value={note} onChange={(e) => setNote(e.target.value)} /></label>
      <Button type="submit" className="secondary">{t('Record the hearing')}</Button>
    </Form>
  )
}

function Decide({ types, onDone }: { types: SanctionType[]; onDone: (body: unknown) => Promise<void> }) {
  const [typeId, setTypeId] = useState('')
  const [note, setNote] = useState('')
  const [from, setFrom] = useState('')
  const [days, setDays] = useState('1')
  const chosen = types.find((x) => String(x.id) === typeId)
  return (
    <Form className="card" onSubmit={() => onDone({
      sanctionTypeId: typeId ? Number(typeId) : null, note,
      suspensionFrom: chosen?.suspends ? from : null, suspensionDays: chosen?.suspends ? Number(days) : null,
    })}>
      <h4>{t('Decision')}</h4>
      <label>{t('Sanction')}
        <select value={typeId} onChange={(e) => setTypeId(e.target.value)}>
          <option value="">{t('No sanction: close the case')}</option>
          {types.filter((x) => x.active).map((x) => <option key={x.id} value={x.id}>{x.name}</option>)}
        </select>
      </label>
      {chosen?.suspends && (
        <div className="grid2">
          <label>{t('First day')}<DateInput required min={today()} value={from} onChange={(e) => setFrom(e.target.value)} /></label>
          <label>{t('Days')}<input type="number" min={1} max={15} required value={days} onChange={(e) => setDays(e.target.value)} /></label>
        </div>
      )}
      {chosen?.suspends && <p className="hint">{t('The days are recorded as unpaid leave: the employee does not work and is not paid for them. At most 15 days at a time and 30 days a year (art. 57), and only when your company rules provide it.')}</p>}
      {chosen?.dismissal && <p className="hint">{t('After the letter, record the departure on the Departures page.')}</p>}
      <label>{t('Reasons for the decision')}<textarea rows={2} required={!typeId} value={note} onChange={(e) => setNote(e.target.value)} /></label>
      <Button type="submit">{typeId ? t('Decide the sanction') : t('Close without sanction')}</Button>
    </Form>
  )
}

function Cancel({ onDone }: { onDone: (reason: string) => Promise<void> }) {
  const [open, setOpen] = useState(false)
  const [reason, setReason] = useState('')
  if (!open) return <p><Button className="link" onClick={() => setOpen(true)}>{t('Cancel this case…')}</Button></p>
  return (
    <Form className="card" onSubmit={() => onDone(reason)}>
      <label>{t('Why is it cancelled?')}<input required value={reason} onChange={(e) => setReason(e.target.value)} /></label>
      <p className="hint">{t('The case stays in the history but no longer counts. The days of a mise à pied are given back.')}</p>
      <div className="row"><Button type="submit" className="secondary">{t('Cancel the case')}</Button><Button className="link" type="button" onClick={() => setOpen(false)}>{t('Keep it')}</Button></div>
    </Form>
  )
}

function EditLetter({ caseId, which, onClose, onDone }: { caseId: number; which: 'hearing' | 'decision'; onClose: () => void; onDone: (c: DisciplineCase) => void }) {
  const [title, setTitle] = useState('')
  const [body, setBody] = useState('')
  const [error, setError] = useState<string | null>(null)
  useEffect(() => {
    api<{ title: string; body: string }>(`/api/discipline/cases/${caseId}/letters/${which}`)
      .then((l) => { setTitle(l.title); setBody(l.body) }).catch((e: Error) => setError(e.message))
  }, [caseId, which])
  const save = async () => {
    try { onDone(await api<DisciplineCase>(`/api/discipline/cases/${caseId}/letters/${which}`, { method: 'PUT', body: { title, body } })) } catch (e) { setError(errorText(e)) }
  }
  return (
    <Modal title={t('Edit the letter')} onClose={onClose} size="lg"
      footer={<><Button className="secondary" type="button" onClick={onClose}>{t('Cancel')}</Button><Button type="submit" form="edit-letter">{t('Save')}</Button></>}>
      <Form id="edit-letter" onSubmit={save}>
        <label>{t('Title')}<input required value={title} onChange={(e) => setTitle(e.target.value)} /></label>
        <label>{t('Text')}<textarea rows={16} required value={body} onChange={(e) => setBody(e.target.value)} /></label>
        <ErrorText error={error} />
      </Form>
    </Modal>
  )
}

/** The employee's own discipline cases, once invited to a hearing: the facts, the decision, and a written contest. */
export function MyDisciplineCard() {
  const [cases, setCases] = useState<DisciplineCase[]>([])
  const [contesting, setContesting] = useState<DisciplineCase | null>(null)
  const [text, setText] = useState('')
  const [error, setError] = useState<string | null>(null)
  const load = () => api<DisciplineCase[]>('/api/my/discipline').then(setCases).catch(() => undefined)
  useEffect(() => { void load() }, [])
  if (cases.length === 0) return null
  const send = async () => {
    setError(null)
    try {
      await api(`/api/my/discipline/${contesting?.id}/contest`, { method: 'POST', body: { text } })
      setContesting(null)
      setText('')
      await load()
    } catch (e) { setError(errorText(e)) }
  }
  return (
    <div className="card">
      <h3>{t('Discipline')}</h3>
      <p className="hint">{t('The letters themselves are in your documents above. You may contest a sanction in writing once; HR reads it and may withdraw the sanction.')}</p>
      <div className="table-wrap">
        <table className="lines">
          <thead><tr><th>{t('No.')}</th><th>{t('Facts')}</th><th>{t('Hearing')}</th><th>{t('Decision')}</th><th></th></tr></thead>
          <tbody>
            {cases.map((c) => (
              <tr key={c.id}>
                <td>{c.number}</td>
                <td className="small">{longDay(c.factsDate)} · {c.facts}</td>
                <td className="small">{c.hearingAt ? `${longDay(c.hearingAt)} ${c.hearingAt.slice(11, 16)}, ${c.hearingPlace ?? ''}` : '—'}</td>
                <td className="small">
                  {c.status === 'CANCELLED' ? t('Cancelled: it no longer counts.') : c.status === 'CLOSED' ? t('Closed without sanction') : c.sanctionName ?? t('Not decided yet')}
                  {c.suspensionDays && c.status !== 'CANCELLED' ? <div>{t('{a} days, from {b} to {c}, unpaid', { a: c.suspensionDays, b: longDay(c.suspensionFrom), c: longDay(c.suspensionTo) })}</div> : null}
                  {c.contestedAt && <div className="muted">{t('You contested it on {a}.', { a: longDay(c.contestedAt) })}</div>}
                </td>
                <td>{c.status === 'NOTIFIED' && !c.contestedAt && <Button className="secondary" onClick={() => setContesting(c)}>{t('Contest')}</Button>}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {contesting && (
        <Modal title={t('Contest the sanction {a}', { a: contesting.number })} onClose={() => setContesting(null)}
          footer={<><Button className="secondary" type="button" onClick={() => setContesting(null)}>{t('Cancel')}</Button><Button type="submit" form="contest">{t('Send')}</Button></>}>
          <Form id="contest" onSubmit={send}>
            <label>{t('Why do you contest it?')}<textarea rows={5} required value={text} onChange={(e) => setText(e.target.value)} /></label>
            <ErrorText error={error} />
          </Form>
        </Modal>
      )}
    </div>
  )
}
