import { Navigate, Route, Routes } from 'react-router-dom'
import { useAuth } from './auth'
import LoginPage from './pages/LoginPage'
import ChangePasswordPage from './pages/ChangePasswordPage'
import HomePage from './pages/HomePage'
import UsersPage from './pages/UsersPage'
import UserFormPage from './pages/UserFormPage'
import RolesPage from './pages/RolesPage'
import SitesPage from './pages/SitesPage'
import SiteFormPage from './pages/SiteFormPage'
import AuditPage from './pages/AuditPage'
import SchedulesPage from './pages/SchedulesPage'
import HolidaysPage from './pages/HolidaysPage'
import OvertimePage from './pages/OvertimePage'
import CheckInPage from './pages/CheckInPage'
import KioskPage from './pages/KioskPage'
import OutboxPage from './pages/OutboxPage'
import UserAttendancePage from './pages/UserAttendancePage'
import MissionsPage from './pages/MissionsPage'
import LeavePage from './pages/LeavePage'
import ReportsPage from './pages/ReportsPage'
import EmployeeFilePage from './pages/EmployeeFilePage'
import PayrollSettingsPage from './pages/PayrollSettingsPage'
import PayElementsPage from './pages/PayElementsPage'
import StatutoryRatesPage from './pages/StatutoryRatesPage'
import PayrollRunsPage from './pages/PayrollRunsPage'
import PayslipPage from './pages/PayslipPage'
import LoansPage from './pages/LoansPage'
import MyPayslipsPage from './pages/MyPayslipsPage'
import LeaveSettingsPage from './pages/LeaveSettingsPage'
import OvertimeApprovalsPage from './pages/OvertimeApprovalsPage'
import CorrectionsPage from './pages/CorrectionsPage'
import PresencePage from './pages/PresencePage'
import PrivacyPage, { PrivacyGate } from './components/PrivacyNotice'
import EvaluationsPage from './pages/EvaluationsPage'
import EvaluationPage from './pages/EvaluationPage'
import EvaluationSettingsPage from './pages/EvaluationSettingsPage'
import PaymentsPage from './pages/PaymentsPage'
import PaymentFilePage from './pages/PaymentFilePage'
import DeclarationsPage from './pages/DeclarationsPage'
import { AnnualStatementPage, YearSummariesPage } from './pages/YearSummaryPage'
import OrgChartPage from './pages/OrgChartPage'
import OrgUnitPage from './pages/OrgUnitPage'
import OrgSettingsPage from './pages/OrgSettingsPage'
import CompanyPage from './pages/CompanyPage'
import SystemPage from './pages/SystemPage'
import { useLocation } from 'react-router-dom'
import { Shell } from './components/Shell'
import type { ReactNode } from 'react'

function Guard({ permission, children }: { permission?: string; children: ReactNode }) {
  const { can } = useAuth()
  if (permission && !can(permission)) {
    return <p className="notice">You do not have permission to open this page.</p>
  }
  return <>{children}</>
}

function PayElementsRoute({ page }: { page?: 'rates' }) {
  const { can } = useAuth()
  return can('salary.read') || can('masterdata.manage')
    ? (page === 'rates' ? <StatutoryRatesPage /> : <PayElementsPage />)
    : <p className="notice">You do not have permission to open this page.</p>
}

function PayrollRoute({ page }: { page?: 'loans' }) {
  const { can } = useAuth()
  if (page === 'loans') {
    return can('loan.manage') || can('payslip.read.all') ? <LoansPage /> : <p className="notice">You do not have permission to open this page.</p>
  }
  return can('payroll.run') || can('payroll.approve') || can('payslip.read.all')
    ? <PayrollRunsPage />
    : <p className="notice">You do not have permission to open this page.</p>
}

function PaymentsRoute({ file }: { file?: boolean }) {
  const { can } = useAuth()
  return can('payroll.pay') || can('payslip.read.all')
    ? (file ? <PaymentFilePage /> : <PaymentsPage />)
    : <p className="notice">You do not have permission to open this page.</p>
}

/** After signing in, go back to where the user was heading (for example the check-in link from the QR code). */
function AfterLogin() {
  const next = new URLSearchParams(useLocation().search).get('next')
  return <Navigate to={next && next.startsWith('/') && !next.startsWith('//') ? next : '/'} replace />
}

export default function App() {
  const { me, loading } = useAuth()
  const location = useLocation()

  // The kiosk screen needs no sign-in.
  if (location.pathname === '/kiosk') return <Routes><Route path="/kiosk" element={<KioskPage />} /></Routes>
  if (loading) return <p className="center">Loading…</p>
  if (!me) {
    return (
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="*" element={<Navigate to={`/login?next=${encodeURIComponent(location.pathname + location.search)}`} replace />} />
      </Routes>
    )
  }
  if (me.mustChangePassword) return <ChangePasswordPage forced />

  return (
    <Shell>
        <Routes>
          <Route path="/" element={<HomePage />} />
          <Route path="/account" element={<ChangePasswordPage />} />
          <Route path="/users" element={<Guard permission="user.read"><UsersPage /></Guard>} />
          <Route path="/users/new" element={<Guard permission="user.create"><UserFormPage /></Guard>} />
          <Route path="/users/:id/file" element={<Guard permission="salary.read"><EmployeeFilePage /></Guard>} />
          <Route path="/users/:id/attendance" element={<Guard permission="attendance.read.all"><UserAttendancePage /></Guard>} />
          <Route path="/users/:id" element={<Guard permission="user.read"><UserFormPage /></Guard>} />
          <Route path="/org" element={<OrgChartPage />} />
          <Route path="/org/units/:id" element={<OrgUnitPage />} />
          <Route path="/org/manage" element={<Guard permission="org.manage"><OrgSettingsPage /></Guard>} />
          <Route path="/roles" element={<Guard permission="role.read"><RolesPage /></Guard>} />
          <Route path="/sites" element={<SitesPage />} />
          <Route path="/sites/new" element={<Guard permission="site.manage"><SiteFormPage /></Guard>} />
          <Route path="/sites/:id" element={<SiteFormPage />} />
          <Route path="/schedules" element={<SchedulesPage />} />
          <Route path="/holidays" element={<HolidaysPage />} />
          <Route path="/overtime" element={<Guard permission="overtime_policy.manage"><OvertimePage /></Guard>} />
          <Route path="/check-in" element={<CheckInPage />} />
          <Route path="/missions" element={<MissionsPage />} />
          <Route path="/leave" element={<LeavePage />} />
          <Route path="/reports" element={<ReportsPage />} />
          <Route path="/payroll" element={<PayrollRoute />} />
          <Route path="/payroll/payments" element={<PaymentsRoute />} />
          <Route path="/payroll/payments/files/:id" element={<PaymentsRoute file />} />
          <Route path="/payroll/declarations" element={<Guard permission="declaration.manage"><DeclarationsPage /></Guard>} />
          <Route path="/payroll/year" element={<Guard permission="payslip.read.all"><YearSummariesPage /></Guard>} />
          <Route path="/payroll/year/:userId" element={<Guard permission="payslip.read.all"><AnnualStatementPage /></Guard>} />
          <Route path="/my/year" element={<Guard permission="payslip.read.own"><AnnualStatementPage /></Guard>} />
          <Route path="/payroll/loans" element={<PayrollRoute page="loans" />} />
          <Route path="/payslips/:id" element={<PayslipPage />} />
          <Route path="/my/payslips" element={<Guard permission="payslip.read.own"><MyPayslipsPage /></Guard>} />
          <Route path="/payroll/elements" element={<PayElementsRoute />} />
          <Route path="/payroll/rates" element={<PayElementsRoute page="rates" />} />
          <Route path="/payroll/settings" element={<Guard permission="masterdata.manage"><PayrollSettingsPage /></Guard>} />
          <Route path="/leave/settings" element={<Guard permission="leave.manage"><LeaveSettingsPage /></Guard>} />
          <Route path="/evaluations" element={<EvaluationsPage />} />
          <Route path="/evaluations/settings" element={<Guard permission="evaluation.manage"><EvaluationSettingsPage /></Guard>} />
          <Route path="/evaluations/:id" element={<EvaluationPage />} />
          <Route path="/overtime/approvals" element={<OvertimeApprovalsPage />} />
          <Route path="/corrections" element={<CorrectionsPage />} />
          <Route path="/presence" element={<PresencePage />} />
          <Route path="/system" element={<Guard permission="system.monitor"><SystemPage /></Guard>} />
          <Route path="/company" element={<Guard permission="company.manage"><CompanyPage /></Guard>} />
          <Route path="/privacy" element={<PrivacyPage />} />
          <Route path="/outbox" element={<Guard permission="audit.read"><OutboxPage /></Guard>} />
          <Route path="/audit" element={<Guard permission="audit.read"><AuditPage /></Guard>} />
          <Route path="/login" element={<AfterLogin />} />
          <Route path="*" element={<p className="notice">Page not found.</p>} />
        </Routes>
      <PrivacyGate />
    </Shell>
  )
}
