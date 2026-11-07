import { createContext, useCallback, useContext, useState, type ReactNode } from 'react'
import { Button, Form } from './Button'
import { ErrorText } from './ErrorText'
import { Modal } from './Modal'
import { t } from '../i18n'

type Field = { label: string; placeholder?: string; required?: boolean; pattern?: string; defaultValue?: string; hint?: string; type?: string; multiline?: boolean }

export type AskOptions = {
  title: string
  message?: ReactNode
  /** The text of the button that goes ahead, such as "Cancel the loan". */
  confirmLabel: string
  pendingText?: string
  danger?: boolean
  /** Fields to fill in before going ahead; their values reach onConfirm in the same order. */
  fields?: Field[]
  /**
   * Runs when the person goes ahead. The button shows a spinner until it settles; the dialog then closes,
   * unless it throws, in which case the error is shown in the dialog.
   */
  onConfirm: (values: string[]) => unknown
}

const Ask = createContext<(o: AskOptions) => void>(() => undefined)

/** Asks for a confirmation or a short answer in a dialog: the app's replacement for window.confirm and window.prompt. */
export const useAsk = () => useContext(Ask)

export function DialogsProvider({ children }: { children: ReactNode }) {
  const [open, setOpen] = useState<AskOptions | null>(null)
  const ask = useCallback((o: AskOptions) => setOpen(o), [])
  return (
    <Ask.Provider value={ask}>
      {children}
      {open && <AskDialog key={open.title + open.confirmLabel} options={open} onClose={() => setOpen(null)} />}
    </Ask.Provider>
  )
}

function AskDialog({ options, onClose }: { options: AskOptions; onClose: () => void }) {
  const fields = options.fields ?? []
  const [values, setValues] = useState(fields.map((f) => f.defaultValue ?? ''))
  const [error, setError] = useState<string | null>(null)

  const submit = async () => {
    setError(null)
    try {
      // The pages' run() helpers show their own errors on the page, which the closed dialog uncovers.
      await options.onConfirm(values)
      onClose()
    } catch (e) {
      setError(e instanceof Error ? e.message : t('Something went wrong.'))
    }
  }

  return (
    <Modal title={options.title} size="sm" onClose={onClose}>
      <Form onSubmit={submit}>
        {options.message && <div className="muted">{options.message}</div>}
        {fields.map((f, i) => (
          <label key={f.label}>
            {f.label}
            {f.multiline
              ? <textarea rows={3} value={values[i]} required={f.required} placeholder={f.placeholder}
                  onChange={(e) => setValues(values.map((v, j) => (j === i ? e.target.value : v)))} />
              : <input type={f.type ?? 'text'} value={values[i]} required={f.required} pattern={f.pattern} placeholder={f.placeholder}
                  onChange={(e) => setValues(values.map((v, j) => (j === i ? e.target.value : v)))} />}
            {f.hint && <span className="hint">{f.hint}</span>}
          </label>
        ))}
        <ErrorText error={error} />
        <div className="modal-foot">
          <Button className="secondary" onClick={onClose}>{t('Back')}</Button>
          <Button type="submit" className={options.danger ? 'danger' : undefined} pendingText={options.pendingText}>{options.confirmLabel}</Button>
        </div>
      </Form>
    </Modal>
  )
}
