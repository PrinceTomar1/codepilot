import { render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ErrorBoundary from './ErrorBoundary'

function Bomb({ message }: { message: string }): never {
  throw new Error(message)
}

describe('ErrorBoundary', () => {
  beforeEach(() => {
    sessionStorage.clear()
    // React logs caught errors to the console by default (helpful in dev, noisy in a test run
    // that's deliberately throwing) -- silence just this test file's expected error logs.
    vi.spyOn(console, 'error').mockImplementation(() => {})
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('renders children normally when nothing throws', () => {
    render(
      <ErrorBoundary>
        <p>all good</p>
      </ErrorBoundary>,
    )
    expect(screen.getByText('all good')).toBeInTheDocument()
  })

  it('reloads once (not an error screen) when a lazy-loaded chunk fails to fetch', () => {
    const reload = vi.fn()
    Object.defineProperty(window, 'location', {
      value: { ...window.location, reload },
      writable: true,
    })

    render(
      <ErrorBoundary>
        <Bomb message="Failed to fetch dynamically imported module: https://example.com/assets/LoginPage-abc123.js" />
      </ErrorBoundary>,
    )

    expect(reload).toHaveBeenCalledOnce()
    expect(sessionStorage.getItem('codepilot.chunk-reload-attempted')).toBe('1')
  })

  it('does not reload-loop: a second chunk error in the same session shows the fallback instead', () => {
    sessionStorage.setItem('codepilot.chunk-reload-attempted', '1')
    const reload = vi.fn()
    Object.defineProperty(window, 'location', {
      value: { ...window.location, reload },
      writable: true,
    })

    render(
      <ErrorBoundary>
        <Bomb message="Failed to fetch dynamically imported module: https://example.com/assets/LoginPage-abc123.js" />
      </ErrorBoundary>,
    )

    expect(reload).not.toHaveBeenCalled()
    expect(screen.getByText('Something went wrong')).toBeInTheDocument()
  })

  it('shows the fallback (not a reload) for an unrelated runtime error', () => {
    const reload = vi.fn()
    Object.defineProperty(window, 'location', {
      value: { ...window.location, reload },
      writable: true,
    })

    render(
      <ErrorBoundary>
        <Bomb message="Cannot read properties of undefined (reading 'foo')" />
      </ErrorBoundary>,
    )

    expect(reload).not.toHaveBeenCalled()
    expect(screen.getByText('Something went wrong')).toBeInTheDocument()
  })
})
