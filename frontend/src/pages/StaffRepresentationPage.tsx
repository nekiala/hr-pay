import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { ApiError, api, fileToBase64, longDay, openFile } from '../api'
import { Button, Form } from '../components/Button'
import { useAsk } from '../components/Dialogs'
import { DateInput } from '../components/DateFields'
import { ErrorText } from '../components/ErrorText'
import { Modal } from '../components/Modal'
import { PageTitle } from '../components/SettingsLink'
import { PersonSelect } from '../components/PersonSelect'
import { t } from '../i18n'

type Seat = 'TITULAR' | 'ALTERNATE'
type EventKind = 'ELECTION' | 'MEETING' | 'RULES_SENT'
type Action = 'DISMISSAL' | 'TRANSFER'

type Delegate = {
  id: number; userId: number; name: string; registrationNo: string; unit: string | null; seat: Seat; unionName: string | null
  electedOn: string; termEnd: string; endedOn: string | null; endReason: string | null; active: boolean
}
type RepEvent = {
  id: number; kind: EventKind; heldOn: string; announcedOn: string | null; title: string; note: string | null
  inspectorCopyOn: string | null; fileName: string | null; hasFile: boolean; createdBy: string; createdAt: string
}
type Authorisation = {
  id: number; userId: number; name: string; action: Action; decidedOn: string; reference: string; granted: boolean
  note: string | null; fileName: string | null; hasFile: boolean; createdBy: string
}
type Overview = {
  headcount: number; fromWorkers: number; requiredSeats: number | null; titulars: number; alternates: number
  nextTermEnd: string | null; lastMeeting: string | null; nextMeetingDue: string | null; rulesSentOn: string | null
  alerts: string[]; delegates: Delegate[]; events: RepEvent[]; authorisations: Authorisation[]
}
type HoursEntry = { id: number; userId: number; name: string; onDay: string; hours: number; note: string | null; createdBy: string }
type Hours = { month: string; min: number; max: number; totals: { userId: number; name: string; seat: Seat; hours: number }[]; entries: HoursEntry[] }
type Saved<T> = { item: T; warnings: string[] }

const SEATS: Record<Seat, string> = { TITULAR: t('Delegate'), ALTERNATE: t('Alternate') }
const KINDS: Record<EventKind, string> = { ELECTION: t('Election'), MEETING: t('Meeting with the delegates'), RULES_SENT: t('Company rules sent') }
const ACTIONS: Record<Action, string> = { DISMISSAL: t('Dismissal'), TRANSFER: t('Transfer') }

const errorText = (e: unknown) => (e instanceof ApiError ? [e.message, ...Object.values(e.fieldErrors)].join(' ') : t('Something went wrong.'))
const today = () => new Date().toISOString().slice(0, 10)
const thisMonth = () => new Date().toISOString().slice(0, 7)

/**
 * The staff representation (délégation syndicale): who holds a mandate, elections and quarterly meetings, the company
 * rules sent, the labour inspector's authorisations to dismiss or transfer a delegate, and the hours of the mandate.
 */
export default function StaffRepresentationPage() {
  const [data, setData] = useState<Overview | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [modal, setModal] = useState<'delegate' | 'event' | 'authorisation' | 'hours' | null>(null)
  const [ending, setEnding] = useState<Delegate | null>(null)
  const [warnings, setWarnings] = useState<string[]>([])
  const ask = useAsk()
  const load = () => api<Overview>('/api/representation').then(setData).catch((e: Error) => setError(e.message))
  useEffect(() => {
    void load()
  }, [])
  const done = (w: string[] = []) => { setModal(null); setEnding(null); setWarnings(w); void load() }
  const remove = (path: string, question: string) => ask({
    title: question, confirmLabel: t('Delete'), danger: true,
    onConfirm: async () => { await api(path, { method: 'DELETE' }); done() },
  })
  const copySent = async (e: RepEvent) => {
    try { await api(`/api/representation/events/${e.id}/inspector-copy`, { method: 'PUT', body: { inspectorCopyOn: today() } }); done() } catch (x) { setError(errorText(x)) }
  }
  if (!data) return <section><PageTitle title={t('Staff representation')} /><ErrorText error={error} /></section>
  const active = data.delegates.filter((d) => d.active)
  const past = data.delegates.filter((d) => !d.active)
  return (
    <section>
      <PageTitle title={t('Staff representation')}>
        <Button onClick={() => setModal('delegate')}>{t('Add a delegate')}</Button>
      </PageTitle>
      <p className="muted">{t('The délégation syndicale is elected in every establishment of at least {a} workers, for three years (arrêté 048/2015). Dismissing or transferring a delegate or an alternate needs the labour inspector\'s authorisation first (arrêté 036/95): HR Pay stops the dismissal, the departure and the transfer until it is recorded here.', { a: data.fromWorkers })}</p>
      <ErrorText error={error} />
      {warnings.length > 0 && <div className="notice small">{warnings.map((w) => <div key={w}>{w}</div>)}</div>}

      <div className="tiles">
        <div className="tile"><span>{t('Workers under contract')}</span><strong>{data.headcount}</strong><small>{t('Delegates from {a} workers', { a: data.fromWorkers })}</small></div>
        <div className={`tile${data.requiredSeats !== null && data.titulars < data.requiredSeats ? ' warn' : ''}`}><span>{t('Delegates / alternates')}</span><strong>{data.titulars} / {data.alternates}</strong>
          <small>{data.requiredSeats === null ? t('Number of seats: to check with your adviser') : t('The law asks for {a}', { a: data.requiredSeats })}</small></div>
        <div className="tile"><span>{t('Next meeting due')}</span><strong>{longDay(data.nextMeetingDue)}</strong><small>{t('Last one: {a}', { a: longDay(data.lastMeeting) })}</small></div>
        <div className="tile"><span>{t('Mandates end')}</span><strong>{longDay(data.nextTermEnd)}</strong><small>{t('Company rules sent: {a}', { a: longDay(data.rulesSentOn) })}</small></div>
      </div>
      {data.alerts.length > 0 && <div className="notice">{data.alerts.map((a) => <div key={a}>{a}</div>)}</div>}

      <div className="card">
        <h3>{t('Delegates')}</h3>
        <div className="table-wrap">
          <table>
            <thead><tr><th>{t('Person')}</th><th>{t('Seat')}</th><th>{t('Union')}</th><th>{t('Elected on')}</th><th>{t('Mandate ends')}</th><th /></tr></thead>
            <tbody>
              {active.length === 0 && <tr><td colSpan={6} className="muted">{t('No delegate recorded.')}</td></tr>}
              {[...active, ...past].map((d) => (
                <tr key={d.id} className={d.active ? '' : 'muted'}>
                  <td><Link to={`/users/${d.userId}`}>{d.name}</Link> <span className="small muted">{d.registrationNo}</span>{d.unit && <div className="small muted">{d.unit}</div>}</td>
                  <td><span className={`badge ${d.seat === 'TITULAR' ? 'info' : ''}`}>{SEATS[d.seat]}</span></td>
                  <td>{d.unionName ?? '—'}</td>
                  <td>{longDay(d.electedOn)}</td>
                  <td>{d.endedOn ? <>{t('Ended {a}', { a: longDay(d.endedOn) })}<div className="small muted">{d.endReason}</div></> : longDay(d.termEnd)}</td>
                  <td className="row">
                    {d.active && <Button className="secondary small" onClick={() => setEnding(d)}>{t('End the mandate')}</Button>}
                    <button type="button" className="link small" onClick={() => remove(`/api/representation/delegates/${d.id}`, t('Delete this entry, made by mistake?'))}>{t('Delete')}</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      <div className="card">
        <div className="row space"><h3>{t('Elections, meetings and company rules')}</h3><Button className="secondary" onClick={() => setModal('event')}>{t('Add a record')}</Button></div>
        <p className="small muted">{t('The employer meets the délégation every three months; the minutes, signed by all, go to the labour inspector within eight days (art. 37). The company rules go to the delegates and the inspector (Labour Code art. 157).')}</p>
        <div className="table-wrap">
          <table>
            <thead><tr><th>{t('Date')}</th><th>{t('What')}</th><th>{t('Inspector\'s copy')}</th><th>{t('File')}</th><th /></tr></thead>
            <tbody>
              {data.events.length === 0 && <tr><td colSpan={5} className="muted">{t('Nothing recorded yet.')}</td></tr>}
              {data.events.map((e) => (
                <tr key={e.id}>
                  <td>{longDay(e.heldOn)}{e.announcedOn && <div className="small muted">{t('announced {a}', { a: longDay(e.announcedOn) })}</div>}</td>
                  <td><span className="badge">{KINDS[e.kind]}</span> {e.title}{e.note && <div className="small muted">{e.note}</div>}</td>
                  <td>{e.inspectorCopyOn ? longDay(e.inspectorCopyOn) : e.kind === 'ELECTION' ? '—'
                    : <button type="button" className="link" onClick={() => void copySent(e)}>{t('Mark as sent today')}</button>}</td>
                  <td>{e.hasFile ? <button type="button" className="link" onClick={() => void openFile(`/api/representation/events/${e.id}/file`)}>{e.fileName}</button> : '—'}</td>
                  <td><button type="button" className="link small" onClick={() => remove(`/api/representation/events/${e.id}`, t('Delete this record?'))}>{t('Delete')}</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      <div className="card">
        <div className="row space"><h3>{t('Labour inspector\'s authorisations')}</h3><Button className="secondary" onClick={() => setModal('authorisation')}>{t('Record an authorisation')}</Button></div>
        <div className="table-wrap">
          <table>
            <thead><tr><th>{t('Person')}</th><th>{t('For')}</th><th>{t('Decided on')}</th><th>{t('Reference')}</th><th>{t('Decision')}</th><th>{t('File')}</th></tr></thead>
            <tbody>
              {data.authorisations.length === 0 && <tr><td colSpan={6} className="muted">{t('None recorded.')}</td></tr>}
              {data.authorisations.map((a) => (
                <tr key={a.id}>
                  <td>{a.name}</td><td>{ACTIONS[a.action]}</td><td>{longDay(a.decidedOn)}</td><td>{a.reference}</td>
                  <td><span className={`badge ${a.granted ? 'ok' : 'off'}`}>{a.granted ? t('Authorised') : t('Refused')}</span>{a.note && <div className="small muted">{a.note}</div>}</td>
                  <td>{a.hasFile ? <button type="button" className="link" onClick={() => void openFile(`/api/representation/authorisations/${a.id}/file`)}>{a.fileName}</button> : '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      <HoursCard delegates={active} onAdd={() => setModal('hours')} key={String(modal === null)} />

      {modal === 'delegate' && <DelegateModal onClose={() => setModal(null)} onDone={done} />}
      {modal === 'event' && <EventModal onClose={() => setModal(null)} onDone={done} />}
      {modal === 'authorisation' && <AuthorisationModal delegates={data.delegates} onClose={() => setModal(null)} onDone={done} />}
      {modal === 'hours' && <HoursModal delegates={active} onClose={() => setModal(null)} onDone={done} />}
      {ending && <EndModal delegate={ending} onClose={() => setEnding(null)} onDone={done} />}
    </section>
  )
}

function HoursCard({ delegates, onAdd }: { delegates: Delegate[]; onAdd: () => void }) {
  const [month, setMonth] = useState(thisMonth())
  const [hours, setHours] = useState<Hours | null>(null)
  const [error, setError] = useState<string | null>(null)
  const load = () => api<Hours>(`/api/representation/hours?month=${month}`).then(setHours).catch((e: Error) => setError(e.message))
  useEffect(() => { void load() }, [month])
  const remove = async (id: number) => {
    try { await api(`/api/representation/hours/${id}`, { method: 'DELETE' }); void load() } catch (e) { setError(errorText(e)) }
  }
  return (
    <div className="card">
      <div className="row space">
        <h3>{t('Hours of the mandate')}</h3>
        <div className="row">
          <input type="month" className="auto-width" value={month} onChange={(e) => setMonth(e.target.value)} aria-label={t('Month')} />
          {delegates.length > 0 && <Button className="secondary" onClick={onAdd}>{t('Add hours')}</Button>}
        </div>
      </div>
      {hours && <p className="small muted">{hours.max > 0
        ? t('Delegates have {a} to {b} hours a month for the mandate, paid as working time (arrêté 048/2015 art. 35).', { a: hours.min, b: hours.max })
        : t('Hours of the mandate, paid as working time.')}</p>}
      <ErrorText error={error} />
      {hours && (
        <div className="table-wrap">
          <table>
            <thead><tr><th>{t('Delegate')}</th><th>{t('Hours this month')}</th></tr></thead>
            <tbody>
              {hours.totals.length === 0 && <tr><td colSpan={2} className="muted">{t('No delegate in this month.')}</td></tr>}
              {hours.totals.map((h) => (
                <tr key={h.userId}><td>{h.name} <span className="small muted">{SEATS[h.seat]}</span></td>
                  <td><span className={`badge ${hours.max > 0 && h.hours > hours.max ? 'warn' : ''}`}>{h.hours} h</span>{hours.max > 0 && <span className="small muted"> / {hours.max} h</span>}</td></tr>
              ))}
            </tbody>
          </table>
          {hours.entries.length > 0 && (
            <table className="lines">
              <thead><tr><th>{t('Day')}</th><th>{t('Delegate')}</th><th>{t('Hours')}</th><th>{t('Note')}</th><th /></tr></thead>
              <tbody>
                {hours.entries.map((e) => (
                  <tr key={e.id}><td>{longDay(e.onDay)}</td><td>{e.name}</td><td>{e.hours}</td><td className="small">{e.note}</td>
                    <td><button type="button" className="link small" onClick={() => remove(e.id)}>{t('Delete')}</button></td></tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      )}
    </div>
  )
}

/** A select over a short list (the delegates). */
function ListSelect({ people, value, onChange }: { people: { userId: number; name: string; registrationNo: string }[]; value: string; onChange: (v: string) => void }) {
  return (
    <select required value={value} onChange={(e) => onChange(e.target.value)}>
      <option value="">{t('Choose…')}</option>
      {people.map((p) => <option key={p.userId} value={p.userId}>{p.name} ({p.registrationNo})</option>)}
    </select>
  )
}

function DelegateModal({ onClose, onDone }: { onClose: () => void; onDone: (w: string[]) => void }) {
  const [userId, setUserId] = useState('')
  const [seat, setSeat] = useState<Seat>('TITULAR')
  const [unionName, setUnionName] = useState('')
  const [electedOn, setElectedOn] = useState(today())
  const [error, setError] = useState<string | null>(null)
  const submit = async () => {
    setError(null)
    try {
      const r = await api<Saved<Delegate>>('/api/representation/delegates', { method: 'POST', body: { userId: Number(userId), seat, unionName, electedOn } })
      onDone(r.warnings)
    } catch (e) { setError(errorText(e)) }
  }
  return (
    <Modal title={t('Add a delegate')} onClose={onClose}
      footer={<><Button className="secondary" type="button" onClick={onClose}>{t('Cancel')}</Button><Button type="submit" form="delegate">{t('Add')}</Button></>}>
      <Form id="delegate" onSubmit={submit}>
        <label>{t('Person')}<PersonSelect required value={userId} onChange={setUserId} /></label>
        <div className="grid2">
          <label>{t('Seat')}
            <select value={seat} onChange={(e) => setSeat(e.target.value as Seat)}>
              <option value="TITULAR">{t('Delegate')}</option><option value="ALTERNATE">{t('Alternate')}</option>
            </select>
          </label>
          <label>{t('Elected on')}<DateInput required max={today()} value={electedOn} onChange={(e) => setElectedOn(e.target.value)} /></label>
        </div>
        <label>{t('Union (optional)')}<input maxLength={150} value={unionName} onChange={(e) => setUnionName(e.target.value)} /></label>
        <p className="hint">{t('The mandate lasts three years from the election.')}</p>
        <ErrorText error={error} />
      </Form>
    </Modal>
  )
}

function EndModal({ delegate, onClose, onDone }: { delegate: Delegate; onClose: () => void; onDone: () => void }) {
  const [endedOn, setEndedOn] = useState(today())
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string | null>(null)
  const submit = async () => {
    setError(null)
    try { await api(`/api/representation/delegates/${delegate.id}/end`, { method: 'POST', body: { endedOn, reason } }); onDone() } catch (e) { setError(errorText(e)) }
  }
  return (
    <Modal title={t('End the mandate of {a}', { a: delegate.name })} onClose={onClose}
      footer={<><Button className="secondary" type="button" onClick={onClose}>{t('Cancel')}</Button><Button type="submit" form="end-mandate">{t('End the mandate')}</Button></>}>
      <Form id="end-mandate" onSubmit={submit}>
        <label>{t('Ended on')}<DateInput required min={delegate.electedOn} value={endedOn} onChange={(e) => setEndedOn(e.target.value)} /></label>
        <label>{t('Why')}<input required maxLength={300} value={reason} onChange={(e) => setReason(e.target.value)} placeholder={t('For example: left the company, resigned the mandate, replaced by the alternate')} /></label>
        <ErrorText error={error} />
      </Form>
    </Modal>
  )
}

function FileField({ onFile }: { onFile: (f: File | null) => void }) {
  return <label>{t('File (PDF, JPEG or PNG, optional)')}<input type="file" accept="application/pdf,image/jpeg,image/png" onChange={(e) => onFile(e.target.files?.[0] ?? null)} /></label>
}

function EventModal({ onClose, onDone }: { onClose: () => void; onDone: (w: string[]) => void }) {
  const [kind, setKind] = useState<EventKind>('MEETING')
  const [heldOn, setHeldOn] = useState(today())
  const [announcedOn, setAnnouncedOn] = useState('')
  const [title, setTitle] = useState('')
  const [note, setNote] = useState('')
  const [copy, setCopy] = useState('')
  const [file, setFile] = useState<File | null>(null)
  const [error, setError] = useState<string | null>(null)
  const submit = async () => {
    setError(null)
    try {
      const content = file ? await fileToBase64(file) : null
      const r = await api<Saved<RepEvent>>('/api/representation/events', { method: 'POST', body: {
        kind, heldOn, announcedOn: kind === 'ELECTION' && announcedOn ? announcedOn : null, title, note,
        inspectorCopyOn: copy || null, fileName: file?.name ?? null, content,
      } })
      onDone(r.warnings)
    } catch (e) { setError(errorText(e)) }
  }
  return (
    <Modal title={t('Add a record')} onClose={onClose}
      footer={<><Button className="secondary" type="button" onClick={onClose}>{t('Cancel')}</Button><Button type="submit" form="rep-event">{t('Save')}</Button></>}>
      <Form id="rep-event" onSubmit={submit}>
        <div className="grid2">
          <label>{t('What')}
            <select value={kind} onChange={(e) => setKind(e.target.value as EventKind)}>
              {(Object.keys(KINDS) as EventKind[]).map((k) => <option key={k} value={k}>{KINDS[k]}</option>)}
            </select>
          </label>
          <label>{kind === 'ELECTION' ? t('Day of the vote') : kind === 'MEETING' ? t('Day of the meeting') : t('Day sent')}
            <DateInput required value={heldOn} onChange={(e) => setHeldOn(e.target.value)} /></label>
        </div>
        {kind === 'ELECTION' && <label>{t('Announced on')}<DateInput value={announcedOn} max={heldOn} onChange={(e) => setAnnouncedOn(e.target.value)} />
          <span className="hint">{t('At least 3 weeks before the vote (art. 6).')}</span></label>}
        <label>{t('Title (optional)')}<input maxLength={200} value={title} onChange={(e) => setTitle(e.target.value)} /></label>
        <label>{t('Notes (optional)')}<textarea rows={3} value={note} onChange={(e) => setNote(e.target.value)} /></label>
        {kind !== 'ELECTION' && <label>{t('Sent to the labour inspector on (optional)')}<DateInput value={copy} min={heldOn} onChange={(e) => setCopy(e.target.value)} /></label>}
        <FileField onFile={setFile} />
        <ErrorText error={error} />
      </Form>
    </Modal>
  )
}

function AuthorisationModal({ delegates, onClose, onDone }: { delegates: Delegate[]; onClose: () => void; onDone: () => void }) {
  // Each delegate once, even with several terms.
  const choices = useMemo(() => [...new Map(delegates.map((d) => [d.userId, d])).values()].sort((a, b) => a.name.localeCompare(b.name)), [delegates])
  const [userId, setUserId] = useState('')
  const [action, setAction] = useState<Action>('DISMISSAL')
  const [decidedOn, setDecidedOn] = useState(today())
  const [reference, setReference] = useState('')
  const [granted, setGranted] = useState(true)
  const [note, setNote] = useState('')
  const [file, setFile] = useState<File | null>(null)
  const [error, setError] = useState<string | null>(null)
  const submit = async () => {
    setError(null)
    try {
      const content = file ? await fileToBase64(file) : null
      await api('/api/representation/authorisations', { method: 'POST', body: {
        userId: Number(userId), action, decidedOn, reference, granted, note, fileName: file?.name ?? null, content,
      } })
      onDone()
    } catch (e) { setError(errorText(e)) }
  }
  return (
    <Modal title={t('Record the inspector\'s decision')} onClose={onClose}
      footer={<><Button className="secondary" type="button" onClick={onClose}>{t('Cancel')}</Button><Button type="submit" form="authorisation">{t('Save')}</Button></>}>
      <Form id="authorisation" onSubmit={submit}>
        <label>{t('Delegate')}{choices.length ? <ListSelect people={choices} value={userId} onChange={setUserId} /> : <PersonSelect required value={userId} onChange={setUserId} />}</label>
        <div className="grid2">
          <label>{t('For')}
            <select value={action} onChange={(e) => setAction(e.target.value as Action)}>
              <option value="DISMISSAL">{t('Dismissal')}</option><option value="TRANSFER">{t('Transfer')}</option>
            </select>
          </label>
          <label>{t('Decision')}
            <select value={granted ? 'yes' : 'no'} onChange={(e) => setGranted(e.target.value === 'yes')}>
              <option value="yes">{t('Authorised')}</option><option value="no">{t('Refused')}</option>
            </select>
          </label>
        </div>
        <div className="grid2">
          <label>{t('Decided on')}<DateInput required max={today()} value={decidedOn} onChange={(e) => setDecidedOn(e.target.value)} /></label>
          <label>{t('Reference')}<input required maxLength={100} value={reference} onChange={(e) => setReference(e.target.value)} /></label>
        </div>
        <label>{t('Note (optional)')}<input maxLength={500} value={note} onChange={(e) => setNote(e.target.value)} /></label>
        <FileField onFile={setFile} />
        <p className="hint">{t('The decision follows an inquiry where both sides are heard. It can be appealed to the Minister within 10 working days (arrêté 036/95).')}</p>
        <ErrorText error={error} />
      </Form>
    </Modal>
  )
}

function HoursModal({ delegates, onClose, onDone }: { delegates: Delegate[]; onClose: () => void; onDone: (w: string[]) => void }) {
  const [userId, setUserId] = useState(delegates.length === 1 ? String(delegates[0].userId) : '')
  const [onDay, setOnDay] = useState(today())
  const [hours, setHours] = useState('2')
  const [note, setNote] = useState('')
  const [error, setError] = useState<string | null>(null)
  const submit = async () => {
    setError(null)
    try {
      const r = await api<Saved<HoursEntry>>('/api/representation/hours', { method: 'POST', body: { userId: Number(userId), onDay, hours: Number(hours), note } })
      onDone(r.warnings)
    } catch (e) { setError(errorText(e)) }
  }
  return (
    <Modal title={t('Add hours of the mandate')} onClose={onClose}
      footer={<><Button className="secondary" type="button" onClick={onClose}>{t('Cancel')}</Button><Button type="submit" form="hours">{t('Add')}</Button></>}>
      <Form id="hours" onSubmit={submit}>
        <label>{t('Delegate')}<ListSelect people={delegates} value={userId} onChange={setUserId} /></label>
        <div className="grid2">
          <label>{t('Day')}<DateInput required max={today()} value={onDay} onChange={(e) => setOnDay(e.target.value)} /></label>
          <label>{t('Hours')}<input required type="number" min="0.25" max="12" step="0.25" value={hours} onChange={(e) => setHours(e.target.value)} /></label>
        </div>
        <label>{t('Note (optional)')}<input maxLength={300} value={note} onChange={(e) => setNote(e.target.value)} placeholder={t('For example: meeting with the inspector, union training')} /></label>
        <ErrorText error={error} />
      </Form>
    </Modal>
  )
}
