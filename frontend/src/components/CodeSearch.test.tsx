import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import CodeSearch from './CodeSearch'
import { apiClient } from '../api/client'
import type { SearchResult } from '../types'

function renderWithQueryClient(ui: React.ReactElement) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>)
}

const exactResult: SearchResult = {
  filePath: 'src/auth/JwtService.java',
  language: 'java',
  startLine: 12,
  endLine: 30,
  snippet: 'public String generateToken(UUID userId, String email) { ... }',
  symbolName: 'generateToken',
  matchType: 'exact',
  relevanceScore: null,
}

const similarityResult: SearchResult = {
  filePath: 'src/services/db.py',
  language: 'python',
  startLine: 1,
  endLine: 10,
  snippet: 'def get_connection(): ...',
  symbolName: 'get_connection',
  matchType: 'similarity',
  relevanceScore: 0.73,
}

describe('CodeSearch', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('shows an idle empty state before any search is submitted', () => {
    renderWithQueryClient(<CodeSearch repoId="repo-1" />)
    expect(screen.getByText('Search this codebase directly')).toBeInTheDocument()
  })

  it('does not submit for a blank query', () => {
    const post = vi.spyOn(apiClient, 'post').mockResolvedValue({ data: { results: [] } })
    renderWithQueryClient(<CodeSearch repoId="repo-1" />)

    expect(screen.getByRole('button', { name: 'Search' })).toBeDisabled()
    expect(post).not.toHaveBeenCalled()
  })

  it('posts the query and renders results with correct match-type labels', async () => {
    const post = vi.spyOn(apiClient, 'post').mockResolvedValue({
      data: { results: [exactResult, similarityResult] },
    })
    const user = userEvent.setup()

    renderWithQueryClient(<CodeSearch repoId="repo-1" />)
    await user.type(
      screen.getByPlaceholderText(/search for a function/i),
      'JWT validation',
    )
    await user.click(screen.getByRole('button', { name: 'Search' }))

    expect(post).toHaveBeenCalledWith('/repositories/repo-1/search', { query: 'JWT validation' })
    expect(await screen.findByText('src/auth/JwtService.java:12-30')).toBeInTheDocument()
    expect(screen.getByText('exact match')).toBeInTheDocument()
    expect(screen.getByText('73% match')).toBeInTheDocument()
  })

  it('shows a not-found empty state when the search returns no results', async () => {
    vi.spyOn(apiClient, 'post').mockResolvedValue({ data: { results: [] } })
    const user = userEvent.setup()

    renderWithQueryClient(<CodeSearch repoId="repo-1" />)
    await user.type(screen.getByPlaceholderText(/search for a function/i), 'nonexistent thing')
    await user.click(screen.getByRole('button', { name: 'Search' }))

    expect(await screen.findByText('No matches found')).toBeInTheDocument()
  })

  it('shows the backend error message when the search request fails', async () => {
    vi.spyOn(apiClient, 'post').mockRejectedValue({
      message: 'Repository not found',
      status: 404,
    })
    const user = userEvent.setup()

    renderWithQueryClient(<CodeSearch repoId="repo-1" />)
    await user.type(screen.getByPlaceholderText(/search for a function/i), 'anything')
    await user.click(screen.getByRole('button', { name: 'Search' }))

    expect(await screen.findByText('Repository not found')).toBeInTheDocument()
  })
})
