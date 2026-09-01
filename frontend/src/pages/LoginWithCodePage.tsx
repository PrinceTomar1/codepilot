import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { requestLoginCode, verifyLoginCode } from '../api/auth'
import { useAuth } from '../context/AuthContext'
import { getErrorMessage } from '../lib/utils'

type Step = 'email' | 'code'

/**
 * Passwordless sign-in: request a one-time code by email, then trade it for a real session --
 * an alternative to password login, not the email-verification code from signup (that one only
 * flips emailVerified; this one goes through loginWithToken() exactly like password login does).
 */
export default function LoginWithCodePage() {
  const { loginWithToken } = useAuth()
  const navigate = useNavigate()

  const [step, setStep] = useState<Step>('email')
  const [email, setEmail] = useState('')
  const [code, setCode] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [confirmationMessage, setConfirmationMessage] = useState<string | null>(null)

  const handleRequestCode = async (event: FormEvent) => {
    event.preventDefault()
    if (!email.trim()) {
      setError('Please enter your email.')
      return
    }
    setError(null)
    setIsSubmitting(true)
    try {
      const { message } = await requestLoginCode(email.trim())
      setConfirmationMessage(message)
      setStep('code')
    } catch (err) {
      setError(getErrorMessage(err))
    } finally {
      setIsSubmitting(false)
    }
  }

  const handleVerifyCode = async (event: FormEvent) => {
    event.preventDefault()
    if (!/^\d{6}$/.test(code)) {
      setError('Enter the 6-digit code from your email.')
      return
    }
    setError(null)
    setIsSubmitting(true)
    try {
      const { token } = await verifyLoginCode(email.trim(), code)
      await loginWithToken(token)
      navigate('/dashboard', { replace: true })
    } catch (err) {
      setError(getErrorMessage(err))
    } finally {
      setIsSubmitting(false)
    }
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-slate-950 px-4">
      <div className="w-full max-w-sm">
        <div className="mb-8 flex flex-col items-center gap-2 text-center">
          <span className="text-3xl">🔐</span>
          <h1 className="text-xl font-semibold text-slate-100">Sign in with a code</h1>
          <p className="text-sm text-slate-500">
            {step === 'email'
              ? "We'll email you a one-time code -- no password needed."
              : (confirmationMessage ?? 'Enter the code we just emailed you.')}
          </p>
        </div>

        {step === 'email' ? (
          <form onSubmit={handleRequestCode} className="card space-y-4 p-6" noValidate>
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
                autoFocus
              />
            </div>

            {error && (
              <p className="rounded-lg border border-rose-900/50 bg-rose-950/40 px-3 py-2 text-sm text-rose-300">
                {error}
              </p>
            )}

            <button type="submit" className="btn-primary w-full" disabled={isSubmitting}>
              {isSubmitting ? 'Sending…' : 'Send code'}
            </button>
          </form>
        ) : (
          <form onSubmit={handleVerifyCode} className="card space-y-4 p-6" noValidate>
            <div>
              <label htmlFor="login-code" className="label">
                6-digit code
              </label>
              <input
                id="login-code"
                inputMode="numeric"
                autoComplete="one-time-code"
                maxLength={6}
                className="input text-center font-mono tracking-[0.4em]"
                placeholder="123456"
                value={code}
                onChange={(e) => setCode(e.target.value.replace(/\D/g, '').slice(0, 6))}
                autoFocus
              />
            </div>

            {error && (
              <p className="rounded-lg border border-rose-900/50 bg-rose-950/40 px-3 py-2 text-sm text-rose-300">
                {error}
              </p>
            )}

            <button
              type="submit"
              className="btn-primary w-full"
              disabled={isSubmitting || code.length !== 6}
            >
              {isSubmitting ? 'Signing in…' : 'Sign in'}
            </button>

            <button
              type="button"
              onClick={() => {
                setStep('email')
                setCode('')
                setError(null)
              }}
              className="w-full text-center text-xs font-medium text-slate-500 hover:text-slate-300"
            >
              Use a different email or send another code
            </button>
          </form>
        )}

        <p className="mt-5 text-center text-sm text-slate-500">
          <Link to="/login" className="font-medium text-brand-400 hover:text-brand-300">
            Back to sign in with a password
          </Link>
        </p>
      </div>
    </div>
  )
}
