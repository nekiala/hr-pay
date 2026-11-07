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

export function t(english: string, vars?: Record<string, string | number | null | undefined>): string {
  let text = current === 'fr' ? dictionary[english] ?? english : english
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
