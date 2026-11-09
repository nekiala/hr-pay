import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api, DEPARTURE_REASONS, longDay, money, NOTICE_GIVERS, NOTICES, type Departure, type DepartureReason, type Notice, type NoticeGivenBy } from '../api'
import { useAuth } from '../auth'
import { Button, Form } from '../components/Button'
import { useAsk } from '../components/Dialogs'
import { ErrorText } from '../components/ErrorText'
import { t } from '../i18n'

type Draft = {
  lastDay: string; reason: DepartureReason; noticeDate: string; noticeGivenBy: NoticeGivenBy | ''; notice: Notice | ''; noticeDays: string; leaveDays: string
  severanceAmount: string; thirteenthAmount: string; note: string
}

const num = (v: string) => (v.trim() === '' ? null : Number(v.replace(',', '.')))

/** A departure and its final settlement: worked out from the rules, adjusted by HR, then confirmed into the last payroll. */
export default function DeparturePage() {
  const { id } = useParams()
  const { can } = useAuth()
  const ask = useAsk()
  const [saved, setSaved] = useState<Departure | null>(null)
  const [proposal, setProposal] = useState<Departure | null>(null)
  const [draft, setDraft] = useState<Draft>({ lastDay: '', reason: 'RESIGNATION', noticeDate: '', noticeGivenBy: '', notice: '', noticeDays: '', leaveDays: '', severanceAmount: '', thirteenthAmount: '', note: '' })
  const [error, setError] = useState<string | null>(null)
  const edit = can('payroll.run')

  const body = (d: Draft) => ({
    lastDay: d.lastDay, reason: d.reason, noticeDate: d.noticeDate || null, noticeGivenBy: d.noticeGivenBy || null, notice: d.notice || null, noticeDays: num(d.noticeDays), leaveDays: num(d.leaveDays),
    severanceAmount: num(d.severanceAmount), thirteenthAmount: num(d.thirteenthAmount), note: d.note || null,
  })

  const load = () => api<Departure | undefined>(`/api/users/${id}/departure`).then((d) => {
    setSaved(d ?? null)
    if (d) {
      setDraft({ lastDay: d.lastDay, reason: d.reason, noticeDate: d.noticeDate ?? '', noticeGivenBy: d.noticeGivenBy ?? '', notice: d.notice, noticeDays: String(d.noticeDays), leaveDays: String(d.leaveDays),
        severanceAmount: String(d.severanceAmount), thirteenthAmount: String(d.thirteenthAmount), note: d.note ?? '' })
    }
  }).catch((e: Error) => setError(e.message))
  useEffect(() => { void load() }, [id])

  // Work the settlement out again when a choice changes; the amounts HR typed are kept.
  const propose = async (d: Draft) => {
    if (!d.lastDay) return
    setError(null)
    try {
      setProposal(await api<Departure>(`/api/users/${id}/departure/propose`, { method: 'POST', body: body(d) }))
    } catch (e) {
      setError((e as Error).message)
    }
  }
  useEffect(() => { if (!saved || saved.status === 'DRAFT') void propose(draft) }, [draft.lastDay, draft.reason, draft.notice, draft.noticeDate, draft.noticeGivenBy])

  const set = (k: keyof Draft) => (e: { target: { value: string } }) => setDraft({ ...draft, [k]: e.target.value })
  // A new choice of reason or notice gives back the rules' amounts.
  const choose = (k: 'reason' | 'notice' | 'noticeGivenBy' | 'noticeDate') => (e: { target: { value: string } }) =>
    setDraft({ ...draft, [k]: e.target.value, ...(k !== 'notice' ? { notice: '' } : {}), ...(k === 'reason' ? { noticeGivenBy: '' } : {}),
      noticeDays: '', severanceAmount: '' })

  const save = async () => {
    setError(null)
    const d = await api<Departure>(`/api/users/${id}/departure`, { method: 'PUT', body: body(draft) })
    setSaved(d)
    setProposal(null)
  }

  const confirm = () => ask({
    title: t('Confirm the departure of {a}?', { a: saved?.name }),
    message: t('The settlement goes into the {a} payroll as pay entries, the leave paid out leaves the balance and the contract ends on {b}. Calculate that payroll again to see the last payslip.',
      { a: saved?.lastDay.slice(0, 7), b: longDay(saved?.lastDay ?? null) }),
    confirmLabel: t('Confirm the departure'),
    onConfirm: async () => { setSaved(await api<Departure>(`/api/users/${id}/departure/confirm`, { method: 'POST' })) },
  })

  const cancel = () => ask({
    title: t('Take back this departure?'), danger: true, confirmLabel: t('Take it back'),
    message: saved?.status === 'CONFIRMED' ? t('Its pay entries are removed, the leave returns to the balance and the contract runs on.') : undefined,
    onConfirm: async () => {
      await api(`/api/users/${id}/departure`, { method: 'DELETE' })
      setSaved(null)
      setDraft({ ...draft, notice: '', noticeGivenBy: '', noticeDate: '', noticeDays: '', leaveDays: '', severanceAmount: '', thirteenthAmount: '' })
    },
  })

  const shown = proposal ?? saved
  const confirmed = saved?.status === 'CONFIRMED'
  const cur = shown?.currency ?? 'USD'

  return (
    <section>
      <p><Link to={`/users/${id}`}>{t('← Back to the person')}</Link>{' · '}<Link to="/departures">{t('All departures')}</Link></p>
      <h2>{t('Departure and final settlement')}{shown ? ` · ${shown.name}` : ''}</h2>
      <ErrorText error={error} />
      {confirmed && (
        <div className="notice">
          {t('Confirmed by {a}. The settlement is in the {b} payroll.', { a: saved.confirmedBy, b: saved.lastDay.slice(0, 7) })}
          {' '}<Link to="/payroll">{t('Open the payroll')}</Link>
          {' · '}<a href={`/api/users/${id}/departure/certificate.pdf`} target="_blank" rel="noreferrer">{t('Certificate of employment')}</a>
          {saved.certificateNo && <span className="muted">{' '}({saved.certificateNo})</span>}
        </div>
      )}

      <Form className="card" onSubmit={save}>
        <div className="grid3">
          <label>{t('Last day of work')}<input type="date" value={draft.lastDay} onChange={set('lastDay')} required disabled={confirmed || !edit} /></label>
          <label>{t('Reason')}
            <select value={draft.reason} onChange={choose('reason')} disabled={confirmed || !edit}>
              {Object.entries(DEPARTURE_REASONS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
            </select>
          </label>
          <label>{t('Notice')}
            <select value={draft.notice || shown?.notice || ''} onChange={choose('notice')} disabled={confirmed || !edit}>
              {Object.entries(NOTICES).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
            </select>
          </label>
        </div>
        <div className="grid3">
          <label>{t('Notice given by')}
            <select value={draft.noticeGivenBy || shown?.noticeGivenBy || ''} onChange={choose('noticeGivenBy')} disabled={confirmed || !edit}>
              <option value="">{t('Nobody (no notice)')}</option>
              {Object.entries(NOTICE_GIVERS).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
            </select>
          </label>
          <label>{t('Notice given on')}<input type="date" value={draft.noticeDate} onChange={choose('noticeDate')} max={draft.lastDay || undefined} disabled={confirmed || !edit} /></label>
          <div className="field-note">
            {shown?.noticeEnd && <span>{t('Notice ends on {a}.', { a: longDay(shown.noticeEnd) })}{' '}
              {!confirmed && edit && shown.noticeEnd !== draft.lastDay && draft.noticeDate &&
                <button type="button" className="link" onClick={() => setDraft({ ...draft, lastDay: shown.noticeEnd!, notice: '', noticeDays: '' })}>{t('Make it the last day')}</button>}
            </span>}
          </div>
        </div>
        {shown && (
          <>
            <div className="table-wrap">
              <table>
                <thead><tr><th>{t('Item')}</th><th>{t('Days')}</th><th className="num">{t('Amount')}</th></tr></thead>
                <tbody>
                  <tr>
                    <td>{shown.notice === 'OWED' ? t('Notice owed by the employee (deducted)') : t('Notice pay (not worked)')}</td>
                    <td>{shown.notice === 'PAID' || shown.notice === 'OWED'
                      ? <input className="narrow-input" inputMode="decimal" value={draft.noticeDays || String(shown.noticeDays)} onChange={set('noticeDays')} onBlur={() => void propose(draft)} disabled={confirmed || !edit} />
                      : '—'}</td>
                    <td className="num">{shown.notice === 'OWED' ? `− ${money(shown.noticeOwed, cur)}` : money(shown.noticeAmount, cur)}</td>
                  </tr>
                  <tr>
                    <td>{t('Unused annual leave paid out')}</td>
                    <td><input className="narrow-input" inputMode="decimal" value={draft.leaveDays || String(shown.leaveDays)} onChange={set('leaveDays')} onBlur={() => void propose(draft)} disabled={confirmed || !edit} /></td>
                    <td className="num">{money(shown.leaveAmount, cur)}</td>
                  </tr>
                  <tr>
                    <td>{t('Severance pay')}</td><td />
                    <td className="num"><input className="narrow-input" inputMode="decimal" value={draft.severanceAmount || String(shown.severanceAmount)} onChange={set('severanceAmount')} onBlur={() => void propose(draft)} disabled={confirmed || !edit} /></td>
                  </tr>
                  {Number(shown.endOfCareerAmount) > 0 && <tr>
                    <td>{t('End-of-career payment (agreement)')}</td><td />
                    <td className="num">{money(shown.endOfCareerAmount, cur)}</td>
                  </tr>}
                  <tr>
                    <td>{t('13th month, share of the year')}</td><td />
                    <td className="num"><input className="narrow-input" inputMode="decimal" value={draft.thirteenthAmount || String(shown.thirteenthAmount)} onChange={set('thirteenthAmount')} onBlur={() => void propose(draft)} disabled={confirmed || !edit} /></td>
                  </tr>
                  <tr className="subtotal"><td colSpan={2}>{t('Settlement, before tax and CNSS')}</td><td className="num">{money(shown.total, cur)}</td></tr>
                </tbody>
              </table>
            </div>
            <p className="small muted">{t('Daily rate {a} · service {b} years · basic salary {c}', { a: money(shown.dailyRate, cur), b: shown.serviceYears, c: money(shown.basicMonthly, cur) })}</p>
            {shown.explanation.length > 0 && <ul className="small">{shown.explanation.map((x) => <li key={x} className={x.startsWith(t('Warning:')) ? 'warn-text' : undefined}>{x}</li>)}</ul>}
            <p className="hint">{t('The last payslip adds the days worked, the settlement and what is left of loans. Check the rules with your payroll expert.')}</p>
          </>
        )}
        <label>{t('Note (optional)')}<textarea value={draft.note} onChange={set('note')} maxLength={500} disabled={confirmed || !edit} /></label>
        {edit && (
          <div className="row">
            {!confirmed && <Button type="submit">{saved ? t('Save the changes') : t('Save as draft')}</Button>}
            {saved && !confirmed && <Button className="secondary" onClick={confirm}>{t('Confirm the departure')}</Button>}
            {saved && (saved.monthStatus === 'OPEN' || saved.monthStatus === 'CALCULATED') && <Button className="secondary danger" onClick={cancel}>{t('Take it back')}</Button>}
          </div>
        )}
      </Form>
    </section>
  )
}
