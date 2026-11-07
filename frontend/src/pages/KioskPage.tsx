import { useEffect, useRef, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import QRCode from 'qrcode'
import { api } from '../api'

type Token = { siteName: string; url: string; secondsLeft: number }

/** Full-screen rotating QR code for a tablet at the entrance. No sign-in; the link carries the site's key. */
export default function KioskPage() {
  const [params] = useSearchParams()
  const canvas = useRef<HTMLCanvasElement>(null)
  const [token, setToken] = useState<Token | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [now, setNow] = useState(new Date())

  useEffect(() => {
    let timer: ReturnType<typeof setTimeout>
    const refresh = async () => {
      try {
        const t = await api<Token>(`/api/kiosk?site=${encodeURIComponent(params.get('site') ?? '')}&key=${encodeURIComponent(params.get('key') ?? '')}`)
        setToken(t)
        setError(null)
        timer = setTimeout(refresh, Math.max(1, t.secondsLeft) * 1000)
      } catch (e) {
        setError(e instanceof Error ? e.message : 'Kiosk unavailable')
        timer = setTimeout(refresh, 10000)
      }
    }
    void refresh()
    const clock = setInterval(() => setNow(new Date()), 1000)
    return () => { clearTimeout(timer); clearInterval(clock) }
  }, [params])

  useEffect(() => {
    if (token && canvas.current) void QRCode.toCanvas(canvas.current, token.url, { width: 420, margin: 2 })
  }, [token])

  return (
    <div className="kiosk">
      {error ? <p className="error">{error}</p> : (
        <>
          <h1>{token?.siteName ?? '…'}</h1>
          <p className="clock">{now.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })}</p>
          <canvas ref={canvas} />
          <p>Scan with your phone to check in or end your day.</p>
          <p className="muted small">The code changes every 30 seconds.</p>
        </>
      )}
    </div>
  )
}
