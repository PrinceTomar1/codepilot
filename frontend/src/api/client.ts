import axios, { AxiosError } from 'axios'
import type { ApiErrorBody } from '../types'

const TOKEN_STORAGE_KEY = 'codepilot.token'

export const baseURL =
  (import.meta.env.VITE_API_BASE_URL as string | undefined) ||
  'http://localhost:8080/api'

/**
 * Tiny in-memory + localStorage token store. Kept outside React so the axios
 * interceptor (which lives outside the component tree) can read/write it
 * without a context indirection.
 */
let currentToken: string | null = localStorage.getItem(TOKEN_STORAGE_KEY)

export const tokenStore = {
  get(): string | null {
    return currentToken
  },
  set(token: string | null): void {
    currentToken = token
    if (token) {
      localStorage.setItem(TOKEN_STORAGE_KEY, token)
    } else {
      localStorage.removeItem(TOKEN_STORAGE_KEY)
    }
  },
}

/**
 * Fired whenever the API responds 401 so the app-level AuthContext can clear
 * session state and redirect to /login, without the client needing to know
 * about React Router.
 */
export const UNAUTHORIZED_EVENT = 'codepilot:unauthorized'

export const apiClient = axios.create({
  baseURL,
  headers: {
    'Content-Type': 'application/json',
  },
  // Axios defaults to no timeout at all, so a stalled connection (or a backend call that itself
  // hangs) leaves the UI stuck on a loading spinner forever with no way to recover. 65s gives the
  // backend's own 60s ai-service timeout (see WebClientConfig.TIMEOUT_MS) room to fire first and
  // return a real error, while still guaranteeing this client never waits indefinitely.
  timeout: 65_000,
})

apiClient.interceptors.request.use((config) => {
  const token = tokenStore.get()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

export interface NormalizedApiError {
  message: string
  status: number
}

apiClient.interceptors.response.use(
  (response) => response,
  (error: AxiosError<ApiErrorBody>) => {
    const status = error.response?.status ?? 0

    if (status === 401) {
      tokenStore.set(null)
      window.dispatchEvent(new CustomEvent(UNAUTHORIZED_EVENT))
    }

    const message =
      error.response?.data?.message ??
      (error.code === 'ECONNABORTED'
        ? "This is taking longer than expected. The server didn't respond in time -- please try again."
        : error.message) ??
      'Something went wrong talking to the server.'

    const normalized: NormalizedApiError = { message, status }
    return Promise.reject(normalized)
  },
)
