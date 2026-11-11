import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { ApiError, api, type GradeCategory, type MissionSettings, type PerDiemRate } from '../api'
import { ErrorText } from '../components/ErrorText'
import { Button } from '../components/Button'
import { t } from '../i18n'

const ZONE: Record<PerDiemRate['zone'], string> = {
  DOMESTIC: t('In the country'), AFRICA: t('Elsewhere in Africa'), WORLD: t('Rest of the world'),
}

type Row = { id: number | null; categoryId: number | null; zone: PerDiemRate['zone']; amount: string; lodgingAmount: string; currency: string }

/** Mission settings: the per diem scale, who approves, the transport allowance on mission days, the report deadline. */
export default function MissionSettingsPage() {
  const [settings, setSettings] = useState<MissionSettings>({ transportOnTravel: 'STOP', secondApproval: 'INTERNATIONAL', reportDays: 10 })
  const [rows, setRows] = useState<Row[]>([])
  const [categories, setCategories] = useState<GradeCategory[]>([])
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)

  const load = async () => {
    setSettings(await api<MissionSettings>('/api/travel-missions/settings'))
    const scales = await api<PerDiemRate[]>('/api/travel-missions/per-diem')
    setRows(scales.map((s) => ({ id: s.id, categoryId: s.categoryId, zone: s.zone, amount: String(s.amount), lodgingAmount: String(s.lodgingAmount), currency: s.currency })))
  }
  useEffect(() => {
    load().catch((e: Error) => setError(e.message))
    api<GradeCategory[]>('/api/leave/categories').then(setCategories).catch(() => undefined)
  }, [])

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

  const set = (i: number, patch: Partial<Row>) => setRows(rows.map((r, j) => (j === i ? { ...r, ...patch } : r)))
  const body = (r: Row) => ({ categoryId: r.categoryId, zone: r.zone, amount: Number(r.amount), lodgingAmount: Number(r.lodgingAmount || 0), currency: r.currency })

  return (
    <section>
      <p className="small muted"><Link to="/missions">{t('← Missions')}</Link></p>
      <h2>{t('Mission settings')}</h2>
      <ErrorText error={error} />
      {message && <p className="success">{message}</p>}

      <div className="card">
        <h3>{t('Rules')}</h3>
        <label>{t('HR approves after the unit head')}
          <select className="auto-width" value={settings.secondApproval} onChange={(e) => setSettings({ ...settings, secondApproval: e.target.value as MissionSettings['secondApproval'] })}>
            <option value="INTERNATIONAL">{t('Missions abroad')}</option>
            <option value="ALL">{t('Every mission')}</option>
            <option value="NONE">{t('Never: the unit head approves alone')}</option>
          </select>
        </label>
        <label>{t('Transport allowance on mission days')}
          <select className="auto-width" value={settings.transportOnTravel} onChange={(e) => setSettings({ ...settings, transportOnTravel: e.target.value as MissionSettings['transportOnTravel'] })}>
            <option value="STOP">{t('Not paid: the employee does not commute')}</option>
            <option value="PAY">{t('Paid as usual')}</option>
          </select>
        </label>
        <label>{t('Report and receipts due, days after the return')}
          <input className="auto-width" type="number" min={1} max={60} value={settings.reportDays} onChange={(e) => setSettings({ ...settings, reportDays: Number(e.target.value) })} />
        </label>
        <Button onClick={() => run(() => api('/api/travel-missions/settings', { method: 'PUT', body: settings }), t('Mission settings saved.'))}>{t('Save')}</Button>
      </div>

      <div className="card">
        <h3>{t('Per diem scale')}</h3>
        <p className="muted">{t('A daily amount for meals and small costs, by grade category and zone, and a nightly amount used only when the company does not book the hotel. Within the scale, the per diem is a cost of the mission, not pay. A higher rate on a mission puts the difference on the payslip, with CNSS and income tax. Amounts are to be confirmed by your payroll adviser.')}</p>
        <div className="table-wrap">
          <table>
            <thead><tr><th>{t('Grade category')}</th><th>{t('Zone')}</th><th>{t('Per day')}</th><th>{t('Hotel per night')}</th><th>{t('Currency')}</th><th></th></tr></thead>
            <tbody>
              {rows.length === 0 && <tr><td colSpan={6} className="muted">{t('No rate yet: missions have no per diem until you add one.')}</td></tr>}
              {rows.map((r, i) => (
                <tr key={r.id ?? `new${i}`}>
                  <td><select value={r.categoryId ?? ''} onChange={(e) => set(i, { categoryId: e.target.value ? Number(e.target.value) : null })}>
                    <option value="">{t('Every category')}</option>
                    {categories.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
                  </select></td>
                  <td><select value={r.zone} onChange={(e) => set(i, { zone: e.target.value as Row['zone'] })}>
                    {Object.entries(ZONE).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
                  </select></td>
                  <td><input type="number" min={0} step="0.01" value={r.amount} onChange={(e) => set(i, { amount: e.target.value })} /></td>
                  <td><input type="number" min={0} step="0.01" value={r.lodgingAmount} onChange={(e) => set(i, { lodgingAmount: e.target.value })} /></td>
                  <td><select value={r.currency} onChange={(e) => set(i, { currency: e.target.value })}>
                    {['USD', 'CDF', 'XAF'].map((c) => <option key={c}>{c}</option>)}
                  </select></td>
                  <td className="row">
                    <Button className="secondary" onClick={() => run(() => (r.id === null
                      ? api('/api/travel-missions/per-diem', { method: 'POST', body: body(r) })
                      : api(`/api/travel-missions/per-diem/${r.id}`, { method: 'PUT', body: body(r) })), t('Rate saved.'))}>{t('Save')}</Button>
                    {r.id !== null
                      ? <Button className="link" onClick={() => run(() => api(`/api/travel-missions/per-diem/${r.id}`, { method: 'DELETE' }), t('Rate removed.'))}>{t('Remove')}</Button>
                      : <Button className="link" onClick={() => setRows(rows.filter((_, j) => j !== i))}>{t('Cancel')}</Button>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <Button className="secondary" onClick={() => setRows([...rows, { id: null, categoryId: null, zone: 'DOMESTIC', amount: '', lodgingAmount: '0', currency: 'USD' }])}>{t('Add a rate')}</Button>
      </div>
    </section>
  )
}
