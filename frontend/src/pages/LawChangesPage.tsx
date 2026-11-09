import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { Scale } from 'lucide-react'
import { ApiError, api, longDay } from '../api'
import { Button, Form } from '../components/Button'
import { useAsk } from '../components/Dialogs'
import { ErrorText } from '../components/ErrorText'
import { Modal } from '../components/Modal'
import { language, t } from '../i18n'
import { Public } from './PublicPages'

type Rate = { code: string; effectiveFrom: string; value: number }
export type LawChange = {
  id: number; countryCode: 'CD' | 'CG' | 'CM' | 'GA'; effectiveFrom: string; category: string; titleEn: string; titleFr: string
  summaryEn: string; summaryFr: string; reference: string; sourceUrl: string | null; inApp: 'APPLIED' | 'COMING' | 'INFO'
  inAppNoteEn: string | null; inAppNoteFr: string | null; verifiedBy: string | null; verifiedOn: string | null
  status: 'DRAFT' | 'PUBLISHED' | 'WITHDRAWN'; publishedAt: string | null; appliedTo: number; rates: Rate[]
  sectorPackCode: string | null; sectorNameEn: string | null; sectorNameFr: string | null
}
type PackName = { code: string; countryCode: string; nameEn: string; nameFr: string; status: string }

const COUNTRY: Record<string, string> = { CD: t('DR Congo'), CG: t('Republic of the Congo'), CM: t('Cameroon'), GA: t('Gabon') }
const CATEGORY: Record<string, string> = {
  TAX: t('Tax'), SOCIAL: t('Social contributions'), WAGE: t('Minimum wage'), LABOUR: t('Labour law'), DECLARATION: t('Declarations'),
}
const IN_APP: Record<string, [string, string]> = {
  APPLIED: [t('Applied in HR Pay'), 'ok'], COMING: [t('Coming to HR Pay'), 'warn'], INFO: [t('For information'), 'info'],
}
const fr = () => language() === 'fr'
const title = (c: LawChange) => (fr() ? c.titleFr : c.titleEn)
const fail = (e: unknown) => e instanceof ApiError ? [e.message, ...Object.values(e.fieldErrors)].join(' ') : t('Something went wrong.')

function ChangeCard({ c }: { c: LawChange }) {
  const note = fr() ? c.inAppNoteFr : c.inAppNoteEn
  return (
    <article className="card" id={String(c.id)}>
      <p className="small muted">{COUNTRY[c.countryCode]}{c.sectorPackCode && <> · {t('Sector: {a}', { a: (fr() ? c.sectorNameFr : c.sectorNameEn) ?? c.sectorPackCode })}</>} · {CATEGORY[c.category] ?? c.category} · {t('in force from {a}', { a: longDay(c.effectiveFrom) })}</p>
      <h3>{title(c)}</h3>
      <p>{fr() ? c.summaryFr : c.summaryEn}</p>
      <p className="small"><strong>{t('Reference')}:</strong> {c.sourceUrl ? <a href={c.sourceUrl} target="_blank" rel="noreferrer">{c.reference}</a> : c.reference}</p>
      <p className="small"><span className={`badge ${IN_APP[c.inApp][1]}`}>{IN_APP[c.inApp][0]}</span>{note && <> {note}</>}</p>
      {c.verifiedBy && <p className="small muted">{t('Checked by {a}', { a: c.verifiedBy })}{c.verifiedOn ? `, ${longDay(c.verifiedOn)}` : ''}</p>}
    </article>
  )
}

/** The public, dated record of the law and rate changes HR Pay follows. */
export function LawChangesPublicPage() {
  const [country, setCountry] = useState('')
  const [list, setList] = useState<LawChange[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => {
    api<LawChange[]>(`/api/public/changes${country ? `?country=${country}` : ''}`).then(setList).catch((e) => setError(fail(e)))
  }, [country])
  useEffect(() => {
    if (list && location.hash) document.getElementById(location.hash.slice(1))?.scrollIntoView()
  }, [list])
  return (
    <Public>
      <h1>{t('Law and rate changes')}</h1>
      <p className="muted">{t('The payroll laws, rates and deadlines HR Pay follows in the DR Congo and the Republic of the Congo, with their official reference and what HR Pay does about each.')}</p>
      <div className="row">
        <select value={country} onChange={(e) => setCountry(e.target.value)} aria-label={t('Country')}>
          <option value="">{t('Every country')}</option>{Object.entries(COUNTRY).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
        </select>
        <a className="button secondary" href={`/api/public/changes.rss?lang=${language()}${country ? `&country=${country}` : ''}`}>{t('RSS feed')}</a>
      </div>
      <ErrorText error={error} />
      {list && list.length === 0 && <p className="muted">{t('No change published yet.')}</p>}
      {list?.map((c) => <ChangeCard key={c.id} c={c} />)}
      <p className="hint">{t('For information: this page does not replace the official texts. Rates applied by HR Pay can be changed by each company.')}</p>
    </Public>
  )
}

/** On the home page: the changes published for the company's country in the last 30 days. */
export function RecentChangesBanner() {
  const [list, setList] = useState<LawChange[]>([])
  useEffect(() => { api<LawChange[]>('/api/changes/recent').then(setList).catch(() => setList([])) }, [])
  if (list.length === 0) return null
  return (
    <div className="banner">
      <span className="banner-icon"><Scale size={20} /></span>
      <div style={{ flex: 1 }}>
        <strong>{t('What changed in the law')}</strong>
        <span className="muted">{list.slice(0, 3).map((c) => `${title(c)} (${longDay(c.effectiveFrom)})`).join(' · ')}</span>
      </div>
      <a className="button secondary" href="/changes" target="_blank" rel="noreferrer">{t('See the changes')}</a>
    </div>
  )
}

type Draft = Omit<LawChange, 'id' | 'status' | 'publishedAt' | 'appliedTo' | 'sectorNameEn' | 'sectorNameFr'>
const empty = (): Draft => ({
  countryCode: 'CD', effectiveFrom: new Date().toISOString().slice(0, 10), category: 'TAX', titleEn: '', titleFr: '', summaryEn: '', summaryFr: '',
  reference: '', sourceUrl: '', inApp: 'APPLIED', inAppNoteEn: '', inAppNoteFr: '', verifiedBy: '', verifiedOn: null, rates: [],
  sectorPackCode: null,
})

/** The provider writes, publishes and withdraws the changes; publishing adds their rates to the companies. */
export function LawChangesAdminPage() {
  const ask = useAsk()
  const [list, setList] = useState<LawChange[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const [editing, setEditing] = useState<{ id: number | null; status: string; d: Draft } | null>(null)

  const [packs, setPacks] = useState<PackName[]>([])
  const load = () => api<LawChange[]>('/api/platform/changes').then(setList).catch((e) => setError(fail(e)))
  useEffect(() => { void load(); api<PackName[]>('/api/platform/sector-packs').then(setPacks).catch(() => setPacks([])) }, [])
  const run = async (work: () => Promise<unknown>, done: string) => {
    setError(null)
    setMessage(null)
    try { await work(); setMessage(done); await load(); return true } catch (e) { setError(fail(e)); return false }
  }
  const set = (patch: Partial<Draft>) => editing && setEditing({ ...editing, d: { ...editing.d, ...patch } })
  const ratesLocked = editing !== null && editing.status !== 'DRAFT'

  return (
    <section>
      <div className="row space">
        <h2>{t('Law and rate changes')}</h2>
        <div className="row">
          <Link className="button secondary" to="/platform/sector-packs">{t('Sector packs')}</Link>
          <Link className="button secondary" to="/changes" target="_blank">{t('Public page')}</Link>
          <Button onClick={() => setEditing({ id: null, status: 'DRAFT', d: empty() })}>{t('New change')}</Button>
        </div>
      </div>
      <ErrorText error={error} />
      {message && <p className="success">{message}</p>}
      <div className="card">
        <div className="table-wrap">
          <table>
            <thead><tr><th>{t('In force')}</th><th>{t('Country')}</th><th>{t('Change')}</th><th>{t('Rates')}</th><th>{t('Status')}</th><th></th></tr></thead>
            <tbody>
              {list?.map((c) => (
                <tr key={c.id}>
                  <td>{longDay(c.effectiveFrom)}</td><td>{c.countryCode}{c.sectorPackCode && <div className="small muted">{c.sectorPackCode}</div>}</td>
                  <td><strong>{title(c)}</strong><div className="small muted">{c.reference}</div>
                    {!c.verifiedBy && <span className="badge warn">{t('To confirm')}</span>}</td>
                  <td className="small">{c.rates.map((r) => `${r.code} ${r.value} (${r.effectiveFrom})`).join(', ') || '—'}</td>
                  <td>{c.status === 'PUBLISHED' ? <span className="badge ok">{t('Published')}</span> : c.status === 'DRAFT' ? <span className="badge warn">{t('Draft')}</span> : <span className="badge">{t('Withdrawn')}</span>}
                    {c.status === 'PUBLISHED' && c.rates.length > 0 && <div className="small muted">{t('{a} companies updated', { a: c.appliedTo })}</div>}</td>
                  <td>
                    {c.status !== 'WITHDRAWN' && <Button className="link" onClick={() => setEditing({ id: c.id, status: c.status, d: { ...c } })}>{t('Edit')}</Button>}
                    {c.status === 'DRAFT' && <>{' '}<Button className="link" onClick={() => ask({
                      title: t('Publish “{a}”?', { a: title(c) }), confirmLabel: t('Publish'),
                      message: c.sectorPackCode ? t('It goes on the public page; the companies on the sector pack {a} get its rates and are told to review the pack.', { a: c.sectorPackCode })
                        : c.rates.length ? t('It goes on the public page, and its rates are added to every company in {a} that has no value of its own for that date. Their payroll staff are told.', { a: COUNTRY[c.countryCode] }) : t('It goes on the public page.'),
                      onConfirm: () => run(() => api(`/api/platform/changes/${c.id}/publish`, { method: 'POST' }), t('Published.')),
                    })}>{t('Publish')}</Button>{' '}
                      <Button className="link" onClick={() => ask({ title: t('Delete this draft?'), confirmLabel: t('Delete'), danger: true,
                        onConfirm: () => run(() => api(`/api/platform/changes/${c.id}`, { method: 'DELETE' }), t('Draft deleted.')) })}>{t('Delete')}</Button></>}
                    {c.status === 'PUBLISHED' && <>{' '}<Button className="link" onClick={() => ask({ title: t('Withdraw “{a}”?', { a: title(c) }), confirmLabel: t('Withdraw'), danger: true,
                      message: t('It leaves the public page. The rates it gave the companies stay; each company can change them.'),
                      onConfirm: () => run(() => api(`/api/platform/changes/${c.id}/withdraw`, { method: 'POST' }), t('Withdrawn.')) })}>{t('Withdraw')}</Button></>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      {editing && (
        <Modal size="lg" title={editing.id ? t('Edit the change') : t('New change')} onClose={() => setEditing(null)}>
          <Form onSubmit={async () => {
            const body = { ...editing.d, rates: editing.d.rates.map((r) => ({ ...r, value: Number(r.value) })) }
            const ok = await run(() => editing.id ? api(`/api/platform/changes/${editing.id}`, { method: 'PUT', body }) : api('/api/platform/changes', { method: 'POST', body }), t('Saved.'))
            if (ok) setEditing(null)
          }}>
            <div className="grid2">
              <label>{t('Country')}<select disabled={ratesLocked} value={editing.d.countryCode} onChange={(e) => set({ countryCode: e.target.value as Draft['countryCode'] })}>
                {Object.entries(COUNTRY).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select></label>
              <label>{t('Sector')}<select disabled={ratesLocked} value={editing.d.sectorPackCode ?? ''} onChange={(e) => set({ sectorPackCode: e.target.value || null })}>
                <option value="">{t('All companies of the country')}</option>
                {packs.filter((p) => p.countryCode === editing.d.countryCode && p.status !== 'RETIRED').map((p) => <option key={p.code} value={p.code}>{fr() ? p.nameFr : p.nameEn}</option>)}
              </select></label>
              <label>{t('In force from')}<input type="date" required value={editing.d.effectiveFrom} onChange={(e) => set({ effectiveFrom: e.target.value })} /></label>
              <label>{t('Kind')}<select value={editing.d.category} onChange={(e) => set({ category: e.target.value })}>
                {Object.entries(CATEGORY).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select></label>
              <label>{t('In HR Pay')}<select value={editing.d.inApp} onChange={(e) => set({ inApp: e.target.value as Draft['inApp'] })}>
                {Object.entries(IN_APP).map(([k, v]) => <option key={k} value={k}>{v[0]}</option>)}</select></label>
              <label>{t('Title (English)')}<input required maxLength={200} value={editing.d.titleEn} onChange={(e) => set({ titleEn: e.target.value })} /></label>
              <label>{t('Title (French)')}<input required maxLength={200} value={editing.d.titleFr} onChange={(e) => set({ titleFr: e.target.value })} /></label>
              <label>{t('Summary (English)')}<textarea required maxLength={2000} rows={4} value={editing.d.summaryEn} onChange={(e) => set({ summaryEn: e.target.value })} /></label>
              <label>{t('Summary (French)')}<textarea required maxLength={2000} rows={4} value={editing.d.summaryFr} onChange={(e) => set({ summaryFr: e.target.value })} /></label>
              <label>{t('What HR Pay does (English)')}<input maxLength={500} value={editing.d.inAppNoteEn ?? ''} onChange={(e) => set({ inAppNoteEn: e.target.value })} /></label>
              <label>{t('What HR Pay does (French)')}<input maxLength={500} value={editing.d.inAppNoteFr ?? ''} onChange={(e) => set({ inAppNoteFr: e.target.value })} /></label>
              <label>{t('Legal reference')}<input required maxLength={300} value={editing.d.reference} onChange={(e) => set({ reference: e.target.value })} /></label>
              <label>{t('Link to the official text')}<input type="url" maxLength={500} placeholder="https://" value={editing.d.sourceUrl ?? ''} onChange={(e) => set({ sourceUrl: e.target.value })} /></label>
              <label>{t('Checked by')}<input maxLength={200} value={editing.d.verifiedBy ?? ''} onChange={(e) => set({ verifiedBy: e.target.value })} /></label>
              <label>{t('Checked on')}<input type="date" value={editing.d.verifiedOn ?? ''} onChange={(e) => set({ verifiedOn: e.target.value || null })} /></label>
            </div>
            <fieldset>
              <legend>{t('Rates added to the companies on publishing')}</legend>
              {editing.d.rates.map((r, i) => (
                <div className="row" key={i}>
                  <input aria-label={t('Code')} placeholder="ONEM" disabled={ratesLocked} value={r.code} onChange={(e) => set({ rates: editing.d.rates.map((x, j) => j === i ? { ...x, code: e.target.value.toUpperCase() } : x) })} />
                  <input aria-label={t('From')} type="date" disabled={ratesLocked} value={r.effectiveFrom} onChange={(e) => set({ rates: editing.d.rates.map((x, j) => j === i ? { ...x, effectiveFrom: e.target.value } : x) })} />
                  <input aria-label={t('Value')} type="number" step="any" min="0" disabled={ratesLocked} value={r.value} onChange={(e) => set({ rates: editing.d.rates.map((x, j) => j === i ? { ...x, value: e.target.value as unknown as number } : x) })} />
                  {!ratesLocked && <Button className="link" onClick={() => set({ rates: editing.d.rates.filter((_, j) => j !== i) })}>{t('Remove')}</Button>}
                </div>
              ))}
              {!ratesLocked && <Button className="secondary" onClick={() => set({ rates: [...editing.d.rates, { code: '', effectiveFrom: editing.d.effectiveFrom, value: 0 }] })}>{t('Add a rate')}</Button>}
              <p className="hint">{ratesLocked ? t('A published change keeps its rates: withdraw it and write a new one.') : t('Use the codes of the statutory rates page (ONEM, SMIG_DAILY, TUS…). A company that already has a value on that date keeps it.')}</p>
            </fieldset>
            <div className="modal-foot"><Button className="secondary" onClick={() => setEditing(null)}>{t('Cancel')}</Button><Button type="submit">{t('Save')}</Button></div>
          </Form>
        </Modal>
      )}
    </section>
  )
}
