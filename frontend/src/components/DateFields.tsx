import { useEffect, useId, useRef, useState, type CSSProperties, type FocusEvent } from 'react'
import { CalendarDays } from 'lucide-react'
import { language, locale, t } from '../i18n'

/**
 * Date and time fields that follow HR Pay's language, not the browser's. The browser's own date box writes 02/14/1989
 * in a browser set to American English even when HR Pay is in French; these always write 14/02/1989 and 14:30.
 * They hold the same values as the browser's fields ("1989-02-14", "14:30", "1989-02-14T14:30") and hand them to
 * onChange as e.target.value, so a page swaps <input type="date"> for <DateInput> without other changes.
 */

type ChangeEvent = { target: { value: string }; currentTarget: { value: string } }

type Common = {
  value: string
  onChange?: (e: ChangeEvent) => void
  onBlur?: (e: FocusEvent<HTMLInputElement>) => void
  required?: boolean
  disabled?: boolean
  className?: string
  id?: string
  name?: string
  title?: string
  autoFocus?: boolean
  style?: CSSProperties
  'aria-label'?: string
}

const event = (value: string): ChangeEvent => ({ target: { value }, currentTarget: { value } })
const pad = (n: number) => String(n).padStart(2, '0')
const iso = (y: number, m: number, d: number) => `${String(y).padStart(4, '0')}-${pad(m)}-${pad(d)}`

/** 1989-02-14 as 14/02/1989. */
export const showDate = (v: string | null | undefined) => {
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(v ?? '')
  return m ? `${m[3]}/${m[2]}/${m[1]}` : ''
}

/** 14/02/1989, 14.2.89, 14-02-1989 or 1989-02-14 as 1989-02-14; null when it is not a real date. */
export function readDate(text: string): string | null {
  const s = text.trim()
  let y: number, m: number, d: number
  let parts = /^(\d{4})-(\d{1,2})-(\d{1,2})$/.exec(s)
  if (parts) {
    [y, m, d] = [Number(parts[1]), Number(parts[2]), Number(parts[3])]
  } else {
    parts = /^(\d{1,2})[/.\-\s](\d{1,2})[/.\-\s](\d{2}|\d{4})$/.exec(s) ?? /^(\d{2})(\d{2})(\d{4})$/.exec(s)
    if (!parts) return null
    ;[d, m, y] = [Number(parts[1]), Number(parts[2]), Number(parts[3])]
    if (parts[3].length === 2) y += y > (new Date().getFullYear() % 100) + 10 ? 1900 : 2000
  }
  const date = new Date(Date.UTC(y, m - 1, d))
  if (y < 1900 || y > 2200 || date.getUTCMonth() !== m - 1 || date.getUTCDate() !== d) return null
  return iso(y, m, d)
}

/** "14:30", "1430", "14h30" or "9:05" as 14:30 or 09:05; null when it is not a time of day. */
export function readTime(text: string): string | null {
  const parts = /^(\d{1,2})\s*[:hH.]?\s*(\d{2})?$/.exec(text.trim())
  if (!parts) return null
  const h = Number(parts[1])
  const m = Number(parts[2] ?? 0)
  return h < 24 && m < 60 ? `${pad(h)}:${pad(m)}` : null
}

/** While typing: 14021989 becomes 14/02/1989 as the digits come. */
function maskDate(text: string, previous: string) {
  if (text.length < previous.length) return text
  const digits = text.replace(/\D/g, '')
  if (/[^\d/]/.test(text) || digits.length > 8) return text
  let out = digits.slice(0, 2)
  if (digits.length >= 2) out += '/' + digits.slice(2, 4)
  if (digits.length >= 4) out += '/' + digits.slice(4, 8)
  return out
}

function outOfRange(v: string | null, min?: string, max?: string) {
  if (!v) return null
  if (min && v < min) return t('The date must be on or after {a}.', { a: showDate(min) })
  if (max && v > max) return t('The date must be on or before {a}.', { a: showDate(max) })
  return null
}

/** Under the field, or above it when there is no room below; inside the screen. */
function place(at: DOMRect): CSSProperties {
  const width = 288
  const height = 330
  const left = Math.max(8, Math.min(at.left, window.innerWidth - width - 8))
  const top = at.bottom + height + 8 > window.innerHeight && at.top > height + 8 ? at.top - height - 4 : at.bottom + 4
  return { left, top }
}

/** A day of the month grid, with the months and weekdays in HR Pay's language. */
function Calendar({ value, min, max, at, onPick, onClose }: { value: string; min?: string; max?: string; at: DOMRect; onPick: (v: string) => void; onClose: () => void }) {
  const today = new Date()
  const start = readDate(value) ?? iso(today.getFullYear(), today.getMonth() + 1, today.getDate())
  const [month, setMonth] = useState({ y: Number(start.slice(0, 4)), m: Number(start.slice(5, 7)) })
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const away = (e: MouseEvent) => { if (ref.current && !ref.current.parentElement?.contains(e.target as Node)) onClose() }
    const esc = (e: KeyboardEvent) => { if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); onClose() } }
    // Fixed on the screen, so that a table or a dialog that scrolls does not cut it: it closes when the page moves.
    const moved = (e: Event) => { if (!ref.current?.contains(e.target as Node)) onClose() }
    document.addEventListener('mousedown', away)
    document.addEventListener('keydown', esc, true)
    window.addEventListener('scroll', moved, true)
    window.addEventListener('resize', onClose)
    return () => {
      document.removeEventListener('mousedown', away); document.removeEventListener('keydown', esc, true)
      window.removeEventListener('scroll', moved, true); window.removeEventListener('resize', onClose)
    }
  }, [onClose])

  const first = new Date(Date.UTC(month.y, month.m - 1, 1))
  const days = new Date(Date.UTC(month.y, month.m, 0)).getUTCDate()
  const lead = (first.getUTCDay() + 6) % 7 // Monday first
  const title = first.toLocaleDateString(locale(), { month: 'long', timeZone: 'UTC' })
  const weekdays = Array.from({ length: 7 }, (_, i) =>
    new Date(Date.UTC(2024, 0, 1 + i)).toLocaleDateString(locale(), { weekday: 'short', timeZone: 'UTC' }).replace('.', ''))
  const move = (delta: number) => setMonth(({ y, m }) => { const n = m - 1 + delta; return { y: y + Math.floor(n / 12), m: ((n % 12) + 12) % 12 + 1 } })
  const todayIso = iso(today.getFullYear(), today.getMonth() + 1, today.getDate())
  const years = Array.from({ length: 131 }, (_, i) => today.getFullYear() + 10 - i)

  return (
    <div className="calendar" ref={ref} role="dialog" aria-label={t('Choose a date')} style={place(at)}>
      <div className="calendar-head">
        <button type="button" className="ghost small" onClick={() => move(-1)} aria-label={t('Previous month')}>‹</button>
        <span className="calendar-title">{title}</span>
        <select aria-label={t('Year')} value={month.y} onChange={(e) => setMonth({ ...month, y: Number(e.target.value) })}>
          {!years.includes(month.y) && <option value={month.y}>{month.y}</option>}
          {years.map((y) => <option key={y} value={y}>{y}</option>)}
        </select>
        <button type="button" className="ghost small" onClick={() => move(1)} aria-label={t('Next month')}>›</button>
      </div>
      <div className="calendar-grid">
        {weekdays.map((w) => <span key={w} className="calendar-weekday">{w}</span>)}
        {Array.from({ length: lead }, (_, i) => <span key={`x${i}`} />)}
        {Array.from({ length: days }, (_, i) => {
          const v = iso(month.y, month.m, i + 1)
          const blocked = (min && v < min) || (max && v > max)
          return (
            <button key={v} type="button" disabled={!!blocked} onClick={() => onPick(v)}
              className={['calendar-day', v === value ? 'chosen' : '', v === todayIso ? 'today' : ''].join(' ')}>{i + 1}</button>
          )
        })}
      </div>
      <div className="calendar-foot">
        <button type="button" className="ghost small" disabled={!!((min && todayIso < min) || (max && todayIso > max))} onClick={() => onPick(todayIso)}>{t('Today')}</button>
      </div>
    </div>
  )
}

/** A day, written dd/mm/yyyy, with a calendar. */
export function DateInput({ value, onChange, onBlur, min, max, required, disabled, className, style, ...rest }: Common & { min?: string; max?: string }) {
  const [text, setText] = useState(showDate(value))
  const [open, setOpen] = useState<DOMRect | null>(null)
  const input = useRef<HTMLInputElement>(null)
  const listId = useId()
  useEffect(() => {
    // A value changed from outside (a reset, a default) shows as it is; what is being typed stays.
    if (readDate(text) !== (value || null)) setText(showDate(value))
  }, [value]) // eslint-disable-line react-hooks/exhaustive-deps

  const problem = (v: string | null, raw: string) =>
    raw.trim() && !v ? t('Write the date as dd/mm/yyyy, for example 14/02/1989.') : outOfRange(v, min, max)
  useEffect(() => {
    input.current?.setCustomValidity(problem(readDate(text), text) ?? '')
  })

  const typed = (raw: string) => {
    const next = maskDate(raw, text)
    setText(next)
    const v = readDate(next)
    if (v !== null) { if (v !== value) onChange?.(event(v)) } else if (!next.trim() && value) onChange?.(event(''))
  }
  const pick = (v: string) => { setText(showDate(v)); setOpen(null); onChange?.(event(v)); input.current?.focus() }

  return (
    <span className={`date-field ${className ?? ''}`} style={style}>
      <input ref={input} type="text" inputMode="numeric" autoComplete="off" placeholder={language() === 'fr' ? 'jj/mm/aaaa' : 'dd/mm/yyyy'}
        value={text} required={required} disabled={disabled} aria-describedby={listId} {...rest}
        onChange={(e) => typed(e.target.value)}
        onBlur={(e) => { const v = readDate(text); if (v) setText(showDate(v)); onBlur?.(e) }} />
      <button type="button" className="date-button" disabled={disabled} aria-label={t('Open the calendar')} tabIndex={-1}
        onClick={() => setOpen(open ? null : input.current?.getBoundingClientRect() ?? null)}><CalendarDays size={16} aria-hidden /></button>
      {open && <Calendar value={value} min={min} max={max} at={open} onPick={pick} onClose={() => setOpen(null)} />}
      <span id={listId} hidden>{language() === 'fr' ? 'jj/mm/aaaa' : 'dd/mm/yyyy'}</span>
    </span>
  )
}

/** A time of day, written on 24 hours: 14:30. */
export function TimeInput({ value, onChange, onBlur, required, disabled, className, style, ...rest }: Common) {
  const [text, setText] = useState(value ? value.slice(0, 5) : '')
  const input = useRef<HTMLInputElement>(null)
  useEffect(() => {
    if (readTime(text) !== (value ? value.slice(0, 5) : null)) setText(value ? value.slice(0, 5) : '')
  }, [value]) // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => {
    input.current?.setCustomValidity(text.trim() && !readTime(text) ? t('Write the time on 24 hours, for example 14:30.') : '')
  })
  const typed = (raw: string) => {
    const next = /^\d{3}$/.test(raw) && text.length === 2 ? `${raw.slice(0, 2)}:${raw.slice(2)}` : raw
    setText(next)
    const v = readTime(next)
    if (v !== null && next.replace(/\D/g, '').length >= 3) { if (v !== value?.slice(0, 5)) onChange?.(event(v)) }
    else if (!next.trim() && value) onChange?.(event(''))
  }
  return (
    <input ref={input} type="text" inputMode="numeric" autoComplete="off" placeholder="hh:mm" maxLength={5}
      className={`time-field ${className ?? ''}`} style={style} value={text} required={required} disabled={disabled} {...rest}
      onChange={(e) => typed(e.target.value)}
      onBlur={(e) => { const v = readTime(text); if (v) { setText(v); if (v !== value?.slice(0, 5)) onChange?.(event(v)) } onBlur?.(e) }} />
  )
}

/** A day and a time: "1989-02-14T14:30", as the browser's datetime-local field holds it. */
export function DateTimeInput({ value, onChange, required, disabled, min, max, ...rest }: Common & { min?: string; max?: string }) {
  const day = value ? value.slice(0, 10) : ''
  const time = value && value.length >= 16 ? value.slice(11, 16) : ''
  const send = (d: string, h: string) => onChange?.(event(d ? `${d}T${h || '00:00'}` : ''))
  return (
    <span className="datetime-field">
      <DateInput {...rest} value={day} required={required} disabled={disabled} min={min?.slice(0, 10)} max={max?.slice(0, 10)}
        onChange={(e) => send(e.target.value, time)} />
      <TimeInput value={time} required={required} disabled={disabled} aria-label={t('Time')} onChange={(e) => send(day, e.target.value)} />
    </span>
  )
}
