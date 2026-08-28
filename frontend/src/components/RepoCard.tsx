import { Link } from 'react-router-dom'
import StatusBadge from './StatusBadge'
import { formatRelativeDate } from '../lib/utils'
import type { Repository } from '../types'

export default function RepoCard({ repo }: { repo: Repository }) {
  return (
    <Link
      to={`/repositories/${repo.id}`}
      className="card group flex flex-col gap-4 p-5 transition-colors hover:border-brand-600/60 hover:bg-slate-900"
    >
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="truncate text-xs font-medium text-slate-500">
            {repo.githubOwner}
          </p>
          <h3 className="truncate text-base font-semibold text-slate-100 group-hover:text-brand-300">
            {repo.githubRepo}
          </h3>
        </div>
        <StatusBadge status={repo.status} />
      </div>

      <div className="mt-auto flex items-center justify-between text-xs text-slate-500">
        <span>Connected {formatRelativeDate(repo.createdAt)}</span>
        <span>
          {repo.status === 'INDEXED' && repo.indexedAt
            ? `Indexed ${formatRelativeDate(repo.indexedAt)}`
            : ' '}
        </span>
      </div>
    </Link>
  )
}
