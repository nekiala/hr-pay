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
import type { ReactNode } from 'react'

function Guard({ permission, children }: { permission?: string; children: ReactNode }) {
  const { can } = useAuth()
  if (permission && !can(permission)) {
    return <p className="notice">You do not have permission to open this page.</p>
  }
  return <>{children}</>
}

export default function App() {
  const { me, loading, can, signOut } = useAuth()

  if (loading) return <p className="center">Loading…</p>
  if (!me) {
    return (
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="*" element={<Navigate to="/login" replace />} />
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
          {can('user.read') && <NavLink to="/users">Users</NavLink>}
          {can('role.read') && <NavLink to="/roles">Roles</NavLink>}
          {(can('site.read') || can('site.manage')) && <NavLink to="/sites">Sites</NavLink>}
          {can('audit.read') && <NavLink to="/audit">Audit log</NavLink>}
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
          <Route path="/users/:id" element={<Guard permission="user.read"><UserFormPage /></Guard>} />
          <Route path="/roles" element={<Guard permission="role.read"><RolesPage /></Guard>} />
          <Route path="/sites" element={<SitesPage />} />
          <Route path="/sites/new" element={<Guard permission="site.manage"><SiteFormPage /></Guard>} />
          <Route path="/sites/:id" element={<SiteFormPage />} />
          <Route path="/audit" element={<Guard permission="audit.read"><AuditPage /></Guard>} />
          <Route path="/login" element={<Navigate to="/" replace />} />
          <Route path="*" element={<p className="notice">Page not found.</p>} />
        </Routes>
      </main>
    </div>
  )
}
