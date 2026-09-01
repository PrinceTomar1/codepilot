import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import Layout from '../components/Layout'
import StatusBadge from '../components/StatusBadge'
import ChatPanel from '../components/ChatPanel'
import ReviewList from '../components/ReviewList'
import ReviewDetail from '../components/ReviewDetail'
import OnboardingView from '../components/OnboardingView'
import ArchitectureGraph from '../components/ArchitectureGraph'
import CodeSearch from '../components/CodeSearch'
import { useRepository } from '../api/repositories'
import { cx } from '../lib/utils'

type Tab = 'ask' | 'search' | 'reviews' | 'onboarding' | 'architecture'

const TABS: { key: Tab; label: string; icon: string }[] = [
  { key: 'ask', label: 'Ask', icon: '💬' },
  { key: 'search', label: 'Code Search', icon: '🔎' },
  { key: 'reviews', label: 'PR Reviews', icon: '🔍' },
  { key: 'onboarding', label: 'Onboarding', icon: '📘' },
  { key: 'architecture', label: 'Architecture', icon: '🗺️' },
]

export default function RepositoryDetailPage() {
  const { id } = useParams<{ id: string }>()
  const { data: repo, isLoading, isError } = useRepository(id)
  const [tab, setTab] = useState<Tab>('ask')
  const [selectedReviewId, setSelectedReviewId] = useState<string | null>(null)

  if (isLoading) {
    return (
      <Layout>
        <div className="flex h-full items-center justify-center">
          <div className="h-6 w-6 animate-spin rounded-full border-2 border-slate-700 border-t-brand-500" />
        </div>
      </Layout>
    )
  }

  if (isError || !repo) {
    return (
      <Layout>
        <div className="mx-auto max-w-lg px-6 py-16 text-center">
          <p className="text-sm font-medium text-rose-400">
            Couldn&apos;t load this repository.
          </p>
          <Link
            to="/dashboard"
            className="mt-4 inline-block text-sm text-brand-400 hover:text-brand-300"
          >
            ← Back to dashboard
          </Link>
        </div>
      </Layout>
    )
  }

  const isReady = repo.status === 'INDEXED'

  return (
    <Layout>
      <div className="flex h-full flex-col">
        <div className="border-b border-slate-800 px-6 py-5">
          <Link
            to="/dashboard"
            className="mb-3 inline-flex items-center gap-1 text-xs text-slate-500 hover:text-slate-300"
          >
            <svg
              xmlns="http://www.w3.org/2000/svg"
              viewBox="0 0 20 20"
              fill="currentColor"
              className="h-3.5 w-3.5"
            >
              <path
                fillRule="evenodd"
                d="M17 10a.75.75 0 01-.75.75H5.612l4.158 3.96a.75.75 0 11-1.04 1.08l-5.5-5.25a.75.75 0 010-1.08l5.5-5.25a.75.75 0 111.04 1.08L5.612 9.25H16.25A.75.75 0 0117 10z"
                clipRule="evenodd"
              />
            </svg>
            All repositories
          </Link>
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div>
              <p className="text-xs font-medium text-slate-500">
                {repo.githubOwner}
              </p>
              <h1 className="text-xl font-semibold text-slate-100">
                {repo.githubRepo}
                {repo.defaultBranch && (
                  <span className="ml-2 rounded-md bg-slate-800 px-2 py-0.5 align-middle text-xs font-medium text-slate-400">
                    {repo.defaultBranch}
                  </span>
                )}
              </h1>
            </div>
            <StatusBadge status={repo.status} />
          </div>

          <div className="mt-5 flex gap-1">
            {TABS.map((t) => (
              <button
                key={t.key}
                type="button"
                onClick={() => setTab(t.key)}
                className={cx(
                  'flex items-center gap-1.5 rounded-lg px-3.5 py-2 text-sm font-medium transition-colors',
                  tab === t.key
                    ? 'bg-slate-800 text-slate-100'
                    : 'text-slate-500 hover:bg-slate-900 hover:text-slate-300',
                )}
              >
                <span>{t.icon}</span>
                {t.label}
              </button>
            ))}
          </div>
        </div>

        <div className="min-h-0 flex-1 overflow-hidden">
          {!isReady ? (
            <NotReadyState status={repo.status} lastIndexError={repo.lastIndexError} />
          ) : (
            <>
              {tab === 'ask' && (
                <div className="mx-auto flex h-full max-w-3xl flex-col px-6 py-4">
                  <ChatPanel repoId={repo.id} />
                </div>
              )}

              {tab === 'search' && <CodeSearch repoId={repo.id} />}

              {tab === 'reviews' && (
                <div className="flex h-full">
                  <div className="w-80 shrink-0 overflow-y-auto border-r border-slate-800">
                    <ReviewList
                      repoId={repo.id}
                      selectedReviewId={selectedReviewId}
                      onSelect={setSelectedReviewId}
                    />
                  </div>
                  <div className="flex-1 overflow-y-auto">
                    {selectedReviewId ? (
                      <ReviewDetail reviewId={selectedReviewId} />
                    ) : (
                      <div className="flex h-full items-center justify-center text-sm text-slate-600">
                        Select a review to see its findings.
                      </div>
                    )}
                  </div>
                </div>
              )}

              {tab === 'onboarding' && (
                <div className="h-full overflow-y-auto">
                  <OnboardingView repoId={repo.id} />
                </div>
              )}

              {tab === 'architecture' && (
                <div className="h-full">
                  <ArchitectureGraph repoId={repo.id} />
                </div>
              )}
            </>
          )}
        </div>
      </div>
    </Layout>
  )
}

/**
 * The backend persists the real exception message (IndexJob.error), but that's Java-exception
 * text, not something to show verbatim to a non-technical user. This translates the couple of
 * causes actually seen in practice into a plain-English explanation with an accurate next step;
 * anything unrecognized still shows the real message (truncated) rather than a generic guess that
 * might be flatly wrong for that specific failure: a repository the size of torvalds/linux failed
 * with a buffer-size error that had nothing to do with the access token, but
 * the UI told everyone to "double-check the token" regardless of the actual cause.
 */
function explainIndexError(raw: string | null): string {
  if (!raw) {
    return "CodePilot couldn't index this repository. Try reconnecting it, or use a smaller repository."
  }
  if (/DataBufferLimitException|Exceeded limit on max bytes/i.test(raw)) {
    return "This repository is too large to index (its file listing alone exceeds what a single GitHub API response can hold) -- very large monorepos aren't supported yet. Try a smaller repository."
  }
  if (/401|403|Bad credentials|token/i.test(raw)) {
    return "CodePilot couldn't index this repository. Double-check the access token has the right scopes and try reconnecting it."
  }
  if (/404|Not Found/i.test(raw)) {
    return "CodePilot couldn't find this repository on GitHub. It may have been renamed, deleted, or made private without updating the connection here."
  }
  return raw.length > 200 ? raw.slice(0, 200) + '…' : raw
}

function NotReadyState({
  status,
  lastIndexError,
}: {
  status: string
  lastIndexError?: string | null
}) {
  const isFailed = status === 'FAILED'
  return (
    <div className="flex h-full flex-col items-center justify-center gap-4 px-6 text-center">
      {isFailed ? (
        <>
          <div className="flex h-14 w-14 items-center justify-center rounded-full bg-rose-500/10 text-3xl">
            ⚠️
          </div>
          <div>
            <p className="text-sm font-medium text-slate-200">
              Indexing failed
            </p>
            <p className="mt-1 max-w-sm text-sm text-slate-500">
              {explainIndexError(lastIndexError ?? null)}
            </p>
          </div>
        </>
      ) : (
        <>
          <div className="h-8 w-8 animate-spin rounded-full border-2 border-slate-700 border-t-brand-500" />
          <div>
            <p className="text-sm font-medium text-slate-200">
              {status === 'PENDING' ? 'Waiting to start indexing…' : 'Indexing in progress…'}
            </p>
            <p className="mt-1 max-w-sm text-sm text-slate-500">
              Ask, PR Reviews and Onboarding unlock once indexing finishes.
              This page updates automatically.
            </p>
          </div>
        </>
      )}
    </div>
  )
}
