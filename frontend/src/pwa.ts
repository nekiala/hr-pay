// The installable app: the service worker, phone notifications, the install prompt, and check-ins kept while offline.
import { useEffect, useState } from 'react'
import { api, ApiError, type OfflineOutcome, type PushSettings } from './api'

/** Registers the service worker in the built app (not in development, where it would keep stale files). */
export function registerServiceWorker() {
  if (!import.meta.env.PROD || !('serviceWorker' in navigator)) return
  window.addEventListener('load', () => { void navigator.serviceWorker.register('/sw.js').catch(() => undefined) })
}

// ---- install ------------------------------------------------------------------------------------------------------

type InstallEvent = Event & { prompt: () => Promise<void>; userChoice: Promise<{ outcome: string }> }
let deferred: InstallEvent | null = null
const listeners = new Set<() => void>()
if (typeof window !== 'undefined') {
  window.addEventListener('beforeinstallprompt', (e) => {
    e.preventDefault()
    deferred = e as InstallEvent
    listeners.forEach((l) => l())
  })
  window.addEventListener('appinstalled', () => { deferred = null; listeners.forEach((l) => l()) })
}

export const installed = () => typeof window !== 'undefined'
  && (window.matchMedia('(display-mode: standalone)').matches || (navigator as Navigator & { standalone?: boolean }).standalone === true)
export const isIos = () => /iphone|ipad|ipod/i.test(navigator.userAgent)

/** Whether the browser offers to install the app now, and the function that asks. */
export function useInstall(): { canInstall: boolean; install: () => Promise<void> } {
  const [, setTick] = useState(0)
  useEffect(() => {
    const l = () => setTick((n) => n + 1)
    listeners.add(l)
    return () => { listeners.delete(l) }
  }, [])
  return {
    canInstall: deferred !== null,
    install: async () => {
      if (!deferred) return
      await deferred.prompt()
      await deferred.userChoice.catch(() => undefined)
      deferred = null
      listeners.forEach((l) => l())
    },
  }
}

// ---- push ---------------------------------------------------------------------------------------------------------

export const pushSupported = () => 'serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window

const key = (base64: string) => {
  const padded = (base64 + '='.repeat((4 - (base64.length % 4)) % 4)).replace(/-/g, '+').replace(/_/g, '/')
  return Uint8Array.from(atob(padded), (c) => c.charCodeAt(0))
}

/** The subscription of this browser, if it has one. */
export async function currentSubscription(): Promise<PushSubscription | null> {
  if (!pushSupported()) return null
  const reg = await navigator.serviceWorker.getRegistration()
  return (await reg?.pushManager.getSubscription()) ?? null
}

/** Asks for permission, subscribes this browser and tells HR Pay where to send. */
export async function turnOnPush(settings: PushSettings): Promise<void> {
  if (!settings.enabled || !settings.publicKey) throw new Error('push off')
  if ((await Notification.requestPermission()) !== 'granted') throw new Error('denied')
  const reg = await navigator.serviceWorker.ready
  const sub = (await reg.pushManager.getSubscription())
    ?? await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: key(settings.publicKey) })
  const json = sub.toJSON()
  await api('/api/notifications/push', { method: 'POST', body: { endpoint: sub.endpoint, keys: json.keys ?? {} } })
}

export async function turnOffPush(): Promise<void> {
  const sub = await currentSubscription()
  if (!sub) return
  await api('/api/notifications/push', { method: 'DELETE', body: { endpoint: sub.endpoint } }).catch(() => undefined)
  await sub.unsubscribe()
}

// ---- check-ins kept while offline ---------------------------------------------------------------------------------

export type QueuedPunch = { userId: number; clientId: string; type: 'IN' | 'OUT'; at: string; lat: number | null; lng: number | null; accuracyM: number | null }
export type SentPunch = QueuedPunch & { outcome: OfflineOutcome['outcome']; detail: string | null; sentAt: string }

const QUEUE = 'hrpay.offline.queue'
const SENT = 'hrpay.offline.sent'
const STATUS = 'hrpay.offline.status'

const read = <T,>(k: string, fallback: T): T => {
  try { return JSON.parse(localStorage.getItem(k) ?? 'null') ?? fallback } catch { return fallback }
}
const write = (k: string, v: unknown) => { try { localStorage.setItem(k, JSON.stringify(v)) } catch { /* private window */ } }
const changed = () => window.dispatchEvent(new Event('hrpay:offline-queue'))

export const queued = () => read<QueuedPunch[]>(QUEUE, [])
export const sent = () => read<SentPunch[]>(SENT, [])

/** What the phone last knew of the person's day, to show while offline. Kept per person. */
export type KnownStatus = { userId: number; checkedIn: boolean; siteName: string; timeZone: string; offline: 'OFF' | 'REVIEW' | 'IN_AREA'; gps: boolean }
export const knownStatus = (userId: number) => {
  const s = read<KnownStatus | null>(STATUS, null)
  return s && s.userId === userId ? s : null
}
export const rememberStatus = (s: KnownStatus) => write(STATUS, s)

const uuid = () => (crypto.randomUUID ? crypto.randomUUID()
  : 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (crypto.getRandomValues(new Uint8Array(1))[0] & 15)
    return (c === 'x' ? r : (r & 3) | 8).toString(16)
  }))

export function queuePunch(userId: number, type: 'IN' | 'OUT', pos: { lat: number | null; lng: number | null; accuracyM: number | null }) {
  write(QUEUE, [...queued(), { userId, clientId: uuid(), type, at: new Date().toISOString(), ...pos }])
  changed()
}

/** A network failure, as opposed to an answer from the server. */
export const isOffline = (e: unknown) => !(e instanceof ApiError) || e.status === 502 || e.status === 503 || e.status === 504

let syncing: Promise<number> | null = null

/** Sends what this person has waiting (a shared phone keeps each person's own); each punch keeps its id, so sending
 *  twice records it once. Returns how many got an answer. */
export function syncPunches(userId: number): Promise<number> {
  syncing ??= (async () => {
    const waiting = queued().filter((p) => p.userId === userId)
    if (waiting.length === 0) return 0
    let answered = 0
    for (let i = 0; i < waiting.length; i += 20) {
      const batch = waiting.slice(i, i + 20)
      let outcomes: OfflineOutcome[]
      try {
        outcomes = await api<OfflineOutcome[]>('/api/attendance/offline', {
          method: 'POST', body: { punches: batch.map(({ userId: _u, ...p }) => p) },
        })
      } catch (e) {
        if (e instanceof ApiError && e.status === 401) break
        if (isOffline(e)) break
        throw e
      }
      const done = outcomes.filter((o) => o.outcome !== 'RETRY')
      const ids = new Set(done.map((o) => o.clientId))
      write(QUEUE, queued().filter((p) => !ids.has(p.clientId)))
      const now = new Date().toISOString()
      write(SENT, [...done.map((o) => ({ ...batch.find((p) => p.clientId === o.clientId)!, outcome: o.outcome, detail: o.detail, sentAt: now })), ...sent()].slice(0, 20))
      answered += done.length
    }
    changed()
    return answered
  })().finally(() => { syncing = null })
  return syncing
}

export const clearSent = (userId: number) => { write(SENT, sent().filter((p) => p.userId !== userId)); changed() }

/** Sends waiting check-ins when the app starts, when the connection comes back, and every few minutes. */
export function useOfflineSync(userId: number | undefined) {
  useEffect(() => {
    if (userId === undefined) return
    const go = () => { if (navigator.onLine) void syncPunches(userId).catch(() => undefined) }
    go()
    window.addEventListener('online', go)
    const timer = window.setInterval(go, 3 * 60_000)
    return () => { window.removeEventListener('online', go); window.clearInterval(timer) }
  }, [userId])
}

/** The queue as it changes, for the check-in page. */
export function useQueue(userId: number): { waiting: QueuedPunch[]; done: SentPunch[] } {
  const mine = () => ({ waiting: queued().filter((p) => p.userId === userId), done: sent().filter((p) => p.userId === userId) })
  const [state, setState] = useState(mine)
  useEffect(() => {
    const l = () => setState(mine())
    window.addEventListener('hrpay:offline-queue', l)
    window.addEventListener('storage', l)
    return () => { window.removeEventListener('hrpay:offline-queue', l); window.removeEventListener('storage', l) }
  }, [userId]) // eslint-disable-line react-hooks/exhaustive-deps
  return state
}

export function useOnline() {
  const [online, setOnline] = useState(() => navigator.onLine)
  useEffect(() => {
    const on = () => setOnline(true)
    const off = () => setOnline(false)
    window.addEventListener('online', on)
    window.addEventListener('offline', off)
    return () => { window.removeEventListener('online', on); window.removeEventListener('offline', off) }
  }, [])
  return online
}
