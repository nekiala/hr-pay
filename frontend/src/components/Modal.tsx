import { useEffect, useId, useRef, type ReactNode } from 'react'
import { t } from '../i18n'

type Props = {
  title: ReactNode
  /** Called on Escape, the close button or a click outside. Leave out for a dialog that must be answered. */
  onClose?: () => void
  children: ReactNode
  /** Buttons along the bottom, right-aligned. */
  footer?: ReactNode
  size?: 'sm' | 'md' | 'lg'
}

/**
 * A dialog over the page, for short forms and confirmations. Long forms get their own page instead.
 * Built on <dialog>, so focus stays inside it and Escape closes it.
 */
export function Modal({ title, onClose, children, footer, size = 'md' }: Props) {
  const ref = useRef<HTMLDialogElement>(null)
  const titleId = useId()

  useEffect(() => {
    const dialog = ref.current
    if (!dialog) return
    if (!dialog.open) dialog.showModal()
    // Put the cursor in the first field, as a person would expect.
    const first = dialog.querySelector<HTMLElement>('input:not([type=hidden]):not([disabled]), select, textarea')
    first?.focus()
    return () => { if (dialog.open) dialog.close() }
  }, [])

  return (
    <dialog
      ref={ref}
      className={`modal modal-${size}`}
      aria-labelledby={titleId}
      onCancel={(e) => { e.preventDefault(); onClose?.() }}
      onClick={(e) => { if (e.target === ref.current) onClose?.() }}
    >
      <div className="modal-body">
        <header className="modal-head">
          <h3 id={titleId}>{title}</h3>
          {onClose && <button type="button" className="modal-x" aria-label={t('Close')} onClick={onClose}>×</button>}
        </header>
        {children}
        {footer && <footer className="modal-foot">{footer}</footer>}
      </div>
    </dialog>
  )
}
