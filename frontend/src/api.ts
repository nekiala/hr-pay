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
}

export type Subscription = {
  companyId: number
  status: 'TRIAL' | 'ACTIVE' | 'CANCELLED'
  cycle: 'MONTHLY' | 'YEARLY'
  currency: 'USD' | 'CDF'
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
  currency: 'USD' | 'CDF'
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
  site: { name: string; timeZone: string; qrRequired: boolean; gpsRequired: boolean; maxAccuracyM: number } | null
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
  currency: 'USD' | 'CDF'
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

export type Grade = { id: number; categoryId: number; categoryCode: string; categoryName: string; code: string; name: string; baseSalary: number; currency: 'USD' | 'CDF'; active: boolean }
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
  mobileMoneyOperator: string | null; mobileMoneyNumber: string | null
}
export type Dependant = { id: number; fullName: string; relationship: 'SPOUSE' | 'CHILD'; birthDate: string | null; inCharge: boolean; note: string | null }
export type EmployeeFileView = { contracts: Contract[]; profile: Profile; dependants: Dependant[] }
export const CONTRACT_TYPES = { PERMANENT: t('Permanent'), FIXED_TERM: t('Fixed term'), INTERN: t('Intern'), CONSULTANT: t('Consultant') }

export type PayElementKind = 'EARNING' | 'DEDUCTION' | 'EMPLOYER_CHARGE'
export type PayMethod = 'COMPUTED' | 'FIXED' | 'PERCENT_OF_BASIC' | 'PER_DAY_WORKED' | 'PER_CHILD' | 'MANUAL'
export type PayElement = {
  id: number; code: string; name: string; kind: PayElementKind; method: PayMethod
  defaultAmount: number | null; defaultCurrency: 'USD' | 'CDF' | null; percent: number | null
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
  amount: number | null; currency: 'USD' | 'CDF' | null; percent: number | null; excluded: boolean
  startMonth: string; endMonth: string | null; note: string | null; createdBy: string; createdAt: string
}
export type PayEntry = {
  id: number; userId: number; userName: string; elementId: number; elementCode: string; elementName: string
  kind: PayElementKind; month: string; amount: number; currency: 'USD' | 'CDF'; note: string | null
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
}
export type Loan = {
  id: number; userId: number; userName: string; kind: 'LOAN' | 'ADVANCE'; principal: number; currency: 'USD' | 'CDF'
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
export type PayslipSettings = { phone: string | null; email: string | null; website: string | null; legalForm: string | null; footer: string | null; template: 'CLASSIC' | 'COMPACT' | 'LEDGER'; hasLogo: boolean }
