import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { resendVerification, verifyEmail } from '../api/auth'
import VerifyCodeForm from '../components/VerifyCodeForm'
import { getErrorMessage } from '../lib/utils'

type Status = 'verifying' | 'success' | 'error'

export default function VerifyEmailPage() {
  const [searchParams] = useSearchParams()
  const token = searchParams.get('token')

  const [status, setStatus] = useState<Status>('verifying')
  const [message, setMessage] = useState('')
  const [resendEmail, setResendEmail] = useState('')
  const [resendState, setResendState] = useState<'idle' | 'sending' | 'sent'>(
    'idle',
  )

  useEffect(() => {
    if (!token) {
      setStatus('error')
      setMessage('This verification link is missing a token.')
      return
    }
    verifyEmail(token)
      .then((res) => {
        setStatus('success')
        setMessage(res.message)
      })
      .catch((err) => {
        setStatus('error')
        setMessage(getErrorMessage(err))
      })
  }, [token])

  const handleResend = async () => {
    if (!resendEmail.trim()) return
    setResendState('sending')
    try {
      await resendVerification(resendEmail.trim())
      setResendState('sent')
    } catch {
      setResendState('idle')
    }
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-slate-950 px-4">
      <div className="w-full max-w-sm text-center">
        <span className="text-3xl">
          {status === 'success' ? '✅' : status === 'error' ? '⚠️' : '🧭'}
        </span>
        <h1 className="mt-4 text-xl font-semibold text-slate-100">
          {status === 'verifying' ? 'Verifying your email…' : status === 'success' ? 'Email verified' : 'Verification failed'}
        </h1>
        <p className="mt-2 text-sm text-slate-400">{message}</p>

        {status === 'success' && (
          <Link
            to="/login"
            className="mt-6 inline-block font-medium text-brand-400 hover:text-brand-300"
          >
            Sign in
          </Link>
        )}

        {status === 'error' && (
          <div className="mt-6 space-y-3 text-left">
            <label htmlFor="resend-email" className="label">
              Get a new verification link
            </label>
            <input
              id="resend-email"
              type="email"
              autoComplete="email"
              className="input"
              placeholder="you@example.com"
              value={resendEmail}
              onChange={(e) => setResendEmail(e.target.value)}
            />
            <button
              type="button"
              onClick={handleResend}
              disabled={resendState !== 'idle'}
              className="btn-primary w-full"
            >
              {resendState === 'sent'
                ? 'Check your inbox'
                : resendState === 'sending'
                  ? 'Sending…'
                  : 'Resend verification email'}
            </button>

            <VerifyCodeForm
              email={resendEmail}
              onVerified={() => {
                setStatus('success')
                setMessage('Email verified. You can sign in now.')
              }}
            />
          </div>
        )}
      </div>
    </div>
  )
}
