import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { ApiError, api } from '../api'
import { Button, Form } from '../components/Button'
import { useAsk } from '../components/Dialogs'
import { ErrorText } from '../components/ErrorText'
import { t } from '../i18n'

type Protocol = 'SMTP' | 'SMTPS'
type Encryption = 'NONE' | 'STARTTLS' | 'SSL_TLS'
export type MailLevel = 'COMPANY' | 'PLATFORM' | 'ENV' | 'NONE'

type MailSettings = {
  enabled: boolean; protocol: Protocol; host: string; port: number; encryption: Encryption; fromAddress: string
  fromName: string; bcc: string | null; username: string | null; passwordSet: boolean; ehloDomain: string | null
  updatedBy: string; updatedAt: string
}

type MailInput = {
  enabled: boolean; protocol: Protocol; host: string; port: number | ''; encryption: Encryption; fromAddress: string
  fromName: string; bcc: string; username: string; password: string; ehloDomain: string
}

const EMPTY: MailInput = {
  enabled: true, protocol: 'SMTP', host: '', port: 587, encryption: 'STARTTLS', fromAddress: '', fromName: '', bcc: '',
  username: '', password: '', ehloDomain: '',
}

/** The port people expect for a protocol and encryption. */
const usualPort = (protocol: Protocol, encryption: Encryption) =>
  protocol === 'SMTPS' || encryption === 'SSL_TLS' ? 465 : encryption === 'NONE' ? 25 : 587

export const levelText = (level: MailLevel) => ({
  COMPANY: t('Your own email server'),
  PLATFORM: t("HR Pay's email server"),
  ENV: t("The installation's email server (.env file)"),
  NONE: t('None: emails are only written on the Messages page'),
}[level])

const toInput = (s: MailSettings | null): MailInput => s ? {
  enabled: s.enabled, protocol: s.protocol, host: s.host, port: s.port, encryption: s.encryption, fromAddress: s.fromAddress,
  fromName: s.fromName, bcc: s.bcc ?? '', username: s.username ?? '', password: '', ehloDomain: s.ehloDomain ?? '',
} : EMPTY

const message = (e: unknown) => e instanceof ApiError ? [e.message, ...Object.values(e.fieldErrors)].join(' ') : t('Something went wrong.')

/** The form shared by the company's and the platform's email server. */
function MailServerForm({ base, settings, onSaved, intro }: {
  base: string; settings: MailSettings | null; onSaved: () => Promise<void>; intro: string
}) {
  const ask = useAsk()
  const [form, setForm] = useState<MailInput>(toInput(settings))
  const [error, setError] = useState<string | null>(null)
  const [result, setResult] = useState<string | null>(null)
  useEffect(() => setForm(toInput(settings)), [settings])

  const set = (patch: Partial<MailInput>) => setForm({ ...form, ...patch })
  const setTransport = (protocol: Protocol, encryption: Encryption) =>
    set({ protocol, encryption, port: form.port === usualPort(form.protocol, form.encryption) || form.port === '' ? usualPort(protocol, encryption) : form.port })
  const body = () => ({ ...form, port: form.port === '' ? null : form.port })

  const run = async (action: () => Promise<string | null>) => {
    setError(null)
    setResult(null)
    try {
      setResult(await action())
    } catch (e) {
      setError(message(e))
    }
  }

  const doSave = () => run(async () => {
    await api(base, { method: 'PUT', body: body() })
    await onSaved()
    return form.enabled ? t('Email server saved and turned on.') : t('Email server saved, turned off.')
  })
  const save = () => {
    if (form.enabled && form.encryption === 'NONE') {
      ask({
        title: t('Send without encryption?'), danger: true, confirmLabel: t('Save without encryption'),
        message: t('The password and every email, including sign-in codes, would cross the network in clear text. Only for a server inside the company network.'),
        onConfirm: () => doSave(),
      })
      return
    }
    return doSave()
  }
  const test = () => run(async () => (await api<{ message: string }>(`${base}/test`, { method: 'POST', body: body() })).message)
  const testEmail = () => run(async () => (await api<{ message: string }>(`${base}/test-email`, { method: 'POST', body: body() })).message)

  return (
    <Form className="card" onSubmit={save}>
      <p className="hint">{intro}</p>
      <label className="check"><input type="checkbox" checked={form.enabled} onChange={(e) => set({ enabled: e.target.checked })} />
        {t('Send emails through this server')}</label>
      <div className="grid4">
        <label>{t('Protocol')}
          <select value={form.protocol} onChange={(e) => setTransport(e.target.value as Protocol, e.target.value === 'SMTPS' ? 'SSL_TLS' : form.encryption)}>
            <option value="SMTP">SMTP</option>
            <option value="SMTPS">SMTPS</option>
          </select></label>
        <label>{t('Host')}<input required value={form.host} maxLength={255} placeholder="smtp.company.cd" onChange={(e) => set({ host: e.target.value })} /></label>
        <label>{t('Port')}<input type="number" min={1} max={65535} value={form.port}
          onChange={(e) => set({ port: e.target.value === '' ? '' : Number(e.target.value) })} /></label>
        <label>{t('Encryption')}
          <select value={form.encryption} onChange={(e) => setTransport(form.protocol, e.target.value as Encryption)}>
            <option value="STARTTLS">TLS (STARTTLS)</option>
            <option value="SSL_TLS">SSL/TLS</option>
            <option value="NONE">{t('None')}</option>
          </select></label>
      </div>
      <div className="grid2">
        <label>{t('Sender address')}<input type="email" required value={form.fromAddress} maxLength={320} placeholder="no-reply@company.cd"
          onChange={(e) => set({ fromAddress: e.target.value })} /></label>
        <label>{t('Sender name')}<input required value={form.fromName} maxLength={120} onChange={(e) => set({ fromName: e.target.value })} /></label>
        <label>{t('Hidden copy (BCC)')} <span className="muted small">{t('(optional)')}</span>
          <input type="email" value={form.bcc} maxLength={320} onChange={(e) => set({ bcc: e.target.value })} />
          <span className="hint">{t('Gets a copy of every email, except sign-in codes and password links.')}</span></label>
        <label>{t('EHLO domain')} <span className="muted small">{t('(optional)')}</span>
          <input value={form.ehloDomain} maxLength={255} placeholder="mail.company.cd" onChange={(e) => set({ ehloDomain: e.target.value })} />
          <span className="hint">{t('The name HR Pay gives the server when it connects. Some relays, such as smtp-relay.gmail.com, ask for it.')}</span></label>
        <label>{t('Username')} <span className="muted small">{t('(optional)')}</span>
          <input value={form.username} maxLength={320} autoComplete="off" onChange={(e) => set({ username: e.target.value })} /></label>
        <label>{t('Password')} <span className="muted small">{settings?.passwordSet ? t('(leave empty to keep the current one)') : t('(optional)')}</span>
          <input type="password" value={form.password} autoComplete="new-password" disabled={!form.username.trim()}
            onChange={(e) => set({ password: e.target.value })} /></label>
      </div>
      <p className="hint">{t('Without a username, HR Pay sends without signing in (a relay that trusts the server). The password is stored encrypted and never shown again.')}</p>
      <ErrorText error={error} />
      {result && <p className="success">{result}</p>}
      <div className="row">
        <Button type="submit">{t('Save')}</Button>
        <Button className="secondary" onClick={test}>{t('Test the connection')}</Button>
        <Button className="secondary" onClick={testEmail}>{t('Send me a test email')}</Button>
      </div>
      {settings && <p className="small muted">{t('Last changed by {a} on {b}.', { a: settings.updatedBy, b: new Date(settings.updatedAt).toLocaleString() })}</p>}
    </Form>
  )
}

type CompanyView = { settings: MailSettings | null; inUse: MailLevel; inUseFrom: string | null }

/** The company's own email server: optional, otherwise the platform's sends its emails. */
export function CompanyMailServerPage() {
  const [view, setView] = useState<CompanyView | null>(null)
  const [error, setError] = useState<string | null>(null)
  const load = async () => setView(await api<CompanyView>('/api/company/mail-server'))
  useEffect(() => { load().catch((e: Error) => setError(e.message)) }, [])
  return (
    <section>
      <p className="small muted"><Link to="/company">{t('Company')}</Link></p>
      <h2>{t('Email server')}</h2>
      <ErrorText error={error} />
      {view && (
        <>
          <div className="card">
            <dl className="facts">
              <dt>{t('Emails leave through')}</dt><dd>{levelText(view.inUse)}</dd>
              {view.inUseFrom && <><dt>{t('Sender')}</dt><dd>{view.inUseFrom}</dd></>}
            </dl>
            {view.inUse === 'NONE' && <p className="hint">{t('See them on the Messages page.')} <Link to="/outbox">{t('Messages')}</Link></p>}
          </div>
          <MailServerForm base="/api/company/mail-server" settings={view.settings} onSaved={load}
            intro={t("Optional. Set your company's own server so emails leave from your domain. When it is turned off, HR Pay's server sends them. If your server fails, emails are not sent another way: they show as failed on the Messages page and administrators are told.")} />
        </>
      )}
    </section>
  )
}

/** The provider's server: sends the emails of every company without its own. It takes over from the .env settings. */
export function PlatformMailServerPage() {
  const [settings, setSettings] = useState<MailSettings | null | undefined>(undefined)
  const [error, setError] = useState<string | null>(null)
  const load = async () => setSettings((await api<{ settings?: MailSettings }>('/api/platform/mail-server')).settings ?? null)
  useEffect(() => { load().catch((e: Error) => setError(e.message)) }, [])
  return (
    <section>
      <h2>{t('Platform email server')}</h2>
      <ErrorText error={error} />
      {settings !== undefined && <MailServerForm base="/api/platform/mail-server" settings={settings} onSaved={load}
        intro={t('Sends the emails of every company that has no server of its own, with the company\'s name in front of the sender name. It takes over from the SMTP settings of the .env file. When it is turned off and .env has none, emails are only written on the Messages page.')} />}
    </section>
  )
}
