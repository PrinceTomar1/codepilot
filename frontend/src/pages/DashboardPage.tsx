import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import Layout from '../components/Layout'
import RepoCard from '../components/RepoCard'
import ConnectRepoModal from '../components/ConnectRepoModal'
import { useRepositories } from '../api/repositories'
import { useAuth } from '../context/AuthContext'

export default function DashboardPage() {
  const { user } = useAuth()
  const { data: repositories, isLoading, isError } = useRepositories()
  const [modalOpen, setModalOpen] = useState(false)
  const navigate = useNavigate()

  return (
    <Layout>
      <div className="mx-auto max-w-6xl px-6 py-8">
        <div className="mb-8 flex flex-wrap items-center justify-between gap-4">
          <div>
            <h1 className="text-2xl font-semibold text-slate-100">
              Welcome back{user?.name ? `, ${user.name.split(' ')[0]}` : ''}
            </h1>
            <p className="mt-1 text-sm text-slate-500">
              Connect a GitHub repository to index it and start exploring.
            </p>
          </div>
          <button
            type="button"
            className="btn-primary"
            onClick={() => setModalOpen(true)}
          >
            <svg
              xmlns="http://www.w3.org/2000/svg"
              viewBox="0 0 20 20"
              fill="currentColor"
              className="h-4 w-4"
            >
              <path d="M10.75 4.75a.75.75 0 00-1.5 0v4.5h-4.5a.75.75 0 000 1.5h4.5v4.5a.75.75 0 001.5 0v-4.5h4.5a.75.75 0 000-1.5h-4.5v-4.5z" />
            </svg>
            Connect repository
          </button>
        </div>

        {isLoading && (
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {[0, 1, 2].map((i) => (
              <div key={i} className="h-32 animate-pulse rounded-xl bg-slate-900" />
            ))}
          </div>
        )}

        {isError && (
          <div className="card p-8 text-center text-sm text-rose-400">
            Couldn&apos;t load your repositories. Check that the API is
            running and try refreshing.
          </div>
        )}

        {!isLoading && !isError && repositories && repositories.length === 0 && (
          <div className="card flex flex-col items-center gap-3 px-6 py-16 text-center">
            <div className="flex h-14 w-14 items-center justify-center rounded-full bg-brand-600/10 text-3xl">
              🗂️
            </div>
            <h2 className="text-base font-medium text-slate-200">
              No repositories connected yet
            </h2>
            <p className="max-w-sm text-sm text-slate-500">
              Connect your first GitHub repository to let CodePilot index the
              codebase, answer questions, and review pull requests.
            </p>
            <button
              type="button"
              className="btn-primary mt-2"
              onClick={() => setModalOpen(true)}
            >
              Connect your first repository
            </button>
          </div>
        )}

        {!isLoading && !isError && repositories && repositories.length > 0 && (
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {repositories.map((repo) => (
              <RepoCard key={repo.id} repo={repo} />
            ))}
          </div>
        )}
      </div>

      {modalOpen && (
        <ConnectRepoModal
          onClose={() => setModalOpen(false)}
          onConnected={(repositoryId) => {
            setModalOpen(false)
            navigate(`/repositories/${repositoryId}`)
          }}
        />
      )}
    </Layout>
  )
}
