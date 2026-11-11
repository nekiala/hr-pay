import { useEffect, useState, type FormEvent, type KeyboardEvent } from 'react'
import {
  ApiError, api, personName, type Mission, type MissionExpense, type MissionPayment, type Page, type TravelMission,
  type TravelMissionDetail, type User,
  day,
} from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'
import { useUnitFilter } from '../components/UnitFilter'
import { Button, Form } from '../components/Button'
import { Modal } from '../components/Modal'
import { CountrySelect } from '../components/ReferencePickers'
import { PageTitle, SettingsLink } from '../components/SettingsLink'
import { t } from '../i18n'
import { DateInput, TimeInput } from '../components/DateFields'

const SHORT_STATUS: Record<Mission['status'], string> = {
  PENDING: t('Waiting for approval'), APPROVED: t('Approved'), REJECTED: t('Rejected'), CANCELLED: t('Cancelled'), CLOSED: t('Closed'),
}

export const TRANSPORT: Record<TravelMission['transport'], string> = {
  PLANE: t('Plane'), ROAD: t('Road'), BOAT: t('Boat'), TRAIN: t('Train'), COMPANY_CAR: t('Company vehicle'), OTHER: t('Other'),
}

const COSTS_BY: Record<TravelMission['costsBy'], string> = {
  COMPANY: t('The company'), HOST: t('The host organisation'), SPONSOR: t('A sponsor'), SHARED: t('Shared with the host'),
}

const EXPENSE: Record<MissionExpense['category'], string> = {
  TRANSPORT: t('Transport'), LODGING: t('Hotel'), MEALS: t('Meals'), VISA: t('Visa and fees'), OTHER: t('Other'),
}

const PAYMENT: Record<MissionPayment['kind'], string> = {
  ADVANCE: t('Advance'), BALANCE: t('Balance paid'), REFUND: t('Given back by the employee'),
}

const PAYMENT_STATUS: Record<MissionPayment['status'], string> = {
  APPROVED: t('To pay'), PAID: t('Paid'), RECEIVED: t('Received'), CANCELLED: t('Cancelled'),
}

function travelStatus(m: TravelMission) {
  if (m.status === 'PENDING') return m.step === 'HR' ? t('Waiting for HR') : t('Waiting for the unit head')
  if (m.status === 'APPROVED') return m.reportOverdue ? t('Approved · report late') : t('Approved')
  return SHORT_STATUS[m.status]
}

const today = () => new Date().toISOString().slice(0, 10)
const hm = (tItem: string) => tItem.slice(0, 5)
const money = (n: number | null | undefined, cur: string) => (n == null ? '—' : `${Number(n).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })} ${cur}`)
const errorText = (e: unknown) => (e instanceof ApiError ? [e.message, ...Object.values(e.fieldErrors)].join(' ') : t('Something went wrong.'))

const EMPTY_TRAVEL = {
  countryCode: 'CD' as string | null, city: '', startDate: today(), endDate: today(), purpose: '', transport: 'PLANE' as TravelMission['transport'],
  costsBy: 'COMPANY' as TravelMission['costsBy'], costsNote: '', lodgingByCompany: true, perDiemRate: '',
}

export default function MissionsPage() {
  const { can } = useAuth()
  const canRequest = can('mission.request')
  const manage = can('mission.manage')
  const canDecide = can('mission.approve.all') || can('mission.approve.team')
  const seesOthers = manage || canDecide
  const [tab, setTab] = useState<'mine' | 'all'>('mine')
  const [mine, setMine] = useState<TravelMission[]>([])
  const [all, setAll] = useState<TravelMission[]>([])
  const [toDecide, setToDecide] = useState<TravelMission[]>([])
  const [period, setPeriod] = useState({ from: `${new Date().getFullYear()}-01-01`, to: `${new Date().getFullYear()}-12-31` })
  const [shortMine, setShortMine] = useState<Mission[]>([])
  const [shortPending, setShortPending] = useState<Mission[]>([])
  const [open, setOpen] = useState<number | null>(null)
  const [creating, setCreating] = useState(false)
  const [requestingShort, setRequestingShort] = useState(false)
  const units = useUnitFilter()
  const [shortForm, setShortForm] = useState({ workDate: today(), startTime: '14:00', endTime: '17:00', location: '', purpose: '' })
  const [notes, setNotes] = useState<Record<string, string>>({})
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)

  const load = () => {
    if (canRequest) {
      api<TravelMission[]>('/api/travel-missions/mine').then(setMine).catch((e: Error) => setError(e.message))
      api<Mission[]>('/api/missions/mine').then(setShortMine).catch((e: Error) => setError(e.message))
    }
    if (canDecide) {
      api<TravelMission[]>('/api/travel-missions/to-decide').then(setToDecide).catch((e: Error) => setError(e.message))
      api<Mission[]>('/api/missions/to-decide').then(setShortPending).catch((e: Error) => setError(e.message))
    }
    if (seesOthers) {
      api<TravelMission[]>(`/api/travel-missions?from=${period.from}&to=${period.to}`).then(setAll).catch((e: Error) => setError(e.message))
    }
  }
  useEffect(load, [period]) // eslint-disable-line react-hooks/exhaustive-deps

  const run = async (action: () => Promise<unknown>, done: string) => {
    setError(null)
    setMessage(null)
    try {
      await action()
      setMessage(done)
      load()
      return true
    } catch (e) {
      setError(errorText(e))
      return false
    }
  }

  const submitShort = (e: FormEvent) => {
    e.preventDefault()
    return run(async () => {
      await api('/api/missions', { method: 'POST', body: shortForm })
      setShortForm({ ...shortForm, location: '', purpose: '' })
    }, t('Mission requested. Your manager will be asked to approve it.'))
  }

  const pending = toDecide.filter((m) => units.keep(m.userId))
  const shortWaiting = shortPending.filter((m) => units.keep(m.userId))
  const list = tab === 'all' && seesOthers ? all.filter((m) => units.keep(m.userId)) : mine

  return (
    <section>
      <PageTitle title={t('Missions')}>
        {(canRequest || manage) && <Button onClick={() => setCreating(true)}>{manage ? t('New mission') : t('Request a mission')}</Button>}
        {canRequest && <Button className="secondary" onClick={() => setRequestingShort(true)}>{t('A few hours away')}</Button>}
        {manage && <SettingsLink to="/missions/settings" />}
      </PageTitle>
      <p className="muted">{t('A mission of whole days, in the country or abroad, comes with an ordre de mission. Its working days count as worked and paid, without checking in.')}</p>
      <ErrorText error={error} />
      {message && <p className="success">{message}</p>}

      {canDecide && (pending.length > 0 || shortWaiting.length > 0) && (
        <div className="card">
          <div className="row space"><h3>{t('To approve')}</h3>{units.select}</div>
          {pending.map((m) => (
            <div key={`t${m.id}`} className="mission">
              <p><strong>{m.employeeName}</strong> <span className="muted">{m.registrationNo} · {units.unitName(m.userId)}</span>
                {' '}<span className="badge">{m.scope === 'INTERNATIONAL' ? t('International') : t('In the country')}</span></p>
              <p>{m.city}, {m.countryName} · {day(m.startDate)} → {day(m.endDate)} ({t('{a} days', { a: m.days })})</p>
              <p className="muted">{m.purpose}{m.firstApprovedBy ? ` · ${t('Approved by the unit head: {a}', { a: m.firstApprovedBy })}` : ''}</p>
              <div className="row">
                <input placeholder={t('Note (required to reject)')} value={notes[`t${m.id}`] ?? ''}
                  onChange={(e) => setNotes({ ...notes, [`t${m.id}`]: e.target.value })} />
                <Button onClick={() => run(() => api(`/api/travel-missions/${m.id}/decision`, { method: 'POST', body: { approve: true, note: notes[`t${m.id}`] } }), t('Mission approved.'))}>{t('Approve')}</Button>
                <Button className="secondary" onClick={() => run(() => api(`/api/travel-missions/${m.id}/decision`, { method: 'POST', body: { approve: false, note: notes[`t${m.id}`] } }), t('Mission rejected.'))}>{t('Reject')}</Button>
                <Button className="link" onClick={() => setOpen(m.id)}>{t('Details')}</Button>
              </div>
            </div>
          ))}
          {shortWaiting.map((m) => (
            <div key={`s${m.id}`} className="mission">
              <p><strong>{m.employeeName}</strong> <span className="muted">{m.registrationNo} · {units.unitName(m.userId)}</span></p>
              <p>{day(m.workDate)}, {hm(m.startTime)}–{hm(m.endTime)} · {m.location}</p>
              <p className="muted">{m.purpose}</p>
              <div className="row">
                <input placeholder={t('Note (required to reject)')} value={notes[`s${m.id}`] ?? ''}
                  onChange={(e) => setNotes({ ...notes, [`s${m.id}`]: e.target.value })} />
                <Button onClick={() => run(() => api(`/api/missions/${m.id}/decision`, { method: 'POST', body: { approve: true, note: notes[`s${m.id}`] } }), t('Mission approved.'))}>{t('Approve')}</Button>
                <Button className="secondary" onClick={() => run(() => api(`/api/missions/${m.id}/decision`, { method: 'POST', body: { approve: false, note: notes[`s${m.id}`] } }), t('Mission rejected.'))}>{t('Reject')}</Button>
              </div>
            </div>
          ))}
        </div>
      )}

      {seesOthers && (
        <div className="row space">
          <div className="segmented" role="tablist">
            <button type="button" role="tab" aria-selected={tab === 'mine'} className={tab === 'mine' ? 'on' : ''} onClick={() => setTab('mine')}>{t('My missions')}</button>
            <button type="button" role="tab" aria-selected={tab === 'all'} className={tab === 'all' ? 'on' : ''} onClick={() => setTab('all')}>{manage ? t('All missions') : t('My team')}</button>
          </div>
          {tab === 'all' && (
            <div className="row">
              <label>{t('From')}{' '}<DateInput value={period.from} onChange={(e) => e.target.value && setPeriod({ ...period, from: e.target.value })} /></label>
              <label>{t('To')}{' '}<DateInput value={period.to} onChange={(e) => e.target.value && setPeriod({ ...period, to: e.target.value })} /></label>
              {units.select}
            </div>
          )}
        </div>
      )}

      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>{t('Ordre de mission')}</th>
              {tab === 'all' && <th>{t('Employee')}</th>}
              <th>{t('Destination')}</th><th>{t('Dates')}</th><th>{t('Status')}</th><th></th>
            </tr>
          </thead>
          <tbody>
            {list.length === 0 && <tr><td colSpan={6} className="muted">{t('No mission.')}</td></tr>}
            {list.map((m) => (
              <tr key={m.id}>
                <td>{m.number ?? <span className="muted">{t('Not yet numbered')}</span>}{m.amendment > 0 && <span className="muted"> · {t('amendment {a}', { a: m.amendment })}</span>}</td>
                {tab === 'all' && <td>{m.employeeName}<br /><span className="muted small">{m.registrationNo}</span></td>}
                <td>{m.city}, {m.countryName}<br /><span className="muted small">{m.purpose}</span></td>
                <td>{day(m.startDate)} → {day(m.endDate)}<br /><span className="muted small">{t('{a} days', { a: m.days })}</span></td>
                <td>{travelStatus(m)}</td>
                <td className="row">
                  <Button className="secondary" onClick={() => setOpen(m.id)}>{t('Open')}</Button>
                  {m.number && (m.status === 'APPROVED' || m.status === 'CLOSED') &&
                    <a className="button secondary" href={`/api/travel-missions/${m.id}/order.pdf`} target="_blank" rel="noreferrer">{t('PDF')}</a>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {canRequest && shortMine.length > 0 && (
        <>
          <h3>{t('A few hours away')}</h3>
          <div className="table-wrap">
            <table>
              <thead><tr><th>{t('Date')}</th><th>{t('Time')}</th><th>{t('Where')}</th><th>{t('Status')}</th><th></th></tr></thead>
              <tbody>
                {shortMine.map((m) => (
                  <tr key={m.id}>
                    <td>{day(m.workDate)}</td>
                    <td>{hm(m.startTime)}–{hm(m.endTime)}</td>
                    <td>{m.location}<br /><span className="muted">{m.purpose}</span></td>
                    <td>{SHORT_STATUS[m.status]}{m.decidedBy ? <><br /><span className="muted">{t('by')}{' '}{m.decidedBy}{m.decisionNote ? `: ${m.decisionNote}` : ''}</span></> : null}</td>
                    <td>{m.status === 'PENDING' && <Button className="secondary" onClick={() => run(() => api(`/api/missions/${m.id}/cancel`, { method: 'POST' }), t('Mission cancelled.'))}>{t('Cancel')}</Button>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}

      {creating && <NewMission manage={manage} canRequest={canRequest} onClose={() => setCreating(false)} onDone={(text) => { setCreating(false); setMessage(text); load() }} />}

      {canRequest && requestingShort && (
        <Modal title={t('A few hours away')} size="md" onClose={() => setRequestingShort(false)}>
          <p className="muted">{t('Work away from the office for part of a day. You check in as usual; once approved, you can end your day where the mission is.')}</p>
          <Form onSubmit={async (e) => { if (await submitShort(e)) setRequestingShort(false) }}>
            <div className="grid3">
              <label>{t('Date')}<DateInput value={shortForm.workDate} onChange={(e) => setShortForm({ ...shortForm, workDate: e.target.value })} required /></label>
              <label>{t('From')}<TimeInput value={shortForm.startTime} onChange={(e) => setShortForm({ ...shortForm, startTime: e.target.value })} required /></label>
              <label>{t('To')}<TimeInput value={shortForm.endTime} onChange={(e) => setShortForm({ ...shortForm, endTime: e.target.value })} required /></label>
            </div>
            <label>{t('Where')}<input value={shortForm.location} maxLength={200} onChange={(e) => setShortForm({ ...shortForm, location: e.target.value })} placeholder={t('For example Ministry of Labour, Gombe')} required /></label>
            <label>{t('Why')}<input value={shortForm.purpose} maxLength={500} onChange={(e) => setShortForm({ ...shortForm, purpose: e.target.value })} placeholder={t('For example submit the CNSS declaration')} required /></label>
            <ErrorText error={error} />
            <div className="modal-foot"><Button className="secondary" onClick={() => setRequestingShort(false)}>{t('Cancel')}</Button><Button type="submit">{t('Send request')}</Button></div>
          </Form>
        </Modal>
      )}

      {open !== null && <MissionDetail id={open} onClose={() => { setOpen(null); load() }} />}
    </section>
  )
}

/** HR picks one or more employees; an employee requests for themselves. */
function NewMission({ manage, canRequest, onClose, onDone }: { manage: boolean; canRequest: boolean; onClose: () => void; onDone: (text: string) => void }) {
  const [form, setForm] = useState(EMPTY_TRAVEL)
  const [people, setPeople] = useState<User[]>([])
  const [forMe, setForMe] = useState(!manage)
  const [error, setError] = useState<string | null>(null)

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setError(null)
    if (!forMe && people.length === 0) { setError(t('Choose at least one employee.')); return }
    try {
      const made = await api<TravelMission[]>('/api/travel-missions', {
        method: 'POST',
        body: {
          ...form, userIds: forMe ? [] : people.map((p) => p.id), costsNote: form.costsNote || null,
          perDiemRate: manage && form.perDiemRate !== '' ? Number(form.perDiemRate) : null,
        },
      })
      onDone(made.length > 1 ? t('{a} missions created. The unit heads will be asked to approve them.', { a: made.length })
        : t('Mission requested. It now waits for approval.'))
    } catch (err) {
      setError(errorText(err))
    }
  }

  return (
    <Modal title={manage ? t('New mission') : t('Request a mission')} size="lg" onClose={onClose}>
      <Form onSubmit={submit}>
        {manage && (
          <>
            {canRequest && <label className="check"><input type="checkbox" checked={forMe} onChange={(e) => setForMe(e.target.checked)} />{t('For me')}</label>}
            {!forMe && <PeoplePicker people={people} onChange={setPeople} />}
          </>
        )}
        <div className="grid2">
          <label>{t('Country')}<CountrySelect value={form.countryCode} onChange={(c) => setForm({ ...form, countryCode: c })} required /></label>
          <label>{t('City')}<input value={form.city} maxLength={120} onChange={(e) => setForm({ ...form, city: e.target.value })} placeholder={t('For example Lubumbashi or Tokyo')} required /></label>
        </div>
        <div className="grid2">
          <label>{t('Departure')}<DateInput value={form.startDate} onChange={(e) => setForm({ ...form, startDate: e.target.value, endDate: form.endDate < e.target.value ? e.target.value : form.endDate })} required /></label>
          <label>{t('Return')}<DateInput value={form.endDate} min={form.startDate} onChange={(e) => setForm({ ...form, endDate: e.target.value })} required /></label>
        </div>
        <label>{t('Purpose of the mission')}<input value={form.purpose} maxLength={500} onChange={(e) => setForm({ ...form, purpose: e.target.value })} placeholder={t('For example training session, deployment at the client')} required /></label>
        <div className="grid2">
          <label>{t('Transport')}<select value={form.transport} onChange={(e) => setForm({ ...form, transport: e.target.value as TravelMission['transport'] })}>
            {Object.entries(TRANSPORT).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
          </select></label>
          <label>{t('Who pays tickets and costs')}<select value={form.costsBy} onChange={(e) => setForm({ ...form, costsBy: e.target.value as TravelMission['costsBy'] })}>
            {Object.entries(COSTS_BY).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
          </select></label>
        </div>
        <label className="check"><input type="checkbox" checked={form.lodgingByCompany} onChange={(e) => setForm({ ...form, lodgingByCompany: e.target.checked })} />{t('The company books the hotel')}</label>
        <label>{t('Note on costs (optional)')}<input value={form.costsNote} maxLength={300} onChange={(e) => setForm({ ...form, costsNote: e.target.value })} placeholder={t('For example hotel paid by the host')} /></label>
        {manage && (
          <label>{t('Daily rate (leave empty for the company scale)')}<input type="number" min={0} step="0.01" value={form.perDiemRate} onChange={(e) => setForm({ ...form, perDiemRate: e.target.value })} />
            <span className="hint">{t('Above the scale, the difference goes on the payslip as taxable pay.')}</span></label>
        )}
        <ErrorText error={error} />
        <div className="modal-foot"><Button className="secondary" onClick={onClose}>{t('Cancel')}</Button><Button type="submit">{manage && !forMe ? t('Create') : t('Send request')}</Button></div>
      </Form>
    </Modal>
  )
}

function PeoplePicker({ people, onChange }: { people: User[]; onChange: (p: User[]) => void }) {
  const [query, setQuery] = useState('')
  const [found, setFound] = useState<User[] | null>(null)
  const [active, setActive] = useState(0)
  const q = query.trim()
  useEffect(() => {
    if (q.length < 2) { setFound(null); return }
    const handle = setTimeout(() => {
      api<Page<User>>(`/api/users?${new URLSearchParams({ page: '0', size: '8', q })}`)
        .then((d) => { setFound(d.items.filter((u) => !people.some((p) => p.id === u.id))); setActive(0) }).catch(() => setFound([]))
    }, 250)
    return () => clearTimeout(handle)
  }, [q, people])
  const add = (u: User) => { onChange([...people, u]); setQuery(''); setFound(null) }
  const keys = (e: KeyboardEvent<HTMLInputElement>) => {
    if (!found?.length) return
    if (e.key === 'ArrowDown') { e.preventDefault(); setActive((i) => Math.min(i + 1, found.length - 1)) }
    if (e.key === 'ArrowUp') { e.preventDefault(); setActive((i) => Math.max(i - 1, 0)) }
    if (e.key === 'Enter') { e.preventDefault(); add(found[active]) }
  }
  return (
    <div>
      <label>{t('Employees')}
        <input type="search" placeholder={t('Search by name, employee ID or email')} value={query} onChange={(e) => setQuery(e.target.value)} onKeyDown={keys} />
      </label>
      {found && found.length > 0 && (
        <ul className="person-results" role="listbox">
          {found.map((u, i) => (
            <li key={u.id} role="option" aria-selected={i === active}>
              <button type="button" className={i === active ? 'on' : undefined} onMouseEnter={() => setActive(i)} onClick={() => add(u)}>
                <span className="who"><strong>{personName(u)}</strong><span className="muted small">{[u.registrationNo, u.unitName].filter(Boolean).join(' · ')}</span></span>
              </button>
            </li>
          ))}
        </ul>
      )}
      {people.length > 0 && (
        <p className="row">
          {people.map((p) => (
            <span key={p.id} className="badge">{personName(p)}{' '}
              <button type="button" className="link" aria-label={t('Remove')} onClick={() => onChange(people.filter((x) => x.id !== p.id))}>×</button>
            </span>
          ))}
        </p>
      )}
    </div>
  )
}

function MissionDetail({ id, onClose }: { id: number; onClose: () => void }) {
  const [d, setD] = useState<TravelMissionDetail | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const [note, setNote] = useState('')
  const [report, setReport] = useState('')
  const [amount, setAmount] = useState('')
  const [refund, setRefund] = useState('')
  const [expense, setExpense] = useState({ spentOn: today(), category: 'TRANSPORT' as MissionExpense['category'], description: '', amount: '' })
  const [file, setFile] = useState<File | null>(null)
  const [amending, setAmending] = useState(false)

  const load = () => api<TravelMissionDetail>(`/api/travel-missions/${id}`).then((x) => { setD(x); setReport(x.report ?? '') }).catch((e: Error) => setError(e.message))
  useEffect(() => { void load() }, [id]) // eslint-disable-line react-hooks/exhaustive-deps

  const run = async (action: () => Promise<unknown>, done: string) => {
    setError(null)
    setMessage(null)
    try {
      const r = await action()
      if (r && typeof r === 'object' && 'mission' in (r as object)) { setD(r as TravelMissionDetail) } else { await load() }
      setMessage(done)
      return true
    } catch (e) {
      setError(errorText(e))
      return false
    }
  }

  if (!d) return <Modal title={t('Mission')} size="lg" onClose={onClose}><ErrorText error={error} /><p className="muted">{t('Loading…')}</p></Modal>
  const m = d.mission
  const cur = m.currency
  const open = m.status === 'APPROVED'
  const canWork = open && (m.mine || m.canManage)

  const addExpense = async (e: FormEvent) => {
    e.preventDefault()
    const base64 = file ? await new Promise<string>((resolve, reject) => {
      const r = new FileReader()
      r.onload = () => resolve(String(r.result))
      r.onerror = () => reject(new Error(t('The file could not be read.')))
      r.readAsDataURL(file)
    }) : null
    const ok = await run(() => api(`/api/travel-missions/${id}/expenses`, {
      method: 'POST', body: { ...expense, amount: Number(expense.amount), fileName: file?.name ?? null, contentType: file?.type ?? null, base64 },
    }), t('Cost added.'))
    if (ok) { setExpense({ ...expense, description: '', amount: '' }); setFile(null) }
  }

  return (
    <Modal title={m.number ? t('Mission {a}', { a: m.number }) : t('Mission')} size="lg" onClose={onClose}>
      <ErrorText error={error} />
      {message && <p className="success">{message}</p>}
      <div className="row space">
        <p><strong>{m.employeeName}</strong> <span className="muted">{m.registrationNo}</span><br />
          {m.city}, {m.countryName} · {day(m.startDate)} → {day(m.endDate)} ({t('{a} days', { a: m.days })})<br />
          <span className="muted">{m.purpose}</span></p>
        <p className="right">
          <span className="badge">{travelStatus(m)}</span><br />
          {m.number && (m.status === 'APPROVED' || m.status === 'CLOSED') &&
            <a className="button" href={`/api/travel-missions/${id}/order.pdf`} target="_blank" rel="noreferrer">{t('Ordre de mission (PDF)')}</a>}
        </p>
      </div>
      <dl className="facts">
        <dt>{t('Transport')}</dt><dd>{TRANSPORT[m.transport]}</dd>
        <dt>{t('Costs')}</dt><dd>{COSTS_BY[m.costsBy]}{m.costsNote ? ` · ${m.costsNote}` : ''} · {m.lodgingByCompany ? t('hotel booked by the company') : t('hotel paid per night')}</dd>
        <dt>{t('Requested by')}</dt><dd>{m.requestedBy}</dd>
        {m.firstApprovedBy && <><dt>{t('Unit head')}</dt><dd>{m.firstApprovedBy}</dd></>}
        {m.decidedBy && <><dt>{m.status === 'REJECTED' || m.status === 'CANCELLED' ? t('Decided by') : t('Approved by')}</dt><dd>{m.decidedBy}{m.decisionNote ? `: ${m.decisionNote}` : ''}</dd></>}
        {m.amendment > 0 && <><dt>{t('Amendment')}</dt><dd>{m.amendment}: {m.amendmentReason}</dd></>}
      </dl>

      {m.canDecide && (
        <div className="row">
          <input placeholder={t('Note (required to reject)')} value={note} onChange={(e) => setNote(e.target.value)} />
          <Button onClick={() => run(() => api(`/api/travel-missions/${id}/decision`, { method: 'POST', body: { approve: true, note } }), t('Mission approved.'))}>{t('Approve')}</Button>
          <Button className="secondary" onClick={() => run(() => api(`/api/travel-missions/${id}/decision`, { method: 'POST', body: { approve: false, note } }), t('Mission rejected.'))}>{t('Reject')}</Button>
        </div>
      )}

      {(m.status === 'APPROVED' || m.status === 'CLOSED' || m.canManage) && (
        <>
          <h3>{t('Money')}</h3>
          <table className="compact">
            <tbody>
              <tr><td>{t('Per diem: {a} days × {b}', { a: d.money.perDiemDays, b: money(m.perDiemRate, cur) })}{m.perDiemScale != null && m.perDiemRate != null && m.perDiemRate > m.perDiemScale ? ` (${t('scale {a}', { a: money(m.perDiemScale, cur) })})` : ''}</td><td className="num">{money(d.money.perDiem, cur)}</td></tr>
              {!m.lodgingByCompany && <tr><td>{t('Hotel: {a} nights × {b}', { a: d.money.nights, b: money(m.lodgingRate, cur) })}</td><td className="num">{money(d.money.lodging, cur)}</td></tr>}
              <tr><td>{t('Costs accepted')}{d.money.expensesWaiting > 0 ? ` (${t('{a} waiting', { a: money(d.money.expensesWaiting, cur) })})` : ''}</td><td className="num">{money(d.money.expensesAccepted, cur)}</td></tr>
              <tr><th>{t('Due to the employee')}</th><th className="num">{money(d.money.due, cur)}</th></tr>
              <tr><td>{t('Paid (advance and balance)')}</td><td className="num">{money(d.money.paid, cur)}</td></tr>
              {d.money.returned > 0 && <tr><td>{t('Given back by the employee')}</td><td className="num">{money(d.money.returned, cur)}</td></tr>}
              <tr><th>{d.money.balance >= 0 ? t('Still to pay') : t('The employee owes')}</th><th className="num">{money(Math.abs(d.money.balance), cur)}</th></tr>
              {d.money.excess > 0 && <tr><td colSpan={2} className="muted small">{t('{a} above the scale goes on the payslip as taxable pay when the mission is closed.', { a: money(d.money.excess, cur) })}</td></tr>}
            </tbody>
          </table>
          {d.payments.length > 0 && (
            <table className="compact">
              <tbody>
                {d.payments.map((p) => (
                  <tr key={p.id}>
                    <td>{p.number}</td><td>{PAYMENT[p.kind]}</td><td className="num">{money(p.amount, p.currency)}</td><td>{PAYMENT_STATUS[p.status]}</td>
                    <td>{m.canManage && p.status === 'APPROVED' && open && <Button className="link" onClick={() => run(() => api(`/api/travel-missions/${id}/payments/${p.id}/cancel`, { method: 'POST' }), t('Payment withdrawn.'))}>{t('Withdraw')}</Button>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          {m.canManage && open && (
            <div className="row">
              <input type="number" min={0} step="0.01" placeholder={t('Advance in {a}', { a: cur })} value={amount} onChange={(e) => setAmount(e.target.value)} />
              <Button className="secondary" onClick={async () => { if (await run(() => api(`/api/travel-missions/${id}/advance`, { method: 'POST', body: { amount: Number(amount) } }), t('Advance put to pay in Payments.'))) setAmount('') }}>{t('Pay an advance')}</Button>
              {d.money.balance < 0 && (
                <>
                  <input type="number" min={0} step="0.01" placeholder={t('Given back, in {a}', { a: cur })} value={refund} onChange={(e) => setRefund(e.target.value)} />
                  <Button className="secondary" onClick={async () => { if (await run(() => api(`/api/travel-missions/${id}/refund`, { method: 'POST', body: { amount: Number(refund) } }), t('Recorded.'))) setRefund('') }}>{t('Record money given back')}</Button>
                </>
              )}
            </div>
          )}
        </>
      )}

      {(m.status === 'APPROVED' || m.status === 'CLOSED') && (
        <>
          <h3>{t('Costs paid by the employee')}</h3>
          {d.expenses.length === 0 && <p className="muted">{t('No cost yet. Add each cost with a photo of its receipt.')}</p>}
          {d.expenses.length > 0 && (
            <table className="compact">
              <tbody>
                {d.expenses.map((x) => (
                  <tr key={x.id}>
                    <td>{day(x.spentOn)}</td><td>{EXPENSE[x.category]}</td><td>{x.description}</td><td className="num">{money(x.amount, x.currency)}</td>
                    <td>{x.hasReceipt ? <a href={`/api/travel-missions/${id}/expenses/${x.id}/receipt`} target="_blank" rel="noreferrer">{t('Receipt')}</a> : <span className="muted">{t('No receipt')}</span>}</td>
                    <td>{x.status === 'SUBMITTED' ? t('To check') : x.status === 'ACCEPTED' ? t('Accepted') : `${t('Refused')}${x.decisionNote ? `: ${x.decisionNote}` : ''}`}</td>
                    <td className="row">
                      {m.canManage && open && x.status === 'SUBMITTED' && <>
                        <Button className="link" onClick={() => run(() => api(`/api/travel-missions/${id}/expenses/${x.id}/decision`, { method: 'POST', body: { approve: true } }), t('Cost accepted.'))}>{t('Accept')}</Button>
                        <Button className="link" onClick={() => { const why = window.prompt(t('Why is this cost refused?')); if (why) void run(() => api(`/api/travel-missions/${id}/expenses/${x.id}/decision`, { method: 'POST', body: { approve: false, note: why } }), t('Cost refused.')) }}>{t('Refuse')}</Button>
                      </>}
                      {canWork && x.status === 'SUBMITTED' && <Button className="link" onClick={() => run(() => api(`/api/travel-missions/${id}/expenses/${x.id}`, { method: 'DELETE' }), t('Cost removed.'))}>{t('Remove')}</Button>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          {canWork && (
            <Form onSubmit={addExpense}>
              <div className="grid4">
                <label>{t('Date')}<DateInput value={expense.spentOn} onChange={(e) => setExpense({ ...expense, spentOn: e.target.value })} required /></label>
                <label>{t('For')}<select value={expense.category} onChange={(e) => setExpense({ ...expense, category: e.target.value as MissionExpense['category'] })}>
                  {Object.entries(EXPENSE).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
                </select></label>
                <label>{t('Amount in {a}', { a: cur })}<input type="number" min={0} step="0.01" value={expense.amount} onChange={(e) => setExpense({ ...expense, amount: e.target.value })} required /></label>
                <label>{t('Receipt (photo or PDF)')}<input type="file" accept="image/*,application/pdf" onChange={(e) => setFile(e.target.files?.[0] ?? null)} /></label>
              </div>
              <label>{t('Description')}<input value={expense.description} maxLength={200} onChange={(e) => setExpense({ ...expense, description: e.target.value })} placeholder={t('For example taxi from the airport')} required /></label>
              <Button type="submit" className="secondary">{t('Add the cost')}</Button>
            </Form>
          )}

          <h3>{t('Mission report')}</h3>
          {open && !m.reportFiled && <p className={m.reportOverdue ? 'error' : 'muted'}>{t('Due by {a}.', { a: m.reportDue })}</p>}
          {canWork ? (
            <>
              <textarea rows={5} value={report} maxLength={10000} onChange={(e) => setReport(e.target.value)} placeholder={t('What was done, results, follow-up.')} />
              <Button className="secondary" onClick={() => run(() => api(`/api/travel-missions/${id}/report`, { method: 'POST', body: { report } }), t('Report filed.'))}>{m.reportFiled ? t('Update the report') : t('File the report')}</Button>
            </>
          ) : <p style={{ whiteSpace: 'pre-wrap' }}>{d.report ?? <span className="muted">{t('Not filed.')}</span>}</p>}
        </>
      )}

      {m.canManage && amending && <Amend mission={m} onClose={() => setAmending(false)} onDone={(x) => { setAmending(false); setMessage(t('Mission changed.')); void load(); void x }} />}

      <div className="modal-foot">
        {m.canManage && (m.status === 'PENDING' || m.status === 'APPROVED') && <Button className="secondary" onClick={() => setAmending(true)}>{t('Change the mission')}</Button>}
        {(m.status === 'PENDING' && (m.mine || m.canManage) || m.status === 'APPROVED' && m.canManage) && (
          <Button className="secondary" onClick={() => {
            const why = m.status === 'APPROVED' ? window.prompt(t('Why is the mission cancelled?')) : ''
            if (why === null) return
            void run(() => api(`/api/travel-missions/${id}/cancel`, { method: 'POST', body: { reason: why || null } }), t('Mission cancelled.'))
          }}>{t('Cancel the mission')}</Button>
        )}
        {m.canManage && open && <Button onClick={() => run(() => api(`/api/travel-missions/${id}/close`, { method: 'POST' }), t('Mission settled and closed.'))}>{t('Settle and close')}</Button>}
        <Button className="secondary" onClick={onClose}>{t('Close')}</Button>
      </div>
    </Modal>
  )
}

function Amend({ mission, onClose, onDone }: { mission: TravelMission; onClose: () => void; onDone: (m: TravelMission) => void }) {
  const [f, setF] = useState({
    countryCode: mission.countryCode as string | null, city: mission.city, startDate: mission.startDate, endDate: mission.endDate,
    purpose: mission.purpose, transport: mission.transport, costsBy: mission.costsBy, lodgingByCompany: mission.lodgingByCompany,
    perDiemRate: mission.perDiemRate == null ? '' : String(mission.perDiemRate), reason: '',
  })
  const [error, setError] = useState<string | null>(null)
  const save = async (e: FormEvent) => {
    e.preventDefault()
    try {
      onDone(await api<TravelMission>(`/api/travel-missions/${mission.id}/amend`, {
        method: 'POST', body: { ...f, perDiemRate: f.perDiemRate === '' ? null : Number(f.perDiemRate) },
      }))
    } catch (err) {
      setError(errorText(err))
    }
  }
  return (
    <div className="card">
      <h3>{t('Change the mission')}</h3>
      {mission.status === 'APPROVED' && <p className="muted">{t('The ordre de mission gets a new amendment number; the employee is told to print it again.')}</p>}
      <Form onSubmit={save}>
        <div className="grid2">
          <label>{t('Country')}<CountrySelect value={f.countryCode} onChange={(c) => setF({ ...f, countryCode: c })} required /></label>
          <label>{t('City')}<input value={f.city} onChange={(e) => setF({ ...f, city: e.target.value })} required /></label>
          <label>{t('Departure')}<DateInput value={f.startDate} onChange={(e) => setF({ ...f, startDate: e.target.value })} required /></label>
          <label>{t('Return')}<DateInput value={f.endDate} min={f.startDate} onChange={(e) => setF({ ...f, endDate: e.target.value })} required /></label>
          <label>{t('Transport')}<select value={f.transport} onChange={(e) => setF({ ...f, transport: e.target.value as TravelMission['transport'] })}>
            {Object.entries(TRANSPORT).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select></label>
          <label>{t('Daily rate')}<input type="number" min={0} step="0.01" value={f.perDiemRate} onChange={(e) => setF({ ...f, perDiemRate: e.target.value })} /></label>
        </div>
        <label className="check"><input type="checkbox" checked={f.lodgingByCompany} onChange={(e) => setF({ ...f, lodgingByCompany: e.target.checked })} />{t('The company books the hotel')}</label>
        <label>{t('Purpose of the mission')}<input value={f.purpose} onChange={(e) => setF({ ...f, purpose: e.target.value })} required /></label>
        <label>{t('Reason for the change')}<input value={f.reason} maxLength={300} onChange={(e) => setF({ ...f, reason: e.target.value })} placeholder={t('For example mission extended by two days')} required /></label>
        <ErrorText error={error} />
        <div className="row"><Button className="secondary" onClick={onClose}>{t('Cancel')}</Button><Button type="submit">{t('Save the change')}</Button></div>
      </Form>
    </div>
  )
}
