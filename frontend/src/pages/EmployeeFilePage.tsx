import { useEffect, useState, type FormEvent } from 'react'
import { Link, useParams } from 'react-router-dom'
import { ApiError, CONTRACT_TYPES, ID_DOCUMENTS, api, money, type Dependant, type EmployeeFileView, type Grade, type IdDocumentType, type Occupation, type PayAssignment, type PayElement, type Profile, type User , personName } from '../api'
import { CountrySelect, InstitutionSelect, OldValue, PlacePicker, useCountries } from '../components/ReferencePickers'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'
import { useAsk } from '../components/Dialogs'
import { Button, Form } from '../components/Button'
import { Modal } from '../components/Modal'
import { GradeChangeModal } from '../components/GradeChange'
import { ContractDocumentModal } from '../components/ContractDocument'
import { t } from '../i18n'

export default function EmployeeFilePage() {
  // null: closed; 0: adding; an id: editing that person.
  const [editingDependant, setEditingDependant] = useState<number | null>(null)
  const [gradeMode, setGradeMode] = useState<'change' | 'correct' | null>(null)
  const [addingItem, setAddingItem] = useState(false)
  const [contracting, setContracting] = useState(false)
  const [documentFor, setDocumentFor] = useState<number | null>(null)
  const ask = useAsk()
  const { id } = useParams()
  const { can, me } = useAuth()
  const home = me?.company?.country ?? 'CD'
  const { withPlaces } = useCountries()
  const editable = can('salary.write')
  const [user, setUser] = useState<User | null>(null)
  const [file, setFile] = useState<EmployeeFileView | null>(null)
  const [grades, setGrades] = useState<Grade[]>([])
  const [occupations, setOccupations] = useState<Occupation[]>([])
  const [profile, setProfile] = useState<Profile | null>(null)
  const [contract, setContract] = useState({ contractType: 'PERMANENT', startDate: '', endDate: '', gradeId: '', occupationId: '', expatriate: false, agreedNet: '', note: '', hireDate: '' })
  const emptyDependant = { fullName: '', relationship: 'CHILD', birthDate: '', inCharge: true, student: false, disabled: false, note: '' }
  const [dependant, setDependant] = useState(emptyDependant)
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
  const today = new Date().toISOString().slice(0, 10)
  const latestContract = file?.contracts[0] ?? null
  const firstContract = (file?.contracts.length ?? 0) === 0
  const currentContract = file?.contracts.find((c) => c.startDate <= today && (!c.endDate || c.endDate >= today)) ?? latestContract

  const startContract = (e: FormEvent) => {
    e.preventDefault()
    return run(() => api(`/api/users/${id}/contracts`, {
      method: 'POST',
      body: {
        ...contract, gradeId: Number(contract.gradeId), occupationId: contract.occupationId ? Number(contract.occupationId) : null,
        endDate: contract.endDate || null, agreedNet: contract.agreedNet ? Number(contract.agreedNet) : null,
        hireDate: firstContract && contract.hireDate ? contract.hireDate : null,
      },
    }), t('Contract saved.'))
  }

  const saveProfile = (e: FormEvent) => {
    e.preventDefault()
    return run(() => api(`/api/users/${id}/profile`, { method: 'PUT', body: profile }), t('Details saved.'))
  }

  const openDependant = (d: Dependant | null) => {
    setDependant(d ? { fullName: d.fullName, relationship: d.relationship, birthDate: d.birthDate ?? '', inCharge: d.inCharge,
      student: d.student, disabled: d.disabled, note: d.note ?? '' } : emptyDependant)
    setEditingDependant(d ? d.id : 0)
  }

  const saveDependant = (e: FormEvent) => {
    e.preventDefault()
    const body = { ...dependant, birthDate: dependant.birthDate || null, note: dependant.note || null }
    return editingDependant
      ? run(() => api(`/api/users/${id}/dependants/${editingDependant}`, { method: 'PUT', body }), t('Updated.'))
      : run(() => api(`/api/users/${id}/dependants`, { method: 'POST', body }), t('Added.'))
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
    <section>
      <p><Link to={`/users/${id}`}>{t('← Back to employee')}</Link></p>
      <h2>{t('Contract and family')}{user ? `: ${personName(user)}` : ''}</h2>
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
            {can('lifecycle.manage') && <>{' '}<Button className="secondary small" onClick={() => setDocumentFor(c.id)}>{t('Contract document')}</Button></>}
          </p>
        ))}
        {editable && (
          <div className="row">
            <Button className="secondary" onClick={() => setContracting(true)}>{t('New contract or change')}</Button>
            {currentContract && <Button className="secondary" onClick={() => setGradeMode('change')}>{t('Change grade')}</Button>}
            {latestContract && <Button className="secondary void" onClick={() => setGradeMode('correct')}>{t('Correct the grade')}</Button>}
          </div>
        )}
        {documentFor !== null && file?.contracts.find((c) => c.id === documentFor) && (
          <ContractDocumentModal userId={id!} contract={file.contracts.find((c) => c.id === documentFor)!} onClose={() => setDocumentFor(null)} />
        )}
        {editable && gradeMode && (gradeMode === 'change' ? currentContract : latestContract) && (
          <GradeChangeModal userId={id!} contract={(gradeMode === 'change' ? currentContract : latestContract)!} mode={gradeMode}
            onClose={() => setGradeMode(null)} onDone={() => { setGradeMode(null); setMessage(t('Grade saved.')); load() }} />
        )}
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
              {firstContract && (
                <label>{t('In the company since (optional)')}<input type="date" value={contract.hireDate} max={contract.startDate || undefined}
                    onChange={(e) => setContract({ ...contract, hireDate: e.target.value })} />
                  <span className="hint">{t('The hire date. Empty: the contract start. Give an earlier date only for someone hired before this contract (seniority).')}</span>
                </label>
              )}
              {firstContract && <p className="hint">{t('The first salary is set here: the agreed net, or the grade\'s basic. Raises and promotions come later.')}</p>}
              <label className="check"><input type="checkbox" checked={contract.expatriate} onChange={(e) => setContract({ ...contract, expatriate: e.target.checked })} />{t('Expatriate (employer pays IERE)')}</label>
              <p className="hint">{t('The previous contract ends the day before. With an agreed net, what is above the grade\'s basic is paid as a salary complement.')}</p>
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
            <h4>{t('Identity')}</h4>
            <div className="grid3">
              <label>{t('Date of birth')}<input type="date" value={p.birthDate ?? ''} onChange={(e) => setP({ birthDate: e.target.value || null })} /></label>
              <label>{t('Gender')}<select value={p.gender ?? ''} onChange={(e) => setP({ gender: (e.target.value || null) as Profile['gender'] })}>
                  <option value="">{t('Not given')}</option><option value="FEMALE">{t('Female')}</option><option value="MALE">{t('Male')}</option>
                </select>
              </label>
              <label>{t('Nationality')}<CountrySelect nationality value={p.nationalityCode} onChange={(c) => setP({ nationalityCode: c })} />
                {!p.nationalityCode && <OldValue value={file?.profile.nationalityCode ? null : file?.profile.nationality} />}
              </label>
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
              <label>{t('Identity document')}<select value={p.idDocumentType ?? ''} onChange={(e) => setP({ idDocumentType: (e.target.value || null) as IdDocumentType | null })}>
                  <option value="">{t('Not given')}</option>
                  {(Object.keys(ID_DOCUMENTS) as IdDocumentType[]).map((k) => <option key={k} value={k}>{ID_DOCUMENTS[k]}</option>)}
                </select>
              </label>
              <label>{t('Document number')}<input value={p.idDocumentNumber ?? ''} maxLength={40} onChange={(e) => setP({ idDocumentNumber: e.target.value })} /></label>
            </div>
            <div className="grid2">
              <label>{t('Issued on')}<input type="date" value={p.idDocumentIssuedOn ?? ''} onChange={(e) => setP({ idDocumentIssuedOn: e.target.value || null })} /></label>
              <label>{t('Issued at')}<input value={p.idDocumentIssuedAt ?? ''} maxLength={100} onChange={(e) => setP({ idDocumentIssuedAt: e.target.value })} /></label>
            </div>

            <h4>{t('Place of birth')}</h4>
            <div className="grid3">
              <label>{t('Country of birth')}<CountrySelect value={p.birthCountryCode} onChange={(c) => setP({ birthCountryCode: c, birthPlaceId: null, birthPlaceText: null })} /></label>
              {p.birthCountryCode && !withPlaces.includes(p.birthCountryCode) && (
                <label>{t('Town of birth')}<input value={p.birthPlaceText ?? ''} maxLength={100} onChange={(e) => setP({ birthPlaceText: e.target.value })} />
                  <span className="hint">{t('Abroad: the town is typed.')}</span>
                </label>
              )}
            </div>
            {p.birthCountryCode && withPlaces.includes(p.birthCountryCode) && (
              <PlacePicker country={p.birthCountryCode} path={file?.birthPlacePath ?? []} disabled={!editable}
                onChange={(place) => setP({ birthPlaceId: place?.id ?? null, birthPlaceText: null })} />
            )}

            <h4>{t('Current address')}</h4>
            <PlacePicker country={home} path={file?.addressPlacePath ?? []} disabled={!editable}
              onChange={(place) => setP({ addressPlaceId: place?.id ?? null })} />
            {!p.addressPlaceId && <OldValue value={file?.profile.addressPlaceId ? null : file?.profile.commune} />}
            <div className="grid2">
              <label>{t('Quarter (quartier)')}<input value={p.addressQuarter ?? ''} maxLength={100} onChange={(e) => setP({ addressQuarter: e.target.value })} /></label>
              <label>{t('Avenue and number')}<input value={p.addressStreet ?? ''} maxLength={200} onChange={(e) => setP({ addressStreet: e.target.value })} /></label>
            </div>
            <p className="hint">{t('The commune or territory goes on the tax declaration; the CNSS registration asks for the full address.')}</p>

            {p.gender !== 'MALE' && (
              <label>{t('Nursing breaks until')}<input type="date" value={p.nursingBreakUntil ?? ''} onChange={(e) => setP({ nursingBreakUntil: e.target.value || null })} />
                <span className="muted small">{t('Two paid half hours a day while she nurses her child (Code du travail art. 132): an hour less at work is not deducted.')}</span>
              </label>
            )}

            <h4>{t('Payment')}</h4>
            <label>{t('Paid by')}<select value={p.paymentMethod} onChange={(e) => setP({ paymentMethod: e.target.value as Profile['paymentMethod'] })}>
                <option value="BANK">{t('Bank transfer')}</option><option value="MOBILE_MONEY">{t('Mobile money')}</option><option value="CASH">{t('Cash')}</option>
              </select>
            </label>
            {p.paymentMethod === 'BANK' && (
              <div className="grid2">
                <label>{t('Bank')}<InstitutionSelect country={home} kind="BANK" value={p.bankId} required={!p.bankName}
                    onChange={(b) => setP({ bankId: b?.id ?? null, bankName: b ? b.name : file?.profile.bankId ? null : file?.profile.bankName ?? null })} />
                  {!p.bankId && <OldValue value={file?.profile.bankId ? null : file?.profile.bankName} />}
                </label>
                <label>{t('Account number')}<input value={p.bankAccount ?? ''} onChange={(e) => setP({ bankAccount: e.target.value })} required /></label>
              </div>
            )}
            {p.paymentMethod === 'MOBILE_MONEY' && (
              <div className="grid2">
                <label>{t('Operator')}<InstitutionSelect country={home} kind="MOBILE_MONEY" value={p.mobileMoneyOperatorId} required={!p.mobileMoneyOperator}
                    onChange={(o) => setP({ mobileMoneyOperatorId: o?.id ?? null, mobileMoneyOperator: o ? o.name : file?.profile.mobileMoneyOperatorId ? null : file?.profile.mobileMoneyOperator ?? null })} />
                  {!p.mobileMoneyOperatorId && <OldValue value={file?.profile.mobileMoneyOperatorId ? null : file?.profile.mobileMoneyOperator} />}
                </label>
                <label>{t('Number')}<input value={p.mobileMoneyNumber ?? ''} placeholder="+243…" onChange={(e) => setP({ mobileMoneyNumber: e.target.value })} required /></label>
              </div>
            )}
            {can('masterdata.manage') && <p className="hint">{t('A bank, operator or place is missing? Add it on the')}{' '}<Link to="/reference-lists">{t('Reference lists')}</Link>{t(' page.')}</p>}
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
        <p className="hint">{t('The spouse and the children in the employee\'s charge count for family allowances and the tax reduction. Payroll checks the children\'s ages each month against the statutory rates (DRC: until 18, until 25 while studying full time, no limit if disabled, Code du travail art. 7).')}</p>
        {file?.dependants.length === 0 && <p className="muted">{t('None recorded.')}</p>}
        {file && file.dependants.length > 0 && (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>{t('Relationship')}</th><th>{t('Full name')}</th><th>{t('Date of birth')}</th><th className="num">{t('Age')}</th>
                  <th>{t('In charge')}</th><th>{t('Counts for allowances')}</th><th>{t('Note')}</th>{editable && <th />}
                </tr>
              </thead>
              <tbody>
                {file.dependants.map((d) => (
                  <tr key={d.id}>
                    <td>{d.relationship === 'SPOUSE' ? t('Spouse') : t('Child')}
                      {d.student && <span className="badge" style={{ marginLeft: 6 }}>{t('Student')}</span>}
                      {d.disabled && <span className="badge" style={{ marginLeft: 6 }}>{t('Disabled')}</span>}</td>
                    <td><strong>{d.fullName}</strong></td>
                    <td>{d.birthDate ?? <span className="muted">{t('Not given')}</span>}</td>
                    <td className="num">{d.age ?? ''}</td>
                    <td>{d.inCharge ? t('Yes') : <span className="muted">{t('Not in charge')}</span>}</td>
                    <td>{d.counts
                      ? <span className="badge ok">{d.countsUntil ? t('Yes, until {a}', { a: d.countsUntil }) : t('Yes')}</span>
                      : <span className="badge warn" title={d.reason ?? ''}>{d.reason ?? t('Not counted')}</span>}</td>
                    <td className="muted">{d.note ?? ''}</td>
                    {editable && (
                      <td style={{ whiteSpace: 'nowrap' }}>
                        <Button className="secondary void" onClick={() => openDependant(d)}>{t('Edit')}</Button>{' '}
                        <Button className="secondary void" onClick={() => ask({
                          title: t('Remove {a}?', { a: d.fullName }), message: t('Family allowances stop counting this person from the next payroll.'), confirmLabel: t('Remove'), danger: true,
                          onConfirm: () => run(() => api(`/api/users/${id}/dependants/${d.id}`, { method: 'DELETE' }), t('Removed.')),
                        })}>{t('Remove')}</Button>
                      </td>
                    )}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {editable && <Button className="secondary" onClick={() => openDependant(null)}>{t('Add a spouse or child')}</Button>}
        {editable && editingDependant !== null && (
          <Modal title={editingDependant ? t('Edit {a}', { a: dependant.fullName || t('family member') }) : t('Add a spouse or child')} size="sm" onClose={() => setEditingDependant(null)}>
            <Form onSubmit={async (e) => { if (await saveDependant(e)) setEditingDependant(null) }}>
              <label>{t('Relationship')}<select value={dependant.relationship} onChange={(e) => setDependant({ ...dependant, relationship: e.target.value })}>
                <option value="CHILD">{t('Child')}</option><option value="SPOUSE">{t('Spouse')}</option>
              </select></label>
              <label>{t('Full name')}<input value={dependant.fullName} maxLength={150} onChange={(e) => setDependant({ ...dependant, fullName: e.target.value })} required /></label>
              <label>{t('Date of birth')}<input type="date" value={dependant.birthDate} onChange={(e) => setDependant({ ...dependant, birthDate: e.target.value })} required={dependant.relationship === 'CHILD'} /></label>
              <label className="check"><input type="checkbox" checked={dependant.inCharge} onChange={(e) => setDependant({ ...dependant, inCharge: e.target.checked })} />{t('In the employee\'s charge')}</label>
              {dependant.relationship === 'CHILD' && (
                <>
                  <label className="check"><input type="checkbox" checked={dependant.student} onChange={(e) => setDependant({ ...dependant, student: e.target.checked })} />{t('Studies full time')}</label>
                  <label className="check"><input type="checkbox" checked={dependant.disabled} onChange={(e) => setDependant({ ...dependant, disabled: e.target.checked })} />{t('Disabled, cannot earn a living')}</label>
                </>
              )}
              <label>{t('Note')}<input value={dependant.note} maxLength={300} onChange={(e) => setDependant({ ...dependant, note: e.target.value })} /></label>
              <p className="hint">{t('A minor who works for a normal wage does not count: untick "in charge".')}</p>
              <ErrorText error={error} />
            <div className="modal-foot"><Button className="secondary" onClick={() => setEditingDependant(null)}>{t('Cancel')}</Button><Button type="submit">{editingDependant ? t('Save') : t('Add')}</Button></div>
            </Form>
          </Modal>
        )}
      </div>
    </section>
  )
}
