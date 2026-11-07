import { NavLink, Navigate, Route, Routes } from 'react-router-dom'
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
import LeaveSettingsPage from './pages/LeaveSettingsPage'
import OvertimeApprovalsPage from './pages/OvertimeApprovalsPage'
import { useLocation } from 'react-router-dom'
import type { ReactNode } from 'react'

function Guard({ permission, children }: { permission?: string; children: ReactNode }) {
  const { can } = useAuth()
  if (permission && !can(permission)) {
    return <p className="notice">You do not have permission to open this page.</p>
  }
  return <>{children}</>
}

/** After signing in, go back to where the user was heading (for example the check-in link from the QR code). */
function AfterLogin() {
  const next = new URLSearchParams(useLocation().search).get('next')
  return <Navigate to={next && next.startsWith('/') && !next.startsWith('//') ? next : '/'} replace />
}

export default function App() {
  const { me, loading, can, signOut } = useAuth()
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
    <div className="shell">
      <header className="topbar">
        <span className="brand">HR Pay</span>
        <nav>
          <NavLink to="/" end>Home</NavLink>
          <NavLink to="/check-in">Check in</NavLink>
          {(can('leave.request') || can('leave.manage') || can('leave.approve.team')) && <NavLink to="/leave" end>Leave</NavLink>}
          {(can('mission.request') || can('mission.approve.all') || can('mission.approve.team')) && <NavLink to="/missions">Missions</NavLink>}
          {can('user.read') && <NavLink to="/users">Users</NavLink>}
          {can('role.read') && <NavLink to="/roles">Roles</NavLink>}
          {(can('site.read') || can('site.manage')) && <NavLink to="/sites">Sites</NavLink>}
          {(can('schedule.manage') || can('user.read')) && <NavLink to="/schedules">Schedules</NavLink>}
          <NavLink to="/holidays">Holidays</NavLink>
          {(can('overtime.approve.all') || can('overtime.approve.team')) && <NavLink to="/overtime/approvals">Approve overtime</NavLink>}
          {can('overtime_policy.manage') && <NavLink to="/overtime" end>Overtime rules</NavLink>}
          {can('masterdata.manage') && <NavLink to="/payroll/settings">Grades</NavLink>}
          {(can('report.attendance.export') || can('report.finance.export')) && <NavLink to="/reports">Reports</NavLink>}
          {can('audit.read') && <NavLink to="/audit">Audit log</NavLink>}
          {can('audit.read') && <NavLink to="/outbox">Messages</NavLink>}
        </nav>
        <span className="who">
          <NavLink to="/account">{me.firstName} {me.lastName}</NavLink>
          <button className="link" onClick={() => void signOut()}>Sign out</button>
        </span>
      </header>
      <main>
        <Routes>
          <Route path="/" element={<HomePage />} />
          <Route path="/account" element={<ChangePasswordPage />} />
          <Route path="/users" element={<Guard permission="user.read"><UsersPage /></Guard>} />
          <Route path="/users/new" element={<Guard permission="user.create"><UserFormPage /></Guard>} />
          <Route path="/users/:id/file" element={<Guard permission="salary.read"><EmployeeFilePage /></Guard>} />
          <Route path="/users/:id/attendance" element={<Guard permission="attendance.read.all"><UserAttendancePage /></Guard>} />
          <Route path="/users/:id" element={<Guard permission="user.read"><UserFormPage /></Guard>} />
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
          <Route path="/payroll/settings" element={<Guard permission="masterdata.manage"><PayrollSettingsPage /></Guard>} />
          <Route path="/leave/settings" element={<Guard permission="leave.manage"><LeaveSettingsPage /></Guard>} />
          <Route path="/overtime/approvals" element={<OvertimeApprovalsPage />} />
          <Route path="/outbox" element={<Guard permission="audit.read"><OutboxPage /></Guard>} />
          <Route path="/audit" element={<Guard permission="audit.read"><AuditPage /></Guard>} />
          <Route path="/login" element={<AfterLogin />} />
          <Route path="*" element={<p className="notice">Page not found.</p>} />
        </Routes>
      </main>
    </div>
  )
}
