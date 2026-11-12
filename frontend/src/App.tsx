import { Navigate, Route, Routes } from 'react-router-dom'
import { useAuth } from './auth'
import LoginPage from './pages/LoginPage'
import ResetPasswordPage from './pages/ResetPasswordPage'
import HelpPage, { ArticlePage, TicketPage, WhatsNewPage } from './pages/HelpPage'
import SetupPage from './pages/SetupPage'
import DashboardsPage from './pages/DashboardsPage'
import { LegalPage, StatusPage, TermsGate, TrialPage } from './pages/PublicPages'
import { IncidentsPage, LegalAdminPage, SupportDeskPage, TrialsPage } from './pages/PlatformDeskPage'
import ChangePasswordPage from './pages/ChangePasswordPage'
import AccountPage from './pages/AccountPage'
import PersonalDataPage from './pages/PersonalDataPage'
import HomePage from './pages/HomePage'
import UsersPage from './pages/UsersPage'
import UserFormPage from './pages/UserFormPage'
import UserViewPage from './pages/UserViewPage'
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
import MissionSettingsPage from './pages/MissionSettingsPage'
import MissionCheckPage from './pages/MissionCheckPage'
import { CareersOfferPage, CareersPage, CareersWithdrawPage } from './pages/CareersPages'
import JobOffersPage from './pages/JobOffersPage'
import OnboardingPage, { WelcomeFormPage, WelcomePage } from './pages/OnboardingPages'
import TrainingPage, { MyTrainingPage, TrainingSettingsPage } from './pages/TrainingPages'
import SkillsPage, { MySkillsPage } from './pages/SkillsPages'
import TalentPage, { TalentProposalsPage, TalentSettingsPage } from './pages/TalentPages'
import LeavePage from './pages/LeavePage'
import ReportsPage from './pages/ReportsPage'
import RankingsPage from './pages/RankingsPage'
import DisciplinePage from './pages/DisciplinePage'
import StaffRepresentationPage from './pages/StaffRepresentationPage'
import HealthSafetyPage from './pages/HealthSafetyPage'
import PositionsPage from './pages/PositionsPage'
import ObjectivesPage from './pages/ObjectivesPage'
import RecruitmentPage from './pages/RecruitmentPage'
import DisciplineSettingsPage from './pages/DisciplineSettingsPage'
import EmployeeFilePage from './pages/EmployeeFilePage'
import PayrollSettingsPage from './pages/PayrollSettingsPage'
import PayElementsPage from './pages/PayElementsPage'
import PayEntriesPage from './pages/PayEntriesPage'
import QuinzaineSettingsPage from './pages/QuinzaineSettingsPage'
import MatriculeSettingsPage from './pages/MatriculeSettingsPage'
import ReferenceListsPage from './pages/ReferenceListsPage'
import PayrollChecksSettingsPage from './pages/PayrollChecksSettingsPage'
import FirmPage from './pages/FirmPage'
import CompanyAccountantPage from './pages/CompanyAccountantPage'
import StatutoryRatesPage from './pages/StatutoryRatesPage'
import PayRunsPage from './pages/PayRunsPage'
import NewPayRunPage from './pages/NewPayRunPage'
import MonthlyRunPage from './pages/MonthlyRunPage'
import ThirteenthRunPage from './pages/ThirteenthRunPage'
import ThirteenthSettingsPage from './pages/ThirteenthSettingsPage'
import PayslipPage from './pages/PayslipPage'
import LoansPage from './pages/LoansPage'
import MyPayslipsPage from './pages/MyPayslipsPage'
import MyAdvancePage from './pages/MyAdvancePage'
import AdvancesPage from './pages/AdvancesPage'
import LeaveSettingsPage from './pages/LeaveSettingsPage'
import LeavePayRunPage from './pages/LeavePayRunPage'
import QuinzaineRunPage from './pages/QuinzaineRunPage'
import PayrollReportsPage from './pages/PayrollReportsPage'
import PaySimulationPage from './pages/PaySimulationPage'
import OvertimeApprovalsPage from './pages/OvertimeApprovalsPage'
import CorrectionsPage from './pages/CorrectionsPage'
import DecadesPage from './pages/DecadesPage'
import PresencePage from './pages/PresencePage'
import PrivacyPage, { PrivacyGate } from './components/PrivacyNotice'
import EvaluationsPage from './pages/EvaluationsPage'
import EvaluationPage from './pages/EvaluationPage'
import EvaluationSettingsPage from './pages/EvaluationSettingsPage'
import PaymentsPage from './pages/PaymentsPage'
import PaymentFilePage from './pages/PaymentFilePage'
import DeclarationsPage from './pages/DeclarationsPage'
import DeclarationCalendarPage from './pages/DeclarationCalendarPage'
import LegalFilingsPage from './pages/LegalFilingsPage'
import LegalFilingSettingsPage from './pages/LegalFilingSettingsPage'
import { LawChangesAdminPage, LawChangesPublicPage } from './pages/LawChangesPage'
import { SectorPacksAdminPage, SectorPage } from './pages/SectorPacksPage'
import { NtpaySettingsPage } from './pages/NtpayPage'
import { AnnualStatementPage, YearSummariesPage } from './pages/YearSummaryPage'
import OrgChartPage from './pages/OrgChartPage'
import OrgUnitPage from './pages/OrgUnitPage'
import OrgSettingsPage from './pages/OrgSettingsPage'
import { CompanyMailServerPage, PlatformMailServerPage } from './pages/MailServerPage'
import { CompanyWhatsAppPage, PlatformWhatsAppPage } from './pages/WhatsAppPage'
import AnnouncementsPage, { AnnouncementPage } from './pages/AnnouncementsPage'
import CompanyPage from './pages/CompanyPage'
import SystemPage from './pages/SystemPage'
import JobsPage from './pages/JobsPage'
import CompaniesPage from './pages/CompaniesPage'
import PlatformCompanyPage from './pages/PlatformCompanyPage'
import PlatformBillingPage from './pages/PlatformBillingPage'
import BillingPage from './pages/BillingPage'
import LicencePage from './pages/LicencePage'
import DeparturePage from './pages/DeparturePage'
import CorrectPayslipPage from './pages/CorrectPayslipPage'
import PayrollCorrectionsPage, { DeparturesPage } from './pages/PayrollCorrectionsPage'
import AccountingPage from './pages/AccountingPage'
import ComparePage from './pages/ComparePage'
import { useLocation } from 'react-router-dom'
import ImportPage from './pages/ImportPage'
import IntegrationsPage from './pages/IntegrationsPage'
import TimeClocksPage from './pages/TimeClocksPage'
import ExportLayoutsPage from './pages/ExportLayoutsPage'
import LifecyclePage, { LifecycleSettingsPage } from './pages/LifecyclePage'
import PersonLifecyclePage from './pages/PersonLifecyclePage'
import MyDocumentsPage from './pages/MyDocumentsPage'
import { Shell } from './components/Shell'
import type { ReactNode } from 'react'
import { t } from './i18n'

function Guard({ permission, children }: { permission?: string | string[]; children: ReactNode }) {
  const { can } = useAuth()
  if (permission && !(Array.isArray(permission) ? permission.some(can) : can(permission))) {
    return <p className="notice">{t('You do not have permission to open this page.')}</p>
  }
  return <>{children}</>
}

function SimulationRoute() {
  const { can } = useAuth()
  return can('payroll.simulate') || can('payroll.increase') || can('payroll.increase.approve')
    ? <PaySimulationPage />
    : <p className="notice">{t('You do not have permission to open this page.')}</p>
}

function AdvancesRoute() {
  const { can } = useAuth()
  return can('advance.manage') || can('payroll.pay')
    ? <AdvancesPage />
    : <p className="notice">{t('You do not have permission to open this page.')}</p>
}

function FirmRoute() {
  const { me } = useAuth()
  return me?.firmMember && !me.firm
    ? <FirmPage />
    : <p className="notice">{t('This page is for accounting firms.')}</p>
}

function PayrollStaff({ children }: { children: ReactNode }) {
  const { can } = useAuth()
  return can('payroll.run') || can('payroll.approve')
    ? <>{children}</>
    : <p className="notice">{t('You do not have permission to open this page.')}</p>
}

function PayElementsRoute({ page }: { page?: 'rates' | 'thirteenth' }) {
  const { can } = useAuth()
  return can('salary.read') || can('masterdata.manage')
    ? (page === 'rates' ? <StatutoryRatesPage /> : page === 'thirteenth' ? <ThirteenthSettingsPage /> : <PayElementsPage />)
    : <p className="notice">{t('You do not have permission to open this page.')}</p>
}

function PayrollRoute({ page }: { page?: 'loans' | 'monthly' | 'thirteenth' }) {
  const { can } = useAuth()
  if (page === 'loans') {
    return can('loan.manage') || can('payslip.read.all') ? <LoansPage /> : <p className="notice">{t('You do not have permission to open this page.')}</p>
  }
  return can('payroll.run') || can('payroll.approve') || can('payslip.read.all')
    ? (page === 'monthly' ? <MonthlyRunPage /> : page === 'thirteenth' ? <ThirteenthRunPage /> : <PayRunsPage />)
    : <p className="notice">{t('You do not have permission to open this page.')}</p>
}

function PaymentsRoute({ file }: { file?: boolean }) {
  const { can } = useAuth()
  return can('payroll.pay') || can('payslip.read.all')
    ? (file ? <PaymentFilePage /> : <PaymentsPage />)
    : <p className="notice">{t('You do not have permission to open this page.')}</p>
}

function LayoutsRoute() {
  const { can } = useAuth()
  return can('accounting.export') || can('payroll.pay')
    ? <ExportLayoutsPage />
    : <p className="notice">{t('You do not have permission to open this page.')}</p>
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
  // The page an emailed link opens: it works signed out, and setting the password signs every browser out.
  if (location.pathname === '/reset-password') return <Routes><Route path="/reset-password" element={<ResetPasswordPage />} /></Routes>
  // Pages anyone can open, signed in or not: the status page, the terms and the trial request.
  if (location.pathname === '/status' || location.pathname === '/trial' || location.pathname === '/changes' || location.pathname.startsWith('/legal/')
    || location.pathname.startsWith('/mission-check/') || location.pathname.startsWith('/careers/') || location.pathname.startsWith('/welcome-form/')) {
    return (
      <Routes>
        <Route path="/careers/withdraw/:token" element={<CareersWithdrawPage />} />
        <Route path="/welcome-form/:token" element={<WelcomeFormPage />} />
        <Route path="/careers/:slug" element={<CareersPage />} />
        <Route path="/careers/:slug/:id" element={<CareersOfferPage />} />
        <Route path="/mission-check/:token" element={<MissionCheckPage />} />
        <Route path="/status" element={<StatusPage />} />
        <Route path="/changes" element={<LawChangesPublicPage />} />
        <Route path="/trial" element={<TrialPage />} />
        <Route path="/legal/:kind" element={<LegalPage />} />
      </Routes>
    )
  }
  if (loading) return <p className="center">{t('Loading…')}</p>
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
          <Route path="/account" element={<AccountPage />} />
          <Route path="/help" element={<HelpPage />} />
          <Route path="/help/:id" element={<ArticlePage />} />
          <Route path="/support/:id" element={<TicketPage />} />
          <Route path="/whats-new" element={<WhatsNewPage />} />
          <Route path="/setup" element={<Guard permission="company.manage"><SetupPage /></Guard>} />
          <Route path="/dashboards" element={<Guard permission="dashboard.read"><DashboardsPage /></Guard>} />
          <Route path="/platform/support" element={<Guard permission="platform.manage"><SupportDeskPage /></Guard>} />
          <Route path="/platform/support/:id" element={<Guard permission="platform.manage"><TicketPage provider /></Guard>} />
          <Route path="/platform/trials" element={<Guard permission="platform.manage"><TrialsPage /></Guard>} />
          <Route path="/platform/email" element={<Guard permission="platform.manage"><PlatformMailServerPage /></Guard>} />
          <Route path="/platform/whatsapp" element={<Guard permission="platform.manage"><PlatformWhatsAppPage /></Guard>} />
          <Route path="/platform/legal" element={<Guard permission="platform.manage"><LegalAdminPage /></Guard>} />
          <Route path="/platform/status" element={<Guard permission="platform.manage"><IncidentsPage /></Guard>} />
          <Route path="/platform/changes" element={<Guard permission="platform.manage"><LawChangesAdminPage /></Guard>} />
          <Route path="/platform/sector-packs" element={<Guard permission="platform.manage"><SectorPacksAdminPage /></Guard>} />
          <Route path="/users" element={<Guard permission="user.read"><UsersPage /></Guard>} />
          <Route path="/users/new" element={<Guard permission="user.create"><UserFormPage /></Guard>} />
          <Route path="/users/matricule" element={<Guard permission="matricule.manage"><MatriculeSettingsPage /></Guard>} />
          <Route path="/reference-lists" element={<Guard permission="salary.read"><ReferenceListsPage /></Guard>} />
          <Route path="/users/:id/file" element={<Guard permission="salary.read"><EmployeeFilePage /></Guard>} />
          <Route path="/users/:id/attendance" element={<Guard permission="attendance.read.all"><UserAttendancePage /></Guard>} />
          <Route path="/users/:id/lifecycle" element={<Guard permission="lifecycle.manage"><PersonLifecyclePage /></Guard>} />
          <Route path="/lifecycle" element={<Guard permission="lifecycle.manage"><LifecyclePage /></Guard>} />
          <Route path="/lifecycle/settings" element={<Guard permission="lifecycle.manage"><LifecycleSettingsPage /></Guard>} />
          <Route path="/my/documents" element={<MyDocumentsPage />} />
          <Route path="/announcements" element={<AnnouncementsPage />} />
          <Route path="/job-offers" element={<JobOffersPage />} />
          <Route path="/onboarding" element={<OnboardingPage />} />
          <Route path="/training" element={<TrainingPage />} />
          <Route path="/training/settings" element={<TrainingSettingsPage />} />
          <Route path="/my/training" element={<MyTrainingPage />} />
          <Route path="/skills" element={<SkillsPage />} />
          <Route path="/my/skills" element={<MySkillsPage />} />
          <Route path="/talent" element={<TalentPage />} />
          <Route path="/talent/proposals" element={<TalentProposalsPage />} />
          <Route path="/talent/settings" element={<TalentSettingsPage />} />
          <Route path="/welcome" element={<WelcomePage />} />
          <Route path="/announcements/:id" element={<AnnouncementPage />} />
          <Route path="/my/advance" element={<MyAdvancePage />} />
          <Route path="/payroll/advances" element={<AdvancesRoute />} />
          <Route path="/users/:id/departure" element={<PayrollStaff><DeparturePage /></PayrollStaff>} />
          <Route path="/departures" element={<PayrollStaff><DeparturesPage /></PayrollStaff>} />
          <Route path="/users/:id/edit" element={<Guard permission="user.read"><UserFormPage /></Guard>} />
          <Route path="/users/:id" element={<Guard permission="user.read"><UserViewPage /></Guard>} />
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
          <Route path="/missions/settings" element={<Guard permission="mission.manage"><MissionSettingsPage /></Guard>} />
          <Route path="/leave" element={<LeavePage />} />
          <Route path="/reports" element={<ReportsPage />} />
          <Route path="/reports/rankings" element={<Guard permission={['report.hr', 'attendance.read.all', 'attendance.read.team', 'mission.manage', 'discipline.manage', 'discipline.propose']}><RankingsPage /></Guard>} />
          <Route path="/discipline" element={<Guard permission={['discipline.manage', 'discipline.propose']}><DisciplinePage /></Guard>} />
          <Route path="/representation" element={<Guard permission="staff.representation"><StaffRepresentationPage /></Guard>} />
          <Route path="/health" element={<Guard permission="health.safety"><HealthSafetyPage /></Guard>} />
          <Route path="/positions" element={<Guard permission="jobs.manage"><PositionsPage /></Guard>} />
          <Route path="/discipline/settings" element={<Guard permission="discipline.manage"><DisciplineSettingsPage /></Guard>} />
          <Route path="/payroll" element={<PayrollRoute />} />
          <Route path="/payroll/reports" element={<Guard permission="payroll.report"><PayrollReportsPage /></Guard>} />
          <Route path="/payroll/simulate" element={<SimulationRoute />} />
          <Route path="/payroll/payments" element={<PaymentsRoute />} />
          <Route path="/payroll/payments/files/:id" element={<PaymentsRoute file />} />
          <Route path="/payroll/payments/ntpay" element={<NtpaySettingsPage />} />
          <Route path="/payroll/declarations" element={<Guard permission="declaration.manage"><DeclarationsPage /></Guard>} />
          <Route path="/payroll/declarations/calendar" element={<Guard permission="declaration.manage"><DeclarationCalendarPage /></Guard>} />
          <Route path="/payroll/legal-filings" element={<Guard permission="declaration.manage"><LegalFilingsPage /></Guard>} />
          <Route path="/payroll/legal-filings/settings" element={<Guard permission="declaration.manage"><LegalFilingSettingsPage /></Guard>} />
          <Route path="/payroll/year" element={<Guard permission="payslip.read.all"><YearSummariesPage /></Guard>} />
          <Route path="/payroll/year/:userId" element={<Guard permission="payslip.read.all"><AnnualStatementPage /></Guard>} />
          <Route path="/my/year" element={<Guard permission="payslip.read.own"><AnnualStatementPage /></Guard>} />
          <Route path="/payroll/loans" element={<PayrollRoute page="loans" />} />
          <Route path="/payslips/:id" element={<PayslipPage />} />
          <Route path="/payslips/:id/correct" element={<Guard permission="payroll.run"><CorrectPayslipPage /></Guard>} />
          <Route path="/payroll/new" element={<PayrollStaff><NewPayRunPage /></PayrollStaff>} />
          <Route path="/payroll/runs/monthly/:month" element={<PayrollRoute page="monthly" />} />
          <Route path="/payroll/runs/quinzaine/:id" element={<PayrollStaff><QuinzaineRunPage /></PayrollStaff>} />
          <Route path="/payroll/runs/leave-pay/:month" element={<PayrollStaff><LeavePayRunPage /></PayrollStaff>} />
          <Route path="/payroll/runs/thirteenth/:id" element={<PayrollRoute page="thirteenth" />} />
          <Route path="/payroll/runs/corrections" element={<PayrollStaff><PayrollCorrectionsPage /></PayrollStaff>} />
          <Route path="/payroll/thirteenth-month/settings" element={<PayElementsRoute page="thirteenth" />} />
          <Route path="/payroll/checks/settings" element={<PayrollStaff><PayrollChecksSettingsPage /></PayrollStaff>} />
          <Route path="/payroll/quinzaine/settings" element={<PayrollStaff><QuinzaineSettingsPage /></PayrollStaff>} />
          <Route path="/payroll/entries" element={<Guard permission="salary.read"><PayEntriesPage /></Guard>} />
          <Route path="/payroll/compare" element={<PayrollStaff><ComparePage /></PayrollStaff>} />
          <Route path="/payroll/accounting" element={<Guard permission="accounting.export"><AccountingPage /></Guard>} />
          <Route path="/my/payslips" element={<Guard permission="payslip.read.own"><MyPayslipsPage /></Guard>} />
          <Route path="/payroll/elements" element={<PayElementsRoute />} />
          <Route path="/payroll/rates" element={<PayElementsRoute page="rates" />} />
          <Route path="/payroll/settings" element={<Guard permission="masterdata.manage"><PayrollSettingsPage /></Guard>} />
          <Route path="/payroll/sector" element={<Guard permission="masterdata.manage"><SectorPage /></Guard>} />
          <Route path="/leave/settings" element={<Guard permission="leave.manage"><LeaveSettingsPage /></Guard>} />
          <Route path="/evaluations" element={<EvaluationsPage />} />
          <Route path="/objectives" element={<ObjectivesPage />} />
          <Route path="/recruitment" element={<RecruitmentPage />} />
          <Route path="/evaluations/settings" element={<Guard permission="evaluation.manage"><EvaluationSettingsPage /></Guard>} />
          <Route path="/evaluations/:id" element={<EvaluationPage />} />
          <Route path="/overtime/approvals" element={<OvertimeApprovalsPage />} />
          <Route path="/corrections" element={<CorrectionsPage />} />
          <Route path="/attendance/decades" element={<Guard permission="attendance.decade.close"><DecadesPage /></Guard>} />
          <Route path="/presence" element={<PresencePage />} />
          <Route path="/platform/companies" element={<Guard permission="platform.manage"><CompaniesPage /></Guard>} />
          <Route path="/platform/companies/:id" element={<Guard permission="platform.manage"><PlatformCompanyPage /></Guard>} />
          <Route path="/platform/billing" element={<Guard permission="platform.manage"><PlatformBillingPage /></Guard>} />
          <Route path="/billing" element={<Guard permission="subscription.manage"><BillingPage /></Guard>} />
          <Route path="/platform/licence" element={<Guard permission="platform.manage"><LicencePage /></Guard>} />
          <Route path="/system" element={<Guard permission="system.monitor"><SystemPage /></Guard>} />
          <Route path="/jobs" element={<JobsPage />} />
          <Route path="/company" element={<Guard permission="company.manage"><CompanyPage /></Guard>} />
          <Route path="/company/email" element={<Guard permission="company.manage"><CompanyMailServerPage /></Guard>} />
          <Route path="/company/accountant" element={<Guard permission="firm.manage"><CompanyAccountantPage /></Guard>} />
          <Route path="/firm" element={<FirmRoute />} />
          <Route path="/company/whatsapp" element={<Guard permission="company.manage"><CompanyWhatsAppPage /></Guard>} />
          <Route path="/privacy" element={<PrivacyPage />} />
          <Route path="/privacy/requests" element={<Guard permission="privacy.manage"><PersonalDataPage /></Guard>} />
          <Route path="/outbox" element={<Guard permission="audit.read"><OutboxPage /></Guard>} />
          <Route path="/audit" element={<Guard permission="audit.read"><AuditPage /></Guard>} />
          <Route path="/imports" element={<Guard permission="data.import"><ImportPage /></Guard>} />
          <Route path="/integrations" element={<Guard permission="api.manage"><IntegrationsPage /></Guard>} />
          <Route path="/time-clocks" element={<Guard permission="api.manage"><TimeClocksPage /></Guard>} />
          <Route path="/export-layouts" element={<LayoutsRoute />} />
          <Route path="/login" element={<AfterLogin />} />
          <Route path="*" element={<p className="notice">{t('Page not found.')}</p>} />
        </Routes>
      <PrivacyGate />
      <TermsGate />
    </Shell>
  )
}
