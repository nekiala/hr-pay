import { useEffect, useState, type FormEvent } from 'react'
import { ApiError, api, fileToBase64, type Employer, type PayslipSettings } from '../api'
import { ErrorText } from '../components/ErrorText'
import { Button, Form } from '../components/Button'

const EMPLOYER_FIELDS: [keyof Employer, string][] = [
  ['name', 'Name'], ['address', 'Address'], ['rccm', 'RCCM'], ['nationalId', 'Id. Nat.'], ['taxNumber', 'NIF'],
  ['cnssNumber', 'CNSS employer number'], ['inppNumber', 'INPP number'], ['onemNumber', 'ONEM number'],
  ['bankName', 'Bank'], ['bankAccount', 'Account salaries are paid from'],
]

const LIMITS: Record<keyof Employer, number> = {
  name: 150, address: 300, cnssNumber: 40, taxNumber: 40, nationalId: 40, rccm: 60, inppNumber: 40, onemNumber: 40, bankName: 100, bankAccount: 60,
}

const TEMPLATES: { code: PayslipSettings['template']; name: string; about: string }[] = [
  { code: 'CLASSIC', name: 'Classic', about: 'One table of earnings and deductions with base and rate, the year to date and the employer charges.' },
  { code: 'COMPACT', name: 'Compact', about: 'Earnings and deductions side by side, so the whole payslip fits on half a page.' },
  { code: 'LEDGER', name: 'Ledger', about: 'A ruled table with element codes and separate employee and employer columns, as accountants like it.' },
]

/** The employer: name, address and legal numbers, the logo, and how payslips look. */
export default function CompanyPage() {
  const [employer, setEmployer] = useState<Employer | null>(null)
  const [settings, setSettings] = useState<PayslipSettings | null>(null)
  const [logoVersion, setLogoVersion] = useState(0)
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)

  useEffect(() => {
    api<Employer>('/api/company').then(setEmployer).catch((e: Error) => setError(e.message))
    api<PayslipSettings>('/api/company/payslip-settings').then(setSettings).catch((e: Error) => setError(e.message))
  }, [])

  const run = async (action: () => Promise<unknown>, done: string) => {
    setError(null)
    setMessage(null)
    try {
      await action()
      setMessage(done)
      return true
    } catch (e) {
      setError(e instanceof ApiError ? [e.message, ...Object.values(e.fieldErrors)].join(' ') : 'Something went wrong.')
      return false
    }
  }

  const saveEmployer = (e: FormEvent) => {
    e.preventDefault()
    if (employer) return run(async () => setEmployer(await api<Employer>('/api/company', { method: 'PUT', body: employer })), 'Company details saved.')
  }
  const saveSettings = (e: FormEvent) => {
    e.preventDefault()
    if (settings) return run(async () => setSettings(await api<PayslipSettings>('/api/company/payslip-settings', { method: 'PUT', body: settings })), 'Payslip settings saved.')
  }
  const upload = (file: File | undefined) => {
    if (!file) return
    if (file.size > 512 * 1024) { setError('The logo must be at most 512 KB.'); return }
    return run(async () => {
      setSettings(await api<PayslipSettings>('/api/company/logo', { method: 'PUT', body: { contentType: file.type, base64: await fileToBase64(file) } }))
      setLogoVersion((v) => v + 1)
    }, 'Logo saved.')
  }
  const removeLogo = () => void run(async () => setSettings(await api<PayslipSettings>('/api/company/logo', { method: 'DELETE' })), 'Logo removed.')

  if (!employer || !settings) return <section><ErrorText error={error} />{!error && <p className="muted">Loading…</p>}</section>
  const set = (k: keyof PayslipSettings, v: string) => setSettings({ ...settings, [k]: v })

  return (
    <section>
      <h2>Company</h2>
      <ErrorText error={error} />
      {message && <p className="success">{message}</p>}

      <Form className="card" onSubmit={saveEmployer}>
        <h3>Name, address and legal information</h3>
        <p className="hint">Printed on payslips, declarations and payment files.</p>
        <div className="grid3">
          {EMPLOYER_FIELDS.map(([k, label]) => (
            <label key={k}>{label}<input value={employer[k] ?? ''} required={k === 'name'} maxLength={LIMITS[k]}
              onChange={(e) => setEmployer({ ...employer, [k]: e.target.value })} /></label>
          ))}
        </div>
        <Button type="submit">Save</Button>
      </Form>

      <div className="card">
        <h3>Logo</h3>
        <p className="hint">Shown at the top of every payslip. PNG or JPEG, at most 512 KB; a wide logo on a white or clear background prints best.</p>
        <div className="row">
          {settings.hasLogo
            ? <img className="company-logo" src={`/api/company/logo?v=${logoVersion}`} alt="Company logo" />
            : <span className="muted">No logo yet.</span>}
          <label className="file-button">
            {settings.hasLogo ? 'Replace the logo' : 'Add a logo'}
            <input type="file" accept="image/png,image/jpeg" onChange={(e) => { upload(e.target.files?.[0]); e.target.value = '' }} />
          </label>
          {settings.hasLogo && <Button className="secondary" onClick={removeLogo}>Remove</Button>}
        </div>
      </div>

      <Form className="card" onSubmit={saveSettings}>
        <h3>Payslips</h3>
        <div className="grid3">
          <label>Legal form and capital<input value={settings.legalForm ?? ''} maxLength={200} placeholder="SARL au capital de 10 000 USD"
            onChange={(e) => set('legalForm', e.target.value)} /></label>
          <label>Phone<input value={settings.phone ?? ''} maxLength={40} onChange={(e) => set('phone', e.target.value)} /></label>
          <label>Email<input type="email" value={settings.email ?? ''} maxLength={150} onChange={(e) => set('email', e.target.value)} /></label>
          <label>Website<input value={settings.website ?? ''} maxLength={150} onChange={(e) => set('website', e.target.value)} /></label>
        </div>
        <label>Footer (for example who to ask about the payslip)<input value={settings.footer ?? ''} maxLength={300}
          onChange={(e) => set('footer', e.target.value)} /></label>

        <h4>Layout</h4>
        <div className="templates">
          {TEMPLATES.map((t) => (
            <label key={t.code} className={`template${settings.template === t.code ? ' chosen' : ''}`}>
              <span className="row">
                <input type="radio" name="template" checked={settings.template === t.code} onChange={() => set('template', t.code)} />
                <strong>{t.name}</strong>
              </span>
              <small>{t.about}</small>
              <a href={`/api/company/payslip-preview.pdf?template=${t.code}`} target="_blank" rel="noreferrer">See a sample</a>
            </label>
          ))}
        </div>
        <p className="hint">Samples use made-up figures with your header, so they show no one's pay. Save first to see your latest changes.</p>
        <Button type="submit">Save</Button>
      </Form>
    </section>
  )
}
