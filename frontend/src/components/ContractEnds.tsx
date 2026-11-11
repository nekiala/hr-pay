import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { ApiError, api, CONTRACT_TYPES, longDay } from '../api'
import { useAuth } from '../auth'
import { Button, Form } from './Button'
import { DateInput } from './DateFields'
import { ErrorText } from './ErrorText'
import { Modal } from './Modal'
import { t } from '../i18n'

export type FollowupKind = 'TRIAL' | 'FIXED_TERM'
export type FollowupDecision = 'CONFIRMED' | 'CONFIRMED_BY_LAW' | 'ENDED' | 'RENEWED' | 'MADE_PERMANENT'

export type FollowupItem = {
  userId: number; name: string; registrationNo: string; unit: string | null; contractId: number; kind: FollowupKind
  contractType: keyof typeof CONTRACT_TYPES; startDate: string; dueOn: string; trialMonths: number | null; daysLeft: number
  decision: FollowupDecision | null; decidedOn: string | null; decidedBy: string | null; note: string | null
  letterId: number | null; newContractId: number | null; departureStatus: 'DRAFT' | 'CONFIRMED' | null
  departureLastDay: string | null; workedAfter: boolean
}

export type DayWorker = {
  userId: number; name: string; registrationNo: string; contractStart: string; daysWorked: number; warnAt: number
  maxDays: number; windowMonths: number
}

type Overview = { items: FollowupItem[]; dayWorkers: DayWorker[]; horizonDays: number }
type Result = { item: FollowupItem; warnings: string[]; departureUserId: number | null; contractId: number | null; letterId: number | null }

export const DECISIONS: Record<FollowupDecision, [string, string]> = {
  CONFIRMED: [t('Confirmed'), 'ok'],
  CONFIRMED_BY_LAW: [t('Confirmed by law'), 'ok'],
  ENDED: [t('Ended'), 'off'],
  RENEWED: [t('Renewed'), 'info'],
  MADE_PERMANENT: [t('Made permanent'), 'ok'],
}

const errorText = (e: unknown) => (e instanceof ApiError ? [e.message, ...Object.values(e.fieldErrors)].join(' ') : t('Something went wrong.'))
const today = () => new Date().toISOString().slice(0, 10)
const addDays = (d: string, n: number) => { const x = new Date(d + 'T00:00:00'); x.setDate(x.getDate() + n); return x.toISOString().slice(0, 10) }
const addMonths = (d: string, n: number) => { const x = new Date(d + 'T00:00:00'); x.setMonth(x.getMonth() + n); return x.toISOString().slice(0, 10) }

type Action = 'confirm' | 'end-trial' | 'renew' | 'permanent' | 'let-end'

/** When it ends, in words, coloured by urgency. */
function When({ item }: { item: FollowupItem }) {
  if (item.decision) return <span className={`badge ${DECISIONS[item.decision][1]}`}>{DECISIONS[item.decision][0]}</span>
  if (item.departureStatus) return <span className="badge off">{t('Departure recorded')}</span>
  const d = item.daysLeft
  const cls = d < 0 ? 'off' : d <= 15 ? 'warn' : 'info'
  return <span className={`badge ${cls}`}>{d < 0 ? t('Ended {a} days ago', { a: -d }) : d === 0 ? t('Ends today') : t('In {a} days', { a: d })}</span>
}

/**
 * Trials and fixed-term contracts coming to their end: confirm or end a trial (Labour Code art. 43 and 71), renew a
 * fixed-term contract, make it permanent or let it end (art. 41), and day workers near the 22 days of art. 40.
 */
export function ContractEnds() {
  const { can } = useAuth()
  const [data, setData] = useState<Overview | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [acting, setActing] = useState<{ item: FollowupItem; action: Action } | null>(null)
  const [done, setDone] = useState<string[]>([])
  const load = () => api<Overview>('/api/contract-followup?days=60').then(setData).catch((e: Error) => setError(e.message))
  useEffect(() => { void load() }, [])
  if (!data) return <div className="card"><h3>{t('Trials and contract ends')}</h3><ErrorText error={error} /></div>
  const contracts = can('salary.write')
  const leaving = can('payroll.run')
  const open = data.items.filter((i) => !i.decision)
  const decided = data.items.filter((i) => i.decision)
  const undo = async (i: FollowupItem) => {
    setError(null)
    try {
      await api(`/api/users/${i.userId}/contract-followup/${i.contractId}?kind=${i.kind}`, { method: 'DELETE' })
      void load()
    } catch (e) { setError(errorText(e)) }
  }
  return (
    <div className="card">
      <h3>{t('Trials and contract ends')}</h3>
      <p className="small muted">{t('Trials and fixed-term contracts ending in the next {a} days. A trial that ends with no decision confirms the contract (art. 43); work continued after a fixed-term contract makes it permanent (art. 41).', { a: data.horizonDays })}</p>
      <ErrorText error={error} />
      {done.length > 0 && <div className="notice small">{done.map((w) => <div key={w}>{w}</div>)}</div>}
      <div className="table-wrap">
        <table>
          <thead><tr><th>{t('Person')}</th><th>{t('What ends')}</th><th>{t('Ends on')}</th><th>{t('Status')}</th><th>{t('Decision')}</th></tr></thead>
          <tbody>
            {data.items.length === 0 && <tr><td colSpan={5} className="muted">{t('No trial or fixed-term contract ends soon.')}</td></tr>}
            {[...open, ...decided].map((i) => (
              <tr key={`${i.kind}-${i.userId}-${i.dueOn}`}>
                <td><Link to={`/users/${i.userId}/lifecycle`}>{i.name}</Link> <span className="muted small">{i.registrationNo}</span>
                  {i.unit && <div className="small muted">{i.unit}</div>}</td>
                <td>{i.kind === 'TRIAL' ? t('Trial of {a} months', { a: i.trialMonths ?? 0 }) : CONTRACT_TYPES[i.contractType]}
                  <div className="small muted">{t('since {a}', { a: longDay(i.startDate) })}</div></td>
                <td>{longDay(i.dueOn)}</td>
                <td><When item={i} />
                  {!i.decision && i.kind === 'FIXED_TERM' && i.daysLeft < 0 && i.workedAfter && <div className="small" style={{ color: 'var(--danger)' }}>{t('Still working: the contract is permanent now.')}</div>}</td>
                <td>
                  {i.decision ? (
                    <div className="small">
                      {i.decidedOn && longDay(i.decidedOn)}{i.decidedBy && i.decidedBy !== 'system' ? ` · ${i.decidedBy}` : ''}
                      {i.note && <div className="muted">{i.note}</div>}
                      {i.decision !== 'CONFIRMED_BY_LAW' && !i.newContractId && <div><button type="button" className="link" onClick={() => void undo(i)}>{t('Take back')}</button></div>}
                    </div>
                  ) : i.departureStatus ? (
                    <Link className="small" to={`/users/${i.userId}/departure`}>{t('See the departure')}</Link>
                  ) : i.kind === 'TRIAL' ? (
                    <div className="row">
                      <Button className="secondary small" onClick={() => setActing({ item: i, action: 'confirm' })}>{t('Confirm')}</Button>
                      {leaving && <Button className="secondary small" onClick={() => setActing({ item: i, action: 'end-trial' })}>{t('End the trial')}</Button>}
                    </div>
                  ) : (
                    <div className="row">
                      {contracts && <Button className="secondary small" onClick={() => setActing({ item: i, action: 'renew' })}>{t('Renew')}</Button>}
                      {contracts && <Button className="secondary small" onClick={() => setActing({ item: i, action: 'permanent' })}>{t('Make permanent')}</Button>}
                      {leaving && <Button className="secondary small" onClick={() => setActing({ item: i, action: 'let-end' })}>{t('Let it end')}</Button>}
                    </div>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {data.dayWorkers.length > 0 && (
        <>
          <h4>{t('Day workers')}</h4>
          <p className="small muted">{t('Days worked in the last {a} months. From {b} days, hiring the person again means a permanent contract (art. 40).', { a: data.dayWorkers[0].windowMonths, b: data.dayWorkers[0].maxDays })}</p>
          <div className="table-wrap">
            <table>
              <thead><tr><th>{t('Person')}</th><th>{t('Since')}</th><th>{t('Days worked')}</th></tr></thead>
              <tbody>
                {data.dayWorkers.map((w) => (
                  <tr key={w.userId}>
                    <td><Link to={`/users/${w.userId}/lifecycle`}>{w.name}</Link> <span className="muted small">{w.registrationNo}</span></td>
                    <td>{longDay(w.contractStart)}</td>
                    <td><span className={`badge ${w.daysWorked >= w.maxDays ? 'off' : w.daysWorked >= w.warnAt ? 'warn' : ''}`}>{w.daysWorked} / {w.maxDays}</span></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}
      {acting && <Decide item={acting.item} action={acting.action} onClose={() => setActing(null)}
        onDone={(r) => { setActing(null); setDone(r.warnings); void load() }} />}
    </div>
  )
}

const TITLES: Record<Action, string> = {
  confirm: t('Confirm the contract'),
  'end-trial': t('End the trial'),
  renew: t('Renew the fixed-term contract'),
  permanent: t('Make the contract permanent'),
  'let-end': t('Let the contract end'),
}

function Decide({ item, action, onClose, onDone }: { item: FollowupItem; action: Action; onClose: () => void; onDone: (r: Result) => void }) {
  const [lastDay, setLastDay] = useState(today() > item.dueOn ? item.dueOn : today())
  const [givenBy, setGivenBy] = useState<'EMPLOYER' | 'EMPLOYEE'>('EMPLOYER')
  const [endDate, setEndDate] = useState(addMonths(item.dueOn, 6))
  const [from, setFrom] = useState(addDays(item.dueOn, 1))
  const [note, setNote] = useState('')
  const [error, setError] = useState<string | null>(null)
  const submit = async () => {
    setError(null)
    const body = action === 'end-trial' ? { lastDay, givenBy, note }
      : action === 'renew' ? { endDate, note }
        : action === 'permanent' ? { from, note } : { note }
    try {
      onDone(await api<Result>(`/api/users/${item.userId}/contract-followup/${item.contractId}/${action}`, { method: 'POST', body }))
    } catch (e) { setError(errorText(e)) }
  }
  return (
    <Modal title={TITLES[action]} onClose={onClose}
      footer={<><Button className="secondary" type="button" onClick={onClose}>{t('Cancel')}</Button><Button type="submit" form="followup-decide">{TITLES[action]}</Button></>}>
      <Form id="followup-decide" onSubmit={submit}>
        <p><b>{item.name}</b> · {item.kind === 'TRIAL'
          ? t('trial from {a} to {b}', { a: longDay(item.startDate), b: longDay(item.dueOn) })
          : t('fixed-term contract ending on {a}', { a: longDay(item.dueOn) })}</p>
        {action === 'confirm' && <p className="small muted">{t('The contract goes on. The confirmation letter is prepared as a draft for you to read and send.')}</p>}
        {action === 'end-trial' && (
          <>
            <div className="grid2">
              <label>{t('Last day')}<DateInput required min={item.startDate} max={item.dueOn} value={lastDay} onChange={(e) => setLastDay(e.target.value)} /></label>
              <label>{t('Who ends it')}
                <select value={givenBy} onChange={(e) => setGivenBy(e.target.value as 'EMPLOYER' | 'EMPLOYEE')}>
                  <option value="EMPLOYER">{t('The company')}</option>
                  <option value="EMPLOYEE">{t('The employee')}</option>
                </select>
              </label>
            </div>
            <p className="hint">{t('In DR Congo either party gives 3 working days\' notice, none in the first 3 days of the trial (art. 71). A departure is saved as a draft for you to check and confirm.')}</p>
          </>
        )}
        {action === 'renew' && (
          <>
            <label>{t('New end date')}<DateInput required min={addDays(item.dueOn, 1)} value={endDate} onChange={(e) => setEndDate(e.target.value)} /></label>
            <p className="hint">{t('A fixed-term contract lasts two years at most and is renewed once (art. 41). The new contract starts on {a} with the same grade and basic salary.', { a: longDay(addDays(item.dueOn, 1)) })}</p>
          </>
        )}
        {action === 'permanent' && (
          <>
            <label>{t('Permanent from')}<DateInput required min={addDays(item.startDate, 1)} max={addDays(item.dueOn, 1)} value={from} onChange={(e) => setFrom(e.target.value)} /></label>
            <p className="hint">{t('Same grade and basic salary; seniority runs from the first contract.')}</p>
          </>
        )}
        {action === 'let-end' && <p className="small muted">{t('A departure "end of contract" on {a} is saved as a draft for you to check and confirm.', { a: longDay(item.dueOn) })}</p>}
        <label>{t('Note (optional)')}<input maxLength={300} value={note} onChange={(e) => setNote(e.target.value)} /></label>
        <ErrorText error={error} />
      </Form>
    </Modal>
  )
}
