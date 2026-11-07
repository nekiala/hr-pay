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
  terms = country === 'CG' ? [[/\bCDF\b/g, local || 'XAF'], [/\bIPR\b/g, 'ITS']] : []
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

export function t(english: string, vars?: Record<string, string | number | null | undefined>): string {
  let text = current === 'fr' ? dictionary[english] ?? english : english
  for (const [re, word] of terms) text = text.replace(re, word)
  if (vars) {
    for (const [k, v] of Object.entries(vars)) {
      text = text.split(`{${k}}`).join(v === null || v === undefined ? '' : typeof v === 'number' ? v.toLocaleString(locale()) : v)
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

document.documentElement.lang = current
