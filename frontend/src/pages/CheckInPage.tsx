import { useEffect, useState, type FormEvent } from 'react'
import { useSearchParams } from 'react-router-dom'
import { api, currentPosition, time, type AttendanceStatus, type StartResponse } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'

const sourceLabel = { SELF: '', HR_MANUAL: ' (entered by HR)', AUTO_CLOSE: ' (closed automatically)', MISSION: ' (mission)' }

export default function CheckInPage() {
  const { signOut } = useAuth()
  const [params] = useSearchParams()
  const qrToken = params.get('qr')
  const [status, setStatus] = useState<AttendanceStatus | null>(null)
  const [busy, setBusy] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [challenge, setChallenge] = useState<StartResponse | null>(null)
  const [code, setCode] = useState('')
  const [done, setDone] = useState<string | null>(null)

  const load = () => api<AttendanceStatus>('/api/attendance/status').then(setStatus).catch((e: Error) => setError(e.message))
  useEffect(() => { void load() }, [])

  const finished = async (message: string) => {
    setDone(message)
    setChallenge(null)
    setCode('')
    await load()
    // A shared office computer signs the employee out right away.
    if (status?.trustedDevice) setTimeout(() => void signOut(), 2500)
  }

  const presence = async () => {
    setBusy('Getting your location…')
    const pos = status?.site?.gpsRequired && !status.trustedDevice ? await currentPosition() : { lat: null, lng: null, accuracyM: null }
    return { ...pos, qrToken }
  }

  const checkIn = async () => {
    setError(null)
    setDone(null)
    try {
      const body = await presence()
      setBusy('Checking…')
      const result = await api<StartResponse>('/api/attendance/check-in', { method: 'POST', body })
      if (result.status === 'CHECKED_IN') await finished(`Checked in at ${time(result.event!.occurredAt)}.`)
      else setChallenge(result)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Check-in failed.')
    } finally {
      setBusy(null)
    }
  }

  const confirm = async (e: FormEvent) => {
    e.preventDefault()
    setError(null)
    setBusy('Verifying the code…')
    try {
      const event = await api<{ occurredAt: string }>('/api/attendance/check-in/confirm', {
        method: 'POST', body: { challengeId: challenge!.challengeId, code },
      })
      await finished(`Checked in at ${time(event.occurredAt)}.`)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Wrong code.')
    } finally {
      setBusy(null)
    }
  }

  const checkOut = async () => {
    if (!confirm_('End your day now?')) return
    setError(null)
    setDone(null)
    try {
      const body = await presence()
      setBusy('Checking…')
      const event = await api<{ occurredAt: string }>('/api/attendance/check-out', { method: 'POST', body })
      await finished(`Day ended at ${time(event.occurredAt)}.`)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Check-out failed.')
    } finally {
      setBusy(null)
    }
  }

  if (!status) return <ErrorText error={error} />
  if (!status.site) return <p className="notice">You are not assigned to a site yet. Contact HR.</p>

  return (
    <section className="narrow">
      <div className="card checkin">
        <p className="muted">{status.site.name}{status.trustedDevice ? ` · office computer: ${status.trustedDeviceName}` : ''}</p>
        <h2>{status.checkedIn ? `Checked in since ${time(status.since!)}` : 'Not checked in'}</h2>
        {qrToken && <p className="success small">QR code scanned.</p>}
        {status.site.qrRequired && !qrToken && !status.trustedDevice && !status.checkedIn && (
          <p className="hint">Scan the QR code on the screen at the entrance to check in.</p>
        )}

        {challenge ? (
          <form onSubmit={confirm} className="stack">
            <p>We sent a 6-digit code by {challenge.channels?.map((c) => (c === 'EMAIL' ? 'email' : 'WhatsApp')).join(' and ')}.</p>
            <input inputMode="numeric" autoComplete="one-time-code" maxLength={6} placeholder="123456" value={code}
              onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))} className="code-input" autoFocus />
            <button type="submit" disabled={code.length !== 6 || !!busy}>Confirm arrival</button>
            <button type="button" className="secondary" onClick={() => setChallenge(null)}>Cancel</button>
          </form>
        ) : status.checkedIn ? (
          <button className="big danger" disabled={!!busy} onClick={() => void checkOut()}>End my day</button>
        ) : (
          <button className="big" disabled={!!busy} onClick={() => void checkIn()}>Check in</button>
        )}

        {busy && <p className="muted">{busy}</p>}
        <ErrorText error={error} />
        {done && <p className="success">{done}</p>}

        {status.today.length > 0 && (
          <>
            <h3>Today</h3>
            <ul className="events">
              {status.today.map((e) => (
                <li key={e.id}>{e.type === 'IN' ? 'Arrived' : 'Left'} at {time(e.occurredAt)}{sourceLabel[e.source]}</li>
              ))}
            </ul>
          </>
        )}
      </div>
    </section>
  )
}

function confirm_(message: string) {
  return window.confirm(message)
}
