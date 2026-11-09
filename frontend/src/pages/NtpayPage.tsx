import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { ApiError, api } from '../api'
import { useAuth } from '../auth'
import { Button, Form } from '../components/Button'
import { useAsk } from '../components/Dialogs'
import { ErrorText } from '../components/ErrorText'
import { t } from '../i18n'

type Settings = {
  configured: boolean; enabled: boolean; testMode: boolean; hasSignatureKey: boolean; notificationUrl: string | null
  checkedAt: string | null; checkResult: string | null; updatedBy: string | null; updatedAt: string | null
}
export type NtpayBatch = {
  id: number; method: string; currency: string; status: string; ntpayBatchCode: string | null; ntpayStatus: string | null
  ntpayFinished: boolean; ntpayTest: boolean | null; ntpayScheduledFor: string | null; ntpaySentAt: string | null; ntpayError: string | null
}

const fail = (e: unknown) => e instanceof ApiError ? [e.message, ...Object.values(e.fieldErrors)].join(' ') : t('Something went wrong.')
const when = (s: string | null) => s ? new Date(s).toLocaleString() : ''

export const NTPAY_STATUS: Record<string, [string, string]> = {
  pending_approval: [t('Waiting for approval in NTPay'), 'warn'],
  scheduled: [t('Approved, scheduled'), 'info'],
  approved: [t('Being paid'), 'info'],
  rejected: [t('Rejected in NTPay'), 'danger'],
  failed: [t('Not paid by NTPay'), 'danger'],
  cancelled: [t('NTPay batch cancelled'), ''],
  REFUSED: [t('Refused by NTPay'), 'danger'],
  UNKNOWN: [t('No answer from NTPay'), 'warn'],
}
export const LINE_STATUS: Record<string, [string, string]> = {
  waiting: [t('Waiting'), ''], pending: [t('Sending'), 'info'], paid: [t('Paid'), 'ok'], failed: [t('Failed'), 'danger'],
}

/** The company's NTPay integration: keys (never shown again), test or live, and the notification address. */
export function NtpaySettingsPage() {
  const { can } = useAuth()
  const [s, setS] = useState<Settings | null>(null)
  const [form, setForm] = useState({ enabled: false, testMode: true, apiKey: '', signatureKey: '' })
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const edit = can('company.manage')
  const show = (x: Settings) => { setS(x); setForm({ enabled: x.enabled, testMode: x.testMode, apiKey: '', signatureKey: '' }) }
  useEffect(() => { api<Settings>('/api/ntpay/settings').then(show).catch((e) => setError(fail(e))) }, [])
  if (!s) return <section><h2>{t('Mobile money with NTPay')}</h2><ErrorText error={error} /></section>
  return (
    <section>
      <div className="row space">
        <h2>{t('Mobile money with NTPay')}</h2>
        <Link className="button secondary" to="/payroll/payments">{t('Payments')}</Link>
      </div>
      <p className="muted">{t('Send mobile money payment files to NTPay as one payment batch: NTPay pays each employee from your NTPay balance after your approval circuit and its own check. HR Pay never holds the money. DR Congo only: Orange Money, M-Pesa and Afrimoney, in CDF or USD.')}</p>
      <div className="card">
        {s.configured
          ? <p>{s.enabled ? <span className="badge ok">{t('On')}</span> : <span className="badge">{t('Off')}</span>}{' '}
              {s.testMode ? <span className="badge warn">{t('Test mode: no money moves')}</span> : <span className="badge info">{t('Live')}</span>}</p>
          : <p className="muted">{t('Not set up yet.')}</p>}
        {s.checkResult && <p className="small">{s.checkResult} <span className="muted">({when(s.checkedAt)})</span></p>}
        {edit && <Form onSubmit={async () => {
          setError(null)
          setMessage(null)
          try {
            show(await api<Settings>('/api/ntpay/settings', { method: 'PUT', body: form }))
            setMessage(t('Saved.'))
          } catch (e) { setError(fail(e)) }
        }}>
          <div className="grid2">
            <label>{t('API key')}<input type="password" autoComplete="off" placeholder={s.configured ? t('Saved; type a new one to change it') : ''} value={form.apiKey} onChange={(e) => setForm({ ...form, apiKey: e.target.value })} />
              <span className="hint">{t('In your NTPay merchant space, Integrations. Use the test key first.')}</span></label>
            <label>{t('Signature key')}<input type="password" autoComplete="off" placeholder={s.hasSignatureKey ? t('Saved; type a new one to change it') : ''} value={form.signatureKey} onChange={(e) => setForm({ ...form, signatureKey: e.target.value })} />
              <span className="hint">{t('Checks that payment results really come from NTPay. Without it, results only arrive by the regular check.')}</span></label>
            <label className="check"><input type="checkbox" checked={form.enabled} onChange={(e) => setForm({ ...form, enabled: e.target.checked })} />{t('Send mobile money files with NTPay')}</label>
            <label className="check"><input type="checkbox" checked={form.testMode} onChange={(e) => setForm({ ...form, testMode: e.target.checked })} />{t('The key is a test key')}</label>
          </div>
          <ErrorText error={error} />
          {message && <p className="success">{message}</p>}
          <div className="row">
            <Button type="submit">{t('Save')}</Button>
            {s.configured && <Button className="secondary" onClick={async () => {
              setError(null)
              try { show(await api<Settings>('/api/ntpay/settings/test', { method: 'POST' })) } catch (e) { setError(fail(e)) }
            }}>{t('Test the connection')}</Button>}
          </div>
        </Form>}
        {s.notificationUrl && <p className="small">{t('Notification address (HR Pay gives it to NTPay with each batch):')} <code>{s.notificationUrl}</code></p>}
        <p className="hint">{t('Live payment batches must be enabled by NTPay for your integration, and an approval circuit must be set in your NTPay merchant space.')}</p>
      </div>
    </section>
  )
}

/** On a mobile money payment file: send it with NTPay, follow it, cancel it while NTPay has not paid. */
export function NtpayPanel({ batch, onChange }: { batch: NtpayBatch; onChange: () => Promise<void> | void }) {
  const { can } = useAuth()
  const ask = useAsk()
  const [settings, setSettings] = useState<Settings | null>(null)
  const [at, setAt] = useState('')
  const [error, setError] = useState<string | null>(null)
  useEffect(() => { api<Settings>('/api/ntpay/settings').then(setSettings).catch(() => setSettings(null)) }, [])
  if (batch.method !== 'MOBILE_MONEY' || !can('payroll.pay')) return null
  if (!batch.ntpayStatus && (batch.status !== 'PREPARED' || !settings?.enabled)) {
    return batch.status === 'PREPARED' && settings && !settings.enabled
      ? <p className="hint no-print">{t('Pay this file in one go with NTPay:')} <Link to="/payroll/payments/ntpay">{t('set it up')}</Link></p> : null
  }
  const status = batch.ntpayStatus ? NTPAY_STATUS[batch.ntpayStatus] ?? [batch.ntpayStatus, ''] : null
  const live = !!batch.ntpayStatus && !batch.ntpayFinished && ['pending_approval', 'scheduled', 'approved', 'UNKNOWN'].includes(batch.ntpayStatus)
  const canSend = batch.status === 'PREPARED' && settings?.enabled && (!live || batch.ntpayStatus === 'UNKNOWN')
  const run = async (work: () => Promise<unknown>) => {
    setError(null)
    try { await work(); await onChange() } catch (e) { setError(fail(e)) }
  }
  return (
    <div className="card no-print">
      <h3>{t('NTPay')}</h3>
      {status && <p><span className={`badge ${status[1]}`}>{status[0]}</span>{' '}
        {batch.ntpayBatchCode && <span className="small">{t('Batch {a}', { a: batch.ntpayBatchCode })}</span>}
        {batch.ntpayTest && <> <span className="badge warn">{t('Test')}</span></>}
        {batch.ntpayScheduledFor && <span className="small muted"> · {t('scheduled for {a}', { a: when(batch.ntpayScheduledFor) })}</span>}
        {batch.ntpaySentAt && <span className="small muted"> · {t('sent {a}', { a: when(batch.ntpaySentAt) })}</span>}</p>}
      {batch.ntpayError && <p className="notice">{batch.ntpayError}</p>}
      {batch.ntpayStatus === 'pending_approval' && <p className="hint">{t('Approve the batch in your NTPay merchant space (Circuits d’approbation). Nothing is paid before.')}</p>}
      <ErrorText error={error} />
      <div className="row">
        {canSend && <>
          <label className="small">{t('Pay on (optional)')}<input type="datetime-local" value={at} onChange={(e) => setAt(e.target.value)} /></label>
          <Button onClick={() => ask({
            title: t('Send this file with NTPay?'), confirmLabel: t('Send'),
            message: t('NTPay pays each line from your NTPay balance once the batch is approved. A line that fails comes back to the payments to pay, after you correct the number.'),
            onConfirm: () => run(() => api(`/api/payment-batches/${batch.id}/ntpay`, { method: 'POST', body: { scheduledFor: at ? new Date(at).toISOString() : null } })),
          })}>{batch.ntpayStatus === 'UNKNOWN' ? t('Send again') : t('Send with NTPay')}</Button>
        </>}
        {live && batch.ntpayBatchCode && <Button className="secondary" onClick={() => run(() => api(`/api/payment-batches/${batch.id}/ntpay/refresh`, { method: 'POST' }))}>{t('Check now')}</Button>}
        {live && (batch.ntpayStatus === 'pending_approval' || batch.ntpayStatus === 'scheduled' || (batch.ntpayStatus === 'UNKNOWN' && !batch.ntpayBatchCode)) &&
          <Button className="secondary" onClick={() => ask({ title: t('Cancel the NTPay batch?'), confirmLabel: t('Cancel the batch'), danger: true,
            message: t('Nothing has been paid yet. The file stays, to send again or pay another way.'),
            onConfirm: () => run(() => api(`/api/payment-batches/${batch.id}/ntpay/cancel`, { method: 'POST' })) })}>{t('Cancel the batch')}</Button>}
      </div>
    </div>
  )
}
