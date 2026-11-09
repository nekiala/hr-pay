import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { ApiError, api } from '../api'
import { Button, Form } from '../components/Button'
import { ErrorText } from '../components/ErrorText'
import { t } from '../i18n'

type TemplateRow = { name: string; language: string; category: string; status: string; reason: string | null; checkedAt: string | null }

export type WhatsAppSettings = {
  enabled: boolean; phoneNumberId: string; businessId: string; displayNumber: string | null; tokenSet: boolean
  appSecretSet: boolean; verifyToken: string; webhookUrl: string; templates: TemplateRow[]; updatedBy: string | null; updatedAt: string | null
}

type Input = { enabled: boolean; phoneNumberId: string; businessId: string; displayNumber: string; accessToken: string; appSecret: string }

const KINDS: { code: string; label: string; hint: string }[] = [
  { code: 'PAYSLIP', label: t('Payslip ready'), hint: t('A notice with a link; the payslip opens in HR Pay after sign-in, never in WhatsApp.') },
  { code: 'DECISIONS', label: t('Decisions on requests'), hint: t('Leave, salary advances, certificates: approved or refused.') },
  { code: 'ACTIONS', label: t('Things to do'), hint: t('Requests to approve, documents to sign, documents about to expire.') },
  { code: 'REMINDERS', label: t('Check-out reminder'), hint: t('When someone forgot to check out.') },
]

const STATUS: Record<string, string> = {
  APPROVED: t('Approved'), PENDING: t('Waiting for Meta'), REJECTED: t('Rejected'), NOT_SUBMITTED: t('Not submitted'),
  PAUSED: t('Paused by Meta'), DISABLED: t('Disabled by Meta'), IN_APPEAL: t('In appeal'),
}

const message = (e: unknown) => e instanceof ApiError ? [e.message, ...Object.values(e.fieldErrors)].join(' ') : t('Something went wrong.')

const toInput = (s: WhatsAppSettings | null, enabled = true): Input => ({
  enabled: s ? s.enabled : enabled, phoneNumberId: s?.phoneNumberId ?? '', businessId: s?.businessId ?? '',
  displayNumber: s?.displayNumber ?? '', accessToken: '', appSecret: '',
})

/** The fields of a Meta account: the same for the platform and for a company with its own number. */
function AccountFields({ form, set, settings }: { form: Input; set: (p: Partial<Input>) => void; settings: WhatsAppSettings | null }) {
  return (
    <>
      <div className="grid3">
        <label>{t('Phone number ID')}<input required value={form.phoneNumberId} inputMode="numeric" onChange={(e) => set({ phoneNumberId: e.target.value })} /></label>
        <label>{t('WhatsApp Business Account ID')}<input required value={form.businessId} inputMode="numeric" onChange={(e) => set({ businessId: e.target.value })} /></label>
        <label>{t('Number as people see it')}<input value={form.displayNumber} placeholder="+243 81 234 5678" onChange={(e) => set({ displayNumber: e.target.value })} /></label>
      </div>
      <div className="grid2">
        <label>{t('Access token (system user)')} <span className="muted small">{settings?.tokenSet ? t('(leave empty to keep the current one)') : ''}</span>
          <input type="password" autoComplete="new-password" value={form.accessToken} required={!settings?.tokenSet} onChange={(e) => set({ accessToken: e.target.value })} /></label>
        <label>{t('App secret')} <span className="muted small">{settings?.appSecretSet ? t('(leave empty to keep the current one)') : ''}</span>
          <input type="password" autoComplete="new-password" value={form.appSecret} required={!settings?.appSecretSet} onChange={(e) => set({ appSecret: e.target.value })} /></label>
      </div>
      <p className="hint">{t('All four values are in Meta Business Manager: WhatsApp Manager for the IDs, a system user with the whatsapp_business_messaging and whatsapp_business_management permissions for the token, and the Meta app settings for the app secret. The token and secret are stored encrypted and never shown again.')}</p>
    </>
  )
}

/** Where to register Meta's webhook, and the templates with Meta's verdict. */
function MetaSide({ settings, base, onChange }: { settings: WhatsAppSettings; base: string; onChange: (s: WhatsAppSettings) => void }) {
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState<string | null>(null)
  const call = async (path: string, done: string) => {
    setError(null)
    setBusy(null)
    try {
      const r = await api<WhatsAppSettings | { settings: WhatsAppSettings }>(`${base}/templates/${path}`, { method: 'POST' })
      onChange('settings' in r ? r.settings : r)
      setBusy(done)
    } catch (e) {
      setError(message(e))
    }
  }
  const waiting = settings.templates.filter((x) => x.status !== 'APPROVED').length
  return (
    <>
      <div className="card">
        <h3>{t('Webhook at Meta')}</h3>
        <p className="hint">{t('In the Meta app, under WhatsApp > Configuration, set this callback URL and verify token, then subscribe to the "messages" field. Meta then reports deliveries and passes on STOP replies.')}</p>
        <dl className="facts">
          <dt>{t('Callback URL')}</dt><dd><code>{settings.webhookUrl}</code></dd>
          <dt>{t('Verify token')}</dt><dd><code>{settings.verifyToken}</code></dd>
        </dl>
      </div>
      <div className="card">
        <div className="row between">
          <h3>{t('Message templates')}</h3>
          <div className="row">
            <Button onClick={() => call('submit', t('Templates sent to Meta.'))}>{t('Submit to Meta')}</Button>
            <Button className="secondary" onClick={() => call('refresh', t('Statuses read from Meta.'))}>{t('Check the status')}</Button>
          </div>
        </div>
        <ErrorText error={error} />
        {busy && <p className="success">{busy}</p>}
        <p className="hint">{waiting === 0 ? t('Every template is approved: messages can go out.')
          : t('{a} template(s) not approved yet. A message whose template is not approved is not sent. Meta usually answers within minutes, sometimes 24 hours.', { a: waiting })}</p>
        <div className="table-wrap">
          <table>
            <thead><tr><th>{t('Template')}</th><th>{t('Language')}</th><th>{t('Category')}</th><th>{t('Status')}</th><th>{t('Checked')}</th></tr></thead>
            <tbody>
              {settings.templates.map((x) => (
                <tr key={x.name + x.language}>
                  <td><code>{x.name}</code></td>
                  <td>{x.language}</td>
                  <td className="small">{x.category}</td>
                  <td>{STATUS[x.status] ?? x.status}{x.reason && <div className="hint">{x.reason}</div>}</td>
                  <td className="small muted">{x.checkedAt ? new Date(x.checkedAt).toLocaleString() : '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </>
  )
}

/** The provider's Meta account: sends for every company without its own number. */
export function PlatformWhatsAppPage() {
  const [settings, setSettings] = useState<WhatsAppSettings | null | undefined>(undefined)
  const [form, setForm] = useState<Input>(toInput(null))
  const [error, setError] = useState<string | null>(null)
  const [done, setDone] = useState<string | null>(null)
  const load = async () => {
    const s = (await api<{ settings?: WhatsAppSettings }>('/api/platform/whatsapp')).settings ?? null
    setSettings(s)
    setForm(toInput(s))
  }
  useEffect(() => { load().catch((e: Error) => setError(e.message)) }, [])
  const save = async () => {
    setError(null)
    setDone(null)
    try {
      const r = await api<{ settings: WhatsAppSettings }>('/api/platform/whatsapp', { method: 'PUT', body: form })
      setSettings(r.settings)
      setForm(toInput(r.settings))
      setDone(t('Saved.'))
    } catch (e) {
      setError(message(e))
    }
  }
  return (
    <section>
      <h2>{t('Platform WhatsApp')}</h2>
      <ErrorText error={error} />
      {settings !== undefined && (
        <>
          <Form className="card" onSubmit={save}>
            <p className="hint">{t('The HR Pay number: it sends one-time codes and the notifications of every company that turned WhatsApp on without a number of its own. It takes over from the WHATSAPP_* settings of the .env file.')}</p>
            <label className="check"><input type="checkbox" checked={form.enabled} onChange={(e) => setForm({ ...form, enabled: e.target.checked })} />{t('Send through this number')}</label>
            <AccountFields form={form} set={(p) => setForm({ ...form, ...p })} settings={settings} />
            {done && <p className="success">{done}</p>}
            <Button type="submit">{t('Save')}</Button>
          </Form>
          {settings && <MetaSide settings={settings} base="/api/platform/whatsapp" onChange={setSettings} />}
        </>
      )}
    </section>
  )
}

type CompanyView = {
  enabled: boolean; kinds: string[]; ownNumber: boolean; own: WhatsAppSettings | null; inUse: 'COMPANY' | 'PLATFORM' | 'ENV' | null
  inUseNumber: string | null; optedIn: number; employees: number
}

/** A company: whether its people get WhatsApp messages, which kinds, and optionally its own number. */
export function CompanyWhatsAppPage() {
  const [view, setView] = useState<CompanyView | null>(null)
  const [enabled, setEnabled] = useState(false)
  const [kinds, setKinds] = useState<string[]>([])
  const [own, setOwn] = useState(false)
  const [form, setForm] = useState<Input>(toInput(null))
  const [error, setError] = useState<string | null>(null)
  const [done, setDone] = useState<string | null>(null)
  const show = (v: CompanyView) => {
    setView(v)
    setEnabled(v.enabled)
    setKinds(v.kinds)
    setOwn(v.ownNumber)
    setForm(toInput(v.own))
  }
  useEffect(() => { api<CompanyView>('/api/company/whatsapp').then(show).catch((e: Error) => setError(e.message)) }, [])
  const save = async () => {
    setError(null)
    setDone(null)
    try {
      show(await api<CompanyView>('/api/company/whatsapp', {
        method: 'PUT', body: { enabled, kinds, ownNumber: own, own: own || form.phoneNumberId ? form : null },
      }))
      setDone(enabled ? t('WhatsApp turned on. Each employee now confirms their number under My account.') : t('Saved.'))
    } catch (e) {
      setError(message(e))
    }
  }
  const toggle = (code: string) => setKinds(kinds.includes(code) ? kinds.filter((k) => k !== code) : [...kinds, code])
  return (
    <section>
      <p className="small muted"><Link to="/company">{t('Company')}</Link></p>
      <h2>{t('WhatsApp')}</h2>
      <ErrorText error={error} />
      {view && (
        <>
          <div className="card">
            <dl className="facts">
              <dt>{t('Messages leave from')}</dt>
              <dd>{view.inUse === 'COMPANY' ? t('Your own number') : view.inUse === 'PLATFORM' ? t('The HR Pay number') : view.inUse === 'ENV' ? t("The installation's number (.env file)") : t('No number is set up yet')}
                {view.inUseNumber && <> · {view.inUseNumber}</>}</dd>
              <dt>{t('Employees who agreed')}</dt><dd>{view.optedIn} / {view.employees}</dd>
            </dl>
          </div>
          <Form className="card" onSubmit={save}>
            <label className="check"><input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} />{t('Send notifications to employees on WhatsApp')}</label>
            <p className="hint">{t('Only employees who confirm their number with a code and agree receive messages; they can stop at any time, in HR Pay or by replying STOP. Pay figures never travel by WhatsApp. The bell in HR Pay and emails keep working as before.')}</p>
            <h3>{t('What is sent')}</h3>
            {KINDS.map((k) => (
              <label key={k.code} className="check"><input type="checkbox" checked={kinds.includes(k.code)} onChange={() => toggle(k.code)} />
                <span>{k.label} <span className="hint">{k.hint}</span></span></label>
            ))}
            <h3>{t('Number')}</h3>
            <label className="check"><input type="checkbox" checked={own} onChange={(e) => setOwn(e.target.checked)} />{t('Use our own WhatsApp Business number')}</label>
            {own && <AccountFields form={form} set={(p) => setForm({ ...form, ...p })} settings={view.own} />}
            {done && <p className="success">{done}</p>}
            <Button type="submit">{t('Save')}</Button>
          </Form>
          {own && view.own && <MetaSide settings={view.own} base="/api/company/whatsapp" onChange={(s) => setView({ ...view, own: s })} />}
        </>
      )}
    </section>
  )
}

type Mine = {
  offered: boolean; phone: string; optedIn: boolean; since: string | null; number: string | null
  history: { action: 'IN' | 'OUT'; phone: string; source: string; createdAt: string }[]
}

const SOURCE: Record<string, string> = { APP: t('in HR Pay'), REPLY: t('by replying STOP'), HR: t('by HR'), PHONE: t('number changed') }

/** The employee's choice, on their account page. */
export function MyWhatsApp() {
  const [mine, setMine] = useState<Mine | null>(null)
  const [challenge, setChallenge] = useState<string | null>(null)
  const [code, setCode] = useState('')
  const [error, setError] = useState<string | null>(null)
  useEffect(() => { api<Mine>('/api/my/whatsapp').then(setMine).catch(() => undefined) }, [])
  if (!mine || (!mine.offered && !mine.optedIn && mine.history.length === 0)) return null
  const run = async (action: () => Promise<void>) => {
    setError(null)
    try { await action() } catch (e) { setError(message(e)) }
  }
  return (
    <div className="card">
      <h3>{t('WhatsApp')}</h3>
      {mine.optedIn ? (
        <>
          <p>{t('You receive HR Pay messages on WhatsApp at {a}, since {b}.', { a: mine.phone, b: new Date(mine.since!).toLocaleDateString() })}
            {mine.number && <> {t('They come from {a}.', { a: mine.number })}</>}</p>
          <Button className="secondary" onClick={() => run(async () => setMine(await api<Mine>('/api/my/whatsapp/stop', { method: 'POST' })))}>{t('Stop WhatsApp messages')}</Button>
        </>
      ) : !mine.offered ? <p className="muted">{t('Your company does not send messages on WhatsApp at the moment.')}</p> : challenge ? (
        <Form onSubmit={() => run(async () => {
          setMine(await api<Mine>('/api/my/whatsapp/confirm', { method: 'POST', body: { challengeId: challenge, code } }))
          setChallenge(null)
          setCode('')
        })}>
          <p>{t('Type the code you just received on WhatsApp at {a}.', { a: mine.phone })}</p>
          <div className="row">
            <input inputMode="numeric" autoComplete="one-time-code" maxLength={6} required className="narrow-input" value={code} onChange={(e) => setCode(e.target.value)} />
            <Button type="submit" disabled={code.length < 6}>{t('Confirm and agree')}</Button>
          </div>
        </Form>
      ) : (
        <>
          <p>{t('Get your payslip notices, decisions on your requests and reminders on WhatsApp at {a}. Pay figures never travel by WhatsApp: links open HR Pay. You can stop at any time, here or by replying STOP.', { a: mine.phone })}</p>
          <Button onClick={() => run(async () => setChallenge((await api<{ challengeId: string }>('/api/my/whatsapp/code', { method: 'POST' })).challengeId))}>
            {t('Send me a code on WhatsApp')}</Button>
          <p className="hint">{t('Wrong number? Ask HR to correct it in your file first.')}</p>
        </>
      )}
      <ErrorText error={error} />
      {mine.history.length > 0 && (
        <details>
          <summary className="small">{t('History')}</summary>
          <ul className="small">{mine.history.map((h) => (
            <li key={h.createdAt + h.action}>{new Date(h.createdAt).toLocaleString()}: {h.action === 'IN' ? t('agreed') : t('stopped')} ({SOURCE[h.source] ?? h.source}) · {h.phone}</li>
          ))}</ul>
        </details>
      )}
    </div>
  )
}
