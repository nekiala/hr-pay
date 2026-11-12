import { useEffect, useState, type ReactNode } from 'react'
import { Link, useParams } from 'react-router-dom'
import { Briefcase, CalendarDays, MapPin } from 'lucide-react'
import { api, fileToBase64, longDay } from '../api'
import { Button, Form } from '../components/Button'
import { ErrorText } from '../components/ErrorText'
import { LanguageToggle } from '../components/Language'
import { Markdown } from '../components/Markdown'
import { language, setLanguage, t, type Language } from '../i18n'

type Offer = { id: number; title: string; unit: string; place: string | null; contractType: string; closesOn: string | null; openedOn: string | null }
type Page = { company: string; language: Language; hasLogo: boolean; offers: Offer[] }
type Ad = { offer: Offer; company: string; language: Language; hasLogo: boolean; adText: string }
type Withdrawal = { company: string; title: string; stage: string }

const contractLabel = (c: string) => ({
  PERMANENT: t('Permanent (CDI)'), FIXED_TERM: t('Fixed term (CDD)'), INTERN: t('Internship'), CONSULTANT: t('Consultant'), DAILY: t('Day worker'),
} as Record<string, string>)[c] ?? c

/**
 * The careers page follows the company's language, unless the visitor picked one in this browser: the texts are read
 * when the app loads, so the page loads again in that language.
 */
function followCompanyLanguage(l: Language) {
  let chosen = false
  try { chosen = localStorage.getItem('hrpay.language') != null } catch { /* no storage */ }
  if (!chosen && l !== language()) {
    setLanguage(l)
    window.location.reload()
  }
}

function Frame({ slug, company, hasLogo, children }: { slug?: string; company?: string; hasLogo?: boolean; children: ReactNode }) {
  return (
    <div className="public">
      <header className="public-head">
        {slug ? (
          <Link to={`/careers/${slug}`} className="public-brand">
            {hasLogo && <img className="careers-logo" src={`/api/public/careers/${slug}/logo`} alt="" />}
            {company ?? ''}
          </Link>
        ) : <span className="public-brand">{company ?? ''}</span>}
        <LanguageToggle />
      </header>
      <main className="public-main">{children}</main>
      <footer className="public-links small">
        <span>{t('Careers page by HR Pay')}</span>
        <Link to="/legal/privacy-policy">{t('Privacy policy')}</Link>
      </footer>
    </div>
  )
}

function Facts({ o }: { o: Offer }) {
  return (
    <p className="careers-facts muted small">
      <span><Briefcase size={14} aria-hidden /> {contractLabel(o.contractType)}</span>
      <span><MapPin size={14} aria-hidden /> {o.place ? `${o.place} · ${o.unit}` : o.unit}</span>
      {o.closesOn && <span><CalendarDays size={14} aria-hidden /> {t('Apply by {d}', { d: longDay(o.closesOn) })}</span>}
    </p>
  )
}

/** /careers/{short name}: the company's open offers. */
export function CareersPage() {
  const { slug = '' } = useParams()
  const [page, setPage] = useState<Page | null>(null)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => {
    api<Page>(`/api/public/careers/${encodeURIComponent(slug)}`).then((p) => { followCompanyLanguage(p.language); setPage(p) })
      .catch((e) => setError((e as Error).message))
  }, [slug])
  const src = new URLSearchParams(window.location.search).get('src')
  return (
    <Frame slug={page ? slug : undefined} company={page?.company} hasLogo={page?.hasLogo}>
      <ErrorText error={error} />
      {page && (
        <>
          <h1>{t('Work with us')}</h1>
          {page.offers.length === 0 && <div className="card"><p className="muted">{t('No offer is open right now. Come back soon.')}</p></div>}
          <div className="stack">
            {page.offers.map((o) => (
              <Link key={o.id} to={`/careers/${slug}/${o.id}${src ? `?src=${encodeURIComponent(src)}` : ''}`} className="card careers-offer">
                <h3>{o.title}</h3>
                <Facts o={o} />
              </Link>
            ))}
          </div>
        </>
      )}
    </Frame>
  )
}

/** /careers/{short name}/{offer}: the ad and the form to apply. */
export function CareersOfferPage() {
  const { slug = '', id = '' } = useParams()
  const [ad, setAd] = useState<Ad | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [sent, setSent] = useState(false)
  const [cv, setCv] = useState<File | null>(null)
  const [form, setForm] = useState({ firstName: '', lastName: '', email: '', phone: '', coverLetter: '', keepForFuture: false, consent: false, website: '' })
  useEffect(() => {
    api<Ad>(`/api/public/careers/${encodeURIComponent(slug)}/offers/${id}`).then((a) => { followCompanyLanguage(a.language); setAd(a) })
      .catch((e) => setError((e as Error).message))
  }, [slug, id])
  const set = (k: 'firstName' | 'lastName' | 'email' | 'phone' | 'coverLetter' | 'website') =>
    (e: { target: { value: string } }) => setForm({ ...form, [k]: e.target.value })
  const send = async () => {
    setError(null)
    if (!cv) { setError(t('Attach your CV.')); return }
    if (cv.size > 5 * 1024 * 1024) { setError(t('The CV is larger than 5 MB.')); return }
    try {
      await api(`/api/public/careers/${encodeURIComponent(slug)}/offers/${id}/apply`, {
        method: 'POST',
        body: { ...form, cvName: cv.name, cvType: cv.type, cv: await fileToBase64(cv), channel: new URLSearchParams(window.location.search).get('src') },
      })
      setSent(true)
    } catch (e) { setError((e as Error).message) }
  }
  return (
    <Frame slug={ad ? slug : undefined} company={ad?.company} hasLogo={ad?.hasLogo}>
      {!ad && <ErrorText error={error} />}
      {ad && (
        <>
          <p className="small"><Link to={`/careers/${slug}`}>{t('All our offers')}</Link></p>
          <h1>{ad.offer.title}</h1>
          <Facts o={ad.offer} />
          <div className="card"><Markdown text={ad.adText} /></div>
          <h2>{t('Apply for this post')}</h2>
          {sent ? (
            <div className="card"><p className="success">{t('Thank you. Your application has been received; you will get an email to confirm it.')}</p></div>
          ) : (
            <Form onSubmit={send} className="card stack">
              <div className="grid2">
                <label>{t('First name')}<input required maxLength={100} value={form.firstName} onChange={set('firstName')} autoComplete="given-name" /></label>
                <label>{t('Last name')}<input required maxLength={100} value={form.lastName} onChange={set('lastName')} autoComplete="family-name" /></label>
                <label>{t('Email')}<input required type="email" maxLength={254} value={form.email} onChange={set('email')} autoComplete="email" /></label>
                <label>{t('Phone')}<input required maxLength={20} value={form.phone} onChange={set('phone')} autoComplete="tel" placeholder="+243 …" /></label>
              </div>
              <label>{t('CV (PDF or Word, 5 MB at most)')}
                <input required type="file" accept=".pdf,.doc,.docx,application/pdf,application/msword,application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                  onChange={(e) => setCv(e.target.files?.[0] ?? null)} /></label>
              <label>{t('Cover letter (optional)')}<textarea maxLength={4000} rows={6} value={form.coverLetter} onChange={set('coverLetter')} /></label>
              {/* Left empty by people; robots fill it. */}
              <input className="hp" tabIndex={-1} autoComplete="off" aria-hidden value={form.website} onChange={set('website')} name="website" />
              <label className="check"><input type="checkbox" required checked={form.consent} onChange={(e) => setForm({ ...form, consent: e.target.checked })} />
                {t('I agree that {company} keeps my application and CV to assess it, until 12 months after the offer closes. I can withdraw at any time with the link in the confirmation email.', { company: ad.company })}</label>
              <label className="check"><input type="checkbox" checked={form.keepForFuture} onChange={(e) => setForm({ ...form, keepForFuture: e.target.checked })} />
                {t('Keep my application longer, for future offers.')}</label>
              <p className="small muted">{t('Applying is free. The company never asks candidates for money.')}</p>
              <ErrorText error={error} />
              <Button type="submit">{t('Send my application')}</Button>
            </Form>
          )}
        </>
      )}
    </Frame>
  )
}

/** /careers/withdraw/{token}: the link in the confirmation email. */
export function CareersWithdrawPage() {
  const { token = '' } = useParams()
  const [w, setW] = useState<Withdrawal | null>(null)
  const [done, setDone] = useState(false)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => {
    api<Withdrawal>(`/api/public/careers/withdraw/${encodeURIComponent(token)}`).then(setW).catch((e) => setError((e as Error).message))
  }, [token])
  const withdraw = async () => {
    setError(null)
    try { await api(`/api/public/careers/withdraw/${encodeURIComponent(token)}`, { method: 'POST' }); setDone(true) } catch (e) { setError((e as Error).message) }
  }
  return (
    <Frame company={w?.company}>
      <h1>{t('Withdraw my application')}</h1>
      <div className="card stack">
        {done ? <p className="success">{t('Your application is withdrawn and your data is deleted.')}</p> : w && (
          <>
            <p>{t('Your application for {title} at {company} will be withdrawn, and your data deleted. This cannot be undone.', { title: w.title, company: w.company })}</p>
            <div><Button onClick={withdraw}>{t('Withdraw and delete my data')}</Button></div>
          </>
        )}
        <ErrorText error={error} />
      </div>
    </Frame>
  )
}
