import { Component, type ReactNode } from 'react'

const RELOAD_FLAG_KEY = 'codepilot.chunk-reload-attempted'

/**
 * Route-level code splitting (App.tsx) means each deploy ships new JS chunk filenames. A tab left
 * open across a deploy -- or one that loaded an HTML shell just before a newer deploy replaced the
 * chunks it references -- gets a 404 the moment it tries to lazy-load a route, and React has no
 * built-in recovery for that: without a boundary here, the failed dynamic import throws, React
 * unmounts everything below it, and the visitor sees a permanently blank page with no way back
 * short of knowing to hard-refresh. This catches exactly that class of error and reloads once
 * automatically (a plain reload re-fetches the current index.html, which references the current
 * chunks, so it self-heals). A one-shot sessionStorage flag stops it from reload-looping if the
 * error turns out to be something else entirely.
 */
function isChunkLoadError(error: unknown): boolean {
  const message = error instanceof Error ? error.message : String(error)
  return /Failed to fetch dynamically imported module|Importing a module script failed|error loading dynamically imported module/i.test(
    message,
  )
}

interface State {
  hasError: boolean
}

export default class ErrorBoundary extends Component<{ children: ReactNode }, State> {
  state: State = { hasError: false }

  static getDerivedStateFromError(): State {
    return { hasError: true }
  }

  componentDidCatch(error: unknown) {
    if (isChunkLoadError(error) && !sessionStorage.getItem(RELOAD_FLAG_KEY)) {
      sessionStorage.setItem(RELOAD_FLAG_KEY, '1')
      window.location.reload()
    }
  }

  render() {
    if (this.state.hasError) {
      return (
        <div className="flex min-h-screen flex-col items-center justify-center gap-4 bg-slate-950 px-4 text-center">
          <span className="text-3xl">⚠️</span>
          <div>
            <p className="text-sm font-medium text-slate-200">Something went wrong</p>
            <p className="mt-1 max-w-sm text-sm text-slate-500">
              Try refreshing the page. If that doesn&apos;t help, please try again in a moment.
            </p>
          </div>
          <button
            type="button"
            onClick={() => window.location.reload()}
            className="btn-primary"
          >
            Refresh
          </button>
        </div>
      )
    }
    return this.props.children
  }
}
