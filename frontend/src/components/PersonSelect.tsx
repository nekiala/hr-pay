import { useEffect, useId, useRef, useState, type KeyboardEvent } from 'react'
import { X } from 'lucide-react'
import { api } from '../api'
import { t } from '../i18n'

/** A person found by the search: the people the current user can act on (everyone for HR, their branch for a head). */
export type FoundPerson = { userId: number; name: string; registrationNo: string | null; unitId: number | null; unitName: string | null }

const cache = new Map<number, FoundPerson>()

/** Looks up people by id, for a value set before the picker opened. */
async function byIds(source: string, ids: number[]): Promise<FoundPerson[]> {
  const missing = ids.filter((id) => !cache.has(id))
  if (missing.length) {
    const found = await api<FoundPerson[]>(`${source}?${new URLSearchParams({ ids: missing.join(','), size: String(missing.length) })}`)
    found.forEach((p) => cache.set(p.userId, p))
  }
  return ids.map((id) => cache.get(id)).filter((p): p is FoundPerson => !!p)
}

/**
 * Picks one employee by typing part of a name, employee ID or email. A company can have thousands of people, so
 * the list is never loaded whole: the server sends back the first matches only.
 *
 * `value` is the user id as a string ('' for none), like a select's value, so it drops into existing forms.
 */
export function PersonSelect({ value, onChange, exclude, required, disabled, placeholder, autoFocus, className, source = '/api/org/my-people/search' }: {
  value: string | number | null | undefined
  onChange: (userId: string, person: FoundPerson | null) => void
  exclude?: (number | null | undefined)[]
  required?: boolean
  disabled?: boolean
  placeholder?: string
  autoFocus?: boolean
  className?: string
  /** Where to search, when the people to choose from are not the usual ones. */
  source?: string
}) {
  const id = useId()
  const chosenId = value === '' || value == null ? null : Number(value)
  const [chosen, setChosen] = useState<FoundPerson | null>(chosenId != null ? cache.get(chosenId) ?? null : null)
  const [query, setQuery] = useState('')
  const [results, setResults] = useState<FoundPerson[] | null>(null)
  const [active, setActive] = useState(0)
  const [openList, setOpenList] = useState(false)
  const input = useRef<HTMLInputElement>(null)
  const skip = new Set((exclude ?? []).filter((x): x is number => x != null))

  useEffect(() => {
    if (chosenId == null) { setChosen(null); return }
    if (chosen?.userId === chosenId) return
    let live = true
    byIds(source, [chosenId]).then((p) => { if (live) setChosen(p[0] ?? { userId: chosenId, name: `#${chosenId}`, registrationNo: null, unitId: null, unitName: null }) }).catch(() => undefined)
    return () => { live = false }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [chosenId])

  const q = query.trim()
  useEffect(() => {
    if (!openList) return
    let live = true
    const handle = setTimeout(() => {
      api<FoundPerson[]>(`${source}?${new URLSearchParams({ q, size: '20' })}`)
        .then((found) => { if (!live) return; found.forEach((p) => cache.set(p.userId, p)); setResults(found); setActive(0) })
        .catch(() => { if (live) setResults([]) })
    }, q ? 250 : 0)
    return () => { live = false; clearTimeout(handle) }
  }, [q, openList])

  const shown = (results ?? []).filter((p) => !skip.has(p.userId))
  const pick = (p: FoundPerson) => {
    setChosen(p)
    setQuery('')
    setOpenList(false)
    onChange(String(p.userId), p)
  }
  const keys = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Escape') { setOpenList(false); return }
    if (!shown.length) return
    if (e.key === 'ArrowDown') { e.preventDefault(); setOpenList(true); setActive((i) => Math.min(i + 1, shown.length - 1)) }
    if (e.key === 'ArrowUp') { e.preventDefault(); setActive((i) => Math.max(i - 1, 0)) }
    if (e.key === 'Enter' && openList) { e.preventDefault(); pick(shown[Math.min(active, shown.length - 1)]) }
  }

  if (chosen && chosenId != null) {
    return (
      <div className={['person-chosen', className].filter(Boolean).join(' ')}>
        <span className="who"><strong>{chosen.name}</strong>
          <span className="muted small">{[chosen.registrationNo, chosen.unitName].filter(Boolean).join(' · ')}</span></span>
        {!disabled && <button type="button" className="link" aria-label={t('Change')} title={t('Change')}
          onClick={() => { setChosen(null); onChange('', null); setTimeout(() => input.current?.focus(), 0) }}><X size={16} /></button>}
      </div>
    )
  }
  return (
    <div className={['person-select', className].filter(Boolean).join(' ')} onBlur={(e) => { if (!e.currentTarget.contains(e.relatedTarget as Node | null)) setOpenList(false) }}>
      <input ref={input} type="search" value={query} disabled={disabled} autoFocus={autoFocus} autoComplete="off"
        placeholder={placeholder ?? t('Search by name, employee ID or email')} role="combobox" aria-expanded={openList}
        aria-controls={`${id}-list`} onFocus={() => setOpenList(true)}
        onChange={(e) => { setQuery(e.target.value); setOpenList(true) }} onKeyDown={keys} />
      {/* Keeps the form's own required check: the hidden field is empty until someone is chosen. */}
      {required && <input tabIndex={-1} aria-hidden className="person-required" required value="" onChange={() => undefined}
        onFocus={() => input.current?.focus()} />}
      {openList && results && (
        <ul id={`${id}-list`} className="person-results floating" role="listbox">
          {shown.length === 0 && <li className="muted small empty">{q ? t('No employee matches "{a}".', { a: q }) : t('No one to choose.')}</li>}
          {shown.map((p, i) => (
            <li key={p.userId} role="option" aria-selected={i === active}>
              <button type="button" className={i === active ? 'on' : undefined} onMouseEnter={() => setActive(i)}
                onMouseDown={(e) => e.preventDefault()} onClick={() => pick(p)}>
                <span className="who"><strong>{p.name}</strong>
                  <span className="muted small">{[p.registrationNo, p.unitName].filter(Boolean).join(' · ')}</span></span>
              </button>
            </li>
          ))}
          {shown.length >= 20 && <li className="muted small empty">{t('Type more to narrow the list.')}</li>}
        </ul>
      )}
    </div>
  )
}
