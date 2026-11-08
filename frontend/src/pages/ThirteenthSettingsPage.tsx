import { Link } from 'react-router-dom'
import { ThirteenthMonthCard } from '../components/ThirteenthMonthCard'
import { t } from '../i18n'

/** The 13th month rules: whether the company pays one, its base, fallback score and default pay month. */
export default function ThirteenthSettingsPage() {
  return (
    <section>
      <h2>{t('13th month settings')}</h2>
      <p className="muted small"><Link to="/payroll/new?type=THIRTEENTH">{t('New pay run')}</Link></p>
      <ThirteenthMonthCard />
    </section>
  )
}
