import { useEffect, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { ApiError, PAY_KINDS, PAY_METHODS, api, money, type PayElement, type PayMethod } from '../api'
import { useAuth } from '../auth'
import { ErrorText } from '../components/ErrorText'
import { Button, Form } from '../components/Button'
import { Modal } from '../components/Modal'
import { t } from '../i18n'

const describe = (e: PayElement) => {
  switch (e.method) {
    case 'FIXED': return `${money(e.defaultAmount ?? 0, e.defaultCurrency ?? 'USD')} a month`
    case 'PERCENT_OF_BASIC': return t('{a}% of basic', { a: e.percent })
    case 'PER_DAY_WORKED': return t('{a} a day worked', { a: money(e.defaultAmount ?? 0, e.defaultCurrency ?? 'USD') })
    case 'PER_CHILD': return `${money(e.defaultAmount ?? 0, e.defaultCurrency ?? 'USD')} a child`
    default: return PAY_METHODS[e.method]
  }
}

const blankElement = {
  code: '', name: '', kind: 'EARNING', method: 'FIXED' as PayMethod, defaultAmount: '', defaultCurrency: 'USD', percent: '',
  subjectToCnss: false, taxableIpr: false, iprExemptCapPercent: '', prorated: false, defaultForAll: false, displayOrder: 100,
}

export default function PayElementsPage() {
  const { can } = useAuth()
  const manage = can('masterdata.manage')
  const [elements, setElements] = useState<PayElement[]>([])
  const [edit, setEdit] = useState<typeof blankElement & { id?: number; system?: boolean; active?: boolean } | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)

  const loadElements = () => api<PayElement[]>('/api/pay-elements').then(setElements).catch((e: Error) => setError(e.message))
  useEffect(() => { void loadElements() }, [])

  const run = async (action: () => Promise<unknown>, done: string) => {
    setError(null)
    setMessage(null)
    try {
      await action()
      setMessage(done)
      await loadElements()
      return true
    } catch (e) {
      setError(e instanceof ApiError ? [e.message, ...Object.values(e.fieldErrors)].join(' ') : t('Something went wrong.'))
      return false
    }
  }

  const saveElement = (ev: FormEvent) => {
    ev.preventDefault()
    if (!edit) return
    const num = (v: string | number) => (v === '' || v === null ? null : Number(v))
    const body = {
      ...edit, defaultAmount: num(edit.defaultAmount), percent: num(edit.percent), iprExemptCapPercent: num(edit.iprExemptCapPercent),
      defaultCurrency: edit.defaultAmount === '' ? null : edit.defaultCurrency, active: edit.active ?? true,
      kind: edit.system ? 'EARNING' : edit.kind, method: edit.system ? 'FIXED' : edit.method,
    }
    return run(async () => {
      await api(edit.id ? `/api/pay-elements/${edit.id}` : '/api/pay-elements', { method: edit.id ? 'PUT' : 'POST', body })
      setEdit(null)
    }, t('Pay element saved.'))
  }

  const startEdit = (e: PayElement) => setEdit({
    id: e.id, system: e.system, active: e.active, code: e.code, name: e.name, kind: e.kind, method: e.method,
    defaultAmount: e.defaultAmount === null ? '' : String(e.defaultAmount), defaultCurrency: e.defaultCurrency ?? 'USD',
    percent: e.percent === null ? '' : String(e.percent), subjectToCnss: e.subjectToCnss, taxableIpr: e.taxableIpr,
    iprExemptCapPercent: e.iprExemptCapPercent === null ? '' : String(e.iprExemptCapPercent), prorated: e.prorated,
    defaultForAll: e.defaultForAll, displayOrder: e.displayOrder,
  })

  const needsAmount = edit && ['FIXED', 'PER_DAY_WORKED', 'PER_CHILD'].includes(edit.method)

  return (
    <section>
      <h2>{t('Pay elements')}</h2>
      <ErrorText error={error} />
      {message && <p className="success">{message}</p>}

      <p className="muted">{t('Bonuses and deductions for one month are entered under')}{' '}<Link to="/payroll/entries">{t('Bonuses and deductions')}</Link>.</p>

      <div className="card">
        <h3>{t('Catalogue')}</h3>
        <p className="hint">{t('Sample values: have a payroll specialist confirm CNSS and IPR before go-live. Built-in elements can only be renamed.')}</p>
        <div className="table-wrap">
          <table>
            <thead><tr><th>{t('Code')}</th><th>{t('Name')}</th><th>{t('Type')}</th><th>{t('Rule')}</th><th>{t('CNSS')}</th><th>{t('IPR')}</th><th>{t('Everyone')}</th><th>{t('Active')}</th><th></th></tr></thead>
            <tbody>
              {elements.map((e) => (
                <tr key={e.id} className={e.active ? '' : 'muted'}>
                  <td>{e.code}</td>
                  <td>{e.name}</td>
                  <td>{PAY_KINDS[e.kind]}</td>
                  <td>{describe(e)}{e.prorated ? t(', prorated') : ''}{e.iprExemptCapPercent !== null ? t(', IPR-free up to {a}% of basic', { a: e.iprExemptCapPercent }) : ''}</td>
                  <td>{e.kind === 'EARNING' ? (e.subjectToCnss ? t('Yes') : t('No')) : ''}</td>
                  <td>{e.kind === 'EARNING' ? (e.taxableIpr ? t('Yes') : t('No')) : ''}</td>
                  <td>{e.defaultForAll ? t('Yes') : ''}</td>
                  <td>{e.active ? t('Yes') : t('No')}</td>
                  <td>{manage && <Button className="secondary" onClick={() => startEdit(e)}>{t('Edit')}</Button>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {manage && !edit && <Button className="secondary" onClick={() => setEdit({ ...blankElement })}>{t('New element')}</Button>}
        {manage && edit && (
          <Modal title={edit.id ? t('Edit {a}', { a: edit.code }) : t('New pay element')} size="lg" onClose={() => setEdit(null)}>
            <Form onSubmit={saveElement}>
              <div className="grid3">
                <label>{t('Code')}<input value={edit.code} disabled={!!edit.id} required pattern="[A-Za-z0-9_-]{1,32}" onChange={(e) => setEdit({ ...edit, code: e.target.value.toUpperCase() })} /></label>
                <label>{t('Name')}<input value={edit.name} required maxLength={150} onChange={(e) => setEdit({ ...edit, name: e.target.value })} /></label>
                <label>{t('Order on payslip')}<input type="number" min={0} max={999} value={edit.displayOrder} onChange={(e) => setEdit({ ...edit, displayOrder: Number(e.target.value) })} /></label>
              </div>
              {!edit.system && (
                <>
                  <div className="grid3">
                    <label>{t('Type')}<select value={edit.kind} onChange={(e) => setEdit({ ...edit, kind: e.target.value })}>
                        <option value="EARNING">{t('Earning')}</option><option value="DEDUCTION">{t('Deduction')}</option>
                      </select>
                    </label>
                    <label>{t('Rule')}<select value={edit.method} onChange={(e) => setEdit({ ...edit, method: e.target.value as PayMethod })}>
                        {(['FIXED', 'PERCENT_OF_BASIC', 'PER_DAY_WORKED', 'PER_CHILD', 'MANUAL'] as PayMethod[]).map((m) => <option key={m} value={m}>{PAY_METHODS[m]}</option>)}
                      </select>
                    </label>
                    {edit.method === 'PERCENT_OF_BASIC' && (
                      <label>{t('Percentage')}<input type="number" min={0} max={100} step={0.001} value={edit.percent} required onChange={(e) => setEdit({ ...edit, percent: e.target.value })} /></label>
                    )}
                    {needsAmount && (
                      <>
                        <label>{t('Default amount')}<input type="number" min={0} step={0.01} value={edit.defaultAmount} required onChange={(e) => setEdit({ ...edit, defaultAmount: e.target.value })} /></label>
                        <label>{t('Currency')}<select value={edit.defaultCurrency} onChange={(e) => setEdit({ ...edit, defaultCurrency: e.target.value })}><option>{t('USD')}</option><option>{t('CDF')}</option></select></label>
                      </>
                    )}
                  </div>
                </>
              )}
              {edit.kind === 'EARNING' && (
                    <div className="grid3">
                      <label className="check"><input type="checkbox" checked={edit.subjectToCnss} onChange={(e) => setEdit({ ...edit, subjectToCnss: e.target.checked })} />{' '}{t('Subject to CNSS')}</label>
                      <label className="check"><input type="checkbox" checked={edit.taxableIpr} onChange={(e) => setEdit({ ...edit, taxableIpr: e.target.checked })} />{' '}{t('Taxable for IPR')}</label>
                      {edit.taxableIpr && (
                        <label>{t('IPR-free share, % of basic (optional)')}<input type="number" min={0} max={100} step={0.001} value={edit.iprExemptCapPercent} onChange={(e) => setEdit({ ...edit, iprExemptCapPercent: e.target.value })} /></label>
                      )}
                    </div>
              )}
              {!edit.system && (
                <>
                  <div className="grid3">
                    <label className="check"><input type="checkbox" checked={edit.prorated} onChange={(e) => setEdit({ ...edit, prorated: e.target.checked })} />{' '}{t('Prorated by hours paid')}</label>
                    {edit.method !== 'MANUAL' && (
                      <label className="check"><input type="checkbox" checked={edit.defaultForAll} onChange={(e) => setEdit({ ...edit, defaultForAll: e.target.checked })} />{' '}{t('Applies to every employee')}</label>
                    )}
                    {edit.id && <label className="check"><input type="checkbox" checked={edit.active ?? true} onChange={(e) => setEdit({ ...edit, active: e.target.checked })} />{' '}{t('Active')}</label>}
                  </div>
                </>
              )}
              <div className="modal-foot"><Button className="secondary" onClick={() => setEdit(null)}>{t('Cancel')}</Button><Button type="submit">{t('Save')}</Button></div>
  
            </Form>
          </Modal>
        )}
      </div>
    </section>
  )
}
