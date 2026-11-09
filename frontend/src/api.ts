// Small API client: session cookie auth, CSRF token from the XSRF-TOKEN cookie.
import { language, locale, plural, t } from './i18n'

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
  // The server answers (errors included) in the person's language.
  const headers: Record<string, string> = { Accept: 'application/json', 'Accept-Language': language() }
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
    let message = t('Request failed ({a})', { a: response.status })
    let fieldErrors: Record<string, string> = {}
    try {
      const problem = await response.json()
      message = problem.detail ?? message
      fieldErrors = problem.errors ?? {}
    } catch {
      /* not JSON */
    }
    if (response.status === 403 && message.startsWith('Request failed')) message = t('You do not have permission to do this.')
    throw new ApiError(response.status, message, fieldErrors)
  }
  if (response.status === 204) return undefined as T
  const body = await response.json()
  // A long task runs in the background: follow its job, then answer as the request would have.
  if (response.status === 202 && body && typeof body === 'object' && 'job' in body) return followJob<T>(body.job as Job)
  return body as T
}

// ---- background jobs ----------------------------------------------------------------------------------------------

export type JobStatus = 'WAITING' | 'RUNNING' | 'DONE' | 'FAILED' | 'CANCELLED'
export type Job = {
  id: number; type: string; title: string; status: JobStatus; progress: number; step: string | null; requestedBy: string
  createdAt: string; startedAt: string | null; finishedAt: string | null; attempts: number; error: string | null
  errorStatus: number | null; fileName: string | null; fileSize: number | null; existing: boolean; result: unknown
}
/** What a job that makes a file answers: the file to download from My jobs. */
export type JobFileResult = { jobId: number; fileName: string; size: number; download: string }

export const jobFinished = (j: Job) => j.status === 'DONE' || j.status === 'FAILED' || j.status === 'CANCELLED'

/** Sent on window as 'hrpay:job' each time a followed job changes, for the progress card. */
export type JobEvent = CustomEvent<Job>

/** Follows a job until it ends: its answer, or its error as the request would have thrown it. */
export async function followJob<T>(job: Job): Promise<T> {
  let current = job
  let pause = 700
  while (true) {
    window.dispatchEvent(new CustomEvent('hrpay:job', { detail: current }))
    if (current.status === 'DONE') return current.result as T
    if (current.status === 'FAILED') throw new ApiError(current.errorStatus ?? 500, current.error ?? t('The job failed.'))
    if (current.status === 'CANCELLED') throw new ApiError(409, t('The job was cancelled.'))
    await new Promise((resolve) => window.setTimeout(resolve, pause))
    pause = Math.min(3000, pause + 300)
    try {
      current = await api<Job>(`/api/jobs/${current.id}`)
    } catch (e) {
      // A network blip: keep following; signed out or gone: stop.
      if (e instanceof ApiError && (e.status === 401 || e.status === 404)) throw e
    }
  }
}

/** Starts a job that makes a file and downloads the file when it is ready. */
export async function downloadJob(path: string, body?: unknown) {
  const r = await api<JobFileResult>(path, { method: 'POST', body })
  await download(r.download, r.fileName)
  return r
}

export type Me = {
  id: number
  email: string
  firstName: string
  lastName: string
  roles: string[]
  permissions: string[]
  mustChangePassword: boolean
  privacyAccepted: boolean
  /** The company the user works in: its plan, modules and any warning about its plan or licence. */
  company: {
    id: number
    name: string
    mode: 'SAAS' | 'ON_PREMISE'
    plan: string
    modules: Module[]
    maxEmployees: number | null
    employees: number
    locked: boolean
    /** Set when the company is suspended: what everyone is told; reading and exports keep working. */
    readOnly: string | null
    notices: { level: 'warn' | 'danger'; text: string }[]
    /** Country code (CD, CG), the company's default language and its local currency (CDF, XAF). */
    country: string
    language: 'fr' | 'en'
    localCurrency: string
  } | null
  /** A provider administrator looking at another company, read-only, with its consent. */
  support: { companyId: number; companyName: string; expiresAt: string } | null
  /** The language the app shows: the person's own choice, else the company's. */
  language: 'fr' | 'en'
  /** The person's own choice, null to follow the company. */
  ownLanguage: 'fr' | 'en' | null
  /** An accounting firm's member working in a client, with the rights the client gave the firm. */
  firm: { linkId: number; firmName: string; clientId: number; clientName: string } | null
  /** The person belongs to an accounting firm. */
  firmMember: boolean
}

export type Module = 'ATTENDANCE' | 'PAYROLL' | 'EVALUATIONS'
export const MODULE_NAMES: Record<Module, string> = { ATTENDANCE: t('Attendance'), PAYROLL: t('Payroll'), EVALUATIONS: t('Evaluations') }

export type Plan = { code: string; name: string; maxEmployees: number | null }

export type CompanySummary = {
  id: number
  name: string
  countryCode: string
  planCode: string
  planName: string
  maxEmployees: number | null
  modules: Module[]
  status: 'ACTIVE' | 'SUSPENDED'
  employees: number
  createdAt: string
  provider: boolean
  suspendedReason: 'UNPAID' | 'MANUAL' | null
  subscriptionStatus: 'TRIAL' | 'ACTIVE' | 'CANCELLED' | null
  cycle: 'MONTHLY' | 'YEARLY' | null
  trialEnds: string | null
  nextInvoiceOn: string | null
  openInvoices: number
  overdueInvoices: number
  lastSignIn: string | null
  supportOpen: boolean
  kind: 'COMPANY' | 'FIRM'
}

export type Subscription = {
  companyId: number
  status: 'TRIAL' | 'ACTIVE' | 'CANCELLED'
  cycle: 'MONTHLY' | 'YEARLY'
  currency: string
  trialEnds: string | null
  nextInvoiceOn: string | null
  autoSuspend: boolean
  startedAt: string
  cancelledAt: string | null
}

export type Invoice = {
  id: number
  companyId: number
  number: string
  issuedOn: string
  dueOn: string
  periodFrom: string
  periodTo: string
  planName: string
  cycle: 'MONTHLY' | 'YEARLY'
  currency: string
  usdCdf: number | null
  priceUsd: number
  subtotal: number
  taxLabel: string
  taxRate: number
  tax: number
  total: number
  status: 'OPEN' | 'PAID' | 'VOID'
  paid: number
  overdue: boolean
  paidAt: string | null
  voidReason: string | null
  customerName: string
}

export type InvoicePayment = {
  id: number
  invoiceId: number
  invoiceNumber: string
  method: PaymentMethod
  reference: string
  amount: number
  currency: string
  paidOn: string
  note: string | null
  recordedAt: string
  recordedBy: string
}

export type PaymentMethod = 'BANK_TRANSFER' | 'MPESA' | 'ORANGE_MONEY' | 'AIRTEL_MONEY' | 'CASH' | 'OTHER'
export const PAYMENT_METHODS: Record<PaymentMethod, string> = {
  BANK_TRANSFER: t('Bank transfer'), MPESA: t('M-Pesa'), ORANGE_MONEY: t('Orange Money'), AIRTEL_MONEY: t('Airtel Money'), CASH: t('Cash'), OTHER: t('Other'),
}

export type SupportGrant = {
  id: number
  grantedBy: string
  grantedAt: string
  expiresAt: string
  reason: string | null
  revokedAt: string | null
  revokedBy: string | null
  active: boolean
  requests: number
  lastRequestAt: string | null
}

export type SupportRequest = { visitor: string; method: string; path: string; at: string }

export type BillingSettings = {
  trialDays: number
  paymentDays: number
  suspendAfterDays: number
  taxLabel: string
  taxRate: number
  invoicePrefix: string
  bankDetails: string | null
  mobileMoney: string | null
  invoiceNote: string | null
}

export type PlanPrice = { code: string; name: string; maxEmployees: number | null; monthlyPrice: number; yearlyPrice: number }

/** Amounts with two decimals and thousands separators, as on the invoices. */
export const amount = (v: number) => v.toLocaleString(locale(), { minimumFractionDigits: 2, maximumFractionDigits: 2 })
export const longDay = (d: string | null) => d ? new Date(d.length === 10 ? d + 'T00:00:00' : d).toLocaleDateString(locale(), { day: 'numeric', month: 'short', year: 'numeric' }) : '—'

export type LicenceStatus = {
  mode: 'SAAS' | 'ON_PREMISE'
  state: 'VALID' | 'EXPIRING' | 'GRACE' | 'EVALUATION' | 'LOCKED'
  licence: { id: string; licensee: string; issued: string; expires: string; maxEmployees: number | null; maxCompanies: number | null; modules: Module[]; graceDays: number | null } | null
  evaluationEnds: string | null
  locksOn: string | null
  daysLeft: number
  message: string | null
  companies: number
}

export type User = {
  unitId?: number | null
  unitName?: string | null
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
  managerId: number | null
  gradeCategoryId: number | null
  hireDate: string | null
}

export type Page<T> = { items: T[]; page: number; size: number; total: number }

export type Role = {
  id: number
  code: string
  name: string
  description: string | null
  systemRole: boolean
  permissions: string[]
  twoStepRequired: boolean
}

/** What a password (or Google, or Microsoft) sign-in still needs before the person is in. */
export type TwoStepNeeded = { method: 'TOTP' | 'CODE' | null; setup: boolean; codesAvailable: boolean; sentTo: string[] }

export type TwoStepSetup = { method: 'TOTP' | 'CODE'; secret: string; uri: string | null }

export type TwoStepStatus = {
  enabled: boolean
  method: 'TOTP' | 'CODE' | null
  required: boolean
  recoveryCodesLeft: number
  confirmedAt: string | null
  codesAvailable: boolean
}

export type SignedInSession = {
  id: string
  method: string
  ip: string | null
  userAgent: string | null
  signedInAt: string
  lastSeenAt: string
  current: boolean
}

export type DataCorrection = {
  id: number
  userId: number
  name: string
  field: string
  current: string | null
  requested: string
  reason: string | null
  status: 'OPEN' | 'DONE' | 'REFUSED'
  answer: string | null
  createdAt: string
  decidedAt: string | null
  decidedBy: string | null
}

export const CORRECTION_FIELDS: Record<string, string> = {
  NAME: t('Name'), EMAIL: t('Email'), PHONE: t('Phone'), BIRTH_DATE: t('Date of birth'), MARITAL_STATUS: t('Marital status'),
  DEPENDANTS: t('Dependants'), NATIONALITY: t('Nationality'), CNSS_NUMBER: t('CNSS number'), TAX_NUMBER: t('Tax number (NIF)'),
  BANK_ACCOUNT: t('Bank account'), MOBILE_MONEY: t('Mobile money number'), OTHER: t('Something else'),
}

/** Downloads a file the API sends as an attachment. */
export async function download(path: string, fallbackName: string) {
  const response = await fetch(path, { credentials: 'same-origin', headers: { 'Accept-Language': language() } })
  if (!response.ok) {
    let message = t('Download failed ({a})', { a: response.status })
    try { message = (await response.json()).detail ?? message } catch { /* not JSON */ }
    throw new ApiError(response.status, message)
  }
  const name = /filename="([^"]+)"/.exec(response.headers.get('Content-Disposition') ?? '')?.[1] ?? fallbackName
  const url = URL.createObjectURL(await response.blob())
  const a = document.createElement('a')
  a.href = url
  a.download = name
  a.click()
  URL.revokeObjectURL(url)
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
  qrRequired: boolean
  offlineCheckin: OfflineMode
  ipRanges: IpRange[]
}

export type TrustedDevice = {
  id: number
  siteId: number
  name: string
  active: boolean
  createdBy: string | null
  createdAt: string
  lastUsedAt: string | null
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
  warnings?: string[]
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

export const WEEKDAYS = [t('Monday'), t('Tuesday'), t('Wednesday'), t('Thursday'), t('Friday'), t('Saturday'), t('Sunday')]

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
  site: { name: string; timeZone: string; qrRequired: boolean; gpsRequired: boolean; maxAccuracyM: number; offlineCheckin: OfflineMode } | null
  trustedDevice: boolean
  trustedDeviceName: string | null
  today: ClockEventView[]
  mission: { id: number; location: string; startTime: string; endTime: string } | null
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

/** Clock time in the site's time zone, whatever the browser's zone is. */
export const time = (iso: string, timeZone = 'Africa/Kinshasa') =>
  new Date(iso).toLocaleTimeString(locale(), { hour: '2-digit', minute: '2-digit', timeZone })

export type DayStatus = 'LEAVE' | 'FUTURE' | 'NOT_STARTED' | 'IN_PROGRESS' | 'PRESENT' | 'ABSENT' | 'HOLIDAY' | 'REST_DAY' | 'NO_SCHEDULE'

export type DayResult = {
  date: string
  status: DayStatus
  scheduledMinutes: number
  workedMinutes: number
  payableMinutes: number
  lateMinutes: number
  lateDeductedMinutes: number
  earlyMinutes: number
  earlyDeductedMinutes: number
  overtimeMinutes: number
  overtimeKind: 'WEEKDAY' | 'REST_DAY' | 'HOLIDAY' | null
  missingCheckOut: boolean
  firstIn: string | null
  lastOut: string | null
  autoClosed: boolean
  corrected: boolean
  mission: boolean
  entries: DayEntry[]
  leaveType: string | null
}

export type DayEntry = {
  id: number
  type: 'IN' | 'OUT'
  at: string
  source: 'SELF' | 'HR_MANUAL' | 'AUTO_CLOSE' | 'MISSION'
  note: string | null
  createdBy: string
}

export type Mission = {
  id: number
  userId: number
  employeeName: string
  registrationNo: string
  workDate: string
  startTime: string
  endTime: string
  location: string
  purpose: string
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'CANCELLED'
  requestedAt: string
  decidedBy: string | null
  decidedAt: string | null
  decisionNote: string | null
}

export type MonthSummary = {
  month: string
  timeZone: string
  asOf: string
  scheduledMinutesMonth: number
  scheduledMinutesToDate: number
  payableMinutesToDate: number
  workedMinutesToDate: number
  overtimeMinutesToDate: number
  lateDays: number
  absentDays: number
  missingCheckOutDays: number
  earnings: { grossMonthly: number; currency: string; earnedToDate: number; minuteRate: number; overtimeEarned: number } | null
  overtimeApprovedMinutes: number
  overtimePendingMinutes: number
  overtime: OvertimeDay[]
  days: DayResult[]
}

export type Salary = {
  id: number
  userId: number
  effectiveFrom: string
  grossMonthly: number
  currency: string
  note: string | null
  createdBy: string
  createdAt: string
}

export const money = (amount: number, currency: string) =>
  new Intl.NumberFormat(locale(), { style: 'currency', currency, minimumFractionDigits: 2 }).format(amount)

export type OvertimeDay = {
  date: string
  minutes: number
  kind: 'WEEKDAY' | 'REST_DAY' | 'HOLIDAY'
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'AUTOMATIC'
  approvedMinutes: number
  decidedBy: string | null
  note: string | null
}

export type PendingOvertime = {
  userId: number
  employeeName: string
  registrationNo: string
  date: string
  minutes: number
  kind: 'WEEKDAY' | 'REST_DAY' | 'HOLIDAY'
  firstIn: string | null
  lastOut: string | null
}

export type LeaveType = { id: number; code: string; name: string; paid: boolean; usesBalance: boolean; requiresDocument: boolean; maxDays: number | null; active: boolean; payPercent: number }
export type GradeCategory = { id: number; code: string; name: string; annualLeaveDays: number; active: boolean }
export type LeaveYearLeft = { year: number; granted: number; left: number; lapsing: number; lapsesOn: string }
export type AnnualLeave = {
  paid: boolean; grantedThisYear: number; earnedToDate: number; carriedOver: number; takenThisYear: number
  available: number; pending: number; years: LeaveYearLeft[]; rightOpensOn: string | null; carryPercent: number; carryYears: number
}
export type LeaveLapse = { userId: number; employeeName: string; registrationNo: string; leaveYear: number; left: number; lapsing: number; kept: number }
export type LeavePayDue = {
  leaveRequestId: number; userId: number; employeeName: string; registrationNo: string; startDate: string; endDate: string
  workingDays: number; payBy: string
}
export type LeaveAllowance = {
  id: number; leaveRequestId: number; userId: number; employeeName: string; registrationNo: string; startDate: string; endDate: string
  currency: string; days: number; monthlySalary: number; daysPerMonth: number; dailyRate: number; salaryPart: number
  variable12m: number; monthsCounted: number; variablePart: number; gross: number; netShare: number; advance: number; payBy: string
  status: 'CALCULATED' | 'APPROVED' | 'PAID' | 'CANCELLED'; explanation: string[]; calculatedBy: string; calculatedAt: string
  approvedBy: string | null; approvedAt: string | null; paidBy: string | null; paidOn: string | null; paymentReference: string | null
  cancelNote: string | null; months: { month: string; days: number; grossAmount: number; advanceAmount: number }[]
  number: string | null; employeeDeductions: number | null; net: number | null; lines: PayslipLine[]
}
export type QuinzaineLine = {
  id: number; userId: number; employeeName: string; registrationNo: string; number: string | null; currency: string; category: string
  percent: number; baseAmount: number; normalNet: number; workedShare: number; earnedNet: number; amount: number; explanation: string[]; paid: boolean
}
export type QuinzaineRun = {
  id: number; month: string; payDate: string; base: 'NET' | 'BASIC'; status: 'CALCULATED' | 'APPROVED' | 'CLOSED' | 'PAID' | 'CANCELLED'
  skipped: string[]; calculatedBy: string; calculatedAt: string; approvedBy: string | null; approvedAt: string | null
  closedBy: string | null; closedAt: string | null; cancelNote: string | null
  totals: { currency: string; amount: number; employees: number }[]; lines: QuinzaineLine[]
}
export type QuinzaineSettings = { enabled: boolean; base: 'NET' | 'BASIC'; day: number; maxDebtMonths: number }
export type QuinzaineCategory = { id: number; code: string; name: string; quinzainePercent: number | null }
export type QuinzaineEmployee = {
  userId: number; employeeName: string; registrationNo: string; category: string | null; percent: number | null
  refused: boolean; reason: string | null; recordedBy: string | null; recordedAt: string | null
}
export type QuinzaineExclusion = { id: number; month: string; userId: number; employeeName: string; reason: string; recordedBy: string; recordedAt: string }
export type MyQuinzaine = {
  enabled: boolean; day: number; refused: boolean
  payments: { id: number; month: string; payDate: string; status: string; number: string | null; currency: string; amount: number }[]
}
export type LeaveBalance = { leaveTypeId: number; leaveType: string; granted: number; taken: number; pending: number; available: number }
export type LeaveRequest = {
  id: number; userId: number; employeeName: string; registrationNo: string; leaveTypeId: number; leaveType: string
  startDate: string; endDate: string; workingDays: number; reason: string | null
  status: 'PENDING' | 'AWAITING_HR' | 'APPROVED' | 'REJECTED' | 'CANCELLED'; requestedAt: string
  decidedBy: string | null; decidedAt: string | null; decisionNote: string | null
  halfDay: 'AM' | 'PM' | null; requiresDocument: boolean; documents: number; documentCheckedBy: string | null
}
export type LeaveDocument = { id: number; leaveRequestId: number; fileName: string; contentType: string; sizeBytes: number; uploadedBy: string; uploadedAt: string }

/** Reads a chosen file as base64, for JSON uploads. */
export const fileToBase64 = (file: File) => new Promise<string>((resolve, reject) => {
  const reader = new FileReader()
  reader.onload = () => resolve(String(reader.result).replace(/^data:[^,]*,/, ''))
  reader.onerror = () => reject(new Error(t('The file could not be read.')))
  reader.readAsDataURL(file)
})
export type LeaveLedgerEntry = { id: number; leaveType: string; entryDate: string; days: number; kind: string; year: number | null; note: string | null; createdBy: string }
export const days = (n: number) => plural('{a} day', '{a} days', Number(n), { a: Number(n).toLocaleString(locale(), { maximumFractionDigits: 2 }) })

export type Grade = { id: number; categoryId: number; categoryCode: string; categoryName: string; code: string; name: string; baseSalary: number; currency: string; active: boolean; smigCoefficient: number | null }
export type Occupation = { id: number; code: string; name: string; active: boolean }
export type Contract = {
  id: number; userId: number; contractType: 'PERMANENT' | 'FIXED_TERM' | 'INTERN' | 'CONSULTANT'; startDate: string; endDate: string | null
  gradeId: number; gradeCode: string; gradeName: string; categoryId: number; occupationId: number | null; occupationName: string | null
  expatriate: boolean; note: string | null; createdBy: string; createdAt: string
}
export type Profile = {
  birthDate: string | null; gender: 'FEMALE' | 'MALE' | null; nationality: string | null
  maritalStatus: 'SINGLE' | 'MARRIED' | 'DIVORCED' | 'WIDOWED'; cnssNumber: string | null; taxNumber: string | null
  paymentMethod: 'BANK' | 'MOBILE_MONEY' | 'CASH'; bankName: string | null; bankAccount: string | null
  mobileMoneyOperator: string | null; mobileMoneyNumber: string | null; postnom: string | null; commune: string | null
  nursingBreakUntil?: string | null
}
export type Dependant = { id: number; fullName: string; relationship: 'SPOUSE' | 'CHILD'; birthDate: string | null; inCharge: boolean; note: string | null }
export type EmployeeFileView = { contracts: Contract[]; profile: Profile; dependants: Dependant[] }
export const CONTRACT_TYPES = { PERMANENT: t('Permanent'), FIXED_TERM: t('Fixed term'), INTERN: t('Intern'), CONSULTANT: t('Consultant') }

export type PayElementKind = 'EARNING' | 'DEDUCTION' | 'EMPLOYER_CHARGE'
export type PayMethod = 'COMPUTED' | 'FIXED' | 'PERCENT_OF_BASIC' | 'PER_DAY_WORKED' | 'PER_CHILD' | 'MANUAL'
export type PayElement = {
  id: number; code: string; name: string; kind: PayElementKind; method: PayMethod
  defaultAmount: number | null; defaultCurrency: string | null; percent: number | null
  subjectToCnss: boolean; taxableIpr: boolean; iprExemptCapPercent: number | null
  prorated: boolean; defaultForAll: boolean; system: boolean; displayOrder: number; active: boolean
}
export const PAY_METHODS: Record<PayMethod, string> = {
  COMPUTED: t('Calculated by payroll'), FIXED: t('Fixed amount per month'), PERCENT_OF_BASIC: t('% of basic salary'),
  PER_DAY_WORKED: t('Amount per day worked'), PER_CHILD: t('Amount per child in charge'), MANUAL: t('Entered each month'),
}
export const PAY_KINDS: Record<PayElementKind, string> = { EARNING: t('Earning'), DEDUCTION: t('Deduction'), EMPLOYER_CHARGE: t('Employer charge') }
export type PayAssignment = {
  id: number; userId: number; elementId: number; elementCode: string; elementName: string
  amount: number | null; currency: string | null; percent: number | null; excluded: boolean
  startMonth: string; endMonth: string | null; note: string | null; createdBy: string; createdAt: string
}
export type PayEntry = {
  id: number; userId: number; userName: string; elementId: number; elementCode: string; elementName: string
  kind: PayElementKind; month: string; amount: number; currency: string; note: string | null
  createdBy: string; createdAt: string
}

export type PayrollRun = {
  id: number; month: string; status: 'CALCULATED' | 'APPROVED' | 'CLOSED'; usdCdf: number; rateDate: string; rateSource: string
  employeeCount: number; projected: boolean; calculatedBy: string; calculatedAt: string
  approvedBy: string | null; approvedAt: string | null; closedBy: string | null; closedAt: string | null
}
export type PayslipSummary = {
  id: number; userId: number; userName: string; registrationNo: string; number: string | null; currency: string
  gross: number; employeeDeductions: number; net: number; employerCharges: number; warningCount: number
  unitId: number | null; unitName: string | null; directionId: number | null; directionName: string | null
  kind: 'REGULAR' | 'COMPLEMENTARY'; correctsId: number | null
}
export type RunView = {
  run: PayrollRun; payslips: PayslipSummary[]; skipped: string[]
  totals: { currency: string; payslips: number; gross: number; employeeDeductions: number; net: number; employerCharges: number }[]
}
export type PayslipLine = { elementCode: string; label: string; kind: 'EARNING' | 'DEDUCTION' | 'EMPLOYER_CHARGE'; base: number | null; rate: number | null; amount: number; displayOrder: number; loanId: number | null }
export type Payslip = {
  id: number; runId: number; month: string; runStatus: PayrollRun['status']; userId: number; number: string | null; currency: string
  gross: number; cnssBase: number; taxableBase: number; taxableBaseCdf: number; employeeDeductions: number; net: number
  employerCharges: number; scheduledMinutes: number; paidMinutes: number; daysWorked: number; usdCdf: number; rateDate: string
  projected: boolean; snapshot: Record<string, string | number | boolean | null>; warnings: string[]; lines: PayslipLine[]
  kind: 'REGULAR' | 'COMPLEMENTARY'; correctsId: number | null; correctsNumber: string | null; reason: string | null
}

export type DepartureReason = 'RESIGNATION' | 'DISMISSAL' | 'GROSS_MISCONDUCT' | 'REDUNDANCY' | 'END_OF_CONTRACT' | 'MUTUAL_AGREEMENT' | 'RETIREMENT' | 'DEATH'
export const DEPARTURE_REASONS: Record<DepartureReason, string> = {
  RESIGNATION: t('Resignation'), DISMISSAL: t('Dismissal'), GROSS_MISCONDUCT: t('Dismissal for gross misconduct'),
  REDUNDANCY: t('Redundancy (economic reasons)'), END_OF_CONTRACT: t('End of a fixed-term contract'),
  MUTUAL_AGREEMENT: t('Mutual agreement'), RETIREMENT: t('Retirement'), DEATH: t('Death'),
}
export type Notice = 'WORKED' | 'PAID' | 'OWED' | 'NONE'
export const NOTICES: Record<Notice, string> = {
  WORKED: t('Worked'), PAID: t('Not worked, paid by the employer'), OWED: t('Not given by the employee, deducted'), NONE: t('None or waived'),
}
export type NoticeGivenBy = 'EMPLOYER' | 'EMPLOYEE'
export const NOTICE_GIVERS: Record<NoticeGivenBy, string> = { EMPLOYER: t('The employer'), EMPLOYEE: t('The employee') }
export type Departure = {
  id: number | null; userId: number; name: string; registrationNo: string; hireDate: string | null; lastDay: string
  reason: DepartureReason; noticeDate: string | null; noticeGivenBy: NoticeGivenBy | null; noticeEnd: string | null
  notice: Notice; currency: string; basicMonthly: number; dailyRate: number; serviceYears: number
  noticeDays: number; noticeAmount: number; noticeOwed: number; leaveDays: number; leaveAmount: number; severanceAmount: number; endOfCareerAmount: number
  thirteenthAmount: number; total: number; loanBalance: number | null; note: string | null; status: 'DRAFT' | 'CONFIRMED'
  createdBy: string | null; confirmedBy: string | null; confirmedAt: string | null; certificateNo: string | null
  monthStatus: 'OPEN' | PayrollRun['status']; explanation: string[]
}
export type DepartureSummary = { id: number; userId: number; name: string; registrationNo: string; lastDay: string; reason: DepartureReason; status: 'DRAFT' | 'CONFIRMED'; currency: string; total: number }

export type DiffLine = { code: string; label: string; kind: PayslipLine['kind']; before: number; after: number; change: number }
export type CorrectionPreview = {
  payslipId: number; number: string; month: string; userId: number; name: string; currency: string; lines: DiffLine[]
  grossBefore: number; grossAfter: number; netBefore: number; netAfter: number; netChange: number; carried: number
  pending: boolean; carriedBefore: boolean; basicPaid: number | null; basicNow: number | null; warnings: string[]
}
export type CorrectionMode = 'COMPLEMENTARY' | 'NEXT_PAYROLL' | 'REVERSAL'
export type Correction = {
  id: number; payslipId: number; number: string; month: string; userId: number; name: string; mode: CorrectionMode
  grossChange: number; currency: string; targetMonth: string | null; complementaryId: number | null
  complementaryNumber: string | null; net: number | null; status: 'WAITING' | 'APPROVED' | 'CARRIED'; reason: string
  createdBy: string; createdAt: string
}
export type BackPayDue = { payslipId: number; number: string; month: string; userId: number; name: string; currency: string; basicPaid: number; basicNow: number; estimate: number }
export type ElementAccounts = { elementId: number; code: string; name: string; kind: PayslipLine['kind']; expenseAccount: string | null; liabilityAccount: string | null; defaultExpense: string | null; defaultLiability: string | null }
export type Journal = {
  month: string; runStatus: string; journal: string; date: string; reference: string; currency: string; rate: number
  entries: { account: string; label: string; costCentre: string | null; debit: number | null; credit: number | null }[]
  debit: number; credit: number; payslips: number; warnings: string[]
}
export type Figures = { gross: number | null; cnss: number | null; tax: number | null; net: number | null }
export type Comparison = {
  month: string; runStatus: string; tolerance: number; matching: number; different: number; onlyOurs: number; onlyTheirs: number
  oursTotal: Figures; theirsTotal: Figures; warnings: string[]
  rows: { registrationNo: string; name: string | null; currency: string | null; ours: Figures | null; theirs: Figures | null; difference: Figures | null; status: 'MATCH' | 'DIFFERENT' | 'ONLY_OURS' | 'ONLY_THEIRS' }[]
}
export type CertificationStatus = {
  last: { id: number; certifiedBy: string; certifiedOn: string; note: string | null; createdBy: string; createdAt: string } | null
  ratesChangedSince: number; bracketsChangedSince: number
}
export type Loan = {
  id: number; userId: number; userName: string; kind: 'LOAN' | 'ADVANCE'; principal: number; currency: string
  installment: number; startMonth: string; grantedOn: string; status: 'ACTIVE' | 'REPAID' | 'CANCELLED'
  repaid: number; balance: number; note: string | null; createdBy: string; createdAt: string
}

export type EvalStatus = 'DRAFT' | 'SUBMITTED' | 'ACKNOWLEDGED' | 'CONTESTED' | 'APPROVED'
export const EVAL_STATUS: Record<EvalStatus, string> = {
  DRAFT: t('Draft'), SUBMITTED: t('Waiting for the employee'), ACKNOWLEDGED: t('Acknowledged, waiting for HR'),
  CONTESTED: t('Contested'), APPROVED: t('Approved'),
}
export type EvalScore = { criterionId: number; code: string; name: string; source: 'MANAGER' | 'ATTENDANCE'; weight: number; score: number | null; comment: string | null }
export type Evaluation = {
  id: number; userId: number; userName: string; templateId: number; templateName: string; periodStart: string; periodEnd: string
  evaluatorId: number; evaluatorName: string; status: EvalStatus; totalPercent: number | null; managerComment: string | null
  employeeComment: string | null; hrComment: string | null; submittedAt: string | null; answeredAt: string | null
  approvedBy: string | null; approvedAt: string | null; scores: EvalScore[] | null
}
export type EvalCriterion = { id: number; code: string; name: string; description: string | null; source: 'MANAGER' | 'ATTENDANCE'; active: boolean }
export type EvalTemplate = {
  id: number; name: string; frequency: 'MONTHLY' | 'QUARTERLY' | 'SEMI_ANNUAL'; gradeCategoryId: number | null; active: boolean
  orgUnitId: number | null
  criteria: { criterionId: number; code: string; name: string; source: string; weight: number }[]
}
export const FREQUENCY = { MONTHLY: t('Monthly'), QUARTERLY: t('Quarterly'), SEMI_ANNUAL: t('Every six months') }

export type Employer = { name: string; address: string; cnssNumber: string; taxNumber: string; nationalId: string; rccm: string; inppNumber: string; onemNumber: string; bankName: string; bankAccount: string }
export type PayslipSettings = { phone: string | null; email: string | null; website: string | null; legalForm: string | null; footer: string | null; template: 'CLASSIC' | 'COMPACT' | 'LEDGER'; hasLogo: boolean; activity: string | null }

/** The countries a company may be in, by their code. */
export const COUNTRY_NAMES: Record<string, string> = { CD: t('DR Congo'), CG: t('Republic of the Congo'), CM: t('Cameroon'), GA: t('Gabon') }

// ---- phase 15: imports, access keys, webhooks, time clocks, file layouts -----------------------------------------

export type ImportKind = 'EMPLOYEES' | 'CONTRACTS' | 'SALARIES' | 'LEAVE_BALANCES' | 'YEAR_TO_DATE'
export const IMPORT_KINDS: Record<ImportKind, string> = {
  EMPLOYEES: t('Employees'), CONTRACTS: t('Contracts'), SALARIES: t('Salaries'), LEAVE_BALANCES: t('Leave balances'), YEAR_TO_DATE: t('Year-to-date totals'),
}
export type ImportColumn = { key: string; header: string; required: boolean; help: string | null }
export type ImportKindView = { kind: ImportKind; columns: ImportColumn[] }
export type ImportRow = { row: number; registrationNo: string | null; name: string | null; action: string | null; errors: string[]; warnings: string[] }
export type ImportResult = { kind: ImportKind; saved: boolean; rows: number; withErrors: number; problems: string[]; results: ImportRow[] }
export type ImportBatch = { id: number; kind: ImportKind; fileName: string | null; rows: number; createdBy: string; createdAt: string }

export type ApiKey = {
  id: number; name: string; prefix: string; permissions: string[]; createdBy: string; createdAt: string
  expiresOn: string | null; lastUsedAt: string | null; lastUsedIp: string | null; revokedAt: string | null
}
export type Webhook = {
  id: number; url: string; events: string[]; active: boolean; createdBy: string; createdAt: string
  pending: number; failed: number; lastSentAt: string | null
}
export type WebhookDelivery = {
  id: number; event: string; status: 'PENDING' | 'SENT' | 'FAILED'; attempts: number; lastCode: number | null; lastError: string | null
  createdAt: string; sentAt: string | null; nextAttemptAt: string | null
}
export type IntegrationCatalogue = { scopes: Record<string, string>; events: string[] }

export type TimeClock = {
  id: number; companyId: number; serialNo: string; name: string; siteId: number; siteName: string; timeZone: string
  active: boolean; allowedIps: string | null; useStatus: boolean; attlogStamp: string | null; lastSeenAt: string | null; lastIp: string | null
}
export type ClockPunch = {
  id: number; device: string; pin: string; punchedAt: string; person: string | null
  outcome: 'RECORDED' | 'UNKNOWN_PIN' | 'IGNORED'; detail: string | null; receivedAt: string
}

export type LayoutTarget = 'ACCOUNTING' | 'BANK' | 'MOBILE_MONEY' | 'CASH'
export const LAYOUT_TARGETS: Record<LayoutTarget, string> = {
  ACCOUNTING: t('Accounting entries'), BANK: t('Bank transfers'), MOBILE_MONEY: t('Mobile money'), CASH: t('Cash'),
}
export type LayoutColumn = { field: string; header: string; value: string | null }
export type ExportLayout = {
  id: number; target: LayoutTarget; name: string; columns: LayoutColumn[]; separator: string; decimalComma: boolean
  dateFormat: string; headerRow: boolean; encoding: 'UTF-8' | 'WINDOWS-1252'; extension: 'csv' | 'txt'; createdBy: string | null; createdAt: string | null
}

// ---- Phase 16: the employee's life in the company, notifications, the phone app ----

export type OfflineMode = 'OFF' | 'REVIEW' | 'IN_AREA'
export const OFFLINE_MODES: Record<OfflineMode, string> = {
  OFF: t('Not allowed'),
  REVIEW: t('Allowed, each one approved by the manager'),
  IN_AREA: t('Recorded when the location is in the site\'s area, otherwise approved by the manager'),
}

export type DocumentType = { id: number; code: string; name: string; hasExpiry: boolean; remindDays: number; employeeCanUpload: boolean; employeeCanSee: boolean; position: number; active: boolean }
export type DocSource = 'HR' | 'EMPLOYEE' | 'SIGNED' | 'CERTIFICATE'
export type EmployeeDocument = {
  id: number; userId: number; typeId: number; typeCode: string; typeName: string; fileName: string; contentType: string; sizeBytes: number
  sha256: string; issuedOn: string | null; expiresOn: string | null; note: string | null; source: DocSource; uploadedBy: string; uploadedAt: string; employeeCanSee: boolean
}
export const DOC_SOURCES: Record<DocSource, string> = { HR: t('Filed by HR'), EMPLOYEE: t('Sent by the employee'), SIGNED: t('Signed in HR Pay'), CERTIFICATE: t('Certificate') }

export type ChecklistKind = 'HIRING' | 'DEPARTURE'
export const CHECKLIST_KINDS: Record<ChecklistKind, string> = { HIRING: t('Hiring'), DEPARTURE: t('Departure') }
export type ChecklistAction = 'DOCUMENT' | 'EQUIPMENT' | 'CONTRACT_SIGNED' | 'SETTLEMENT' | 'ACCOUNT_CLOSED'
export const CHECKLIST_ACTIONS: Record<ChecklistAction, string> = {
  DOCUMENT: t('A document of this type is filed'),
  EQUIPMENT: t('Equipment: lent on hiring, all back on departure'),
  CONTRACT_SIGNED: t('A contract is signed'),
  SETTLEMENT: t('The final settlement is confirmed'),
  ACCOUNT_CLOSED: t('The account is closed'),
}
export type ChecklistStep = { id: number; kind: ChecklistKind; position: number; label: string; documentTypeId: number | null; action: ChecklistAction | null; active: boolean }
export type ChecklistItem = { id: number; position: number; label: string; documentTypeId: number | null; action: ChecklistAction | null; done: boolean; auto: boolean; doneAt: string | null; doneBy: string | null; note: string | null }
export type Checklist = { id: number; userId: number; kind: ChecklistKind; status: 'OPEN' | 'DONE' | 'CANCELLED'; dueOn: string | null; startedBy: string; startedAt: string; closedAt: string | null; items: ChecklistItem[] }
export type OpenChecklist = { id: number; userId: number; name: string; registrationNo: string; kind: ChecklistKind; dueOn: string | null; startedAt: string; done: number; total: number }
export type Equipment = { id: number; userId: number; kind: string; description: string | null; serialNo: string | null; issuedOn: string; returnedOn: string | null; returnedTo: string | null; note: string | null }
export type EquipmentOut = { id: number; userId: number; name: string; kind: string; description: string | null; serialNo: string | null; issuedOn: string }
export type ExpiringDocument = { id: number; userId: number; name: string; registrationNo: string; typeName: string; expiresOn: string }

export type LetterKind = 'CONTRACT' | 'LETTER'
export const LETTER_KINDS: Record<LetterKind, string> = { CONTRACT: t('Contract'), LETTER: t('Letter') }
export type LetterStatus = 'DRAFT' | 'SENT' | 'SIGNED' | 'DECLINED' | 'CANCELLED'
export const LETTER_STATUS: Record<LetterStatus, [string, string]> = {
  DRAFT: [t('Draft'), 'badge'], SENT: [t('Waiting for the signature'), 'badge warn'], SIGNED: [t('Signed'), 'badge ok'],
  DECLINED: [t('Refused'), 'badge off'], CANCELLED: [t('Cancelled'), 'badge'],
}
export type LetterTemplate = { id: number; code: string; name: string; kind: LetterKind; body: string; requiresSignature: boolean; documentTypeId: number | null; active: boolean; updatedBy: string | null; updatedAt: string }
export type Letter = {
  id: number; userId: number; employeeName: string; templateId: number | null; kind: LetterKind; number: string | null; title: string; body: string | null
  requiresSignature: boolean; status: LetterStatus; sha256: string | null; sentBy: string | null; sentByName: string | null; sentAt: string | null
  signedSha256: string | null; signedAt: string | null; signedName: string | null; signedIp: string | null; declinedReason: string | null; documentId: number | null; createdBy: string | null; createdAt: string
}
export type LetterDraft = { title: string; body: string; missing: string[] }
export type CodeSent = { challengeId: string; channels: string[]; expiresAt: string }

export type CertificateKind = 'WORK' | 'SALARY'
export const CERTIFICATE_KINDS: Record<CertificateKind, string> = { WORK: t('Work certificate'), SALARY: t('Salary certificate') }
export type CertificateStatus = 'PENDING' | 'ISSUED' | 'REJECTED' | 'CANCELLED'
export const CERTIFICATE_STATUS: Record<CertificateStatus, [string, string]> = {
  PENDING: [t('Asked'), 'badge warn'], ISSUED: [t('Issued'), 'badge ok'], REJECTED: [t('Refused'), 'badge off'], CANCELLED: [t('Withdrawn'), 'badge'],
}
export type CertificateRequest = {
  id: number; userId: number; employeeName: string; registrationNo: string; kind: CertificateKind; purpose: string | null; status: CertificateStatus
  number: string | null; documentId: number | null; requestedBy: string; requestedAt: string; decidedBy: string | null; decidedAt: string | null; reason: string | null
}
export type LifecycleOverview = { checklists: OpenChecklist[]; expiring: ExpiringDocument[]; certificates: CertificateRequest[]; letters: Letter[]; equipment: EquipmentOut[] }
export type PersonLifecycle = { checklists: Checklist[]; documents: EmployeeDocument[]; equipment: Equipment[]; letters: Letter[]; certificates: CertificateRequest[] }
export type MyDocuments = { documents: EmployeeDocument[]; letters: Letter[]; certificates: CertificateRequest[]; uploadTypes: DocumentType[] }

export type NotificationItem = { id: number; kind: string; title: string; body: string | null; link: string | null; createdAt: string; readAt: string | null }
export type Inbox = { unread: number; items: NotificationItem[] }
export type PushSettings = { enabled: boolean; publicKey: string | null; phones: { id: number; userAgent: string | null; createdAt: string; lastOkAt: string | null }[] }
export type OfflineOutcome = { clientId: string; outcome: 'RECORDED' | 'REVIEW' | 'REFUSED' | 'RETRY'; detail: string | null }

/** Opens a file from HR Pay in a new tab (a PDF or a picture), read with the session; the tab opens first so no popup blocker stops it. */
export async function openFile(path: string) {
  const tab = window.open('', '_blank')
  try {
    const response = await fetch(path, { credentials: 'same-origin', headers: { 'Accept-Language': language() } })
    if (!response.ok) {
      let message = t('Download failed ({a})', { a: response.status })
      try { message = (await response.json()).detail ?? message } catch { /* not JSON */ }
      throw new ApiError(response.status, message)
    }
    const url = URL.createObjectURL(await response.blob())
    if (tab) tab.location.href = url
    else window.location.href = url
    setTimeout(() => URL.revokeObjectURL(url), 60_000)
  } catch (e) {
    tab?.close()
    throw e
  }
}

export const fileSize = (bytes: number) => bytes < 1024 * 1024
  ? t('{a} KB', { a: Math.max(1, Math.round(bytes / 1024)) })
  : t('{a} MB', { a: (bytes / 1024 / 1024).toLocaleString(locale(), { maximumFractionDigits: 1 }) })

// ---- pay runs: every type in one list ------------------------------------------------------------------------------
export type PayRunType = 'MONTHLY' | 'QUINZAINE' | 'LEAVE_PAY' | 'THIRTEENTH' | 'CORRECTION' | 'SETTLEMENT'
export type PayRunStage = 'TO_APPROVE' | 'TO_CLOSE' | 'TO_PAY' | 'DONE' | 'CANCELLED'
export type PayRunRow = {
  type: PayRunType; key: string; month: string; payDate: string | null; title: string; status: string; stage: PayRunStage
  people: number; totals: { currency: string; amount: number }[]; calculatedBy: string | null; calculatedAt: string | null
  approvedBy: string | null
}
export type PayRunNotice = { level: 'WARN' | 'INFO'; text: string; type: PayRunType; key: string }
export type PayRunMonth = { month: string; runs: PayRunRow[]; notices: PayRunNotice[] }
export type PayRunTypeInfo = { type: PayRunType; available: boolean }
export type ThirteenthSettings = { enabled: boolean; base: 'AVERAGE_BASIC' | 'LAST_BASIC'; fallbackPercent: number; payMonth: number; prorate: boolean }
export type ThirteenthLine = {
  id: number; userId: number; employeeName: string; registrationNo: string; currency: string; scorePercent: number
  scoreSource: 'EVALUATIONS' | 'FALLBACK'; evaluations: number; base: number; monthsWorked: number; amount: number
}
export type ThirteenthRun = {
  id: number; year: number; month: string; base: ThirteenthSettings['base']; fallbackPercent: number; prorate: boolean
  status: 'CALCULATED' | 'APPROVED' | 'PAID' | 'CANCELLED'; skipped: string[]; excluded: { userId: number; name: string; reason: string }[]
  calculatedBy: string; calculatedAt: string; approvedBy: string | null; approvedAt: string | null; paidAt: string | null
  cancelNote: string | null; totals: { currency: string; amount: number; employees: number }[]; lines: ThirteenthLine[]
}

/** Where each pay run opens. */
export const payRunLink = (r: { type: PayRunType; key: string }) => ({
  MONTHLY: `/payroll/runs/monthly/${r.key}`, QUINZAINE: `/payroll/runs/quinzaine/${r.key}`, LEAVE_PAY: `/payroll/runs/leave-pay/${r.key}`,
  THIRTEENTH: `/payroll/runs/thirteenth/${r.key}`, CORRECTION: '/payroll/runs/corrections', SETTLEMENT: `/users/${r.key}/departure`,
}[r.type])

export type AdvanceSettings = { enabled: boolean; approval: 'AUTO' | 'HR'; maxPerMonth: number; closingDays: number; minServiceDays: number; maxDebtMonths: number }
export type AdvanceCategory = { id: number; code: string; name: string; advancePercent: number | null }
export type AdvanceAvailability = {
  open: boolean; reason: string | null; month: string; currency: string | null; normalNet: number | null; earnedNet: number | null
  percent: number | null; quinzaine: number | null; taken: number | null; available: number; requestsLeft: number; explanation: string[]
}
export type SalaryAdvance = {
  id: number; userId: number; employeeName: string; registrationNo: string | null; month: string; number: string | null
  amount: number; currency: string; available: number; earnedNet: number; percent: number
  status: 'REQUESTED' | 'APPROVED' | 'PAID' | 'REJECTED' | 'CANCELLED'; signedName: string; signedAt: string; signedChannels: string | null
  sha256: string; decidedBy: string | null; decidedAt: string | null; decisionNote: string | null; createdAt: string; paid: boolean
}
export type MyAdvances = { settings: AdvanceSettings; availability: AdvanceAvailability; advances: SalaryAdvance[] }
export type AdvanceCodeSent = { challengeId: string; channels: string[]; expiresAt: string; receipt: string }
