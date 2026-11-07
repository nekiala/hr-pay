import { useEffect, useState, type FormEvent } from 'react'
import { Link, useParams } from 'react-router-dom'
import { ApiError, CONTRACT_TYPES, api, money, type EmployeeFileView, type Grade, type Occupation, type PayAssignment, type PayElement, type Profile, type User } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'
import { useAsk } from '../components/Dialogs'
import { Button, Form } from '../components/Button'
import { Modal } from '../components/Modal'
import { t } from '../i18n'

const age = (birth: string | null) => {
  if (!birth) return ''
  const b = new Date(birth)
  const n = new Date()
  return String(n.getFullYear() - b.getFullYear() - (n < new Date(n.getFullYear(), b.getMonth(), b.getDate()) ? 1 : 0))
}

export default function EmployeeFilePage() {
  const [addingDependant, setAddingDependant] = useState(false)
  const [addingItem, setAddingItem] = useState(false)
  const [contracting, setContracting] = useState(false)
  const ask = useAsk()
  const { id } = useParams()
  const { can } = useAuth()
  const editable = can('salary.write')
  const [user, setUser] = useState<User | null>(null)
  const [file, setFile] = useState<EmployeeFileView | null>(null)
  const [grades, setGrades] = useState<Grade[]>([])
  const [occupations, setOccupations] = useState<Occupation[]>([])
  const [profile, setProfile] = useState<Profile | null>(null)
  const [contract, setContract] = useState({ contractType: 'PERMANENT', startDate: '', endDate: '', gradeId: '', occupationId: '', expatriate: false, agreedNet: '', note: '' })
  const [dependant, setDependant] = useState({ fullName: '', relationship: 'CHILD', birthDate: '', inCharge: true, note: '' })
  const [elements, setElements] = useState<PayElement[]>([])
  const [items, setItems] = useState<PayAssignment[]>([])
  const [item, setItem] = useState({ elementId: '', mode: 'amount', amount: '', currency: 'USD', percent: '', startMonth: new Date().toISOString().slice(0, 7), endMonth: '', note: '' })
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)

  const load = () => Promise.all([
    api<EmployeeFileView>(`/api/users/${id}/file`).then((f) => { setFile(f); setProfile(f.profile) }),
    api<PayAssignment[]>(`/api/users/${id}/pay-elements`).then(setItems),
  ]).catch((e: Error) => setError(e.message))
  useEffect(() => {
    load()
    api<User>(`/api/users/${id}`).then(setUser).catch(() => undefined)
    api<Grade[]>('/api/grades').then(setGrades).catch(() => undefined)
    api<Occupation[]>('/api/occupations').then(setOccupations).catch(() => undefined)
    api<PayElement[]>('/api/pay-elements').then(setElements).catch(() => undefined)
  }, [id]) // eslint-disable-line react-hooks/exhaustive-deps

  const run = async (action: () => Promise<unknown>, done: string) => {
    setError(null)
    setMessage(null)
    try {
      await action()
      setMessage(done)
      await load()
      return true
    } catch (e) {
      setError(e instanceof ApiError ? [e.message, ...Object.values(e.fieldErrors)].join(' ') : t('Something went wrong.'))
      return false
    }
  }

  const grade = grades.find((g) => g.id === Number(contract.gradeId))

  const startContract = (e: FormEvent) => {
    e.preventDefault()
    return run(() => api(`/api/users/${id}/contracts`, {
      method: 'POST',
      body: {
        ...contract, gradeId: Number(contract.gradeId), occupationId: contract.occupationId ? Number(contract.occupationId) : null,
        endDate: contract.endDate || null, agreedNet: contract.agreedNet ? Number(contract.agreedNet) : null,
      },
    }), t('Contract saved.'))
  }

  const saveProfile = (e: FormEvent) => {
    e.preventDefault()
    return run(() => api(`/api/users/${id}/profile`, { method: 'PUT', body: profile }), t('Details saved.'))
  }

  const addDependant = (e: FormEvent) => {
    e.preventDefault()
    return run(async () => {
      await api(`/api/users/${id}/dependants`, { method: 'POST', body: { ...dependant, birthDate: dependant.birthDate || null } })
      setDependant({ fullName: '', relationship: 'CHILD', birthDate: '', inCharge: true, note: '' })
    }, t('Added.'))
  }

  const addItem = (e: FormEvent) => {
    e.preventDefault()
    return run(async () => {
      await api(`/api/users/${id}/pay-elements`, {
        method: 'POST',
        body: {
          elementId: Number(item.elementId), excluded: item.mode === 'exclude', note: item.note || null,
          amount: item.mode === 'amount' ? Number(item.amount) : null, currency: item.mode === 'amount' ? item.currency : null,
          percent: item.mode === 'percent' ? Number(item.percent) : null, startMonth: item.startMonth, endMonth: item.endMonth || null,
        },
      })
      setItem({ ...item, elementId: '', amount: '', percent: '', endMonth: '', note: '' })
    }, t('Standing item saved.'))
  }
  const standing = elements.filter((x) => x.active && x.method !== 'COMPUTED' && x.method !== 'MANUAL')
  const chosen = standing.find((x) => x.id === Number(item.elementId))
  const month = (d: string | null) => (d ? d.slice(0, 7) : '')

  const p = profile
  const setP = (patch: Partial<Profile>) => p && setProfile({ ...p, ...patch })

  return (
    <section className="narrow">
      <p><Link to={`/users/${id}`}>{t('← Back to employee')}</Link></p>
      <h2>{t('Contract and family')}{user ? `: ${user.firstName} ${user.lastName}` : ''}</h2>
      {user?.unitId && <p className="muted">{t('Unit:')}{' '}<Link to={`/org/units/${user.unitId}`}>{user.unitName}</Link></p>}
      <ErrorText error={error} />
      {message && <p className="success">{message}</p>}

      <div className="card">
        <h3>{t('Contracts')}</h3>
        {file?.contracts.length === 0 && <p className="muted">{t('No contract yet.')}</p>}
        {file?.contracts.map((c) => (
          <p key={c.id}>
            <strong>{CONTRACT_TYPES[c.contractType]}</strong> · {c.gradeName} ({c.gradeCode}){c.occupationName ? ` · ${c.occupationName}` : ''}{c.expatriate ? t(' · expatriate') : ''}
            <br /><span className="muted">{t('from')}{' '}{c.startDate}{c.endDate ? t(' to {a}', { a: c.endDate }) : ''}{c.note ? ` · ${c.note}` : ''}</span>
          </p>
        ))}
        {editable && <Button className="secondary" onClick={() => setContracting(true)}>{t('New contract or change')}</Button>}
        {editable && contracting && (
          <Modal title={t('New contract or change')} size="lg" onClose={() => setContracting(false)}>
            <Form onSubmit={async (e) => { if (await startContract(e)) setContracting(false) }}>
              <div className="grid3">
                <label>{t('Type')}<select value={contract.contractType} onChange={(e) => setContract({ ...contract, contractType: e.target.value })}>
                    {Object.entries(CONTRACT_TYPES).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
                  </select>
                </label>
                <label>{t('Starts')}<input type="date" value={contract.startDate} onChange={(e) => setContract({ ...contract, startDate: e.target.value })} required /></label>
                <label>{t('Ends')}<input type="date" value={contract.endDate} onChange={(e) => setContract({ ...contract, endDate: e.target.value })} required={contract.contractType === 'FIXED_TERM'} /></label>
              </div>
              <div className="grid2">
                <label>{t('Grade')}<select value={contract.gradeId} onChange={(e) => setContract({ ...contract, gradeId: e.target.value })} required>
                    <option value="">{t('Choose')}</option>
                    {grades.filter((g) => g.active).map((g) => <option key={g.id} value={g.id}>{g.name} · {money(g.baseSalary, g.currency)}</option>)}
                  </select>
                </label>
                <label>{t('Occupation')}<select value={contract.occupationId} onChange={(e) => setContract({ ...contract, occupationId: e.target.value })}>
                    <option value="">{t('None')}</option>
                    {occupations.filter((o) => o.active).map((o) => <option key={o.id} value={o.id}>{o.name}</option>)}
                  </select>
                </label>
              </div>
              <div className="grid2">
                <label>{t('Agreed net salary (optional)')}<input type="number" min="0.01" step="0.01" value={contract.agreedNet} placeholder={grade ? t('Grade basic: {a}', { a: money(grade.baseSalary, grade.currency) }) : ''}
                    onChange={(e) => setContract({ ...contract, agreedNet: e.target.value })} />
                </label>
                <label>{t('Note')}<input value={contract.note} maxLength={300} onChange={(e) => setContract({ ...contract, note: e.target.value })} /></label>
              </div>
              <label className="check"><input type="checkbox" checked={contract.expatriate} onChange={(e) => setContract({ ...contract, expatriate: e.target.checked })} />{t('Expatriate (employer pays IERE)')}</label>
              <p className="hint">{t('The previous contract ends the day before. The basic salary is the grade\'s. With an agreed net, HR Pay works out the gross and pays the difference above the basic as a salary complement.')}</p>
              <ErrorText error={error} />
            <div className="modal-foot"><Button className="secondary" onClick={() => setContracting(false)}>{t('Cancel')}</Button><Button type="submit">{t('Save contract')}</Button></div>
            </Form>
          </Modal>
        )}
      </div>

      {p && (
        <Form className="card" onSubmit={saveProfile}>
          <h3>{t('Personal and payment details')}</h3>
          <fieldset disabled={!editable}>
            <div className="grid3">
              <label>{t('Date of birth')}<input type="date" value={p.birthDate ?? ''} onChange={(e) => setP({ birthDate: e.target.value || null })} /></label>
              <label>{t('Gender')}<select value={p.gender ?? ''} onChange={(e) => setP({ gender: (e.target.value || null) as Profile['gender'] })}>
                  <option value="">{t('Not given')}</option><option value="FEMALE">{t('Female')}</option><option value="MALE">{t('Male')}</option>
                </select>
              </label>
              <label>{t('Nationality')}<input value={p.nationality ?? ''} onChange={(e) => setP({ nationality: e.target.value })} /></label>
            </div>
            <div className="grid3">
              <label>{t('Marital status')}<select value={p.maritalStatus} onChange={(e) => setP({ maritalStatus: e.target.value as Profile['maritalStatus'] })}>
                  <option value="SINGLE">{t('Single')}</option><option value="MARRIED">{t('Married')}</option><option value="DIVORCED">{t('Divorced')}</option><option value="WIDOWED">{t('Widowed')}</option>
                </select>
              </label>
              <label>{t('CNSS number')}<input value={p.cnssNumber ?? ''} onChange={(e) => setP({ cnssNumber: e.target.value })} /></label>
              <label>{t('Tax number (NIF)')}<input value={p.taxNumber ?? ''} onChange={(e) => setP({ taxNumber: e.target.value })} /></label>
            </div>
            <div className="grid2">
              <label>{t('Postnom')}<input value={p.postnom ?? ''} onChange={(e) => setP({ postnom: e.target.value })} maxLength={100} /></label>
              <label>{t('Commune or territory')}<input value={p.commune ?? ''} onChange={(e) => setP({ commune: e.target.value })} maxLength={100} placeholder={t('For the tax declaration')} /></label>
            </div>
            <label>{t('Paid by')}<select value={p.paymentMethod} onChange={(e) => setP({ paymentMethod: e.target.value as Profile['paymentMethod'] })}>
                <option value="BANK">{t('Bank transfer')}</option><option value="MOBILE_MONEY">{t('Mobile money')}</option><option value="CASH">{t('Cash')}</option>
              </select>
            </label>
            {p.paymentMethod === 'BANK' && (
              <div className="grid2">
                <label>{t('Bank')}<input value={p.bankName ?? ''} onChange={(e) => setP({ bankName: e.target.value })} required /></label>
                <label>{t('Account number')}<input value={p.bankAccount ?? ''} onChange={(e) => setP({ bankAccount: e.target.value })} required /></label>
              </div>
            )}
            {p.paymentMethod === 'MOBILE_MONEY' && (
              <div className="grid2">
                <label>{t('Operator')}<input value={p.mobileMoneyOperator ?? ''} placeholder={t('M-Pesa, Orange Money, Airtel Money')} onChange={(e) => setP({ mobileMoneyOperator: e.target.value })} required /></label>
                <label>{t('Number')}<input value={p.mobileMoneyNumber ?? ''} placeholder="+243…" onChange={(e) => setP({ mobileMoneyNumber: e.target.value })} required /></label>
              </div>
            )}
          </fieldset>
          {editable && <Button type="submit">{t('Save details')}</Button>}
        </Form>
      )}

      <div className="card">
        <h3>{t('Standing pay items')}</h3>
        <p className="hint">{t('Allowances that every employee gets (')}{standing.filter((x) => x.defaultForAll).map((x) => x.name).join(', ') || 'none'}{t(') apply automatically. Add an item here to give this employee a different amount, an extra allowance, or to exclude one.')}</p>
        {items.length === 0 && <p className="muted">{t('No standing items: the defaults apply.')}</p>}
        {items.map((a) => (
          <div key={a.id} className="row">
            <span>
              <strong>{a.elementName}</strong>{' · '}
              {a.excluded ? 'excluded' : a.amount !== null ? money(a.amount, a.currency ?? 'USD') : t('{a}% of basic', { a: a.percent })}
              {' · '}{t('from')}{' '}{month(a.startMonth)}{a.endMonth ? t(' to {a}', { a: month(a.endMonth) }) : ''}{a.note ? ` · ${a.note}` : ''}
            </span>
            {editable && !a.endMonth && (
              <Button className="secondary void" onClick={() => ask({
                title: t('End {a}', { a: a.elementName }), confirmLabel: t('Save'),
                fields: [{ label: t('Last month it applies'), type: 'month', defaultValue: new Date().toISOString().slice(0, 7), hint: t('Leave empty to remove it altogether.') }],
                onConfirm: ([last]) => run(() => api(`/api/users/${id}/pay-elements/${a.id}/end`, { method: 'PUT', body: { lastMonth: last || null } }), t('Updated.')),
              })}>{t('End')}</Button>
            )}
          </div>
        ))}
        {editable && <Button className="secondary" onClick={() => setAddingItem(true)}>{t('Add a pay item')}</Button>}
        {editable && addingItem && (
          <Modal title={t('Add a pay item')} size="lg" onClose={() => setAddingItem(false)}>
            <Form onSubmit={async (e) => { if (await addItem(e)) setAddingItem(false) }}>
              <div className="grid3">
                <label>{t('Element')}<select value={item.elementId} required onChange={(e) => {
                    const el = standing.find((x) => x.id === Number(e.target.value))
                    setItem({ ...item, elementId: e.target.value, mode: el?.method === 'PERCENT_OF_BASIC' ? 'percent' : 'amount', currency: el?.defaultCurrency ?? item.currency })
                  }}>
                    <option value="">{t('Choose')}</option>
                    {standing.map((x) => <option key={x.id} value={x.id}>{x.name}</option>)}
                  </select>
                </label>
                <label>{t('For this employee')}<select value={item.mode} onChange={(e) => setItem({ ...item, mode: e.target.value })}>
                    <option value="amount">{chosen?.method === 'PER_DAY_WORKED' ? t('Amount per day') : chosen?.method === 'PER_CHILD' ? t('Amount per child') : t('Amount')}</option>
                    {chosen?.method === 'PERCENT_OF_BASIC' && <option value="percent">{t('% of basic')}</option>}
                    <option value="exclude">{t('Exclude')}</option>
                  </select>
                </label>
                {item.mode === 'amount' && (
                  <div className="grid2">
                    <label>{t('Amount')}<input type="number" min={0} step={0.01} value={item.amount} required onChange={(e) => setItem({ ...item, amount: e.target.value })} /></label>
                    <label>{t('Currency')}<select value={item.currency} onChange={(e) => setItem({ ...item, currency: e.target.value })}><option>{t('USD')}</option><option>{t('CDF')}</option></select></label>
                  </div>
                )}
                {item.mode === 'percent' && (
                  <label>{t('Percentage')}<input type="number" min={0} max={100} step={0.001} value={item.percent} required onChange={(e) => setItem({ ...item, percent: e.target.value })} /></label>
                )}
                {item.mode === 'exclude' && <span />}
                <label>{t('First month')}<input type="month" value={item.startMonth} required onChange={(e) => setItem({ ...item, startMonth: e.target.value })} /></label>
                <label>{t('Last month (optional)')}<input type="month" value={item.endMonth} onChange={(e) => setItem({ ...item, endMonth: e.target.value })} /></label>
                <label>{t('Note')}<input value={item.note} maxLength={300} onChange={(e) => setItem({ ...item, note: e.target.value })} /></label>
              </div>
              <ErrorText error={error} />
            <div className="modal-foot"><Button className="secondary" onClick={() => setAddingItem(false)}>{t('Cancel')}</Button><Button type="submit">{t('Add item')}</Button></div>
            </Form>
          </Modal>
        )}
      </div>

      <div className="card">
        <h3>{t('Spouse and children')}</h3>
        <p className="hint">{t('Dependants in the employee\'s charge count for family allowances and the tax reduction.')}</p>
        {file?.dependants.length === 0 && <p className="muted">{t('None recorded.')}</p>}
        {file?.dependants.map((d) => (
          <div key={d.id} className="row">
            <span>{d.relationship === 'SPOUSE' ? t('Spouse') : t('Child')}: <strong>{d.fullName}</strong>{d.birthDate ? t(" · {a} years", { a: age(d.birthDate) }) : ''}{d.inCharge ? '' : t(' · not in charge')}</span>
            {editable && (
              <>
                <Button className="secondary void" onClick={() => run(() => api(`/api/users/${id}/dependants/${d.id}`, { method: 'PUT', body: { inCharge: !d.inCharge, note: d.note } }), t('Updated.'))}>
                  {d.inCharge ? t('Mark not in charge') : t('Mark in charge')}
                </Button>
                <Button className="secondary void" onClick={() => ask({
                  title: t('Remove {a}?', { a: d.fullName }), message: t('Family allowances stop counting this person from the next payroll.'), confirmLabel: t('Remove'), danger: true,
                  onConfirm: () => run(() => api(`/api/users/${id}/dependants/${d.id}`, { method: 'DELETE' }), t('Removed.')),
                })}>{t('Remove')}</Button>
              </>
            )}
          </div>
        ))}
        {editable && <Button className="secondary" onClick={() => setAddingDependant(true)}>{t('Add a spouse or child')}</Button>}
        {editable && addingDependant && (
          <Modal title={t('Add a spouse or child')} size="sm" onClose={() => setAddingDependant(false)}>
            <Form onSubmit={async (e) => { if (await addDependant(e)) setAddingDependant(false) }}>
              <label>{t('Relationship')}<select value={dependant.relationship} onChange={(e) => setDependant({ ...dependant, relationship: e.target.value })}>
                <option value="CHILD">{t('Child')}</option><option value="SPOUSE">{t('Spouse')}</option>
              </select></label>
              <label>{t('Full name')}<input value={dependant.fullName} onChange={(e) => setDependant({ ...dependant, fullName: e.target.value })} required /></label>
              <label>{t('Date of birth')}<input type="date" value={dependant.birthDate} onChange={(e) => setDependant({ ...dependant, birthDate: e.target.value })} required={dependant.relationship === 'CHILD'} /></label>
              <ErrorText error={error} />
            <div className="modal-foot"><Button className="secondary" onClick={() => setAddingDependant(false)}>{t('Cancel')}</Button><Button type="submit">{t('Add')}</Button></div>
            </Form>
          </Modal>
        )}
      </div>
    </section>
  )
}
