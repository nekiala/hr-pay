import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react'
import { api, type Me, type TwoStepNeeded } from './api'
import { language, setCountry, setLanguage, t } from './i18n'

type AuthState = {
  me: Me | null
  loading: boolean
  can: (permission: string) => boolean
  refresh: () => Promise<void>
  /** Signs in, or returns the second step still needed. */
  signIn: (email: string, password: string) => Promise<TwoStepNeeded | null>
  /** The person is in, after the second step. */
  signedIn: (me: Me) => void
  signOut: () => Promise<void>
}

/** Shows the app in the person's language: a change reloads the page, so every text is read again. */
function follow(m: Me | null): Me | null {
  if (m?.company) setCountry(m.company.country, m.company.localCurrency)
  if (m && m.language !== language()) {
    setLanguage(m.language)
    window.location.reload()
  }
  return m
}

const AuthContext = createContext<AuthState | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [me, setMe] = useState<Me | null>(null)
  const [loading, setLoading] = useState(true)

  const refresh = useCallback(async () => {
    try {
      setMe(follow(await api<Me>('/api/auth/me')))
    } catch {
      setMe(null)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void refresh()
    const onSignedOut = () => setMe(null)
    window.addEventListener('hrpay:signed-out', onSignedOut)
    return () => window.removeEventListener('hrpay:signed-out', onSignedOut)
  }, [refresh])

  const signIn = async (email: string, password: string) => {
    const result = await api<Me | TwoStepNeeded>('/api/auth/login', { method: 'POST', body: { email, password } })
    if ('setup' in result) return result
    setMe(follow(result))
    return null
  }

  const signedIn = (m: Me) => setMe(follow(m))

  const signOut = async () => {
    await api('/api/auth/logout', { method: 'POST' }).catch(() => undefined)
    setMe(null)
  }

  const can = useCallback((permission: string) => !!me?.permissions.includes(permission), [me])

  return (
    <AuthContext.Provider value={{ me, loading, can, refresh, signIn, signedIn, signOut }}>{children}</AuthContext.Provider>
  )
}

export function useAuth() {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error(t('useAuth outside AuthProvider'))
  return ctx
}
