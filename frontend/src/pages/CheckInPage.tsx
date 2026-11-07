import { useEffect, useState, type FormEvent } from 'react'
import { useSearchParams } from 'react-router-dom'
import { api, currentPosition, time, type AttendanceStatus, type StartResponse } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'
import { Button, Form } from '../components/Button'

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
    const none = { lat: null, lng: null, accuracyM: null }
    const onMission = status?.checkedIn && status.mission
    // On a mission the location is recorded when available but not required.
    const pos = status?.site?.gpsRequired && !status.trustedDevice
      ? onMission ? await currentPosition().catch(() => none) : await currentPosition()
      : none
    return { ...pos, qrToken }
  }

  const checkIn = async () => {
    setError(null)
    setDone(null)
    try {
      const body = await presence()
      setBusy('Checking…')
      const result = await api<StartResponse>('/api/attendance/check-in', { method: 'POST', body })
      if (result.status === 'CHECKED_IN') await finished(`Checked in at ${time(result.event!.occurredAt, status?.site?.timeZone)}.`)
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
      await finished(`Checked in at ${time(event.occurredAt, status?.site?.timeZone)}.`)
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
      await finished(`Day ended at ${time(event.occurredAt, status?.site?.timeZone)}.`)
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
        <h2>{status.checkedIn ? `Checked in since ${time(status.since!, status?.site?.timeZone)}` : 'Not checked in'}</h2>
        {qrToken && <p className="success small">QR code scanned.</p>}
        {status.mission && status.checkedIn && (
          <p className="hint">You are on an approved mission ({status.mission.location}, {status.mission.startTime.slice(0, 5)}–{status.mission.endTime.slice(0, 5)}). You can end your day from there.</p>
        )}
        {status.site.qrRequired && !qrToken && !status.trustedDevice && !status.checkedIn && (
          <p className="hint">Scan the QR code on the screen at the entrance to check in.</p>
        )}

        {challenge ? (
          <Form onSubmit={confirm} className="stack">
            <p>We sent a 6-digit code by {challenge.channels?.map((c) => (c === 'EMAIL' ? 'email' : 'WhatsApp')).join(' and ')}.</p>
            <input inputMode="numeric" autoComplete="one-time-code" maxLength={6} placeholder="123456" value={code}
              onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))} className="code-input" autoFocus />
            <Button type="submit" disabled={code.length !== 6} pending={!!busy} pendingText={busy ?? undefined}>Confirm arrival</Button>
            <Button type="button" className="secondary" onClick={() => setChallenge(null)}>Cancel</Button>
          </Form>
        ) : status.checkedIn ? (
          <Button className="big danger" pending={!!busy} pendingText={busy ?? undefined} onClick={() => checkOut()}>End my day</Button>
        ) : (
          <Button className="big" pending={!!busy} pendingText={busy ?? undefined} onClick={() => checkIn()}>Check in</Button>
        )}

        <ErrorText error={error} />
        {done && <p className="success">{done}</p>}

        {status.today.length > 0 && (
          <>
            <h3>Today</h3>
            <ul className="events">
              {status.today.map((e) => (
                <li key={e.id}>{e.type === 'IN' ? 'Arrived' : 'Left'} at {time(e.occurredAt, status?.site?.timeZone)}{sourceLabel[e.source]}</li>
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
