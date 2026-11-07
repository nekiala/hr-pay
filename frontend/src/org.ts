// Organizational structure: types and tree helpers shared by the org pages.

export type OrgLevel = { id: number; code: string; name: string; rank: number; headTitle: string; staff: boolean; active: boolean }
export type OrgHead = {
  id: number; unitId: number; userId: number; userName: string; role: 'HEAD' | 'DEPUTY'; interim: boolean
  title: string | null; validFrom: string; validTo: string | null
}
export type OrgUnit = {
  id: number; code: string; name: string; levelId: number; levelName: string; rank: number; staff: boolean
  parentId: number | null; costCentre: string | null; validFrom: string; validTo: string | null; open: boolean
  head: OrgHead | null; deputy: OrgHead | null; members: number | null; totalMembers: number | null
}
/** Who someone reports to; manual when the manager was set by hand on their record. */
export type OrgBoss = { userId: number; userName: string; manual: boolean }
export type OrgMember = {
  userId: number; name: string; registrationNo: string; unitId: number; unitName: string; since: string
  reportsToId: number | null; reportsToName: string | null; heads: number[]
  siteId: number | null; siteName: string | null
}
export type OrgAssignment = {
  id: number; unitId: number; unitCode: string; unitName: string; validFrom: string; validTo: string | null
  reason: string; note: string | null; createdBy: string | null
}

export const REASON: Record<string, string> = {
  HIRE: 'Hire', TRANSFER: 'Transfer', PROMOTION: 'Promotion', REORGANISATION: 'Reorganisation', CORRECTION: 'Correction',
}

export type TreeNode<T> = { item: T; children: TreeNode<T>[] }

/** Builds the unit tree: top unit(s) first, children by level then name, staff units (Cellules) first under a parent. */
export function unitTree(units: OrgUnit[]): TreeNode<OrgUnit>[] {
  const byParent = new Map<number | null, OrgUnit[]>()
  for (const u of units) {
    const p = u.parentId !== null && units.some((x) => x.id === u.parentId) ? u.parentId : null
    byParent.set(p, [...(byParent.get(p) ?? []), u])
  }
  const sort = (a: OrgUnit, b: OrgUnit) => Number(b.staff) - Number(a.staff) || a.rank - b.rank || a.name.localeCompare(b.name)
  const build = (parent: number | null): TreeNode<OrgUnit>[] =>
    (byParent.get(parent) ?? []).sort(sort).map((u) => ({ item: u, children: build(u.id) }))
  return build(null)
}

/** Units in tree order with their depth, for indented pickers. */
export function flatten(units: OrgUnit[]): { unit: OrgUnit; depth: number }[] {
  const out: { unit: OrgUnit; depth: number }[] = []
  const walk = (nodes: TreeNode<OrgUnit>[], depth: number) => nodes.forEach((n) => { out.push({ unit: n.item, depth }); walk(n.children, depth + 1) })
  walk(unitTree(units), 0)
  return out
}

export function unitPath(units: OrgUnit[], id: number): string {
  const byId = new Map(units.map((u) => [u.id, u]))
  const names: string[] = []
  let u = byId.get(id)
  while (u) {
    names.unshift(u.name)
    u = u.parentId === null ? undefined : byId.get(u.parentId)
  }
  return names.join(' › ')
}

export function headTitle(u: OrgUnit, h: OrgHead, levels: OrgLevel[]): string {
  const base = h.title ?? levels.find((l) => l.id === u.levelId)?.headTitle ?? 'Head'
  const t = h.role === 'DEPUTY' ? `${base} adjoint` : base
  return h.interim ? `${t} a.i.` : t
}
