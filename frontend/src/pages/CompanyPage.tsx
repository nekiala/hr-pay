import { useEffect, useState, type FormEvent } from 'react'
import { ApiError, api, COUNTRY_NAMES, fileToBase64, type Employer, type PayslipSettings } from '../api'
import { ErrorText } from '../components/ErrorText'
import { Button, Form } from '../components/Button'
import { useAuth } from '../auth'
import { t } from '../i18n'

const EMPLOYER_FIELDS: [keyof Employer, string][] = [
  ['name', t('Name')], ['address', t('Address')], ['rccm', 'RCCM'], ['nationalId', t('Id. Nat.')], ['taxNumber', 'NIF'],
  ['cnssNumber', t('CNSS employer number')], ['inppNumber', t('INPP number')], ['onemNumber', t('ONEM number')],
  ['bankName', t('Bank')], ['bankAccount', t('Account salaries are paid from')],
]

const LIMITS: Record<keyof Employer, number> = {
  name: 150, address: 300, cnssNumber: 40, taxNumber: 40, nationalId: 40, rccm: 60, inppNumber: 40, onemNumber: 40, bankName: 100, bankAccount: 60,
}

const TEMPLATES: { code: PayslipSettings['template']; name: string; about: string }[] = [
  { code: 'CLASSIC', name: 'Classic', about: t('One table of earnings and deductions with base and rate, the year to date and the employer charges.') },
  { code: 'COMPACT', name: 'Compact', about: t('Earnings and deductions side by side, so the whole payslip fits on half a page.') },
  { code: 'LEDGER', name: 'Ledger', about: t('A ruled table with element codes and separate employee and employer columns, as accountants like it.') },
]

/** The employer: name, address and legal numbers, the logo, and how payslips look. */

/** The company's language: the one its people see unless they choose their own, and its documents are written in. */
function LanguageCard() {
  const { me } = useAuth()
  const [value, setValue] = useState(me?.company?.language ?? 'fr')
  const [error, setError] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)
  const save = async (v: string) => {
    setError(null)
    setSaved(false)
    try {
      await api('/api/company/language', { method: 'PUT', body: { language: v } })
      setValue(v as 'fr' | 'en')
      setSaved(true)
    } catch (e) {
      setError(e instanceof Error ? e.message : t('Could not save.'))
    }
  }
  return (
    <div className="card">
      <h3>{t('Language and country')}</h3>
      <dl className="facts">
        <dt>{t('Country')}</dt><dd>{COUNTRY_NAMES[me?.company?.country ?? 'CD'] ?? me?.company?.country}</dd>
        <dt>{t('Local currency')}</dt><dd>{me?.company?.localCurrency}</dd>
      </dl>
      <label>{t('Company language')}
        <select value={value} onChange={(e) => void save(e.target.value)}>
          <option value="fr" lang="fr">Français</option>
          <option value="en" lang="en">English</option>
        </select>
      </label>
      <p className="hint">{t('Used for payslips, declarations, emails and messages, and for people who have not chosen a language.')}</p>
      {saved && <p className="success">{t('Saved.')}</p>}
      <ErrorText error={error} />
    </div>
  )
}

export default function CompanyPage() {
  const { me } = useAuth()
  const drc = (me?.company?.country ?? 'CD') === 'CD'
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
      setError(e instanceof ApiError ? [e.message, ...Object.values(e.fieldErrors)].join(' ') : t('Something went wrong.'))
      return false
    }
  }

  const saveEmployer = (e: FormEvent) => {
    e.preventDefault()
    if (employer) return run(async () => setEmployer(await api<Employer>('/api/company', { method: 'PUT', body: employer })), t('Company details saved.'))
  }
  const saveSettings = (e: FormEvent) => {
    e.preventDefault()
    if (settings) return run(async () => setSettings(await api<PayslipSettings>('/api/company/payslip-settings', { method: 'PUT', body: settings })), t('Payslip settings saved.'))
  }
  const upload = (file: File | undefined) => {
    if (!file) return
    if (file.size > 512 * 1024) { setError(t('The logo must be at most 512 KB.')); return }
    return run(async () => {
      setSettings(await api<PayslipSettings>('/api/company/logo', { method: 'PUT', body: { contentType: file.type, base64: await fileToBase64(file) } }))
      setLogoVersion((v) => v + 1)
    }, t('Logo saved.'))
  }
  const removeLogo = () => void run(async () => setSettings(await api<PayslipSettings>('/api/company/logo', { method: 'DELETE' })), t('Logo removed.'))

  if (!employer || !settings) return <section><ErrorText error={error} />{!error && <p className="muted">{t('Loading…')}</p>}</section>
  const set = (k: keyof PayslipSettings, v: string) => setSettings({ ...settings, [k]: v })

  return (
    <section>
      <h2>{t('Company')}</h2>
      <ErrorText error={error} />
      {message && <p className="success">{message}</p>}
      <LanguageCard />

      <Form className="card" onSubmit={saveEmployer}>
        <h3>{t('Name, address and legal information')}</h3>
        <p className="hint">{t('Printed on payslips, declarations and payment files.')}</p>
        <div className="grid3">
          {EMPLOYER_FIELDS.filter(([k]) => drc || (k !== 'inppNumber' && k !== 'onemNumber')).map(([k, label]) => (
            <label key={k}>{label}<input value={employer[k] ?? ''} required={k === 'name'} maxLength={LIMITS[k]}
              onChange={(e) => setEmployer({ ...employer, [k]: e.target.value })} /></label>
          ))}
        </div>
        <Button type="submit">{t('Save')}</Button>
      </Form>

      <div className="card">
        <h3>{t('Logo')}</h3>
        <p className="hint">{t('Shown at the top of every payslip. PNG or JPEG, at most 512 KB; a wide logo on a white or clear background prints best.')}</p>
        <div className="row">
          {settings.hasLogo
            ? <img className="company-logo" src={`/api/company/logo?v=${logoVersion}`} alt={t('Company logo')} />
            : <span className="muted">{t('No logo yet.')}</span>}
          <label className="file-button">
            {settings.hasLogo ? t('Replace the logo') : t('Add a logo')}
            <input type="file" accept="image/png,image/jpeg" onChange={(e) => { upload(e.target.files?.[0]); e.target.value = '' }} />
          </label>
          {settings.hasLogo && <Button className="secondary" onClick={removeLogo}>{t('Remove')}</Button>}
        </div>
      </div>

      <Form className="card" onSubmit={saveSettings}>
        <h3>{t('Payslips')}</h3>
        <div className="grid3">
          <label>{t('Legal form and capital')}<input value={settings.legalForm ?? ''} maxLength={200} placeholder={t('SARL au capital de 10 000 USD')}
            onChange={(e) => set('legalForm', e.target.value)} /></label>
          <label>{t('Branch of activity')}<input value={settings.activity ?? ''} maxLength={150} placeholder={t('Telecommunications')}
            onChange={(e) => set('activity', e.target.value)} /></label>
          <label>{t('Phone')}<input value={settings.phone ?? ''} maxLength={40} onChange={(e) => set('phone', e.target.value)} /></label>
          <label>{t('Email')}<input type="email" value={settings.email ?? ''} maxLength={150} onChange={(e) => set('email', e.target.value)} /></label>
          <label>{t('Website')}<input value={settings.website ?? ''} maxLength={150} onChange={(e) => set('website', e.target.value)} /></label>
        </div>
        <label>{t('Footer (for example who to ask about the payslip)')}<input value={settings.footer ?? ''} maxLength={300}
          onChange={(e) => set('footer', e.target.value)} /></label>

        <h4>{t('Layout')}</h4>
        <div className="templates">
          {TEMPLATES.map((tItem) => (
            <label key={tItem.code} className={`template${settings.template === tItem.code ? ' chosen' : ''}`}>
              <span className="row">
                <input type="radio" name="template" checked={settings.template === tItem.code} onChange={() => set('template', tItem.code)} />
                <strong>{tItem.name}</strong>
              </span>
              <small>{tItem.about}</small>
              <a href={`/api/company/payslip-preview.pdf?template=${tItem.code}`} target="_blank" rel="noreferrer">{t('See a sample')}</a>
            </label>
          ))}
        </div>
        <p className="hint">{t('Samples use made-up figures with your header, so they show no one\'s pay. Save first to see your latest changes.')}</p>
        <Button type="submit">{t('Save')}</Button>
      </Form>
    </section>
  )
}
