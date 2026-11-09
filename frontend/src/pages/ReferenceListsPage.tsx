import { useEffect, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { ApiError, PLACE_KINDS, api, type Institution, type Place, type PlaceKind, type ToPick } from '../api'
import { useAuth } from '../auth'
import { Button, Form } from '../components/Button'
import { useAsk } from '../components/Dialogs'
import { ErrorText } from '../components/ErrorText'
import { useCountries } from '../components/ReferencePickers'
import { t } from '../i18n'

type Tab = 'places' | 'BANK' | 'MOBILE_MONEY' | 'pick'

const FIELDS: Record<ToPick['field'], string> = {
  NATIONALITY: t('Nationality'), COMMUNE: t('Commune or territory'), BANK: t('Bank'), MOBILE_MONEY: t('Mobile money operator'),
}

/** The kinds that can sit under a place of a kind (or at the top of a country). */
function kindsUnder(country: string, parent: Place | null): PlaceKind[] {
  if (!parent) return country === 'CG' ? ['DEPARTMENT'] : ['PROVINCE']
  switch (parent.kind) {
    case 'PROVINCE': return ['CITY', 'TERRITORY']
    case 'DEPARTMENT': return ['CITY', 'DISTRICT']
    case 'CITY': return country === 'CG' ? ['ARRONDISSEMENT'] : ['COMMUNE']
    case 'TERRITORY': return ['SECTOR', 'CHIEFDOM', 'CITY']
    case 'DISTRICT': return ['CITY', 'COMMUNE']
    default: return ['COMMUNE']
  }
}

function Status({ shared, verified, active }: { shared: boolean; verified: boolean; active: boolean }) {
  return (
    <>
      <span className={`badge ${shared ? 'info' : ''}`}>{shared ? t('HR Pay list') : t('Added by the company')}</span>
      {shared && !verified && <>{' '}<span className="badge warn">{t('To verify')}</span></>}
      {!active && <>{' '}<span className="badge off">{t('Not in use')}</span></>}
    </>
  )
}

/** The lists HR picks from: places, banks, mobile money operators; and the employee files still to be picked. */
export default function ReferenceListsPage() {
  const { me, can } = useAuth()
  const { countries, withPlaces, canShare } = useCountries()
  const [country, setCountry] = useState(me?.company?.country ?? 'CD')
  const [tab, setTab] = useState<Tab>('places')
  const tabs: Record<Tab, string> = { places: t('Places'), BANK: t('Banks'), MOBILE_MONEY: t('Mobile money'), pick: t('To pick in employee files') }
  const shown = [...new Set([...(me?.company?.country ? [me.company.country] : []), ...withPlaces, 'CD', 'CG'])]

  return (
    <section>
      <h2>{t('Reference lists')}</h2>
      <p className="hint">{t('Banks, operators and administrative places are picked from these lists in the employee file instead of typed. HR Pay keeps the shared lists; add what is missing for your company.')}</p>
      <div className="row space">
        <div className="segmented" role="tablist">
          {(Object.keys(tabs) as Tab[]).filter((k) => k !== 'pick' || can('salary.read')).map((k) => (
            <button key={k} type="button" role="tab" aria-selected={tab === k} className={tab === k ? 'on' : ''} onClick={() => setTab(k)}>{tabs[k]}</button>
          ))}
        </div>
        {tab !== 'pick' && (
          <label className="inline-filter" style={{ maxWidth: 320 }}>{t('Country')}
            <select value={country} onChange={(e) => setCountry(e.target.value)}>
              {shown.map((c) => <option key={c} value={c}>{countries.find((x) => x.code === c)?.name ?? c}</option>)}
            </select>
          </label>
        )}
      </div>
      {tab === 'places' && <Places key={country} country={country} canShare={canShare} />}
      {(tab === 'BANK' || tab === 'MOBILE_MONEY') && <Institutions key={country + tab} country={country} kind={tab} canShare={canShare} />}
      {tab === 'pick' && <ToPickList />}
    </section>
  )
}

function useRun() {
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const run = async (action: () => Promise<unknown>, done: string) => {
    setError(null)
    setMessage(null)
    try {
      await action()
      setMessage(done)
      return true
    } catch (e) {
      setError(e instanceof ApiError ? [e.message, ...Object.values(e.fieldErrors)].join(' ') : t('Something went wrong.'))
      return false
    }
  }
  return { error, message, run }
}

function Places({ country, canShare }: { country: string; canShare: boolean }) {
  const { can } = useAuth()
  const ask = useAsk()
  const { error, message, run } = useRun()
  const [trail, setTrail] = useState<Place[]>([])
  const [places, setPlaces] = useState<Place[]>([])
  const parent = trail.length > 0 ? trail[trail.length - 1] : null
  const kinds = kindsUnder(country, parent)
  const [form, setForm] = useState({ kind: kinds[0] as string, name: '', shared: false })
  const mayAdd = can('masterdata.manage') || canShare

  const load = () => api<Place[]>(`/api/reference/places?country=${country}&all=true${parent ? `&parent=${parent.id}` : ''}`)
    .then(setPlaces).catch(() => setPlaces([]))
  useEffect(() => { load(); setForm((f) => ({ ...f, kind: kinds[0], name: '' })) }, [parent?.id]) // eslint-disable-line react-hooks/exhaustive-deps

  const mayChange = (p: Place) => (p.shared ? canShare : can('masterdata.manage'))
  const add = (e: FormEvent) => {
    e.preventDefault()
    return run(async () => {
      await api('/api/reference/places', { method: 'POST', body: { countryCode: country, parentId: parent?.id ?? null, kind: form.kind, name: form.name, shared: form.shared, verified: form.shared } })
      setForm({ ...form, name: '' })
      await load()
    }, t('Added.'))
  }
  const change = (p: Place, body: Record<string, unknown>) => run(async () => {
    await api(`/api/reference/places/${p.id}`, { method: 'PUT', body })
    await load()
  }, t('Saved.'))

  return (
    <div className="card">
      <p className="small">
        <button type="button" className="link" onClick={() => setTrail([])}>{t('All')}</button>
        {trail.map((p, i) => <span key={p.id}>{' › '}<button type="button" className="link" onClick={() => setTrail(trail.slice(0, i + 1))}>{p.name}</button></span>)}
      </p>
      <ErrorText error={error} />
      {message && <p className="success">{message}</p>}
      {places.length === 0 ? <p className="muted">{t('Nothing here yet.')}</p> : (
        <table>
          <thead><tr><th>{t('Name')}</th><th>{t('Kind')}</th><th>{t('Below')}</th><th>{t('List')}</th><th /></tr></thead>
          <tbody>
            {places.map((p) => (
              <tr key={p.id}>
                <td><button type="button" className="link" onClick={() => setTrail([...trail, p])}>{p.name}</button></td>
                <td>{PLACE_KINDS[p.kind]}</td>
                <td>{p.children > 0 ? p.children : ''}</td>
                <td><Status shared={p.shared} verified={p.verified} active={p.active} /></td>
                <td className="row">
                  {mayChange(p) && (
                    <>
                      <Button className="secondary" onClick={() => ask({
                        title: t('Rename {a}', { a: p.name }), confirmLabel: t('Save'),
                        fields: [{ label: t('Name'), defaultValue: p.name, required: true }],
                        onConfirm: ([name]) => change(p, { name }),
                      })}>{t('Rename')}</Button>
                      {p.shared && !p.verified && <Button className="secondary" onClick={() => change(p, { verified: true })}>{t('Mark verified')}</Button>}
                      <Button className="secondary" onClick={() => change(p, { active: !p.active })}>{p.active ? t('Stop using') : t('Use again')}</Button>
                    </>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {mayAdd && (
        <Form onSubmit={add}>
          <h4>{parent ? t('Add a place in {a}', { a: parent.name }) : t('Add a province or department')}</h4>
          <div className="grid3">
            <label>{t('Kind')}<select value={form.kind} onChange={(e) => setForm({ ...form, kind: e.target.value })}>
                {kinds.map((k) => <option key={k} value={k}>{PLACE_KINDS[k]}</option>)}
              </select>
            </label>
            <label>{t('Name')}<input value={form.name} maxLength={100} required onChange={(e) => setForm({ ...form, name: e.target.value })} /></label>
            {canShare && (!parent || parent.shared) && (
              <label className="check"><input type="checkbox" checked={form.shared} onChange={(e) => setForm({ ...form, shared: e.target.checked })} />{t('In HR Pay\'s shared list (every company)')}</label>
            )}
          </div>
          <Button type="submit">{t('Add')}</Button>
        </Form>
      )}
    </div>
  )
}

function Institutions({ country, kind, canShare }: { country: string; kind: 'BANK' | 'MOBILE_MONEY'; canShare: boolean }) {
  const { can } = useAuth()
  const ask = useAsk()
  const { error, message, run } = useRun()
  const [list, setList] = useState<Institution[]>([])
  const empty = { name: '', shortName: '', swiftCode: '', shared: false }
  const [form, setForm] = useState(empty)
  const load = () => api<Institution[]>(`/api/reference/institutions?country=${country}&kind=${kind}&all=true`).then(setList).catch(() => setList([]))
  useEffect(() => { load() }, []) // eslint-disable-line react-hooks/exhaustive-deps
  const mayChange = (i: Institution) => (i.shared ? canShare : can('masterdata.manage'))
  const body = (i: Institution) => ({ name: i.name, shortName: i.shortName, swiftCode: i.swiftCode, aliases: i.aliases, active: i.active, verified: i.verified })
  const change = (i: Institution, patch: Record<string, unknown>) => run(async () => {
    await api(`/api/reference/institutions/${i.id}`, { method: 'PUT', body: { ...body(i), ...patch } })
    await load()
  }, t('Saved.'))
  const add = (e: FormEvent) => {
    e.preventDefault()
    return run(async () => {
      await api('/api/reference/institutions', { method: 'POST', body: { ...form, countryCode: country, kind, verified: form.shared } })
      setForm(empty)
      await load()
    }, t('Added.'))
  }

  return (
    <div className="card">
      <ErrorText error={error} />
      {message && <p className="success">{message}</p>}
      <table>
        <thead><tr><th>{t('Name')}</th><th>{t('Short name')}</th>{kind === 'BANK' && <th>{t('SWIFT (BIC)')}</th>}<th>{t('List')}</th><th /></tr></thead>
        <tbody>
          {list.map((i) => (
            <tr key={i.id}>
              <td>{i.name}{i.aliases && <span className="hint">{t('Also written: {a}', { a: i.aliases.split('|').join(', ') })}</span>}</td>
              <td>{i.shortName}</td>
              {kind === 'BANK' && <td>{i.swiftCode}</td>}
              <td><Status shared={i.shared} verified={i.verified} active={i.active} /></td>
              <td className="row">
                {mayChange(i) && (
                  <>
                    <Button className="secondary" onClick={() => ask({
                      title: t('Change {a}', { a: i.name }), confirmLabel: t('Save'),
                      fields: [
                        { label: t('Name'), defaultValue: i.name, required: true },
                        { label: t('Short name'), defaultValue: i.shortName ?? '' },
                        ...(kind === 'BANK' ? [{ label: t('SWIFT (BIC)'), defaultValue: i.swiftCode ?? '', hint: t('8 or 11 letters and digits, for payment files.') }] : []),
                        { label: t('Other spellings'), defaultValue: i.aliases ?? '', hint: t('Separated by |, to recognise names typed in imports.') },
                      ],
                      onConfirm: (v) => change(i, kind === 'BANK'
                        ? { name: v[0], shortName: v[1], swiftCode: v[2], aliases: v[3] }
                        : { name: v[0], shortName: v[1], aliases: v[2] }),
                    })}>{t('Change')}</Button>
                    {i.shared && !i.verified && <Button className="secondary" onClick={() => change(i, { verified: true })}>{t('Mark verified')}</Button>}
                    <Button className="secondary" onClick={() => change(i, { active: !i.active })}>{i.active ? t('Stop using') : t('Use again')}</Button>
                  </>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {(can('masterdata.manage') || canShare) && (
        <Form onSubmit={add}>
          <h4>{kind === 'BANK' ? t('Add a bank') : t('Add a mobile money operator')}</h4>
          <div className="grid3">
            <label>{t('Name')}<input value={form.name} maxLength={100} required onChange={(e) => setForm({ ...form, name: e.target.value })} /></label>
            <label>{t('Short name')}<input value={form.shortName} maxLength={30} onChange={(e) => setForm({ ...form, shortName: e.target.value })} /></label>
            {kind === 'BANK' && <label>{t('SWIFT (BIC)')}<input value={form.swiftCode} maxLength={11} onChange={(e) => setForm({ ...form, swiftCode: e.target.value })} /></label>}
          </div>
          {canShare && <label className="check"><input type="checkbox" checked={form.shared} onChange={(e) => setForm({ ...form, shared: e.target.checked })} />{t('In HR Pay\'s shared list (every company)')}</label>}
          <Button type="submit">{t('Add')}</Button>
        </Form>
      )}
    </div>
  )
}

function ToPickList() {
  const [rows, setRows] = useState<ToPick[] | null>(null)
  useEffect(() => { api<ToPick[]>('/api/reference/to-pick').then(setRows).catch(() => setRows([])) }, [])
  if (!rows) return null
  return (
    <div className="card">
      <p className="hint">{t('Values typed before the lists existed. Open the employee file and pick each one from the list; payroll checks list them until then.')}</p>
      {rows.length === 0 ? <p className="success">{t('Every employee file uses the lists.')}</p> : (
        <table>
          <thead><tr><th>{t('Employee')}</th><th>{t('Field')}</th><th>{t('Typed value')}</th></tr></thead>
          <tbody>
            {rows.map((r) => (
              <tr key={`${r.userId}-${r.field}`}>
                <td><Link to={`/users/${r.userId}/file`}>{r.name}</Link> <span className="muted small">{r.registrationNo}</span></td>
                <td>{FIELDS[r.field]}</td>
                <td>{r.value}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  )
}
