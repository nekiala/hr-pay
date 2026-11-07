import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api, CORRECTION_FIELDS, longDay, type DataCorrection } from '../api'
import { Button } from '../components/Button'
import { useAsk } from '../components/Dialogs'
import { ErrorText } from '../components/ErrorText'
import { t } from '../i18n'

type Due = { userId: number; name: string; registrationNo: string; leftOn: string; eraseFrom: string }
type Retention = { eraseAfterYears: number; due: Due[] }

/** HR's side of personal data rights: correction requests, and erasing former employees after the retention period. */
export default function PersonalDataPage() {
  const ask = useAsk()
  const [status, setStatus] = useState<'OPEN' | ''>('OPEN')
  const [requests, setRequests] = useState<DataCorrection[]>([])
  const [retention, setRetention] = useState<Retention | null>(null)
  const [years, setYears] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)

  const loadRequests = () => api<DataCorrection[]>(`/api/privacy/corrections${status ? `?status=${status}` : ''}`)
    .then(setRequests).catch((e: Error) => setError(e.message))
  const loadRetention = () => api<Retention>('/api/privacy/retention')
    .then((r) => { setRetention(r); setYears(String(r.eraseAfterYears)) }).catch((e: Error) => setError(e.message))
  useEffect(() => { void loadRequests() }, [status])
  useEffect(() => { void loadRetention() }, [])

  const decide = (r: DataCorrection, done: boolean) => ask({
    title: done ? t('Mark the correction for {a} as done?', { a: r.name }) : t('Refuse the correction for {a}?', { a: r.name }),
    message: done ? t('Make the change in the person’s file first: this only records the answer.') : t('The person sees your reason.'),
    confirmLabel: done ? t('Mark as done') : t('Refuse'), danger: !done,
    fields: [{ label: done ? t('Note for the person (optional)') : t('Reason'), required: !done, multiline: true }],
    onConfirm: async ([answer]) => {
      await api(`/api/privacy/corrections/${r.id}/${done ? 'done' : 'refuse'}`, { method: 'POST', body: { answer } })
      await loadRequests()
    },
  })

  const saveYears = async () => {
    setError(null)
    setSaved(false)
    try {
      const r = await api<Retention>('/api/privacy/retention', { method: 'PUT', body: { eraseAfterYears: Number(years) } })
      setRetention(r)
      setSaved(true)
    } catch (e) {
      setError((e as Error).message)
    }
  }

  const erase = (d: Due) => ask({
    title: t('Erase {a}\'s personal data?', { a: d.name }), danger: true, confirmLabel: t('Erase for good'),
    message: <>{t('Name, contact details, employee file, dependants, GPS positions and the identifiers on payslips are removed. Payroll amounts, the registration number (')}{d.registrationNo}{t(') and the audit log stay.')}{' '}<strong>{t('This cannot be undone.')}</strong></>,
    fields: [{ label: t('Type {a} to confirm', { a: d.registrationNo }), required: true, pattern: d.registrationNo.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') }],
    onConfirm: async () => {
      setRetention(await api<Retention>(`/api/privacy/erase/${d.userId}`, { method: 'POST' }))
    },
  })

  return (
    <section>
      <h2>{t('Personal data')}</h2>
      <ErrorText error={error} />
      <div className="card">
        <div className="row between">
          <h3>{t('Correction requests')}</h3>
          <div className="segmented" role="group" aria-label={t('Which requests')}>
            <button type="button" className={status === 'OPEN' ? 'on' : ''} onClick={() => setStatus('OPEN')}>{t('Waiting')}</button>
            <button type="button" className={status === '' ? 'on' : ''} onClick={() => setStatus('')}>{t('All')}</button>
          </div>
        </div>
        {requests.length === 0 ? <p className="muted">{status === 'OPEN' ? t('No request is waiting.') : t('No request yet.')}</p> : (
          <table>
            <thead><tr><th>{t('Asked')}</th><th>{t('Person')}</th><th>{t('What')}</th><th>{t('Shown now')}</th><th>{t('Correct value')}</th><th>{t('Why')}</th><th /></tr></thead>
            <tbody>
              {requests.map((r) => (
                <tr key={r.id}>
                  <td>{longDay(r.createdAt.slice(0, 10))}</td>
                  <td><Link to={`/users/${r.userId}`}>{r.name}</Link></td>
                  <td>{CORRECTION_FIELDS[r.field] ?? r.field}</td>
                  <td>{r.current ?? '—'}</td>
                  <td><strong>{r.requested}</strong></td>
                  <td>{r.reason ?? ''}</td>
                  <td>
                    {r.status === 'OPEN' ? (
                      <div className="row">
                        <Button onClick={() => decide(r, true)}>{t('Done')}</Button>
                        <Button className="secondary" onClick={() => decide(r, false)}>{t('Refuse')}</Button>
                      </div>
                    ) : (
                      <span className="muted small">{r.status === 'DONE' ? t('Done') : t('Refused')}{' '}{t('by')}{' '}{r.decidedBy}{r.answer ? `: ${r.answer}` : ''}</span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      <div className="card">
        <h3>{t('Keeping former employees\' data')}</h3>
        <p className="muted">{t('After someone leaves (account disabled, last contract ended), their personal data is kept for this long, then HR erases it here. Check the period with your lawyer.')}</p>
        <div className="row">
          <label>{t('Years after leaving')}<input className="narrow-input" type="number" min={1} max={50} value={years} onChange={(e) => setYears(e.target.value)} />
          </label>
          <Button className="secondary" onClick={saveYears}>{t('Save')}</Button>
        </div>
        {saved && <p className="success">{t('Saved.')}</p>}
        {retention && (retention.due.length === 0
          ? <p className="muted">{t('Nobody is due for erasure.')}</p>
          : (
            <table>
              <thead><tr><th>{t('Person')}</th><th>{t('Registration no.')}</th><th>{t('Left on')}</th><th>{t('Can be erased since')}</th><th /></tr></thead>
              <tbody>
                {retention.due.map((d) => (
                  <tr key={d.userId}>
                    <td>{d.name}</td><td>{d.registrationNo}</td><td>{longDay(d.leftOn)}</td><td>{longDay(d.eraseFrom)}</td>
                    <td><Button className="danger" onClick={() => erase(d)}>{t('Erase')}</Button></td>
                  </tr>
                ))}
              </tbody>
            </table>
          ))}
      </div>
    </section>
  )
}
