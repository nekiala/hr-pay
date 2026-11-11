import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { ApiError, api } from '../api'
import { Button, Form } from '../components/Button'
import { ErrorText } from '../components/ErrorText'
import { t } from '../i18n'
import type { FilingSettings } from './LegalFilingsPage'
import { DateInput } from '../components/DateFields'

/** From when hirings and departures are declared here, and whether the company keeps its personnel register here. */
export default function LegalFilingSettingsPage() {
  const [settings, setSettings] = useState<FilingSettings | null>(null)
  const [form, setForm] = useState({ filingsFrom: '', register: 'DEFAULT' })
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)

  const show = (s: FilingSettings) => {
    setSettings(s)
    setForm({ filingsFrom: s.filingsFrom, register: s.personnelRegister === s.registerByDefault ? 'DEFAULT' : s.personnelRegister ? 'ON' : 'OFF' })
  }
  useEffect(() => { api<{ settings: FilingSettings }>('/api/legal-filings').then((p) => show(p.settings)).catch((e: Error) => setError(e.message)) }, [])

  const save = async () => {
    setError(null)
    setMessage(null)
    try {
      const s = await api<FilingSettings>('/api/legal-filings/settings', { method: 'PUT', body: {
        filingsFrom: form.filingsFrom, personnelRegister: form.register === 'DEFAULT' ? null : form.register === 'ON',
      } })
      show(s)
      setMessage(t('Settings saved.'))
    } catch (e) {
      setError(e instanceof ApiError ? [e.message, ...Object.values(e.fieldErrors)].join(' ') : t('Something went wrong.'))
    }
  }

  return (
    <section>
      <p className="small muted"><Link to="/payroll/legal-filings">{t('Legal registers and annual filings')}</Link></p>
      <h2>{t('Legal filings settings')}</h2>
      <ErrorText error={error} />
      {message && <p className="success">{message}</p>}
      {settings && (
        <div className="card">
          <Form onSubmit={save}>
            {settings.movements && (
              <label>{t('Declare hirings and departures from')}
                <DateInput required value={form.filingsFrom} onChange={(e) => setForm({ ...form, filingsFrom: e.target.value })} />
                <span className="hint">{t('Hirings and departures before this date are not listed (they were declared outside HR Pay).')}</span>
              </label>
            )}
            <label>{t('Personnel register (registre d\'employeur)')}
              <select value={form.register} onChange={(e) => setForm({ ...form, register: e.target.value })}>
                <option value="DEFAULT">{settings.registerByDefault ? t('Kept here (the rule in this country)') : t('Not kept here (the rule in this country)')}</option>
                <option value="ON">{t('Kept here')}</option>
                <option value="OFF">{t('Not kept here')}</option>
              </select>
              <span className="hint">{t('Required in the Republic of the Congo (Code du travail art. 182). Optional elsewhere.')}</span>
            </label>
            <Button type="submit">{t('Save')}</Button>
          </Form>
        </div>
      )}
    </section>
  )
}
