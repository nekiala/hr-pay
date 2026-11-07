import {
  createContext, isValidElement, useContext, useRef, useState,
  type ButtonHTMLAttributes, type FormEvent, type FormHTMLAttributes, type MouseEvent, type ReactNode,
} from 'react'
import { t } from '../i18n'

/** Says what is happening while a button waits, from its label: "Save" becomes "Saving…". */
const VERBS: Record<string, string> = {
  add: 'Adding', apply: 'Applying', approve: 'Approving', assign: 'Assigning', calculate: 'Calculating', cancel: 'Cancelling',
  change: 'Changing', check: 'Checking', choose: 'Saving', close: 'Closing', confirm: 'Confirming', create: 'Creating',
  deactivate: 'Deactivating', delete: 'Deleting', disable: 'Disabling', enable: 'Enabling', end: 'Ending', enter: 'Saving',
  generate: 'Generating', grant: 'Granting', import: 'Importing', install: 'Installing', move: 'Moving', propose: 'Sending', record: 'Recording',
  refresh: 'Refreshing', refuse: 'Refusing', register: 'Registering', reject: 'Rejecting', remove: 'Removing',
  reopen: 'Reopening', save: 'Saving', send: 'Sending', set: 'Saving', sign: t('Signing in'), skip: 'Saving',
  start: 'Starting', submit: 'Sending', void: 'Voiding', work: t('Working it out'), accept: 'Accepting', acknowledge: 'Sending',
  contest: 'Sending', mark: 'Saving', recalculate: 'Calculating', return: t('Sending back'), send_back: t('Sending back'),
}

function text(node: ReactNode): string {
  if (node === null || node === undefined || typeof node === 'boolean') return ''
  if (typeof node === 'string' || typeof node === 'number') return String(node)
  if (Array.isArray(node)) return node.map(text).join('')
  if (isValidElement<{ children?: ReactNode }>(node)) return text(node.props.children)
  return ''
}

export function pendingLabel(label: ReactNode): string {
  const first = text(label).trim().split(/\s+/)[0]?.toLowerCase() ?? ''
  const verb = VERBS[first]
  return verb ? `${verb}…` : t('Please wait…')
}

export function Spinner({ className = '' }: { className?: string }) {
  return <span className={`spinner ${className}`} aria-hidden="true" />
}

const FormPending = createContext(false)

type ButtonProps = Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'onClick'> & {
  /** May return a promise: the button then shows a spinner until it settles. */
  onClick?: (e: MouseEvent<HTMLButtonElement>) => unknown
  /** Shows the spinner whatever onClick returns, for work the caller tracks itself. */
  pending?: boolean
  /** The text shown with the spinner; worked out from the label when left out. */
  pendingText?: string
}

/**
 * Every button of the app. While its action runs (a returned promise, the form it submits, or `pending`)
 * it is disabled and shows a spinner with a short text, so a click can't be sent twice.
 */
export function Button({ onClick, pending, pendingText, disabled, children, type = 'button', className, ...rest }: ButtonProps) {
  const [running, setRunning] = useState(false)
  const formPending = useContext(FormPending)
  const busy = Boolean(pending) || running || (type === 'submit' && formPending)

  const click = (e: MouseEvent<HTMLButtonElement>) => {
    if (busy) { e.preventDefault(); return }
    const result = onClick?.(e)
    if (result && typeof (result as Promise<unknown>).then === 'function') {
      setRunning(true)
      ;(result as Promise<unknown>).catch(() => undefined).finally(() => setRunning(false))
    }
  }

  return (
    <button type={type} {...rest} className={className} disabled={disabled || busy} aria-busy={busy || undefined} onClick={click}>
      {busy ? <span className="busy"><Spinner />{pendingText ?? pendingLabel(children)}</span> : children}
    </button>
  )
}

type FormProps = Omit<FormHTMLAttributes<HTMLFormElement>, 'onSubmit'> & {
  /** May return a promise: the form's submit button shows a spinner until it settles. */
  onSubmit?: (e: FormEvent<HTMLFormElement>) => unknown
}

/** A form whose submit button shows a spinner while the submit runs, and that ignores a second submit meanwhile. */
export function Form({ onSubmit, children, ...rest }: FormProps) {
  const [pending, setPending] = useState(false)
  const running = useRef(false)
  const submit = (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault()
    if (running.current) return
    const result = onSubmit?.(e)
    if (result && typeof (result as Promise<unknown>).then === 'function') {
      running.current = true
      setPending(true)
      ;(result as Promise<unknown>).catch(() => undefined).finally(() => { running.current = false; setPending(false) })
    }
  }
  return (
    <form {...rest} onSubmit={submit} aria-busy={pending || undefined}>
      <FormPending.Provider value={pending}>{children}</FormPending.Provider>
    </form>
  )
}
