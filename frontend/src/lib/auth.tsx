import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { api, setUnauthorizedHandler, tokenStore, type UserResponse } from './api'

interface AuthState {
  user: UserResponse | null
  loading: boolean
  login: (username: string, password: string) => Promise<void>
  /** 회원 가입처럼 이미 받은 토큰·사용자를 그대로 세션에 반영한다. */
  loginWithToken: (token: string, user: UserResponse) => void
  logout: () => void
  refresh: () => Promise<void>
}

const AuthContext = createContext<AuthState | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<UserResponse | null>(null)
  const [loading, setLoading] = useState(true)

  const logout = useCallback(() => {
    tokenStore.clear()
    setUser(null)
  }, [])

  const refresh = useCallback(async () => {
    if (!tokenStore.get()) { setUser(null); return }
    try { setUser(await api.auth.me()) } catch { logout() }
  }, [logout])

  useEffect(() => {
    setUnauthorizedHandler(() => setUser(null))
    refresh().finally(() => setLoading(false))
  }, [refresh])

  const login = useCallback(async (username: string, password: string) => {
    const res = await api.auth.login(username, password)
    tokenStore.set(res.token)
    setUser(res.user)
  }, [])

  const loginWithToken = useCallback((token: string, u: UserResponse) => {
    tokenStore.set(token)
    setUser(u)
  }, [])

  const value = useMemo(() => ({ user, loading, login, loginWithToken, logout, refresh }), [user, loading, login, loginWithToken, logout, refresh])
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used within AuthProvider')
  return ctx
}
