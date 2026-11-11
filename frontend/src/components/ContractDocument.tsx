import { useEffect, useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  ApiError, CONTRACT_TYPES, FIXED_TERM_REASONS, api, type ContractIssue, type ContractTermsView, type Contract as EmployeeContract,
  type FixedTermReason, type Letter, type LetterDraft, type LetterTemplate, type Site,
  day,
} from '../api'
import { Button, Form } from './Button'
import { ErrorText } from './ErrorText'
import { Modal } from './Modal'
import { CityPicker, OldValue } from './ReferencePickers'
import { t } from '../i18n'
import { DateInput } from './DateFields'

/** The Labour Code points of a contract: what blocks the document, and what to look at. */
export function ContractIssues({ issues }: { issues: ContractIssue[] }) {
  if (issues.length === 0) return <p className="success">{t('Nothing missing: the contract can be written and sent.')}</p>
  return (
    <ul className="issues">
      {issues.map((i) => (
        <li key={i.code} className={i.level === 'BLOCK' ? 'error' : 'hint'}>
          <span className={i.level === 'BLOCK' ? 'badge off' : 'badge warn'}>{i.level === 'BLOCK' ? t('To fix') : t('To check')}</span>{' '}{i.message}
        </li>
      ))}
    </ul>
  )
}

const empty = {
  trialMonths: '0', unskilledLabourer: false, fixedTermReason: '' as FixedTermReason | '', fixedTermDetail: '', permanentJob: false,
  workSiteId: '', supervisor: '', signedPlaceId: '', signedCountry: '', signedOn: '', draftGivenOn: '', medicalFitOn: '', medicalDoctor: '', onemVisaNo: '', onemVisaOn: '',
}

/**
 * What a written contract states beyond its kind, dates and grade (trial, why it is fixed-term, place, medical fitness,
 * draft handed over, ONEM visa), then "Write the contract": a draft from the company's model, opened in the person's
 * Contracts and letters to read, print, send for signature or download in Word.
 */
export function ContractDocumentModal({ userId, contract, onClose }: { userId: string; contract: EmployeeContract; onClose: () => void }) {
  const navigate = useNavigate()
  const [view, setView] = useState<ContractTermsView | null>(null)
  const [form, setForm] = useState(empty)
  const [error, setError] = useState('')
  const [saved, setSaved] = useState('')
  const [sites, setSites] = useState<Site[]>([])
  useEffect(() => { api<Site[]>('/api/sites').then(setSites).catch(() => setSites([])) }, [])
  const base = `/api/lifecycle/people/${userId}/contracts/${contract.id}/terms`
  const fixed = contract.contractType === 'FIXED_TERM'

  const fill = (v: ContractTermsView) => {
    const x = v.terms
    setView(v)
    setForm({
      trialMonths: String(x.trialMonths), unskilledLabourer: x.unskilledLabourer, fixedTermReason: x.fixedTermReason ?? '',
      fixedTermDetail: x.fixedTermDetail ?? '', permanentJob: x.permanentJob, workSiteId: x.workSiteId ? String(x.workSiteId) : '',
      supervisor: x.supervisor ?? '', signedPlaceId: x.signedPlaceId ? String(x.signedPlaceId) : '', signedCountry: v.signedCountry ?? '', signedOn: x.signedOn ?? '', draftGivenOn: x.draftGivenOn ?? '', medicalFitOn: x.medicalFitOn ?? '',
      medicalDoctor: x.medicalDoctor ?? '', onemVisaNo: x.onemVisaNo ?? '', onemVisaOn: x.onemVisaOn ?? '',
    })
  }
  useEffect(() => { api<ContractTermsView>(base).then(fill).catch((e) => setError(e instanceof Error ? e.message : String(e))) }, [base])

  const save = async () => {
    const body: Record<string, unknown> = Object.fromEntries(Object.entries(form).map(([k, v]) => [k, v === '' ? null : v]))
    body.trialMonths = Number(form.trialMonths)
    body.workSiteId = form.workSiteId ? Number(form.workSiteId) : null
    body.signedPlaceId = form.signedPlaceId ? Number(form.signedPlaceId) : null
    delete body.signedCountry
    const v = await api<ContractTermsView>(base, { method: 'PUT', body })
    fill(v)
    return v
  }

  const run = async (work: () => Promise<unknown>) => {
    setError(''); setSaved('')
    try { await work() } catch (e) {
      setError(e instanceof ApiError ? [e.message, ...Object.values(e.fieldErrors)].join(' ') : t('Something went wrong.'))
    }
  }

  const submit = (e: FormEvent) => { e.preventDefault(); return run(async () => { await save(); setSaved(t('Saved.')) }) }

  /** Saves, then makes the draft from the model that fits the contract and opens it. */
  const write = () => run(async () => {
    await save()
    const templates = (await api<LetterTemplate[]>('/api/lifecycle/templates')).filter((x) => x.active && x.kind === 'CONTRACT')
    const wanted = fixed ? 'CONTRACT_CDD' : 'CONTRACT_CDI'
    const template = templates.find((x) => x.code === `${wanted}_FULL`) ?? templates.find((x) => x.code.startsWith(wanted)) ?? templates[0]
    if (!template) throw new Error(t('No contract model is active: add one in the lifecycle settings.'))
    const draft = await api<LetterDraft>(`/api/lifecycle/people/${userId}/letters/preview`, { method: 'POST', body: { templateId: template.id, contractId: contract.id } })
    await api<Letter>(`/api/lifecycle/people/${userId}/letters`, {
      method: 'POST',
      body: { templateId: template.id, contractId: contract.id, title: draft.title, body: template.body, kind: 'CONTRACT', requiresSignature: true },
    })
    navigate(`/users/${userId}/lifecycle`)
  })

  const set = (patch: Partial<typeof form>) => setForm({ ...form, ...patch })

  return (
    <Modal title={t('Contract document')} size="lg" onClose={onClose}>
      <p className="muted">
        {CONTRACT_TYPES[contract.contractType]} · {contract.gradeName} ({contract.gradeCode}){contract.occupationName ? ` · ${contract.occupationName}` : ''}
        {' · '}{t('from')}{' '}{day(contract.startDate)}{contract.endDate ? t(' to {a}', { a: contract.endDate }) : ''}
      </p>
      <ErrorText error={error} />
      {saved && <p className="success">{saved}</p>}
      {view && <ContractIssues issues={view.issues} />}
      <Form onSubmit={submit}>
        <div className="grid2">
          <label>{t('Trial period (months)')}
            <input type="number" min={0} max={form.unskilledLabourer ? 1 : 6} value={form.trialMonths} onChange={(e) => set({ trialMonths: e.target.value })} required />
          </label>
          <label className="check"><input type="checkbox" checked={form.unskilledLabourer} onChange={(e) => set({ unskilledLabourer: e.target.checked })} />{t('Unskilled labourer (trial one month at most)')}</label>
          {fixed && (
            <label>{t('Why fixed-term')}
              <select value={form.fixedTermReason} onChange={(e) => set({ fixedTermReason: e.target.value as FixedTermReason })} required>
                <option value="">{t('Choose…')}</option>
                {(Object.keys(FIXED_TERM_REASONS) as FixedTermReason[]).map((r) => <option key={r} value={r}>{FIXED_TERM_REASONS[r]}</option>)}
              </select>
            </label>
          )}
          {fixed && (
            <label>{form.fixedTermReason === 'REPLACEMENT' ? t('Person replaced, and why') : t('Details (optional)')}
              <input value={form.fixedTermDetail} maxLength={200} onChange={(e) => set({ fixedTermDetail: e.target.value })} required={form.fixedTermReason === 'REPLACEMENT'} />
            </label>
          )}
          {fixed && <label className="check"><input type="checkbox" checked={form.permanentJob} onChange={(e) => set({ permanentJob: e.target.checked })} />{t('The job itself is permanent')}</label>}
          <label>{t('Place of work')}
            <select value={form.workSiteId} onChange={(e) => set({ workSiteId: e.target.value })}>
              <option value="">{view?.defaultWorkPlace ? t('The person\'s site ({a})', { a: view.defaultWorkPlace }) : t('Choose…')}</option>
              {sites.filter((s) => s.active || String(s.id) === form.workSiteId).map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
            </select>
            {!form.workSiteId && <OldValue value={view?.terms.workSiteId ? null : view?.terms.workPlace} />}
          </label>
          <label>{t('Supervisor')}<input value={form.supervisor} maxLength={150} placeholder={view?.defaultSupervisor ?? ''} onChange={(e) => set({ supervisor: e.target.value })} /></label>
          <label>{t('Medical fitness: certificate of')}<DateInput value={form.medicalFitOn} onChange={(e) => set({ medicalFitOn: e.target.value })} /></label>
          <label>{t('Doctor')}<input value={form.medicalDoctor} maxLength={150} onChange={(e) => set({ medicalDoctor: e.target.value })} /></label>
          <CityPicker country={form.signedCountry || null} cityId={form.signedPlaceId ? Number(form.signedPlaceId) : null} cityLabel={t('Signed at')}
            emptyLabel={view?.defaultSignedPlace ? t('The company\'s town ({a})', { a: view.defaultSignedPlace }) : t('Choose…')}
            onChange={(country, city) => set({ signedCountry: country ?? '', signedPlaceId: city ? String(city.id) : '' })} />
          {!form.signedPlaceId && view?.terms.signedPlace && !view.terms.signedPlaceId && <OldValue value={view.terms.signedPlace} />}
          <label>{t('Signed on (paper)')}<DateInput value={form.signedOn} onChange={(e) => set({ signedOn: e.target.value })} /></label>
          <label>{t('Paper draft handed over on')}<DateInput value={form.draftGivenOn} onChange={(e) => set({ draftGivenOn: e.target.value })} /></label>
          <label>{t('ONEM visa no.')}<input value={form.onemVisaNo} maxLength={40} onChange={(e) => set({ onemVisaNo: e.target.value })} /></label>
          <label>{t('ONEM visa date')}<DateInput value={form.onemVisaOn} onChange={(e) => set({ onemVisaOn: e.target.value })} /></label>
        </div>
        <p className="hint">{t('Empty fields take the value shown in grey or in the list. The draft is given to the employee at least two working days before signing (Labour Code art. 46); the signed contract goes to the ONEM visa within 15 days (art. 47).')}</p>
        <div className="modal-foot">
          <Button className="secondary" onClick={onClose}>{t('Close')}</Button>
          <Button className="secondary" type="submit">{t('Save')}</Button>
          <Button onClick={write}>{t('Write the contract')}</Button>
        </div>
      </Form>
    </Modal>
  )
}
