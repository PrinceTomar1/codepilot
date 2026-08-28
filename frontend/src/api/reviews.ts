import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiClient } from './client'
import type { ReviewDetail, ReviewSummary } from '../types'

const reviewsKey = (repoId: string) => ['repositories', repoId, 'reviews'] as const
const reviewDetailKey = (reviewId: string) => ['reviews', reviewId] as const

async function fetchReviews(repoId: string): Promise<ReviewSummary[]> {
  const { data } = await apiClient.get<ReviewSummary[]>(
    `/repositories/${repoId}/reviews`,
  )
  return data
}

async function fetchReviewDetail(reviewId: string): Promise<ReviewDetail> {
  const { data } = await apiClient.get<ReviewDetail>(`/reviews/${reviewId}`)
  return data
}

export function useReviews(repoId: string | undefined) {
  return useQuery({
    queryKey: reviewsKey(repoId ?? ''),
    queryFn: () => fetchReviews(repoId as string),
    enabled: Boolean(repoId),
    // The review this list is waiting on runs asynchronously on the backend (a 4-agent AI review
    // takes real time) -- without polling, a just-triggered review would never appear until the
    // user manually reloads the page. Cheap for a small per-repo list.
    refetchInterval: 5000,
  })
}

export function useReviewDetail(reviewId: string | undefined) {
  return useQuery({
    queryKey: reviewDetailKey(reviewId ?? ''),
    queryFn: () => fetchReviewDetail(reviewId as string),
    enabled: Boolean(reviewId),
  })
}

interface TriggerReviewResponse {
  pullRequestId: string
  status: string
}

/** Starts a review for a PR that hasn't arrived via webhook yet -- webhook registration on
 * GitHub's side isn't automated, so this is the only way to ever get a review for most
 * repositories connected through this app. The review itself runs asynchronously on the backend;
 * this resolves as soon as it's queued, not once findings are ready. */
export function useTriggerReview(repoId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (prNumber: number) => {
      const { data } = await apiClient.post<TriggerReviewResponse>(
        `/repositories/${repoId}/pull-requests/${prNumber}/review`,
      )
      return data
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: reviewsKey(repoId) })
    },
  })
}
