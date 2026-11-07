import { useEffect, useRef, useState } from 'react'
import { Bell } from 'lucide-react'
import { useNavigate } from 'react-router-dom'
import { api, type Inbox } from '../api'
import { locale, t } from '../i18n'

const ago = (iso: string) => {
  const minutes = Math.round((Date.now() - new Date(iso).getTime()) / 60_000)
  const f = new Intl.RelativeTimeFormat(locale(), { numeric: 'auto' })
  if (minutes < 60) return f.format(-minutes, 'minute')
  if (minutes < 24 * 60) return f.format(-Math.round(minutes / 60), 'hour')
  return f.format(-Math.round(minutes / 1440), 'day')
}

/** The bell in the top bar: what needs the person's attention, checked every minute. */
export function NotificationBell() {
  const navigate = useNavigate()
  const [inbox, setInbox] = useState<Inbox | null>(null)
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLDivElement>(null)
  const load = () => api<Inbox>('/api/notifications?limit=20').then(setInbox).catch(() => undefined)

  useEffect(() => {
    void load()
    const timer = window.setInterval(() => { if (document.visibilityState === 'visible') void load() }, 60_000)
    const back = () => { if (document.visibilityState === 'visible') void load() }
    document.addEventListener('visibilitychange', back)
    return () => { window.clearInterval(timer); document.removeEventListener('visibilitychange', back) }
  }, [])
  useEffect(() => {
    if (!open) return
    const away = (e: MouseEvent) => { if (!ref.current?.contains(e.target as Node)) setOpen(false) }
    const esc = (e: KeyboardEvent) => { if (e.key === 'Escape') setOpen(false) }
    document.addEventListener('mousedown', away)
    document.addEventListener('keydown', esc)
    return () => { document.removeEventListener('mousedown', away); document.removeEventListener('keydown', esc) }
  }, [open])

  const read = async (id?: number) => {
    await api('/api/notifications/read', { method: 'POST', body: { id: id ?? null } }).catch(() => undefined)
    await load()
  }
  const unread = inbox?.unread ?? 0

  return (
    <div className="user-menu" ref={ref}>
      <button type="button" className="icon-button bell" aria-expanded={open} onClick={() => setOpen(!open)}
        aria-label={unread ? t('Notifications, {a} unread', { a: unread }) : t('Notifications')}>
        <Bell size={18} aria-hidden />
        {unread > 0 && <span className="bell-count">{unread > 9 ? '9+' : unread}</span>}
      </button>
      {open && (
        <div className="menu notifications" role="dialog" aria-label={t('Notifications')}>
          <div className="row between notifications-head">
            <strong>{t('Notifications')}</strong>
            {unread > 0 && <button type="button" className="link" onClick={() => void read()}>{t('Mark all as read')}</button>}
          </div>
          {!inbox?.items.length && <p className="muted small notifications-empty">{t('Nothing new.')}</p>}
          <ul>
            {inbox?.items.map((n) => (
              <li key={n.id} className={n.readAt ? '' : 'unread'}>
                <button type="button" onClick={() => {
                  setOpen(false)
                  void read(n.id)
                  if (n.link && n.link.startsWith('/') && !n.link.startsWith('//')) navigate(n.link)
                }}>
                  <strong>{n.title}</strong>
                  {n.body && <span>{n.body}</span>}
                  <small className="muted">{ago(n.createdAt)}</small>
                </button>
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  )
}
