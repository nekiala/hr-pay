import { useEffect, useState, type ComponentType } from 'react'
import { Link } from 'react-router-dom'
import {
  Banknote, Briefcase, CalendarDays, CircleCheck, Clock, Landmark, Lock, PencilLine, Star, Timer, TriangleAlert, Calculator, Circle,
} from 'lucide-react'
import { api, money, type HolidayOccurrence, type PayrollRun, type RunView } from '../api'
import { useAuth } from '../auth'
import MonthView from '../components/MonthView'

type Presence = { date: string; counts: Record<string, number> }
type Payments = { payslips: number; paid: number }
type Declarations = { declarations: { kind: string; filed: unknown | null }[] }
type Waiting = { label: string; detail: string; count: number; to: string; icon: ComponentType<{ size?: number }> }

const today = () => new Date(Date.now() + 60 * 60 * 1000).toISOString().slice(0, 10)   // Kinshasa is UTC+1
const monthName = (m: string) => new Date(m + '-01T00:00:00').toLocaleDateString(undefined, { month: 'long', year: 'numeric' })
const greeting = () => { const h = new Date().getHours(); return h < 12 ? 'Good morning' : h < 18 ? 'Good afternoon' : 'Good evening' }
const quiet = <T,>(p: Promise<T>) => p.catch(() => null)

/** Home: what needs doing today, for whoever signs in, limited to what their role lets them see. */
export default function HomePage() {
  const { me, can } = useAuth()
  const any = (...p: string[]) => p.some(can)
  const seesPayroll = any('payroll.run', 'payroll.approve', 'payslip.read.all')
  const seesPresence = any('attendance.read.all', 'attendance.read.team')

  const [presence, setPresence] = useState<Presence | null>(null)
  const [run, setRun] = useState<RunView | null>(null)
  const [payments, setPayments] = useState<Payments | null>(null)
  const [declared, setDeclared] = useState<Declarations | null>(null)
  const [waiting, setWaiting] = useState<Waiting[]>([])
  const [holidays, setHolidays] = useState<HolidayOccurrence[]>([])

  useEffect(() => {
    if (seesPresence) void quiet(api<Presence>(`/api/reports/presence?date=${today()}`)).then(setPresence)
    if (seesPayroll) {
      void quiet(api<PayrollRun[]>('/api/payroll/runs')).then(async (runs) => {
        const latest = runs?.[0]
        if (!latest) return
        const m = latest.month.slice(0, 7)
        setRun(await quiet(api<RunView>(`/api/payroll/runs/${m}`)))
        if (latest.status === 'CLOSED') {
          if (any('payroll.pay', 'payslip.read.all')) setPayments(await quiet(api<Payments>(`/api/payments/${m}`)))
          if (can('declaration.manage')) setDeclared(await quiet(api<Declarations>(`/api/declarations/${m}`)))
        }
      })
    }
    const year = Number(today().slice(0, 4))
    void Promise.all([quiet(api<HolidayOccurrence[]>(`/api/holidays?year=${year}`)), quiet(api<HolidayOccurrence[]>(`/api/holidays?year=${year + 1}`))])
      .then(([a, b]) => setHolidays([...(a ?? []), ...(b ?? [])].filter((h) => h.observedDate >= today()).slice(0, 4)))

    const asks: Promise<Waiting | null>[] = []
    const count = (url: string, w: Omit<Waiting, 'count'>) =>
      asks.push(quiet(api<unknown[]>(url)).then((rows) => (rows && rows.length ? { ...w, count: rows.length } : null)))
    if (any('leave.manage', 'leave.approve.team')) count('/api/leave/requests/to-decide', { label: 'Leave requests', detail: 'Waiting for your decision', to: '/leave', icon: CalendarDays })
    if (any('mission.approve.all', 'mission.approve.team')) count('/api/missions/to-decide', { label: 'Missions', detail: 'Waiting for approval', to: '/missions', icon: Briefcase })
    if (any('overtime.approve.all', 'overtime.approve.team')) count('/api/overtime/pending', { label: 'Overtime', detail: 'Days to approve before payroll', to: '/overtime/approvals', icon: Timer })
    if (any('attendance.correct.propose', 'attendance.correct')) count('/api/corrections/to-decide', { label: 'Attendance corrections', detail: 'Asked by employees', to: '/corrections', icon: PencilLine })
    if (any('evaluation.manage', 'evaluation.score.team')) count('/api/evaluations/to-do', { label: 'Evaluations', detail: 'To score or approve', to: '/evaluations', icon: Star })
    void Promise.all(asks).then((list) => setWaiting(list.filter((w): w is Waiting => w !== null)))
  }, [])

  if (!me) return null
  const total = waiting.reduce((n, w) => n + w.count, 0)
  const r = run?.run
  const t = run?.totals[0]
  const filed = declared ? declared.declarations.filter((d) => d.filed).length : 0

  type StepState = 'done' | 'now' | 'todo'
  const steps: { name: string; state: StepState; detail: string; pct: number }[] = r ? [
    { name: 'Calculated', state: 'done', detail: `${r.employeeCount} payslips`, pct: 100 },
    { name: 'Approved by Finance', state: r.status === 'CALCULATED' ? 'now' : 'done', detail: r.approvedBy ? `by ${r.approvedBy.split('@')[0]}` : 'Waiting', pct: r.status === 'CALCULATED' ? 50 : 100 },
    { name: 'Month closed', state: r.status === 'CLOSED' ? 'done' : r.status === 'APPROVED' ? 'now' : 'todo', detail: r.closedAt ? 'Payslips numbered' : 'Not yet', pct: r.status === 'CLOSED' ? 100 : 0 },
    ...(payments ? [{ name: 'Paid', state: (payments.paid >= payments.payslips ? 'done' : 'now') as StepState, detail: `${payments.paid} of ${payments.payslips} people`, pct: payments.payslips ? Math.round((payments.paid / payments.payslips) * 100) : 0 }] : []),
    ...(declared ? [{ name: 'Declared', state: (filed === declared.declarations.length ? 'done' : payments && payments.paid < payments.payslips ? 'todo' : 'now') as StepState, detail: `${filed} of ${declared.declarations.length} filed`, pct: declared.declarations.length ? Math.round((filed / declared.declarations.length) * 100) : 0 }] : []),
  ] : []

  return (
    <section>
      <div className="dash-head">
        <div>
          <span className="eyebrow">{new Date().toLocaleDateString(undefined, { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' })}</span>
          <h2>{greeting()}, {me.firstName}</h2>
          <p className="muted" style={{ margin: 0 }}>{total > 0 ? `${total} thing${total === 1 ? '' : 's'} waiting for you.` : 'Nothing is waiting for you.'}</p>
        </div>
        <div className="row" style={{ margin: 0 }}>
          {seesPayroll && <Link className="button secondary" to="/payroll"><Calculator size={16} /> Payroll</Link>}
          <Link className="button" to="/check-in"><Clock size={16} /> Check in or end my day</Link>
        </div>
      </div>

      {r && r.status !== 'CLOSED' && (
        <div className="banner">
          <span className="banner-icon"><TriangleAlert size={20} /></span>
          <div style={{ flex: 1 }}>
            <strong>{monthName(r.month.slice(0, 7))} payroll {r.status === 'CALCULATED' ? 'needs approval' : 'is approved, not closed'}</strong>
            <span className="muted">{r.status === 'CALCULATED' ? 'Finance approves it; HR then closes the month and payslips are numbered.' : 'Close the month to number payslips, record loan repayments and prepare payments.'}</span>
          </div>
          <Link className="button secondary" to="/payroll">Open</Link>
        </div>
      )}

      {(presence || t) && (
        <div className="tiles">
          {presence && <>
            <Link to="/presence" className="tile"><span>At work today</span><strong>{(presence.counts.in ?? 0) + (presence.counts.late ?? 0)}</strong><small>of {presence.counts.expected ?? 0} expected</small></Link>
            <Link to="/presence" className={`tile${(presence.counts.late ?? 0) > 0 ? ' warn' : ''}`}><span>Late</span><strong>{presence.counts.late ?? 0}</strong><small>{presence.counts.notYetIn ?? 0} not in yet</small></Link>
            <Link to="/presence" className="tile"><span>Away</span><strong>{(presence.counts.leave ?? 0) + (presence.counts.mission ?? 0)}</strong><small>{presence.counts.leave ?? 0} on leave · {presence.counts.mission ?? 0} on mission</small></Link>
          </>}
          {t && r && <>
            <Link to="/payroll" className="tile"><span>Gross pay · {r.month.slice(0, 7)}</span><strong>{money(t.gross, t.currency)}</strong><small>{t.payslips} payslips</small></Link>
            <Link to="/payroll" className="tile accent"><span>Net to pay</span><strong>{money(t.net, t.currency)}</strong><small>+ {money(t.employerCharges, t.currency)} employer charges</small></Link>
          </>}
        </div>
      )}

      <div className="dash-grid">
        <div>
          {r && (
            <div className="card">
              <div className="card-head">
                <div><h3>Payroll of {monthName(r.month.slice(0, 7))}</h3><p>From calculation to payment and declarations</p></div>
                <span className={`badge ${r.status === 'CLOSED' ? 'ok' : 'warn'}`}>{r.status.toLowerCase()}</span>
              </div>
              <div className="steps">
                {steps.map((s, i) => (
                  <div key={s.name} className={`step ${s.state}`}>
                    <span className="label-caps">Step {String(i + 1).padStart(2, '0')}
                      {s.state === 'done' ? <CircleCheck size={16} /> : s.state === 'now' ? <Lock size={15} /> : <Circle size={15} />}</span>
                    <strong>{s.name}</strong>
                    <small>{s.detail}</small>
                    <div className={`progress${s.state === 'now' ? ' warn' : ''}`}><span style={{ width: `${s.pct}%` }} /></div>
                  </div>
                ))}
              </div>
            </div>
          )}
          {can('attendance.read.own') && (
            <div className="card">
              <div className="card-head"><div><h3>My month</h3><p>Hours worked, late days and what you have earned so far</p></div></div>
              <MonthView source="/api/attendance/month" />
            </div>
          )}
        </div>

        <div>
          <div className="card">
            <div className="card-head"><div><h3>Waiting for you</h3><p>Requests that need your decision</p></div></div>
            {waiting.length === 0 ? <p className="muted">Nothing waiting. Well done.</p> : (
              <div className="todo-list">
                {waiting.map((w) => (
                  <Link key={w.label} to={w.to} className="todo-item">
                    <span className="todo-icon"><w.icon size={18} /></span>
                    <span className="todo-text"><strong>{w.label}</strong><span>{w.detail}</span></span>
                    <span className="count">{w.count}</span>
                  </Link>
                ))}
              </div>
            )}
          </div>

          {(payments || declared) && (
            <div className="card">
              <div className="card-head"><div><h3>After closing</h3><p>{r && monthName(r.month.slice(0, 7))}</p></div></div>
              <div className="todo-list">
                {payments && <Link to="/payroll/payments" className="todo-item"><span className="todo-icon"><Banknote size={18} /></span>
                  <span className="todo-text"><strong>Payments</strong><span>{payments.paid} of {payments.payslips} people paid</span></span></Link>}
                {declared && <Link to="/payroll/declarations" className="todo-item"><span className="todo-icon"><Landmark size={18} /></span>
                  <span className="todo-text"><strong>Declarations</strong><span>{filed} of {declared.declarations.length} filed (CNSS, IPR, INPP, ONEM…)</span></span></Link>}
              </div>
            </div>
          )}

          <div className="card">
            <div className="card-head"><div><h3>Coming up</h3><p>Public holidays, as observed</p></div><CalendarDays size={18} className="muted" /></div>
            {holidays.length === 0 ? <p className="muted">No holiday ahead this year.</p> : (
              <div className="date-list">
                {holidays.map((h) => {
                  const d = new Date(h.observedDate + 'T00:00:00')
                  const days = Math.round((d.getTime() - new Date(today() + 'T00:00:00').getTime()) / 86400000)
                  return (
                    <div key={h.id} className="date-item">
                      <span className="date-badge"><b>{d.toLocaleDateString(undefined, { month: 'short' })}</b><span>{d.getDate()}</span></span>
                      <div><strong>{h.name}</strong>{days <= 30 && <span className="in">{days === 0 ? 'today' : `in ${days} d`}</span>}
                        <small>{d.toLocaleDateString(undefined, { weekday: 'long' })}{h.moved ? ' · moved by the government' : ''}</small></div>
                    </div>
                  )
                })}
              </div>
            )}
          </div>
        </div>
      </div>
    </section>
  )
}
