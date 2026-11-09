import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { ApiError, api, longDay } from '../api'
import { Button, Form } from '../components/Button'
import { useAsk } from '../components/Dialogs'
import { ErrorText } from '../components/ErrorText'
import { Modal } from '../components/Modal'
import { t } from '../i18n'

type Role = { code: string; name: string; description: string | null }
type FirmLink = {
  id: number; firmId: number; firmName: string; firmRegistration: string | null; firmEmail: string | null
  status: 'INVITED' | 'ACTIVE' | 'DECLINED' | 'ENDED'; roleCodes: string[]; billing: 'COMMISSION' | 'WHOLESALE' | null
  invitedBy: string; invitedAt: string; answeredBy: string | null; answeredAt: string | null
  endedBy: string | null; endedAt: string | null; endReason: string | null; pagesOpened: number; lastVisitAt: string | null
}
type Visit = { visitor: string; method: string; path: string; at: string }
type Page = { links: FirmLink[]; roles: Role[]; clauseVersion: string }

const STATUS: Record<FirmLink['status'], string> = {
  INVITED: t('Invited, waiting for the firm'), ACTIVE: t('Working for you'), DECLINED: t('Declined'), ENDED: t('Ended'),
}
const fail = (e: unknown) => e instanceof ApiError ? [e.message, ...Object.values(e.fieldErrors)].join(' ') : t('Something went wrong.')

/** The company's accounting firm: invite it, choose what it may do, see what it opened, end it. */
export default function CompanyAccountantPage() {
  const ask = useAsk()
  const [page, setPage] = useState<Page | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const [email, setEmail] = useState('')
  const [roles, setRoles] = useState<string[]>(['HR', 'FINANCE'])
  const [clause, setClause] = useState(false)
  const [editing, setEditing] = useState<FirmLink | null>(null)
  const [editRoles, setEditRoles] = useState<string[]>([])
  const [visits, setVisits] = useState<{ link: FirmLink; rows: Visit[] } | null>(null)

  const load = () => api<Page>('/api/company/firms').then((p) => {
    setPage(p)
    setRoles((r) => r.filter((code) => p.roles.some((x) => x.code === code)))
  }).catch((e) => setError(fail(e)))
  useEffect(() => { void load() }, [])

  const run = async (work: () => Promise<unknown>, done?: string) => {
    setError(null)
    setMessage(null)
    try { await work(); if (done) setMessage(done); await load() } catch (e) { setError(fail(e)) }
  }

  const roleName = (code: string) => page?.roles.find((r) => r.code === code)?.name ?? code
  const toggle = (list: string[], code: string, on: boolean) => on ? [...list, code] : list.filter((c) => c !== code)
  const roleBoxes = (list: string[], set: (l: string[]) => void) => page?.roles.map((r) => (
    <label key={r.code} className="check">
      <input type="checkbox" checked={list.includes(r.code)} onChange={(e) => set(toggle(list, r.code, e.target.checked))} />
      {r.name} {r.description && <span className="muted small">{r.description}</span>}
    </label>
  ))

  return (
    <section>
      <p className="small muted"><Link to="/company">{t('Company')}</Link></p>
      <h2>{t('Accounting firm')}</h2>
      <ErrorText error={error} />
      {message && <p className="success">{message}</p>}

      <div className="card">
        <h3>{t('Invite a firm')}</h3>
        <p className="hint">{t('Your accounting firm then works in your HR Pay with the rights you choose, without you sharing a password. You can change them or end the access at any time.')}</p>
        <Form onSubmit={() => run(async () => {
          await api('/api/company/firms', { method: 'POST', body: { firmEmail: email, roleCodes: roles, clauseAccepted: clause } })
          setEmail('')
          setClause(false)
        }, t('Invitation sent. The firm accepts it from its own HR Pay.'))}>
          <label>{t('Email address of the firm\'s administrator')}<input type="email" required value={email} onChange={(e) => setEmail(e.target.value)} /></label>
          <fieldset>
            <legend>{t('What the firm may do: the rights of these roles')}</legend>
            {roleBoxes(roles, setRoles)}
            <p className="hint">{t('The firm never gets your subscription, nor the right to invite other firms.')}</p>
          </fieldset>
          <div className="notice">
            <p><strong>{t('Data processing clause ({a})', { a: page?.clauseVersion ?? '' })}</strong></p>
            <p className="small">{t('Your company remains responsible for its employees\' personal data. The firm processes them only on your behalf, for the work you give it, and keeps them confidential. It lets its staff see only what that work needs, keeps nothing once the access ends, and tells you without delay of any breach. Every page its staff open and every change they make is kept and shown to you. DRC: Code du numérique (loi 23/010); Republic of the Congo: loi 29-2019.')}</p>
            <label className="check"><input type="checkbox" checked={clause} onChange={(e) => setClause(e.target.checked)} />{t('I accept this clause on behalf of the company')}</label>
          </div>
          <Button type="submit" disabled={!clause || roles.length === 0}>{t('Invite')}</Button>
        </Form>
      </div>

      {page && page.links.length > 0 && (
        <div className="card">
          <h3>{t('Firms')}</h3>
          <div className="table-wrap">
            <table>
              <thead><tr><th>{t('Firm')}</th><th>{t('Status')}</th><th>{t('Rights')}</th><th>{t('Paid by')}</th><th>{t('Pages opened')}</th><th></th></tr></thead>
              <tbody>
                {page.links.map((l) => (
                  <tr key={l.id}>
                    <td><strong>{l.firmName}</strong>
                      <div className="small muted">{[l.firmRegistration, l.firmEmail].filter(Boolean).join(' · ')}</div></td>
                    <td>{STATUS[l.status]}
                      <div className="small muted">{l.endedAt
                        ? t('{a}, {b}', { a: l.endedBy ?? '', b: longDay(l.endedAt) }) + (l.endReason ? ` · ${l.endReason}` : '')
                        : t('Invited by {a} on {b}', { a: l.invitedBy, b: longDay(l.invitedAt) })}</div></td>
                    <td className="small">{l.roleCodes.map(roleName).join(', ')}</td>
                    <td className="small">{l.billing === 'WHOLESALE' ? t('The firm') : l.billing === 'COMMISSION' ? t('Your company') : '—'}</td>
                    <td>{l.pagesOpened > 0
                      ? <Button className="link" onClick={() => void api<Visit[]>(`/api/company/firms/${l.id}/visits`).then((rows) => setVisits({ link: l, rows })).catch((e) => setError(fail(e)))}>
                          {l.pagesOpened}{l.lastVisitAt ? ` · ${longDay(l.lastVisitAt)}` : ''}</Button>
                      : '—'}</td>
                    <td>{(l.status === 'ACTIVE' || l.status === 'INVITED') && (
                      <>
                        <Button className="link" onClick={() => { setEditRoles(l.roleCodes); setEditing(l) }}>{t('Rights')}</Button>{' '}
                        <Button className="link" onClick={() => ask({
                          title: t('End the access of {a}?', { a: l.firmName }), confirmLabel: t('End the access'), danger: true,
                          message: t('Its staff lose access to your company at once.'),
                          fields: [{ label: t('Reason (optional)'), multiline: true }],
                          onConfirm: ([reason]) => run(() => api(`/api/company/firms/${l.id}/end`, { method: 'POST', body: { reason } }), t('Access ended.')),
                        })}>{t('End')}</Button>
                      </>
                    )}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {editing && (
        <Modal title={t('Rights of {a}', { a: editing.firmName })} onClose={() => setEditing(null)} footer={<>
          <Button className="secondary" onClick={() => setEditing(null)}>{t('Cancel')}</Button>
          <Button disabled={editRoles.length === 0} onClick={async () => {
            const l = editing
            setEditing(null)
            await run(() => api(`/api/company/firms/${l.id}/roles`, { method: 'PUT', body: { roleCodes: editRoles } }), t('Rights saved.'))
          }}>{t('Save')}</Button>
        </>}>
          {roleBoxes(editRoles, setEditRoles)}
        </Modal>
      )}

      {visits && (
        <Modal size="lg" title={t('What {a} opened', { a: visits.link.firmName })} onClose={() => setVisits(null)}>
          <div className="table-wrap">
            <table>
              <thead><tr><th>{t('When')}</th><th>{t('Who')}</th><th>{t('What')}</th></tr></thead>
              <tbody>
                {visits.rows.map((v, i) => (
                  <tr key={i}><td className="small">{new Date(v.at).toLocaleString()}</td><td className="small">{v.visitor}</td>
                    <td className="small"><code>{v.method === 'GET' ? '' : v.method + ' '}{v.path}</code></td></tr>
                ))}
              </tbody>
            </table>
          </div>
          <p className="hint">{t('The last 100 lines. Changes are also in the audit log.')}</p>
        </Modal>
      )}
    </section>
  )
}
