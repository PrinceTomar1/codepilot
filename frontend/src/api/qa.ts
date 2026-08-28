import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiClient } from './client'
import type { AskQuestionResponse, QAHistoryEntry } from '../types'

const qaHistoryKey = (repoId: string) => ['repositories', repoId, 'qa-history'] as const

async function fetchQAHistory(repoId: string): Promise<QAHistoryEntry[]> {
  const { data } = await apiClient.get<QAHistoryEntry[]>(
    `/repositories/${repoId}/qa-history`,
  )
  return data
}

async function askQuestion(
  repoId: string,
  question: string,
): Promise<AskQuestionResponse> {
  const { data } = await apiClient.post<AskQuestionResponse>(
    `/repositories/${repoId}/ask`,
    { question },
  )
  return data
}

export function useQAHistory(repoId: string | undefined) {
  return useQuery({
    queryKey: qaHistoryKey(repoId ?? ''),
    queryFn: () => fetchQAHistory(repoId as string),
    enabled: Boolean(repoId),
  })
}

export function useAskQuestion(repoId: string | undefined) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (question: string) => askQuestion(repoId as string, question),
    onSuccess: () => {
      if (repoId) {
        queryClient.invalidateQueries({ queryKey: qaHistoryKey(repoId) })
      }
    },
  })
}
