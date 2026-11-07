import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { api, type Permission, type Role } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'
import { useAsk } from '../components/Dialogs'
import { Button, Form } from '../components/Button'
import { Modal } from '../components/Modal'
import { SignInCard } from '../components/SignInCard'

export default function RolesPage() {
  const ask = useAsk()
  const { can, refresh } = useAuth()
  const editable = can('role.manage')
  const [roles, setRoles] = useState<Role[]>([])
  const [permissions, setPermissions] = useState<Permission[]>([])
  const [selected, setSelected] = useState<number | null>(null)
  const [draft, setDraft] = useState<Set<string>>(new Set())
  const [name, setName] = useState('')
  const [twoStep, setTwoStep] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const [newCode, setNewCode] = useState('')
  const [newName, setNewName] = useState('')
  const [creating, setCreating] = useState(false)

  const load = async (keep?: number) => {
    const list = await api<Role[]>('/api/roles')
    setRoles(list)
    const role = list.find((r) => r.id === keep) ?? list[0]
    if (role) choose(role)
  }

  useEffect(() => {
    api<Permission[]>('/api/permissions').then(setPermissions).catch((e: Error) => setError(e.message))
    load().catch((e: Error) => setError(e.message))
  }, [])

  const choose = (role: Role) => {
    setSelected(role.id)
    setDraft(new Set(role.permissions))
    setName(role.name)
    setTwoStep(role.twoStepRequired)
    setMessage(null)
    setError(null)
  }

  const byCategory = useMemo(() => {
    const groups = new Map<string, Permission[]>()
    for (const p of permissions) groups.set(p.category, [...(groups.get(p.category) ?? []), p])
    return [...groups.entries()]
  }, [permissions])

  const role = roles.find((r) => r.id === selected)

  const toggle = (code: string) => {
    const next = new Set(draft)
    if (next.has(code)) next.delete(code)
    else next.add(code)
    setDraft(next)
  }

  const save = async () => {
    if (!role) return
    setError(null)
    try {
      await api(`/api/roles/${role.id}`, {
        method: 'PUT',
        body: { name, description: role.description, permissions: [...draft], twoStepRequired: twoStep },
      })
      await load(role.id)
      await refresh()
      setMessage('Saved. The change applies to signed-in users on their next action.')
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not save.')
    }
  }

  const remove = () => {
    if (!role) return
    ask({
      title: `Delete the role ${role.name}?`, message: 'People who have it lose its permissions.', confirmLabel: 'Delete role', danger: true,
      onConfirm: async () => { await api(`/api/roles/${role.id}`, { method: 'DELETE' }); await load() },
    })
  }

  const create = async (e: FormEvent) => {
    e.preventDefault()
    setError(null)
    try {
      const created = await api<Role>('/api/roles', {
        method: 'POST',
        body: { code: newCode.toUpperCase(), name: newName, permissions: [] },
      })
      setNewCode('')
      setNewName('')
      setCreating(false)
      await load(created.id)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not create the role.')
    }
  }

  return (
    <section>
      <h2>Roles and permissions</h2>
      {creating && (
        <Modal title="New role" size="sm" onClose={() => setCreating(false)}>
          <Form onSubmit={create}>
            <label>Code<input placeholder="e.g. SITE_MANAGER" value={newCode} onChange={(e) => setNewCode(e.target.value)} required /></label>
            <label>Name<input value={newName} onChange={(e) => setNewName(e.target.value)} required /></label>
            <p className="hint">The role starts with no permissions; tick them after creating it.</p>
            <ErrorText error={error} />
            <div className="modal-foot"><Button className="secondary" onClick={() => setCreating(false)}>Cancel</Button><Button type="submit">Create role</Button></div>
          </Form>
        </Modal>
      )}
      <div className="split">
        <aside className="card">
          {roles.map((r) => (
            <Button key={r.id} className={`list-item ${r.id === selected ? 'active' : ''}`} onClick={() => choose(r)}>
              {r.name}
              <span className="muted small">{r.permissions.length} permissions{r.systemRole ? ' · built-in' : ''}{r.twoStepRequired ? ' · second step' : ''}</span>
            </Button>
          ))}
          {editable && <Button className="secondary" onClick={() => { setError(null); setCreating(true) }}>New role</Button>}
        </aside>
        {role && (
          <div className="card grow">
            <div className="row between">
              {editable ? <input className="title-input" value={name} onChange={(e) => setName(e.target.value)} /> : <h3>{role.name}</h3>}
              <code>{role.code}</code>
            </div>
            {role.description && <p className="muted">{role.description}</p>}
            <label className="check">
              <input type="checkbox" checked={twoStep} disabled={!editable} onChange={(e) => setTwoStep(e.target.checked)} />
              <span><strong>Second step required at sign-in</strong>
                <span className="hint"> Members must use an authenticator app or an emailed code after their password. Recommended for
                  anyone who sees pay or personal data.</span></span>
            </label>
            {byCategory.map(([category, perms]) => (
              <fieldset key={category}>
                <legend>{category}</legend>
                {perms.map((p) => (
                  <label key={p.code} className="check">
                    <input type="checkbox" checked={draft.has(p.code)} disabled={!editable} onChange={() => toggle(p.code)} />
                    <span>{p.description} <code className="small">{p.code}</code></span>
                  </label>
                ))}
              </fieldset>
            ))}
            <ErrorText error={error} />
            {message && <p className="success">{message}</p>}
            {editable && (
              <div className="row">
                <Button onClick={() => save()}>Save role</Button>
                {!role.systemRole && <Button className="danger" onClick={() => remove()}>Delete role</Button>}
              </div>
            )}
          </div>
        )}
      </div>
      <SignInCard />
    </section>
  )
}
