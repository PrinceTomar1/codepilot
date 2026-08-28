import { cx } from '../lib/utils'
import type { RepositoryStatus } from '../types'

const STYLES: Record<RepositoryStatus, string> = {
  PENDING: 'bg-amber-500/10 text-amber-400 ring-amber-500/30',
  INDEXING: 'bg-brand-500/10 text-brand-300 ring-brand-500/30',
  INDEXED: 'bg-emerald-500/10 text-emerald-400 ring-emerald-500/30',
  FAILED: 'bg-rose-500/10 text-rose-400 ring-rose-500/30',
}

const LABELS: Record<RepositoryStatus, string> = {
  PENDING: 'Pending',
  INDEXING: 'Indexing',
  INDEXED: 'Indexed',
  FAILED: 'Failed',
}

export default function StatusBadge({ status }: { status: RepositoryStatus }) {
  const isBusy = status === 'PENDING' || status === 'INDEXING'
  return (
    <span
      className={cx(
        'inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-medium ring-1 ring-inset',
        STYLES[status] ?? 'bg-slate-500/10 text-slate-400 ring-slate-500/30',
      )}
    >
      {isBusy ? (
        <span className="relative flex h-1.5 w-1.5">
          <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-current opacity-60" />
          <span className="relative inline-flex h-1.5 w-1.5 rounded-full bg-current" />
        </span>
      ) : (
        <span className="h-1.5 w-1.5 rounded-full bg-current" />
      )}
      {LABELS[status] ?? status}
    </span>
  )
}
