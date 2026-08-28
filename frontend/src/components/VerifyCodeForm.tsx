import { useState, type FormEvent } from 'react'
import { verifyCode } from '../api/auth'
import { getErrorMessage } from '../lib/utils'

/**
 * Alternative to clicking the link in the verification email -- types the 6-digit code from
 * the same email instead. Needs the email as a controlled prop since a bare code alone isn't
 * enough to look up which account it belongs to.
 */
export default function VerifyCodeForm({
  email,
  onVerified,
}: {
  email: string
  onVerified: () => void
}) {
  const [code, setCode] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [isSubmitting, setIsSubmitting] = useState(false)

  const handleSubmit = async (event: FormEvent) => {
    event.preventDefault()
    if (!email.trim()) {
      setError('Enter your email above first.')
      return
    }
    if (!/^\d{6}$/.test(code)) {
      setError('Enter the 6-digit code from your email.')
      return
    }
    setError(null)
    setIsSubmitting(true)
    try {
      await verifyCode(email.trim(), code)
      onVerified()
    } catch (err) {
      setError(getErrorMessage(err))
    } finally {
      setIsSubmitting(false)
    }
  }

  return (
    <form onSubmit={handleSubmit} className="space-y-2 text-left">
      <label htmlFor="verification-code" className="label">
        Or enter the 6-digit code from your email
      </label>
      <div className="flex gap-2">
        <input
          id="verification-code"
          inputMode="numeric"
          autoComplete="one-time-code"
          maxLength={6}
          className="input text-center font-mono tracking-[0.4em]"
          placeholder="123456"
          value={code}
          onChange={(e) => setCode(e.target.value.replace(/\D/g, '').slice(0, 6))}
        />
        <button
          type="submit"
          className="btn-primary shrink-0"
          disabled={isSubmitting || code.length !== 6}
        >
          {isSubmitting ? 'Verifying…' : 'Verify'}
        </button>
      </div>
      {error && (
        <p className="rounded-lg border border-rose-900/50 bg-rose-950/40 px-3 py-2 text-sm text-rose-300">
          {error}
        </p>
      )}
    </form>
  )
}
