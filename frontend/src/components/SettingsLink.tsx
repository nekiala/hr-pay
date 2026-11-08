import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { Settings } from 'lucide-react'
import { t } from '../i18n'

/** The button in a work page's header that opens that module's settings page. */
export function SettingsLink({ to, label }: { to: string; label?: string }) {
  return (
    <Link className="button secondary" to={to}>
      <Settings size={16} aria-hidden />{' '}{label ?? t('Settings')}
    </Link>
  )
}

/** A page title with the page's actions on the right. */
export function PageTitle({ title, children }: { title: string; children?: ReactNode }) {
  return (
    <div className="row space">
      <h2>{title}</h2>
      {children && <div className="row">{children}</div>}
    </div>
  )
}
