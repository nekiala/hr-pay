import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { ApiError, api } from '../api'
import { useAuth } from '../auth'
import { Button, Form } from '../components/Button'
import { ErrorText } from '../components/ErrorText'
import { t } from '../i18n'

type Settings = { netChangePercent: number; rateMaxDays: number }

/** The company's thresholds for the checks made before a payroll is approved. */
export default function PayrollChecksSettingsPage() {
  const { can } = useAuth()
  const editable = can('payroll.run')
  const [form, setForm] = useState<Settings | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)

  useEffect(() => { api<Settings>('/api/payroll/check-settings').then(setForm).catch((e: Error) => setError(e.message)) }, [])

  const save = async () => {
    setError(null)
    setMessage(null)
    try {
      setForm(await api<Settings>('/api/payroll/check-settings', { method: 'PUT', body: form }))
      setMessage(t('Thresholds saved. They apply the next time the checks are shown.'))
    } catch (e) {
      setError(e instanceof ApiError ? [e.message, ...Object.values(e.fieldErrors)].join(' ') : t('Something went wrong.'))
    }
  }

  return (
    <section>
      <p className="small muted"><Link to="/payroll">{t('Pay runs')}</Link></p>
      <h2>{t('Payroll checks')}</h2>
      <ErrorText error={error} />
      {message && <p className="success">{message}</p>}
      {form && (
        <div className="card">
          <Form onSubmit={save}>
            <div className="row grant">
              <label>{t('Flag a change in net pay above (%)')}<input type="number" min={1} max={500} step={0.5} className="narrow-input"
                value={form.netChangePercent} disabled={!editable} onChange={(e) => setForm({ ...form, netChangePercent: Number(e.target.value) })} /></label>
              <label>{t('Flag an exchange rate older than (days)')}<input type="number" min={1} max={90} className="narrow-input"
                value={form.rateMaxDays} disabled={!editable} onChange={(e) => setForm({ ...form, rateMaxDays: Number(e.target.value) })} /></label>
              {editable && <Button type="submit">{t('Save')}</Button>}
            </div>
          </Form>
          <p className="hint">{t('Net pay is compared with the previous month, in the same currency. The overtime limit is the weekly maximum of the overtime policy.')}</p>
        </div>
      )}
    </section>
  )
}
