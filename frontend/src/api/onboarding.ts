import { useQuery } from '@tanstack/react-query'
import { apiClient } from './client'
import type { OnboardingDoc } from '../types'

const onboardingKey = (repoId: string) => ['repositories', repoId, 'onboarding'] as const

async function fetchOnboarding(repoId: string): Promise<OnboardingDoc> {
  const { data } = await apiClient.get<OnboardingDoc>(
    `/repositories/${repoId}/onboarding`,
  )
  return data
}

export function useOnboarding(repoId: string | undefined) {
  return useQuery({
    queryKey: onboardingKey(repoId ?? ''),
    queryFn: () => fetchOnboarding(repoId as string),
    enabled: Boolean(repoId),
    // This endpoint is lazily generated server-side on first call and can be
    // slow; don't let a transient timeout spam retries on top of that.
    retry: 1,
    staleTime: 5 * 60 * 1000,
  })
}
