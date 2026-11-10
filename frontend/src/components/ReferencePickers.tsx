import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api, countries, PLACE_KINDS, type ActivityBranch, type City, type Country, type Institution, type Place, type PlaceKind } from '../api'
import { t } from '../i18n'

/** The countries, for a select. */
export function useCountries() {
  const [list, setList] = useState<Country[]>([])
  const [withPlaces, setWithPlaces] = useState<string[]>([])
  const [canShare, setCanShare] = useState(false)
  useEffect(() => {
    countries().then((c) => { setList(c.countries); setWithPlaces(c.withPlaces); setCanShare(c.canShare) }).catch(() => undefined)
  }, [])
  return { countries: list, withPlaces, canShare }
}

/** A country, or a nationality (the same list, shown by its nationality). */
export function CountrySelect({ value, onChange, nationality, required, placeholder }: {
  value: string | null | undefined; onChange: (code: string | null) => void; nationality?: boolean; required?: boolean; placeholder?: string
}) {
  const { countries: list } = useCountries()
  // The two Congos first: most people are from one of them.
  const first = list.filter((c) => c.code === 'CD' || c.code === 'CG')
  const rest = list.filter((c) => c.code !== 'CD' && c.code !== 'CG')
  const label = (c: Country) => (nationality ? `${c.nationality}` : c.name)
  const sorted = nationality ? [...rest].sort((a, b) => a.nationality.localeCompare(b.nationality)) : rest
  return (
    <select value={value ?? ''} required={required} onChange={(e) => onChange(e.target.value || null)}>
      <option value="">{placeholder ?? t('Choose…')}</option>
      {first.map((c) => <option key={c.code} value={c.code}>{label(c)}</option>)}
      {first.length > 0 && <option disabled>──────────</option>}
      {sorted.map((c) => <option key={c.code} value={c.code}>{label(c)}</option>)}
    </select>
  )
}

const LEVEL_LABEL = (kinds: Set<PlaceKind>, depth: number) => {
  if (kinds.has('PROVINCE')) return t('Province')
  if (kinds.has('DEPARTMENT')) return t('Department')
  if (kinds.has('ARRONDISSEMENT')) return t('Arrondissement')
  if (kinds.has('COMMUNE') || kinds.has('SECTOR') || kinds.has('CHIEFDOM')) return t('Commune, sector or chiefdom')
  if (kinds.has('CITY') || kinds.has('TERRITORY') || kinds.has('DISTRICT')) return t('Town or territory')
  return depth === 0 ? t('Province') : t('Place')
}

/** The kinds of place, grouped in a select: towns apart from territories (Beni the town, Beni the territory). */
function PlaceOptions({ places }: { places: Place[] }) {
  const kinds = [...new Set(places.map((p) => p.kind))]
  if (kinds.length <= 1) return <>{places.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}</>
  return (
    <>
      {kinds.map((k) => (
        <optgroup key={k} label={PLACE_KINDS[k]}>
          {places.filter((p) => p.kind === k).map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
        </optgroup>
      ))}
    </>
  )
}

/**
 * A place picked level by level: province, then town or territory, then commune. The value is the lowest place picked;
 * a level can be left empty when the person does not know it.
 */
export function PlacePicker({ country, path, onChange, disabled }: {
  country: string; path: Place[]; onChange: (place: Place | null, path: Place[]) => void; disabled?: boolean
}) {
  // levels[i]: the places to choose from at depth i; chosen[i]: the one picked.
  const [levels, setLevels] = useState<Place[][]>([])
  const [chosen, setChosen] = useState<Place[]>([])

  const load = (parent: number | null) => api<Place[]>(`/api/reference/places?country=${country}${parent ? `&parent=${parent}` : ''}`)

  useEffect(() => {
    let live = true
    const start = path.filter((p) => p.countryCode === country)
    Promise.all([load(null), ...start.filter((p) => p.children > 0).map((p) => load(p.id))])
      .then((lists) => { if (live) { setLevels(lists.filter((l) => l.length > 0)); setChosen(start) } })
      .catch(() => undefined)
    return () => { live = false }
  }, [country, path.map((p) => p.id).join('/')]) // eslint-disable-line react-hooks/exhaustive-deps

  const pick = async (depth: number, id: string) => {
    const place = levels[depth]?.find((p) => p.id === Number(id)) ?? null
    const next = [...chosen.slice(0, depth), ...(place ? [place] : [])]
    setChosen(next)
    const below = place && place.children > 0 ? await load(place.id) : []
    setLevels([...levels.slice(0, depth + 1), ...(below.length > 0 ? [below] : [])])
    onChange(next.length > 0 ? next[next.length - 1] : null, next)
  }

  if (levels.length === 0) return null
  return (
    <div className="grid3">
      {levels.map((options, depth) => (
        <label key={depth}>{LEVEL_LABEL(new Set(options.map((o) => o.kind)), depth)}
          <select value={chosen[depth]?.id ?? ''} disabled={disabled} onChange={(e) => pick(depth, e.target.value)}>
            <option value="">{depth === 0 ? t('Choose…') : t('Not given')}</option>
            <PlaceOptions places={options} />
          </select>
        </label>
      ))}
    </div>
  )
}

/** A bank or a mobile money operator from the lists of a country. */
export function InstitutionSelect({ country, kind, value, onChange, required }: {
  country: string; kind: 'BANK' | 'MOBILE_MONEY'; value: number | null | undefined; onChange: (i: Institution | null) => void; required?: boolean
}) {
  const [list, setList] = useState<Institution[]>([])
  useEffect(() => {
    api<Institution[]>(`/api/reference/institutions?country=${country}&kind=${kind}`).then(setList).catch(() => undefined)
  }, [country, kind])
  return (
    <select value={value ?? ''} required={required} onChange={(e) => onChange(list.find((i) => i.id === Number(e.target.value)) ?? null)}>
      <option value="">{t('Choose…')}</option>
      {list.map((i) => <option key={i.id} value={i.id}>{i.name}{i.shortName && i.shortName !== i.name ? ` (${i.shortName})` : ''}</option>)}
    </select>
  )
}

/**
 * A town picked country first, then town: only the towns of the chosen country are offered, grouped by province or
 * department, so a town of the wrong country cannot be picked.
 */
export function CityPicker({ country, cityId, onChange, cityLabel, emptyLabel, required }: {
  country: string | null | undefined; cityId: number | null | undefined
  onChange: (country: string | null, city: City | null) => void
  cityLabel?: string; emptyLabel?: string; required?: boolean
}) {
  const [cities, setCities] = useState<City[] | null>(null)
  useEffect(() => {
    if (!country) { setCities([]); return }
    let live = true
    setCities(null)
    api<City[]>(`/api/reference/cities?country=${country}`).then((c) => { if (live) setCities(c) }).catch(() => { if (live) setCities([]) })
    return () => { live = false }
  }, [country])
  const regions = [...new Set((cities ?? []).map((c) => c.region ?? ''))].sort((a, b) => a.localeCompare(b))
  return (
    <>
      <label>{t('Country')}<CountrySelect value={country} required={required} onChange={(c) => onChange(c, null)} /></label>
      <label>{cityLabel ?? t('Town')}
        <select value={cityId ?? ''} required={required} disabled={!country || cities === null}
          onChange={(e) => onChange(country ?? null, cities?.find((c) => c.id === Number(e.target.value)) ?? null)}>
          <option value="">{emptyLabel ?? t('Choose…')}</option>
          {regions.map((r) => (
            <optgroup key={r} label={r || t('Other')}>
              {(cities ?? []).filter((c) => (c.region ?? '') === r).map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
            </optgroup>
          ))}
        </select>
        {country && cities?.length === 0 && (
          <span className="hint">{t('No town of this country is in the lists yet.')} <Link to="/reference-lists">{t('Add it on Reference lists')}</Link></span>
        )}
      </label>
    </>
  )
}

/** HR Pay's branches of activity: each section, then its divisions. */
export function useBranches(all = false) {
  const [list, setList] = useState<ActivityBranch[]>([])
  const [loaded, setLoaded] = useState(false)
  const reload = () => api<ActivityBranch[]>(`/api/reference/branches${all ? '?all=true' : ''}`)
    .then((b) => { setList(b); setLoaded(true) }).catch(() => setLoaded(true))
  useEffect(() => { reload() }, [all]) // eslint-disable-line react-hooks/exhaustive-deps
  return { branches: list, loaded, reload }
}

/** A branch of activity picked from HR Pay's list: a whole section, or one of its divisions. */
export function BranchSelect({ value, onChange, required }: {
  value: number | null | undefined; onChange: (b: ActivityBranch | null) => void; required?: boolean
}) {
  const { branches } = useBranches()
  const sections = branches.filter((b) => b.parentId === null)
  return (
    <select value={value ?? ''} required={required} onChange={(e) => onChange(branches.find((b) => b.id === Number(e.target.value)) ?? null)}>
      <option value="">{t('Choose…')}</option>
      {sections.map((s) => (
        <optgroup key={s.id} label={`${s.code}. ${s.name}`}>
          <option value={s.id}>{s.name} ({t('whole section')})</option>
          {branches.filter((b) => b.parentId === s.id).map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}
        </optgroup>
      ))}
    </select>
  )
}

/** A value typed before the lists, shown until one is picked. */
export function OldValue({ value }: { value: string | null | undefined }) {
  if (!value) return null
  return <span className="hint warn-text">{t('Typed before the lists: « {a} ». Pick it from the list.', { a: value })}</span>
}
