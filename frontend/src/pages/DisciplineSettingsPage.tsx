import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { ApiError, api } from '../api'
import { Button } from '../components/Button'
import { ErrorText } from '../components/ErrorText'
import { t } from '../i18n'
import type { SanctionType } from './DisciplinePage'

type Settings = { rankingMonths: number; followUpDays: number; suspensionAllowed: boolean; suspensionMaxDays: number; suspensionYearDays: number }

/** Discipline settings: the sanctions used and their weight, the mise à pied, and the rankings' rules. */
export default function DisciplineSettingsPage() {
  const [settings, setSettings] = useState<Settings | null>(null)
  const [types, setTypes] = useState<SanctionType[]>([])
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const load = async () => {
    setSettings(await api<Settings>('/api/discipline/settings'))
    setTypes(await api<SanctionType[]>('/api/discipline/types'))
  }
  useEffect(() => { load().catch((e: Error) => setError(e.message)) }, [])
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
  const set = (i: number, patch: Partial<SanctionType>) => setTypes(types.map((x, j) => (j === i ? { ...x, ...patch } : x)))

  return (
    <section>
      <p className="small muted"><Link to="/discipline">{t('← Discipline register')}</Link></p>
      <h2>{t('Discipline settings')}</h2>
      <ErrorText error={error} />
      {message && <p className="success">{message}</p>}
      {settings && (
        <div className="card">
          <h3>{t('Rules')}</h3>
          <label className="check"><input type="checkbox" checked={settings.suspensionAllowed} onChange={(e) => setSettings({ ...settings, suspensionAllowed: e.target.checked })} />
            {t('Our company rules (or the collective agreement) provide the mise à pied')}</label>
          <p className="hint">{t('Labour Code art. 57: a mise à pied is possible only when the contract, the collective agreement or the company rules provide it. Company rules are compulsory from 20 workers and are filed with the labour court (Arrêté 70/0016).')}</p>
          <div className="grid2">
            <label>{t('Days of one mise à pied, at most')}<input type="number" min={1} max={15} value={settings.suspensionMaxDays} onChange={(e) => setSettings({ ...settings, suspensionMaxDays: Number(e.target.value) })} /></label>
            <label>{t('Days of mise à pied in a year, at most')}<input type="number" min={1} max={30} value={settings.suspensionYearDays} onChange={(e) => setSettings({ ...settings, suspensionYearDays: Number(e.target.value) })} /></label>
            <label>{t('Sanctions count in the rankings for (months)')}<input type="number" min={1} max={120} value={settings.rankingMonths} onChange={(e) => setSettings({ ...settings, rankingMonths: Number(e.target.value) })} /></label>
            <label>{t('Lateness follow-up from (late days in a month)')}<input type="number" min={1} max={31} value={settings.followUpDays} onChange={(e) => setSettings({ ...settings, followUpDays: Number(e.target.value) })} /></label>
          </div>
          <p className="hint">{t('The labour code sets no time after which a sanction is erased; 24 months is our proposal, to confirm with your labour law adviser.')}</p>
          <Button onClick={() => run(() => api('/api/discipline/settings', { method: 'PUT', body: settings }), t('Discipline settings saved.'))}>{t('Save')}</Button>
        </div>
      )}
      <div className="card">
        <h3>{t('Sanctions')}</h3>
        <p className="muted">{t('The sanctions of the Labour Code (art. 54), lightest first. The weight orders the sanction ranking. Fines are not a sanction: the code forbids them (art. 111). Each sanction has its letter template, which you can change under Hiring and departure settings.')}</p>
        <div className="table-wrap">
          <table>
            <thead><tr><th>{t('Name')}</th><th>{t('Weight')}</th><th>{t('Effect')}</th><th>{t('In use')}</th><th></th></tr></thead>
            <tbody>
              {types.map((x, i) => (
                <tr key={x.id}>
                  <td><input value={x.name} onChange={(e) => set(i, { name: e.target.value })} /></td>
                  <td><input className="auto-width" type="number" min={0} max={10} value={x.weight} onChange={(e) => set(i, { weight: Number(e.target.value) })} /></td>
                  <td className="small">{x.suspends ? t('Unpaid days') : x.dismissal ? t('Ends the contract') : t('Kept in the file')}</td>
                  <td><input type="checkbox" checked={x.active} onChange={(e) => set(i, { active: e.target.checked })} /></td>
                  <td><Button className="secondary" onClick={() => run(() => api(`/api/discipline/types/${x.id}`, { method: 'PUT', body: { name: x.name, weight: x.weight, active: x.active } }), t('Sanction saved.'))}>{t('Save')}</Button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </section>
  )
}
