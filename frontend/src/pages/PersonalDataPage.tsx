import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api, CORRECTION_FIELDS, longDay, type DataCorrection } from '../api'
import { Button } from '../components/Button'
import { useAsk } from '../components/Dialogs'
import { ErrorText } from '../components/ErrorText'

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
    title: done ? `Mark the correction for ${r.name} as done?` : `Refuse the correction for ${r.name}?`,
    message: done ? 'Make the change in the person’s file first: this only records the answer.' : 'The person sees your reason.',
    confirmLabel: done ? 'Mark as done' : 'Refuse', danger: !done,
    fields: [{ label: done ? 'Note for the person (optional)' : 'Reason', required: !done, multiline: true }],
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
    title: `Erase ${d.name}'s personal data?`, danger: true, confirmLabel: 'Erase for good',
    message: <>Name, contact details, employee file, dependants, GPS positions and the identifiers on payslips are removed. Payroll
      amounts, the registration number ({d.registrationNo}) and the audit log stay. <strong>This cannot be undone.</strong></>,
    fields: [{ label: `Type ${d.registrationNo} to confirm`, required: true, pattern: d.registrationNo.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') }],
    onConfirm: async () => {
      setRetention(await api<Retention>(`/api/privacy/erase/${d.userId}`, { method: 'POST' }))
    },
  })

  return (
    <section>
      <h2>Personal data</h2>
      <ErrorText error={error} />
      <div className="card">
        <div className="row between">
          <h3>Correction requests</h3>
          <div className="segmented" role="group" aria-label="Which requests">
            <button type="button" className={status === 'OPEN' ? 'on' : ''} onClick={() => setStatus('OPEN')}>Waiting</button>
            <button type="button" className={status === '' ? 'on' : ''} onClick={() => setStatus('')}>All</button>
          </div>
        </div>
        {requests.length === 0 ? <p className="muted">{status === 'OPEN' ? 'No request is waiting.' : 'No request yet.'}</p> : (
          <table>
            <thead><tr><th>Asked</th><th>Person</th><th>What</th><th>Shown now</th><th>Correct value</th><th>Why</th><th /></tr></thead>
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
                        <Button onClick={() => decide(r, true)}>Done</Button>
                        <Button className="secondary" onClick={() => decide(r, false)}>Refuse</Button>
                      </div>
                    ) : (
                      <span className="muted small">{r.status === 'DONE' ? 'Done' : 'Refused'} by {r.decidedBy}{r.answer ? `: ${r.answer}` : ''}</span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      <div className="card">
        <h3>Keeping former employees' data</h3>
        <p className="muted">After someone leaves (account disabled, last contract ended), their personal data is kept for this long,
          then HR erases it here. Check the period with your lawyer.</p>
        <div className="row">
          <label>Years after leaving
            <input className="narrow-input" type="number" min={1} max={50} value={years} onChange={(e) => setYears(e.target.value)} />
          </label>
          <Button className="secondary" onClick={saveYears}>Save</Button>
        </div>
        {saved && <p className="success">Saved.</p>}
        {retention && (retention.due.length === 0
          ? <p className="muted">Nobody is due for erasure.</p>
          : (
            <table>
              <thead><tr><th>Person</th><th>Registration no.</th><th>Left on</th><th>Can be erased since</th><th /></tr></thead>
              <tbody>
                {retention.due.map((d) => (
                  <tr key={d.userId}>
                    <td>{d.name}</td><td>{d.registrationNo}</td><td>{longDay(d.leftOn)}</td><td>{longDay(d.eraseFrom)}</td>
                    <td><Button className="danger" onClick={() => erase(d)}>Erase</Button></td>
                  </tr>
                ))}
              </tbody>
            </table>
          ))}
      </div>
    </section>
  )
}
