// Small API client: session cookie auth, CSRF token from the XSRF-TOKEN cookie.

export class ApiError extends Error {
  status: number
  fieldErrors: Record<string, string>
  constructor(status: number, message: string, fieldErrors: Record<string, string> = {}) {
    super(message)
    this.status = status
    this.fieldErrors = fieldErrors
  }
}

function csrfToken(): string | undefined {
  return document.cookie
    .split('; ')
    .find((c) => c.startsWith('XSRF-TOKEN='))
    ?.split('=')[1]
}

let csrfReady: Promise<void> | null = null
async function ensureCsrf() {
  if (csrfToken()) return
  csrfReady ??= fetch('/api/auth/csrf', { credentials: 'same-origin' }).then(() => undefined)
  await csrfReady
  csrfReady = null
}

export async function api<T>(path: string, options: { method?: string; body?: unknown } = {}): Promise<T> {
  const method = options.method ?? 'GET'
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (method !== 'GET') {
    await ensureCsrf()
    const token = csrfToken()
    if (token) headers['X-XSRF-TOKEN'] = decodeURIComponent(token)
  }
  if (options.body !== undefined) headers['Content-Type'] = 'application/json'
  const response = await fetch(path, {
    method,
    headers,
    credentials: 'same-origin',
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
  })
  if (response.status === 401 && !path.startsWith('/api/auth/')) {
    window.dispatchEvent(new Event('hrpay:signed-out'))
  }
  if (!response.ok) {
    let message = `Request failed (${response.status})`
    let fieldErrors: Record<string, string> = {}
    try {
      const problem = await response.json()
      message = problem.detail ?? message
      fieldErrors = problem.errors ?? {}
    } catch {
      /* not JSON */
    }
    if (response.status === 403 && message.startsWith('Request failed')) message = 'You do not have permission to do this.'
    throw new ApiError(response.status, message, fieldErrors)
  }
  if (response.status === 204) return undefined as T
  return (await response.json()) as T
}

export type Me = {
  id: number
  email: string
  firstName: string
  lastName: string
  roles: string[]
  permissions: string[]
  mustChangePassword: boolean
}

export type User = {
  id: number
  email: string
  registrationNo: string
  phone: string
  firstName: string
  lastName: string
  siteId: number | null
  siteName: string | null
  status: 'ACTIVE' | 'DISABLED'
  roles: string[]
  hasPassword: boolean
  lastLoginAt: string | null
  createdAt: string
}

export type Page<T> = { items: T[]; page: number; size: number; total: number }

export type Role = {
  id: number
  code: string
  name: string
  description: string | null
  systemRole: boolean
  permissions: string[]
}

export type Permission = { code: string; category: string; description: string }

export type IpRange = { id?: number; cidr: string; label: string | null }

export type Site = {
  id: number
  code: string
  name: string
  countryCode: string
  timeZone: string
  centerLat: number | null
  centerLng: number | null
  radiusM: number | null
  gpsRequired: boolean
  maxAccuracyM: number
  active: boolean
  ipRanges: IpRange[]
}

export type AuditEntry = {
  id: number
  occurredAt: string
  actor: string | null
  action: string
  entityType: string | null
  entityId: string | null
  details: string | null
  ip: string | null
}

export type ScheduleDay = {
  weekday: number
  workday: boolean
  startTime: string | null
  endTime: string | null
  lunchEnabled: boolean
  lunchStart: string | null
  lunchMinutes: number | null
  lunchPaid: boolean
  scheduledMinutes?: number
}

export type Schedule = {
  id: number
  code: string
  name: string
  graceMinutes: number
  deductLateArrival: boolean
  deductEarlyDeparture: boolean
  active: boolean
  weeklyMinutes: number
  days: ScheduleDay[]
}

export type ScheduleAssignment = { id: number; scheduleId: number; scheduleName: string; effectiveFrom: string }

export type HolidayOccurrence = {
  id: number
  definitionId: number
  name: string
  year: number
  officialDate: string
  observedDate: string
  moved: boolean
  paid: boolean
  announcementRef: string | null
  setBy: string | null
  setAt: string | null
}

export type HolidayDefinition = {
  id: number
  countryCode: string
  name: string
  usualMonth: number
  usualDay: number
  paid: boolean
  active: boolean
}

export type OvertimeTier = { kind: 'WEEKDAY' | 'REST_DAY' | 'HOLIDAY'; fromHour: number; toHour: number | null; multiplier: number }

export type OvertimePolicy = {
  id: number
  countryCode: string
  effectiveFrom: string
  allowed: boolean
  requiresApproval: boolean
  roundingMinutes: number
  maxWeeklyOvertimeMinutes: number | null
  tiers: OvertimeTier[]
}

export const WEEKDAYS = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday']

export function hours(minutes: number) {
  const h = Math.floor(minutes / 60)
  const m = minutes % 60
  return m ? `${h} h ${String(m).padStart(2, '0')}` : `${h} h`
}

export type ClockEventView = {
  id: number
  type: 'IN' | 'OUT'
  occurredAt: string
  workDate: string
  source: 'SELF' | 'HR_MANUAL' | 'AUTO_CLOSE' | 'MISSION'
  qrVerified: boolean
  trustedDevice: boolean
  note: string | null
}

export type AttendanceStatus = {
  checkedIn: boolean
  since: string | null
  site: { name: string; timeZone: string; qrRequired: boolean; gpsRequired: boolean; maxAccuracyM: number } | null
  trustedDevice: boolean
  trustedDeviceName: string | null
  today: ClockEventView[]
}

export type StartResponse = {
  status: 'CHECKED_IN' | 'CODE_SENT'
  event: ClockEventView | null
  challengeId: string | null
  channels: string[] | null
  expiresAt: string | null
}

export type Position = { lat: number | null; lng: number | null; accuracyM: number | null }

/** Asks the browser for a fresh, precise position. Resolves with nulls if refused or unavailable. */
export function currentPosition(): Promise<Position> {
  return new Promise((resolve) => {
    if (!navigator.geolocation) return resolve({ lat: null, lng: null, accuracyM: null })
    navigator.geolocation.getCurrentPosition(
      (p) => resolve({ lat: Number(p.coords.latitude.toFixed(6)), lng: Number(p.coords.longitude.toFixed(6)), accuracyM: Math.round(p.coords.accuracy) }),
      () => resolve({ lat: null, lng: null, accuracyM: null }),
      { enableHighAccuracy: true, timeout: 15000, maximumAge: 0 },
    )
  })
}

export const time = (iso: string) => new Date(iso).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
