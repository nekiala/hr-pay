import { useEffect, useState, type FormEvent } from 'react'
import { ApiError, api, money, type PayElement, type PayEntry } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'
import { useAsk } from '../components/Dialogs'
import { Button, Form } from '../components/Button'
import { PageTitle, SettingsLink } from '../components/SettingsLink'
import { PersonSelect } from '../components/PersonSelect'
import { t } from '../i18n'

const thisMonth = () => new Date().toISOString().slice(0, 7)

/** One-off bonuses and deductions for one month's payroll. The catalogue of pay elements is a setting. */
export default function PayEntriesPage() {
  const ask = useAsk()
  const { can } = useAuth()
  const writePay = can('salary.write')
  const [elements, setElements] = useState<PayElement[]>([])
  const [month, setMonth] = useState(thisMonth())
  const [entries, setEntries] = useState<PayEntry[]>([])
  const [entry, setEntry] = useState({ userId: '', elementId: '', amount: '', currency: 'USD', note: '' })
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)

  const loadEntries = () => api<PayEntry[]>(`/api/pay-entries?month=${month}`).then(setEntries).catch((e: Error) => setError(e.message))
  useEffect(() => { api<PayElement[]>('/api/pay-elements').then(setElements).catch((e: Error) => setError(e.message)) }, [])
  useEffect(() => { void loadEntries() }, [month]) // eslint-disable-line react-hooks/exhaustive-deps


  const run = async (action: () => Promise<unknown>, done: string) => {
    setError(null)
    setMessage(null)
    try {
      await action()
      setMessage(done)
      await loadEntries()
      return true
    } catch (e) {
      setError(e instanceof ApiError ? [e.message, ...Object.values(e.fieldErrors)].join(' ') : t('Something went wrong.'))
      return false
    }
  }

  const addEntry = (ev: FormEvent) => {
    ev.preventDefault()
    return run(async () => {
      await api('/api/pay-entries', {
        method: 'POST',
        body: { userId: Number(entry.userId), elementId: Number(entry.elementId), month, amount: Number(entry.amount), currency: entry.currency, note: entry.note || null },
      })
      setEntry({ userId: '', elementId: '', amount: '', currency: entry.currency, note: '' })
    }, t('Entry added.'))
  }

  const manual = elements.filter((e) => e.active && e.method !== 'COMPUTED' && e.kind !== 'EMPLOYER_CHARGE')

  return (
    <section>
      <PageTitle title={t('Bonuses and deductions')}>{can('masterdata.manage') && <SettingsLink to="/payroll/elements" label={t('Pay elements')} />}</PageTitle>
      <p className="muted">{t('One-off lines for one month, such as a bonus. Standing items are set in the employee\'s file.')}</p>
      <ErrorText error={error} />
      {message && <p className="success">{message}</p>}

        <div className="card">
          <label className="month-pick">{t('Month')}{' '}<input type="month" value={month} onChange={(e) => setMonth(e.target.value)} /></label>
          {entries.length === 0 ? <p className="muted">{t('Nothing entered for')}{' '}{month}.</p> : (
            <div className="table-wrap">
              <table>
                <thead><tr><th>{t('Employee')}</th><th>{t('Element')}</th><th>{t('Amount')}</th><th>{t('Note')}</th><th>{t('By')}</th><th></th></tr></thead>
                <tbody>
                  {entries.map((x) => (
                    <tr key={x.id}>
                      <td>{x.userName}</td>
                      <td>{x.elementName}</td>
                      <td>{x.kind === 'DEDUCTION' ? '−' : ''}{money(x.amount, x.currency)}</td>
                      <td>{x.note}</td>
                      <td className="muted">{x.createdBy}</td>
                      <td>{writePay && <Button className="secondary" onClick={() => ask({ title: t('Remove {a} for {b}?', { a: x.elementName, b: x.userName }), confirmLabel: t('Remove'), danger: true, onConfirm: () => run(() => api(`/api/pay-entries/${x.id}`, { method: 'DELETE' }), t('Removed.')) })}>{t('Remove')}</Button>}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {writePay && (
            <Form onSubmit={addEntry}>
              <div className="grid3">
                <label>{t('Employee')}<PersonSelect required value={entry.userId} onChange={(v) => setEntry({ ...entry, userId: v })} /></label>
                <label>{t('Element')}<select value={entry.elementId} required onChange={(e) => setEntry({ ...entry, elementId: e.target.value })}>
                    <option value="">{t('Choose')}</option>
                    {manual.map((e) => <option key={e.id} value={e.id}>{e.name}{e.kind === 'DEDUCTION' ? t(' (deduction)') : ''}</option>)}
                  </select>
                </label>
                <label>{t('Amount')}<input type="number" min={0.01} step={0.01} value={entry.amount} required onChange={(e) => setEntry({ ...entry, amount: e.target.value })} /></label>
                <label>{t('Currency')}<select value={entry.currency} onChange={(e) => setEntry({ ...entry, currency: e.target.value })}><option>{t('USD')}</option><option>{t('CDF')}</option></select></label>
                <label>{t('Note')}<input value={entry.note} maxLength={300} onChange={(e) => setEntry({ ...entry, note: e.target.value })} /></label>
              </div>
              <Button type="submit" disabled={!entry.userId}>{t('Add for')}{' '}{month}</Button>
            </Form>
          )}
        </div>
    </section>
  )
}
