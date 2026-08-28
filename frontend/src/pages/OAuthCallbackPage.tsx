import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import { getErrorMessage } from '../lib/utils'

/**
 * Landing page for the GitHub OAuth redirect. The backend puts the JWT in the URL fragment
 * (not a query param) so it never gets logged server-side or land in browser history search --
 * fragments aren't sent in HTTP requests at all.
 */
export default function OAuthCallbackPage() {
  const { loginWithToken } = useAuth()
  const navigate = useNavigate()
  const [error, setError] = useState<string | null>(null)
  const ranOnce = useRef(false)

  useEffect(() => {
    if (ranOnce.current) return
    ranOnce.current = true

    const token = new URLSearchParams(window.location.hash.slice(1)).get('token')
    if (!token) {
      setError('No sign-in token was returned by GitHub.')
      return
    }

    loginWithToken(token)
      .then(() => navigate('/dashboard', { replace: true }))
      .catch((err) => setError(getErrorMessage(err)))
  }, [loginWithToken, navigate])

  return (
    <div className="flex min-h-screen items-center justify-center bg-slate-950 px-4">
      <div className="w-full max-w-sm text-center">
        {error ? (
          <>
            <span className="text-3xl">⚠️</span>
            <h1 className="mt-4 text-xl font-semibold text-slate-100">
              Sign-in failed
            </h1>
            <p className="mt-2 text-sm text-slate-400">{error}</p>
            <Link
              to="/login"
              className="mt-6 inline-block font-medium text-brand-400 hover:text-brand-300"
            >
              Back to sign in
            </Link>
          </>
        ) : (
          <>
            <div className="mx-auto h-8 w-8 animate-spin rounded-full border-2 border-slate-700 border-t-brand-500" />
            <p className="mt-4 text-sm text-slate-400">Signing you in…</p>
          </>
        )}
      </div>
    </div>
  )
}
