import { useState } from 'react'
import { api, type Me } from '../api'
import { ErrorText } from './ErrorText'
import { language, setLanguage, t, type Language } from '../i18n'

/** Each language by its own name, so anyone finds theirs. */
const NAMES: Record<Language, string> = { fr: 'Français', en: 'English' }

/** Shows the page again in another language: every text is read once, when the page loads. */
function switchTo(l: Language) {
  if (l === language()) return
  setLanguage(l)
  window.location.reload()
}

/** On the sign-in page, before anyone is known: a choice kept in this browser. */
export function LanguageToggle() {
  return (
    <div className="segmented language-toggle" role="group" aria-label={t('Language')}>
      {(['fr', 'en'] as Language[]).map((l) => (
        <button key={l} type="button" lang={l} className={language() === l ? 'on' : ''} onClick={() => switchTo(l)}>{NAMES[l]}</button>
      ))}
    </div>
  )
}

/** On My account: the person's own language, or the company's. */
export function LanguageCard({ me }: { me: Me }) {
  const [error, setError] = useState<string | null>(null)
  const company = me.company?.language ?? 'fr'
  const save = async (value: string) => {
    setError(null)
    try {
      await api('/api/account/language', { method: 'PUT', body: { language: value || null } })
      setLanguage((value || company) as Language)
      window.location.reload()
    } catch (e) {
      setError(e instanceof Error ? e.message : t('Could not save.'))
    }
  }
  return (
    <div className="card">
      <h3>{t('Language')}</h3>
      <label>{t('Show HR Pay in')}
        <select value={me.ownLanguage ?? ''} onChange={(e) => void save(e.target.value)}>
          <option value="">{t('The company\'s language ({a})', { a: NAMES[company] })}</option>
          <option value="fr" lang="fr">{NAMES.fr}</option>
          <option value="en" lang="en">{NAMES.en}</option>
        </select>
      </label>
      <p className="hint">{t('Dates and amounts follow the language. Emails, payslips and WhatsApp messages to you use it too.')}</p>
      <ErrorText error={error} />
    </div>
  )
}
