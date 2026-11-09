import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { ApiError, api, longDay, money, type AdvanceCategory, type AdvanceSettings, type SalaryAdvance } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'
import { useAsk } from '../components/Dialogs'
import { Button } from '../components/Button'
import { t } from '../i18n'
import { ADVANCE_STATUS } from './MyAdvancePage'

type Overview = { settings: AdvanceSettings; categories: AdvanceCategory[]; waiting: SalaryAdvance[]; month: SalaryAdvance[] }

const thisMonth = () => new Date().toISOString().slice(0, 7)

/** Salary advances on demand: requests waiting for HR, the month's advances, the rules and the share per grade category. */
export default function AdvancesPage() {
  const ask = useAsk()
  const { can } = useAuth()
  const manage = can('advance.manage')
  const [month, setMonth] = useState(thisMonth())
  const [data, setData] = useState<Overview | null>(null)
  const [form, setForm] = useState<AdvanceSettings | null>(null)
  const [categories, setCategories] = useState<AdvanceCategory[]>([])
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)

  const load = async () => {
    const o = await api<Overview>(`/api/advances?month=${month}`)
    setData(o)
    setForm({ ...o.settings, maxDebtMonths: Number(o.settings.maxDebtMonths) })
    setCategories(o.categories)
  }
  useEffect(() => { load().catch((e: Error) => setError(e.message)) }, [month]) // eslint-disable-line react-hooks/exhaustive-deps

  const run = async (action: () => Promise<unknown>, done: string) => {
    setError(null)
    setMessage(null)
    try {
      await action()
      setMessage(done)
      await load()
    } catch (e) {
      setError(e instanceof ApiError ? [e.message, ...Object.values(e.fieldErrors)].join(' ') : t('Something went wrong.'))
    }
  }

  const total = (rows: SalaryAdvance[]) => {
    const by: Record<string, number> = {}
    rows.filter((r) => r.status === 'APPROVED' || r.status === 'PAID').forEach((r) => { by[r.currency] = (by[r.currency] ?? 0) + Number(r.amount) })
    return Object.entries(by).map(([c, v]) => money(v, c)).join(' · ') || '—'
  }

  const table = (rows: SalaryAdvance[], decide: boolean) => (
    <div className="table-wrap">
      <table>
        <thead><tr><th>{t('Date')}</th><th>{t('Employee')}</th><th>{t('Number')}</th><th>{t('Amount')}</th><th>{t('Available when asked')}</th><th>{t('Signed')}</th><th>{t('Status')}</th><th></th></tr></thead>
        <tbody>
          {rows.map((a) => (
            <tr key={a.id}>
              <td>{longDay(a.createdAt)}</td>
              <td>{a.employeeName} <span className="muted">{a.registrationNo}</span></td>
              <td>{a.number ?? '—'}</td>
              <td><strong>{money(a.amount, a.currency)}</strong></td>
              <td>{money(a.available, a.currency)} <span className="hint">({Number(a.percent)}% {t('of')} {money(a.earnedNet, a.currency)})</span></td>
              <td className="small">{a.signedName}<div className="hint">{new Date(a.signedAt).toLocaleString()}{a.signedChannels ? ` · ${a.signedChannels.toLowerCase()}` : ''}</div></td>
              <td>{ADVANCE_STATUS[a.status]}{a.decidedBy && <div className="hint">{a.decidedBy === 'automatic' ? t('Approved automatically') : a.decidedBy}</div>}{a.decisionNote && <div className="hint">{a.decisionNote}</div>}</td>
              <td className="nowrap">
                {decide && manage && a.status === 'REQUESTED' && (
                  <>
                    <Button onClick={() => run(() => api(`/api/advances/${a.id}/approve`, { method: 'POST' }), t('Approved.'))}>{t('Approve')}</Button>{' '}
                    <Button className="secondary" onClick={() => ask({
                      title: t('Refuse the advance of {a}?', { a: a.employeeName }), confirmLabel: t('Refuse'), danger: true,
                      fields: [{ label: t('Reason, the employee reads it'), required: true, multiline: true }],
                      onConfirm: ([note]) => run(() => api(`/api/advances/${a.id}/reject`, { method: 'POST', body: { note } }), t('Refused.')),
                    })}>{t('Refuse')}</Button>
                  </>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )

  const setPercent = (c: AdvanceCategory, value: string) => setCategories(categories.map((x) => (x.id === c.id
    ? { ...x, advancePercent: value === '' ? null : Number(value) } : x)))

  return (
    <section>
      <div className="row between">
        <h2>{t('Salary advances')}</h2>
        {can('payroll.pay') && <Link to={`/payroll/payments?kind=ADVANCE&month=${month}`}>{t('Pay the approved advances')}</Link>}
      </div>
      <ErrorText error={error} />
      {message && <p className="success">{message}</p>}

      {data && !data.settings.enabled && <p className="notice">{t('Salary advances on demand are off. Turn them on below once the rules suit the company.')}</p>}

      {data && data.waiting.length > 0 && (
        <div className="card">
          <h3>{t('Waiting for a decision')}</h3>
          {table(data.waiting, true)}
        </div>
      )}

      {data && (
        <div className="card">
          <div className="row between">
            <h3>{t('Advances of the month')}</h3>
            <label className="month-pick">{t('Month')}{' '}<input type="month" value={month} onChange={(e) => e.target.value && setMonth(e.target.value)} /></label>
          </div>
          {data.month.length === 0 ? <p className="muted">{t('None.')}</p> : (
            <>
              <p className="muted">{t('Approved and paid')}: <strong>{total(data.month)}</strong></p>
              {table(data.month, true)}
            </>
          )}
          <p className="hint">{t('Each advance is taken back in full on the payslip of its month, before loans. What the payslip cannot take back becomes an advance repaid from the next months when the payroll closes.')}</p>
        </div>
      )}

      {form && (
        <div className="card">
          <h3>{t('Rules')}</h3>
          <label className="check"><input type="checkbox" checked={form.enabled} disabled={!manage}
            onChange={(e) => setForm({ ...form, enabled: e.target.checked })} />{t('Employees may ask for an advance on the pay already earned')}</label>
          <div className="grid3">
            <label>{t('Approval')}<select value={form.approval} disabled={!manage} onChange={(e) => setForm({ ...form, approval: e.target.value as AdvanceSettings['approval'] })}>
              <option value="AUTO">{t('Automatic within the rules')}</option>
              <option value="HR">{t('HR approves each request')}</option>
            </select></label>
            <label>{t('Requests per person and month')}<input type="number" min={1} max={10} value={form.maxPerMonth} disabled={!manage}
              onChange={(e) => setForm({ ...form, maxPerMonth: Number(e.target.value) })} /></label>
            <label>{t('No request in the last days of the month')}<input type="number" min={0} max={15} value={form.closingDays} disabled={!manage}
              onChange={(e) => setForm({ ...form, closingDays: Number(e.target.value) })} /></label>
            <label>{t('Days under contract before a first request')}<input type="number" min={0} max={365} value={form.minServiceDays} disabled={!manage}
              onChange={(e) => setForm({ ...form, minServiceDays: Number(e.target.value) })} /></label>
            <label>{t('No advance above a debt of (months of net pay)')}<input type="number" min={0.5} max={12} step={0.5} value={form.maxDebtMonths} disabled={!manage}
              onChange={(e) => setForm({ ...form, maxDebtMonths: Number(e.target.value) })} /></label>
          </div>
          {manage && <Button className="secondary" onClick={() => run(() => api('/api/advances/settings', { method: 'PUT', body: form }),
            form.enabled ? t('Salary advances turned on.') : t('Rules saved.'))}>{t('Save')}</Button>}
          <p className="hint">{t('The company pays the advance from its own funds, free of interest and fees (DRC Labour Code art. 112 c). Payroll keeps the total of loans and advances taken from a payslip within art. 114: one fifth of the net up to five times the monthly minimum wage of the category, one third above. Finance gets the list of advances to pay each morning.')}</p>

          <h3>{t('Share of the net already earned, by grade category')}</h3>
          <div className="table-wrap">
            <table>
              <thead><tr><th>{t('Category')}</th><th>{t('Share (%)')}</th><th></th></tr></thead>
              <tbody>
                {categories.map((c) => (
                  <tr key={c.id}>
                    <td>{c.name} <span className="muted">{c.code}</span></td>
                    <td><input type="number" min={10} max={50} step={0.5} className="narrow-input" placeholder={t('none')} disabled={!manage}
                      value={c.advancePercent ?? ''} onChange={(e) => setPercent(c, e.target.value)} /></td>
                    <td>{manage && <Button className="secondary" onClick={() => run(() => api(`/api/advances/categories/${c.id}`,
                      { method: 'PUT', body: { percent: c.advancePercent } }), t('{a} saved.', { a: c.code }))}>{t('Save')}</Button>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <p className="hint">{t('From 10% to 50%. Leave it empty for a category that may not ask. The quinzaine of the month counts in the share.')}</p>
        </div>
      )}
    </section>
  )
}
