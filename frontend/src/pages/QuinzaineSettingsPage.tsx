import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { ApiError, api, type QuinzaineCategory, type QuinzaineSettings } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'
import { Button } from '../components/Button'
import { t } from '../i18n'

/** The quinzaine's settings: whether the company pays it, on which day and base, and the rate per grade category. */
export default function QuinzaineSettingsPage() {
  const { can } = useAuth()
  const editable = can('masterdata.manage')
  const [settings, setSettings] = useState<QuinzaineSettings | null>(null)
  const [form, setForm] = useState<QuinzaineSettings>({ enabled: false, base: 'NET', day: 15, maxDebtMonths: 3 })
  const [categories, setCategories] = useState<QuinzaineCategory[]>([])
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)

  const load = async () => {
    const o = await api<{ settings: QuinzaineSettings; categories: QuinzaineCategory[] }>('/api/quinzaine')
    setSettings(o.settings)
    setForm({ ...o.settings, maxDebtMonths: Number(o.settings.maxDebtMonths) })
    setCategories(o.categories)
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

  const save = () => run(async () => {
    const put = (key: string, value: string) => api(`/api/statutory/settings/${key}`, { method: 'PUT', body: { value } })
    await put('QUINZAINE_BASE', form.base)
    await put('QUINZAINE_DAY', String(form.day))
    await put('QUINZAINE_MAX_DEBT_MONTHS', String(form.maxDebtMonths))
    await put('QUINZAINE_ENABLED', String(form.enabled))
  }, form.enabled ? t('Quinzaine turned on.') : t('Quinzaine settings saved.'))

  const setRate = (c: QuinzaineCategory, value: string) => setCategories(categories.map((x) => (x.id === c.id
    ? { ...x, quinzainePercent: value === '' ? null : Number(value) } : x)))

  return (
    <section>
      <p className="small muted"><Link to="/payroll/quinzaine">{t('Quinzaine')}</Link></p>
      <h2>{t('Quinzaine settings')}</h2>
      <ErrorText error={error} />
      {message && <p className="success">{message}</p>}

      {settings && (
        <div className="card">
          <h3>{t('Company setting')}</h3>
          <label className="check"><input type="checkbox" checked={form.enabled} disabled={!editable}
            onChange={(e) => setForm({ ...form, enabled: e.target.checked })} />{t('The company pays a quinzaine')}</label>
          <div className="row grant">
            <label>{t('Rate applies to')}<select className="auto-width" value={form.base} disabled={!editable}
              onChange={(e) => setForm({ ...form, base: e.target.value as QuinzaineSettings['base'] })}>
              <option value="NET">{t('the net pay of a normal month')}</option>
              <option value="BASIC">{t('the basic salary')}</option>
            </select></label>
            <label>{t('Paid on day')}<input type="number" min={1} max={28} className="narrow-input" value={form.day}
              disabled={!editable} onChange={(e) => setForm({ ...form, day: Number(e.target.value) })} /></label>
            <label>{t('No quinzaine above a debt of (months of net pay)')}<input type="number" min={0} max={99} step={0.5} className="narrow-input"
              value={form.maxDebtMonths} disabled={!editable} onChange={(e) => setForm({ ...form, maxDebtMonths: Number(e.target.value) })} /></label>
            {editable && <Button className="secondary" onClick={save}>{t('Save')}</Button>}
          </div>
          <p className="hint">{t('On a weekend or public holiday, it is paid the working day before. DRC: Labour Code art. 99 and 112; Republic of the Congo: art. 88 and 89.')}</p>

          <h3>{t('Rate by grade category')}</h3>
          <div className="table-wrap">
            <table>
              <thead><tr><th>{t('Category')}</th><th>{t('Rate (%)')}</th><th></th></tr></thead>
              <tbody>
                {categories.map((c) => (
                  <tr key={c.id}>
                    <td>{c.name} <span className="muted">{c.code}</span></td>
                    <td><input type="number" min={10} max={50} step={0.5} className="narrow-input" placeholder={t('none')} disabled={!editable}
                      value={c.quinzainePercent ?? ''} onChange={(e) => setRate(c, e.target.value)} /></td>
                    <td>{editable && <Button className="secondary" onClick={() => run(() => api(`/api/quinzaine/categories/${c.id}`,
                      { method: 'PUT', body: { percent: c.quinzainePercent } }), t('{a} saved.', { a: c.code }))}>{t('Save')}</Button>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <p className="hint">{t('From 10% to 50%. Leave it empty for a category that does not get the quinzaine.')}</p>
        </div>
      )}
    </section>
  )
}
