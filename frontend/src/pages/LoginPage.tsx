import { useState, type FormEvent } from 'react'
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router-dom'
import { baseURL } from '../api/client'
import { resendVerification } from '../api/auth'
import VerifyCodeForm from '../components/VerifyCodeForm'
import { useAuth } from '../context/AuthContext'
import { getErrorMessage } from '../lib/utils'

export default function LoginPage() {
  const { login } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const [searchParams] = useSearchParams()
  const oauthError = searchParams.get('oauth_error')

  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(oauthError)
  const [needsVerification, setNeedsVerification] = useState(false)
  const [resendState, setResendState] = useState<
    'idle' | 'sending' | 'sent'
  >('idle')
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [justVerified, setJustVerified] = useState(false)

  const redirectTo =
    (location.state as { from?: string } | null)?.from ?? '/dashboard'

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault()
    setError(null)
    setNeedsVerification(false)
    setResendState('idle')
    setJustVerified(false)

    if (!email.trim() || !password) {
      setError('Please enter your email and password.')
      return
    }

    setIsSubmitting(true)
    try {
      await login({ email: email.trim(), password })
      navigate(redirectTo, { replace: true })
    } catch (err) {
      setError(getErrorMessage(err))
      const status = (err as { status?: number } | null)?.status
      setNeedsVerification(status === 403)
    } finally {
      setIsSubmitting(false)
    }
  }

  const handleResend = async () => {
    if (!email.trim()) return
    setResendState('sending')
    try {
      await resendVerification(email.trim())
      setResendState('sent')
    } catch {
      setResendState('idle')
    }
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-slate-950 px-4">
      <div className="w-full max-w-sm">
        <div className="mb-8 flex flex-col items-center gap-2 text-center">
          <span className="text-3xl">🧭</span>
          <h1 className="text-xl font-semibold text-slate-100">
            Sign in to CodePilot
          </h1>
          <p className="text-sm text-slate-500">
            AI codebase intelligence for your repositories.
          </p>
        </div>

        <form
          onSubmit={handleSubmit}
          className="card space-y-4 p-6"
          noValidate
        >
          <div>
            <label htmlFor="email" className="label">
              Email
            </label>
            <input
              id="email"
              type="email"
              autoComplete="email"
              className="input"
              placeholder="you@example.com"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
            />
          </div>

          <div>
            <label htmlFor="password" className="label">
              Password
            </label>
            <input
              id="password"
              type="password"
              autoComplete="current-password"
              className="input"
              placeholder="••••••••"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
            />
          </div>

          {justVerified && (
            <p className="rounded-lg border border-emerald-900/50 bg-emerald-950/40 px-3 py-2 text-sm text-emerald-300">
              Email verified — sign in below.
            </p>
          )}

          {error && !justVerified && (
            <div className="rounded-lg border border-rose-900/50 bg-rose-950/40 px-3 py-2 text-sm text-rose-300">
              <p>{error}</p>
              {needsVerification && (
                <button
                  type="button"
                  onClick={handleResend}
                  disabled={resendState !== 'idle'}
                  className="mt-1 font-medium text-rose-200 underline underline-offset-2 hover:text-rose-100 disabled:opacity-60"
                >
                  {resendState === 'sent'
                    ? 'Verification email sent — check your inbox'
                    : resendState === 'sending'
                      ? 'Sending…'
                      : 'Resend verification email'}
                </button>
              )}
            </div>
          )}

          <button
            type="submit"
            className="btn-primary w-full"
            disabled={isSubmitting}
          >
            {isSubmitting ? 'Signing in…' : 'Sign in'}
          </button>

          <Link
            to="/login-with-code"
            className="block text-center text-xs font-medium text-brand-400 hover:text-brand-300"
          >
            Or sign in with a one-time code instead
          </Link>

          <div className="flex items-center gap-3 text-xs text-slate-600">
            <div className="h-px flex-1 bg-slate-800" />
            or
            <div className="h-px flex-1 bg-slate-800" />
          </div>

          <a href={`${baseURL}/auth/github/login`} className="btn-secondary flex w-full items-center justify-center gap-2">
            <svg viewBox="0 0 16 16" className="h-4 w-4 fill-current" aria-hidden="true">
              <path d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.01 8.01 0 0016 8c0-4.42-3.58-8-8-8Z" />
            </svg>
            Continue with GitHub
          </a>
        </form>

        {/* Deliberately outside the <form> above: VerifyCodeForm renders its own <form>, and a
            <form> nested inside another <form> is invalid HTML -- browsers handle the inner
            submit unpredictably, commonly routing it to the outer (login) form instead of this
            one. That's a real, confirmed-live bug: clicking "Verify" here did nothing, or
            silently re-triggered a login attempt, because the click never reliably reached
            VerifyCodeForm's own onSubmit. */}
        {error && !justVerified && needsVerification && (
          <div className="mt-3">
            <VerifyCodeForm
              email={email}
              onVerified={() => {
                setJustVerified(true)
                setError(null)
                setNeedsVerification(false)
              }}
            />
          </div>
        )}

        <p className="mt-5 text-center text-sm text-slate-500">
          Don&apos;t have an account?{' '}
          <Link to="/register" className="font-medium text-brand-400 hover:text-brand-300">
            Create one
          </Link>
        </p>
      </div>
    </div>
  )
}
