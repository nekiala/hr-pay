import type { PayRunStage, PayRunType } from './api'
import { t } from './i18n'

/** The pay types, in the order the New pay run page shows them. */
export const PAY_RUN_TYPES: { type: PayRunType; name: string; description: string; settings?: string }[] = [
  { type: 'MONTHLY', name: t('Month-end payroll'), description: t('Every employee\'s payslip for the month, with CNSS and income tax.') },
  { type: 'QUINZAINE', name: t('Quinzaine'), description: t('Mid-month advance on pay, taken back on the month-end payslip.'), settings: '/payroll/quinzaine/settings' },
  { type: 'LEAVE_PAY', name: t('Leave pay'), description: t('Allocation de congé for approved annual leave, paid before the leave.'), settings: '/leave/settings' },
  { type: 'THIRTEENTH', name: t('13th month'), description: t('For a year, paid with the month-end payroll you choose.'), settings: '/payroll/thirteenth-month/settings' },
  { type: 'CORRECTION', name: t('Corrections and back pay'), description: t('Complementary payslips for closed months, and back pay for past-dated raises.') },
  { type: 'SETTLEMENT', name: t('Final settlement'), description: t('Notice, leave, severance and 13th month share of someone leaving, on their last payslip.') },
]

export const typeName = (type: PayRunType) => PAY_RUN_TYPES.find((x) => x.type === type)?.name ?? type

export const STAGE: Record<PayRunStage, { label: string; badge: string }> = {
  TO_APPROVE: { label: t('Waiting for approval'), badge: 'warn' },
  TO_CLOSE: { label: t('Approved, to close'), badge: 'info' },
  TO_PAY: { label: t('To pay'), badge: 'info' },
  DONE: { label: t('Done'), badge: 'ok' },
  CANCELLED: { label: t('Cancelled'), badge: '' },
}
