import { useEffect, useRef, useState, type ComponentType, type ReactNode } from 'react'
import { NavLink, useLocation, useNavigate } from 'react-router-dom'
import {
  Activity, BadgeCheck, FolderOpen, ClipboardList, Fingerprint, FileUp, Plug, Columns3, BookOpenCheck, DoorOpen, Gift, Banknote, PiggyBank, Building, Briefcase, Building2, Calculator, CalendarClock, CreditCard, LifeBuoy, CalendarDays, ChartColumn, ChevronDown, Clock,
  FileSpreadsheet, Coins, Flag, HandCoins, House, KeyRound, Landmark, Layers, LogOut, Mail, MapPin, Menu, Monitor, Moon, Network,
  Percent, PencilLine, ReceiptText, ScrollText, ShieldCheck, SlidersHorizontal, Star, Sun, Timer, UserCheck, UserRound, Users, X,
  ChartNoAxesCombined, CircleHelp, ListChecks, CalendarRange, Headset, Inbox, Scale, Siren, Sparkles, MessageSquareWarning, MessageCircle, Megaphone,
  BriefcaseBusiness, Handshake, Smartphone, ListTree,
} from 'lucide-react'
import { useAuth } from '../auth'
import { api, longDay, type Module , personName } from '../api'
import { t } from '../i18n'
import { NotificationBell } from './NotificationBell'
import { JobProgress } from './JobProgress'
import { useOfflineSync } from '../pwa'
import { articleFor } from '../help'
import { hasNews, ReportProblem, serverVersion } from '../pages/HelpPage'

type Icon = ComponentType<{ size?: number; 'aria-hidden'?: boolean }>
type Item = { to: string; label: string; icon: Icon; end?: boolean; show: boolean }

/** The sidebar's links, grouped by what people come to do; each shows only to those allowed to open it. */
function useNav(): { title: string; items: Item[] }[] {
  const { can, me } = useAuth()
  const any = (...p: string[]) => p.some(can)
  // A module outside the company's plan is left out of the menu.
  const has = (m: Module) => !me?.company || me.company.modules.includes(m)
  const att = has('ATTENDANCE')
  const pay = has('PAYROLL')
  // "My salary advance" shows only where the company offers advances.
  const [advances, setAdvances] = useState(false)
  useEffect(() => {
    if (!pay || !me) return
    api<boolean>('/api/advances/offered').then(setAdvances).catch(() => setAdvances(false))
  }, [pay, me?.id]) // eslint-disable-line react-hooks/exhaustive-deps
  return [
    {
      title: t('My work'),
      items: [
        { to: '/', label: t('Home'), icon: House, end: true, show: true },
        { to: '/firm', label: t('Clients'), icon: BriefcaseBusiness, show: !!me?.firmMember && !me?.firm },
        { to: '/check-in', label: t('Check in'), icon: Clock, show: att },
        { to: '/leave', label: t('Leave'), icon: CalendarDays, end: true, show: any('leave.request', 'leave.manage', 'leave.approve.team') },
        { to: '/missions', label: t('Missions'), icon: Briefcase, show: att && any('mission.request', 'mission.approve.all', 'mission.approve.team') },
        { to: '/corrections', label: t('Corrections'), icon: PencilLine, show: att && any('attendance.correct.request', 'attendance.correct.propose', 'attendance.correct') },
        { to: '/my/payslips', label: t('My payslips'), icon: ReceiptText, show: pay && can('payslip.read.own') },
        { to: '/my/advance', label: t('My salary advance'), icon: PiggyBank, show: pay && advances },
        { to: '/my/documents', label: t('My documents'), icon: FolderOpen, show: true },
        { to: '/announcements', label: t('Announcements'), icon: Megaphone, show: true },
        { to: '/jobs', label: t('My jobs'), icon: ListChecks, show: true },
      ],
    },
    {
      title: t('Team'),
      items: [
        { to: '/presence', label: t('Presence'), icon: UserCheck, show: att && any('attendance.read.all', 'attendance.read.team') },
        { to: '/overtime/approvals', label: t('Approve overtime'), icon: Timer, show: att && any('overtime.approve.all', 'overtime.approve.team') },
        { to: '/evaluations', label: t('Evaluations'), icon: Star, end: true, show: has('EVALUATIONS') },
        { to: '/org', label: t('Org chart'), icon: Network, show: true },
        { to: '/dashboards', label: t('Dashboards'), icon: ChartNoAxesCombined, show: can('dashboard.read') },
      ],
    },
    {
      title: t('People'),
      items: [
        { to: '/users', label: t('Users'), icon: Users, show: can('user.read') },
        { to: '/lifecycle', label: t('Hiring and departures'), icon: ClipboardList, end: true, show: can('lifecycle.manage') },
        { to: '/schedules', label: t('Schedules'), icon: CalendarClock, show: any('schedule.manage', 'user.read') },
        { to: '/holidays', label: t('Holidays'), icon: Flag, show: true },
        { to: '/departures', label: t('Departures'), icon: DoorOpen, show: pay && any('payroll.run', 'payroll.approve') },
        { to: '/sites', label: t('Sites'), icon: MapPin, show: att && any('site.read', 'site.manage') },
      ],
    },
    {
      title: t('Payroll'),
      items: [
        { to: '/payroll', label: t('Pay runs'), icon: Calculator, end: true, show: pay && any('payroll.run', 'payroll.approve', 'payslip.read.all') },
        { to: '/payroll/reports', label: t('Payroll reports'), icon: FileSpreadsheet, show: pay && can('payroll.report') },
        { to: '/payroll/payments', label: t('Payments'), icon: Banknote, show: pay && any('payroll.pay', 'payslip.read.all') },
        { to: '/payroll/declarations', label: t('Declarations'), icon: Landmark, show: pay && can('declaration.manage') },
        { to: '/payroll/year', label: t('Year summary'), icon: ChartColumn, end: true, show: pay && can('payslip.read.all') },
        { to: '/payroll/entries', label: t('Bonuses and deductions'), icon: Coins, show: pay && can('salary.read') },
        { to: '/payroll/advances', label: t('Salary advances'), icon: PiggyBank, show: pay && any('advance.manage', 'payroll.pay') },
        { to: '/payroll/loans', label: t('Loans'), icon: HandCoins, show: pay && any('loan.manage', 'payslip.read.all') },
        { to: '/payroll/accounting', label: t('Accounting'), icon: BookOpenCheck, show: pay && can('accounting.export') },
        { to: '/reports', label: t('Reports'), icon: FileSpreadsheet, show: any('report.attendance.export', 'report.finance.export') },
      ],
    },
    {
      title: t('Settings'),
      items: [
        { to: '/company', label: t('Company'), icon: Building2, show: can('company.manage') },
        { to: '/company/accountant', label: t('Accounting firm'), icon: BriefcaseBusiness, show: can('firm.manage') && !me?.firmMember },
        { to: '/billing', label: t('Billing'), icon: CreditCard, show: can('subscription.manage') && !can('platform.manage') },
        { to: '/payroll/settings', label: t('Grades'), icon: Layers, show: can('masterdata.manage') },
        { to: '/reference-lists', label: t('Reference lists'), icon: ListTree, show: any('masterdata.manage', 'platform.manage') && can('salary.read') },
        { to: '/payroll/elements', label: t('Pay elements'), icon: SlidersHorizontal, show: pay && any('salary.read', 'masterdata.manage') },
        { to: '/payroll/rates', label: t('Rates'), icon: Percent, show: pay && any('salary.read', 'masterdata.manage') },
        { to: '/payroll/sector', label: t('Sector agreement'), icon: Handshake, show: pay && can('masterdata.manage') },
        { to: '/payroll/payments/ntpay', label: t('Mobile money (NTPay)'), icon: Smartphone, show: pay && any('company.manage', 'payroll.pay') },
        { to: '/overtime', label: t('Overtime rules'), icon: Timer, end: true, show: att && can('overtime_policy.manage') },
        { to: '/leave/settings', label: t('Leave settings'), icon: CalendarDays, show: can('leave.manage') },
        { to: '/payroll/quinzaine/settings', label: t('Quinzaine settings'), icon: CalendarRange, show: pay && can('masterdata.manage') },
        { to: '/payroll/thirteenth-month/settings', label: t('13th month settings'), icon: Gift, show: pay && any('salary.read', 'masterdata.manage') },
        { to: '/evaluations/settings', label: t('Criteria and templates'), icon: Star, show: has('EVALUATIONS') && can('evaluation.manage') },
        { to: '/lifecycle/settings', label: t('Hiring and departure settings'), icon: ClipboardList, show: can('lifecycle.manage') },
        { to: '/roles', label: t('Roles'), icon: KeyRound, show: can('role.read') },
        { to: '/privacy/requests', label: t('Personal data'), icon: ShieldCheck, show: can('privacy.manage') },
        { to: '/imports', label: t('Import data'), icon: FileUp, show: can('data.import') },
        { to: '/export-layouts', label: t('File layouts'), icon: Columns3, show: pay && any('accounting.export', 'payroll.pay') },
        { to: '/time-clocks', label: t('Time clocks'), icon: Fingerprint, show: att && can('api.manage') },
        { to: '/integrations', label: t('API and webhooks'), icon: Plug, show: can('api.manage') },
      ],
    },
    {
      title: t('System'),
      items: [
        { to: '/platform/companies', label: t('Companies'), icon: Building, show: can('platform.manage') },
        { to: '/platform/billing', label: t('Invoices'), icon: CreditCard, show: can('platform.manage') && me?.company?.mode === 'SAAS' },
        { to: '/platform/licence', label: t('Licence'), icon: BadgeCheck, show: can('platform.manage') && me?.company?.mode === 'ON_PREMISE' },
        { to: '/platform/support', label: t('Support desk'), icon: Headset, show: can('platform.manage') },
        { to: '/platform/trials', label: t('Trial requests'), icon: Inbox, show: can('platform.manage') && me?.company?.mode === 'SAAS' },
        { to: '/platform/email', label: t('Email server'), icon: Mail, show: can('platform.manage') },
        { to: '/platform/whatsapp', label: t('WhatsApp'), icon: MessageCircle, show: can('platform.manage') },
        { to: '/platform/legal', label: t('Terms and policies'), icon: Scale, show: can('platform.manage') },
        { to: '/platform/status', label: t('Service status'), icon: Siren, show: can('platform.manage') },
        { to: '/platform/changes', label: t('Law changes'), icon: Scale, show: can('platform.manage') },
        { to: '/platform/sector-packs', label: t('Sector packs'), icon: Handshake, show: can('platform.manage') },
        { to: '/system', label: t('System'), icon: Activity, show: can('system.monitor') },
        { to: '/audit', label: t('Audit log'), icon: ScrollText, show: can('audit.read') },
        { to: '/outbox', label: t('Messages'), icon: Mail, show: can('audit.read') },
      ],
    },
  ].map((g) => ({ ...g, items: g.items.filter((i) => i.show) })).filter((g) => g.items.length > 0)
}

type Theme = 'dark' | 'light' | 'system'
const THEME_KEY = 'hrpay.theme'

function readTheme(): Theme {
  try { return (localStorage.getItem(THEME_KEY) as Theme | null) ?? 'system' } catch { return 'system' }
}

/** Follows the device's light or dark setting until the person picks one. */
export function applyTheme(theme: Theme = readTheme()) {
  const dark = theme === 'dark' || (theme === 'system' && window.matchMedia('(prefers-color-scheme: dark)').matches)
  document.documentElement.dataset.theme = dark ? 'dark' : 'light'
}

export function Shell({ children }: { children: ReactNode }) {
  const { me, signOut, refresh } = useAuth()
  const groups = useNav()
  useOfflineSync(me?.id)
  const location = useLocation()
  const navigate = useNavigate()
  const endSupport = async () => {
    const company = me?.support?.companyId
    await api('/api/support/end', { method: 'POST' }).catch(() => undefined)
    await refresh()
    navigate(company ? `/platform/companies/${company}` : '/')
  }
  const endFirmVisit = async () => {
    await api('/api/firm/visit/end', { method: 'POST' }).catch(() => undefined)
    await refresh()
    navigate('/firm')
  }
  const [navOpen, setNavOpen] = useState(false)
  const [menuOpen, setMenuOpen] = useState(false)
  const [theme, setTheme] = useState<Theme>(readTheme)
  const [reporting, setReporting] = useState(false)
  const [demo, setDemo] = useState(false)
  useEffect(() => { void serverVersion().then((v) => setDemo(v.demo)) }, [])
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
  const dark = theme === 'dark' || (theme === 'system' && window.matchMedia('(prefers-color-scheme: dark)').matches)
  // Plan and licence warnings are for the people who run the company's set-up.
  const notices = me.company && ['user.create', 'company.manage', 'role.manage', 'platform.manage'].some((p) => me.permissions.includes(p))
    ? me.company.notices : []
  const initials = `${me.firstName[0] ?? ''}${me.lastName[0] ?? ''}`.toUpperCase()
  const current = groups.flatMap((g) => g.items).find((i) => (i.end ? location.pathname === i.to : location.pathname.startsWith(i.to)) && i.to !== '/')

  return (
    <div className="shell">
      <aside className={`sidebar no-print${navOpen ? ' open' : ''}`} aria-label={t('Main menu')}>
        <div className="sidebar-brand">
          <span className="brand-mark"><Wallet /></span>
          <span className="brand-text"><span className="brand-name">{t('HR Pay')}</span>{me.company && <small className="brand-company">{me.company.name}</small>}</span>
          <button type="button" className="icon-button sidebar-close" aria-label={t('Close the menu')} onClick={() => setNavOpen(false)}><X size={20} /></button>
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
        <NavLink to="/privacy" className="sidebar-foot"><ShieldCheck size={16} aria-hidden />{' '}{t('How your data is used')}</NavLink>
      </aside>
      {navOpen && <div className="sidebar-scrim no-print" onClick={() => setNavOpen(false)} />}

      <div className="shell-main has-bottom-nav">
        <header className="topbar no-print">
          <button type="button" className="icon-button menu-button" aria-label={t('Open the menu')} onClick={() => setNavOpen(true)}><Menu size={22} /></button>
          <span className="topbar-title">{current?.label ?? (location.pathname === '/' ? t('Home') : '')}</span>
          <div className="topbar-right">
            <NavLink to={articleFor(location.pathname) ? `/help/${articleFor(location.pathname)!.id}` : '/help'} className="icon-button"
              aria-label={t('Help on this page')} title={t('Help on this page')}><CircleHelp size={18} aria-hidden /></NavLink>
            <NotificationBell />
            <button type="button" className="icon-button theme-toggle" aria-label={dark ? t('Use the light theme') : t('Use the dark theme')}
              title={dark ? t('Light theme') : t('Dark theme')} onClick={() => setTheme(dark ? 'light' : 'dark')}>
              {dark ? <Sun size={18} aria-hidden /> : <Moon size={18} aria-hidden />}
            </button>
            <div className="user-menu" ref={menuRef}>
              <button type="button" className="user-chip" aria-expanded={menuOpen} onClick={() => setMenuOpen(!menuOpen)}>
                <span className="avatar">{initials}</span>
                <span className="user-text"><strong>{personName(me)}</strong><small>{me.roles.join(' · ')}</small></span>
                <ChevronDown size={16} aria-hidden />
              </button>
              {menuOpen && (
                <div className="menu" role="menu">
                  <NavLink to="/account" role="menuitem"><UserRound size={16} aria-hidden />{' '}{t('My account')}</NavLink>
                  <NavLink to="/help" role="menuitem"><CircleHelp size={16} aria-hidden />{' '}{t('Help')}</NavLink>
                  <button type="button" role="menuitem" onClick={() => { setMenuOpen(false); setReporting(true) }}><MessageSquareWarning size={16} aria-hidden />{' '}{t('Report a problem')}</button>
                  <NavLink to="/whats-new" role="menuitem"><Sparkles size={16} aria-hidden />{' '}{t('What’s new')}{hasNews() && <span className="dot" aria-label={t('New')} />}</NavLink>
                  <div className="menu-theme" role="group" aria-label={t('Appearance')}>
                    <button type="button" className={theme === 'dark' ? 'on' : ''} onClick={() => setTheme('dark')}><Moon size={15} aria-hidden />{' '}{t('Dark')}</button>
                    <button type="button" className={theme === 'light' ? 'on' : ''} onClick={() => setTheme('light')}><Sun size={15} aria-hidden />{' '}{t('Light')}</button>
                    <button type="button" className={theme === 'system' ? 'on' : ''} onClick={() => setTheme('system')}><Monitor size={15} aria-hidden />{' '}{t('Device')}</button>
                  </div>
                  <button type="button" role="menuitem" onClick={() => void signOut()}><LogOut size={16} aria-hidden />{' '}{t('Sign out')}</button>
                </div>
              )}
            </div>
          </div>
        </header>
        <main>
          {demo && <p className="notice no-print">{t('Demo: everything you change here is erased every night.')}</p>}
          {me.support && (
            <div className="support-bar no-print" role="status">
              <span><LifeBuoy size={16} aria-hidden /> <strong>{t('Support visit:')}{' '}{me.support.companyName}.</strong>{' '}{t('You see what its users see, read-only, until')}{' '}{longDay(me.support.expiresAt)}{t('. Every page you open is shown to the company.')}</span>
              <button type="button" className="secondary" onClick={() => void endSupport()}>{t('End the visit')}</button>
            </div>
          )}
          {me.firm && (
            <div className="support-bar no-print" role="status">
              <span><BriefcaseBusiness size={16} aria-hidden /> <strong>{t('Working for {a}', { a: me.firm.clientName })}</strong>{' '}{t('as {a}, with the rights the client gave your firm. Every page you open and every change is shown to the client.', { a: me.firm.firmName })}</span>
              <button type="button" className="secondary" onClick={() => void endFirmVisit()}>{t('Back to my firm')}</button>
            </div>
          )}
          {me.company?.readOnly && !me.support && <p className="error no-print">{me.company.readOnly}</p>}
          {notices.length > 0 && (
            <div className="plan-notices no-print">
              {notices.map((n) => <p key={n.text} className={n.level === 'danger' ? 'error' : 'notice'}>{n.text}</p>)}
            </div>
          )}
          {children}
          {reporting && <ReportProblem onClose={() => setReporting(false)} onDone={(id) => { setReporting(false); navigate(`/support/${id}`) }} />}
        </main>
        <JobProgress />
        <BottomNav />
      </div>
    </div>
  )
}

/** On a phone: the four things people open most, under the thumb. */
function BottomNav() {
  const { can, me } = useAuth()
  const has = (m: Module) => !me?.company || me.company.modules.includes(m)
  const items = [
    { to: '/', label: t('Home'), icon: House, end: true, show: true },
    { to: '/check-in', label: t('Check in'), icon: Clock, show: has('ATTENDANCE') },
    { to: '/leave', label: t('Leave'), icon: CalendarDays, show: ['leave.request', 'leave.manage', 'leave.approve.team'].some(can) },
    { to: '/my/payslips', label: t('Payslips'), icon: ReceiptText, show: has('PAYROLL') && can('payslip.read.own') },
    { to: '/my/documents', label: t('Documents'), icon: FolderOpen, show: true },
  ].filter((i) => i.show).slice(0, 5)
  return (
    <nav className="bottom-nav no-print" aria-label={t('Quick links')}>
      {items.map((i) => (
        <NavLink key={i.to} to={i.to} end={i.end}>
          <i.icon size={20} aria-hidden />
          <span>{i.label}</span>
        </NavLink>
      ))}
    </nav>
  )
}

function Wallet() {
  return <Banknote size={18} aria-hidden />
}
