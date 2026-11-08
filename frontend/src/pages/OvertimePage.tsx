import { useEffect, useState } from 'react'
import { api, type OvertimePolicy, type OvertimeTier } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'
import { Button } from '../components/Button'
import { t } from '../i18n'

const kindLabel: Record<OvertimeTier['kind'], string> = { WEEKDAY: t('Workday overtime'), REST_DAY: t('Rest day'), HOLIDAY: t('Public holiday') }

export default function OvertimePage() {
  const { can } = useAuth()
  const editable = can('overtime_policy.manage')
  const [policies, setPolicies] = useState<OvertimePolicy[]>([])
  const [draft, setDraft] = useState<OvertimePolicy | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)

  const load = async () => {
    const list = await api<OvertimePolicy[]>('/api/overtime-policies')
    setPolicies(list)
    if (list[0]) setDraft(list[0])
  }
  useEffect(() => { load().catch((e: Error) => setError(e.message)) }, [])

  const setTier = (i: number, patch: Partial<OvertimeTier>) =>
    draft && setDraft({ ...draft, tiers: draft.tiers.map((tItem, j) => (i === j ? { ...tItem, ...patch } : tItem)) })

  const save = async () => {
    if (!draft) return
    setError(null)
    try {
      await api('/api/overtime-policies', { method: 'PUT', body: draft })
      await load()
      setMessage(t('Saved.'))
    } catch (e) {
      setError(e instanceof Error ? e.message : t('Could not save.'))
    }
  }

  if (!draft) return <ErrorText error={error} />

  return (
    <section>
      <h2>{t('Overtime rules (DR Congo)')}</h2>
      <p className="muted">{t('Overtime is time worked beyond the schedule. Rates apply to the hourly value of the basic salary.')}</p>
      <div className="card">
        <fieldset disabled={!editable}>
          <div className="grid2">
            <label>{t('Effective from')}<input type="date" value={draft.effectiveFrom} onChange={(e) => setDraft({ ...draft, effectiveFrom: e.target.value })} /></label>
            <label>{t('Round overtime to (min)')}<input type="number" min={1} max={60} value={draft.roundingMinutes} onChange={(e) => setDraft({ ...draft, roundingMinutes: Number(e.target.value) })} /></label>
          </div>
          <label className="check"><input type="checkbox" checked={draft.allowed} onChange={(e) => setDraft({ ...draft, allowed: e.target.checked })} />{t('Overtime is authorized')}</label>
          <label className="check"><input type="checkbox" checked={draft.requiresApproval} onChange={(e) => setDraft({ ...draft, requiresApproval: e.target.checked })} />{t('Overtime is paid only after approval')}</label>
          <table>
            <thead><tr><th>{t('Kind')}</th><th>{t('From hour (in the week)')}</th><th>{t('To hour')}</th><th>{t('Multiplier')}</th></tr></thead>
            <tbody>
              {draft.tiers.map((tItem, i) => (
                <tr key={i}>
                  <td>{kindLabel[tItem.kind]}</td>
                  <td><input type="number" min={0} value={tItem.fromHour} onChange={(e) => setTier(i, { fromHour: Number(e.target.value) })} /></td>
                  <td><input type="number" min={0} value={tItem.toHour ?? ''} placeholder={t('no limit')} onChange={(e) => setTier(i, { toHour: e.target.value === '' ? null : Number(e.target.value) })} /></td>
                  <td><input type="number" step="0.05" min={1} value={tItem.multiplier} onChange={(e) => setTier(i, { multiplier: Number(e.target.value) })} /></td>
                </tr>
              ))}
            </tbody>
          </table>
        </fieldset>
        <p className="hint">{t('Saving with a new effective date keeps the earlier rules for earlier periods. Versions:')}{' '}{policies.map((p) => p.effectiveFrom).join(', ')}</p>
        <ErrorText error={error} />
        {message && <p className="success">{message}</p>}
        {editable && <Button onClick={() => save()}>{t('Save rules')}</Button>}
      </div>
    </section>
  )
}
