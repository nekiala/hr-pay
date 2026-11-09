import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { ApiError, api, longDay, money } from '../api'
import { useAuth } from '../auth'
import { Button } from '../components/Button'
import { useAsk } from '../components/Dialogs'
import { ErrorText } from '../components/ErrorText'
import { Modal } from '../components/Modal'
import { t } from '../i18n'

type Billing = 'COMMISSION' | 'WHOLESALE'
type Client = {
  id: number; clientId: number; clientName: string; countryCode: string; status: 'INVITED' | 'ACTIVE'
  roleNames: string[]; billing: Billing | null; invitedBy: string; invitedAt: string; employees: number
  lastRun: { month: string; status: string } | null; openChecks: number | null; openInvoices: number; overdue: boolean
  companyStatus: string; staff: number[]
}
type Staff = { id: number; name: string; email: string; admin: boolean }
type FirmInvoice = { id: number; clientName: string; number: string; issuedOn: string; dueOn: string; currency: string; total: number; discountPercent: number; status: string; overdue: boolean }
type Commission = { id: number; clientName: string; invoiceNumber: string; base: number; percent: number; amount: number; currency: string; createdAt: string; paidOutAt: string | null; paidOutRef: string | null }
type Page = { clients: Client[]; staff: Staff[]; admin: boolean; invoices: FirmInvoice[]; commissions: Commission[]; terms: { commissionPercent: number; discountPercent: number } }

const RUN = { CALCULATED: t('calculated'), APPROVED: t('approved'), CLOSED: t('closed') } as Record<string, string>
const fail = (e: unknown) => e instanceof ApiError ? [e.message, ...Object.values(e.fieldErrors)].join(' ') : t('Something went wrong.')

/** The accounting firm's clients on one screen; one click opens a client with the rights it gave the firm. */
export default function FirmPage() {
  const { refresh } = useAuth()
  const navigate = useNavigate()
  const ask = useAsk()
  const [page, setPage] = useState<Page | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [accepting, setAccepting] = useState<Client | null>(null)
  const [billing, setBilling] = useState<Billing>('COMMISSION')
  const [assigning, setAssigning] = useState<Client | null>(null)
  const [chosen, setChosen] = useState<number[]>([])

  const load = () => api<Page>('/api/firm').then(setPage).catch((e) => setError(fail(e)))
  useEffect(() => { void load() }, [])

  const act = async (work: () => Promise<unknown>) => {
    setError(null)
    try { await work(); await load() } catch (e) { setError(fail(e)) }
  }
  const open = async (c: Client) => {
    setError(null)
    try {
      await api(`/api/firm/clients/${c.id}/open`, { method: 'POST' })
      await refresh()
      navigate('/')
    } catch (e) { setError(fail(e)) }
  }

  const invited = page?.clients.filter((c) => c.status === 'INVITED') ?? []
  const active = page?.clients.filter((c) => c.status === 'ACTIVE') ?? []

  return (
    <section>
      <h2>{t('Clients')}</h2>
      <ErrorText error={error} />
      {invited.length > 0 && (
        <div className="card">
          <h3>{t('Invitations')}</h3>
          <div className="table-wrap">
            <table>
              <tbody>
                {invited.map((c) => (
                  <tr key={c.id}>
                    <td><strong>{c.clientName}</strong> <span className="muted">{c.countryCode}</span></td>
                    <td className="small">{t('Invited by {a} on {b}', { a: c.invitedBy, b: longDay(c.invitedAt) })}</td>
                    <td className="small">{t('Rights: {a}', { a: c.roleNames.join(', ') })}</td>
                    <td>
                      <Button onClick={() => { setBilling('COMMISSION'); setAccepting(c) }}>{t('Accept')}</Button>{' '}
                      <Button className="secondary" onClick={() => ask({
                        title: t('Decline the invitation of {a}?', { a: c.clientName }), confirmLabel: t('Decline'),
                        onConfirm: () => act(() => api(`/api/firm/clients/${c.id}/decline`, { method: 'POST' })),
                      })}>{t('Decline')}</Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      <div className="card">
        <h3>{t('Your clients')}</h3>
        {page && active.length === 0 && <p className="muted">{page.admin
          ? t('No client yet. A company invites your firm from its own HR Pay, with your administrator\'s email address.')
          : t('Your firm\'s administrator has not given you a client yet.')}</p>}
        {active.length > 0 && (
          <div className="table-wrap">
            <table>
              <thead><tr><th>{t('Client')}</th><th>{t('Employees')}</th><th>{t('Last payroll')}</th><th>{t('Checks')}</th><th>{t('Invoices')}</th>{page?.admin && <th>{t('Paid by')}</th>}<th></th></tr></thead>
              <tbody>
                {active.map((c) => (
                  <tr key={c.id}>
                    <td><strong>{c.clientName}</strong> <span className="muted">{c.countryCode}</span>
                      {c.companyStatus === 'SUSPENDED' && <>{' '}<span className="badge off">{t('Read-only')}</span></>}
                      <div className="small muted">{c.roleNames.join(', ')}</div></td>
                    <td>{c.employees}</td>
                    <td>{c.lastRun ? `${c.lastRun.month} (${RUN[c.lastRun.status] ?? c.lastRun.status})` : '—'}</td>
                    <td>{c.openChecks === null ? '—' : c.openChecks === 0
                      ? <span className="badge ok">{t('Clear')}</span>
                      : <span className="badge warn">{t('{a} open', { a: c.openChecks })}</span>}</td>
                    <td>{c.openInvoices === 0 ? '—' : <span className={`badge ${c.overdue ? 'off' : 'warn'}`}>{t('{a} to pay', { a: c.openInvoices })}</span>}</td>
                    {page?.admin && <td className="small">{c.billing === 'WHOLESALE' ? t('Your firm') : t('The client')}</td>}
                    <td>
                      <Button onClick={() => void open(c)}>{t('Open')}</Button>
                      {page?.admin && (
                        <>{' '}<Button className="link" onClick={() => { setChosen(c.staff); setAssigning(c) }}>{t('Staff ({a})', { a: c.staff.length })}</Button>
                          {' '}<Button className="link" onClick={() => ask({
                            title: t('Stop working for {a}?', { a: c.clientName }), confirmLabel: t('Stop'), danger: true,
                            message: t('Your firm loses its access to this client at once. The client can invite you again.'),
                            fields: [{ label: t('Reason (optional)'), multiline: true }],
                            onConfirm: ([reason]) => act(() => api(`/api/firm/clients/${c.id}/leave`, { method: 'POST', body: { reason } })),
                          })}>{t('Stop')}</Button></>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <p className="hint">{t('Each client chose what your firm may do in its company. Everything you change there is in its audit log under your name and your firm\'s.')}</p>
      </div>

      {page?.admin && page.invoices.length > 0 && (
        <div className="card">
          <h3>{t('Invoices your firm pays')}</h3>
          <div className="table-wrap">
            <table>
              <thead><tr><th>{t('Number')}</th><th>{t('Client')}</th><th>{t('Issued')}</th><th>{t('Due')}</th><th>{t('Total')}</th><th>{t('Status')}</th></tr></thead>
              <tbody>
                {page.invoices.map((i) => (
                  <tr key={i.id}>
                    <td>{i.number}</td><td>{i.clientName}</td><td>{longDay(i.issuedOn)}</td><td>{longDay(i.dueOn)}</td>
                    <td>{money(i.total, i.currency)} <span className="muted small">(−{i.discountPercent} %)</span></td>
                    <td>{i.status === 'OPEN' ? <span className={`badge ${i.overdue ? 'off' : 'warn'}`}>{i.overdue ? t('Overdue') : t('To pay')}</span> : i.status === 'PAID' ? t('Paid') : t('Cancelled')}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <p className="hint">{t('Pay them like any HR Pay invoice, with the invoice number as the reference. An unpaid invoice makes that client read-only after the grace days.')}</p>
        </div>
      )}

      {page?.admin && (
        <div className="card">
          <h3>{t('Commissions')}</h3>
          <p className="hint">{t('For clients that pay HR Pay themselves, your firm earns {a} % of each paid invoice before tax. For clients your firm pays for, the price is {b} % lower.',
            { a: page.terms.commissionPercent, b: page.terms.discountPercent })}</p>
          {page.commissions.length === 0 ? <p className="muted">{t('No commission yet.')}</p> : (
            <div className="table-wrap">
              <table>
                <thead><tr><th>{t('Date')}</th><th>{t('Client')}</th><th>{t('Invoice')}</th><th>{t('Amount')}</th><th>{t('Paid out')}</th></tr></thead>
                <tbody>
                  {page.commissions.map((m) => (
                    <tr key={m.id}>
                      <td>{longDay(m.createdAt)}</td><td>{m.clientName}</td><td>{m.invoiceNumber}</td>
                      <td>{money(m.amount, m.currency)} <span className="muted small">({m.percent} % × {money(m.base, m.currency)})</span></td>
                      <td>{m.paidOutAt ? `${longDay(m.paidOutAt)} · ${m.paidOutRef ?? ''}` : t('Not yet')}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}

      {accepting && (
        <Modal title={t('Work for {a}', { a: accepting.clientName })} onClose={() => setAccepting(null)} footer={<>
          <Button className="secondary" onClick={() => setAccepting(null)}>{t('Cancel')}</Button>
          <Button onClick={async () => {
            const c = accepting
            setAccepting(null)
            await act(() => api(`/api/firm/clients/${c.id}/accept`, { method: 'POST', body: { billing } }))
          }}>{t('Accept')}</Button>
        </>}>
          <p>{t('Who pays HR Pay for this client?')}</p>
          <label className="check"><input type="radio" name="billing" checked={billing === 'COMMISSION'} onChange={() => setBilling('COMMISSION')} />
            {t('The client pays its subscription; your firm earns {a} % of each paid invoice.', { a: page?.terms.commissionPercent ?? 0 })}</label>
          <label className="check"><input type="radio" name="billing" checked={billing === 'WHOLESALE'} onChange={() => setBilling('WHOLESALE')} />
            {t('Your firm pays, {a} % below the price, and bills the client itself.', { a: page?.terms.discountPercent ?? 0 })}</label>
        </Modal>
      )}

      {assigning && page && (
        <Modal title={t('Who works for {a}?', { a: assigning.clientName })} onClose={() => setAssigning(null)} footer={<>
          <Button className="secondary" onClick={() => setAssigning(null)}>{t('Cancel')}</Button>
          <Button onClick={async () => {
            const c = assigning
            setAssigning(null)
            await act(() => api(`/api/firm/clients/${c.id}/staff`, { method: 'PUT', body: { userIds: chosen } }))
          }}>{t('Save')}</Button>
        </>}>
          {page.staff.map((s) => (
            <label key={s.id} className="check">
              <input type="checkbox" disabled={s.admin} checked={s.admin || chosen.includes(s.id)}
                onChange={(e) => setChosen(e.target.checked ? [...chosen, s.id] : chosen.filter((x) => x !== s.id))} />
              {s.name} <span className="muted small">{s.email}{s.admin ? ` · ${t('administrator: all clients')}` : ''}</span>
            </label>
          ))}
        </Modal>
      )}
    </section>
  )
}
