import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { Briefcase, CalendarDays, MapPin } from 'lucide-react'
import { api, longDay } from '../api'
import { Button } from '../components/Button'
import { useAsk } from '../components/Dialogs'
import { ErrorText } from '../components/ErrorText'
import { Markdown } from '../components/Markdown'
import { Modal } from '../components/Modal'
import { PageTitle } from '../components/SettingsLink'
import { t } from '../i18n'

type Stage = 'APPLIED' | 'SCREENED' | 'INTERVIEW' | 'OFFER' | 'HIRED' | 'REJECTED' | 'WITHDRAWN' | 'ON_HOLD'
type JobOffer = {
  id: number; title: string; unit: string; place: string | null; contractType: string; closesOn: string | null; openedOn: string | null
  visibility: 'INTERNAL' | 'PUBLIC' | 'INTERNAL_FIRST'; applicationId: number | null; stage: Stage | null
}
type JobOfferAd = { offer: JobOffer; adText: string }

const CONTRACT: Record<string, string> = { PERMANENT: t('Permanent (CDI)'), FIXED_TERM: t('Fixed term (CDD)'), INTERN: t('Internship'), CONSULTANT: t('Consultant'), DAILY: t('Day worker') }
// What the employee sees of their application: plain words, never HR's notes.
const MY_STAGE: Record<Stage, string> = {
  APPLIED: t('Application received'), SCREENED: t('Shortlisted'), INTERVIEW: t('Interview'), OFFER: t('Offer'), HIRED: t('Selected'),
  REJECTED: t('Not selected'), WITHDRAWN: t('Withdrew'), ON_HOLD: t('Under review'),
}
const ACTIVE: Stage[] = ['APPLIED', 'SCREENED', 'INTERVIEW', 'OFFER', 'ON_HOLD']

/** Job offers: the company's open vacancies, which staff apply to in one click. */
export default function JobOffersPage() {
  const [offers, setOffers] = useState<JobOffer[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [params, setParams] = useSearchParams()
  const openId = Number(params.get('offer')) || null
  const load = () => api<JobOffer[]>('/api/job-offers').then(setOffers).catch((e: Error) => setError(e.message))
  useEffect(() => { void load() }, [])
  return (
    <section>
      <PageTitle title={t('Job offers')} />
      <p className="muted">{t('Open posts in the company. Apply in one click: HR sees your file, and your manager hears of it only if you are shortlisted.')}</p>
      <ErrorText error={error} />
      {offers && offers.length === 0 && <div className="card"><p className="muted">{t('No offer is open right now.')}</p></div>}
      <div className="stack">
        {offers?.map((o) => (
          <button key={o.id} type="button" className="card careers-offer" onClick={() => setParams({ offer: String(o.id) })}>
            <div className="row space">
              <h3>{o.title}</h3>
              {o.stage && <span className={o.stage === 'HIRED' ? 'badge ok' : ACTIVE.includes(o.stage) ? 'badge warn' : 'badge'}>{MY_STAGE[o.stage]}</span>}
            </div>
            <p className="careers-facts muted small">
              <span><Briefcase size={14} aria-hidden /> {CONTRACT[o.contractType] ?? o.contractType}</span>
              <span><MapPin size={14} aria-hidden /> {o.place ? `${o.place} · ${o.unit}` : o.unit}</span>
              {o.closesOn && <span><CalendarDays size={14} aria-hidden /> {t('Apply by {d}', { d: longDay(o.closesOn) })}</span>}
              {o.visibility === 'INTERNAL' && <span className="badge">{t('Staff only')}</span>}
            </p>
          </button>
        ))}
      </div>
      {openId && <OfferModal id={openId} onClose={() => { setParams({}); void load() }} />}
    </section>
  )
}

function OfferModal({ id, onClose }: { id: number; onClose: () => void }) {
  const [data, setData] = useState<JobOfferAd | null>(null)
  const [note, setNote] = useState('')
  const [error, setError] = useState<string | null>(null)
  const ask = useAsk()
  useEffect(() => { api<JobOfferAd>(`/api/job-offers/${id}`).then(setData).catch((e: Error) => setError(e.message)) }, [id])
  const o = data?.offer
  const active = !!o?.stage && ACTIVE.includes(o.stage)
  const canApply = !!o && (!o.stage || o.stage === 'WITHDRAWN')
  const apply = async () => {
    setError(null)
    try { setData(await api<JobOfferAd>(`/api/job-offers/${id}/apply`, { method: 'POST', body: { note } })) } catch (e) { setError((e as Error).message) }
  }
  const withdraw = () => ask({ title: t('Withdraw my application'), message: t('HR is told that you no longer apply. You can apply again while the offer is open.'),
    confirmLabel: t('Withdraw'), danger: true,
    onConfirm: async () => { setData(await api<JobOfferAd>(`/api/job-offers/${id}/withdraw`, { method: 'POST' })) } })
  return (
    <Modal title={o?.title ?? t('Job offer')} onClose={onClose} size="lg"
      footer={o && (
        <>
          {canApply && <Button onClick={apply}>{t('Apply for this post')}</Button>}
          {active && <Button className="secondary" onClick={withdraw}>{t('Withdraw my application')}</Button>}
        </>
      )}>
      <ErrorText error={error} />
      {o && data && (
        <div className="stack">
          {o.stage && <p><span className={active ? 'badge warn' : 'badge'}>{MY_STAGE[o.stage]}</span></p>}
          <p className="muted small">{CONTRACT[o.contractType] ?? o.contractType} · {o.place ? `${o.place} · ${o.unit}` : o.unit}
            {o.closesOn ? ` · ${t('Apply by {d}', { d: longDay(o.closesOn) })}` : ''}</p>
          <Markdown text={data.adText} />
          {canApply && <label>{t('A word for HR (optional)')}<textarea maxLength={2000} rows={3} value={note} onChange={(e) => setNote(e.target.value)}
            placeholder={t('Why this post interests you, what you would bring')} /></label>}
        </div>
      )}
    </Modal>
  )
}
