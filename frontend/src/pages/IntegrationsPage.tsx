import { useEffect, useState } from 'react'
import { api, longDay, type ApiKey, type IntegrationCatalogue, type Webhook, type WebhookDelivery } from '../api'
import { Button, Form } from '../components/Button'
import { useAsk } from '../components/Dialogs'
import { ErrorText } from '../components/ErrorText'
import { Modal } from '../components/Modal'
import { t } from '../i18n'

const when = (d: string | null) => d ? new Date(d).toLocaleString() : '—'

/** A secret shown once, right after it is made: the server keeps only a hash or an encrypted copy. */
function SecretOnce({ title, secret, note, onClose }: { title: string; secret: string; note: string; onClose: () => void }) {
  const [copied, setCopied] = useState(false)
  return (
    <Modal title={title} onClose={onClose} footer={<Button onClick={onClose}>{t('I have saved it')}</Button>}>
      <p>{note}</p>
      <p><code className="secret">{secret}</code></p>
      <Button className="secondary" onClick={() => navigator.clipboard.writeText(secret).then(() => setCopied(true))}>{copied ? t('Copied') : t('Copy')}</Button>
    </Modal>
  )
}

function Checks({ options, chosen, onChange }: { options: [string, string][]; chosen: string[]; onChange: (v: string[]) => void }) {
  return (
    <div className="checks">
      {options.map(([code, label]) => (
        <label key={code} className="check">
          <input type="checkbox" checked={chosen.includes(code)}
            onChange={(e) => onChange(e.target.checked ? [...chosen, code] : chosen.filter((c) => c !== code))} />
          {label} <span className="muted small">{code}</span>
        </label>
      ))}
    </div>
  )
}

/** Access keys for other software to use the HR Pay API, and webhooks that tell it when something happens. */
export default function IntegrationsPage() {
  const ask = useAsk()
  const [catalogue, setCatalogue] = useState<IntegrationCatalogue | null>(null)
  const [keys, setKeys] = useState<ApiKey[]>([])
  const [hooks, setHooks] = useState<Webhook[]>([])
  const [error, setError] = useState<string | null>(null)
  const [secret, setSecret] = useState<{ title: string; value: string; note: string } | null>(null)
  const [newKey, setNewKey] = useState<{ name: string; permissions: string[]; expiresOn: string } | null>(null)
  const [hookForm, setHookForm] = useState<{ id?: number; url: string; events: string[]; active: boolean } | null>(null)
  const [formError, setFormError] = useState<string | null>(null)
  const [deliveries, setDeliveries] = useState<{ hook: Webhook; list: WebhookDelivery[] } | null>(null)

  const load = () => Promise.all([
    api<ApiKey[]>('/api/integrations/keys').then(setKeys),
    api<Webhook[]>('/api/integrations/webhooks').then(setHooks),
  ]).catch((e: Error) => setError(e.message))
  useEffect(() => {
    api<IntegrationCatalogue>('/api/integrations/catalogue').then(setCatalogue).catch((e: Error) => setError(e.message))
    void load()
  }, [])

  const createKey = async () => {
    if (!newKey) return
    setFormError(null)
    try {
      const r = await api<{ key: ApiKey; secret: string }>('/api/integrations/keys', { method: 'POST', body: { ...newKey, expiresOn: newKey.expiresOn || null } })
      setNewKey(null)
      setSecret({ title: t('Access key {a}', { a: r.key.name }), value: r.secret, note: t('Copy this key now and give it to the software that will use it. It will not be shown again.') })
      await load()
    } catch (e) { setFormError((e as Error).message) }
  }

  const saveHook = async () => {
    if (!hookForm) return
    setFormError(null)
    try {
      const body = { url: hookForm.url, events: hookForm.events, active: hookForm.active }
      if (hookForm.id) {
        await api(`/api/integrations/webhooks/${hookForm.id}`, { method: 'PUT', body })
      } else {
        const r = await api<{ webhook: Webhook; secret: string }>('/api/integrations/webhooks', { method: 'POST', body })
        setSecret({ title: t('Signing secret'), value: r.secret, note: t('Each call carries the header X-HRPay-Signature, made with this secret. Keep it in the receiving software to check that calls come from HR Pay. It will not be shown again.') })
      }
      setHookForm(null)
      await load()
    } catch (e) { setFormError((e as Error).message) }
  }

  const openDeliveries = (hook: Webhook) =>
    api<WebhookDelivery[]>(`/api/integrations/webhooks/${hook.id}/deliveries`).then((list) => setDeliveries({ hook, list })).catch((e: Error) => setError(e.message))

  const scopes = Object.entries(catalogue?.scopes ?? {})
  const events = (catalogue?.events ?? []).map((e) => [e, ''] as [string, string])
  return (
    <section>
      <h2>{t('API and webhooks')}</h2>
      <p className="muted">
        {t('Other software (an ERP, a time and attendance system, a bank portal) can read and send data through the HR Pay API with an access key.')}{' '}
        <a href="/api/v1/openapi.json" target="_blank" rel="noreferrer">{t('API description (OpenAPI)')}</a>
      </p>
      <ErrorText error={error} />

      <div className="card">
        <div className="row between">
          <h3>{t('Access keys')}</h3>
          <Button onClick={() => { setFormError(null); setNewKey({ name: '', permissions: [], expiresOn: '' }) }}>{t('New key')}</Button>
        </div>
        <p className="small muted">{t('Send the key in the header Authorization: Bearer <key>. A key can do only what it was given, and never more than the person who made it.')}</p>
        <div className="table-wrap">
          <table>
            <thead><tr><th>{t('Name')}</th><th>{t('Key')}</th><th>{t('Permissions')}</th><th>{t('Expires')}</th><th>{t('Last used')}</th><th /></tr></thead>
            <tbody>
              {keys.length === 0 && <tr><td colSpan={6} className="muted">{t('No key yet.')}</td></tr>}
              {keys.map((k) => (
                <tr key={k.id}>
                  <td>{k.name}<div className="small muted">{t('by {a}, {b}', { a: k.createdBy, b: longDay(k.createdAt) })}</div></td>
                  <td><code>hrp_{k.prefix}_…</code></td>
                  <td className="small">{k.permissions.map((p) => catalogue?.scopes[p] ?? p).join(', ')}</td>
                  <td>{longDay(k.expiresOn)}</td>
                  <td className="small">{k.lastUsedAt ? <>{when(k.lastUsedAt)}<div className="muted">{k.lastUsedIp}</div></> : '—'}</td>
                  <td>{k.revokedAt
                    ? <span className="badge off">{t('Revoked')}</span>
                    : <Button className="secondary" onClick={() => ask({
                        title: t('Revoke the key {a}?', { a: k.name }), message: t('Software using this key will be refused at once.'), confirmLabel: t('Revoke'), danger: true,
                        onConfirm: async () => { await api(`/api/integrations/keys/${k.id}/revoke`, { method: 'POST' }); await load() },
                      })}>{t('Revoke')}</Button>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      <div className="card">
        <div className="row between">
          <h3>{t('Webhooks')}</h3>
          <Button onClick={() => { setFormError(null); setHookForm({ url: 'https://', events: ['payroll.closed'], active: true }) }}>{t('New webhook')}</Button>
        </div>
        <p className="small muted">{t('HR Pay calls the address (POST, JSON) when one of the chosen events happens, and tries again for a day if it does not answer.')}</p>
        <div className="table-wrap">
          <table>
            <thead><tr><th>{t('Address')}</th><th>{t('Events')}</th><th>{t('State')}</th><th>{t('Last sent')}</th><th /></tr></thead>
            <tbody>
              {hooks.length === 0 && <tr><td colSpan={5} className="muted">{t('No webhook yet.')}</td></tr>}
              {hooks.map((h) => (
                <tr key={h.id}>
                  <td className="small"><code>{h.url}</code></td>
                  <td className="small">{h.events.join(', ')}</td>
                  <td>
                    {h.active ? <span className="badge ok">{t('Active')}</span> : <span className="badge">{t('Paused')}</span>}
                    {h.pending > 0 && <div className="small muted">{t('{a} waiting', { a: h.pending })}</div>}
                    {h.failed > 0 && <div className="small error">{t('{a} failed', { a: h.failed })}</div>}
                  </td>
                  <td className="small">{when(h.lastSentAt)}</td>
                  <td>
                    <div className="row">
                      <Button className="secondary" onClick={() => openDeliveries(h)}>{t('Deliveries')}</Button>
                      <Button className="secondary" onClick={async () => { await api(`/api/integrations/webhooks/${h.id}/ping`, { method: 'POST' }); await load() }}>{t('Send a test')}</Button>
                      <Button className="secondary" onClick={() => { setFormError(null); setHookForm({ id: h.id, url: h.url, events: h.events, active: h.active }) }}>{t('Edit')}</Button>
                      <Button className="secondary" onClick={() => ask({
                        title: t('Make a new signing secret?'), message: t('The old secret stops working at once: update the receiving software.'), confirmLabel: t('New secret'),
                        onConfirm: async () => {
                          const r = await api<{ webhook: Webhook; secret: string }>(`/api/integrations/webhooks/${h.id}/secret`, { method: 'POST' })
                          setSecret({ title: t('Signing secret'), value: r.secret, note: t('It will not be shown again.') })
                        },
                      })}>{t('New secret')}</Button>
                      <Button className="secondary" onClick={() => ask({
                        title: t('Delete this webhook?'), confirmLabel: t('Delete'), danger: true,
                        onConfirm: async () => { await api(`/api/integrations/webhooks/${h.id}`, { method: 'DELETE' }); await load() },
                      })}>{t('Delete')}</Button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <details>
          <summary>{t('How to check a call')}</summary>
          <p className="small">{t('Each call carries X-HRPay-Event, X-HRPay-Delivery, X-HRPay-Timestamp and X-HRPay-Signature. The signature is sha256= followed by the HMAC-SHA256, in hexadecimal, of the timestamp, a dot and the body, with the signing secret as key. Refuse a call whose timestamp is more than five minutes old.')}</p>
        </details>
      </div>

      {newKey && (
        <Modal title={t('New access key')} onClose={() => setNewKey(null)}>
          <Form onSubmit={createKey}>
            <label>{t('Name')}<input required maxLength={100} value={newKey.name} placeholder={t('For example: Sage accounting')} onChange={(e) => setNewKey({ ...newKey, name: e.target.value })} /></label>
            <label>{t('Expires on (optional)')}<input type="date" value={newKey.expiresOn} onChange={(e) => setNewKey({ ...newKey, expiresOn: e.target.value })} /></label>
            <fieldset><legend>{t('What the key may do')}</legend>
              <Checks options={scopes} chosen={newKey.permissions} onChange={(permissions) => setNewKey({ ...newKey, permissions })} />
            </fieldset>
            <ErrorText error={formError} />
            <div className="modal-foot"><Button type="submit" disabled={!newKey.permissions.length}>{t('Make the key')}</Button></div>
          </Form>
        </Modal>
      )}

      {hookForm && (
        <Modal title={hookForm.id ? t('Edit the webhook') : t('New webhook')} onClose={() => setHookForm(null)}>
          <Form onSubmit={saveHook}>
            <label>{t('Address (https)')}<input required type="url" maxLength={500} value={hookForm.url} onChange={(e) => setHookForm({ ...hookForm, url: e.target.value })} /></label>
            <fieldset><legend>{t('Events')}</legend>
              <Checks options={events} chosen={hookForm.events} onChange={(evs) => setHookForm({ ...hookForm, events: evs })} />
            </fieldset>
            {hookForm.id && <label className="check"><input type="checkbox" checked={hookForm.active} onChange={(e) => setHookForm({ ...hookForm, active: e.target.checked })} />{t('Active')}</label>}
            <ErrorText error={formError} />
            <div className="modal-foot"><Button type="submit" disabled={!hookForm.events.length}>{t('Save')}</Button></div>
          </Form>
        </Modal>
      )}

      {deliveries && (
        <Modal size="lg" title={t('Deliveries to {a}', { a: deliveries.hook.url })} onClose={() => setDeliveries(null)}>
          <div className="table-wrap">
            <table>
              <thead><tr><th>{t('Event')}</th><th>{t('State')}</th><th className="num">{t('Tries')}</th><th>{t('Answer')}</th><th>{t('Time')}</th><th /></tr></thead>
              <tbody>
                {deliveries.list.length === 0 && <tr><td colSpan={6} className="muted">{t('Nothing sent yet.')}</td></tr>}
                {deliveries.list.map((d) => (
                  <tr key={d.id}>
                    <td>{d.event}</td>
                    <td><span className={d.status === 'SENT' ? 'badge ok' : d.status === 'FAILED' ? 'badge off' : 'badge warn'}>
                      {d.status === 'SENT' ? t('Sent') : d.status === 'FAILED' ? t('Failed') : t('Waiting')}</span></td>
                    <td className="num">{d.attempts}</td>
                    <td className="small">{d.lastCode ?? ''} {d.lastError ?? ''}</td>
                    <td className="small">{when(d.sentAt ?? d.nextAttemptAt ?? d.createdAt)}</td>
                    <td>{d.status !== 'SENT' && <Button className="secondary" onClick={async () => {
                      await api(`/api/integrations/webhooks/${deliveries.hook.id}/deliveries/${d.id}/retry`, { method: 'POST' })
                      await openDeliveries(deliveries.hook)
                    }}>{t('Try again')}</Button>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Modal>
      )}

      {secret && <SecretOnce title={secret.title} secret={secret.value} note={secret.note} onClose={() => setSecret(null)} />}
    </section>
  )
}
