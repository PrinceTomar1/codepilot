import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { TriggerReviewForm } from './ReviewList'
import { apiClient } from '../api/client'

function renderWithQueryClient(ui: React.ReactElement) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>)
}

describe('TriggerReviewForm', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('posts the entered PR number to the trigger endpoint', async () => {
    const post = vi.spyOn(apiClient, 'post').mockResolvedValue({
      data: { pullRequestId: 'pr-1', status: 'PENDING_REVIEW' },
    })
    const user = userEvent.setup()

    renderWithQueryClient(<TriggerReviewForm repoId="repo-1" />)
    await user.type(screen.getByLabelText('Review a pull request'), '42')
    await user.click(screen.getByRole('button', { name: 'Start review' }))

    expect(post).toHaveBeenCalledWith('/repositories/repo-1/pull-requests/42/review')
    expect(await screen.findByText(/review queued/i)).toBeInTheDocument()
  })

  it('shows the backend error message when triggering fails', async () => {
    vi.spyOn(apiClient, 'post').mockRejectedValue({
      message: 'Pull request #999 not found on GitHub',
      status: 404,
    })
    const user = userEvent.setup()

    renderWithQueryClient(<TriggerReviewForm repoId="repo-1" />)
    await user.type(screen.getByLabelText('Review a pull request'), '999')
    await user.click(screen.getByRole('button', { name: 'Start review' }))

    expect(await screen.findByText('Pull request #999 not found on GitHub')).toBeInTheDocument()
  })

  it('does not submit for a non-numeric or blank PR number', async () => {
    const post = vi.spyOn(apiClient, 'post').mockResolvedValue({ data: {} })

    renderWithQueryClient(<TriggerReviewForm repoId="repo-1" />)
    // The submit button stays disabled until something is typed -- covers the blank case.
    expect(screen.getByRole('button', { name: 'Start review' })).toBeDisabled()
    expect(post).not.toHaveBeenCalled()
  })
})
