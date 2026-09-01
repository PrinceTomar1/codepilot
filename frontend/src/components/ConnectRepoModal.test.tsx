import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import ConnectRepoModal from './ConnectRepoModal'
import { apiClient } from '../api/client'

function renderWithQueryClient(ui: React.ReactElement) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>)
}

describe('ConnectRepoModal - add someone else\'s repository', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('lets a signed-in-with-GitHub user type an arbitrary owner/repo and connects it via createFromGitHub', async () => {
    vi.spyOn(apiClient, 'get').mockResolvedValue({ data: [] }) // picker: no repos of their own
    const post = vi.spyOn(apiClient, 'post').mockResolvedValue({
      data: { id: 'repo-1', githubOwner: 'facebook', githubRepo: 'react', status: 'PENDING' },
    })
    const onConnected = vi.fn()
    const user = userEvent.setup()

    renderWithQueryClient(<ConnectRepoModal onClose={vi.fn()} onConnected={onConnected} />)

    // Picker starts empty (no repos of their own) -- switch to "any" mode.
    await user.click(await screen.findByText("Add someone else's repository"))

    await user.type(screen.getByLabelText('Owner'), 'facebook')
    await user.type(screen.getByLabelText('Repository'), 'react')
    await user.click(screen.getByRole('button', { name: 'Connect repository' }))

    // Reuses the SAME endpoint as picking from "my repos" -- the user's existing GitHub OAuth
    // token, not a newly-pasted PAT, which is exactly why no token field is shown for this mode.
    expect(post).toHaveBeenCalledWith('/repositories/from-github', {
      githubOwner: 'facebook',
      githubRepo: 'react',
    })
    expect(onConnected).toHaveBeenCalledWith('repo-1')
  })

  it('does not require a personal access token in "any" mode, unlike manual mode', async () => {
    vi.spyOn(apiClient, 'get').mockResolvedValue({ data: [] })
    renderWithQueryClient(<ConnectRepoModal onClose={vi.fn()} onConnected={vi.fn()} />)

    await userEvent.setup().click(await screen.findByText("Add someone else's repository"))

    expect(screen.queryByLabelText(/personal access token/i)).not.toBeInTheDocument()
  })

  it('stays reachable even when the "my repos" picker fails to load', async () => {
    // Real bug: a stale/invalid GitHub token makes the picker request fail, and "Add someone
    // else's repository" used to live inside a block that only rendered on a SUCCESSFUL picker
    // fetch -- so the one way to connect any other repo disappeared right when the picker (which
    // needs the exact same GitHub token) was the thing failing. It must render on error too.
    vi.spyOn(apiClient, 'get').mockRejectedValue(new Error('GitHub token invalid'))
    const user = userEvent.setup()

    renderWithQueryClient(<ConnectRepoModal onClose={vi.fn()} onConnected={vi.fn()} />)

    await user.click(await screen.findByText("Add someone else's repository"))

    expect(screen.getByLabelText('Owner')).toBeInTheDocument()
    expect(screen.getByLabelText('Repository')).toBeInTheDocument()
  })

  it('shows a validation error when submitting with a blank field', async () => {
    vi.spyOn(apiClient, 'get').mockResolvedValue({ data: [] })
    const post = vi.spyOn(apiClient, 'post')
    const user = userEvent.setup()

    renderWithQueryClient(<ConnectRepoModal onClose={vi.fn()} onConnected={vi.fn()} />)
    await user.click(await screen.findByText("Add someone else's repository"))
    await user.type(screen.getByLabelText('Owner'), 'facebook')
    await user.click(screen.getByRole('button', { name: 'Connect repository' }))

    expect(await screen.findByText('Both fields are required.')).toBeInTheDocument()
    expect(post).not.toHaveBeenCalled()
  })

  it('offers a fallback to the token form when the account has no GitHub OAuth token', async () => {
    // Real bug: this mode had no way to reach the token-based form on a "Sign in with GitHub
    // first" failure -- only "Back to my repos" was offered, which just leads back to the
    // picker's own (different) error state instead of the token form itself.
    vi.spyOn(apiClient, 'get').mockResolvedValue({ data: [] })
    vi.spyOn(apiClient, 'post').mockRejectedValue({ message: 'Sign in with GitHub first.' })
    const user = userEvent.setup()

    renderWithQueryClient(<ConnectRepoModal onClose={vi.fn()} onConnected={vi.fn()} />)
    await user.click(await screen.findByText("Add someone else's repository"))
    await user.type(screen.getByLabelText('Owner'), 'Manas1111')
    await user.type(screen.getByLabelText('Repository'), 'smart-crop-advisor')
    await user.click(screen.getByRole('button', { name: 'Connect repository' }))

    expect(await screen.findByText('Sign in with GitHub first.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Connect with a token instead' })).toBeInTheDocument()
  })

  it('carries the typed owner/repo over into the token form instead of making the user retype it', async () => {
    vi.spyOn(apiClient, 'get').mockResolvedValue({ data: [] })
    vi.spyOn(apiClient, 'post').mockRejectedValue({ message: 'Sign in with GitHub first.' })
    const user = userEvent.setup()

    renderWithQueryClient(<ConnectRepoModal onClose={vi.fn()} onConnected={vi.fn()} />)
    await user.click(await screen.findByText("Add someone else's repository"))
    await user.type(screen.getByLabelText('Owner'), 'Manas1111')
    await user.type(screen.getByLabelText('Repository'), 'smart-crop-advisor')
    await user.click(screen.getByRole('button', { name: 'Connect repository' }))
    await user.click(await screen.findByRole('button', { name: 'Connect with a token instead' }))

    expect(screen.getByLabelText('Owner')).toHaveValue('Manas1111')
    expect(screen.getByLabelText('Repository')).toHaveValue('smart-crop-advisor')
    expect(screen.getByLabelText(/personal access token/i)).toBeInTheDocument()
  })
})
