import {
  useMutation,
  useQuery,
  useQueryClient,
  type UseQueryOptions,
} from '@tanstack/react-query'
import { apiClient } from './client'
import type {
  CreateRepositoryFromGitHubRequest,
  CreateRepositoryRequest,
  GitHubRepoOption,
  Repository,
} from '../types'

const REPOSITORIES_KEY = ['repositories'] as const
const repositoryKey = (id: string) => ['repositories', id] as const

async function fetchRepositories(): Promise<Repository[]> {
  const { data } = await apiClient.get<Repository[]>('/repositories')
  return data
}

async function fetchRepository(id: string): Promise<Repository> {
  const { data } = await apiClient.get<Repository>(`/repositories/${id}`)
  return data
}

async function createRepository(
  payload: CreateRepositoryRequest,
): Promise<Repository> {
  const { data } = await apiClient.post<Repository>('/repositories', payload)
  return data
}

async function fetchAvailableGitHubRepos(): Promise<GitHubRepoOption[]> {
  const { data } = await apiClient.get<GitHubRepoOption[]>(
    '/repositories/github/available',
  )
  return data
}

async function createRepositoryFromGitHub(
  payload: CreateRepositoryFromGitHubRequest,
): Promise<Repository> {
  const { data } = await apiClient.post<Repository>(
    '/repositories/from-github',
    payload,
  )
  return data
}

/** Statuses that mean "indexing is still in progress, keep polling". */
const IN_PROGRESS_STATUSES: Repository['status'][] = ['PENDING', 'INDEXING']

export function useRepositories() {
  return useQuery({
    queryKey: REPOSITORIES_KEY,
    queryFn: fetchRepositories,
    // Any repo still indexing means the dashboard list itself should
    // refresh periodically so status badges update without a manual reload.
    refetchInterval: (query) => {
      const repos = query.state.data
      const stillGoing = repos?.some((r) =>
        IN_PROGRESS_STATUSES.includes(r.status),
      )
      return stillGoing ? 5000 : false
    },
  })
}

export function useRepository(
  id: string | undefined,
  options?: Pick<UseQueryOptions<Repository>, 'enabled'>,
) {
  return useQuery({
    queryKey: repositoryKey(id ?? ''),
    queryFn: () => fetchRepository(id as string),
    enabled: Boolean(id) && (options?.enabled ?? true),
    refetchInterval: (query) => {
      const repo = query.state.data
      return repo && IN_PROGRESS_STATUSES.includes(repo.status) ? 4000 : false
    },
  })
}

export function useCreateRepository() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: createRepository,
    onSuccess: (repo) => {
      queryClient.invalidateQueries({ queryKey: REPOSITORIES_KEY })
      queryClient.setQueryData(repositoryKey(repo.id), repo)
    },
  })
}

/** Only meaningful for users who signed in with GitHub -- callers should gate `enabled` on that. */
export function useAvailableGitHubRepos(enabled: boolean) {
  return useQuery({
    queryKey: ['repositories', 'github', 'available'] as const,
    queryFn: fetchAvailableGitHubRepos,
    enabled,
    retry: false,
  })
}

export function useCreateRepositoryFromGitHub() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: createRepositoryFromGitHub,
    onSuccess: (repo) => {
      queryClient.invalidateQueries({ queryKey: REPOSITORIES_KEY })
      queryClient.setQueryData(repositoryKey(repo.id), repo)
    },
  })
}
