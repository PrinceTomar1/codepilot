import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react'
import * as authApi from '../api/auth'
import { tokenStore, UNAUTHORIZED_EVENT } from '../api/client'
import type { LoginRequest, RegisterRequest, User } from '../types'

const USER_STORAGE_KEY = 'codepilot.user'

function readStoredUser(): User | null {
  try {
    const raw = localStorage.getItem(USER_STORAGE_KEY)
    return raw ? (JSON.parse(raw) as User) : null
  } catch {
    return null
  }
}

interface AuthContextValue {
  user: User | null
  isAuthenticated: boolean
  isInitializing: boolean
  login: (payload: LoginRequest) => Promise<void>
  loginWithToken: (token: string) => Promise<void>
  register: (payload: RegisterRequest) => Promise<string>
  logout: () => void
}

const AuthContext = createContext<AuthContextValue | undefined>(undefined)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null)
  const [isInitializing, setIsInitializing] = useState(true)

  useEffect(() => {
    const storedToken = tokenStore.get()
    const storedUser = readStoredUser()
    if (storedToken && storedUser) {
      setUser(storedUser)
    }
    setIsInitializing(false)
  }, [])

  const persistSession = useCallback((token: string, nextUser: User) => {
    tokenStore.set(token)
    localStorage.setItem(USER_STORAGE_KEY, JSON.stringify(nextUser))
    setUser(nextUser)
  }, [])

  const clearSession = useCallback(() => {
    tokenStore.set(null)
    localStorage.removeItem(USER_STORAGE_KEY)
    setUser(null)
  }, [])

  useEffect(() => {
    const handleUnauthorized = () => clearSession()
    window.addEventListener(UNAUTHORIZED_EVENT, handleUnauthorized)
    return () =>
      window.removeEventListener(UNAUTHORIZED_EVENT, handleUnauthorized)
  }, [clearSession])

  const login = useCallback(
    async (payload: LoginRequest) => {
      const response = await authApi.login(payload)
      persistSession(response.token, response.user)
    },
    [persistSession],
  )

  const loginWithToken = useCallback(
    async (token: string) => {
      // Used by the GitHub OAuth callback -- the redirect only carries a JWT, not a user object,
      // so the token has to be set before /auth/me can be called with it (the request
      // interceptor reads it from tokenStore). Roll back on failure so a bad/expired token
      // doesn't get left behind as a half-broken session.
      tokenStore.set(token)
      try {
        const nextUser = await authApi.getCurrentUser()
        persistSession(token, nextUser)
      } catch (err) {
        tokenStore.set(null)
        throw err
      }
    },
    [persistSession],
  )

  const register = useCallback(async (payload: RegisterRequest) => {
    const response = await authApi.register(payload)
    return response.message
  }, [])

  const logout = useCallback(() => {
    clearSession()
  }, [clearSession])

  const value = useMemo<AuthContextValue>(
    () => ({
      user,
      isAuthenticated: Boolean(user),
      isInitializing,
      login,
      loginWithToken,
      register,
      logout,
    }),
    [user, isInitializing, login, loginWithToken, register, logout],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext)
  if (!ctx) {
    throw new Error('useAuth must be used within an AuthProvider')
  }
  return ctx
}
