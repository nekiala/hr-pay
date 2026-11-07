import { useEffect, useMemo, useState } from 'react'
import { api } from '../api'
import type { OrgUnit } from '../org'
import { UnitSelect } from './OrgCard'

export type MyPerson = { userId: number; name: string; registrationNo: string; unitId: number }

/**
 * A unit filter for lists of people: choosing a unit keeps the people in it and in every unit below it.
 * Only the units of the people the current user can act on are offered.
 */
export function useUnitFilter() {
  const [units, setUnits] = useState<OrgUnit[]>([])
  const [people, setPeople] = useState<MyPerson[]>([])
  const [unit, setUnit] = useState('')

  useEffect(() => {
    api<OrgUnit[]>('/api/org/units').then(setUnits).catch(() => undefined)
    api<MyPerson[]>('/api/org/my-people').then(setPeople).catch(() => undefined)
  }, [])
  const unitOf = useMemo(() => Object.fromEntries(people.map((p) => [String(p.userId), p.unitId])) as Record<string, number>, [people])

  // Units that hold someone, and the units above them, so the picker keeps its tree shape.
  const offered = useMemo(() => {
    const byId = new Map(units.map((u) => [u.id, u]))
    const keep = new Set<number>()
    Object.values(unitOf).forEach((id) => {
      let u = byId.get(id)
      while (u && !keep.has(u.id)) {
        keep.add(u.id)
        u = u.parentId === null ? undefined : byId.get(u.parentId)
      }
    })
    return units.filter((u) => keep.has(u.id))
  }, [units, unitOf])

  const branch = useMemo(() => {
    if (!unit) return null
    const out = new Set<number>([Number(unit)])
    let grew = true
    while (grew) {
      grew = false
      units.forEach((u) => {
        if (u.parentId !== null && out.has(u.parentId) && !out.has(u.id)) {
          out.add(u.id)
          grew = true
        }
      })
    }
    return out
  }, [units, unit])

  const keep = (userId: number) => branch === null || branch.has(unitOf[String(userId)])
  const unitName = (userId: number) => units.find((u) => u.id === unitOf[String(userId)])?.name ?? ''

  const select = offered.length > 1
    ? <UnitSelect units={offered} value={unit} onChange={setUnit} placeholder="All units" />
    : null
  return { select, keep, unitName, unit, people }
}
