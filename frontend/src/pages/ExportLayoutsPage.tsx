import { useEffect, useState } from 'react'
import { api, download, LAYOUT_TARGETS, type ExportLayout, type LayoutColumn, type LayoutTarget } from '../api'
import { useAuth } from '../auth'
import { Button, Form } from '../components/Button'
import { useAsk } from '../components/Dialogs'
import { ErrorText } from '../components/ErrorText'
import { t } from '../i18n'

type Draft = Omit<ExportLayout, 'id' | 'createdBy' | 'createdAt'> & { id?: number }
const SEPARATORS: Record<string, string> = { ';': t('Semicolon ;'), ',': t('Comma ,'), '|': t('Bar |'), TAB: t('Tab') }

const col = (field: string, header: string): LayoutColumn => ({ field, header, value: null })
/** Starting points; every customer's software differs, so each is a draft to adjust and test with it. */
const STARTS: { name: string; target: LayoutTarget; draft: Partial<Draft> }[] = [
  { name: t('Journal with debit and credit columns'), target: 'ACCOUNTING', draft: { columns: [col('journal', 'Journal'), col('date', 'Date'), col('reference', 'Piece'), col('account', 'Compte'), col('label', 'Libelle'), col('debit', 'Debit'), col('credit', 'Credit')] } },
  { name: t('Journal with one signed amount'), target: 'ACCOUNTING', draft: { columns: [col('date', 'Date'), col('account', 'Account'), col('label', 'Label'), col('amount', 'Amount'), col('cost_centre', 'Analytic')], separator: ',', decimalComma: false, dateFormat: 'yyyy-MM-dd' } },
  { name: t('Bank transfers'), target: 'BANK', draft: { columns: [col('beneficiary', 'Beneficiaire'), col('account', 'Compte'), col('amount', 'Montant'), col('currency', 'Devise'), col('narration', 'Motif')] } },
  { name: t('Mobile money'), target: 'MOBILE_MONEY', draft: { columns: [col('account', 'MSISDN'), col('amount', 'Amount'), col('beneficiary', 'Name'), col('narration', 'Reference')], separator: ',', decimalComma: false } },
]
const blank = (target: LayoutTarget): Draft => ({
  target, name: '', columns: [], separator: ';', decimalComma: true, dateFormat: 'dd/MM/yyyy', headerRow: true, encoding: 'UTF-8', extension: 'csv',
})

/** How the payroll's files are laid out for the customer's accounting software, bank and mobile money operator. */
export default function ExportLayoutsPage() {
  const { can } = useAuth()
  const ask = useAsk()
  const [layouts, setLayouts] = useState<ExportLayout[]>([])
  const [fields, setFields] = useState<Record<string, Record<string, string>>>({})
  const [draft, setDraft] = useState<Draft | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [formError, setFormError] = useState<string | null>(null)

  const targets = (Object.keys(LAYOUT_TARGETS) as LayoutTarget[]).filter((k) => k === 'ACCOUNTING' ? can('accounting.export') : can('payroll.pay'))
  const load = () => api<ExportLayout[]>('/api/export-layouts').then(setLayouts).catch((e: Error) => setError(e.message))
  useEffect(() => {
    void load()
    api<Record<string, Record<string, string>>>('/api/export-layouts/fields').then(setFields).catch(() => undefined)
  }, [])

  const save = async () => {
    if (!draft) return
    setFormError(null)
    try {
      const { id, ...body } = draft
      await api(id ? `/api/export-layouts/${id}` : '/api/export-layouts', { method: id ? 'PUT' : 'POST', body })
      setDraft(null)
      await load()
    } catch (e) { setFormError((e as Error).message) }
  }
  const setCol = (i: number, c: Partial<LayoutColumn>) => draft && setDraft({ ...draft, columns: draft.columns.map((x, j) => j === i ? { ...x, ...c } : x) })
  const move = (i: number, by: number) => {
    if (!draft) return
    const cols = [...draft.columns]
    const [c] = cols.splice(i, 1)
    cols.splice(i + by, 0, c)
    setDraft({ ...draft, columns: cols })
  }

  if (draft) {
    const available = Object.entries(fields[draft.target] ?? {})
    return (
      <section>
        <h2>{draft.id ? t('Edit the layout') : t('New layout')}</h2>
        <Form onSubmit={save} className="card">
          <label>{t('Name')}<input required maxLength={100} value={draft.name} onChange={(e) => setDraft({ ...draft, name: e.target.value })} /></label>
          <label>{t('File')}
            <select value={draft.target} onChange={(e) => setDraft({ ...draft, target: e.target.value as LayoutTarget, columns: [] })}>
              {targets.map((k) => <option key={k} value={k}>{LAYOUT_TARGETS[k]}</option>)}
            </select>
          </label>
          <fieldset><legend>{t('Columns, in order')}</legend>
            {draft.columns.length === 0 && <p className="muted small">{t('Add the columns the software expects, in its order.')}</p>}
            {draft.columns.map((c, i) => (
              <div key={i} className="row">
                <select value={c.field} aria-label={t('Field')} onChange={(e) => setCol(i, { field: e.target.value })}>
                  {available.map(([k, label]) => <option key={k} value={k}>{label}</option>)}
                </select>
                <input value={c.header} aria-label={t('Header')} placeholder={t('Header')} maxLength={60} onChange={(e) => setCol(i, { header: e.target.value })} />
                {c.field === 'text' && <input value={c.value ?? ''} aria-label={t('Text')} placeholder={t('Text')} maxLength={60} onChange={(e) => setCol(i, { value: e.target.value })} />}
                <Button className="secondary" disabled={i === 0} aria-label={t('Up')} onClick={() => move(i, -1)}>↑</Button>
                <Button className="secondary" disabled={i === draft.columns.length - 1} aria-label={t('Down')} onClick={() => move(i, 1)}>↓</Button>
                <Button className="secondary" aria-label={t('Remove')} onClick={() => setDraft({ ...draft, columns: draft.columns.filter((_, j) => j !== i) })}>×</Button>
              </div>
            ))}
            <Button className="secondary" onClick={() => setDraft({ ...draft, columns: [...draft.columns, col(available[0]?.[0] ?? 'text', '')] })}>{t('Add a column')}</Button>
          </fieldset>
          <div className="row">
            <label>{t('Separator')}
              <select value={draft.separator === '\t' ? 'TAB' : draft.separator} onChange={(e) => setDraft({ ...draft, separator: e.target.value })}>
                {Object.entries(SEPARATORS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
              </select>
            </label>
            <label>{t('Date format')}<input required maxLength={20} value={draft.dateFormat} onChange={(e) => setDraft({ ...draft, dateFormat: e.target.value })} /></label>
            <label>{t('Characters')}
              <select value={draft.encoding} onChange={(e) => setDraft({ ...draft, encoding: e.target.value as Draft['encoding'] })}>
                <option value="UTF-8">UTF-8</option><option value="WINDOWS-1252">Windows-1252 (ANSI)</option>
              </select>
            </label>
            <label>{t('Extension')}
              <select value={draft.extension} onChange={(e) => setDraft({ ...draft, extension: e.target.value as Draft['extension'] })}>
                <option value="csv">.csv</option><option value="txt">.txt</option>
              </select>
            </label>
          </div>
          <label className="check"><input type="checkbox" checked={draft.decimalComma} onChange={(e) => setDraft({ ...draft, decimalComma: e.target.checked })} />{t('Decimal comma (1234,50 rather than 1234.50)')}</label>
          <label className="check"><input type="checkbox" checked={draft.headerRow} onChange={(e) => setDraft({ ...draft, headerRow: e.target.checked })} />{t('First line holds the headers')}</label>
          <p className="small muted">{t('Dates use dd for the day, MM for the month and yyyy for the year, for example dd/MM/yyyy or yyyyMMdd.')}</p>
          <ErrorText error={formError} />
          <div className="row">
            <Button type="submit" disabled={!draft.columns.length}>{t('Save')}</Button>
            <Button className="secondary" onClick={() => setDraft(null)}>{t('Cancel')}</Button>
          </div>
        </Form>
      </section>
    )
  }

  return (
    <section>
      <h2>{t('File layouts')}</h2>
      <p className="muted">{t('Shape the files the way your accounting software, bank or operator expects, then pick the layout when you download.')}</p>
      <ErrorText error={error} />
      <div className="card">
        <div className="row between">
          <h3>{t('Layouts')}</h3>
          <div className="row">
            {targets.length > 0 && <Button onClick={() => { setFormError(null); setDraft(blank(targets[0])) }}>{t('New layout')}</Button>}
          </div>
        </div>
        <div className="table-wrap">
          <table>
            <thead><tr><th>{t('Name')}</th><th>{t('File')}</th><th>{t('Columns')}</th><th /></tr></thead>
            <tbody>
              {layouts.length === 0 && <tr><td colSpan={4} className="muted">{t('No layout yet. Start from one below.')}</td></tr>}
              {layouts.map((l) => (
                <tr key={l.id}>
                  <td>{l.name}</td><td>{LAYOUT_TARGETS[l.target]}</td>
                  <td className="small">{l.columns.map((c) => c.header || c.field).join(l.separator === '\t' ? ' ⇥ ' : ` ${l.separator} `)}</td>
                  <td>
                    <div className="row">
                      <Button className="secondary" onClick={() => { setFormError(null); setDraft({ ...l }) }}>{t('Edit')}</Button>
                      <Button className="secondary" onClick={() => ask({
                        title: t('Delete the layout {a}?', { a: l.name }), confirmLabel: t('Delete'), danger: true,
                        onConfirm: async () => { await api(`/api/export-layouts/${l.id}`, { method: 'DELETE' }); await load() },
                      })}>{t('Delete')}</Button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
      <div className="card">
        <h3>{t('Start from')}</h3>
        <p className="small muted">{t('Starting points only: check the order of the columns, the separator and the formats with the software that reads the file, then try one file before using it for real.')}</p>
        <div className="row">
          {STARTS.filter((s) => targets.includes(s.target)).map((s) => (
            <Button key={s.name} className="secondary" onClick={() => { setFormError(null); setDraft({ ...blank(s.target), name: s.name, ...s.draft }) }}>{s.name}</Button>
          ))}
        </div>
      </div>
    </section>
  )
}

/** Download a file with one of the company's layouts; shows nothing while there is none for this kind of file. */
export function LayoutDownload({ target, path }: { target: LayoutTarget; path: (layoutId: number) => string }) {
  const [layouts, setLayouts] = useState<ExportLayout[]>([])
  const [error, setError] = useState<string | null>(null)
  useEffect(() => { api<ExportLayout[]>(`/api/export-layouts?target=${target}`).then(setLayouts).catch(() => undefined) }, [target])
  if (layouts.length === 0) return null
  return (
    <>
      {layouts.map((l) => (
        <Button key={l.id} className="secondary" onClick={async () => {
          setError(null)
          try {
            await download(path(l.id), `${l.name}.${l.extension}`)
          } catch (e) { setError((e as Error).message) }
        }}>{t('Download for {a}', { a: l.name })}</Button>
      ))}
      <ErrorText error={error} />
    </>
  )
}
