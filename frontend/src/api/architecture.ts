import { useQuery } from '@tanstack/react-query'
import { apiClient } from './client'
import type { ArchitectureGraph } from '../types'

const architectureKey = (repoId: string) => ['repositories', repoId, 'architecture'] as const

async function fetchArchitecture(repoId: string): Promise<ArchitectureGraph> {
  const { data } = await apiClient.get<ArchitectureGraph>(
    `/repositories/${repoId}/architecture`,
  )
  return data
}

export function useArchitecture(repoId: string | undefined) {
  return useQuery({
    queryKey: architectureKey(repoId ?? ''),
    queryFn: () => fetchArchitecture(repoId as string),
    enabled: Boolean(repoId),
    staleTime: 5 * 60 * 1000,
  })
}
