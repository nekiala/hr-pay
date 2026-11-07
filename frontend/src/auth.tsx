import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react'
import { api, type Me } from './api'

type AuthState = {
  me: Me | null
  loading: boolean
  can: (permission: string) => boolean
  refresh: () => Promise<void>
  signIn: (email: string, password: string) => Promise<void>
  signOut: () => Promise<void>
}

const AuthContext = createContext<AuthState | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [me, setMe] = useState<Me | null>(null)
  const [loading, setLoading] = useState(true)

  const refresh = useCallback(async () => {
    try {
      setMe(await api<Me>('/api/auth/me'))
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
    setMe(await api<Me>('/api/auth/login', { method: 'POST', body: { email, password } }))
  }

  const signOut = async () => {
    await api('/api/auth/logout', { method: 'POST' }).catch(() => undefined)
    setMe(null)
  }

  const can = useCallback((permission: string) => !!me?.permissions.includes(permission), [me])

  return (
    <AuthContext.Provider value={{ me, loading, can, refresh, signIn, signOut }}>{children}</AuthContext.Provider>
  )
}

export function useAuth() {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth outside AuthProvider')
  return ctx
}
