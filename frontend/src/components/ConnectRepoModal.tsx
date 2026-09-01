import { useState, type FormEvent } from 'react'
import {
  useAvailableGitHubRepos,
  useCreateRepository,
  useCreateRepositoryFromGitHub,
} from '../api/repositories'
import { getErrorMessage } from '../lib/utils'

interface ConnectRepoModalProps {
  onClose: () => void
  onConnected: (repositoryId: string) => void
}

export default function ConnectRepoModal({
  onClose,
  onConnected,
}: ConnectRepoModalProps) {
  // Default to the picker -- if the user never signed in with GitHub, the availability request
  // fails cleanly (400) and the empty/error state below offers the manual form instead, rather
  // than needing to know in advance whether they have a GitHub connection.
  const [mode, setMode] = useState<'picker' | 'any' | 'manual'>('picker')
  const [selectedRepo, setSelectedRepo] = useState<string | null>(null)

  // "any" mode: same createFromGitHub call as the picker (reuses the signed-in user's own stored
  // OAuth token -- never a new token to paste), but for a repo typed by hand instead of chosen
  // from "my repos". A GitHub token authenticates the CALLER, not a claim of ownership -- it can
  // already read any public repo (and any private one the account collaborates on) regardless of
  // who owns it, so this needed no new backend capability, just a way to reach it from the UI.
  const [anyOwner, setAnyOwner] = useState('')
  const [anyRepo, setAnyRepo] = useState('')

  const [githubOwner, setGithubOwner] = useState('')
  const [githubRepo, setGithubRepo] = useState('')
  const [accessToken, setAccessToken] = useState('')
  const [formError, setFormError] = useState<string | null>(null)

  const availableRepos = useAvailableGitHubRepos(mode === 'picker')
  const createRepository = useCreateRepository()
  const createFromGitHub = useCreateRepositoryFromGitHub()

  const handlePickerConnect = async () => {
    if (!selectedRepo) return
    setFormError(null)
    const [owner, name] = selectedRepo.split('/')
    try {
      const repo = await createFromGitHub.mutateAsync({
        githubOwner: owner,
        githubRepo: name,
      })
      onConnected(repo.id)
    } catch (err) {
      setFormError(getErrorMessage(err))
    }
  }

  const handleAnySubmit = async (event: FormEvent) => {
    event.preventDefault()
    setFormError(null)

    if (!anyOwner.trim() || !anyRepo.trim()) {
      setFormError('Both fields are required.')
      return
    }

    try {
      const repo = await createFromGitHub.mutateAsync({
        githubOwner: anyOwner.trim(),
        githubRepo: anyRepo.trim(),
      })
      onConnected(repo.id)
    } catch (err) {
      setFormError(getErrorMessage(err))
    }
  }

  const handleManualSubmit = async (event: FormEvent) => {
    event.preventDefault()
    setFormError(null)

    if (!githubOwner.trim() || !githubRepo.trim() || !accessToken.trim()) {
      setFormError('All fields are required.')
      return
    }

    try {
      const repo = await createRepository.mutateAsync({
        githubOwner: githubOwner.trim(),
        githubRepo: githubRepo.trim(),
        accessToken: accessToken.trim(),
      })
      onConnected(repo.id)
    } catch (err) {
      setFormError(getErrorMessage(err))
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/70 p-4 backdrop-blur-sm">
      <div className="w-full max-w-md rounded-2xl border border-slate-800 bg-slate-900 shadow-2xl">
        <div className="flex items-center justify-between border-b border-slate-800 px-6 py-4">
          <h2 className="text-lg font-semibold text-slate-100">
            Connect a repository
          </h2>
          <button
            type="button"
            onClick={onClose}
            className="rounded-md p-1 text-slate-500 hover:bg-slate-800 hover:text-slate-200"
            aria-label="Close"
          >
            <svg
              xmlns="http://www.w3.org/2000/svg"
              viewBox="0 0 20 20"
              fill="currentColor"
              className="h-5 w-5"
            >
              <path d="M6.28 5.22a.75.75 0 00-1.06 1.06L8.94 10l-3.72 3.72a.75.75 0 101.06 1.06L10 11.06l3.72 3.72a.75.75 0 101.06-1.06L11.06 10l3.72-3.72a.75.75 0 00-1.06-1.06L10 8.94 6.28 5.22z" />
            </svg>
          </button>
        </div>

        {mode === 'picker' ? (
          <div className="flex flex-col gap-4 px-6 py-5">
            {availableRepos.isLoading && (
              <div className="space-y-2">
                {[0, 1, 2].map((i) => (
                  <div key={i} className="h-12 animate-pulse rounded-lg bg-slate-800" />
                ))}
              </div>
            )}

            {availableRepos.isError && (
              <div className="rounded-lg border border-slate-800 bg-slate-950/50 p-4 text-center">
                <p className="text-sm text-slate-400">
                  {getErrorMessage(availableRepos.error)}
                </p>
                <button
                  type="button"
                  className="btn-secondary mt-3"
                  onClick={() => setMode('manual')}
                >
                  Connect with a token instead
                </button>
              </div>
            )}

            {availableRepos.data && availableRepos.data.length === 0 && (
              <p className="text-sm text-slate-500">
                No repositories found on your GitHub account.
              </p>
            )}

            {availableRepos.data && availableRepos.data.length > 0 && (
              <div className="max-h-72 space-y-1.5 overflow-y-auto">
                {availableRepos.data.map((r) => {
                  const full = `${r.owner}/${r.name}`
                  return (
                    <button
                      key={full}
                      type="button"
                      onClick={() => setSelectedRepo(full)}
                      className={`flex w-full items-center justify-between rounded-lg border px-3 py-2.5 text-left text-sm transition-colors ${
                        selectedRepo === full
                          ? 'border-brand-500 bg-brand-500/10 text-slate-100'
                          : 'border-slate-800 bg-slate-950/50 text-slate-300 hover:border-slate-700'
                      }`}
                    >
                      <span className="truncate font-mono">{full}</span>
                      {r.isPrivate && (
                        <span className="ml-2 shrink-0 rounded-full bg-slate-800 px-2 py-0.5 text-[11px] text-slate-500">
                          Private
                        </span>
                      )}
                    </button>
                  )
                })}
              </div>
            )}

            {formError && (
              <p className="rounded-lg border border-rose-900/50 bg-rose-950/40 px-3 py-2 text-sm text-rose-300">
                {formError}
              </p>
            )}

            {/* Always reachable, regardless of whether "my repos" loaded, errored, or came back
                empty -- this used to live inside `{availableRepos.data && (...)}`, so a failed
                picker fetch (e.g. a stale GitHub token) hid the one way to connect ANY other
                repo, not just the ones affected by that failure -- a user whose account-level
                GitHub token had gone stale would otherwise see only their own known repos, with
                no path to add someone else's, because the picker request was erroring. */}
            <div className="mt-1 flex items-center justify-between gap-2">
              <button
                type="button"
                className="text-xs text-slate-500 underline underline-offset-2 hover:text-slate-300"
                onClick={() => setMode('any')}
              >
                Add someone else&apos;s repository
              </button>
              <div className="flex gap-2">
                <button type="button" className="btn-ghost" onClick={onClose}>
                  Cancel
                </button>
                {availableRepos.data && availableRepos.data.length > 0 && (
                  <button
                    type="button"
                    className="btn-primary"
                    disabled={!selectedRepo || createFromGitHub.isPending}
                    onClick={handlePickerConnect}
                  >
                    {createFromGitHub.isPending ? 'Connecting…' : 'Connect repository'}
                  </button>
                )}
              </div>
            </div>
          </div>
        ) : mode === 'any' ? (
          <form onSubmit={handleAnySubmit} className="flex flex-col gap-4 px-6 py-5">
            <p className="text-xs leading-relaxed text-slate-500">
              Connect any public repository (or a private one your GitHub account already has
              access to) using your existing GitHub sign-in -- no separate token needed. This
              works the same way as picking from &quot;my repos&quot;, just for a repository you
              don&apos;t own.
            </p>
            <div className="grid grid-cols-2 gap-3">
              <div>
                <label htmlFor="anyOwner" className="label">
                  Owner
                </label>
                <input
                  id="anyOwner"
                  className="input"
                  placeholder="e.g. facebook"
                  value={anyOwner}
                  onChange={(e) => setAnyOwner(e.target.value)}
                  autoFocus
                />
              </div>
              <div>
                <label htmlFor="anyRepo" className="label">
                  Repository
                </label>
                <input
                  id="anyRepo"
                  className="input"
                  placeholder="e.g. react"
                  value={anyRepo}
                  onChange={(e) => setAnyRepo(e.target.value)}
                />
              </div>
            </div>

            {formError && (
              <p className="rounded-lg border border-rose-900/50 bg-rose-950/40 px-3 py-2 text-sm text-rose-300">
                {formError}
              </p>
            )}

            <div className="mt-1 flex items-center justify-between gap-2">
              <button
                type="button"
                className="text-xs text-slate-500 underline underline-offset-2 hover:text-slate-300"
                onClick={() => setMode('picker')}
              >
                Back to my repos
              </button>
              <div className="flex gap-2">
                <button type="button" className="btn-ghost" onClick={onClose}>
                  Cancel
                </button>
                <button
                  type="submit"
                  className="btn-primary"
                  disabled={createFromGitHub.isPending}
                >
                  {createFromGitHub.isPending ? 'Connecting…' : 'Connect repository'}
                </button>
              </div>
            </div>
          </form>
        ) : (
          <form onSubmit={handleManualSubmit} className="flex flex-col gap-4 px-6 py-5">
            <div className="grid grid-cols-2 gap-3">
              <div>
                <label htmlFor="githubOwner" className="label">
                  Owner
                </label>
                <input
                  id="githubOwner"
                  className="input"
                  placeholder="e.g. octocat"
                  value={githubOwner}
                  onChange={(e) => setGithubOwner(e.target.value)}
                  autoFocus
                />
              </div>
              <div>
                <label htmlFor="githubRepo" className="label">
                  Repository
                </label>
                <input
                  id="githubRepo"
                  className="input"
                  placeholder="e.g. hello-world"
                  value={githubRepo}
                  onChange={(e) => setGithubRepo(e.target.value)}
                />
              </div>
            </div>

            <div>
              <label htmlFor="accessToken" className="label">
                GitHub personal access token
              </label>
              <input
                id="accessToken"
                type="password"
                className="input font-mono"
                placeholder="ghp_••••••••••••••••••••"
                value={accessToken}
                onChange={(e) => setAccessToken(e.target.value)}
              />
              <p className="mt-2 text-xs leading-relaxed text-slate-500">
                Needs <code className="text-slate-400">repo</code> (read) and{' '}
                <code className="text-slate-400">webhook</code> scopes so
                CodePilot can index the codebase and stay in sync with new
                commits. Your token is encrypted at rest on the server and is
                never exposed to other users.
              </p>
            </div>

            {formError && (
              <p className="rounded-lg border border-rose-900/50 bg-rose-950/40 px-3 py-2 text-sm text-rose-300">
                {formError}
              </p>
            )}

            <div className="mt-1 flex items-center justify-between gap-2">
              <button
                type="button"
                className="text-xs text-slate-500 underline underline-offset-2 hover:text-slate-300"
                onClick={() => setMode('picker')}
              >
                Pick from my GitHub repos instead
              </button>
              <div className="flex gap-2">
                <button type="button" className="btn-ghost" onClick={onClose}>
                  Cancel
                </button>
                <button
                  type="submit"
                  className="btn-primary"
                  disabled={createRepository.isPending}
                >
                  {createRepository.isPending && (
                    <svg
                      className="h-4 w-4 animate-spin"
                      viewBox="0 0 24 24"
                      fill="none"
                    >
                      <circle
                        className="opacity-25"
                        cx="12"
                        cy="12"
                        r="10"
                        stroke="currentColor"
                        strokeWidth="4"
                      />
                      <path
                        className="opacity-75"
                        fill="currentColor"
                        d="M4 12a8 8 0 018-8v4a4 4 0 00-4 4H4z"
                      />
                    </svg>
                  )}
                  Connect repository
                </button>
              </div>
            </div>
          </form>
        )}
      </div>
    </div>
  )
}
