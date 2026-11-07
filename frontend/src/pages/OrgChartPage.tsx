import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'
import { headTitle, unitTree, type OrgLevel, type OrgMember, type OrgUnit, type TreeNode } from '../org'
import { Button } from '../components/Button'

/** The org chart: units with their heads (or "Vacant"), and for HR a people view built from who reports to whom. */
export default function OrgChartPage() {
  const { can } = useAuth()
  const people = can('user.read')
  const [units, setUnits] = useState<OrgUnit[]>([])
  const [levels, setLevels] = useState<OrgLevel[]>([])
  const [members, setMembers] = useState<OrgMember[]>([])
  const [view, setView] = useState<'units' | 'people'>('units')
  const [collapsed, setCollapsed] = useState<Set<number>>(new Set())
  const [root, setRoot] = useState<string>('')
  const [site, setSite] = useState<string>('')
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    api<OrgUnit[]>('/api/org/units').then(setUnits).catch((e: Error) => setError(e.message))
    api<OrgLevel[]>('/api/org/levels').then(setLevels).catch(() => undefined)
    if (people) api<OrgMember[]>('/api/org/people').then(setMembers).catch(() => undefined)
  }, [people])

  const sites = useMemo(() => {
    const m = new Map<number, string>()
    members.forEach((x) => { if (x.siteId !== null) m.set(x.siteId, x.siteName ?? `#${x.siteId}`) })
    return [...m.entries()].sort((a, b) => a[1].localeCompare(b[1]))
  }, [members])
  const atSite = useMemo(() => (site ? members.filter((m) => String(m.siteId) === site) : members), [members, site])

  // With a site chosen, each unit counts only that site's people, and units with nobody there are left out.
  const shown = useMemo(() => {
    if (!site) return units
    const here = new Map<number, number>()
    atSite.forEach((m) => here.set(m.unitId, (here.get(m.unitId) ?? 0) + 1))
    const total = new Map<number, number>()
    const byParent = new Map<number | null, OrgUnit[]>()
    units.forEach((u) => byParent.set(u.parentId, [...(byParent.get(u.parentId) ?? []), u]))
    const count = (u: OrgUnit): number => {
      const t = (here.get(u.id) ?? 0) + (byParent.get(u.id) ?? []).reduce((s, c) => s + count(c), 0)
      total.set(u.id, t)
      return t
    }
    (byParent.get(null) ?? []).forEach(count)
    return units.filter((u) => (total.get(u.id) ?? 0) > 0)
      .map((u) => ({ ...u, members: here.get(u.id) ?? 0, totalMembers: total.get(u.id) ?? 0 }))
  }, [units, atSite, site])

  const tree = useMemo(() => {
    const all = unitTree(shown)
    if (!root) return all
    const find = (nodes: TreeNode<OrgUnit>[]): TreeNode<OrgUnit> | undefined => {
      for (const n of nodes) {
        if (String(n.item.id) === root) return n
        const f = find(n.children)
        if (f) return f
      }
      return undefined
    }
    const n = find(all)
    return n ? [n] : all
  }, [shown, root])

  const toggle = (id: number) => {
    const next = new Set(collapsed)
    if (next.has(id)) next.delete(id)
    else next.add(id)
    setCollapsed(next)
  }

  const unitBox = (u: OrgUnit) => (
    <div className={`org-box${u.staff ? ' staff' : ''}${u.head ? '' : ' vacant'}`}>
      <small>{u.levelName}</small>
      <Link to={`/org/units/${u.id}`}><strong>{u.name}</strong></Link>
      {u.head ? <span>{u.head.userName}<br /><small>{headTitle(u, u.head, levels)}</small></span> : <span className="muted">Vacant</span>}
      {u.deputy && <small>{headTitle(u, u.deputy, levels)}: {u.deputy.userName}</small>}
      {u.totalMembers !== null && <small>{u.members} here · {u.totalMembers} in total</small>}
    </div>
  )

  const renderUnits = (nodes: TreeNode<OrgUnit>[]) => (
    <ul>{nodes.map((n) => (
      <li key={n.item.id}>
        {unitBox(n.item)}
        {n.children.length > 0 && (
          <Button className="secondary org-toggle no-print" onClick={() => toggle(n.item.id)}>
            {collapsed.has(n.item.id) ? `+ ${n.children.length}` : '−'}
          </Button>
        )}
        {n.children.length > 0 && !collapsed.has(n.item.id) && renderUnits(n.children)}
      </li>
    ))}</ul>
  )

  // People: everyone under the person they report to; the people at the top have nobody above them.
  const peopleTree = useMemo(() => {
    const ids = new Set(atSite.map((m) => m.userId))
    const byBoss = new Map<number | null, OrgMember[]>()
    for (const m of atSite) {
      const boss = m.reportsToId !== null && ids.has(m.reportsToId) ? m.reportsToId : null
      byBoss.set(boss, [...(byBoss.get(boss) ?? []), m])
    }
    const build = (boss: number | null, seen: Set<number>): TreeNode<OrgMember>[] =>
      (byBoss.get(boss) ?? []).filter((m) => !seen.has(m.userId)).sort((a, b) => b.heads.length - a.heads.length || a.name.localeCompare(b.name))
        .map((m) => ({ item: m, children: build(m.userId, new Set([...seen, m.userId])) }))
    return build(null, new Set())
  }, [atSite])

  const renderPeople = (nodes: TreeNode<OrgMember>[]) => (
    <ul>{nodes.map((n) => (
      <li key={n.item.userId}>
        <div className={`org-box${n.item.heads.length ? '' : ' person'}`}>
          <Link to={`/users/${n.item.userId}`}><strong>{n.item.name}</strong></Link>
          <small>{n.item.unitName}</small>
          {n.item.heads.length > 0 && <small>Heads {n.item.heads.map((h) => units.find((u) => u.id === h)?.name ?? `#${h}`).join(', ')}</small>}
        </div>
        {n.children.length > 0 && (
          <Button className="secondary org-toggle no-print" onClick={() => toggle(-n.item.userId)}>
            {collapsed.has(-n.item.userId) ? `+ ${n.children.length}` : '−'}
          </Button>
        )}
        {n.children.length > 0 && !collapsed.has(-n.item.userId) && renderPeople(n.children)}
      </li>
    ))}</ul>
  )

  const vacant = units.filter((u) => !u.head).length
  return (
    <section>
      <div className="row no-print org-tools">
        <h2 style={{ marginRight: 'auto' }}>Org chart</h2>
        {people && (
          <select value={view} onChange={(e) => setView(e.target.value as 'units' | 'people')}>
            <option value="units">Units</option>
            <option value="people">People</option>
          </select>
        )}
        {view === 'units' && (
          <select value={root} onChange={(e) => setRoot(e.target.value)}>
            <option value="">Whole company</option>
            {units.filter((u) => units.some((c) => c.parentId === u.id)).map((u) => <option key={u.id} value={u.id}>{u.name}</option>)}
          </select>
        )}
        {sites.length > 1 && (
          <select value={site} onChange={(e) => setSite(e.target.value)}>
            <option value="">All sites</option>
            {sites.map(([id, name]) => <option key={id} value={id}>{name}</option>)}
          </select>
        )}
        <Button className="secondary" onClick={() => setCollapsed(new Set())}>Expand all</Button>
        <Button className="secondary" onClick={() => window.print()}>Print or save as PDF</Button>
        {can('org.manage') && <Link className="button secondary" to="/org/manage">Levels, import and moves</Link>}
      </div>
      <h2 className="print-only">Org chart{site && ` · ${sites.find(([id]) => String(id) === site)?.[1]}`} · {new Date().toISOString().slice(0, 10)}</h2>
      <p className="muted no-print">
        {site && `${atSite.length} people at this site, in ${shown.length} units. `}
        {units.length} units{vacant > 0 && `, ${vacant} without a head`}. A vacant unit's people report to the nearest head above it.
        Cellules (staff units) are shown with a dashed border.
      </p>
      <ErrorText error={error} />
      <div className="org-scroll">
        <div className="org-tree">
          {view === 'units' ? renderUnits(tree) : renderPeople(peopleTree)}
        </div>
      </div>
    </section>
  )
}
