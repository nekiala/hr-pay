import { useEffect, useState } from 'react'
import { api, money, type ElementAccounts, type Journal } from '../api'
import { useAuth } from '../auth'
import { Button } from '../components/Button'
import { ErrorText } from '../components/ErrorText'
import { t } from '../i18n'
import { LayoutDownload } from './ExportLayoutsPage'

const lastMonth = () => { const d = new Date(); d.setDate(1); d.setMonth(d.getMonth() - 1); return d.toISOString().slice(0, 7) }

/** The month's payroll as SYSCOHADA entries, to import into the accounting software; and the accounts of each element. */
export default function AccountingPage() {
  const { can } = useAuth()
  const [month, setMonth] = useState(lastMonth())
  const [byCentre, setByCentre] = useState(false)
  const [journal, setJournal] = useState<Journal | null>(null)
  const [accounts, setAccounts] = useState<ElementAccounts[]>([])
  const [edits, setEdits] = useState<Record<number, { expense: string; liability: string }>>({})
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    setError(null)
    setJournal(null)
    api<Journal>(`/api/accounting/${month}?byCostCentre=${byCentre}`).then(setJournal).catch((e: Error) => setError(e.message))
  }, [month, byCentre])
  const loadAccounts = () => api<ElementAccounts[]>('/api/accounting/accounts').then(setAccounts).catch(() => undefined)
  useEffect(() => { void loadAccounts() }, [])

  const save = async (a: ElementAccounts) => {
    const e = edits[a.elementId]
    await api(`/api/accounting/accounts/${a.elementId}`, { method: 'PUT', body: { expenseAccount: e.expense, liabilityAccount: e.liability } })
    setEdits(Object.fromEntries(Object.entries(edits).filter(([k]) => Number(k) !== a.elementId)))
    await loadAccounts()
    api<Journal>(`/api/accounting/${month}?byCostCentre=${byCentre}`).then(setJournal).catch(() => undefined)
  }

  const cur = journal?.currency ?? 'CDF'
  const query = `?byCostCentre=${byCentre}`
  return (
    <section>
      <h2>{t('Accounting entries')}</h2>
      <div className="row">
        <label className="inline">{t('Month')}<input type="month" value={month} onChange={(e) => setMonth(e.target.value)} /></label>
        <label className="check"><input type="checkbox" checked={byCentre} onChange={(e) => setByCentre(e.target.checked)} />{t('Split the expenses by cost centre')}</label>
        {journal && <>
          <a className="button secondary" href={`/api/accounting/${month}.xlsx${query}`}>{t('Download Excel')}</a>
          <a className="button secondary" href={`/api/accounting/${month}.csv${query}`}>{t('Download CSV')}</a>
          <LayoutDownload target="ACCOUNTING" path={(id) => `/api/accounting/${month}/layout/${id}${query}`} />
        </>}
      </div>
      <ErrorText error={error} />
      {journal && (
        <div className="card">
          {journal.warnings.map((w) => <p key={w} className="notice">{w}</p>)}
          <p className="small muted">{t('Journal {a} · {b} · {c} payslips · amounts in {d} at $1 = {e} {d}', { a: journal.journal, b: journal.reference, c: journal.payslips, d: cur, e: journal.rate })}</p>
          <div className="table-wrap">
            <table>
              <thead><tr><th>{t('Account')}</th><th>{t('Label')}</th>{byCentre && <th>{t('Cost centre')}</th>}<th className="num">{t('Debit')}</th><th className="num">{t('Credit')}</th></tr></thead>
              <tbody>
                {journal.entries.map((e, i) => (
                  <tr key={i}><td>{e.account}</td><td>{e.label}</td>{byCentre && <td>{e.costCentre ?? '—'}</td>}
                    <td className="num">{e.debit === null ? '' : money(e.debit, cur)}</td><td className="num">{e.credit === null ? '' : money(e.credit, cur)}</td></tr>
                ))}
                <tr className="subtotal"><td colSpan={byCentre ? 3 : 2}>{t('Total')}</td><td className="num">{money(journal.debit, cur)}</td><td className="num">{money(journal.credit, cur)}</td></tr>
              </tbody>
            </table>
          </div>
        </div>
      )}

      <div className="card">
        <h3>{t('Accounts of each pay element')}</h3>
        <p className="muted small">{t('Leave empty to use the SYSCOHADA account shown in grey. Check every account with your accountant.')}</p>
        <div className="table-wrap">
          <table>
            <thead><tr><th>{t('Element')}</th><th>{t('Expense account')}</th><th>{t('Debt account')}</th><th /></tr></thead>
            <tbody>
              {accounts.map((a) => {
                const e = edits[a.elementId] ?? { expense: a.expenseAccount ?? '', liability: a.liabilityAccount ?? '' }
                const change = (k: 'expense' | 'liability') => (ev: { target: { value: string } }) => setEdits({ ...edits, [a.elementId]: { ...e, [k]: ev.target.value } })
                return (
                  <tr key={a.elementId}>
                    <td>{a.name} <span className="muted small">{a.code}</span></td>
                    <td>{a.kind !== 'DEDUCTION' && <input className="narrow-input" value={e.expense} placeholder={a.defaultExpense ?? ''} onChange={change('expense')} disabled={!can('accounting.export')} />}</td>
                    <td>{a.kind !== 'EARNING' && <input className="narrow-input" value={e.liability} placeholder={a.defaultLiability ?? ''} onChange={change('liability')} disabled={!can('accounting.export')} />}</td>
                    <td>{edits[a.elementId] && <Button className="secondary" onClick={() => save(a)}>{t('Save')}</Button>}</td>
                  </tr>
                )
              })}
              <tr><td>{t('Net pay owed to staff')}</td><td /><td className="muted">4220</td><td /></tr>
            </tbody>
          </table>
        </div>
      </div>
    </section>
  )
}
