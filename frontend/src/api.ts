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
