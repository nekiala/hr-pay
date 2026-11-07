import { useEffect, useState, type FormEvent } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { ApiError, api, type IpRange, type Site, type TrustedDevice } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'
import { useAsk } from '../components/Dialogs'
import { Button, Form } from '../components/Button'

type Form = Omit<Site, 'id'>

const empty: Form = {
  code: '', name: '', countryCode: 'CD', timeZone: 'Africa/Kinshasa', centerLat: null, centerLng: null,
  radiusM: 150, gpsRequired: true, maxAccuracyM: 100, active: true, qrRequired: false, ipRanges: [],
}

const zones = ['Africa/Kinshasa', 'Africa/Lubumbashi']

export default function SiteFormPage() {
  const { id } = useParams()
  const isNew = !id
  const navigate = useNavigate()
  const { can } = useAuth()
  const editable = can('site.manage')
  const [form, setForm] = useState<Form>(empty)
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)

  useEffect(() => {
    if (id) api<Site>(`/api/sites/${id}`).then(({ id: _ignored, ...rest }) => setForm(rest)).catch((e: Error) => setError(e.message))
  }, [id])

  const num = (v: string) => (v === '' ? null : Number(v))
  const update = <K extends keyof Form>(key: K, value: Form[K]) => setForm({ ...form, [key]: value })
  const setRange = (i: number, patch: Partial<IpRange>) =>
    update('ipRanges', form.ipRanges.map((r, j) => (i === j ? { ...r, ...patch } : r)))

  const useMyLocation = () => {
    navigator.geolocation?.getCurrentPosition(
      (pos) => setForm({ ...form, centerLat: Number(pos.coords.latitude.toFixed(6)), centerLng: Number(pos.coords.longitude.toFixed(6)) }),
      () => setError('Could not read your location.'),
      { enableHighAccuracy: true },
    )
  }

  const save = async (e: FormEvent) => {
    e.preventDefault()
    setError(null)
    setMessage(null)
    try {
      const body = { ...form, ipRanges: form.ipRanges.filter((r) => r.cidr.trim()).map(({ cidr, label }) => ({ cidr, label })) }
      const saved = await api<Site>(isNew ? '/api/sites' : `/api/sites/${id}`, { method: isNew ? 'POST' : 'PUT', body })
      if (isNew) navigate(`/sites/${saved.id}`, { replace: true })
      else {
        const { id: _ignored, ...rest } = saved
        setForm(rest)
        setMessage('Saved.')
      }
    } catch (err) {
      setError(err instanceof ApiError ? [err.message, ...Object.values(err.fieldErrors)].join(' ') : 'Could not save.')
    }
  }

  return (
    <section className="narrow">
      <h2>{isNew ? 'New site' : form.name}</h2>
      <Form className="card" onSubmit={save}>
        <fieldset disabled={!editable}>
          <div className="grid2">
            <label>Code<input value={form.code} onChange={(e) => update('code', e.target.value.toUpperCase())} required disabled={!isNew} /></label>
            <label>Name<input value={form.name} onChange={(e) => update('name', e.target.value)} required /></label>
          </div>
          <div className="grid2">
            <label>Country<input value={form.countryCode} maxLength={2} onChange={(e) => update('countryCode', e.target.value.toUpperCase())} required /></label>
            <label>Time zone
              <select value={form.timeZone} onChange={(e) => update('timeZone', e.target.value)}>
                {[...new Set([...zones, form.timeZone])].map((z) => <option key={z}>{z}</option>)}
              </select>
            </label>
          </div>

          <h3>Allowed networks</h3>
          <p className="hint">Check-ins are accepted only from these public IP addresses or ranges (for example 41.243.16.0/24).</p>
          {form.ipRanges.map((r, i) => (
            <div className="row" key={i}>
              <input placeholder="IP or CIDR" value={r.cidr} onChange={(e) => setRange(i, { cidr: e.target.value })} />
              <input placeholder="Label" value={r.label ?? ''} onChange={(e) => setRange(i, { label: e.target.value })} />
              <Button type="button" className="secondary" onClick={() => update('ipRanges', form.ipRanges.filter((_, j) => j !== i))}>Remove</Button>
            </div>
          ))}
          <Button type="button" className="secondary" onClick={() => update('ipRanges', [...form.ipRanges, { cidr: '', label: '' }])}>Add network</Button>

          <h3>GPS area</h3>
          <div className="grid3">
            <label>Latitude<input type="number" step="0.000001" value={form.centerLat ?? ''} onChange={(e) => update('centerLat', num(e.target.value))} /></label>
            <label>Longitude<input type="number" step="0.000001" value={form.centerLng ?? ''} onChange={(e) => update('centerLng', num(e.target.value))} /></label>
            <label>Radius (m)<input type="number" min={10} value={form.radiusM ?? ''} onChange={(e) => update('radiusM', num(e.target.value))} /></label>
          </div>
          <Button type="button" className="secondary" onClick={useMyLocation}>Use my current location</Button>
          <div className="grid2">
            <label className="check"><input type="checkbox" checked={form.gpsRequired} onChange={(e) => update('gpsRequired', e.target.checked)} />GPS required at check-in</label>
            <label>Maximum GPS accuracy (m)<input type="number" min={5} value={form.maxAccuracyM} onChange={(e) => update('maxAccuracyM', Number(e.target.value))} /></label>
          </div>
          <label className="check"><input type="checkbox" checked={form.qrRequired} onChange={(e) => update('qrRequired', e.target.checked)} />Employees must scan the office QR code to check in</label>
          <label className="check"><input type="checkbox" checked={form.active} onChange={(e) => update('active', e.target.checked)} />Site is active</label>
        </fieldset>
        <ErrorText error={error} />
        {message && <p className="success">{message}</p>}
        {editable && <Button type="submit">{isNew ? 'Create site' : 'Save changes'}</Button>}
      </Form>
      {!isNew && editable && <OfficeTools siteId={Number(id)} />}
    </section>
  )
}

function OfficeTools({ siteId }: { siteId: number }) {
  const ask = useAsk()
  const [devices, setDevices] = useState<TrustedDevice[]>([])
  const [link, setLink] = useState<string | null>(null)
  const [name, setName] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)

  const load = () => api<TrustedDevice[]>(`/api/sites/${siteId}/devices`).then(setDevices).catch((e: Error) => setError(e.message))
  useEffect(() => { load() }, [siteId]) // eslint-disable-line react-hooks/exhaustive-deps

  const run = async (action: () => Promise<void>) => {
    setError(null)
    setMessage(null)
    try { await action() } catch (e) { setError(e instanceof Error ? e.message : 'Something went wrong.') }
  }
  const showLink = () => run(async () => setLink((await api<{ url: string }>(`/api/sites/${siteId}/kiosk-link`)).url))
  const newKey = () => ask({
    title: 'Create a new kiosk link?', message: 'Screens using the old kiosk link will stop working.', confirmLabel: 'Create a new link', danger: true,
    onConfirm: () => run(async () => {
      setLink((await api<{ url: string }>(`/api/sites/${siteId}/kiosk-key`, { method: 'POST' })).url)
      setMessage('New kiosk link created.')
    }),
  })
  const register = () => run(async () => {
    await api(`/api/sites/${siteId}/devices`, { method: 'POST', body: { name: name.trim() || 'Office computer' } })
    setName('')
    setMessage('This computer is now an office device. Employees who sign in here skip the GPS and QR checks.')
    await load()
  })
  const deactivate = (d: TrustedDevice) => run(async () => {
    await api(`/api/sites/${siteId}/devices/${d.id}/deactivate`, { method: 'POST' })
    await load()
  })

  return (
    <div className="card">
      <h3>Kiosk QR screen</h3>
      <p className="hint">Open this link on a screen at the entrance. It shows a QR code that changes every 30 seconds.</p>
      <div className="row">
        <Button type="button" className="secondary" onClick={showLink}>Show kiosk link</Button>
        <Button type="button" className="secondary" onClick={newKey}>Create a new link</Button>
      </div>
      {link && <p><a href={link} target="_blank" rel="noreferrer">{link}</a></p>}

      <h3>Office devices</h3>
      <p className="hint">A shared office computer lets employees without a smartphone check in. The network check still applies.</p>
      <div className="row">
        <input placeholder="Name, for example Reception laptop" value={name} onChange={(e) => setName(e.target.value)} />
        <Button type="button" onClick={register}>Register this computer</Button>
      </div>
      {devices.length > 0 && (
        <table>
          <thead><tr><th>Name</th><th>Registered</th><th>Last used</th><th></th></tr></thead>
          <tbody>
            {devices.map((d) => (
              <tr key={d.id}>
                <td>{d.name}</td>
                <td>{new Date(d.createdAt).toLocaleDateString()}{d.createdBy ? ` by ${d.createdBy}` : ''}</td>
                <td>{d.lastUsedAt ? new Date(d.lastUsedAt).toLocaleString() : 'Never'}</td>
                <td>{d.active ? <Button type="button" className="secondary" onClick={() => deactivate(d)}>Deactivate</Button> : 'Inactive'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <ErrorText error={error} />
      {message && <p className="success">{message}</p>}
    </div>
  )
}
