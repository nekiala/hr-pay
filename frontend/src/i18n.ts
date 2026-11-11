// The app's texts in French, with English kept. The English text is its own key: t('Save') gives "Enregistrer" in
// French. Values go in braces: t('{n} people', { n: 3 }). A text missing from the French catalogue stays English.
import fr from './fr'

export type Language = 'fr' | 'en'

const STORE = 'hrpay.language'

function initial(): Language {
  try {
    const saved = localStorage.getItem(STORE)
    if (saved === 'fr' || saved === 'en') return saved
  } catch { /* no storage */ }
  // The app's markets work in French: English only for a browser set to it.
  return typeof navigator !== 'undefined' && navigator.language?.toLowerCase().startsWith('en') ? 'en' : 'fr'
}

let current: Language = initial()

export const language = () => current

export function setLanguage(l: Language) {
  current = l
  try { localStorage.setItem(STORE, l) } catch { /* no storage */ }
  document.documentElement.lang = l
}

/** The locale for dates and numbers. */
export const locale = () => (current === 'fr' ? 'fr-FR' : 'en-GB')

const dictionary = fr as Record<string, string>

// The country's own words: in the Republic of the Congo the local currency is XAF and the income tax is the ITS, where
// the texts (written for the DRC) say CDF and IPR. Kept in this browser so the first screen already uses them.
const COUNTRY = 'hrpay.country'
let terms: [RegExp, string][] = []

function applyTerms(country: string | null, local: string | null) {
  terms = country === 'CG' ? [[/\bCDF\b/g, local || 'XAF'], [/\bIPR\b/g, 'ITS']]
    : country === 'CM' || country === 'GA' ? [[/\bCDF\b/g, local || 'XAF'], [/\bIPR\b/g, 'IRPP'], [/\bITS\b/g, 'IRPP']]
    : [[/\bIPR\b/g, 'IRPP']]
}

try {
  const saved = JSON.parse(localStorage.getItem(COUNTRY) ?? 'null') as { country: string; local: string } | null
  if (saved) applyTerms(saved.country, saved.local)
} catch { /* no storage */ }

/** The signed-in company's country and local currency, for the texts that name them. */
export function setCountry(country: string | null | undefined, local: string | null | undefined) {
  applyTerms(country ?? null, local ?? null)
  try { localStorage.setItem(COUNTRY, JSON.stringify({ country, local })) } catch { /* no storage */ }
}

/** The local currency the texts use: CDF, or the company's own. */
export const localCurrency = () => terms.find(([re]) => re.source.includes('CDF'))?.[1] ?? 'CDF'

/**
 * A day as people write it in HR Pay's language: 14/02/1989 in French, 14 Feb 1989 in English. Takes 1989-02-14 (or
 * a full date and time); anything else comes back as it is.
 */
export function day(d: string | null | undefined): string {
  if (!d) return ''
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(d)
  if (!m) return d
  if (current === 'fr') return `${m[3]}/${m[2]}/${m[1]}`
  return new Date(Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3]))).toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC' })
}

/** A moment, in the browser's time zone: 14/02/1989 14:30 in French, 14 Feb 1989, 2:30 PM in English. */
export function dayTime(iso: string | null | undefined): string {
  if (!iso) return ''
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  const local = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
  const time = current === 'fr'
    ? d.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' })
    : d.toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit' })
  return current === 'fr' ? `${day(local)} ${time}` : `${day(local)}, ${time}`
}

const ISO_DAY = /^\d{4}-\d{2}-\d{2}$/
const ISO_MOMENT = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}:?\d{2})$/

/** A value put into a text: numbers and dates written the language's way. */
function shown(v: string | number): string {
  if (typeof v === 'number') return v.toLocaleString(locale())
  if (ISO_DAY.test(v)) return day(v)
  if (ISO_MOMENT.test(v)) return dayTime(v)
  return v
}

export function t(english: string, vars?: Record<string, string | number | null | undefined>): string {
  let text = current === 'fr' ? dictionary[english] ?? english : english
  for (const [re, word] of terms) text = text.replace(re, word)
  if (vars) {
    for (const [k, v] of Object.entries(vars)) {
      text = text.split(`{${k}}`).join(v === null || v === undefined ? '' : shown(v))
    }
  }
  return text
}

/** A count with its noun: plural('{n} day', '{n} days', 3). French counts 0 and 1 as singular. */
export function plural(one: string, many: string, n: number, vars?: Record<string, string | number>): string {
  const single = current === 'fr' ? n < 2 : n === 1
  return t(single ? one : many, { n, ...vars })
}

/** Translates a message from the server that arrived in English (older copies, or a text the server misses). */
export const tr = (text: string) => (current === 'fr' ? dictionary[text] ?? text : text)

// Unit tests run without a page.
if (typeof document !== 'undefined') document.documentElement.lang = current
