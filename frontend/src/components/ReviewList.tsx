import { useState, type FormEvent } from 'react'
import { useReviews, useTriggerReview } from '../api/reviews'
import { formatRelativeDate, getErrorMessage } from '../lib/utils'

interface ReviewListProps {
  repoId: string
  selectedReviewId: string | null
  onSelect: (reviewId: string) => void
}

/** Webhook registration on GitHub's side isn't automated by this app (it has to be configured
 * manually per repo), so without a manual trigger, a connected repository could go forever with
 * "No reviews yet" even though real PRs exist on GitHub -- this is the only way most users will
 * ever see a review. */
export function TriggerReviewForm({ repoId }: { repoId: string }) {
  const [prNumber, setPrNumber] = useState('')
  const trigger = useTriggerReview(repoId)

  const handleSubmit = (e: FormEvent) => {
    e.preventDefault()
    const n = Number(prNumber)
    if (!Number.isInteger(n) || n <= 0) return
    trigger.mutate(n)
  }

  return (
    <form onSubmit={handleSubmit} className="border-b border-slate-800 p-3">
      <label htmlFor="pr-number" className="mb-1.5 block text-xs font-medium text-slate-400">
        Review a pull request
      </label>
      <div className="flex gap-2">
        <input
          id="pr-number"
          type="number"
          min={1}
          inputMode="numeric"
          placeholder="PR #"
          value={prNumber}
          onChange={(e) => setPrNumber(e.target.value)}
          className="w-20 rounded-md border border-slate-700 bg-slate-900 px-2 py-1.5 text-sm text-slate-200 placeholder:text-slate-600 focus:border-brand-500 focus:outline-none"
        />
        <button
          type="submit"
          disabled={trigger.isPending || !prNumber}
          className="flex-1 rounded-md bg-brand-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-brand-500 disabled:cursor-not-allowed disabled:opacity-50"
        >
          {trigger.isPending ? 'Starting review…' : 'Start review'}
        </button>
      </div>
      {trigger.isError && (
        <p className="mt-1.5 text-xs text-rose-400">{getErrorMessage(trigger.error)}</p>
      )}
      {trigger.isSuccess && (
        <p className="mt-1.5 text-xs text-emerald-400">
          Review queued -- it'll appear below once findings are ready.
        </p>
      )}
    </form>
  )
}

export default function ReviewList({
  repoId,
  selectedReviewId,
  onSelect,
}: ReviewListProps) {
  const { data: reviews, isLoading, isError } = useReviews(repoId)

  if (isLoading) {
    return (
      <div className="space-y-2 p-4">
        {[0, 1, 2].map((i) => (
          <div key={i} className="h-16 animate-pulse rounded-xl bg-slate-900" />
        ))}
      </div>
    )
  }

  if (isError) {
    return (
      <div className="p-4 text-sm text-rose-400">
        Couldn&apos;t load PR reviews.
      </div>
    )
  }

  if (!reviews || reviews.length === 0) {
    return (
      <div>
        <TriggerReviewForm repoId={repoId} />
        <div className="flex flex-col items-center justify-center gap-2 px-4 py-16 text-center">
          <div className="flex h-12 w-12 items-center justify-center rounded-full bg-slate-800 text-2xl">
            🔍
          </div>
          <p className="text-sm font-medium text-slate-300">No reviews yet</p>
          <p className="max-w-xs text-sm text-slate-500">
            Enter a PR number above to review it directly, or connect a
            webhook on GitHub for new PRs to be reviewed automatically.
          </p>
        </div>
      </div>
    )
  }

  return (
    <div>
      <TriggerReviewForm repoId={repoId} />
      <ul className="divide-y divide-slate-800">
        {reviews.map((review) => (
          <li key={review.id}>
            <button
              type="button"
              onClick={() => onSelect(review.id)}
              className={`w-full px-4 py-3.5 text-left transition-colors hover:bg-slate-900 ${
                selectedReviewId === review.id ? 'bg-slate-900' : ''
              }`}
            >
              <div className="flex items-center justify-between gap-2">
                <span className="font-mono text-xs text-brand-400">
                  #{review.prNumber}
                </span>
                <span className="text-xs text-slate-600">
                  {formatRelativeDate(review.createdAt)}
                </span>
              </div>
              <p className="mt-1 truncate text-sm font-medium text-slate-200">
                {review.prTitle}
              </p>
              <p className="mt-1 line-clamp-2 text-xs text-slate-500">
                {review.summary}
              </p>
            </button>
          </li>
        ))}
      </ul>
    </div>
  )
}
