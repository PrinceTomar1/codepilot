import { useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { baseURL } from '../api/client'
import VerifyCodeForm from '../components/VerifyCodeForm'
import { useAuth } from '../context/AuthContext'
import { getErrorMessage } from '../lib/utils'

export default function RegisterPage() {
  const { register } = useAuth()

  const [name, setName] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [confirmationMessage, setConfirmationMessage] = useState<
    string | null
  >(null)
  const [verified, setVerified] = useState(false)

  const validate = (): string | null => {
    if (!name.trim()) return 'Please enter your name.'
    if (!email.trim()) return 'Please enter your email.'
    if (password.length < 8) {
      return 'Password must be at least 8 characters.'
    }
    if (password !== confirmPassword) return 'Passwords do not match.'
    return null
  }

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault()
    const validationError = validate()
    if (validationError) {
      setError(validationError)
      return
    }
    setError(null)
    setIsSubmitting(true)
    try {
      const message = await register({
        name: name.trim(),
        email: email.trim(),
        password,
      })
      setConfirmationMessage(message)
    } catch (err) {
      setError(getErrorMessage(err))
    } finally {
      setIsSubmitting(false)
    }
  }

  if (confirmationMessage) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-slate-950 px-4">
        <div className="w-full max-w-sm text-center">
          <span className="text-3xl">{verified ? '✅' : '📬'}</span>
          <h1 className="mt-4 text-xl font-semibold text-slate-100">
            {verified ? 'Email verified' : 'Check your email'}
          </h1>
          <p className="mt-2 text-sm text-slate-400">
            {verified
              ? 'You can sign in now.'
              : confirmationMessage}
          </p>

          {!verified && (
            <div className="mt-6">
              <VerifyCodeForm email={email} onVerified={() => setVerified(true)} />
            </div>
          )}

          <Link
            to="/login"
            className="mt-6 inline-block font-medium text-brand-400 hover:text-brand-300"
          >
            Back to sign in
          </Link>
        </div>
      </div>
    )
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-slate-950 px-4">
      <div className="w-full max-w-sm">
        <div className="mb-8 flex flex-col items-center gap-2 text-center">
          <span className="text-3xl">🧭</span>
          <h1 className="text-xl font-semibold text-slate-100">
            Create your CodePilot account
          </h1>
          <p className="text-sm text-slate-500">
            Index a repo and start asking questions in minutes.
          </p>
        </div>

        <form
          onSubmit={handleSubmit}
          className="card space-y-4 p-6"
          noValidate
        >
          <div>
            <label htmlFor="name" className="label">
              Name
            </label>
            <input
              id="name"
              autoComplete="name"
              className="input"
              placeholder="Ada Lovelace"
              value={name}
              onChange={(e) => setName(e.target.value)}
            />
          </div>

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
              autoComplete="new-password"
              className="input"
              placeholder="At least 8 characters"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
            />
          </div>

          <div>
            <label htmlFor="confirmPassword" className="label">
              Confirm password
            </label>
            <input
              id="confirmPassword"
              type="password"
              autoComplete="new-password"
              className="input"
              placeholder="••••••••"
              value={confirmPassword}
              onChange={(e) => setConfirmPassword(e.target.value)}
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
            disabled={isSubmitting}
          >
            {isSubmitting ? 'Creating account…' : 'Create account'}
          </button>

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

        <p className="mt-5 text-center text-sm text-slate-500">
          Already have an account?{' '}
          <Link to="/login" className="font-medium text-brand-400 hover:text-brand-300">
            Sign in
          </Link>
        </p>
      </div>
    </div>
  )
}
