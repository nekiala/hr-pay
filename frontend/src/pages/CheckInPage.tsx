import { useEffect, useState, type FormEvent } from 'react'
import { useSearchParams } from 'react-router-dom'
import { api, currentPosition, time, type AttendanceStatus, type StartResponse } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'
import { Button, Form } from '../components/Button'
import { useAsk } from '../components/Dialogs'
import { t } from '../i18n'
import { isOffline, knownStatus, queuePunch, rememberStatus, syncPunches, clearSent, useOnline, useQueue, type KnownStatus } from '../pwa'

const sourceLabel: Record<string, string> = { SELF: '', HR_MANUAL: t(' (entered by HR)'), AUTO_CLOSE: t(' (closed automatically)'), MISSION: t(' (mission)'), OFFLINE: t(' (sent from the phone)'), BIOMETRIC: t(' (time clock)') }

export default function CheckInPage() {
  const { signOut, me } = useAuth()
  const ask = useAsk()
  const userId = me!.id
  const online = useOnline()
  const { waiting, done: answered } = useQueue(userId)
  const [offline, setOffline] = useState<KnownStatus | null>(null)
  const [params] = useSearchParams()
  const qrToken = params.get('qr')
  const [status, setStatus] = useState<AttendanceStatus | null>(null)
  const [busy, setBusy] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [challenge, setChallenge] = useState<StartResponse | null>(null)
  const [code, setCode] = useState('')
  const [done, setDone] = useState<string | null>(null)

  const load = () => api<AttendanceStatus>('/api/attendance/status').then((s) => {
    setStatus(s)
    setOffline(null)
    if (s.site) {
      rememberStatus({ userId, checkedIn: s.checkedIn, siteName: s.site.name, timeZone: s.site.timeZone, offline: s.site.offlineCheckin, gps: s.site.gpsRequired })
    }
  }).catch((e: Error) => {
    // Without a connection, the phone works from what it last knew.
    if (isOffline(e)) setOffline(knownStatus(userId) ?? { userId, checkedIn: false, siteName: '', timeZone: 'Africa/Kinshasa', offline: 'OFF', gps: false })
    else setError(e.message)
  })
  useEffect(() => { void load() }, []) // eslint-disable-line react-hooks/exhaustive-deps
  // Back online: send what waited, then show the day as the server has it.
  useEffect(() => {
    if (!online) return
    void syncPunches(userId).catch(() => undefined).finally(() => void load())
  }, [online]) // eslint-disable-line react-hooks/exhaustive-deps

  /** Keeps the check-in on the phone, to send as soon as there is a connection. */
  const keep = async (type: 'IN' | 'OUT', known: KnownStatus) => {
    setError(null)
    setBusy(t('Getting your location…'))
    const pos = await currentPosition().catch(() => ({ lat: null, lng: null, accuracyM: null }))
    setBusy(null)
    queuePunch(userId, type, pos)
    const next = { ...known, checkedIn: type === 'IN' }
    rememberStatus(next)
    setOffline(next)
    setDone(type === 'IN' ? t('Kept on the phone: your check-in is sent when the connection comes back.')
      : t('Kept on the phone: your check-out is sent when the connection comes back.'))
  }

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
      if (isOffline(e)) setOffline(knownStatus(userId))
      setError(isOffline(e) ? t('No connection.') : e instanceof Error ? e.message : t('Check-in failed.'))
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

  /** Asks in the app's own dialog, not the browser's, before ending the day. */
  const confirmEnd = (end: () => Promise<unknown>) => ask({
    title: t('End my day'),
    message: t('End your day now?'),
    confirmLabel: t('End my day'),
    pendingText: t('Ending the day…'),
    danger: true,
    onConfirm: end,
  })

  const checkOut = async () => {
    setError(null)
    setDone(null)
    try {
      const body = await presence()
      setBusy(t('Checking…'))
      const event = await api<{ occurredAt: string }>('/api/attendance/check-out', { method: 'POST', body })
      await finished(t('Day ended at {a}.', { a: time(event.occurredAt, status?.site?.timeZone) }))
    } catch (e) {
      if (isOffline(e)) setOffline(knownStatus(userId))
      setError(isOffline(e) ? t('No connection.') : e instanceof Error ? e.message : t('Check-out failed.'))
    } finally {
      setBusy(null)
    }
  }

  const queue = (
    <OfflineQueue waiting={waiting} answered={answered} timeZone={offline?.timeZone ?? status?.site?.timeZone} onClear={() => clearSent(userId)} />
  )
  if (offline && (!status || !online)) {
    return (
      <section>
        <div className="card checkin">
          <p className="notice">{t('No connection.')}</p>
          {offline.siteName && <p className="muted">{offline.siteName}</p>}
          <h2>{offline.checkedIn ? t('Checked in') : t('Not checked in')}</h2>
          {offline.offline === 'OFF' ? (
            <p className="hint">{t('Your site does not allow check-ins without a connection. Check in when you are back online.')}</p>
          ) : (
            <>
              <p className="hint">{offline.offline === 'IN_AREA'
                ? t('The phone keeps the time and your location. In the site\'s area it is recorded when sent; otherwise your manager approves it.')
                : t('The phone keeps the time and your location; your manager approves it once it is sent.')}</p>
              {offline.checkedIn
                ? <Button className="big danger" pending={!!busy} pendingText={busy ?? undefined} onClick={() => confirmEnd(() => keep('OUT', offline))}>{t('End my day')}</Button>
                : <Button className="big" pending={!!busy} pendingText={busy ?? undefined} onClick={() => void keep('IN', offline)}>{t('Check in')}</Button>}
            </>
          )}
          <ErrorText error={error} />
          {done && <p className="success">{done}</p>}
          {queue}
          <Button className="secondary" onClick={() => { setError(null); void load() }}>{t('Try again')}</Button>
        </div>
      </section>
    )
  }
  if (!status) return <ErrorText error={error} />
  if (!status.site) return <p className="notice">{t('You are not assigned to a site yet. Contact HR.')}</p>

  return (
    <section>
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
          <Button className="big danger" pending={!!busy} pendingText={busy ?? undefined} onClick={() => confirmEnd(checkOut)}>{t('End my day')}</Button>
        ) : (
          <Button className="big" pending={!!busy} pendingText={busy ?? undefined} onClick={() => checkIn()}>{t('Check in')}</Button>
        )}

        <ErrorText error={error} />
        {done && <p className="success">{done}</p>}
        {queue}

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

const OUTCOME: Record<string, [string, string]> = {
  RECORDED: [t('Recorded'), 'badge ok'], REVIEW: [t('Sent to your manager'), 'badge warn'], REFUSED: [t('Not recorded'), 'badge off'],
}

/** The check-ins kept on the phone, and what became of the last ones sent. */
function OfflineQueue({ waiting, answered, timeZone, onClear }: {
  waiting: { clientId: string; type: 'IN' | 'OUT'; at: string }[]
  answered: { clientId: string; type: 'IN' | 'OUT'; at: string; outcome: string; detail: string | null }[]
  timeZone?: string
  onClear: () => void
}) {
  if (waiting.length === 0 && answered.length === 0) return null
  const label = (type: 'IN' | 'OUT') => type === 'IN' ? t('Arrived') : t('Left')
  return (
    <div className="stack small">
      {waiting.length > 0 && (
        <>
          <h3>{t('Waiting to be sent')}</h3>
          <ul className="events">
            {waiting.map((p) => <li key={p.clientId}>{label(p.type)}{' '}{t('at')}{' '}{time(p.at, timeZone)} <span className="badge">{t('On the phone')}</span></li>)}
          </ul>
        </>
      )}
      {answered.length > 0 && (
        <>
          <div className="row between"><h3>{t('Sent from the phone')}</h3><Button className="link" onClick={onClear}>{t('Clear')}</Button></div>
          <ul className="events">
            {answered.slice(0, 5).map((p) => (
              <li key={p.clientId}>{label(p.type)}{' '}{t('at')}{' '}{time(p.at, timeZone)}{' '}
                <span className={OUTCOME[p.outcome]?.[1] ?? 'badge'}>{OUTCOME[p.outcome]?.[0] ?? p.outcome}</span>
                {p.detail && <div className="muted">{p.detail}</div>}
              </li>
            ))}
          </ul>
        </>
      )}
    </div>
  )
}
