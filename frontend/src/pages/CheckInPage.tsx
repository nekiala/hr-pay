import { useEffect, useState, type FormEvent } from 'react'
import { useSearchParams } from 'react-router-dom'
import { api, currentPosition, time, type AttendanceStatus, type StartResponse } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'
import { Button, Form } from '../components/Button'
import { t } from '../i18n'

const sourceLabel = { SELF: '', HR_MANUAL: t(' (entered by HR)'), AUTO_CLOSE: t(' (closed automatically)'), MISSION: t(' (mission)') }

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
    setBusy(t('Getting your location…'))
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
      setBusy(t('Checking…'))
      const result = await api<StartResponse>('/api/attendance/check-in', { method: 'POST', body })
      if (result.status === 'CHECKED_IN') await finished(t('Checked in at {a}.', { a: time(result.event!.occurredAt, status?.site?.timeZone) }))
      else setChallenge(result)
    } catch (e) {
      setError(e instanceof Error ? e.message : t('Check-in failed.'))
    } finally {
      setBusy(null)
    }
  }

  const confirm = async (e: FormEvent) => {
    e.preventDefault()
    setError(null)
    setBusy(t('Verifying the code…'))
    try {
      const event = await api<{ occurredAt: string }>('/api/attendance/check-in/confirm', {
        method: 'POST', body: { challengeId: challenge!.challengeId, code },
      })
      await finished(t('Checked in at {a}.', { a: time(event.occurredAt, status?.site?.timeZone) }))
    } catch (err) {
      setError(err instanceof Error ? err.message : t('Wrong code.'))
    } finally {
      setBusy(null)
    }
  }

  const checkOut = async () => {
    if (!confirm_(t('End your day now?'))) return
    setError(null)
    setDone(null)
    try {
      const body = await presence()
      setBusy(t('Checking…'))
      const event = await api<{ occurredAt: string }>('/api/attendance/check-out', { method: 'POST', body })
      await finished(t('Day ended at {a}.', { a: time(event.occurredAt, status?.site?.timeZone) }))
    } catch (e) {
      setError(e instanceof Error ? e.message : t('Check-out failed.'))
    } finally {
      setBusy(null)
    }
  }

  if (!status) return <ErrorText error={error} />
  if (!status.site) return <p className="notice">{t('You are not assigned to a site yet. Contact HR.')}</p>

  return (
    <section className="narrow">
      <div className="card checkin">
        <p className="muted">{status.site.name}{status.trustedDevice ? t(' · office computer: {a}', { a: status.trustedDeviceName }) : ''}</p>
        <h2>{status.checkedIn ? t('Checked in since {a}', { a: time(status.since!, status?.site?.timeZone) }) : t('Not checked in')}</h2>
        {qrToken && <p className="success small">{t('QR code scanned.')}</p>}
        {status.mission && status.checkedIn && (
          <p className="hint">{t('You are on an approved mission (')}{status.mission.location}, {status.mission.startTime.slice(0, 5)}–{status.mission.endTime.slice(0, 5)}{t('). You can end your day from there.')}</p>
        )}
        {status.site.qrRequired && !qrToken && !status.trustedDevice && !status.checkedIn && (
          <p className="hint">{t('Scan the QR code on the screen at the entrance to check in.')}</p>
        )}

        {challenge ? (
          <Form onSubmit={confirm} className="stack">
            <p>{t('We sent a 6-digit code by')}{' '}{challenge.channels?.map((c) => (c === 'EMAIL' ? 'email' : t('WhatsApp'))).join(' and ')}.</p>
            <input inputMode="numeric" autoComplete="one-time-code" maxLength={6} placeholder="123456" value={code}
              onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))} className="code-input" autoFocus />
            <Button type="submit" disabled={code.length !== 6} pending={!!busy} pendingText={busy ?? undefined}>{t('Confirm arrival')}</Button>
            <Button type="button" className="secondary" onClick={() => setChallenge(null)}>{t('Cancel')}</Button>
          </Form>
        ) : status.checkedIn ? (
          <Button className="big danger" pending={!!busy} pendingText={busy ?? undefined} onClick={() => checkOut()}>{t('End my day')}</Button>
        ) : (
          <Button className="big" pending={!!busy} pendingText={busy ?? undefined} onClick={() => checkIn()}>{t('Check in')}</Button>
        )}

        <ErrorText error={error} />
        {done && <p className="success">{done}</p>}

        {status.today.length > 0 && (
          <>
            <h3>{t('Today')}</h3>
            <ul className="events">
              {status.today.map((e) => (
                <li key={e.id}>{e.type === 'IN' ? t('Arrived') : t('Left')}{' '}{t('at')}{' '}{time(e.occurredAt, status?.site?.timeZone)}{sourceLabel[e.source]}</li>
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
