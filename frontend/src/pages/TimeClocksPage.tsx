import { useEffect, useState } from 'react'
import { api, type ClockPunch, type Site, type TimeClock } from '../api'
import { Button, Form } from '../components/Button'
import { useAsk } from '../components/Dialogs'
import { ErrorText } from '../components/ErrorText'
import { Modal } from '../components/Modal'
import { t } from '../i18n'

type Draft = { id?: number; serialNo: string; name: string; siteId: number | ''; active: boolean; allowedIps: string; useStatus: boolean }
const OUTCOMES: Record<ClockPunch['outcome'], [string, string]> = {
  RECORDED: [t('Recorded'), 'badge ok'], UNKNOWN_PIN: [t('Unknown person'), 'badge warn'], IGNORED: [t('Ignored'), 'badge'],
}
const when = (d: string | null) => d ? new Date(d).toLocaleString() : '—'

/** Biometric time clocks (ZKTeco and compatible) that send their punches to HR Pay over the network. */
export default function TimeClocksPage() {
  const ask = useAsk()
  const [devices, setDevices] = useState<TimeClock[]>([])
  const [sites, setSites] = useState<Site[]>([])
  const [punches, setPunches] = useState<ClockPunch[]>([])
  const [device, setDevice] = useState('')
  const [outcome, setOutcome] = useState('')
  const [draft, setDraft] = useState<Draft | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [formError, setFormError] = useState<string | null>(null)
  const [info, setInfo] = useState<string | null>(null)

  const load = () => api<TimeClock[]>('/api/integrations/clocks').then(setDevices).catch((e: Error) => setError(e.message))
  const loadPunches = () => {
    const q = new URLSearchParams()
    if (device) q.set('device', device)
    if (outcome) q.set('outcome', outcome)
    return api<ClockPunch[]>(`/api/integrations/clocks/punches?${q}`).then(setPunches).catch((e: Error) => setError(e.message))
  }
  useEffect(() => {
    void load()
    api<Site[]>('/api/sites').then(setSites).catch(() => undefined)
  }, [])
  useEffect(() => { void loadPunches() }, [device, outcome]) // eslint-disable-line react-hooks/exhaustive-deps

  const save = async () => {
    if (!draft) return
    setFormError(null)
    try {
      const body = { ...draft, allowedIps: draft.allowedIps.trim() || null }
      await api(draft.id ? `/api/integrations/clocks/${draft.id}` : '/api/integrations/clocks', { method: draft.id ? 'PUT' : 'POST', body })
      setDraft(null)
      await load()
    } catch (e) { setFormError((e as Error).message) }
  }

  const host = window.location.hostname
  return (
    <section>
      <h2>{t('Time clocks')}</h2>
      <p className="muted">{t('Each punch from a fingerprint or face clock becomes a check-in or check-out of the person with that clock or registration number.')}</p>
      <ErrorText error={error} />
      {info && <p className="notice">{info}</p>}

      <div className="card">
        <div className="row between">
          <h3>{t('Clocks')}</h3>
          <Button onClick={() => { setFormError(null); setDraft({ serialNo: '', name: '', siteId: sites[0]?.id ?? '', active: true, allowedIps: '', useStatus: false }) }}>{t('Add a clock')}</Button>
        </div>
        <div className="table-wrap">
          <table>
            <thead><tr><th>{t('Name')}</th><th>{t('Serial number')}</th><th>{t('Site')}</th><th>{t('Last contact')}</th><th>{t('State')}</th><th /></tr></thead>
            <tbody>
              {devices.length === 0 && <tr><td colSpan={6} className="muted">{t('No clock yet.')}</td></tr>}
              {devices.map((d) => (
                <tr key={d.id}>
                  <td>{d.name}</td><td><code>{d.serialNo}</code></td><td>{d.siteName}</td>
                  <td className="small">{when(d.lastSeenAt)}{d.lastIp && <div className="muted">{d.lastIp}</div>}</td>
                  <td>{d.active ? <span className="badge ok">{t('Active')}</span> : <span className="badge">{t('Off')}</span>}</td>
                  <td>
                    <div className="row">
                      <Button className="secondary" onClick={() => { setFormError(null); setDraft({ id: d.id, serialNo: d.serialNo, name: d.name, siteId: d.siteId, active: d.active, allowedIps: d.allowedIps ?? '', useStatus: d.useStatus }) }}>{t('Edit')}</Button>
                      <Button className="secondary" onClick={() => ask({
                        title: t('Remove the clock {a}?', { a: d.name }), message: t('Its punches are removed from this list too (check-ins already recorded stay). To keep them, turn the clock off instead.'), confirmLabel: t('Remove'), danger: true,
                        onConfirm: async () => { await api(`/api/integrations/clocks/${d.id}`, { method: 'DELETE' }); await load() },
                      })}>{t('Remove')}</Button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      <details className="card">
        <summary><strong>{t('How to connect a clock')}</strong></summary>
        <ol className="small">
          <li>{t('Add the clock here with its serial number (Menu › System info › Device info on the clock).')}</li>
          <li>{t('On the clock, open Menu › Comm. › Cloud server setting (ADMS).')}</li>
          <li>{t('Server address: {a}. Port: 443 with HTTPS on, or 80 for a clock that cannot use HTTPS.', { a: host })}</li>
          <li>{t('Turn off the proxy on the clock and restart it. Within a minute its last contact shows here.')}</li>
          <li>{t('A person is found by the number they are enrolled under on the clock: their registration number, or a clock number given in the employee import or with Link to a person below.')}</li>
        </ol>
        <p className="small muted">{t('Without HTTPS, punches cross the network unencrypted: limit the clock to the addresses of your office with the allowed addresses field.')}</p>
      </details>

      <div className="card">
        <div className="row between">
          <h3>{t('Punches received')}</h3>
          <div className="row">
            <select value={device} onChange={(e) => setDevice(e.target.value)} aria-label={t('Clock')}>
              <option value="">{t('Every clock')}</option>
              {devices.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
            </select>
            <select value={outcome} onChange={(e) => setOutcome(e.target.value)} aria-label={t('Outcome')}>
              <option value="">{t('Every outcome')}</option>
              {Object.entries(OUTCOMES).map(([k, [label]]) => <option key={k} value={k}>{label}</option>)}
            </select>
            <Button className="secondary" onClick={loadPunches}>{t('Refresh')}</Button>
          </div>
        </div>
        <div className="table-wrap">
          <table>
            <thead><tr><th>{t('Time on the clock')}</th><th>{t('Clock')}</th><th>{t('Number')}</th><th>{t('Person')}</th><th>{t('Outcome')}</th></tr></thead>
            <tbody>
              {punches.length === 0 && <tr><td colSpan={5} className="muted">{t('No punch yet.')}</td></tr>}
              {punches.map((p) => (
                <tr key={p.id}>
                  <td>{p.punchedAt.replace('T', ' ').slice(0, 16)}</td><td>{p.device}</td><td>{p.pin}</td><td>{p.person ?? '—'}</td>
                  <td><span className={OUTCOMES[p.outcome][1]}>{OUTCOMES[p.outcome][0]}</span>{p.detail && <div className="small muted">{p.detail}</div>}
                    {p.outcome === 'UNKNOWN_PIN' && <Button className="link" onClick={() => ask({
                      title: t('Who has the number {a} on the clock?', { a: p.pin }),
                      message: t('The person is enrolled under this number from now on, and the punches waiting under it are recorded.'),
                      confirmLabel: t('Link'), fields: [{ label: t('Registration no.'), required: true }],
                      onConfirm: async ([reg]) => {
                        const r = await api<{ recorded: number }>('/api/integrations/clocks/link', { method: 'POST', body: { pin: p.pin, registrationNo: reg } })
                        await loadPunches()
                        setInfo(r.recorded === 0 ? t('Linked. No waiting punch could be recorded (see the outcome of each).')
                          : t('Linked: {a} punches recorded.', { a: r.recorded }))
                      },
                    })}>{t('Link to a person')}</Button>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      {draft && (
        <Modal title={draft.id ? t('Edit the clock') : t('Add a clock')} onClose={() => setDraft(null)}>
          <Form onSubmit={save}>
            <label>{t('Serial number')}<input required maxLength={40} value={draft.serialNo} onChange={(e) => setDraft({ ...draft, serialNo: e.target.value })} /></label>
            <label>{t('Name')}<input required maxLength={100} value={draft.name} placeholder={t('For example: Main entrance')} onChange={(e) => setDraft({ ...draft, name: e.target.value })} /></label>
            <label>{t('Site')}
              <select required value={draft.siteId} onChange={(e) => setDraft({ ...draft, siteId: Number(e.target.value) })}>
                <option value="" disabled>{t('Choose…')}</option>
                {sites.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
              </select>
            </label>
            <label>{t('Allowed addresses (optional)')}<input maxLength={300} value={draft.allowedIps} placeholder="41.243.0.0/16, 197.157.1.10" onChange={(e) => setDraft({ ...draft, allowedIps: e.target.value })} /></label>
            <label className="check"><input type="checkbox" checked={draft.useStatus} onChange={(e) => setDraft({ ...draft, useStatus: e.target.checked })} />
              {t('Use the in/out key pressed on the clock (otherwise each punch alternates check-in and check-out)')}</label>
            <label className="check"><input type="checkbox" checked={draft.active} onChange={(e) => setDraft({ ...draft, active: e.target.checked })} />{t('Active')}</label>
            <ErrorText error={formError} />
            <div className="modal-foot"><Button type="submit">{t('Save')}</Button></div>
          </Form>
        </Modal>
      )}
    </section>
  )
}
