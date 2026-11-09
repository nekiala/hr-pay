import { useEffect, useState } from 'react'
import {
  api, CERTIFICATE_KINDS, DOC_SOURCES, fileSize, LETTER_STATUS, longDay, openFile,
  type CertificateKind, type CodeSent, type Letter, type MyDocuments,
  personName,
} from '../api'
import { useAuth } from '../auth'
import { Button, Form } from '../components/Button'
import { useAsk } from '../components/Dialogs'
import { ErrorText } from '../components/ErrorText'
import { Modal } from '../components/Modal'
import { t } from '../i18n'
import { CertificatesTable, UploadModal } from './PersonLifecyclePage'

/** The person's own documents: what HR keeps for them, what waits for their signature, and certificates on request. */
export default function MyDocumentsPage() {
  const ask = useAsk()
  const [data, setData] = useState<MyDocuments | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [info, setInfo] = useState<string | null>(null)
  const [upload, setUpload] = useState(false)
  const [signing, setSigning] = useState<Letter | null>(null)
  const load = () => api<MyDocuments>('/api/my/documents').then(setData).catch((e: Error) => setError(e.message))
  useEffect(() => { void load() }, [])
  const open = (path: string) => { setError(null); openFile(path).catch((e: Error) => setError(e.message)) }

  if (!data) return <ErrorText error={error} />
  const toSign = data.letters.filter((l) => l.status === 'SENT' && l.requiresSignature)
  const others = data.letters.filter((l) => !(l.status === 'SENT' && l.requiresSignature))
  const pending = new Set(data.certificates.filter((c) => c.status === 'PENDING').map((c) => c.kind))

  return (
    <section>
      <h2>{t('My documents')}</h2>
      <ErrorText error={error} />
      {info && <p className="success">{info}</p>}

      {toSign.length > 0 && (
        <div className="card">
          <h3>{t('To sign')}</h3>
          <ul className="checklist">
            {toSign.map((l) => (
              <li key={l.id}>
                <span style={{ flex: 1 }}>{l.title}<div className="small muted">{t('No. {a}', { a: l.number })} · {t('sent {a} by {b}', { a: longDay(l.sentAt), b: l.sentByName })}</div></span>
                <Button onClick={() => setSigning(l)}>{t('Read and sign')}</Button>
              </li>
            ))}
          </ul>
        </div>
      )}

      <div className="card">
        <div className="row between">
          <h3>{t('Certificates')}</h3>
          <div className="row">
            {(Object.keys(CERTIFICATE_KINDS) as CertificateKind[]).filter((k) => !pending.has(k)).map((k) => (
              <Button key={k} className="secondary" onClick={() => ask({
                title: t('Ask HR for a {a}?', { a: CERTIFICATE_KINDS[k].toLowerCase() }), confirmLabel: t('Ask'),
                message: t('You are told here as soon as it is ready.'),
                fields: [{ label: t('What it is for (optional)'), placeholder: t('For example: visa, bank loan') }],
                onConfirm: async ([purpose]) => { await api('/api/my/certificates', { method: 'POST', body: { kind: k, purpose: purpose || null } }); await load() },
              })}>{t('Ask for a {a}', { a: CERTIFICATE_KINDS[k].toLowerCase() })}</Button>
            ))}
          </div>
        </div>
        <CertificatesTable rows={data.certificates} onOpen={(docId) => open(`/api/my/documents/${docId}/file`)}
          onCancel={(id) => void api(`/api/my/certificates/${id}/cancel`, { method: 'POST' }).then(load).catch((e: Error) => setError(e.message))} />
      </div>

      <div className="card">
        <div className="row between">
          <h3>{t('My file')}</h3>
          {data.uploadTypes.length > 0 && <Button className="secondary" onClick={() => setUpload(true)}>{t('Send a document to HR')}</Button>}
        </div>
        <ul className="checklist">
          {data.documents.length === 0 && <li className="muted">{t('No document yet.')}</li>}
          {data.documents.map((d) => (
            <li key={d.id}>
              <span style={{ flex: 1 }}>
                <Button className="link" onClick={() => open(`/api/my/documents/${d.id}/file`)}>{(d.source === 'SIGNED' || d.source === 'CERTIFICATE') && d.note ? d.note : d.typeName}</Button>
                <div className="small muted">{d.fileName} · {fileSize(d.sizeBytes)} · {DOC_SOURCES[d.source]} · {longDay(d.uploadedAt)}</div>
              </span>
              {d.expiresOn && <span className={d.expiresOn < new Date().toISOString().slice(0, 10) ? 'badge off' : 'badge'}>{t('expires {a}', { a: longDay(d.expiresOn) })}</span>}
            </li>
          ))}
        </ul>
        {others.length > 0 && (
          <>
            <h4>{t('Contracts and letters')}</h4>
            <ul className="checklist">
              {others.map((l) => (
                <li key={l.id}>
                  <span style={{ flex: 1 }}>{l.title}<div className="small muted">{l.number} · {longDay(l.sentAt)}</div></span>
                  <span className={LETTER_STATUS[l.status][1]}>{l.requiresSignature ? LETTER_STATUS[l.status][0] : t('Received')}</span>
                  <Button className="secondary" onClick={() => open(`/api/my/letters/${l.id}/file`)}>{t('PDF')}</Button>
                </li>
              ))}
            </ul>
          </>
        )}
      </div>

      {upload && <UploadModal mine types={data.uploadTypes} onClose={() => setUpload(false)} onDone={async () => { setUpload(false); setInfo(t('Sent to HR.')); await load() }} />}
      {signing && <SignModal letter={signing} onOpen={() => open(`/api/my/letters/${signing.id}/file`)} onClose={() => setSigning(null)}
        onDone={async (message) => { setSigning(null); setInfo(message); await load() }} />}
    </section>
  )
}

/** Reading, then signing with the code sent by email or WhatsApp, or refusing with a reason. */
function SignModal({ letter, onOpen, onClose, onDone }: { letter: Letter; onOpen: () => void; onClose: () => void; onDone: (message: string) => Promise<void> }) {
  const { me } = useAuth()
  const [read, setRead] = useState(false)
  const [sent, setSent] = useState<CodeSent | null>(null)
  const [code, setCode] = useState('')
  const [name, setName] = useState('')
  const [declining, setDeclining] = useState(false)
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string | null>(null)

  const askCode = async () => {
    setError(null)
    try { setSent(await api<CodeSent>(`/api/my/letters/${letter.id}/code`, { method: 'POST' })) } catch (e) { setError((e as Error).message) }
  }
  const sign = async () => {
    setError(null)
    try {
      await api(`/api/my/letters/${letter.id}/sign`, { method: 'POST', body: { challengeId: sent!.challengeId, code, name } })
      await onDone(t('Signed. The signed copy is in your documents.'))
    } catch (e) { setError((e as Error).message) }
  }
  const decline = async () => {
    setError(null)
    try {
      await api(`/api/my/letters/${letter.id}/decline`, { method: 'POST', body: { reason: reason || null } })
      await onDone(t('HR is told that you did not sign.'))
    } catch (e) { setError((e as Error).message) }
  }
  const channels = (sent?.channels ?? []).map((c) => (c === 'EMAIL' ? t('email') : c === 'WHATSAPP' ? 'WhatsApp' : c)).join(t(' and '))

  const early = !!letter.signableFrom && letter.signableFrom > new Date().toISOString().slice(0, 10)
  return (
    <Modal title={letter.title} onClose={onClose}>
      <p className="small muted">{t('No. {a}', { a: letter.number })} · {t('sent {a} by {b}', { a: longDay(letter.sentAt), b: letter.sentByName })}</p>
      <ol className="small stack">
        <li>
          <Button className="secondary" onClick={() => { onOpen(); setRead(true) }}>{t('Read the document (PDF)')}</Button>
        </li>
        {!declining && (
          <li>
            {early ? (
              <p className="notice">{t('You can sign from {a}: the law gives you two working days to read the draft of your contract (Labour Code art. 46).', { a: longDay(letter.signableFrom) })}</p>
            ) : !sent ? (
              <Button disabled={!read} onClick={askCode}>{t('Send me a code to sign')}</Button>
            ) : (
              <Form onSubmit={sign} className="stack">
                <p>{t('We sent a 6-digit code by {a}.', { a: channels || t('email') })}</p>
                <input inputMode="numeric" autoComplete="one-time-code" maxLength={6} placeholder="123456" value={code} className="code-input"
                  aria-label={t('Code')} onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))} autoFocus />
                <label>{t('Type your full name to sign')}<input required maxLength={200} value={name} placeholder={me ? personName(me) : ''} onChange={(e) => setName(e.target.value)} /></label>
                <p className="muted">{t('By signing you confirm you read this document and agree to it. The date, the time and this device are recorded with your signature.')}</p>
                <div className="row">
                  <Button type="submit" disabled={code.length !== 6 || !name.trim()}>{t('Sign')}</Button>
                  <Button className="link" onClick={askCode}>{t('Send a new code')}</Button>
                </div>
              </Form>
            )}
          </li>
        )}
      </ol>
      {declining ? (
        <Form onSubmit={decline} className="stack">
          <label>{t('Why you do not sign (HR reads it)')}<textarea maxLength={300} value={reason} onChange={(e) => setReason(e.target.value)} /></label>
          <div className="row">
            <Button type="submit" className="danger">{t('Refuse to sign')}</Button>
            <Button className="secondary" onClick={() => setDeclining(false)}>{t('Back')}</Button>
          </div>
        </Form>
      ) : (
        <p className="small"><Button className="link" onClick={() => setDeclining(true)}>{t('I do not agree with this document')}</Button></p>
      )}
      {!read && !declining && <p className="small muted">{t('Read the document first.')}</p>}
      <ErrorText error={error} />
    </Modal>
  )
}
