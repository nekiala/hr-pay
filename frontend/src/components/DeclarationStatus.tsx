import { useState } from 'react'
import { money } from '../api'
import { Button, Form } from './Button'
import { Modal } from './Modal'
import { t } from '../i18n'
import { DateInput } from './DateFields'

export type Status = 'TO_PREPARE' | 'READY' | 'DECLARED' | 'PAID' | 'NIL'
export type Filed = { filedOn: string; reference: string | null; note: string | null; filedBy: string; filedAt: string }
export type Paid = { paidOn: string; amount: number; reference: string | null; mode: string | null; paidBy: string; paidAt: string }
export type CalendarItem = {
  month: string; kind: string; authority: string; declareDue: string; payDue: string; quarterly: boolean; status: Status
  late: boolean; nextDue: string; daysLeft: number; amount: number | null; filed: Filed | null; paid: Paid | null
  penalty: { lateDeclaration: number; latePayment: number; total: number; how: string[] } | null
  annual: string | null; legalBasis: string | null; verified: boolean
}

export const STATUS_BADGE: Record<Status, string> = { TO_PREPARE: 'warn', READY: 'info', DECLARED: 'info', PAID: 'ok', NIL: '' }

export const statusLabel = (i: CalendarItem) => {
  const base = {
    TO_PREPARE: t('To prepare'), READY: t('Ready to declare'), DECLARED: t('Declared, to pay'), PAID: t('Paid'), NIL: t('Nobody concerned'),
  }[i.status]
  return i.late && i.status !== 'PAID' && i.status !== 'NIL' ? t('{a}, late', { a: base }) : base
}

export const PAY_MODES = [['OV', t('Bank transfer (OV)')], ['OP', t('Payment order (OP)')], ['CHEQUE', t('Cheque')], ['CASH', t('Cash')]] as const

type Body = { paidOn: string; amount: number; reference: string | null; mode: string }

/** Records how a declared amount was paid: the date, the amount, the receipt or bank reference and the method. */
export function PayDeclarationModal({ item, currency, onClose, onSave }: { item: CalendarItem; currency: string; onClose: () => void; onSave: (b: Body) => Promise<unknown> }) {
  const [form, setForm] = useState({ paidOn: new Date().toISOString().slice(0, 10), amount: String(item.amount ?? ''), reference: '', mode: 'OV' })
  return (
    <Modal title={t('Payment of {a} for {b}', { a: item.kind, b: item.month })} size="sm" onClose={onClose}>
      <Form onSubmit={() => onSave({ paidOn: form.paidOn, amount: Number(form.amount), reference: form.reference || null, mode: form.mode })}>
        {item.amount !== null && <p className="hint">{t('Declared: {a}', { a: money(item.amount, currency) })}</p>}
        <label>{t('Paid on')}<DateInput required value={form.paidOn} onChange={(e) => setForm({ ...form, paidOn: e.target.value })} /></label>
        <label>{t('Amount paid ({a})', { a: currency })}<input type="number" min="0" step="0.01" required value={form.amount} onChange={(e) => setForm({ ...form, amount: e.target.value })} /></label>
        <label>{t('How')}<select value={form.mode} onChange={(e) => setForm({ ...form, mode: e.target.value })}>
          {PAY_MODES.map(([v, l]) => <option key={v} value={v}>{l}</option>)}</select></label>
        <label>{t('Receipt or bank reference')}<input maxLength={100} value={form.reference} onChange={(e) => setForm({ ...form, reference: e.target.value })} /></label>
        <div className="modal-foot"><Button className="secondary" onClick={onClose}>{t('Cancel')}</Button><Button type="submit">{t('Save')}</Button></div>
      </Form>
    </Modal>
  )
}
