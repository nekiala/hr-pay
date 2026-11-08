import { useEffect, useState } from 'react'
import { api, download, fileToBase64, IMPORT_KINDS, longDay, type ImportBatch, type ImportKind, type ImportKindView, type ImportResult } from '../api'
import { Button } from '../components/Button'
import { ErrorText } from '../components/ErrorText'
import { t } from '../i18n'

const ACTIONS: Record<string, string> = { CREATE: t('New'), UPDATE: t('Update'), ADD: t('Add'), REPLACE: t('Replace') }
const path = (k: ImportKind) => k.toLowerCase().replace('_', '-')

/**
 * Bringing existing data in from a spreadsheet: download the template, fill it in Excel, then check it.
 * Nothing is saved until every row is right; the import then saves the whole file at once.
 */
export default function ImportPage() {
  const [kinds, setKinds] = useState<ImportKindView[]>([])
  const [kind, setKind] = useState<ImportKind>('EMPLOYEES')
  const [file, setFile] = useState<File | null>(null)
  const [result, setResult] = useState<ImportResult | null>(null)
  const [history, setHistory] = useState<ImportBatch[]>([])
  const [error, setError] = useState<string | null>(null)

  const loadHistory = () => api<ImportBatch[]>('/api/imports/history').then(setHistory).catch(() => undefined)
  useEffect(() => {
    api<ImportKindView[]>('/api/imports/kinds').then(setKinds).catch((e: Error) => setError(e.message))
    void loadHistory()
  }, [])

  const columns = kinds.find((k) => k.kind === kind)?.columns ?? []
  const choose = (k: ImportKind) => { setKind(k); setResult(null); setError(null) }

  const send = async (apply: boolean) => {
    if (!file) return
    setError(null)
    try {
      const body = { fileName: file.name, content: await fileToBase64(file) }
      const r = await api<ImportResult>(`/api/imports/${path(kind)}${apply ? '' : '/check'}`, { method: 'POST', body })
      setResult(r)
      if (r.saved) { setFile(null); void loadHistory() }
    } catch (e) {
      setError((e as Error).message)
    }
  }

  const ready = result !== null && !result.saved && result.withErrors === 0 && result.problems.length === 0 && result.rows > 0
  return (
    <section>
      <h2>{t('Import data')}</h2>
      <p className="muted">{t('Download the template, fill one row per person, then check the file. Nothing is saved until every row is right.')}</p>
      <div className="row" role="group" aria-label={t('What to import')}>
        {(Object.keys(IMPORT_KINDS) as ImportKind[]).map((k) => (
          <Button key={k} aria-pressed={k === kind} className={k === kind ? undefined : 'secondary'} onClick={() => choose(k)}>{IMPORT_KINDS[k]}</Button>
        ))}
      </div>

      <div className="card">
        <h3>{t('1. The template')}</h3>
        <div className="row">
          <Button className="secondary" onClick={() => download(`/api/imports/${path(kind)}/template.xlsx`, `hr-pay-${path(kind)}.xlsx`).catch((e: Error) => setError(e.message))}>{t('Download Excel')}</Button>
          <Button className="secondary" onClick={() => download(`/api/imports/${path(kind)}/template.csv`, `hr-pay-${path(kind)}.csv`).catch((e: Error) => setError(e.message))}>{t('Download CSV')}</Button>
        </div>
        <p className="small muted">{t('Headers in French or English are both understood. Columns marked * are required; an empty cell leaves what is already saved.')}</p>
        <div className="table-wrap">
          <table>
            <thead><tr><th>{t('Column')}</th><th>{t('What to write')}</th></tr></thead>
            <tbody>
              {columns.map((c) => (
                <tr key={c.key}><td>{c.header}{c.required && ' *'}</td><td className="small">{c.help ?? ''}</td></tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      <div className="card">
        <h3>{t('2. Check and import')}</h3>
        <div className="row">
          <input type="file" accept=".xlsx,.csv,.txt" onChange={(e) => { setFile(e.target.files?.[0] ?? null); setResult(null) }} />
          <Button className="secondary" disabled={!file} onClick={() => send(false)} pendingText={t('Checking…')}>{t('Check the file')}</Button>
          <Button disabled={!file || !ready} onClick={() => send(true)} pendingText={t('Importing…')}>{t('Import')}</Button>
        </div>
        <ErrorText error={error} />
        {result && (
          <>
            {result.problems.map((p) => <p key={p} className="error">{p}</p>)}
            {result.saved
              ? <p className="notice">{t('Imported: {a} rows saved.', { a: result.rows })}</p>
              : result.withErrors > 0
                ? <p className="error">{t('{a} of {b} rows need a correction. Correct them in the file and check it again; nothing was saved.', { a: result.withErrors, b: result.rows })}</p>
                : result.rows > 0 && result.problems.length === 0 && <p className="notice">{t('Every row is right ({a} rows). Import to save them.', { a: result.rows })}</p>}
            {result.results.length > 0 && (
              <div className="table-wrap">
                <table>
                  <thead><tr><th>{t('Row')}</th><th>{t('Reg. no')}</th><th>{t('Name')}</th><th>{t('Action')}</th><th>{t('Remarks')}</th></tr></thead>
                  <tbody>
                    {result.results.map((r) => (
                      <tr key={r.row}>
                        <td>{r.row}</td><td>{r.registrationNo ?? '—'}</td><td>{r.name ?? ''}</td>
                        <td>{r.errors.length ? <span className="badge off">{t('Error')}</span> : r.action ? ACTIONS[r.action] ?? r.action : ''}</td>
                        <td className="small">
                          {r.errors.map((e) => <div key={e} className="error">{e}</div>)}
                          {r.warnings.map((w) => <div key={w} className="muted">{w}</div>)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </>
        )}
      </div>

      {history.length > 0 && (
        <div className="card">
          <h3>{t('Earlier imports')}</h3>
          <div className="table-wrap">
            <table>
              <thead><tr><th>{t('Date')}</th><th>{t('Kind')}</th><th>{t('File')}</th><th className="num">{t('Rows')}</th><th>{t('By')}</th></tr></thead>
              <tbody>
                {history.map((h) => (
                  <tr key={h.id}><td>{longDay(h.createdAt)}</td><td>{IMPORT_KINDS[h.kind] ?? h.kind}</td><td>{h.fileName ?? ''}</td><td className="num">{h.rows}</td><td>{h.createdBy}</td></tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </section>
  )
}
