export type RepositoryStatus = 'PENDING' | 'INDEXING' | 'INDEXED' | 'FAILED'

export interface Repository {
  id: string
  githubOwner: string
  githubRepo: string
  defaultBranch: string | null
  status: RepositoryStatus
  indexedAt: string | null
  createdAt: string
  lastIndexError: string | null
}

export interface CreateRepositoryRequest {
  githubOwner: string
  githubRepo: string
  accessToken: string
}

export interface GitHubRepoOption {
  owner: string
  name: string
  isPrivate: boolean
  defaultBranch: string | null
}

export interface CreateRepositoryFromGitHubRequest {
  githubOwner: string
  githubRepo: string
}
