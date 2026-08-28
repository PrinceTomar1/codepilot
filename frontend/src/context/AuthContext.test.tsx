import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AuthProvider, useAuth } from './AuthContext'
import { tokenStore, UNAUTHORIZED_EVENT } from '../api/client'
import * as authApi from '../api/auth'

const sampleUser = { id: 'u1', email: 'dev@example.com', name: 'Dev' }

/** Small harness so tests can drive useAuth()'s actions and observe its state via the DOM,
 * matching how the real app consumes the context (no direct hook-internals access needed). */
function Harness() {
  const auth = useAuth()
  return (
    <div>
      <p data-testid="state">
        {auth.isInitializing ? 'initializing' : auth.isAuthenticated ? 'authed' : 'anonymous'}
      </p>
      <p data-testid="email">{auth.user?.email ?? ''}</p>
      <button onClick={() => auth.login({ email: 'dev@example.com', password: 'pw' })}>
        login
      </button>
      <button onClick={() => auth.loginWithToken('a-jwt').catch(() => {})}>
        loginWithToken
      </button>
      <button onClick={() => auth.register({ email: 'x@example.com', password: 'pw', name: 'X' })}>
        register
      </button>
      <button onClick={() => auth.logout()}>logout</button>
    </div>
  )
}

function renderHarness() {
  return render(
    <AuthProvider>
      <Harness />
    </AuthProvider>,
  )
}

beforeEach(() => {
  tokenStore.set(null)
  localStorage.clear()
})

afterEach(() => {
  tokenStore.set(null)
  localStorage.clear()
})

describe('AuthProvider', () => {
  it('starts anonymous with no existing session', async () => {
    renderHarness()
    await waitFor(() => expect(screen.getByTestId('state')).toHaveTextContent('anonymous'))
  })

  it('hydrates an existing session from tokenStore + localStorage on mount', async () => {
    tokenStore.set('existing-token')
    localStorage.setItem('codepilot.user', JSON.stringify(sampleUser))

    renderHarness()

    await waitFor(() => expect(screen.getByTestId('state')).toHaveTextContent('authed'))
    expect(screen.getByTestId('email')).toHaveTextContent('dev@example.com')
  })

  it('login() persists the token and user on success', async () => {
    vi.spyOn(authApi, 'login').mockResolvedValue({ token: 'new-token', user: sampleUser })
    const user = userEvent.setup()
    renderHarness()
    await waitFor(() => expect(screen.getByTestId('state')).toHaveTextContent('anonymous'))

    await user.click(screen.getByText('login'))

    await waitFor(() => expect(screen.getByTestId('state')).toHaveTextContent('authed'))
    expect(tokenStore.get()).toBe('new-token')
    expect(JSON.parse(localStorage.getItem('codepilot.user')!)).toEqual(sampleUser)
  })

  it('loginWithToken() sets the token, fetches the user, and persists on success', async () => {
    vi.spyOn(authApi, 'getCurrentUser').mockResolvedValue(sampleUser)
    const user = userEvent.setup()
    renderHarness()
    await waitFor(() => expect(screen.getByTestId('state')).toHaveTextContent('anonymous'))

    await user.click(screen.getByText('loginWithToken'))

    await waitFor(() => expect(screen.getByTestId('state')).toHaveTextContent('authed'))
    expect(tokenStore.get()).toBe('a-jwt')
  })

  it('loginWithToken() rolls back the token if fetching the user fails', async () => {
    vi.spyOn(authApi, 'getCurrentUser').mockRejectedValue(new Error('invalid token'))
    const user = userEvent.setup()
    renderHarness()
    await waitFor(() => expect(screen.getByTestId('state')).toHaveTextContent('anonymous'))

    await user.click(screen.getByText('loginWithToken'))

    await waitFor(() => expect(tokenStore.get()).toBeNull())
    expect(screen.getByTestId('state')).toHaveTextContent('anonymous')
  })

  it('register() does not log the user in -- it only returns the confirmation message', async () => {
    vi.spyOn(authApi, 'register').mockResolvedValue({
      user: sampleUser,
      message: 'Account created. Check your email.',
    })
    const user = userEvent.setup()
    renderHarness()

    await user.click(screen.getByText('register'))

    // register() itself doesn't touch auth state -- this app requires email verification first.
    expect(screen.getByTestId('state')).toHaveTextContent('anonymous')
  })

  it('logout() clears the session', async () => {
    vi.spyOn(authApi, 'login').mockResolvedValue({ token: 'new-token', user: sampleUser })
    const user = userEvent.setup()
    renderHarness()
    await user.click(screen.getByText('login'))
    await waitFor(() => expect(screen.getByTestId('state')).toHaveTextContent('authed'))

    await user.click(screen.getByText('logout'))

    expect(screen.getByTestId('state')).toHaveTextContent('anonymous')
    expect(tokenStore.get()).toBeNull()
    expect(localStorage.getItem('codepilot.user')).toBeNull()
  })

  it('clears the session automatically when the app-wide UNAUTHORIZED_EVENT fires', async () => {
    vi.spyOn(authApi, 'login').mockResolvedValue({ token: 'new-token', user: sampleUser })
    const user = userEvent.setup()
    renderHarness()
    await user.click(screen.getByText('login'))
    await waitFor(() => expect(screen.getByTestId('state')).toHaveTextContent('authed'))

    act(() => {
      window.dispatchEvent(new CustomEvent(UNAUTHORIZED_EVENT))
    })

    expect(screen.getByTestId('state')).toHaveTextContent('anonymous')
    expect(tokenStore.get()).toBeNull()
  })
})
