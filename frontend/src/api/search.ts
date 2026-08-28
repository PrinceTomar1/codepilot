import { useMutation } from '@tanstack/react-query'
import { apiClient } from './client'
import type { SearchResult } from '../types'

interface SearchResponse {
  results: SearchResult[]
}

/** A dedicated search mutation (not useQuery): the search term is free-typed user input driven
 * by an explicit submit, not something to auto-fetch/cache on mount the way a repo's fixed data
 * (architecture, reviews) is. No LLM call on the backend -- this is direct retrieval, so it's
 * fast and doesn't touch the scarce Gemini quota. */
export function useCodeSearch(repoId: string) {
  return useMutation({
    mutationFn: async (query: string) => {
      const { data } = await apiClient.post<SearchResponse>(
        `/repositories/${repoId}/search`,
        { query },
      )
      return data.results
    },
  })
}
