import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { ApiError, api, type MatriculeFormat } from '../api'
import { Button, Form } from '../components/Button'
import { ErrorText } from '../components/ErrorText'
import { t } from '../i18n'

type FormState = { enabled: boolean; pattern: string; resetYearly: boolean; nextSeq: string }

const EXAMPLES = ['{SEQ:5}', 'EMP-{SEQ:4}', 'NTP-{YY}-{SEQ:4}', '{YYYY}{SEQ:4}', '{SITE}-{SEQ:4}']

/** How HR Pay numbers new employees (matricule). Administrators only. */
export default function MatriculeSettingsPage() {
  const [saved, setSaved] = useState<MatriculeFormat | null>(null)
  const [form, setForm] = useState<FormState | null>(null)
  const [preview, setPreview] = useState<string | null>(null)
  const [previewError, setPreviewError] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)

  const fill = (f: MatriculeFormat) => {
    setSaved(f)
    setForm({ enabled: f.enabled, pattern: f.pattern, resetYearly: f.resetYearly, nextSeq: String(f.nextSeq) })
  }

  useEffect(() => { api<MatriculeFormat>('/api/matricule-format').then(fill).catch((e: Error) => setError(e.message)) }, [])

  // The live preview of the next employee ID.
  useEffect(() => {
    if (!form) return
    const handle = setTimeout(() => {
      api<{ value: string }>('/api/matricule-format/preview', {
        method: 'POST', body: { pattern: form.pattern, nextSeq: Number(form.nextSeq) || 1, site: null },
      }).then((r) => { setPreview(r.value); setPreviewError(null) })
        .catch((e: Error) => { setPreview(null); setPreviewError(e.message) })
    }, 250)
    return () => clearTimeout(handle)
  }, [form?.pattern, form?.nextSeq]) // eslint-disable-line react-hooks/exhaustive-deps

  const save = async () => {
    if (!form) return
    setError(null)
    setMessage(null)
    try {
      fill(await api<MatriculeFormat>('/api/matricule-format', {
        method: 'PUT', body: { enabled: form.enabled, pattern: form.pattern, resetYearly: form.resetYearly, nextSeq: Number(form.nextSeq) },
      }))
      setMessage(t('Saved. New employees get the next number from now on.'))
    } catch (e) {
      setError(e instanceof ApiError ? [e.message, ...Object.values(e.fieldErrors)].join(' ') : t('Something went wrong.'))
    }
  }

  return (
    <section>
      <p className="small muted"><Link to="/users">{t('Users')}</Link></p>
      <h2>{t('Employee ID (matricule)')}</h2>
      <ErrorText error={error} />
      {message && <p className="success">{message}</p>}
      {form && (
        <div className="card">
          <Form onSubmit={save}>
            <label className="check">
              <input type="checkbox" checked={form.enabled} onChange={(e) => setForm({ ...form, enabled: e.target.checked })} />
              {t('HR Pay gives the employee ID of new employees')}
            </label>
            <p className="hint">{t('When on, the employee ID is not typed when a person is created; an import row with no employee ID gets one too. Existing employee IDs never change.')}</p>
            <div className="grid3">
              <label>{t('Format')}<input value={form.pattern} maxLength={60} required onChange={(e) => setForm({ ...form, pattern: e.target.value })} />
                <span className="hint">{t('{YYYY} or {YY}: the year · {SITE}: the site code · {SEQ:5}: the counter on 5 digits (required)')}</span>
              </label>
              <label>{t('Next number')}<input type="number" min={1} step={1} value={form.nextSeq} required onChange={(e) => setForm({ ...form, nextSeq: e.target.value })} />
                <span className="hint">{t('Set it above your existing numbers. Numbers already used are skipped.')}</span>
              </label>
              <label>{t('Counter')}<select value={form.resetYearly ? 'YEAR' : 'NEVER'} onChange={(e) => setForm({ ...form, resetYearly: e.target.value === 'YEAR' })}>
                  <option value="NEVER">{t('Never starts again')}</option>
                  <option value="YEAR">{t('Starts again at 1 each year')}</option>
                </select>
              </label>
            </div>
            <p>{t('Next employee ID')}: {preview ? <strong className="mono">{preview}</strong> : <span className="error">{previewError}</span>}</p>
            <p className="hint">{t('Examples')}:{' '}{EXAMPLES.map((x, i) => (
              <span key={x}>{i > 0 && ' · '}<button type="button" className="link" onClick={() => setForm({ ...form, pattern: x })}>{x}</button></span>
            ))}</p>
            {saved?.updatedBy && <p className="small muted">{t('Last changed by {a}', { a: saved.updatedBy })}</p>}
            <Button type="submit">{t('Save')}</Button>
          </Form>
        </div>
      )}
    </section>
  )
}
