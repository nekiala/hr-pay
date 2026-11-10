import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { ApiError, api, longDay } from '../api'
import { useAuth } from '../auth'
import { Button, Form } from '../components/Button'
import { useAsk } from '../components/Dialogs'
import { ErrorText } from '../components/ErrorText'
import { Modal } from '../components/Modal'
import { language, t } from '../i18n'

type Kind = 'SETTING' | 'LEAVE_DAYS' | 'OVERTIME' | 'RATE' | 'NOTE'
type Rule = {
  id?: number | null; kind: Kind; key: string | null; fromHour: number | null; toHour: number | null; value: string | null
  labelEn: string; labelFr: string; article: string | null; position?: number | null
}
type Pack = {
  code: string; countryCode: 'CD' | 'CG' | 'CM' | 'GA'; nameEn: string; nameFr: string; scopeEn: string | null; scopeFr: string | null
  reference: string | null; sourceUrl: string | null; status: 'FRAME' | 'READY' | 'RETIRED'; verifiedBy: string | null
  verifiedOn: string | null; displayOrder: number; updatedAt: string; companies: number; rules: Rule[]
}
type Line = { kind: Kind; key: string | null; labelEn: string; labelFr: string; article: string | null; current: string | null; next: string | null; changes: boolean; applies: boolean }
type Preview = { pack: Pack; from: string; lines: Line[]; changes: number }
type CompanySector = { country: string; current: string | null; appliedAt: string | null; appliedBy: string | null; packs: Pack[]; activity: string | null; suggested: string[] }

const fr = () => language() === 'fr'
const name = (p: Pack) => (fr() ? p.nameFr : p.nameEn)
const label = (x: { labelEn: string; labelFr: string }) => (fr() ? x.labelFr : x.labelEn)
const fail = (e: unknown) => e instanceof ApiError ? [e.message, ...Object.values(e.fieldErrors)].join(' ') : t('Something went wrong.')
const KIND: Record<Kind, string> = {
  SETTING: t('Setting'), LEAVE_DAYS: t('Leave days'), OVERTIME: t('Overtime'), RATE: t('Rate'), NOTE: t('Note'),
}
const STATUS: Record<Pack['status'], [string, string]> = {
  READY: [t('Ready'), 'ok'], FRAME: [t('Waiting for the agreement'), 'warn'], RETIRED: [t('Retired'), ''],
}
const nextMonth = () => {
  const d = new Date()
  return new Date(Date.UTC(d.getFullYear(), d.getMonth() + 1, 1)).toISOString().slice(0, 10)
}

/** The seniority bonus and end-of-career settings, which a pack or HR sets. */
const AGREEMENT_SETTINGS: [string, string, string][] = [
  ['SENIORITY_BONUS_PERCENT_PER_YEAR', t('Seniority bonus, % of the basic per year of service'), t('0 = no seniority bonus.')],
  ['SENIORITY_BONUS_FROM_YEARS', t('Seniority bonus from (years of service)'), t('The bonus starts after this many full years.')],
  ['SENIORITY_BONUS_MAX_PERCENT', t('Seniority bonus ceiling (% of the basic)'), t('0 = no ceiling.')],
  ['END_OF_CAREER_DAYS_PER_YEAR', t('End-of-career payment, days of pay per year of service'), t('Paid on retirement. 0 = none.')],
  ['END_OF_CAREER_MAX_MONTHS', t('End-of-career payment ceiling (months of pay)'), t('0 = no ceiling.')],
]

function AgreementSettings() {
  const [values, setValues] = useState<Record<string, string> | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  useEffect(() => {
    api<{ settings: Record<string, string> }>('/api/statutory').then((o) => setValues(o.settings)).catch((e) => setError(fail(e)))
  }, [])
  if (!values) return <ErrorText error={error} />
  return (
    <div className="card">
      <h3>{t('Seniority bonus and end-of-career payment')}</h3>
      <p className="hint">{t('The law leaves them to branch and company agreements. A sector pack fills them in; change them here if your company agreement says otherwise.')}</p>
      <Form onSubmit={async () => {
        setError(null)
        setMessage(null)
        try {
          for (const [key] of AGREEMENT_SETTINGS) {
            await api(`/api/statutory/settings/${key}`, { method: 'PUT', body: { value: values[key] ?? '0' } })
          }
          setMessage(t('Saved.'))
        } catch (e) { setError(fail(e)) }
      }}>
        <div className="grid2">
          {AGREEMENT_SETTINGS.map(([key, text, hint]) => (
            <label key={key}>{text}
              <input inputMode="decimal" value={values[key] ?? '0'} onChange={(e) => setValues({ ...values, [key]: e.target.value })} />
              <span className="hint">{hint}</span>
            </label>
          ))}
        </div>
        <ErrorText error={error} />
        {message && <p className="success">{message}</p>}
        <Button type="submit">{t('Save')}</Button>
      </Form>
    </div>
  )
}

/** The company chooses the pack of its collective agreement, sees what changes, and applies it. */
export function SectorPage() {
  const { can } = useAuth()
  const ask = useAsk()
  const [data, setData] = useState<CompanySector | null>(null)
  const [chosen, setChosen] = useState<string>('')
  const [from, setFrom] = useState(nextMonth())
  const [preview, setPreview] = useState<Preview | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const edit = can('masterdata.manage')

  const load = () => api<CompanySector>('/api/sector-packs').then((d) => {
    setData(d)
    setChosen((c) => c || d.current || d.packs.find((p) => p.status === 'READY')?.code || '')
  }).catch((e) => setError(fail(e)))
  useEffect(() => { void load() }, [])
  const pack = data?.packs.find((p) => p.code === chosen) ?? null
  useEffect(() => {
    setPreview(null)
    if (!pack || pack.status !== 'READY' || !from) return
    api<Preview>(`/api/sector-packs/${pack.code}/preview?from=${from}`).then(setPreview).catch((e) => setError(fail(e)))
  }, [pack?.code, from])

  if (!data) return <section><h2>{t('Sector agreement')}</h2><ErrorText error={error} /></section>
  const current = data.packs.find((p) => p.code === data.current)
  return (
    <section>
      <div className="row space">
        <h2>{t('Sector agreement')}</h2>
        <a className="button secondary" href="/changes" target="_blank" rel="noreferrer">{t('Law and rate changes')}</a>
      </div>
      <p className="muted">{t('Collective agreements add to the Labour Code: more leave, other overtime rates, a seniority bonus… Choose the pack of your sector to set them in HR Pay. Nothing changes before you apply it.')}</p>
      <div className="card">
        {current
          ? <p>{t('Your company follows: {a}', { a: name(current) })}{data.appliedAt && <span className="muted"> · {t('applied on {a} by {b}', { a: longDay(data.appliedAt.slice(0, 10)), b: data.appliedBy ?? '' })}</span>}</p>
          : <p className="muted">{t('No sector pack applied yet: the country rules and your own settings apply.')}</p>}
        {data.suggested.filter((c) => c !== data.current).map((c) => {
          const p = data.packs.find((x) => x.code === c)
          return p && <p key={c} className="hint">{t('For your branch of activity ({a}), the pack is: {b}.', { a: data.activity ?? '', b: name(p) })}{' '}
            {chosen !== c && <button type="button" className="link" onClick={() => setChosen(c)}>{t('See it')}</button>}</p>
        })}
        <div className="grid2">
          <label>{t('Sector pack')}
            <select value={chosen} onChange={(e) => setChosen(e.target.value)}>
              {data.packs.map((p) => <option key={p.code} value={p.code}>{name(p)}{p.status === 'FRAME' ? ` (${t('waiting for the agreement')})` : ''}</option>)}
            </select>
          </label>
          <label>{t('Overtime and rates change from')}
            <input type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
            <span className="hint">{t('Leave and bonus settings change at once; months already closed never change.')}</span>
          </label>
        </div>
        {pack && <>
          <p className="small"><span className={`badge ${STATUS[pack.status][1]}`}>{STATUS[pack.status][0]}</span>{' '}
            {pack.reference && (pack.sourceUrl ? <a href={pack.sourceUrl} target="_blank" rel="noreferrer">{pack.reference}</a> : pack.reference)}</p>
          {(fr() ? pack.scopeFr : pack.scopeEn) && <p className="small muted">{fr() ? pack.scopeFr : pack.scopeEn}</p>}
          {pack.verifiedBy ? <p className="small muted">{t('Checked by {a}', { a: pack.verifiedBy })}{pack.verifiedOn ? `, ${longDay(pack.verifiedOn)}` : ''}</p>
            : pack.status === 'READY' && <p className="small"><span className="badge warn">{t('To confirm')}</span> {t('Not yet checked by a payroll expert: compare with your agreement before applying.')}</p>}
          {pack.status === 'FRAME' && <p className="hint">{t('HR Pay does not have this agreement’s text yet. Send it to support and we add its rules; meanwhile set your rules on the settings pages.')}</p>}
        </>}
      </div>
      <ErrorText error={error} />
      {message && <p className="success">{message}</p>}
      {preview && (
        <div className="card">
          <div className="row space">
            <h3>{t('What the pack changes')}</h3>
            {edit && <Button disabled={preview.changes === 0} onClick={() => ask({
              title: t('Apply “{a}”?', { a: name(preview.pack) }), confirmLabel: t('Apply'),
              message: t('{a} settings change. Overtime and rates change from {b}; payroll months already closed do not change.', { a: preview.changes, b: longDay(from) }),
              onConfirm: async () => {
                setError(null)
                try {
                  await api(`/api/sector-packs/${preview.pack.code}/apply`, { method: 'POST', body: { from } })
                  setMessage(t('Pack applied.'))
                  await load()
                  setPreview(await api<Preview>(`/api/sector-packs/${preview.pack.code}/preview?from=${from}`))
                } catch (e) { setError(fail(e)) }
              },
            })}>{preview.changes === 0 ? t('Nothing to change') : t('Apply the pack')}</Button>}
          </div>
          <div className="table-wrap">
            <table>
              <thead><tr><th>{t('Rule')}</th><th>{t('Article')}</th><th>{t('Now')}</th><th>{t('With the pack')}</th></tr></thead>
              <tbody>
                {preview.lines.map((l, i) => (
                  <tr key={i}>
                    <td>{label(l)}{l.kind === 'LEAVE_DAYS' && !l.applies && <div className="small muted">{t('This leave type is not in your company.')}</div>}</td>
                    <td className="small">{l.article}</td>
                    <td>{l.kind === 'NOTE' ? <span className="small muted">{t('Not applied by HR Pay')}</span> : l.current ?? '—'}</td>
                    <td>{l.kind === 'NOTE' ? '' : l.changes ? <strong>{l.next}</strong> : <span className="muted">{l.next}</span>}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
      {edit && <AgreementSettings />}
    </section>
  )
}

const emptyPack = (): Omit<Pack, 'updatedAt' | 'companies'> => ({
  code: '', countryCode: 'CD', nameEn: '', nameFr: '', scopeEn: '', scopeFr: '', reference: '', sourceUrl: '', status: 'FRAME',
  verifiedBy: '', verifiedOn: null, displayOrder: 100, rules: [],
})
const emptyRule = (): Rule => ({ kind: 'SETTING', key: '', fromHour: null, toHour: null, value: '', labelEn: '', labelFr: '', article: '' })

/** The provider keeps the sector packs: their agreement, rules and status. */
export function SectorPacksAdminPage() {
  const ask = useAsk()
  const [list, setList] = useState<Pack[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const [editing, setEditing] = useState<{ isNew: boolean; p: Omit<Pack, 'updatedAt' | 'companies'> } | null>(null)

  const load = () => api<Pack[]>('/api/platform/sector-packs').then(setList).catch((e) => setError(fail(e)))
  useEffect(() => { void load() }, [])
  const set = (patch: Partial<Pack>) => editing && setEditing({ ...editing, p: { ...editing.p, ...patch } })
  const setRule = (i: number, patch: Partial<Rule>) => editing && set({ rules: editing.p.rules.map((r, j) => j === i ? { ...r, ...patch } : r) })

  return (
    <section>
      <div className="row space">
        <h2>{t('Sector packs')}</h2>
        <div className="row">
          <Link className="button secondary" to="/platform/changes">{t('Law changes')}</Link>
          <Button onClick={() => setEditing({ isNew: true, p: emptyPack() })}>{t('New pack')}</Button>
        </div>
      </div>
      <p className="hint">{t('A change to an agreement goes on the law page as a change for its sector: the companies on the pack are told to review it.')}</p>
      <ErrorText error={error} />
      {message && <p className="success">{message}</p>}
      <div className="card">
        <div className="table-wrap">
          <table>
            <thead><tr><th>{t('Country')}</th><th>{t('Pack')}</th><th>{t('Rules')}</th><th>{t('Companies')}</th><th>{t('Status')}</th><th></th></tr></thead>
            <tbody>
              {list?.map((p) => (
                <tr key={p.code}>
                  <td>{p.countryCode}</td>
                  <td><strong>{name(p)}</strong><div className="small muted">{p.code}{p.reference ? ` · ${p.reference}` : ''}</div>
                    {p.status === 'READY' && !p.verifiedBy && <span className="badge warn">{t('To confirm')}</span>}</td>
                  <td>{p.rules.length}</td>
                  <td>{p.companies}</td>
                  <td><span className={`badge ${STATUS[p.status][1]}`}>{STATUS[p.status][0]}</span></td>
                  <td>
                    <Button className="link" onClick={() => setEditing({ isNew: false, p: { ...p } })}>{t('Edit')}</Button>
                    {p.companies === 0 && <>{' '}<Button className="link" onClick={() => ask({ title: t('Delete this pack?'), confirmLabel: t('Delete'), danger: true,
                      onConfirm: async () => { try { await api(`/api/platform/sector-packs/${p.code}`, { method: 'DELETE' }); setMessage(t('Pack deleted.')); await load() } catch (e) { setError(fail(e)) } } })}>{t('Delete')}</Button></>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      {editing && (
        <Modal size="lg" title={editing.isNew ? t('New pack') : t('Edit the pack')} onClose={() => setEditing(null)}>
          <Form onSubmit={async () => {
            setError(null)
            try {
              const { code, ...body } = editing.p
              await api(`/api/platform/sector-packs/${code}`, { method: 'PUT', body: { ...body, rules: body.rules.map((r) => ({
                ...r, fromHour: r.fromHour === null || (r.fromHour as unknown) === '' ? null : Number(r.fromHour),
                toHour: r.toHour === null || (r.toHour as unknown) === '' ? null : Number(r.toHour),
              })) } })
              setMessage(t('Saved.'))
              setEditing(null)
              await load()
            } catch (e) { setError(fail(e)) }
          }}>
            <ErrorText error={error} />
            <div className="grid2">
              <label>{t('Code')}<input required disabled={!editing.isNew} pattern="[A-Z0-9_]{2,40}" value={editing.p.code} onChange={(e) => set({ code: e.target.value.toUpperCase() })} /></label>
              <label>{t('Country')}<select value={editing.p.countryCode} onChange={(e) => set({ countryCode: e.target.value as Pack['countryCode'] })}>
                <option value="CD">{t('DR Congo')}</option><option value="CG">{t('Republic of the Congo')}</option>
                <option value="CM">{t('Cameroon')}</option><option value="GA">{t('Gabon')}</option></select></label>
              <label>{t('Name (English)')}<input required maxLength={150} value={editing.p.nameEn} onChange={(e) => set({ nameEn: e.target.value })} /></label>
              <label>{t('Name (French)')}<input required maxLength={150} value={editing.p.nameFr} onChange={(e) => set({ nameFr: e.target.value })} /></label>
              <label>{t('Who it covers (English)')}<textarea maxLength={1000} rows={2} value={editing.p.scopeEn ?? ''} onChange={(e) => set({ scopeEn: e.target.value })} /></label>
              <label>{t('Who it covers (French)')}<textarea maxLength={1000} rows={2} value={editing.p.scopeFr ?? ''} onChange={(e) => set({ scopeFr: e.target.value })} /></label>
              <label>{t('Agreement')}<input maxLength={300} value={editing.p.reference ?? ''} onChange={(e) => set({ reference: e.target.value })} /></label>
              <label>{t('Link to the text')}<input type="url" maxLength={500} placeholder="https://" value={editing.p.sourceUrl ?? ''} onChange={(e) => set({ sourceUrl: e.target.value })} /></label>
              <label>{t('Status')}<select value={editing.p.status} onChange={(e) => set({ status: e.target.value as Pack['status'] })}>
                {Object.entries(STATUS).map(([k, v]) => <option key={k} value={k}>{v[0]}</option>)}</select></label>
              <label>{t('Order')}<input type="number" min={0} value={editing.p.displayOrder} onChange={(e) => set({ displayOrder: Number(e.target.value) })} /></label>
              <label>{t('Checked by')}<input maxLength={150} value={editing.p.verifiedBy ?? ''} onChange={(e) => set({ verifiedBy: e.target.value })} /></label>
              <label>{t('Checked on')}<input type="date" value={editing.p.verifiedOn ?? ''} onChange={(e) => set({ verifiedOn: e.target.value || null })} /></label>
            </div>
            <fieldset>
              <legend>{t('Rules')}</legend>
              {editing.p.rules.map((r, i) => (
                <div className="card" key={i}>
                  <div className="row">
                    <select aria-label={t('Kind')} value={r.kind} onChange={(e) => setRule(i, { kind: e.target.value as Kind })}>
                      {Object.entries(KIND).map(([k, v]) => <option key={k} value={k}>{v}</option>)}</select>
                    {r.kind !== 'NOTE' && <input aria-label={t('Key')} placeholder={r.kind === 'OVERTIME' ? 'WEEKDAY' : r.kind === 'LEAVE_DAYS' ? 'FAMILY_MARRIAGE' : r.kind === 'RATE' ? 'SEVERANCE_DAYS_PER_YEAR' : 'LEAVE_SENIORITY_DAYS'}
                      value={r.key ?? ''} onChange={(e) => setRule(i, { key: e.target.value.toUpperCase() })} />}
                    {r.kind === 'OVERTIME' && <>
                      <input aria-label={t('From hour')} type="number" min={0} className="narrow-input" value={r.fromHour ?? ''} onChange={(e) => setRule(i, { fromHour: e.target.value as unknown as number })} />
                      <input aria-label={t('To hour')} type="number" min={1} className="narrow-input" placeholder="∞" value={r.toHour ?? ''} onChange={(e) => setRule(i, { toHour: e.target.value as unknown as number })} />
                    </>}
                    {r.kind !== 'NOTE' && <input aria-label={t('Value')} className="narrow-input" value={r.value ?? ''} onChange={(e) => setRule(i, { value: e.target.value })} />}
                    <input aria-label={t('Article')} placeholder="art." className="narrow-input" value={r.article ?? ''} onChange={(e) => setRule(i, { article: e.target.value })} />
                    <Button className="link" onClick={() => set({ rules: editing.p.rules.filter((_, j) => j !== i) })}>{t('Remove')}</Button>
                  </div>
                  <div className="grid2">
                    <input aria-label={t('Wording (English)')} placeholder={t('Wording (English)')} required value={r.labelEn} onChange={(e) => setRule(i, { labelEn: e.target.value })} />
                    <input aria-label={t('Wording (French)')} placeholder={t('Wording (French)')} required value={r.labelFr} onChange={(e) => setRule(i, { labelFr: e.target.value })} />
                  </div>
                </div>
              ))}
              <Button className="secondary" onClick={() => set({ rules: [...editing.p.rules, emptyRule()] })}>{t('Add a rule')}</Button>
              <p className="hint">{t('Settings: LEAVE_MIN_DAYS_MONTH, LEAVE_SENIORITY_DAYS, LEAVE_CIRCUMSTANCE_YEAR_MAX, SENIORITY_BONUS_PERCENT_PER_YEAR, END_OF_CAREER_DAYS_PER_YEAR… Leave days: a leave type code. Overtime: WEEKDAY, REST_DAY or HOLIDAY with hours of the week and a rate (1.3 = +30 %). Rate: a statutory rate code. Notes are shown, not applied.')}</p>
            </fieldset>
            <div className="modal-foot"><Button className="secondary" onClick={() => setEditing(null)}>{t('Cancel')}</Button><Button type="submit">{t('Save')}</Button></div>
          </Form>
        </Modal>
      )}
    </section>
  )
}
