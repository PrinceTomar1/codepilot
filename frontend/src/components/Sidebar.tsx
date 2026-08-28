import { NavLink } from 'react-router-dom'
import { useRepositories } from '../api/repositories'
import { useAuth } from '../context/AuthContext'
import { cx, initials, getErrorMessage } from '../lib/utils'

export default function Sidebar() {
  const { data: repositories, isLoading, isError, error } = useRepositories()
  const { user, logout } = useAuth()

  return (
    <aside className="flex h-full w-64 shrink-0 flex-col border-r border-slate-800 bg-slate-950">
      <div className="flex items-center gap-2 border-b border-slate-800 px-5 py-4">
        <span className="text-xl">🧭</span>
        <span className="text-base font-semibold tracking-tight text-slate-100">
          CodePilot
        </span>
      </div>

      <nav className="flex flex-1 flex-col gap-1 overflow-y-auto px-3 py-4">
        <NavLink
          to="/dashboard"
          className={({ isActive }) =>
            cx(
              'flex items-center gap-2 rounded-lg px-3 py-2 text-sm font-medium transition-colors',
              isActive
                ? 'bg-brand-600/15 text-brand-300'
                : 'text-slate-400 hover:bg-slate-900 hover:text-slate-100',
            )
          }
          end
        >
          <svg
            xmlns="http://www.w3.org/2000/svg"
            viewBox="0 0 20 20"
            fill="currentColor"
            className="h-4 w-4"
          >
            <path d="M10.707 2.293a1 1 0 00-1.414 0l-7 7a1 1 0 001.414 1.414L4 10.414V17a1 1 0 001 1h2a1 1 0 001-1v-2a1 1 0 011-1h2a1 1 0 011 1v2a1 1 0 001 1h2a1 1 0 001-1v-6.586l.293.293a1 1 0 001.414-1.414l-7-7z" />
          </svg>
          Dashboard
        </NavLink>

        <div className="mt-5 mb-2 px-3 text-xs font-semibold uppercase tracking-wider text-slate-600">
          Repositories
        </div>

        {isLoading && (
          <div className="space-y-2 px-3">
            {[0, 1, 2].map((i) => (
              <div
                key={i}
                className="h-4 animate-pulse rounded bg-slate-800/70"
              />
            ))}
          </div>
        )}

        {isError && (
          <p className="px-3 text-sm text-rose-400">
            Couldn't load repositories: {getErrorMessage(error)}
          </p>
        )}

        {!isLoading && !isError && repositories && repositories.length === 0 && (
          <p className="px-3 text-sm text-slate-600">No repositories yet.</p>
        )}

        {repositories?.map((repo) => (
          <NavLink
            key={repo.id}
            to={`/repositories/${repo.id}`}
            className={({ isActive }) =>
              cx(
                'flex items-center gap-2 truncate rounded-lg px-3 py-2 text-sm transition-colors',
                isActive
                  ? 'bg-brand-600/15 text-brand-300'
                  : 'text-slate-400 hover:bg-slate-900 hover:text-slate-100',
              )
            }
          >
            <span
              className={cx(
                'h-1.5 w-1.5 shrink-0 rounded-full',
                repo.status === 'INDEXED' && 'bg-emerald-500',
                repo.status === 'FAILED' && 'bg-rose-500',
                (repo.status === 'PENDING' || repo.status === 'INDEXING') &&
                  'bg-amber-500',
              )}
            />
            <span className="truncate">
              {repo.githubOwner}/{repo.githubRepo}
            </span>
          </NavLink>
        ))}
      </nav>

      <div className="border-t border-slate-800 px-3 py-3">
        <div className="flex items-center gap-3 rounded-lg px-2 py-2">
          <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-brand-600/20 text-xs font-semibold text-brand-300">
            {user ? initials(user.name) : '?'}
          </div>
          <div className="min-w-0 flex-1">
            <p className="truncate text-sm font-medium text-slate-200">
              {user?.name}
            </p>
            <p className="truncate text-xs text-slate-500">{user?.email}</p>
          </div>
          <button
            type="button"
            onClick={logout}
            title="Log out"
            className="rounded-md p-1.5 text-slate-500 hover:bg-slate-800 hover:text-slate-200"
          >
            <svg
              xmlns="http://www.w3.org/2000/svg"
              viewBox="0 0 20 20"
              fill="currentColor"
              className="h-4 w-4"
            >
              <path
                fillRule="evenodd"
                d="M3 4.25A2.25 2.25 0 015.25 2h5.5A2.25 2.25 0 0113 4.25v2a.75.75 0 01-1.5 0v-2a.75.75 0 00-.75-.75h-5.5a.75.75 0 00-.75.75v11.5c0 .414.336.75.75.75h5.5a.75.75 0 00.75-.75v-2a.75.75 0 011.5 0v2A2.25 2.25 0 0110.75 18h-5.5A2.25 2.25 0 013 15.75V4.25z"
                clipRule="evenodd"
              />
              <path
                fillRule="evenodd"
                d="M6 10a.75.75 0 01.75-.75h9.546l-1.048-.943a.75.75 0 111.004-1.114l2.5 2.25a.75.75 0 010 1.114l-2.5 2.25a.75.75 0 11-1.004-1.114l1.048-.943H6.75A.75.75 0 016 10z"
                clipRule="evenodd"
              />
            </svg>
          </button>
        </div>
      </div>
    </aside>
  )
}
