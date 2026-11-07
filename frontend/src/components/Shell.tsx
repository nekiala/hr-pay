import { useEffect, useRef, useState, type ComponentType, type ReactNode } from 'react'
import { NavLink, useLocation } from 'react-router-dom'
import {
  Activity, Banknote, Briefcase, Building2, Calculator, CalendarClock, CalendarDays, ChartColumn, ChevronDown, Clock,
  FileSpreadsheet, Flag, HandCoins, House, KeyRound, Landmark, Layers, LogOut, Mail, MapPin, Menu, Monitor, Moon, Network,
  Percent, PencilLine, ReceiptText, ScrollText, ShieldCheck, SlidersHorizontal, Star, Sun, Timer, UserCheck, UserRound, Users, X,
} from 'lucide-react'
import { useAuth } from '../auth'

type Icon = ComponentType<{ size?: number; 'aria-hidden'?: boolean }>
type Item = { to: string; label: string; icon: Icon; end?: boolean; show: boolean }

/** The sidebar's links, grouped by what people come to do; each shows only to those allowed to open it. */
function useNav(): { title: string; items: Item[] }[] {
  const { can } = useAuth()
  const any = (...p: string[]) => p.some(can)
  return [
    {
      title: 'My work',
      items: [
        { to: '/', label: 'Home', icon: House, end: true, show: true },
        { to: '/check-in', label: 'Check in', icon: Clock, show: true },
        { to: '/leave', label: 'Leave', icon: CalendarDays, end: true, show: any('leave.request', 'leave.manage', 'leave.approve.team') },
        { to: '/missions', label: 'Missions', icon: Briefcase, show: any('mission.request', 'mission.approve.all', 'mission.approve.team') },
        { to: '/corrections', label: 'Corrections', icon: PencilLine, show: any('attendance.correct.request', 'attendance.correct.propose', 'attendance.correct') },
        { to: '/my/payslips', label: 'My payslips', icon: ReceiptText, show: can('payslip.read.own') },
      ],
    },
    {
      title: 'Team',
      items: [
        { to: '/presence', label: 'Presence', icon: UserCheck, show: any('attendance.read.all', 'attendance.read.team') },
        { to: '/overtime/approvals', label: 'Approve overtime', icon: Timer, show: any('overtime.approve.all', 'overtime.approve.team') },
        { to: '/evaluations', label: 'Evaluations', icon: Star, end: true, show: true },
        { to: '/org', label: 'Org chart', icon: Network, show: true },
      ],
    },
    {
      title: 'People',
      items: [
        { to: '/users', label: 'Users', icon: Users, show: can('user.read') },
        { to: '/schedules', label: 'Schedules', icon: CalendarClock, show: any('schedule.manage', 'user.read') },
        { to: '/holidays', label: 'Holidays', icon: Flag, show: true },
        { to: '/sites', label: 'Sites', icon: MapPin, show: any('site.read', 'site.manage') },
      ],
    },
    {
      title: 'Payroll',
      items: [
        { to: '/payroll', label: 'Payroll', icon: Calculator, end: true, show: any('payroll.run', 'payroll.approve', 'payslip.read.all') },
        { to: '/payroll/payments', label: 'Payments', icon: Banknote, show: any('payroll.pay', 'payslip.read.all') },
        { to: '/payroll/declarations', label: 'Declarations', icon: Landmark, show: can('declaration.manage') },
        { to: '/payroll/year', label: 'Year summary', icon: ChartColumn, end: true, show: can('payslip.read.all') },
        { to: '/payroll/loans', label: 'Loans', icon: HandCoins, show: any('loan.manage', 'payslip.read.all') },
        { to: '/reports', label: 'Reports', icon: FileSpreadsheet, show: any('report.attendance.export', 'report.finance.export') },
      ],
    },
    {
      title: 'Settings',
      items: [
        { to: '/company', label: 'Company', icon: Building2, show: can('company.manage') },
        { to: '/payroll/settings', label: 'Grades', icon: Layers, show: can('masterdata.manage') },
        { to: '/payroll/elements', label: 'Pay elements', icon: SlidersHorizontal, show: any('salary.read', 'masterdata.manage') },
        { to: '/payroll/rates', label: 'Rates', icon: Percent, show: any('salary.read', 'masterdata.manage') },
        { to: '/overtime', label: 'Overtime rules', icon: Timer, end: true, show: can('overtime_policy.manage') },
        { to: '/roles', label: 'Roles', icon: KeyRound, show: can('role.read') },
      ],
    },
    {
      title: 'System',
      items: [
        { to: '/system', label: 'System', icon: Activity, show: can('system.monitor') },
        { to: '/audit', label: 'Audit log', icon: ScrollText, show: can('audit.read') },
        { to: '/outbox', label: 'Messages', icon: Mail, show: can('audit.read') },
      ],
    },
  ].map((g) => ({ ...g, items: g.items.filter((i) => i.show) })).filter((g) => g.items.length > 0)
}

type Theme = 'dark' | 'light' | 'system'
const THEME_KEY = 'hrpay.theme'

function readTheme(): Theme {
  try { return (localStorage.getItem(THEME_KEY) as Theme | null) ?? 'dark' } catch { return 'dark' }
}

/** Dark by default, as in the design; light, or the device's own setting, on request. */
export function applyTheme(theme: Theme = readTheme()) {
  const dark = theme === 'dark' || (theme === 'system' && window.matchMedia('(prefers-color-scheme: dark)').matches)
  document.documentElement.dataset.theme = dark ? 'dark' : 'light'
}

export function Shell({ children }: { children: ReactNode }) {
  const { me, signOut } = useAuth()
  const groups = useNav()
  const location = useLocation()
  const [navOpen, setNavOpen] = useState(false)
  const [menuOpen, setMenuOpen] = useState(false)
  const [theme, setTheme] = useState<Theme>(readTheme)
  const menuRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!menuOpen) return
    const away = (e: MouseEvent) => { if (!menuRef.current?.contains(e.target as Node)) setMenuOpen(false) }
    const esc = (e: KeyboardEvent) => { if (e.key === 'Escape') setMenuOpen(false) }
    document.addEventListener('mousedown', away)
    document.addEventListener('keydown', esc)
    return () => { document.removeEventListener('mousedown', away); document.removeEventListener('keydown', esc) }
  }, [menuOpen])

  useEffect(() => { setNavOpen(false); setMenuOpen(false) }, [location.pathname])
  useEffect(() => {
    try { localStorage.setItem(THEME_KEY, theme) } catch { /* private window */ }
    applyTheme(theme)
    if (theme !== 'system') return
    const media = window.matchMedia('(prefers-color-scheme: dark)')
    const follow = () => applyTheme('system')
    media.addEventListener('change', follow)
    return () => media.removeEventListener('change', follow)
  }, [theme])

  if (!me) return null
  const initials = `${me.firstName[0] ?? ''}${me.lastName[0] ?? ''}`.toUpperCase()
  const current = groups.flatMap((g) => g.items).find((i) => (i.end ? location.pathname === i.to : location.pathname.startsWith(i.to)) && i.to !== '/')

  return (
    <div className="shell">
      <aside className={`sidebar no-print${navOpen ? ' open' : ''}`} aria-label="Main menu">
        <div className="sidebar-brand">
          <span className="brand-mark"><Wallet /></span>
          <span className="brand-name">HR Pay</span>
          <button type="button" className="icon-button sidebar-close" aria-label="Close the menu" onClick={() => setNavOpen(false)}><X size={20} /></button>
        </div>
        <nav className="sidebar-nav">
          {groups.map((g) => (
            <div key={g.title} className="nav-group">
              <p className="nav-title">{g.title}</p>
              {g.items.map((i) => (
                <NavLink key={i.to} to={i.to} end={i.end} className="nav-link">
                  <i.icon size={18} aria-hidden />
                  <span>{i.label}</span>
                </NavLink>
              ))}
            </div>
          ))}
        </nav>
        <NavLink to="/privacy" className="sidebar-foot"><ShieldCheck size={16} aria-hidden /> How your data is used</NavLink>
      </aside>
      {navOpen && <div className="sidebar-scrim no-print" onClick={() => setNavOpen(false)} />}

      <div className="shell-main">
        <header className="topbar no-print">
          <button type="button" className="icon-button menu-button" aria-label="Open the menu" onClick={() => setNavOpen(true)}><Menu size={22} /></button>
          <span className="topbar-title">{current?.label ?? (location.pathname === '/' ? 'Home' : '')}</span>
          <div className="topbar-right">
            <div className="user-menu" ref={menuRef}>
              <button type="button" className="user-chip" aria-expanded={menuOpen} onClick={() => setMenuOpen(!menuOpen)}>
                <span className="avatar">{initials}</span>
                <span className="user-text"><strong>{me.firstName} {me.lastName}</strong><small>{me.roles.join(' · ')}</small></span>
                <ChevronDown size={16} aria-hidden />
              </button>
              {menuOpen && (
                <div className="menu" role="menu">
                  <NavLink to="/account" role="menuitem"><UserRound size={16} aria-hidden /> My account</NavLink>
                  <div className="menu-theme" role="group" aria-label="Appearance">
                    <button type="button" className={theme === 'dark' ? 'on' : ''} onClick={() => setTheme('dark')}><Moon size={15} aria-hidden /> Dark</button>
                    <button type="button" className={theme === 'light' ? 'on' : ''} onClick={() => setTheme('light')}><Sun size={15} aria-hidden /> Light</button>
                    <button type="button" className={theme === 'system' ? 'on' : ''} onClick={() => setTheme('system')}><Monitor size={15} aria-hidden /> Device</button>
                  </div>
                  <button type="button" role="menuitem" onClick={() => void signOut()}><LogOut size={16} aria-hidden /> Sign out</button>
                </div>
              )}
            </div>
          </div>
        </header>
        <main>{children}</main>
      </div>
    </div>
  )
}

function Wallet() {
  return <Banknote size={18} aria-hidden />
}
